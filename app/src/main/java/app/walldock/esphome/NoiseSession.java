package app.walldock.esphome;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Côté appareil (répondeur) du chiffrement de l'API ESPHome :
 * Noise_NNpsk0_25519_ChaChaPoly_SHA256, prologue « NoiseAPIInit\0\0 ».
 * Poignée de main en deux messages (→ psk, e / ← e, ee), puis deux clés de transport.
 */
final class NoiseSession {

    static final String PROTOCOL = "Noise_NNpsk0_25519_ChaChaPoly_SHA256";
    private static final byte[] PROLOGUE = "NoiseAPIInit\0\0".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EMPTY = new byte[0];

    private final byte[] psk;
    private byte[] ck;
    private byte[] h;
    private byte[] k;
    private long n;
    private byte[] remoteEphemeral;

    private byte[] sendKey;
    private byte[] receiveKey;
    private long sendNonce;
    private long receiveNonce;

    NoiseSession(byte[] psk) {
        this.psk = psk;
        h = NoiseCrypto.sha256(PROTOCOL.getBytes(StandardCharsets.US_ASCII));
        ck = h.clone();
        mixHash(PROLOGUE);
    }

    /** Premier message du client (psk, e, charge utile). Faux si la clé ne correspond pas. */
    boolean readHandshake(byte[] message) {
        if (message.length < 32) return false;
        mixKeyAndHash(psk);
        remoteEphemeral = Arrays.copyOfRange(message, 0, 32);
        mixHash(remoteEphemeral);
        mixKey(remoteEphemeral);
        return decryptAndHash(Arrays.copyOfRange(message, 32, message.length)) != null;
    }

    /** Réponse de l'appareil (e, ee, charge utile vide), puis clés de transport. */
    byte[] writeHandshake() {
        byte[] secret = NoiseCrypto.randomBytes(32);
        byte[] ephemeral = NoiseCrypto.x25519(secret, NoiseCrypto.BASEPOINT);
        mixHash(ephemeral);
        mixKey(ephemeral);
        mixKey(NoiseCrypto.x25519(secret, remoteEphemeral));
        byte[] payload = encryptAndHash(EMPTY);
        byte[] out = Arrays.copyOf(ephemeral, 32 + payload.length);
        System.arraycopy(payload, 0, out, 32, payload.length);
        byte[][] keys = NoiseCrypto.hkdf(ck, EMPTY, 2);
        receiveKey = keys[0]; // client → appareil
        sendKey = keys[1];    // appareil → client
        sendNonce = 0;
        receiveNonce = 0;
        return out;
    }

    byte[] encrypt(byte[] plain) {
        return NoiseCrypto.aeadEncrypt(sendKey, NoiseCrypto.noiseNonce(sendNonce++), EMPTY, plain);
    }

    /** Null si le message a été modifié ou chiffré avec une autre clé. */
    byte[] decrypt(byte[] data) {
        byte[] plain = NoiseCrypto.aeadDecrypt(receiveKey, NoiseCrypto.noiseNonce(receiveNonce), EMPTY, data);
        if (plain != null) receiveNonce++;
        return plain;
    }

    private void mixHash(byte[] data) {
        h = NoiseCrypto.sha256(h, data);
    }

    private void mixKey(byte[] input) {
        byte[][] out = NoiseCrypto.hkdf(ck, input, 2);
        ck = out[0];
        k = out[1];
        n = 0;
    }

    private void mixKeyAndHash(byte[] input) {
        byte[][] out = NoiseCrypto.hkdf(ck, input, 3);
        ck = out[0];
        mixHash(out[1]);
        k = out[2];
        n = 0;
    }

    private byte[] encryptAndHash(byte[] plain) {
        byte[] c = NoiseCrypto.aeadEncrypt(k, NoiseCrypto.noiseNonce(n++), h, plain);
        mixHash(c);
        return c;
    }

    private byte[] decryptAndHash(byte[] c) {
        byte[] plain = NoiseCrypto.aeadDecrypt(k, NoiseCrypto.noiseNonce(n), h, c);
        if (plain == null) return null;
        n++;
        mixHash(c);
        return plain;
    }
}
