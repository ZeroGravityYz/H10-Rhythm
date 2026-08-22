package com.local.polarh10monitor;

import java.util.Locale;

/** Modèle métrique personnel, compact et entièrement local. */
public final class MorphologyModel {
    public static final int DIMENSIONS = 16;
    public static final int BASELINE_TARGET = 500;

    private int normalCount;
    private int confirmedCount;
    private int artifactCount;
    private final double[] normalMean = new double[DIMENSIONS];
    private final double[] normalM2 = new double[DIMENSIONS];
    private final double[] confirmedMean = new double[DIMENSIONS];
    private final double[] artifactMean = new double[DIMENSIONS];
    private double distanceMean;
    private double distanceM2;

    public synchronized void observeNormal(float[] vector) {
        if (!valid(vector) || normalCount >= BASELINE_TARGET) return;
        addNormal(vector);
    }

    public synchronized void confirmNormal(float[] vector) {
        if (!valid(vector)) return;
        addNormal(vector);
    }

    public synchronized void confirmAnomaly(float[] vector) {
        if (!valid(vector)) return;
        confirmedCount++;
        for (int i = 0; i < DIMENSIONS; i++) {
            confirmedMean[i] += (vector[i] - confirmedMean[i]) / confirmedCount;
        }
    }

    /** Les exemples marqués comme artefacts forment un prototype séparé et ne polluent jamais la baseline sinusale. */
    public synchronized void confirmArtifact(float[] vector) {
        if (!valid(vector)) return;
        artifactCount++;
        for (int i = 0; i < DIMENSIONS; i++) {
            artifactMean[i] += (vector[i] - artifactMean[i]) / artifactCount;
        }
    }

    private void addNormal(float[] vector) {
        double previousDistance = normalCount == 0 ? 0 : distance(vector, normalMean);
        normalCount++;
        for (int i = 0; i < DIMENSIONS; i++) {
            double delta = vector[i] - normalMean[i];
            normalMean[i] += delta / normalCount;
            normalM2[i] += delta * (vector[i] - normalMean[i]);
        }
        if (normalCount > 20) {
            int n = normalCount - 20;
            double delta = previousDistance - distanceMean;
            distanceMean += delta / n;
            distanceM2 += delta * (previousDistance - distanceMean);
        }
    }

    public synchronized Result evaluate(float[] vector) {
        if (!valid(vector) || normalCount < 20) return new Result(0, threshold(), false, false, false, confirmedCount > 0, artifactCount > 0);
        double score = distance(vector, normalMean);
        boolean ready = isReady();
        boolean anomaly = ready && score > threshold();
        boolean artifact = false;
        if (ready && confirmedCount > 0) {
            double positiveDistance = distance(vector, confirmedMean);
            double normalDistance = Math.max(1e-6, score);
            anomaly = anomaly || positiveDistance < normalDistance * 0.88;
        }
        if (artifactCount > 0) {
            double artifactDistance = distance(vector, artifactMean);
            artifact = artifactDistance < Math.max(0.08, score * 0.82);
            if (artifact) anomaly = false;
        }
        return new Result(score, threshold(), ready, anomaly, artifact, confirmedCount > 0, artifactCount > 0);
    }

    public synchronized boolean isReady() { return normalCount >= BASELINE_TARGET; }
    public synchronized int normalCount() { return normalCount; }
    public synchronized int confirmedCount() { return confirmedCount; }
    public synchronized int artifactCount() { return artifactCount; }

    public synchronized double threshold() {
        int n = normalCount - 20;
        double sd = n > 1 ? Math.sqrt(Math.max(0, distanceM2 / (n - 1))) : 0;
        return clamp(distanceMean + 4.0 * sd, 0.10, 0.34);
    }

    public synchronized void reset() {
        normalCount = confirmedCount = artifactCount = 0;
        distanceMean = distanceM2 = 0;
        for (int i = 0; i < DIMENSIONS; i++) {
            normalMean[i] = normalM2[i] = confirmedMean[i] = artifactMean[i] = 0;
        }
    }

    public synchronized String serialize() {
        return normalCount + "|" + confirmedCount + "|" + format(distanceMean) + "|" + format(distanceM2)
                + "|" + join(normalMean) + "|" + join(normalM2) + "|" + join(confirmedMean)
                + "|" + artifactCount + "|" + join(artifactMean);
    }

    public static MorphologyModel deserialize(String encoded) {
        MorphologyModel model = new MorphologyModel();
        if (encoded == null || encoded.isEmpty()) return model;
        try {
            String[] parts = encoded.split("\\|", -1);
            if (parts.length != 7 && parts.length != 9) return model;
            model.normalCount = Math.max(0, Integer.parseInt(parts[0]));
            model.confirmedCount = Math.max(0, Integer.parseInt(parts[1]));
            model.distanceMean = Double.parseDouble(parts[2]);
            model.distanceM2 = Double.parseDouble(parts[3]);
            parse(parts[4], model.normalMean);
            parse(parts[5], model.normalM2);
            parse(parts[6], model.confirmedMean);
            if (parts.length == 9) {
                model.artifactCount = Math.max(0, Integer.parseInt(parts[7]));
                parse(parts[8], model.artifactMean);
            }
        } catch (RuntimeException ignored) {
            model.reset();
        }
        return model;
    }

    private static boolean valid(float[] vector) {
        if (vector == null || vector.length != DIMENSIONS) return false;
        for (float value : vector) if (!Float.isFinite(value)) return false;
        return true;
    }

    private static double distance(float[] vector, double[] prototype) {
        double sum = 0;
        for (int i = 0; i < DIMENSIONS; i++) {
            double delta = vector[i] - prototype[i];
            sum += delta * delta;
        }
        return Math.sqrt(sum / DIMENSIONS);
    }

    private static String join(double[] values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append(',');
            out.append(format(values[i]));
        }
        return out.toString();
    }

    private static void parse(String encoded, double[] target) {
        String[] values = encoded.split(",", -1);
        if (values.length != target.length) throw new IllegalArgumentException("dimensions");
        for (int i = 0; i < target.length; i++) target[i] = Double.parseDouble(values[i]);
    }

    private static String format(double value) { return String.format(Locale.ROOT, "%.10g", value); }
    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }

    public static final class Result {
        public final double score;
        public final double threshold;
        public final boolean ready;
        public final boolean anomaly;
        public final boolean artifact;
        public final boolean fewShotReady;
        public final boolean artifactReady;

        Result(double score, double threshold, boolean ready, boolean anomaly, boolean artifact,
               boolean fewShotReady, boolean artifactReady) {
            this.score = score;
            this.threshold = threshold;
            this.ready = ready;
            this.anomaly = anomaly;
            this.artifact = artifact;
            this.fewShotReady = fewShotReady;
            this.artifactReady = artifactReady;
        }
    }
}
