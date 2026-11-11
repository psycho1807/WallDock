package app.walldock.wakeword;

/**
 * Portage en Java du « micro frontend » audio de TensorFlow Lite Micro, celui qu'utilisent
 * microWakeWord et ESPHome : 40 bandes mel toutes les 10 ms, réduction du bruit,
 * contrôle de gain PCAN et échelle logarithmique, en arithmétique entière comme l'original
 * (y compris la FFT kissfft en virgule fixe 16 bits), pour donner les mêmes valeurs
 * que celles vues par les modèles pendant leur entraînement.
 *
 * Source d'origine : tensorflow/lite/experimental/microfrontend (Apache 2.0).
 */
public final class MicroFrontend {

    public static final int SAMPLE_RATE = 16000;
    public static final int NUM_CHANNELS = 40;
    public static final int STEP_SAMPLES = 160;   // 10 ms
    private static final int WINDOW_SAMPLES = 480; // 30 ms

    private static final int WINDOW_BITS = 12;
    private static final int FILTERBANK_BITS = 12;
    private static final int NOISE_REDUCTION_BITS = 14;
    private static final int PCAN_SNR_BITS = 12;
    private static final int PCAN_OUTPUT_BITS = 6;
    private static final int WIDE_DYNAMIC_FUNCTION_BITS = 32;
    private static final int LOG_SCALE_LOG2 = 16;
    private static final int LOG_SEGMENTS_LOG2 = 7;
    private static final int LOG_SCALE = 65536;
    private static final int LOG_COEFF = 45426;
    private static final int LOG_SCALE_SHIFT = 6;

    private static final int[] LOG_LUT = {
        0, 224, 442, 654, 861, 1063, 1259, 1450, 1636, 1817, 1992, 2163,
        2329, 2490, 2646, 2797, 2944, 3087, 3224, 3358, 3487, 3611, 3732, 3848,
        3960, 4068, 4172, 4272, 4368, 4460, 4549, 4633, 4714, 4791, 4864, 4934,
        5001, 5063, 5123, 5178, 5231, 5280, 5326, 5368, 5408, 5444, 5477, 5507,
        5533, 5557, 5578, 5595, 5610, 5622, 5631, 5637, 5640, 5641, 5638, 5633,
        5626, 5615, 5602, 5586, 5568, 5547, 5524, 5498, 5470, 5439, 5406, 5370,
        5332, 5291, 5249, 5203, 5156, 5106, 5054, 5000, 4944, 4885, 4825, 4762,
        4697, 4630, 4561, 4490, 4416, 4341, 4264, 4184, 4103, 4020, 3935, 3848,
        3759, 3668, 3575, 3481, 3384, 3286, 3186, 3084, 2981, 2875, 2768, 2659,
        2549, 2437, 2323, 2207, 2090, 1971, 1851, 1729, 1605, 1480, 1353, 1224,
        1094, 963, 830, 695, 559, 421, 282, 142, 0, 0
    };

    // ---- Fenêtre ----
    private final short[] windowCoefficients = new short[WINDOW_SAMPLES];
    private final short[] windowInput = new short[WINDOW_SAMPLES];
    private final short[] windowOutput = new short[WINDOW_SAMPLES];
    private int inputUsed;
    private int maxAbsOutput;

    // ---- FFT (kissfft réelle, 512 points, virgule fixe 16 bits) ----
    private static final int FFT_SIZE = 512;
    private static final int NCFFT = FFT_SIZE / 2;
    private final short[] fftInput = new short[FFT_SIZE];
    private final short[] twR = new short[NCFFT];
    private final short[] twI = new short[NCFFT];
    private final short[] superR = new short[NCFFT / 2];
    private final short[] superI = new short[NCFFT / 2];
    private final int[] factors;
    private final short[] tmpR = new short[NCFFT];
    private final short[] tmpI = new short[NCFFT];
    private final short[] outR = new short[NCFFT + 1];
    private final short[] outI = new short[NCFFT + 1];

    // ---- Banc de filtres ----
    private int startIndex;
    private int endIndex;
    private final int[] chanStart = new int[NUM_CHANNELS + 1];
    private final int[] chanWidth = new int[NUM_CHANNELS + 1];
    private final int[] chanWeightStart = new int[NUM_CHANNELS + 1];
    private short[] weights;
    private short[] unweights;
    private final long[] energy = new long[NCFFT + 1];
    private final long[] work = new long[NUM_CHANNELS + 1];
    private final long[] signal = new long[NUM_CHANNELS]; // valeurs uint32

    // ---- Réduction du bruit ----
    private static final int SMOOTHING_BITS = 10;
    private final long evenSmoothing = (long) (0.025f * (1 << NOISE_REDUCTION_BITS));
    private final long oddSmoothing = (long) (0.06f * (1 << NOISE_REDUCTION_BITS));
    private final long minSignalRemaining = (long) (0.05f * (1 << NOISE_REDUCTION_BITS));
    private final long[] estimate = new long[NUM_CHANNELS];

    // ---- PCAN ----
    private final short[] gainLut = new short[4 * WIDE_DYNAMIC_FUNCTION_BITS - 3];
    private final int snrShift;

    private final int[] output = new int[NUM_CHANNELS];

    public MicroFrontend() {
        // Fenêtre de Hann
        float arg = (float) Math.PI * 2.0f / WINDOW_SAMPLES;
        for (int i = 0; i < WINDOW_SAMPLES; i++) {
            float v = 0.5f - (0.5f * (float) Math.cos(arg * (i + 0.5f)));
            windowCoefficients[i] = (short) Math.floor(v * (1 << WINDOW_BITS) + 0.5f);
        }
        // Coefficients de la FFT complexe de 256 points
        for (int i = 0; i < NCFFT; i++) {
            double phase = -2 * Math.PI * i / NCFFT;
            twR[i] = (short) Math.floor(.5 + 32767 * Math.cos(phase));
            twI[i] = (short) Math.floor(.5 + 32767 * Math.sin(phase));
        }
        for (int i = 0; i < NCFFT / 2; i++) {
            double phase = -3.14159265358979323846264338327 * ((double) (i + 1) / NCFFT + .5);
            superR[i] = (short) Math.floor(.5 + 32767 * Math.cos(phase));
            superI[i] = (short) Math.floor(.5 + 32767 * Math.sin(phase));
        }
        factors = factor(NCFFT);
        populateFilterbank();
        int inputCorrectionBits = msb32(FFT_SIZE) - 1 - (FILTERBANK_BITS / 2);
        snrShift = 21 - inputCorrectionBits - PCAN_SNR_BITS;
        populatePcan(SMOOTHING_BITS - inputCorrectionBits);
        reset();
    }

    public void reset() {
        java.util.Arrays.fill(windowInput, (short) 0);
        java.util.Arrays.fill(windowOutput, (short) 0);
        inputUsed = 0;
        maxAbsOutput = 0;
        java.util.Arrays.fill(work, 0);
        java.util.Arrays.fill(estimate, 0);
    }

    /**
     * Ajoute des échantillons (au plus STEP_SAMPLES utiles par appel).
     * Renvoie le nombre d'échantillons consommés ; [hasOutput] dit si 40 valeurs sont prêtes.
     */
    public int process(short[] samples, int offset, int count) {
        hasOutput = false;
        int toCopy = Math.min(WINDOW_SAMPLES - inputUsed, count);
        System.arraycopy(samples, offset, windowInput, inputUsed, toCopy);
        inputUsed += toCopy;
        if (inputUsed < WINDOW_SAMPLES) return toCopy;

        int maxAbs = 0;
        for (int i = 0; i < WINDOW_SAMPLES; i++) {
            short v = (short) ((windowInput[i] * windowCoefficients[i]) >> WINDOW_BITS);
            windowOutput[i] = v;
            int a = v < 0 ? (short) -v : v;
            if (a > maxAbs) maxAbs = a;
        }
        System.arraycopy(windowInput, STEP_SAMPLES, windowInput, 0, WINDOW_SAMPLES - STEP_SAMPLES);
        inputUsed -= STEP_SAMPLES;
        maxAbsOutput = maxAbs;

        int inputShift = 15 - msb32(maxAbsOutput);
        computeFft(inputShift);
        // Énergie
        for (int i = startIndex; i < endIndex; i++) {
            int re = outR[i];
            int im = outI[i];
            energy[i] = ((long) re * re + (long) im * im) & 0xFFFFFFFFL;
        }
        accumulateChannels();
        for (int i = 0; i < NUM_CHANNELS; i++) {
            signal[i] = (sqrt64(work[i + 1]) >>> inputShift) & 0xFFFFFFFFL;
        }
        noiseReduction();
        pcan();
        int correctionBits = msb32(FFT_SIZE) - 1 - (FILTERBANK_BITS / 2);
        for (int i = 0; i < NUM_CHANNELS; i++) {
            long value = signal[i];
            if (correctionBits < 0) value >>>= -correctionBits;
            else value = (value << correctionBits) & 0xFFFFFFFFL;
            value = value > 1 ? log(value) : 0;
            output[i] = (int) Math.min(value, 0xFFFF);
        }
        hasOutput = true;
        return toCopy;
    }

    private boolean hasOutput;

    public boolean hasOutput() {
        return hasOutput;
    }

    /** Les 40 valeurs de la dernière trame (environ 0 à 670). */
    public int[] output() {
        return output;
    }

    // ---------------- FFT ----------------

    private void computeFft(int shift) {
        for (int i = 0; i < WINDOW_SAMPLES; i++) {
            fftInput[i] = (short) ((windowOutput[i] & 0xFFFF) << shift);
        }
        for (int i = WINDOW_SAMPLES; i < FFT_SIZE; i++) fftInput[i] = 0;

        // FFT complexe de 256 points sur les paires (pair, impair)
        short[] inR = new short[NCFFT];
        short[] inI = new short[NCFFT];
        for (int i = 0; i < NCFFT; i++) {
            inR[i] = fftInput[2 * i];
            inI[i] = fftInput[2 * i + 1];
        }
        kfWork(tmpR, tmpI, 0, inR, inI, 0, 1, 0);

        int tdcR = divScalar(tmpR[0], 2);
        int tdcI = divScalar(tmpI[0], 2);
        outR[0] = (short) (tdcR + tdcI);
        outR[NCFFT] = (short) (tdcR - tdcI);
        outI[0] = 0;
        outI[NCFFT] = 0;
        for (int k = 1; k <= NCFFT / 2; k++) {
            short fpkR = (short) divScalar(tmpR[k], 2);
            short fpkI = (short) divScalar(tmpI[k], 2);
            short fpnkR = (short) divScalar(tmpR[NCFFT - k], 2);
            short fpnkI = (short) divScalar((short) -tmpI[NCFFT - k], 2);
            short f1kR = (short) (fpkR + fpnkR);
            short f1kI = (short) (fpkI + fpnkI);
            short f2kR = (short) (fpkR - fpnkR);
            short f2kI = (short) (fpkI - fpnkI);
            short twr = sround((int) f2kR * superR[k - 1] - (int) f2kI * superI[k - 1]);
            short twi = sround((int) f2kR * superI[k - 1] + (int) f2kI * superR[k - 1]);
            outR[k] = (short) ((f1kR + twr) >> 1);
            outI[k] = (short) ((f1kI + twi) >> 1);
            outR[NCFFT - k] = (short) ((f1kR - twr) >> 1);
            outI[NCFFT - k] = (short) ((twi - f1kI) >> 1);
        }
    }

    private void kfWork(short[] oR, short[] oI, int out, short[] fR, short[] fI, int f, int fstride, int factorIndex) {
        int p = factors[factorIndex];
        int m = factors[factorIndex + 1];
        int outEnd = out + p * m;
        int o = out;
        if (m == 1) {
            do {
                oR[o] = fR[f];
                oI[o] = fI[f];
                f += fstride;
            } while (++o != outEnd);
        } else {
            do {
                kfWork(oR, oI, o, fR, fI, f, fstride * p, factorIndex + 2);
                f += fstride;
            } while ((o += m) != outEnd);
        }
        if (p == 4) bfly4(oR, oI, out, fstride, m);
        else if (p == 2) bfly2(oR, oI, out, fstride, m);
        else throw new IllegalStateException("radix " + p);
    }

    private void bfly2(short[] r, short[] im, int out, int fstride, int m) {
        int tw = 0;
        int o2 = out + m;
        int o = out;
        for (int k = 0; k < m; k++) {
            fixdiv(r, im, o, 2);
            fixdiv(r, im, o2, 2);
            short tR = sround((int) r[o2] * twR[tw] - (int) im[o2] * twI[tw]);
            short tI = sround((int) r[o2] * twI[tw] + (int) im[o2] * twR[tw]);
            tw += fstride;
            r[o2] = (short) (r[o] - tR);
            im[o2] = (short) (im[o] - tI);
            r[o] = (short) (r[o] + tR);
            im[o] = (short) (im[o] + tI);
            o2++;
            o++;
        }
    }

    private void bfly4(short[] r, short[] im, int out, int fstride, int m) {
        int tw1 = 0, tw2 = 0, tw3 = 0;
        int m2 = 2 * m, m3 = 3 * m;
        int o = out;
        for (int k = 0; k < m; k++) {
            fixdiv(r, im, o, 4);
            fixdiv(r, im, o + m, 4);
            fixdiv(r, im, o + m2, 4);
            fixdiv(r, im, o + m3, 4);
            short s0r = sround((int) r[o + m] * twR[tw1] - (int) im[o + m] * twI[tw1]);
            short s0i = sround((int) r[o + m] * twI[tw1] + (int) im[o + m] * twR[tw1]);
            short s1r = sround((int) r[o + m2] * twR[tw2] - (int) im[o + m2] * twI[tw2]);
            short s1i = sround((int) r[o + m2] * twI[tw2] + (int) im[o + m2] * twR[tw2]);
            short s2r = sround((int) r[o + m3] * twR[tw3] - (int) im[o + m3] * twI[tw3]);
            short s2i = sround((int) r[o + m3] * twI[tw3] + (int) im[o + m3] * twR[tw3]);

            short s5r = (short) (r[o] - s1r);
            short s5i = (short) (im[o] - s1i);
            r[o] = (short) (r[o] + s1r);
            im[o] = (short) (im[o] + s1i);
            short s3r = (short) (s0r + s2r);
            short s3i = (short) (s0i + s2i);
            short s4r = (short) (s0r - s2r);
            short s4i = (short) (s0i - s2i);
            r[o + m2] = (short) (r[o] - s3r);
            im[o + m2] = (short) (im[o] - s3i);
            tw1 += fstride;
            tw2 += fstride * 2;
            tw3 += fstride * 3;
            r[o] = (short) (r[o] + s3r);
            im[o] = (short) (im[o] + s3i);

            r[o + m] = (short) (s5r + s4i);
            im[o + m] = (short) (s5i - s4r);
            r[o + m3] = (short) (s5r - s4i);
            im[o + m3] = (short) (s5i + s4r);
            o++;
        }
    }

    private static short sround(int x) {
        return (short) ((x + (1 << 14)) >> 15);
    }

    private static int divScalar(short x, int k) {
        return sround(x * (32767 / k));
    }

    private static void fixdiv(short[] r, short[] im, int i, int div) {
        r[i] = (short) divScalar(r[i], div);
        im[i] = (short) divScalar(im[i], div);
    }

    private static int[] factor(int n) {
        int[] buf = new int[64];
        int count = 0;
        int p = 4;
        double floorSqrt = Math.floor(Math.sqrt(n));
        do {
            while (n % p != 0) {
                switch (p) {
                    case 4: p = 2; break;
                    case 2: p = 3; break;
                    default: p += 2; break;
                }
                if (p > floorSqrt) p = n;
            }
            n /= p;
            buf[count++] = p;
            buf[count++] = n;
        } while (n > 1);
        return java.util.Arrays.copyOf(buf, count);
    }

    // ---------------- Banc de filtres mel ----------------

    private static float freqToMel(float freq) {
        return 1127.0f * (float) Math.log1p(freq / 700.0f);
    }

    private void populateFilterbank() {
        int numPlus1 = NUM_CHANNELS + 1;
        int spectrumSize = FFT_SIZE / 2 + 1;
        float[] centerMel = new float[numPlus1];
        float melLow = freqToMel(125.0f);
        float melHi = freqToMel(7500.0f);
        float melSpacing = (melHi - melLow) / numPlus1;
        for (int i = 0; i < numPlus1; i++) centerMel[i] = melLow + (melSpacing * (i + 1));

        float hzPerSbin = 0.5f * SAMPLE_RATE / ((float) spectrumSize - 1);
        startIndex = (int) (1.5f + 125.0f / hzPerSbin);
        endIndex = 0;
        int chanFreqIndexStart = startIndex;
        int total = 0;
        for (int chan = 0; chan < numPlus1; chan++) {
            int freqIndex = chanFreqIndexStart;
            while (freqToMel(freqIndex * hzPerSbin) <= centerMel[chan]) freqIndex++;
            chanStart[chan] = chanFreqIndexStart;
            chanWidth[chan] = freqIndex - chanFreqIndexStart;
            chanWeightStart[chan] = total;
            total += chanWidth[chan];
            chanFreqIndexStart = freqIndex;
        }
        weights = new short[total];
        unweights = new short[total];
        for (int chan = 0; chan < numPlus1; chan++) {
            int frequency = chanStart[chan];
            float denom = (chan == 0) ? melLow : centerMel[chan - 1];
            for (int j = 0; j < chanWidth[chan]; j++, frequency++) {
                float w = (centerMel[chan] - freqToMel(frequency * hzPerSbin)) / (centerMel[chan] - denom);
                weights[chanWeightStart[chan] + j] = (short) Math.floor(w * (1 << FILTERBANK_BITS) + 0.5f);
                unweights[chanWeightStart[chan] + j] = (short) Math.floor((1.0f - w) * (1 << FILTERBANK_BITS) + 0.5f);
            }
            if (frequency > endIndex) endIndex = frequency;
        }
    }

    private void accumulateChannels() {
        long weightAcc = 0;
        long unweightAcc = 0;
        for (int chan = 0; chan <= NUM_CHANNELS; chan++) {
            int base = chanWeightStart[chan];
            for (int j = 0; j < chanWidth[chan]; j++) {
                long mag = energy[chanStart[chan] + j];
                weightAcc += weights[base + j] * mag;
                unweightAcc += unweights[base + j] * mag;
            }
            work[chan] = weightAcc;
            weightAcc = unweightAcc;
            unweightAcc = 0;
        }
    }

    private static long sqrt32(long num) {
        if (num == 0) return 0;
        long res = 0;
        int maxBit = 32 - msb32(num);
        maxBit |= 1;
        long bit = 1L << (31 - maxBit);
        int iterations = (31 - maxBit) / 2 + 1;
        while (iterations-- > 0) {
            if (num >= res + bit) {
                num -= res + bit;
                res = (res >>> 1) + bit;
            } else {
                res >>>= 1;
            }
            bit >>>= 2;
        }
        if (num > res && res != 0xFFFF) res++;
        return res & 0xFFFF;
    }

    private static long sqrt64(long num) {
        if ((num >>> 32) == 0) return sqrt32(num);
        long res = 0;
        int maxBit = 64 - msb64(num);
        maxBit |= 1;
        long bit = 1L << (63 - maxBit);
        int iterations = (63 - maxBit) / 2 + 1;
        while (iterations-- > 0) {
            if (Long.compareUnsigned(num, res + bit) >= 0) {
                num -= res + bit;
                res = (res >>> 1) + bit;
            } else {
                res >>>= 1;
            }
            bit >>>= 2;
        }
        if (Long.compareUnsigned(num, res) > 0 && res != 0xFFFFFFFFL) res++;
        return res & 0xFFFFFFFFL;
    }

    // ---------------- Réduction du bruit ----------------

    private void noiseReduction() {
        for (int i = 0; i < NUM_CHANNELS; i++) {
            long smoothing = ((i & 1) == 0) ? evenSmoothing : oddSmoothing;
            long oneMinus = (1L << NOISE_REDUCTION_BITS) - smoothing;
            long scaledUp = (signal[i] << SMOOTHING_BITS) & 0xFFFFFFFFL;
            long est = ((scaledUp * smoothing) + (estimate[i] * oneMinus)) >>> NOISE_REDUCTION_BITS;
            est &= 0xFFFFFFFFL;
            estimate[i] = est;
            if (est > scaledUp) est = scaledUp;
            long floor = ((signal[i] * minSignalRemaining) >>> NOISE_REDUCTION_BITS) & 0xFFFFFFFFL;
            long subtracted = (scaledUp - est) >>> SMOOTHING_BITS;
            signal[i] = subtracted > floor ? subtracted : floor;
        }
    }

    // ---------------- PCAN ----------------

    private static short pcanGain(int inputBits, long x) {
        float xf = ((float) x) / (float) (1L << inputBits);
        float g = (float) (1L << 21) * (float) Math.pow(xf + 80.0f, -0.95f);
        if (g > 0x7FFF) return 0x7FFF;
        return (short) (g + 0.5f);
    }

    // gainLut[i] correspond à gain_lut[i] dans le code C (après le décalage de 6)
    private void populatePcan(int inputBits) {
        gainLut[0] = pcanGain(inputBits, 0);
        gainLut[1] = pcanGain(inputBits, 1);
        for (int interval = 2; interval <= WIDE_DYNAMIC_FUNCTION_BITS; interval++) {
            long x0 = 1L << (interval - 1);
            long x1 = x0 + (x0 >> 1);
            long x2 = (interval == WIDE_DYNAMIC_FUNCTION_BITS) ? x0 + (x0 - 1) : 2 * x0;
            short y0 = pcanGain(inputBits, x0);
            short y1 = pcanGain(inputBits, x1);
            short y2 = pcanGain(inputBits, x2);
            int diff1 = y1 - y0;
            int diff2 = y2 - y0;
            int a1 = 4 * diff1 - diff2;
            int a2 = diff2 - a1;
            gainLut[4 * interval - 6] = y0;
            gainLut[4 * interval - 6 + 1] = (short) a1;
            gainLut[4 * interval - 6 + 2] = (short) a2;
        }
    }

    private int wideDynamicFunction(long x) {
        if (x <= 2) return gainLut[(int) x];
        int interval = msb32(x);
        int base = 4 * interval - 6;
        int frac = (int) (((interval < 11) ? (x << (11 - interval)) : (x >>> (interval - 11))) & 0x3FF);
        int result = (gainLut[base + 2] * frac) >> 5;
        result += gainLut[base + 1] << 5;
        result *= frac;
        result = (result + (1 << 14)) >> 15;
        result += gainLut[base];
        return (short) result;
    }

    private static long pcanShrink(long x) {
        if (x < (2L << PCAN_SNR_BITS)) {
            return ((x * x) & 0xFFFFFFFFL) >>> (2 + 2 * PCAN_SNR_BITS - PCAN_OUTPUT_BITS);
        }
        return ((x >>> (PCAN_SNR_BITS - PCAN_OUTPUT_BITS)) - (1L << PCAN_OUTPUT_BITS)) & 0xFFFFFFFFL;
    }

    private void pcan() {
        for (int i = 0; i < NUM_CHANNELS; i++) {
            long gain = wideDynamicFunction(estimate[i]) & 0xFFFFFFFFL;
            long snr = ((signal[i] * gain) >>> snrShift) & 0xFFFFFFFFL;
            signal[i] = pcanShrink(snr);
        }
    }

    // ---------------- Logarithme ----------------

    private static long log2FractionPart(long x, int log2x) {
        long frac = x - (1L << log2x);
        if (log2x < LOG_SCALE_LOG2) frac <<= LOG_SCALE_LOG2 - log2x;
        else frac >>= log2x - LOG_SCALE_LOG2;
        frac = (int) frac;
        int baseSeg = (int) ((frac & 0xFFFFFFFFL) >>> (LOG_SCALE_LOG2 - LOG_SEGMENTS_LOG2));
        int segUnit = (1 << LOG_SCALE_LOG2) >> LOG_SEGMENTS_LOG2;
        int c0 = LOG_LUT[baseSeg];
        int c1 = LOG_LUT[baseSeg + 1];
        int segBase = segUnit * baseSeg;
        int relPos = (int) (((long) (c1 - c0) * (frac - segBase)) >> LOG_SCALE_LOG2);
        return (frac + c0 + relPos) & 0xFFFFFFFFL;
    }

    private static long log(long x) {
        int integer = msb32(x) - 1;
        long fraction = log2FractionPart(x, integer);
        long log2 = (((long) integer << LOG_SCALE_LOG2) + fraction) & 0xFFFFFFFFL;
        long round = LOG_SCALE / 2;
        long loge = ((LOG_COEFF * log2) + round) >>> LOG_SCALE_LOG2;
        loge &= 0xFFFFFFFFL;
        return ((((loge << LOG_SCALE_SHIFT) & 0xFFFFFFFFL) + round) >>> LOG_SCALE_LOG2) & 0xFFFFFFFFL;
    }

    private static int msb32(long n) {
        return 32 - Integer.numberOfLeadingZeros((int) n);
    }

    private static int msb64(long n) {
        return 64 - Long.numberOfLeadingZeros(n);
    }
}
