package com.local.polarh10monitor;

import java.util.Locale;

/** Modèle métrique personnel, compact et entièrement local. */
public final class MorphologyModel {
    public static final int DIMENSIONS = 16;
    public static final int BASELINE_TARGET = 500;

    private final java.util.ArrayList<float[]> artifactBank=new java.util.ArrayList<>(),anomalyBank=new java.util.ArrayList<>();
    private static void remember(java.util.ArrayList<float[]> bank,float[] v){if(bank.size()<64)bank.add(v.clone());}
    private static double nearest(float[] v,java.util.ArrayList<float[]> bank,double fallback){double best=bank.isEmpty()?fallback:Double.MAX_VALUE;for(float[] item:bank){double sum=0;for(int i=0;i<v.length;i++)sum+=Math.pow(v[i]-item[i],2);best=Math.min(best,Math.sqrt(sum/v.length));}return best;}
    private int normalCount;
    private int confirmedCount;
    private int artifactCount;
    private int reviewedNormalCount;
    private final double[] normalMean = new double[DIMENSIONS];
    private final double[] normalM2 = new double[DIMENSIONS];
    private final double[] confirmedMean = new double[DIMENSIONS];
    private final double[] artifactMean = new double[DIMENSIONS];
    private final double[] reviewedNormalMean = new double[DIMENSIONS];
    private double distanceMean;
    private double distanceM2;

    public synchronized void observeNormal(float[] vector) {
        if (!valid(vector) || normalCount >= BASELINE_TARGET) return;
        addNormal(vector);
    }

    public synchronized void confirmNormal(float[] vector) {
        if (!valid(vector)) return;
        reviewedNormalCount++;
        addToMean(vector, reviewedNormalMean, reviewedNormalCount);
    }

    public synchronized void confirmAnomaly(float[] vector) {
        if (!valid(vector)) return;
        remember(anomalyBank,vector);confirmedCount++;
        addToMean(vector, confirmedMean, confirmedCount);
    }

    /** Les exemples marqués comme artefacts forment un prototype séparé et ne polluent jamais la baseline sinusale. */
    public synchronized void confirmArtifact(float[] vector) {
        if (!valid(vector)) return;
        remember(artifactBank,vector);artifactCount++;
        addToMean(vector, artifactMean, artifactCount);
    }

    /** Reconstruit les exemples utilisateurs depuis l'historique afin qu'une annotation soit unique et réversible. */
    public synchronized void clearFeedback() {
        confirmedCount = artifactCount = reviewedNormalCount = 0;artifactBank.clear();anomalyBank.clear();
        for (int i = 0; i < DIMENSIONS; i++) {
            confirmedMean[i] = artifactMean[i] = reviewedNormalMean[i] = 0;
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
        boolean artifact = false,ambiguous=false;
        if (ready && confirmedCount > 0) {
            double positiveDistance = nearest(vector,anomalyBank,distance(vector, confirmedMean));
            double normalDistance = Math.max(1e-6, score);
            anomaly = anomaly || positiveDistance < normalDistance * 0.88;
        }
        if (reviewedNormalCount > 0) {
            double reviewedNormalDistance = distance(vector, reviewedNormalMean);
            if (reviewedNormalDistance < Math.max(0.08, score * 0.86)) anomaly = false;
        }
        if (artifactCount > 0) {
            double artifactDistance = nearest(vector,artifactBank,distance(vector, artifactMean));
            double reviewedNormalDistance = reviewedNormalCount > 0 ? distance(vector, reviewedNormalMean) : Double.MAX_VALUE;
            artifact = score>threshold()*.65 && artifactDistance < Math.max(0.08, score * 0.82) && artifactDistance < reviewedNormalDistance * 0.92;
            if(artifact&&confirmedCount>0){double positiveDistance=nearest(vector,anomalyBank,distance(vector,confirmedMean));ambiguous=positiveDistance<Math.max(.08,artifactDistance*1.2);if(ambiguous)artifact=false;}
            if (artifact) anomaly = false;
        }
        return new Result(score, threshold(), ready, anomaly, artifact, confirmedCount > 0, artifactCount > 0,ambiguous);
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
        normalCount = confirmedCount = artifactCount = reviewedNormalCount = 0;artifactBank.clear();anomalyBank.clear();
        distanceMean = distanceM2 = 0;
        for (int i = 0; i < DIMENSIONS; i++) {
            normalMean[i] = normalM2[i] = confirmedMean[i] = artifactMean[i] = reviewedNormalMean[i] = 0;
        }
    }

    public synchronized String serialize() {
        return normalCount + "|" + confirmedCount + "|" + format(distanceMean) + "|" + format(distanceM2)
                + "|" + join(normalMean) + "|" + join(normalM2) + "|" + join(confirmedMean)
                + "|" + artifactCount + "|" + join(artifactMean)
                + "|" + reviewedNormalCount + "|" + join(reviewedNormalMean) + "|" + joinBank(artifactBank) + "|" + joinBank(anomalyBank);
    }

    public static MorphologyModel deserialize(String encoded) {
        MorphologyModel model = new MorphologyModel();
        if (encoded == null || encoded.isEmpty()) return model;
        try {
            String[] parts = encoded.split("\\|", -1);
            if (parts.length != 7 && parts.length != 9 && parts.length != 11 && parts.length != 13) return model;
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
            if (parts.length >= 11) {
                model.artifactCount = Math.max(0, Integer.parseInt(parts[7]));
                parse(parts[8], model.artifactMean);
                model.reviewedNormalCount = Math.max(0, Integer.parseInt(parts[9]));
                parse(parts[10], model.reviewedNormalMean);
            }
            if(parts.length==13){parseBank(parts[11],model.artifactBank);parseBank(parts[12],model.anomalyBank);}
            if(!Double.isFinite(model.distanceMean)||!Double.isFinite(model.distanceM2))throw new IllegalArgumentException("non-finite");
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

    private static void addToMean(float[] vector, double[] mean, int count) {
        for (int i = 0; i < DIMENSIONS; i++) mean[i] += (vector[i] - mean[i]) / count;
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
        for (int i = 0; i < target.length; i++) {target[i] = Double.parseDouble(values[i]);if(!Double.isFinite(target[i]))throw new IllegalArgumentException("non-finite");}
    }

    private static String joinBank(java.util.ArrayList<float[]> bank){StringBuilder b=new StringBuilder();for(float[] v:bank){if(b.length()>0)b.append(';');double[] a=new double[v.length];for(int i=0;i<v.length;i++)a[i]=v[i];b.append(join(a));}return b.toString();}
    private static void parseBank(String s,java.util.ArrayList<float[]> bank){if(s.isEmpty())return;String[] rows=s.split(";");if(rows.length>64)throw new IllegalArgumentException("bank");for(String row:rows){double[] a=new double[DIMENSIONS];parse(row,a);float[] v=new float[DIMENSIONS];for(int i=0;i<v.length;i++)v[i]=(float)a[i];if(!valid(v))throw new IllegalArgumentException("vector");bank.add(v);}}
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
        public final boolean ambiguous;

        Result(double score, double threshold, boolean ready, boolean anomaly, boolean artifact,
               boolean fewShotReady, boolean artifactReady) {
            this(score,threshold,ready,anomaly,artifact,fewShotReady,artifactReady,false);
        }
        Result(double score,double threshold,boolean ready,boolean anomaly,boolean artifact,boolean fewShotReady,boolean artifactReady,boolean ambiguous){
            this.ambiguous=ambiguous;
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
