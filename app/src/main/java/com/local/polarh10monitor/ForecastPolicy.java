package com.local.polarh10monitor;
/** Publication gate for empirically evaluated personal predictions, not a medical threshold. */
public final class ForecastPolicy {
    private ForecastPolicy(){}
    public static boolean ready(int count,double mae,double naive,double recent){return count>=20&&Double.isFinite(mae)&&Double.isFinite(naive)&&Double.isFinite(recent)&&mae>=0&&naive>0&&recent>0&&mae<.9*naive&&mae<.9*recent;}
}
