package com.local.polarh10monitor;
import java.lang.reflect.*;
import java.util.*;
public final class ReliabilitySelfTest {
    private static void check(boolean condition,String text){if(!condition)throw new AssertionError(text);}
    public static void main(String[] args)throws Exception{
        check(!ForecastPolicy.ready(19,.1,1,1),"premature forecast publication");
        check(!ForecastPolicy.ready(20,.2,1,.2),"did not beat both baselines");
        check(!ForecastPolicy.ready(20,Double.NaN,1,1),"non-finite forecast");
        check(ForecastPolicy.ready(20,.2,.5,.4),"valid prospective gate");
        MorphologyModel model=new MorphologyModel();for(int i=0;i<500;i++)model.observeNormal(new float[16]);
        float[] a=new float[16],b=new float[16];Arrays.fill(a,1);Arrays.fill(b,-1);
        model.confirmArtifact(a);model.confirmArtifact(b);
        MorphologyModel restored=MorphologyModel.deserialize(model.serialize());check(restored.evaluate(a).artifact&&restored.evaluate(b).artifact,"opposite artifacts averaged or forgotten");
        restored.confirmAnomaly(a);check(restored.evaluate(a).ambiguous&&!restored.evaluate(a).artifact,"conflicting labels silently suppress alert");
        restored.reset();check(restored.artifactCount()==0&&!restored.evaluate(a).artifact,"reset");
        check(!MorphologyModel.deserialize("500|0|NaN|0||||0|||||").isReady(),"invalid persistence accepted");
        EcgEngine engine=new EcgEngine(e->{throw new AssertionError("Unexpected event "+e.type);});
        Class<?> beatClass=Class.forName("com.local.polarh10monitor.EcgEngine$Beat");
        Constructor<?> constructor=beatClass.getDeclaredConstructor(long.class,long.class,double.class,boolean.class);constructor.setAccessible(true);
        Field storage=EcgEngine.class.getDeclaredField("beats");storage.setAccessible(true);
        @SuppressWarnings("unchecked") List<Object> beats=(List<Object>)storage.get(engine);
        Field rr=beatClass.getDeclaredField("rr"),classified=beatClass.getDeclaredField("classified"),label=beatClass.getDeclaredField("label");
        rr.setAccessible(true);classified.setAccessible(true);label.setAccessible(true);
        check(label.getChar(constructor.newInstance(0L,0L,0.0,true))=='?',"Unclassified beat defaulted to normal");
        for(int i=0;i<28;i++){Object beat=constructor.newInstance(i*130L,i*1000L,1000.0,i!=12);rr.setDouble(beat,i<13?800:1200);classified.setBoolean(beat,true);label.setChar(beat,'N');beats.add(beat);}
        EcgEngine.FitnessMetrics metrics=engine.fitnessMetricsSince(0);
        check(metrics.nnCount==24,"Unexpected valid NN count: "+metrics.nnCount);check(metrics.rmssdMs==0,"RMSSD bridged excluded intervals: "+metrics.rmssdMs);check(metrics.sdnnMs>0,"SDNN missing");
        engine.discontinuity();check(engine.fitnessMetricsSince(0).nnCount==0&&engine.snapshot().bpm==0,"Gap retained stale RR");
        for(int i=0;i<130*4;i++)engine.push(0,1_800_000_000_000L+i*1000/130);
        check(engine.snapshot().pauses==0,"Data loss interpreted as pause");
        System.out.println("Reliability OK: multi-artifact persistence, conflicting feedback, reset, contiguous NN HRV, no pause across gap.");
    }
}
