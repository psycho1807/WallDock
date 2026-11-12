package app.walldock.sendspin;

/**
 * Filtre de temps Sendspin (filtre de Kalman 2D : décalage et dérive entre l'horloge du
 * serveur et celle de la tablette), exactement comme la spécification. Temps en microsecondes.
 */
public final class TimeFilter {

    private static final double PROCESS_STD_DEV = 0.0;
    private static final double DRIFT_PROCESS_STD_DEV = 1e-11;
    private static final double FORGET_FACTOR = 2.0;
    private static final double ADAPTIVE_CUTOFF = 3.0;
    private static final int MIN_SAMPLES = 100;
    private static final double DRIFT_SIGNIFICANCE = 2.0;
    private static final double MAX_ERROR_SCALE = 0.5;

    private double offset;
    private double drift;
    private double pOO = Double.POSITIVE_INFINITY;
    private double pOD;
    private double pDD;
    private long lastUpdate;
    private int count;
    private boolean useDrift;

    public synchronized void reset() {
        offset = 0; drift = 0; pOO = Double.POSITIVE_INFINITY; pOD = 0; pDD = 0;
        lastUpdate = 0; count = 0; useDrift = false;
    }

    public synchronized int count() {
        return count;
    }

    /** Mesure (T1 envoi client, T2 réception serveur, T3 envoi serveur, T4 réception client). */
    public static long[] measure(long t1, long t2, long t3, long t4) {
        long measurement = ((t2 - t1) + (t3 - t4)) / 2;
        long maxError = ((t4 - t1) - (t3 - t2)) / 2;
        return new long[]{measurement, maxError, t4};
    }

    public synchronized void update(long measurement, long maxError, long timeAdded) {
        if (timeAdded <= lastUpdate || maxError <= 0) return;
        double dt = timeAdded - lastUpdate;
        lastUpdate = timeAdded;
        double r = Math.pow(MAX_ERROR_SCALE * maxError, 2);
        if (count == 0) {
            offset = measurement; pOO = r; drift = 0; count = 1;
            return;
        }
        if (count == 1) {
            drift = (measurement - offset) / dt;
            pDD = (pOO + r) / (dt * dt);
            offset = measurement; pOO = r; count = 2;
            return;
        }
        double offsetP = offset + drift * dt;
        double pOOp = pOO + 2 * pOD * dt + pDD * dt * dt + PROCESS_STD_DEV * PROCESS_STD_DEV * dt;
        double pODp = pOD + pDD * dt;
        double pDDp = pDD + DRIFT_PROCESS_STD_DEV * DRIFT_PROCESS_STD_DEV * dt;
        double y = measurement - offsetP;
        if (count < MIN_SAMPLES) {
            count++;
        } else if (Math.abs(y) > ADAPTIVE_CUTOFF * maxError) {
            double f = FORGET_FACTOR * FORGET_FACTOR;
            pOOp *= f; pODp *= f; pDDp *= f;
        }
        double s = pOOp + r;
        double kO = pOOp / s;
        double kD = pODp / s;
        offset = offsetP + kO * y;
        drift = drift + kD * y;
        pOO = pOOp - kO * pOOp;
        pOD = pODp - kD * pOOp;
        pDD = pDDp - kD * pODp;
        useDrift = drift * drift > DRIFT_SIGNIFICANCE * DRIFT_SIGNIFICANCE * pDD;
    }

    public synchronized long serverTime(long clientTime) {
        double d = useDrift ? drift : 0;
        return clientTime + Math.round(offset + d * (clientTime - lastUpdate));
    }

    public synchronized long clientTime(long serverTime) {
        double d = useDrift ? drift : 0;
        return Math.round((serverTime - offset + d * lastUpdate) / (1 + d));
    }
}
