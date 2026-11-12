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
import java.util.HashMap;
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
        void onSelect(Entity entity, String option);
        /** Action appelée depuis HA ; [args] associe le nom de chaque paramètre à sa valeur. */
        void onService(UserService service, Map<String, Object> args);
        /**
         * Commande du lecteur multimédia : command (MEDIA_COMMAND_*), volume (0–1) et url
         * valent null quand HA ne les envoie pas.
         */
        default void onMediaPlayer(Entity entity, Integer command, Float volume, String url, boolean announcement) {}
        /** HA demande une image d'une entité caméra : répondre avec sendCameraImage(). */
        default void onCameraRequest(Entity entity) {}
        /** Nouvelle valeur d'une entité texte, saisie dans HA. */
        default void onText(Entity entity, String value) {}
        /** Réponse de HA à requestHomeAssistantState() (état ou attribut d'une entité HA). */
        default void onHomeAssistantState(String entityId, String attribute, String state) {}
    }

    // Commandes du lecteur multimédia (MediaPlayerCommand)
    public static final int MEDIA_COMMAND_PLAY = 0;
    public static final int MEDIA_COMMAND_PAUSE = 1;
    public static final int MEDIA_COMMAND_STOP = 2;
    public static final int MEDIA_COMMAND_MUTE = 3;
    public static final int MEDIA_COMMAND_UNMUTE = 4;
    public static final int MEDIA_COMMAND_TOGGLE = 5;
    public static final int MEDIA_COMMAND_VOLUME_UP = 6;
    public static final int MEDIA_COMMAND_VOLUME_DOWN = 7;

    // Fonctions annoncées à HA (MediaPlayerEntityFeature)
    private static final int MEDIA_FEATURES = 1 /* PAUSE */ | 4 /* VOLUME_SET */ | 8 /* VOLUME_MUTE */
        | 512 /* PLAY_MEDIA */ | 1024 /* VOLUME_STEP */ | 4096 /* STOP */ | 16384 /* PLAY */
        | 131072 /* BROWSE_MEDIA */ | 1048576 /* MEDIA_ANNOUNCE */;

    /** Événements de l'assistant vocal envoyés par Home Assistant (fil du client réseau). */
    public interface VoiceHandler {
        /** HA s'abonne (ou se désabonne) à l'assistant vocal de l'appareil. */
        void onVoiceSubscribed(boolean subscribed);
        /** Réponse de HA à une demande de démarrage : erreur = le pipeline n'a pas pu démarrer. */
        void onVoiceResponse(boolean error);
        /** Étape du pipeline (VOICE_EVENT_*) avec ses données (texte reconnu, adresse du son, etc.). */
        void onVoiceEvent(int eventType, Map<String, String> data);
        /** Annonce à lire (assist_satellite.announce / start_conversation). */
        void onAnnounce(String mediaId, String text, String preannounceMediaId, boolean startConversation);
        /** HA demande d'arrêter le pipeline en cours. */
        void onVoiceStopRequested();
        /** Minuteur vocal : démarré (0), mis à jour (1), annulé (2) ou terminé (3). */
        void onTimerEvent(int eventType, String timerId, String name, int totalSeconds, int secondsLeft, boolean active);
    }

    // Fonctions d'assistant vocal annoncées à HA (DeviceInfo, champ 17)
    public static final int VOICE_FEATURE_VOICE_ASSISTANT = 1;
    public static final int VOICE_FEATURE_API_AUDIO = 1 << 2;
    public static final int VOICE_FEATURE_TIMERS = 1 << 3;
    public static final int VOICE_FEATURE_ANNOUNCE = 1 << 4;
    public static final int VOICE_FEATURE_START_CONVERSATION = 1 << 5;

    // Drapeaux d'une demande de pipeline
    public static final int VOICE_FLAG_USE_VAD = 1;
    public static final int VOICE_FLAG_USE_WAKE_WORD = 2;

    // Étapes du pipeline (VoiceAssistantEvent)
    public static final int VOICE_EVENT_ERROR = 0;
    public static final int VOICE_EVENT_RUN_START = 1;
    public static final int VOICE_EVENT_RUN_END = 2;
    public static final int VOICE_EVENT_STT_START = 3;
    public static final int VOICE_EVENT_STT_END = 4;
    public static final int VOICE_EVENT_INTENT_START = 5;
    public static final int VOICE_EVENT_INTENT_END = 6;
    public static final int VOICE_EVENT_TTS_START = 7;
    public static final int VOICE_EVENT_TTS_END = 8;
    public static final int VOICE_EVENT_WAKE_WORD_START = 9;
    public static final int VOICE_EVENT_WAKE_WORD_END = 10;
    public static final int VOICE_EVENT_STT_VAD_START = 11;
    public static final int VOICE_EVENT_STT_VAD_END = 12;

    /** Fiche de l'appareil telle que Home Assistant l'affiche. */
    public static final class DeviceInfo {
        public String name = "walldock";          // identifiant réseau, minuscules et tirets
        public String friendlyName = "WallDock";   // nom affiché dans HA
        public String macAddress = "02:00:00:00:00:00";
        public String model = "Android";
        public String manufacturer = "WallDock";
        public String projectVersion = "0.2.0";
        public String suggestedArea = "";
        /** Fonctions vocales (VOICE_FEATURE_*), 0 = pas d'assistant vocal. */
        public int voiceFeatures = 0;
    }

    /** Où l'appareil garde sa clé de chiffrement (32 octets, ou null : pas de chiffrement). */
    public interface KeyStore {
        byte[] loadKey();
        void saveKey(byte[] key);
    }

    private static final byte[] ZERO_PSK = new byte[32];
    private volatile KeyStore keyStore;
    private volatile byte[] noiseKey;

    /** Active le chiffrement Noise : la clé est lue maintenant, et HA peut la (re)définir. */
    public void setKeyStore(KeyStore store) {
        keyStore = store;
        byte[] key = store != null ? store.loadKey() : null;
        noiseKey = key != null && key.length == 32 ? key : null;
    }

    /** Vrai si une clé est en place : seules les connexions chiffrées avec elle sont acceptées. */
    public boolean isEncrypted() {
        return noiseKey != null;
    }

    /** Nom du protocole, pour l'annonce mDNS (api_encryption). */
    public static final String NOISE_PROTOCOL = NoiseSession.PROTOCOL;

    /** Nouvelle clé (ou null pour revenir sans chiffrement) : les clients sont déconnectés. */
    public void changeKey(byte[] key) {
        KeyStore store = keyStore;
        if (store != null) store.saveKey(key);
        noiseKey = key;
        for (Client client : clients) {
            client.trySend(DISCONNECT_REQUEST, new byte[0]);
        }
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
    private static final int LIST_SERVICES = 41;
    private static final int EXECUTE_SERVICE = 42;
    private static final int LIST_SELECT = 52;
    private static final int SELECT_STATE = 53;
    private static final int SELECT_COMMAND = 54;
    private static final int LIST_MEDIA_PLAYER = 63;
    private static final int LIST_CAMERA = 43;
    private static final int LIST_TEXT = 97;
    private static final int NOISE_SET_KEY_REQUEST = 124;
    private static final int NOISE_SET_KEY_RESPONSE = 125;
    private static final int TEXT_STATE = 98;
    private static final int TEXT_COMMAND = 99;
    private static final int CAMERA_IMAGE = 44;
    private static final int CAMERA_IMAGE_REQUEST = 45;
    private static final int SUBSCRIBE_HA_STATES = 38;
    private static final int HA_STATE_SUBSCRIPTION = 39;
    private static final int HA_STATE = 40;
    private static final int MEDIA_PLAYER_STATE = 64;
    private static final int MEDIA_PLAYER_COMMAND = 65;
    private static final int SUBSCRIBE_VOICE_ASSISTANT = 89;
    private static final int VOICE_ASSISTANT_REQUEST = 90;
    private static final int VOICE_ASSISTANT_RESPONSE = 91;
    private static final int VOICE_ASSISTANT_EVENT = 92;
    private static final int VOICE_ASSISTANT_AUDIO = 106;
    private static final int VOICE_ASSISTANT_TIMER_EVENT = 115;
    private static final int VOICE_ASSISTANT_ANNOUNCE_REQUEST = 119;
    private static final int VOICE_ASSISTANT_ANNOUNCE_FINISHED = 120;
    private static final int VOICE_ASSISTANT_CONFIGURATION_REQUEST = 121;
    private static final int VOICE_ASSISTANT_CONFIGURATION_RESPONSE = 122;
    private static final int VOICE_ASSISTANT_SET_CONFIGURATION = 123;

    private static final int API_VERSION_MAJOR = 1;
    private static final int API_VERSION_MINOR = 10;

    private final DeviceInfo info;
    private final List<Entity> entities;
    private final List<UserService> services;
    private final Handler handler;
    private final int port;
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "esphome-sender");
        thread.setDaemon(true);
        return thread;
    });

    private volatile VoiceHandler voiceHandler;
    /** Connexion HA abonnée à l'assistant vocal (une seule à la fois). */
    private volatile Client voiceClient;

    private volatile ServerSocket serverSocket;
    private volatile boolean running;

    public EsphomeServer(DeviceInfo info, List<Entity> entities, List<UserService> services, Handler handler, int port) {
        this.info = info;
        this.entities = entities;
        this.services = services;
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
     * Ferme les connexions : Home Assistant se reconnecte aussitôt et relit la liste
     * des entités (utile quand les choix d'une liste ont changé).
     */
    public void reconnectClients() {
        sender.execute(() -> {
            for (Client client : clients) client.close();
        });
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

    public void setVoiceHandler(VoiceHandler handler) {
        this.voiceHandler = handler;
    }

    /** Vrai quand HA écoute l'assistant vocal de l'appareil. */
    public boolean isVoiceSubscribed() {
        return voiceClient != null;
    }

    /**
     * Demande à HA de lancer un pipeline (fil quelconque, réseau sur le fil d'envoi).
     * flags : VOICE_FLAG_USE_WAKE_WORD pour commencer par la détection du mot d'activation.
     */
    public void voiceStart(String conversationId, int flags, String wakeWordPhrase) {
        sender.execute(() -> {
            Client client = voiceClient;
            if (client == null) return;
            byte[] audioSettings = new Proto.Writer().floatValue(3, 1.0f).bytes();
            client.trySend(VOICE_ASSISTANT_REQUEST, new Proto.Writer()
                .bool(1, true)
                .string(2, conversationId)
                .varint(3, flags)
                .message(4, audioSettings)
                .string(5, wakeWordPhrase)
                .bytes());
        });
    }

    /** Demande à HA d'arrêter le pipeline en cours. */
    public void voiceStop() {
        sender.execute(() -> {
            Client client = voiceClient;
            if (client != null) client.trySend(VOICE_ASSISTANT_REQUEST, new Proto.Writer().bool(1, false).bytes());
        });
    }

    /**
     * Envoie un bloc de son du micro (PCM 16 bits, 16 kHz, mono).
     * Appelé depuis le fil d'enregistrement : envoi direct, sans file d'attente.
     */
    public void voiceAudio(byte[] pcm, int length) {
        Client client = voiceClient;
        if (client == null) return;
        byte[] data = length == pcm.length ? pcm : java.util.Arrays.copyOf(pcm, length);
        client.trySend(VOICE_ASSISTANT_AUDIO, new Proto.Writer().message(1, data).bytes());
    }

    /** Fin de lecture d'une annonce. */
    /** Envoie une image JPEG pour une entité caméra (en morceaux, comme ESPHome). */
    public void sendCameraImage(Entity entity, byte[] jpeg) {
        sender.execute(() -> {
            for (Client client : clients) {
                if (!client.subscribed) continue;
                int offset = 0;
                do {
                    int size = Math.min(CAMERA_CHUNK, jpeg.length - offset);
                    byte[] part = java.util.Arrays.copyOfRange(jpeg, offset, offset + size);
                    offset += size;
                    client.trySend(CAMERA_IMAGE, new Proto.Writer()
                        .fixed32(1, entity.key)
                        .message(2, part)
                        .bool(3, offset >= jpeg.length)
                        .bytes());
                } while (offset < jpeg.length);
            }
        });
    }

    private static final int CAMERA_CHUNK = 16 * 1024;

    /**
     * Demande une fois à HA l'état d'une entité (ou l'un de ses attributs) ;
     * la réponse arrive dans Handler.onHomeAssistantState. Faux si HA n'est pas connecté.
     */
    public boolean requestHomeAssistantState(String entityId, String attribute) {
        boolean any = false;
        for (Client client : clients) if (client.haStates) any = true;
        if (!any) return false;
        sender.execute(() -> {
            for (Client client : clients) {
                if (!client.haStates) continue;
                client.trySend(HA_STATE_SUBSCRIPTION, new Proto.Writer()
                    .string(1, entityId)
                    .string(2, attribute)
                    .bool(3, true)
                    .bytes());
            }
        });
        return true;
    }

    public void voiceAnnounceFinished(boolean success) {
        sender.execute(() -> {
            Client client = voiceClient;
            if (client != null) client.trySend(VOICE_ASSISTANT_ANNOUNCE_FINISHED, new Proto.Writer().bool(1, success).bytes());
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
        /** HA accepte de nous donner l'état de ses entités. */
        volatile boolean haStates;

        Client(Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
        }

        /** Session chiffrée (null : connexion en clair). */
        private volatile NoiseSession noise;

        void run() {
            try {
                int preamble = in.read();
                if (preamble < 0) return;
                if (preamble == 0x01) {
                    runEncrypted();
                    return;
                }
                if (preamble != 0x00) return;
                if (noiseKey != null) {
                    // Chiffrement en place : on refuse le clair, ce qui fait demander la clé par HA
                    rejectPlaintext();
                    return;
                }
                while (running) {
                    int length = (int) readVarint();
                    int type = (int) readVarint();
                    byte[] payload = readFully(length);
                    if (!handle(type, payload)) break;
                    int next = in.read();
                    if (next != 0x00) break;
                }
            } catch (IOException ignored) {
            } finally {
                close();
            }
        }

        private void runEncrypted() throws IOException {
            byte[] key = noiseKey != null ? noiseKey : ZERO_PSK;
            // 1. « Bonjour » du client (trame vide), 2. premier message de la poignée de main
            readNoiseFrame(false);
            byte[] handshake = readNoiseFrame(true);
            if (handshake.length == 0 || handshake[0] != 0) return;
            NoiseSession session = new NoiseSession(key);
            String mac = info.macAddress.replace(":", "").toLowerCase(java.util.Locale.ROOT);
            ByteArrayOutputStream hello = new ByteArrayOutputStream();
            hello.write(0x01);
            hello.write(info.name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            hello.write(0);
            hello.write(mac.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            hello.write(0);
            writeNoiseFrame(hello.toByteArray());
            if (!session.readHandshake(java.util.Arrays.copyOfRange(handshake, 1, handshake.length))) {
                byte[] reason = "Handshake MAC failure".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
                byte[] reject = new byte[reason.length + 1];
                reject[0] = 0x01;
                System.arraycopy(reason, 0, reject, 1, reason.length);
                writeNoiseFrame(reject);
                return;
            }
            byte[] reply = session.writeHandshake();
            byte[] frame = new byte[reply.length + 1];
            System.arraycopy(reply, 0, frame, 1, reply.length);
            writeNoiseFrame(frame);
            noise = session;
            while (running) {
                byte[] data = session.decrypt(readNoiseFrame(true));
                if (data == null || data.length < 4) break;
                int type = ((data[0] & 0xff) << 8) | (data[1] & 0xff);
                int length = ((data[2] & 0xff) << 8) | (data[3] & 0xff);
                if (length > data.length - 4) break;
                if (!handle(type, java.util.Arrays.copyOfRange(data, 4, 4 + length))) break;
            }
        }

        /** Lit une trame chiffrée [0x01][longueur sur 2 octets][données]. */
        private byte[] readNoiseFrame(boolean readPreamble) throws IOException {
            if (readPreamble && in.read() != 0x01) throw new EOFException();
            int hi = in.read();
            int lo = in.read();
            if (hi < 0 || lo < 0) throw new EOFException();
            return readFully((hi << 8) | lo);
        }

        private void writeNoiseFrame(byte[] payload) throws IOException {
            byte[] frame = new byte[payload.length + 3];
            frame[0] = 0x01;
            frame[1] = (byte) (payload.length >> 8);
            frame[2] = (byte) payload.length;
            System.arraycopy(payload, 0, frame, 3, payload.length);
            synchronized (out) {
                out.write(frame);
                out.flush();
            }
        }

        private void rejectPlaintext() throws IOException {
            byte[] reason = "Bad indicator byte".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            byte[] reject = new byte[reason.length + 1];
            reject[0] = 0x01;
            System.arraycopy(reason, 0, reject, 1, reason.length);
            writeNoiseFrame(reject);
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
                    for (UserService service : services) send(LIST_SERVICES, describe(service));
                    send(LIST_ENTITIES_DONE, new byte[0]);
                    return true;
                case NOISE_SET_KEY_REQUEST: {
                    Map<Integer, Object> f = Proto.read(payload);
                    byte[] key = null;
                    boolean ok;
                    try {
                        String text = new String(Proto.getBytes(f, 1), java.nio.charset.StandardCharsets.US_ASCII).trim();
                        if (!text.isEmpty()) key = java.util.Base64.getDecoder().decode(text);
                        ok = keyStore != null && (key == null || key.length == 32);
                    } catch (IllegalArgumentException e) {
                        ok = false;
                    }
                    send(NOISE_SET_KEY_RESPONSE, new Proto.Writer().bool(1, ok).bytes());
                    if (ok) {
                        final byte[] newKey = key;
                        // Comme ESPHome : on laisse partir la réponse, puis on déconnecte tout le monde
                        new Thread(() -> {
                            try {
                                Thread.sleep(150);
                            } catch (InterruptedException ignored) {
                            }
                            changeKey(newKey);
                        }, "walldock-noise-key").start();
                    }
                    return true;
                }
                case TEXT_COMMAND: {
                    Map<Integer, Object> f = Proto.read(payload);
                    Entity entity = find(Proto.getInt(f, 1), Entity.Kind.TEXT);
                    if (entity != null && handler != null) handler.onText(entity, Proto.getString(f, 2));
                    return true;
                }
                case SUBSCRIBE_HA_STATES:
                    haStates = true;
                    return true;
                case HA_STATE: {
                    Map<Integer, Object> f = Proto.read(payload);
                    if (handler != null) {
                        handler.onHomeAssistantState(Proto.getString(f, 1), Proto.getString(f, 3), Proto.getString(f, 2));
                    }
                    return true;
                }
                case CAMERA_IMAGE_REQUEST: {
                    if (handler != null) {
                        for (Entity entity : entities) {
                            if (entity.kind == Entity.Kind.CAMERA) handler.onCameraRequest(entity);
                        }
                    }
                    return true;
                }
                case MEDIA_PLAYER_COMMAND: {
                    Map<Integer, Object> f = Proto.read(payload);
                    Entity entity = find(Proto.getInt(f, 1), Entity.Kind.MEDIA_PLAYER);
                    if (entity != null && handler != null) {
                        handler.onMediaPlayer(entity,
                            Proto.getBool(f, 2) ? Integer.valueOf(Proto.getInt(f, 3)) : null,
                            Proto.getBool(f, 4) ? Float.valueOf(Proto.getFloat(f, 5)) : null,
                            Proto.getBool(f, 6) ? Proto.getString(f, 7) : null,
                            Proto.getBool(f, 8) && Proto.getBool(f, 9));
                    }
                    return true;
                }
                case SELECT_COMMAND: {
                    Map<Integer, Object> f = Proto.read(payload);
                    Entity entity = find(Proto.getInt(f, 1), Entity.Kind.SELECT);
                    if (entity != null && handler != null) handler.onSelect(entity, Proto.getString(f, 2));
                    return true;
                }
                case EXECUTE_SERVICE: {
                    Map<Integer, Object> f = Proto.read(payload);
                    int key = Proto.getInt(f, 1);
                    UserService service = null;
                    for (UserService s : services) if (s.key == key) service = s;
                    if (service == null || handler == null) return true;
                    List<byte[]> rawArgs = Proto.readRepeated(payload, 2);
                    Map<String, Object> args = new HashMap<>();
                    for (int i = 0; i < service.argNames.size() && i < rawArgs.size(); i++) {
                        Map<Integer, Object> a = Proto.read(rawArgs.get(i));
                        switch (service.argTypes.get(i)) {
                            case UserService.ARG_BOOL:
                                args.put(service.argNames.get(i), Proto.getBool(a, 1));
                                break;
                            case UserService.ARG_INT: {
                                long zigzag = a.containsKey(5) ? (Long) a.get(5) : 0L;
                                int value = a.containsKey(5) ? (int) ((zigzag >>> 1) ^ -(zigzag & 1)) : Proto.getInt(a, 2);
                                args.put(service.argNames.get(i), value);
                                break;
                            }
                            case UserService.ARG_FLOAT:
                                args.put(service.argNames.get(i), Proto.getFloat(a, 3));
                                break;
                            default:
                                args.put(service.argNames.get(i), Proto.getString(a, 4));
                        }
                    }
                    handler.onService(service, args);
                    return true;
                }
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
                case SUBSCRIBE_VOICE_ASSISTANT: {
                    boolean subscribe = Proto.getBool(Proto.read(payload), 1);
                    if (subscribe) {
                        voiceClient = this;
                    } else if (voiceClient == this) {
                        voiceClient = null;
                    }
                    VoiceHandler vh = voiceHandler;
                    if (vh != null) vh.onVoiceSubscribed(voiceClient != null);
                    return true;
                }
                case VOICE_ASSISTANT_REQUEST: {
                    // HA demande l'arrêt du pipeline (start = faux)
                    Map<Integer, Object> f = Proto.read(payload);
                    VoiceHandler vh = voiceHandler;
                    if (!Proto.getBool(f, 1) && vh != null) vh.onVoiceStopRequested();
                    return true;
                }
                case VOICE_ASSISTANT_RESPONSE: {
                    VoiceHandler vh = voiceHandler;
                    if (vh != null) vh.onVoiceResponse(Proto.getBool(Proto.read(payload), 2));
                    return true;
                }
                case VOICE_ASSISTANT_EVENT: {
                    Map<Integer, Object> f = Proto.read(payload);
                    Map<String, String> data = new HashMap<>();
                    for (byte[] item : Proto.readRepeated(payload, 2)) {
                        Map<Integer, Object> kv = Proto.read(item);
                        data.put(Proto.getString(kv, 1), Proto.getString(kv, 2));
                    }
                    VoiceHandler vh = voiceHandler;
                    if (vh != null) vh.onVoiceEvent(Proto.getInt(f, 1), data);
                    return true;
                }
                case VOICE_ASSISTANT_TIMER_EVENT: {
                    Map<Integer, Object> f = Proto.read(payload);
                    VoiceHandler vh = voiceHandler;
                    if (vh != null) {
                        vh.onTimerEvent(Proto.getInt(f, 1), Proto.getString(f, 2), Proto.getString(f, 3),
                            Proto.getInt(f, 4), Proto.getInt(f, 5), Proto.getBool(f, 6));
                    }
                    return true;
                }
                case VOICE_ASSISTANT_ANNOUNCE_REQUEST: {
                    Map<Integer, Object> f = Proto.read(payload);
                    VoiceHandler vh = voiceHandler;
                    if (vh != null) {
                        vh.onAnnounce(Proto.getString(f, 1), Proto.getString(f, 2), Proto.getString(f, 3), Proto.getBool(f, 4));
                    } else {
                        send(VOICE_ASSISTANT_ANNOUNCE_FINISHED, new Proto.Writer().bool(1, false).bytes());
                    }
                    return true;
                }
                case VOICE_ASSISTANT_CONFIGURATION_REQUEST:
                    // Le mot d'activation est détecté par HA (openWakeWord) : rien à proposer côté appareil
                    send(VOICE_ASSISTANT_CONFIGURATION_RESPONSE, new Proto.Writer().varint(3, 1).bytes());
                    return true;
                case VOICE_ASSISTANT_SET_CONFIGURATION:
                    return true;
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
                .varint(17, info.voiceFeatures)
                .bool(19, keyStore != null)   // sait recevoir et garder une clé (HA la crée tout seul)
                .bool(26, keyStore != null)   // accepte la clé « zéro » pour la recevoir chiffrée
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
                    case SELECT: {
                        Proto.Writer w = new Proto.Writer().fixed32(1, entity.key);
                        if (state instanceof String) w.string(2, (String) state); else w.bool(3, true);
                        send(SELECT_STATE, w.bytes());
                        break;
                    }
                    case MEDIA_PLAYER: {
                        Proto.Writer w = new Proto.Writer().fixed32(1, entity.key);
                        if (state instanceof Entity.MediaState) {
                            Entity.MediaState m = (Entity.MediaState) state;
                            w.varint(2, m.state).floatValue(3, m.volume).bool(4, m.muted);
                        } else {
                            w.varint(2, Entity.MediaState.IDLE);
                        }
                        send(MEDIA_PLAYER_STATE, w.bytes());
                        break;
                    }
                    case TEXT: {
                        Proto.Writer w = new Proto.Writer().fixed32(1, entity.key);
                        if (state instanceof String) w.string(2, (String) state); else w.bool(3, true);
                        send(TEXT_STATE, w.bytes());
                        break;
                    }
                    case BUTTON:
                    case CAMERA:
                        break; // pas d'état (la caméra envoie ses images à la demande)
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
                case SELECT: return LIST_SELECT;
                case MEDIA_PLAYER: return LIST_MEDIA_PLAYER;
                case CAMERA: return LIST_CAMERA;
                case TEXT: return LIST_TEXT;
                default: return LIST_TEXT_SENSOR;
            }
        }

        private byte[] describe(UserService service) {
            Proto.Writer w = new Proto.Writer().string(1, service.name).fixed32(2, service.key);
            for (int i = 0; i < service.argNames.size(); i++) {
                byte[] arg = new Proto.Writer()
                    .string(1, service.argNames.get(i))
                    .varint(2, service.argTypes.get(i))
                    .bytes();
                w.message(3, arg);
            }
            return w.bytes();
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
                case SELECT: {
                    w.string(5, e.icon);
                    for (String option : e.options) w.stringAlways(6, option);
                    return w.varint(8, e.entityCategory).bytes();
                }
                case TEXT:
                    return w.string(5, e.icon).varint(7, e.entityCategory).varint(9, 255).bytes();
                case CAMERA:
                    return w.string(6, e.icon).varint(7, e.entityCategory).bytes();
                case MEDIA_PLAYER:
                    return w.string(5, e.icon).varint(7, e.entityCategory).bool(8, true)
                        .varint(11, MEDIA_FEATURES).bytes();
                default: // TEXT_SENSOR
                    return w.string(5, e.icon).varint(7, e.entityCategory).string(8, e.deviceClass).bytes();
            }
        }

        private void send(int type, byte[] payload) throws IOException {
            NoiseSession session = noise;
            if (session != null) {
                byte[] plain = new byte[payload.length + 4];
                plain[0] = (byte) (type >> 8);
                plain[1] = (byte) type;
                plain[2] = (byte) (payload.length >> 8);
                plain[3] = (byte) payload.length;
                System.arraycopy(payload, 0, plain, 4, payload.length);
                // Chiffrement et envoi dans le même ordre (le compteur de nonces doit suivre l'ordre d'envoi)
                synchronized (out) {
                    byte[] data = session.encrypt(plain);
                    byte[] frame = new byte[data.length + 3];
                    frame[0] = 0x01;
                    frame[1] = (byte) (data.length >> 8);
                    frame[2] = (byte) data.length;
                    System.arraycopy(data, 0, frame, 3, data.length);
                    out.write(frame);
                    out.flush();
                }
                return;
            }
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

        /** Envoi qui ferme la connexion en cas d'erreur au lieu de lever une exception. */
        void trySend(int type, byte[] payload) {
            try {
                send(type, payload);
            } catch (IOException e) {
                close();
            }
        }

        void close() {
            if (voiceClient == this) {
                voiceClient = null;
                VoiceHandler vh = voiceHandler;
                if (vh != null) vh.onVoiceSubscribed(false);
            }
            clients.remove(this);
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
