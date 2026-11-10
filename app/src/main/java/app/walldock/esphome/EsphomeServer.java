package app.walldock.esphome;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Serveur de l'API native ESPHome (port 6053, sans chiffrement) : Home Assistant
 * s'y connecte comme à un module ESPHome et voit les entités de la tablette.
 *
 * Indépendant d'Android : il se teste sur un simple JVM.
 */
public final class EsphomeServer {

    /** Commandes reçues de Home Assistant. Appelé depuis le fil du client réseau. */
    public interface Handler {
        void onSwitch(Entity entity, boolean state);
        void onNumber(Entity entity, float value);
        void onButton(Entity entity);
    }

    /** Fiche de l'appareil telle que Home Assistant l'affiche. */
    public static final class DeviceInfo {
        public String name = "walldock";          // identifiant réseau, minuscules et tirets
        public String friendlyName = "WallDock";   // nom affiché dans HA
        public String macAddress = "02:00:00:00:00:00";
        public String model = "Android";
        public String manufacturer = "WallDock";
        public String projectVersion = "0.2.0";
        public String suggestedArea = "";
    }

    public static final int DEFAULT_PORT = 6053;

    // Types de messages (api.proto d'ESPHome)
    private static final int HELLO_REQUEST = 1;
    private static final int HELLO_RESPONSE = 2;
    private static final int AUTH_REQUEST = 3;
    private static final int AUTH_RESPONSE = 4;
    private static final int DISCONNECT_REQUEST = 5;
    private static final int DISCONNECT_RESPONSE = 6;
    private static final int PING_REQUEST = 7;
    private static final int PING_RESPONSE = 8;
    private static final int DEVICE_INFO_REQUEST = 9;
    private static final int DEVICE_INFO_RESPONSE = 10;
    private static final int LIST_ENTITIES_REQUEST = 11;
    private static final int LIST_BINARY_SENSOR = 12;
    private static final int LIST_SENSOR = 16;
    private static final int LIST_SWITCH = 17;
    private static final int LIST_TEXT_SENSOR = 18;
    private static final int LIST_ENTITIES_DONE = 19;
    private static final int SUBSCRIBE_STATES_REQUEST = 20;
    private static final int BINARY_SENSOR_STATE = 21;
    private static final int SENSOR_STATE = 25;
    private static final int SWITCH_STATE = 26;
    private static final int TEXT_SENSOR_STATE = 27;
    private static final int SWITCH_COMMAND = 33;
    private static final int LIST_NUMBER = 49;
    private static final int NUMBER_STATE = 50;
    private static final int NUMBER_COMMAND = 51;
    private static final int LIST_BUTTON = 61;
    private static final int BUTTON_COMMAND = 62;

    private static final int API_VERSION_MAJOR = 1;
    private static final int API_VERSION_MINOR = 10;

    private final DeviceInfo info;
    private final List<Entity> entities;
    private final Handler handler;
    private final int port;
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "esphome-sender");
        thread.setDaemon(true);
        return thread;
    });

    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    public EsphomeServer(DeviceInfo info, List<Entity> entities, Handler handler, int port) {
        this.info = info;
        this.entities = entities;
        this.handler = handler;
        this.port = port;
    }

    public void start() throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port));
        serverSocket = socket;
        running = true;
        Thread acceptThread = new Thread(this::acceptLoop, "esphome-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        for (Client client : clients) client.close();
        clients.clear();
        sender.shutdownNow();
    }

    public int clientCount() {
        return clients.size();
    }

    /**
     * Met à jour l'état d'une entité et le pousse aux clients abonnés si la valeur a changé.
     * Utilisable depuis n'importe quel fil : l'envoi réseau se fait sur un fil dédié
     * (Android interdit les accès réseau depuis le fil d'affichage).
     */
    public void setState(Entity entity, Object value) {
        Object previous = entity.state;
        entity.state = value;
        if (value != null && value.equals(previous)) return;
        sender.execute(() -> {
            for (Client client : clients) {
                if (client.subscribed) client.sendState(entity);
            }
        });
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                Client client = new Client(socket);
                clients.add(client);
                Thread thread = new Thread(client::run, "esphome-client");
                thread.setDaemon(true);
                thread.start();
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private final class Client {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        volatile boolean subscribed;

        Client(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
        }

        void run() {
            try {
                while (running) {
                    int preamble = in.read();
                    if (preamble < 0) break;
                    if (preamble != 0) break; // 0x01 = connexion chiffrée (Noise) : non gérée
                    int length = (int) readVarint();
                    int type = (int) readVarint();
                    byte[] payload = readFully(length);
                    if (!handle(type, payload)) break;
                }
            } catch (IOException ignored) {
            } finally {
                close();
            }
        }

        /** Renvoie faux pour fermer la connexion. */
        private boolean handle(int type, byte[] payload) throws IOException {
            switch (type) {
                case HELLO_REQUEST:
                    send(HELLO_RESPONSE, new Proto.Writer()
                        .varint(1, API_VERSION_MAJOR)
                        .varint(2, API_VERSION_MINOR)
                        .string(3, "WallDock " + info.projectVersion)
                        .string(4, info.name)
                        .bytes());
                    return true;
                case AUTH_REQUEST:
                    send(AUTH_RESPONSE, new byte[0]); // pas de mot de passe
                    return true;
                case DISCONNECT_REQUEST:
                    send(DISCONNECT_RESPONSE, new byte[0]);
                    return false;
                case DISCONNECT_RESPONSE:
                    return false;
                case PING_REQUEST:
                    send(PING_RESPONSE, new byte[0]);
                    return true;
                case DEVICE_INFO_REQUEST:
                    send(DEVICE_INFO_RESPONSE, deviceInfo());
                    return true;
                case LIST_ENTITIES_REQUEST:
                    for (Entity entity : entities) send(listType(entity), describe(entity));
                    send(LIST_ENTITIES_DONE, new byte[0]);
                    return true;
                case SUBSCRIBE_STATES_REQUEST:
                    subscribed = true;
                    for (Entity entity : entities) sendState(entity);
                    return true;
                case SWITCH_COMMAND: {
                    Map<Integer, Object> f = Proto.read(payload);
                    Entity entity = find(Proto.getInt(f, 1), Entity.Kind.SWITCH);
                    if (entity != null && handler != null) handler.onSwitch(entity, Proto.getBool(f, 2));
                    return true;
                }
                case NUMBER_COMMAND: {
                    Map<Integer, Object> f = Proto.read(payload);
                    Entity entity = find(Proto.getInt(f, 1), Entity.Kind.NUMBER);
                    if (entity != null && handler != null) handler.onNumber(entity, Proto.getFloat(f, 2));
                    return true;
                }
                case BUTTON_COMMAND: {
                    Map<Integer, Object> f = Proto.read(payload);
                    Entity entity = find(Proto.getInt(f, 1), Entity.Kind.BUTTON);
                    if (entity != null && handler != null) handler.onButton(entity);
                    return true;
                }
                default:
                    // Abonnements aux journaux, services HA, états HA, heure… : ignorés sans erreur
                    return true;
            }
        }

        private byte[] deviceInfo() {
            return new Proto.Writer()
                .bool(1, false)
                .string(2, info.name)
                .string(3, info.macAddress)
                .string(4, "2025.10.0")
                .string(5, "WallDock " + info.projectVersion)
                .string(6, info.model)
                .string(8, "walldock.walldock")
                .string(9, info.projectVersion)
                .string(12, info.manufacturer)
                .string(13, info.friendlyName)
                .string(16, info.suggestedArea)
                .bytes();
        }

        private Entity find(int key, Entity.Kind kind) {
            for (Entity entity : entities) if (entity.key == key && entity.kind == kind) return entity;
            return null;
        }

        void sendState(Entity entity) {
            Object state = entity.state;
            try {
                switch (entity.kind) {
                    case SENSOR: {
                        Proto.Writer w = new Proto.Writer().fixed32(1, entity.key);
                        if (state instanceof Float) w.floatValue(2, (Float) state); else w.bool(3, true);
                        send(SENSOR_STATE, w.bytes());
                        break;
                    }
                    case BINARY_SENSOR: {
                        Proto.Writer w = new Proto.Writer().fixed32(1, entity.key);
                        if (state instanceof Boolean) w.bool(2, (Boolean) state); else w.bool(3, true);
                        send(BINARY_SENSOR_STATE, w.bytes());
                        break;
                    }
                    case SWITCH:
                        send(SWITCH_STATE, new Proto.Writer()
                            .fixed32(1, entity.key)
                            .bool(2, Boolean.TRUE.equals(state))
                            .bytes());
                        break;
                    case NUMBER: {
                        Proto.Writer w = new Proto.Writer().fixed32(1, entity.key);
                        if (state instanceof Float) w.floatValue(2, (Float) state); else w.bool(3, true);
                        send(NUMBER_STATE, w.bytes());
                        break;
                    }
                    case TEXT_SENSOR: {
                        Proto.Writer w = new Proto.Writer().fixed32(1, entity.key);
                        if (state instanceof String) w.string(2, (String) state); else w.bool(3, true);
                        send(TEXT_SENSOR_STATE, w.bytes());
                        break;
                    }
                    case BUTTON:
                        break; // un bouton n'a pas d'état
                }
            } catch (IOException e) {
                close();
            }
        }

        private int listType(Entity entity) {
            switch (entity.kind) {
                case SENSOR: return LIST_SENSOR;
                case BINARY_SENSOR: return LIST_BINARY_SENSOR;
                case SWITCH: return LIST_SWITCH;
                case NUMBER: return LIST_NUMBER;
                case BUTTON: return LIST_BUTTON;
                default: return LIST_TEXT_SENSOR;
            }
        }

        private byte[] describe(Entity e) {
            Proto.Writer w = new Proto.Writer().string(1, e.objectId).fixed32(2, e.key).string(3, e.name);
            switch (e.kind) {
                case SENSOR:
                    return w.string(5, e.icon).string(6, e.unit).varint(7, e.accuracyDecimals)
                        .string(9, e.deviceClass).varint(10, e.stateClass).varint(13, e.entityCategory).bytes();
                case BINARY_SENSOR:
                    return w.string(5, e.deviceClass).string(8, e.icon).varint(9, e.entityCategory).bytes();
                case SWITCH:
                    return w.string(5, e.icon).varint(8, e.entityCategory).string(9, e.deviceClass).bytes();
                case NUMBER:
                    return w.string(5, e.icon).floatValue(6, e.min).floatValue(7, e.max).floatValue(8, e.step)
                        .varint(10, e.entityCategory).string(11, e.unit).varint(12, e.numberMode)
                        .string(13, e.deviceClass).bytes();
                case BUTTON:
                    return w.string(5, e.icon).varint(7, e.entityCategory).string(8, e.deviceClass).bytes();
                default: // TEXT_SENSOR
                    return w.string(5, e.icon).varint(7, e.entityCategory).string(8, e.deviceClass).bytes();
            }
        }

        private void send(int type, byte[] payload) throws IOException {
            ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 8);
            frame.write(0);
            Proto.writeVarint(frame, payload.length);
            Proto.writeVarint(frame, type);
            frame.write(payload, 0, payload.length);
            synchronized (out) {
                out.write(frame.toByteArray());
                out.flush();
            }
        }

        private long readVarint() throws IOException {
            long result = 0;
            int shift = 0;
            while (true) {
                int b = in.read();
                if (b < 0) throw new EOFException();
                result |= ((long) (b & 0x7F)) << shift;
                if ((b & 0x80) == 0) return result;
                shift += 7;
            }
        }

        private byte[] readFully(int length) throws IOException {
            byte[] data = new byte[length];
            int read = 0;
            while (read < length) {
                int n = in.read(data, read, length - read);
                if (n < 0) throw new EOFException();
                read += n;
            }
            return data;
        }

        void close() {
            clients.remove(this);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
