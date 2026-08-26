package com.local.polarh10monitor;

/** Régression déterministe du petit modèle local dose-réponse. */
public final class AdaptiveTwinSelfTest {
    public static void main(String[] args){
        double[][]x=new double[36][10];double[]y=new double[36];
        for(int i=0;i<x.length;i++){double state=-1.2+2.4*(i%9)/8.0,load=(i%6)/3.0,sleep=((i/3)%5-2)/2.0;x[i]=new double[]{1,state,sleep,0,0,0,0,0,load,load*load};y[i]=.72*state+.18*sleep-.48*load-.08*load*load;}
        double[]easy={1,.4,.5,0,0,0,0,0,.25,.0625},hard={1,.4,.5,0,0,0,0,0,1.75,3.0625};
        double easyPrediction=AdaptiveTwin.predictForTest(x,y,easy),hardPrediction=AdaptiveTwin.predictForTest(x,y,hard);
        if(!(easyPrediction>hardPrediction+.35))throw new AssertionError("La dose apprise ne dégrade pas correctement la réponse: "+easyPrediction+" / "+hardPrediction);
        if(Math.abs(easyPrediction-(.72*.4+.18*.5-.48*.25-.08*.0625))>.25)throw new AssertionError("Prédiction synthétique trop éloignée: "+easyPrediction);
        System.out.println("Adaptive Twin OK: relation dose-réponse apprise et prédiction cohérente.");
    }
}
