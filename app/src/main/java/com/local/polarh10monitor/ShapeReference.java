package com.local.polarh10monitor;

import java.util.Arrays;

/** Session-only morphology reference, NOT a diagnosis and NOT supervised clinical ML.
 * Similarity is correlation, never a calibrated probability of health.
 * Kept separate from persistent user feedback; reset after each signal discontinuity.
 */
final class ShapeReference {
    static final int SIZE=40;
    private final double[] mean=new double[SIZE];
    private double width;
    private int count;
    void reset(){Arrays.fill(mean,0);width=0;count=0;}
    boolean ready(){return count>=20;}
    double width(){return width;}
    void observe(float[] x,double w){
        if(x==null||count>=500)return;
        double norm=0;for(float v:x){if(!Float.isFinite(v))return;norm+=v*v;}if(norm<.1)return;
        count++;for(int i=0;i<SIZE;i++)mean[i]+=(x[i]-mean[i])/count;width+=(w-width)/count;
    }
    double similarity(float[] x){
        if(!ready()||x==null)return Double.NaN;
        double dot=0,a=0,b=0;for(int i=0;i<SIZE;i++){dot+=mean[i]*x[i];a+=mean[i]*mean[i];b+=x[i]*x[i];}
        return a*b>1e-12?Math.max(-1,Math.min(1,dot/Math.sqrt(a*b))):0;
    }
}
