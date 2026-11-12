package app.walldock.sendspin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Client Sendspin (rôle « player@v1 ») : lecteur synchronisé de Music Assistant.
 *
 * Music Assistant se connecte à la tablette (annonce mDNS « _sendspin._tcp » faite par l'appli),
 * par WebSocket sur le port {@link #PORT}, chemin « /sendspin ». Chiffrement Noise KKpsk2
 * (AES-GCM) avec la clé publique « Sentinel » : accès non appairé, accepté dans Music Assistant.
 * Horloge synchronisée par le filtre de temps, son PCM horodaté transmis au {@link Player}.
 */
public final class SendspinClient {

    public static final int PORT = 8928;
    public static final String PATH = "/sendspin";
    private static final String SUITE = "25519_AESGCM_SHA256";
    private static final byte[] SENTINEL_PSK = NoiseKK.sha256("sendspin-sentinel-psk-v1".getBytes(StandardCharsets.UTF_8));

    /** Sortie audio (implémentée côté Android par AudioTrack). */
    public interface Player {
        /** Nouveau flux PCM (entiers signés little-endian). */
        void start(int sampleRate, int channels, int bitDepth);
        /** Morceau de son à jouer quand l'horloge locale atteint {@code localTimeUs}. */
        void chunk(long localTimeUs, byte[] pcm, int offset, int length);
        /** Vide les morceaux en attente (à partir de {@code fromLocalUs}, ou tous si négatif). */
        void clear(long fromLocalUs);
        void end();
        void setVolume(int volume, boolean muted);
    }

    /** Événements pour l'appli (état, nom du serveur). */
    public interface Listener {
        void onStatus(String status);
    }

    private final byte[] staticPriv;
    private final byte[] staticPub;
    private final String clientId;
    private final String name;
    private final Player player;
    private final Listener listener;
    private final String deviceModel;
    private final String softwareVersion;
    private final TimeFilter filter = new TimeFilter();

    private volatile ServerSocket serverSocket;
    private volatile Connection current;
    private volatile int volume;
    private volatile boolean muted;

    public SendspinClient(byte[] staticPriv, String name, String deviceModel, String softwareVersion,
                          int volume, boolean muted, Player player, Listener listener) {
        this.staticPriv = staticPriv;
        this.staticPub = NoiseKK.publicKey(staticPriv);
        this.clientId = Base64.getUrlEncoder().withoutPadding().encodeToString(staticPub);
        this.name = name;
        this.deviceModel = deviceModel;
        this.softwareVersion = softwareVersion;
        this.volume = volume;
        this.muted = muted;
        this.player = player;
        this.listener = listener;
    }

    public String clientId() {
        return clientId;
    }

    public static long nowUs() {
        return System.nanoTime() / 1000;
    }

    // ---- Serveur WebSocket ----

    public void start(int port) throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(port));
        serverSocket = s;
        Thread t = new Thread(() -> acceptLoop(s), "sendspin-accept");
        t.setDaemon(true);
        t.start();
        listener.onStatus("waiting");
    }

    public void stop() {
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        Connection c = current;
        if (c != null) c.close();
        player.end();
    }

    /** Volume changé sur la tablette : prévenir le serveur. */
    public void localVolume(int newVolume, boolean newMuted) {
        volume = newVolume;
        muted = newMuted;
        player.setVolume(volume, muted);
        Connection c = current;
        if (c != null) c.sendState();
    }

    private void acceptLoop(ServerSocket s) {
        while (!s.isClosed()) {
            try {
                Socket socket = s.accept();
                Thread t = new Thread(() -> {
                    Connection c = new Connection(socket);
                    c.run();
                }, "sendspin-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private final class Connection {
        private final Socket socket;
        private DataInputStream in;
        private OutputStream out;
        private NoiseKK.Cipher1 sender;
        private NoiseKK.Cipher1 receiver;
        private volatile boolean closed;
        private volatile boolean activated;
        private volatile boolean playerActive;
        private volatile boolean stateSent;
        // Synchronisation d'horloge (rafales de 8 mesures)
        private final Object timeLock = new Object();
        private long burstBestError = Long.MAX_VALUE;
        private long[] burstBest;
        private int burstCount;
        private long lastTimeSent;
        // Format du flux en cours
        private int bytesPerFrame = 4;
        private int sampleRate = 48000;
        // Fragmentation
        private ByteArrayOutputStream fragment;
        private int fragmentType = -1;

        Connection(Socket socket) {
            this.socket = socket;
        }

        void run() {
            try {
                socket.setTcpNoDelay(true);
                in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                out = socket.getOutputStream();
                if (!upgrade()) {
                    socket.close();
                    return;
                }
                // Une seule connexion à la fois : la nouvelle remplace l'ancienne
                Connection old = current;
                current = this;
                if (old != null) old.close();
                handshake();
                listener.onStatus("connected");
                Thread timer = new Thread(this::timeLoop, "sendspin-time");
                timer.setDaemon(true);
                timer.start();
                while (!closed) {
                    WsMessage m = readMessage();
                    if (m == null) break;
                    if (m.binary) onBinary(m.data);
                }
            } catch (Exception e) {
                // fin de connexion
            } finally {
                close();
                if (current == this) {
                    current = null;
                    player.end();
                    listener.onStatus("waiting");
                }
            }
        }

        void close() {
            if (closed) return;
            closed = true;
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        // ---- Poignée de main HTTP → WebSocket ----

        private boolean upgrade() throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int last4 = 0;
            while (true) {
                int b = in.read();
                if (b < 0) return false;
                head.write(b);
                last4 = (last4 << 8) | b;
                if (last4 == 0x0D0A0D0A) break;
                if (head.size() > 16384) return false;
            }
            String[] lines = head.toString("UTF-8").split("\r\n");
            String[] request = lines[0].split(" ");
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int c = lines[i].indexOf(':');
                if (c > 0) headers.put(lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT), lines[i].substring(c + 1).trim());
            }
            String key = headers.get("sec-websocket-key");
            boolean ok = request.length >= 2 && request[1].split("\\?")[0].equals(PATH) && key != null && !headers.containsKey("origin");
            if (!ok) {
                out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                return false;
            }
            String accept;
            try {
                accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
            } catch (Exception e) {
                return false;
            }
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            return true;
        }

        // ---- Noise ----

        private void handshake() throws Exception {
            String clientInit = new JSONObject()
                .put("type", "client/init")
                .put("payload", new JSONObject().put("client_id", clientId).put("version", 1).put("suite", SUITE))
                .toString();
            byte[] clientInitBytes = clientInit.getBytes(StandardCharsets.UTF_8);
            writeFrame(1, clientInitBytes);

            WsMessage serverInitMsg = readMessage();
            if (serverInitMsg == null || serverInitMsg.binary) throw new IOException("server/init attendu");
            JSONObject serverInit = new JSONObject(new String(serverInitMsg.data, StandardCharsets.UTF_8));
            if (!"server/init".equals(serverInit.optString("type"))) throw new IOException("server/init attendu");
            JSONObject sp = serverInit.getJSONObject("payload");
            if (sp.optInt("version") != 1) throw new IOException("version");
            byte[] serverStatic = Base64.getUrlDecoder().decode(sp.getString("server_id"));

            byte[] prologue = new byte[clientInitBytes.length + serverInitMsg.data.length];
            System.arraycopy(clientInitBytes, 0, prologue, 0, clientInitBytes.length);
            System.arraycopy(serverInitMsg.data, 0, prologue, clientInitBytes.length, serverInitMsg.data.length);
            NoiseKK noise = new NoiseKK(staticPriv, staticPub, serverStatic, prologue);

            WsMessage m1 = readMessage();
            if (m1 == null || m1.binary) throw new IOException("noise 1 attendu");
            JSONObject h1 = new JSONObject(new String(m1.data, StandardCharsets.UTF_8));
            byte[] message1 = Base64.getUrlDecoder().decode(h1.getJSONObject("payload").getString("data"));
            noise.readMessage1(message1);
            // Pas de clé d'appairage : on répond toujours avec la clé « Sentinel » (accès non appairé)
            byte[] message2 = noise.writeMessage2(SENTINEL_PSK, "{}".getBytes(StandardCharsets.UTF_8));
            String h2 = new JSONObject().put("type", "noise/handshake")
                .put("payload", new JSONObject().put("data", Base64.getUrlEncoder().withoutPadding().encodeToString(message2)))
                .toString();
            writeFrame(1, h2.getBytes(StandardCharsets.UTF_8));
            sender = noise.sender;
            receiver = noise.receiver;
        }

        // ---- Messages chiffrés ----

        private void sendJson(String type, JSONObject payload) {
            try {
                byte[] json = new JSONObject().put("type", type).put("payload", payload).toString().getBytes(StandardCharsets.UTF_8);
                byte[] plain = new byte[json.length + 1];
                plain[0] = 0;
                System.arraycopy(json, 0, plain, 1, json.length);
                synchronized (this) {
                    writeFrame(2, sender.encrypt(plain));
                }
            } catch (Exception e) {
                close();
            }
        }

        private void onBinary(byte[] cipher) throws Exception {
            long arrival = nowUs();
            byte[] plain = receiver.decrypt(cipher);
            if (plain.length == 0) throw new IOException("message vide");
            int type = plain[0] & 0xFF;
            if (type == 1) {
                // Fragment : [1][flags][orig_type?][data]
                int flags = plain[1] & 0xFF;
                boolean first = (flags & 2) != 0, last = (flags & 1) != 0;
                int off = 2;
                if (first) {
                    fragmentType = plain[2] & 0xFF;
                    fragment = new ByteArrayOutputStream();
                    off = 3;
                }
                if (fragment == null) throw new IOException("fragment orphelin");
                fragment.write(plain, off, plain.length - off);
                if (last) {
                    byte[] data = fragment.toByteArray();
                    byte[] whole = new byte[data.length + 1];
                    whole[0] = (byte) fragmentType;
                    System.arraycopy(data, 0, whole, 1, data.length);
                    fragment = null;
                    dispatch(whole, arrival);
                }
                return;
            }
            dispatch(plain, arrival);
        }

        private void dispatch(byte[] plain, long arrival) throws Exception {
            int type = plain[0] & 0xFF;
            if (type == 0) {
                JSONObject m = new JSONObject(new String(plain, 1, plain.length - 1, StandardCharsets.UTF_8));
                onJson(m.optString("type"), m.optJSONObject("payload"), arrival);
            } else if (type == 4) {
                if (plain.length < 13) throw new IOException("morceau audio trop court");
                if (!playerActive) return;
                long ts = 0;
                for (int i = 1; i <= 8; i++) ts = (ts << 8) | (plain[i] & 0xFF);
                long local = filter.clientTime(ts);
                player.chunk(local, plain, 13, plain.length - 13);
            }
        }

        private void onJson(String type, JSONObject p, long arrival) {
            if (p == null) p = new JSONObject();
            switch (type) {
                case "server/hello":
                    sendHello();
                    break;
                case "server/activate": {
                    JSONArray roles = p.optJSONArray("active_roles");
                    if (roles != null) {
                        boolean active = false;
                        for (int i = 0; i < roles.length(); i++) if (roles.optString(i).startsWith("player@")) active = true;
                        playerActive = active;
                    }
                    JSONArray activities = p.optJSONArray("activities");
                    boolean pairing = false;
                    if (activities != null) for (int i = 0; i < activities.length(); i++) if ("pairing".equals(activities.optString(i))) pairing = true;
                    if (pairing) {
                        // Pas de méthode d'appairage proposée
                        try {
                            sendJson("pair/abort", new JSONObject().put("reason", "method_not_supported"));
                        } catch (Exception ignored) {
                        }
                    }
                    activated = true;
                    synchronized (timeLock) {
                        timeLock.notifyAll();
                    }
                    // Rôle (ré)activé : renvoyer l'état du lecteur (si l'horloge est déjà synchronisée)
                    if (playerActive) sendState();
                    break;
                }
                case "server/time": {
                    long t1 = p.optLong("client_transmitted");
                    long[] m = TimeFilter.measure(t1, p.optLong("server_received"), p.optLong("server_transmitted"), arrival);
                    synchronized (timeLock) {
                        if (m[1] > 0 && m[1] < burstBestError) {
                            burstBestError = m[1];
                            burstBest = m;
                        }
                        burstCount++;
                        timeLock.notifyAll();
                    }
                    break;
                }
                case "server/command": {
                    JSONObject cmd = p.optJSONObject("player");
                    if (cmd == null) break;
                    String c = cmd.optString("command");
                    if ("volume".equals(c) && cmd.has("volume")) volume = Math.max(0, Math.min(100, cmd.optInt("volume")));
                    else if ("mute".equals(c) && cmd.has("mute")) muted = cmd.optBoolean("mute");
                    else break;
                    player.setVolume(volume, muted);
                    sendState();
                    break;
                }
                case "stream/start": {
                    JSONObject f = p.optJSONObject("player");
                    if (f == null) break;
                    int channels = f.optInt("channels", 2);
                    int bits = f.optInt("bit_depth", 16);
                    sampleRate = f.optInt("sample_rate", 48000);
                    bytesPerFrame = channels * bits / 8;
                    player.start(sampleRate, channels, bits);
                    break;
                }
                case "stream/clear": {
                    if (!includesPlayer(p)) break;
                    if (p.has("from_timestamp")) player.clear(filter.clientTime(p.optLong("from_timestamp")));
                    else player.clear(-1);
                    break;
                }
                case "stream/end":
                    if (includesPlayer(p)) player.end();
                    break;
                default:
                    break;
            }
        }

        private boolean includesPlayer(JSONObject p) {
            JSONArray roles = p.optJSONArray("roles");
            if (roles == null) return true;
            for (int i = 0; i < roles.length(); i++) if (roles.optString(i).startsWith("player")) return true;
            return false;
        }

        private void sendHello() {
            try {
                JSONArray formats = new JSONArray();
                for (int rate : new int[]{48000, 44100}) {
                    formats.put(new JSONObject().put("codec", "pcm").put("channels", 2).put("sample_rate", rate).put("bit_depth", 16));
                }
                JSONObject payload = new JSONObject()
                    .put("name", name.length() > 60 ? name.substring(0, 60) : name)
                    .put("device_info", new JSONObject()
                        .put("product_name", deviceModel.length() > 60 ? deviceModel.substring(0, 60) : deviceModel)
                        .put("manufacturer", "WallDock")
                        .put("software_version", softwareVersion))
                    .put("supported_roles", new JSONArray().put("player@v1"))
                    .put("player@v1_support", new JSONObject()
                        .put("supported_formats", formats)
                        .put("buffer_capacity", 2_000_000))
                    .put("supported_pair_methods", new JSONObject().put("pairing_psk", new JSONObject()))
                    .put("unpaired_access", new JSONObject().put("enabled", true));
                sendJson("client/hello", payload);
            } catch (Exception e) {
                close();
            }
        }

        void sendState() {
            if (!activated || filter.count() < 1) return;
            try {
                JSONObject playerState = new JSONObject()
                    .put("volume", volume)
                    .put("muted", muted)
                    .put("output_delay_ms", 0)
                    .put("required_lead_time_ms", 400)
                    .put("min_buffer_ms", 300)
                    .put("supported_commands", new JSONArray().put("volume").put("mute"));
                sendJson("client/state", new JSONObject().put("available", true).put("player", playerState));
                stateSent = true;
            } catch (Exception ignored) {
            }
        }

        /** Rafales de 8 mesures d'horloge, toutes les 5 secondes (plus vite au début). */
        private void timeLoop() {
            try {
                synchronized (timeLock) {
                    while (!activated && !closed) timeLock.wait(500);
                }
                int bursts = 0;
                while (!closed) {
                    synchronized (timeLock) {
                        burstBestError = Long.MAX_VALUE;
                        burstBest = null;
                        burstCount = 0;
                    }
                    for (int i = 0; i < 8 && !closed; i++) {
                        int before;
                        synchronized (timeLock) {
                            before = burstCount;
                        }
                        lastTimeSent = nowUs();
                        sendJson("client/time", new JSONObject().put("client_transmitted", lastTimeSent));
                        synchronized (timeLock) {
                            long deadline = System.currentTimeMillis() + 500;
                            while (burstCount == before && !closed && System.currentTimeMillis() < deadline) timeLock.wait(50);
                        }
                    }
                    long[] best;
                    synchronized (timeLock) {
                        best = burstBest;
                    }
                    if (best != null) filter.update(best[0], best[1], best[2]);
                    if (!stateSent && filter.count() >= 1) sendState();
                    bursts++;
                    Thread.sleep(bursts < 5 ? 1_000 : 5_000);
                }
            } catch (Exception ignored) {
                // connexion fermée
            }
        }

        // ---- Trames WebSocket ----

        private final class WsMessage {
            final boolean binary;
            final byte[] data;

            WsMessage(boolean binary, byte[] data) {
                this.binary = binary;
                this.data = data;
            }
        }

        private WsMessage readMessage() throws IOException {
            ByteArrayOutputStream buffer = null;
            int messageOpcode = 0;
            while (true) {
                int b0 = in.read();
                if (b0 < 0) return null;
                int b1 = in.readUnsignedByte();
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) len = in.readUnsignedShort();
                else if (len == 127) len = in.readLong();
                if (len > 8_000_000) throw new IOException("trame trop grande");
                byte[] mask = new byte[4];
                if (masked) in.readFully(mask);
                byte[] data = new byte[(int) len];
                in.readFully(data);
                if (masked) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
                switch (opcode) {
                    case 8: // fermeture
                        try {
                            writeFrame(8, new byte[0]);
                        } catch (IOException ignored) {
                        }
                        return null;
                    case 9: // ping
                        writeFrame(10, data);
                        continue;
                    case 10: // pong
                        continue;
                    case 0: // suite
                        if (buffer == null) throw new IOException("suite inattendue");
                        buffer.write(data);
                        break;
                    default:
                        messageOpcode = opcode;
                        buffer = new ByteArrayOutputStream();
                        buffer.write(data);
                }
                if (fin) return new WsMessage(messageOpcode == 2, buffer.toByteArray());
            }
        }

        private void writeFrame(int opcode, byte[] data) throws IOException {
            ByteArrayOutputStream f = new ByteArrayOutputStream(data.length + 10);
            f.write(0x80 | opcode);
            if (data.length < 126) {
                f.write(data.length);
            } else if (data.length < 65536) {
                f.write(126);
                f.write(data.length >>> 8);
                f.write(data.length & 0xFF);
            } else {
                f.write(127);
                for (int i = 7; i >= 0; i--) f.write((int) ((long) data.length >>> (8 * i)) & 0xFF);
            }
            f.write(data);
            synchronized (out) {
                out.write(f.toByteArray());
                out.flush();
            }
        }
    }

    @SuppressWarnings("unused")
    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    @SuppressWarnings("unused")
    private static int readFully(InputStream in, byte[] b) throws IOException {
        int r = 0;
        while (r < b.length) {
            int n = in.read(b, r, b.length - r);
            if (n < 0) break;
            r += n;
        }
        return r;
    }
}
