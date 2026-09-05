package com.local.polarh10monitor;
import java.nio.file.*;
import java.util.*;
public final class ExperimentalSelfTest {
    public static void main(String[] args)throws Exception{
        if(ExperimentPolicy.PHYSICAL_CARDIAC_ALERTS)throw new AssertionError("Unvalidated models enabled physical alerts");
        float[] bad=new float[48];bad[0]=Float.NaN;
        if(ExperimentalClassifier.predict(bad)!=null)throw new AssertionError("NaN accepted");
        if(ExperimentalClassifier.confidentClass(new double[]{.45,.44,.10,.01})!=-1)throw new AssertionError("Uncertain class accepted");
        for(double p:ExperimentalClassifier.predict(new float[48]))if(!Double.isFinite(p)||p<0||p>1)throw new AssertionError("invalid vote");
        StreamingQrs a=new StreamingQrs(),b=new StreamingQrs();Random r=new Random(19);long last=-1;
        for(int i=0;i<130*120;i++){
            double t=i/130.0,phase=t%0.8;int v=(int)(900*Math.exp(-.5*Math.pow((phase-.2)/.015,2))+r.nextGaussian()*5);
            long x=a.push(v),y=b.push(v);if(x!=y)throw new AssertionError("not deterministic");
            if(x>=0){if(x<=last||x>i||i-x>65)throw new AssertionError("causality/index/latency");last=x;}
        }
        a.reset(50000);for(int i=0;i<500;i++)if(a.push(0)>=0)throw new AssertionError("stale detection across gap");
        if(args.length>0){double max=0;int n=0;for(String line:Files.readAllLines(Path.of(args[0]))){String[] s=line.split(",");float[] x=new float[48];for(int i=0;i<48;i++)x[i]=Float.parseFloat(s[i]);double[] p=ExperimentalClassifier.predict(x);for(int i=0;i<4;i++)max=Math.max(max,Math.abs(p[i]-Double.parseDouble(s[48+i])));n++;}if(max>1e-4)throw new AssertionError("Export parity: "+max);System.out.println("Python/Java parity: "+n+" vectors, max error="+max);}
        System.out.println("Experimental inference: finite input, abstention, causal QRS, bounded confirmation delay, reset OK.");
    }
}
