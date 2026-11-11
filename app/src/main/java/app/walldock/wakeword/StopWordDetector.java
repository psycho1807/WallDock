package app.walldock.wakeword;

import android.content.Context;

import org.tensorflow.lite.Interpreter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Détecte le mot « Stop » sur la tablette avec le modèle microWakeWord « stop »
 * (le même que celui du Home Assistant Voice Preview Edition), en continu et sans
 * passer par Home Assistant. Le modèle (Apache 2.0) est fourni avec l'appli.
 *
 * Fonctionnement identique à ESPHome : 40 bandes mel toutes les 10 ms (MicroFrontend),
 * le modèle est appelé toutes les 3 trames et la moyenne des 5 dernières probabilités
 * doit dépasser 0,5.
 */
public final class StopWordDetector {

    private static final String ASSET = "wakeword/stop.tflite";
    private static final int STRIDE = 3;
    private static final int SLIDING_WINDOW = 5;
    private static final int CUTOFF = 127;            // 0,5 × 255
    private static final int WARMUP_INFERENCES = 25;  // ≈ 0,75 s après une remise à zéro

    private final Interpreter interpreter;
    private final MicroFrontend frontend = new MicroFrontend();
    // Tampons natifs : entrée int8 [1, 3, 40], sortie uint8 [1, 1]
    private final java.nio.ByteBuffer input =
        java.nio.ByteBuffer.allocateDirect(STRIDE * MicroFrontend.NUM_CHANNELS).order(java.nio.ByteOrder.nativeOrder());
    private final java.nio.ByteBuffer output =
        java.nio.ByteBuffer.allocateDirect(1).order(java.nio.ByteOrder.nativeOrder());
    private final int[] recent = new int[SLIDING_WINDOW];
    private final short[] samples = new short[MicroFrontend.STEP_SAMPLES];
    private int sampleCount;
    private int frames;
    private int recentIndex;
    private int inferences;
    private boolean needsReset = true;

    /** Dernière probabilité moyenne (0–1), pour le diagnostic. */
    public volatile float lastScore;

    private StopWordDetector(File model) {
        Interpreter.Options options = new Interpreter.Options().setNumThreads(1).setUseXNNPACK(false);
        interpreter = new Interpreter(model, options);
    }

    /** Charge le modèle, ou renvoie null si la tablette ne peut pas l'exécuter. */
    public static StopWordDetector load(Context context) {
        try {
            File file = new File(context.getFilesDir(), "wakeword/stop.tflite");
            if (!file.isFile()) {
                File dir = file.getParentFile();
                if (dir != null) dir.mkdirs();
                File temp = new File(file.getPath() + ".part");
                try (InputStream in = context.getAssets().open(ASSET); OutputStream out = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[16384];
                    int n;
                    while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                }
                if (!temp.renameTo(file)) return null;
            }
            StopWordDetector detector = new StopWordDetector(file);
            // Essai sur une seconde de silence
            byte[] silence = new byte[2048];
            for (int i = 0; i < 16; i++) detector.process(silence, silence.length);
            detector.reset();
            return detector;
        } catch (Throwable e) {
            return null;
        }
    }

    /** À appeler avant de recommencer à écouter (évite de réutiliser du vieux son). */
    public void reset() {
        needsReset = true;
    }

    /** Ajoute du son PCM 16 bits little-endian ; renvoie vrai si « Stop » vient d'être dit. */
    public boolean process(byte[] pcm, int length) {
        if (needsReset) {
            needsReset = false;
            frontend.reset();
            sampleCount = 0;
            frames = 0;
            recentIndex = 0;
            inferences = 0;
            java.util.Arrays.fill(recent, 0);
            interpreter.resetVariableTensors();
            lastScore = 0f;
        }
        boolean detected = false;
        for (int i = 0; i + 1 < length; i += 2) {
            samples[sampleCount++] = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            if (sampleCount < samples.length) continue;
            sampleCount = 0;
            int offset = 0;
            while (offset < samples.length) {
                offset += frontend.process(samples, offset, samples.length - offset);
                if (frontend.hasOutput() && addFrame(frontend.output())) detected = true;
            }
        }
        return detected;
    }

    private boolean addFrame(int[] features) {
        int base = frames * MicroFrontend.NUM_CHANNELS;
        for (int i = 0; i < MicroFrontend.NUM_CHANNELS; i++) {
            int value = ((features[i] * 256) + 333) / 666 - 128;
            input.put(base + i, (byte) Math.max(-128, Math.min(127, value)));
        }
        if (++frames < STRIDE) return false;
        frames = 0;
        input.rewind();
        output.rewind();
        interpreter.run(input, output);
        recent[recentIndex] = output.get(0) & 0xFF;
        recentIndex = (recentIndex + 1) % SLIDING_WINDOW;
        int sum = 0;
        for (int p : recent) sum += p;
        lastScore = sum / (255f * SLIDING_WINDOW);
        if (++inferences <= WARMUP_INFERENCES) return false;
        if (sum > CUTOFF * SLIDING_WINDOW) {
            // Une seule détection par mot : on repart de zéro
            needsReset = true;
            return true;
        }
        return false;
    }

    public void close() {
        interpreter.close();
    }
}
