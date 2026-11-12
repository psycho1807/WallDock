package app.walldock.sendspin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import app.walldock.esphome.NoiseCrypto;

/**
 * Noise_KKpsk2_25519_AESGCM_SHA256, côté répondeur (le client Sendspin), comme l'exige
 * Sendspin : le serveur est l'initiateur, les deux clés statiques sont connues d'avance,
 * la clé partagée (PSK) est mêlée à la fin du 2e message.
 */
public final class NoiseKK {

    private static final byte[] PROTOCOL = "Noise_KKpsk2_25519_AESGCM_SHA256".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BASEPOINT = new byte[32];
    static { BASEPOINT[0] = 9; }

    private final byte[] staticPriv;
    private final byte[] staticPub;
    private final byte[] remoteStatic;

    private byte[] h;
    private byte[] ck;
    private byte[] k;      // clé de chiffrement de l'état symétrique (null = aucune)
    private long n;
    private byte[] remoteEphemeral;

    /** Clés de transport après la poignée de main. */
    public Cipher1 sender;
    public Cipher1 receiver;

    public NoiseKK(byte[] staticPriv, byte[] staticPub, byte[] remoteStatic, byte[] prologue) {
        this.staticPriv = staticPriv;
        this.staticPub = staticPub;
        this.remoteStatic = remoteStatic;
        h = PROTOCOL.length == 32 ? PROTOCOL.clone() : sha256(PROTOCOL);
        ck = h.clone();
        mixHash(prologue);
        // Pré-messages : -> s (initiateur), <- s (répondeur)
        mixHash(remoteStatic);
        mixHash(staticPub);
    }

    public static byte[] publicKey(byte[] priv) {
        return NoiseCrypto.x25519(priv, BASEPOINT);
    }

    /** Lit le message 1 (-> e, es, ss) et renvoie sa charge utile déchiffrée. */
    public byte[] readMessage1(byte[] message) throws Exception {
        if (message.length < 32) throw new IllegalArgumentException("message 1 trop court");
        remoteEphemeral = Arrays.copyOf(message, 32);
        mixHash(remoteEphemeral);
        mixKey(remoteEphemeral);                                  // règle des motifs « psk »
        mixKey(NoiseCrypto.x25519(staticPriv, remoteEphemeral));  // es (répondeur : DH(s, re))
        mixKey(NoiseCrypto.x25519(staticPriv, remoteStatic));     // ss
        return decryptAndHash(Arrays.copyOfRange(message, 32, message.length));
    }

    /** Écrit le message 2 (<- e, ee, se, psk) avec sa charge utile, puis passe en transport. */
    public byte[] writeMessage2(byte[] psk, byte[] payload) throws Exception {
        byte[] ePriv = NoiseCrypto.randomBytes(32);
        byte[] ePub = publicKey(ePriv);
        mixHash(ePub);
        mixKey(ePub);                                             // règle des motifs « psk »
        mixKey(NoiseCrypto.x25519(ePriv, remoteEphemeral));       // ee
        mixKey(NoiseCrypto.x25519(ePriv, remoteStatic));          // se (répondeur : DH(e, rs))
        mixKeyAndHash(psk);
        byte[] cipher = encryptAndHash(payload);
        byte[] out = new byte[32 + cipher.length];
        System.arraycopy(ePub, 0, out, 0, 32);
        System.arraycopy(cipher, 0, out, 32, cipher.length);
        // Split : l'initiateur envoie avec k1, le répondeur avec k2
        byte[][] keys = hkdf(ck, new byte[0], 2);
        receiver = new Cipher1(keys[0]);
        sender = new Cipher1(keys[1]);
        return out;
    }

    public byte[] handshakeHash() {
        return h.clone();
    }

    // ---- État symétrique ----

    private void mixHash(byte[] data) {
        h = sha256(h, data);
    }

    private void mixKey(byte[] ikm) throws Exception {
        byte[][] out = hkdf(ck, ikm, 2);
        ck = out[0];
        k = out[1];
        n = 0;
    }

    private void mixKeyAndHash(byte[] ikm) throws Exception {
        byte[][] out = hkdf(ck, ikm, 3);
        ck = out[0];
        mixHash(out[1]);
        k = out[2];
        n = 0;
    }

    private byte[] encryptAndHash(byte[] plain) throws Exception {
        byte[] c = k == null ? plain : aes(true, k, n++, h, plain);
        mixHash(c);
        return c;
    }

    private byte[] decryptAndHash(byte[] cipher) throws Exception {
        byte[] p = k == null ? cipher : aes(false, k, n++, h, cipher);
        mixHash(cipher);
        return p;
    }

    // ---- Primitives ----

    static byte[] aes(boolean encrypt, byte[] key, long counter, byte[] ad, byte[] data) throws Exception {
        byte[] nonce = new byte[12];
        for (int i = 0; i < 8; i++) nonce[4 + i] = (byte) (counter >>> (8 * (7 - i)));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        if (ad != null && ad.length > 0) cipher.updateAAD(ad);
        return cipher.doFinal(data);
    }

    static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, byte[]... parts) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        for (byte[] p : parts) mac.update(p);
        return mac.doFinal();
    }

    private static byte[][] hkdf(byte[] chainingKey, byte[] ikm, int count) throws Exception {
        byte[] temp = hmac(chainingKey, ikm);
        byte[][] out = new byte[count][];
        byte[] prev = new byte[0];
        for (int i = 0; i < count; i++) {
            prev = hmac(temp, prev, new byte[]{(byte) (i + 1)});
            out[i] = prev;
        }
        return out;
    }

    /** Chiffrement de transport (une direction). */
    public static final class Cipher1 {
        private final byte[] key;
        private long nonce;

        Cipher1(byte[] key) {
            this.key = key;
        }

        public synchronized byte[] encrypt(byte[] plain) throws Exception {
            return aes(true, key, nonce++, null, plain);
        }

        public synchronized byte[] decrypt(byte[] cipher) throws Exception {
            return aes(false, key, nonce++, null, cipher);
        }
    }
}
