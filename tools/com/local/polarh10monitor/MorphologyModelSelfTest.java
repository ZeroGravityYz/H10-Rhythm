package com.local.polarh10monitor;

public final class MorphologyModelSelfTest {
    public static void main(String[] args) {
        MorphologyModel model=new MorphologyModel();
        for(int n=0;n<MorphologyModel.BASELINE_TARGET;n++){
            float[] vector=new float[MorphologyModel.DIMENSIONS];
            for(int i=0;i<vector.length;i++)vector[i]=(float)(Math.sin(i*.45)+(n%7-3)*.001);
            model.observeNormal(vector);
        }
        if(!model.isReady())throw new AssertionError("baseline not ready");
        float[] unusual=new float[MorphologyModel.DIMENSIONS];
        for(int i=0;i<unusual.length;i++)unusual[i]=(i%2==0?2.2f:-2.2f);
        if(!model.evaluate(unusual).anomaly)throw new AssertionError("unusual morphology missed");
        MorphologyModel restored=MorphologyModel.deserialize(model.serialize());
        if(restored.normalCount()!=MorphologyModel.BASELINE_TARGET||!restored.evaluate(unusual).anomaly)throw new AssertionError("persistence failed");
        restored.confirmAnomaly(unusual);
        if(restored.confirmedCount()!=1)throw new AssertionError("few-shot feedback failed");
        System.out.println("Morphology model: baseline, anomaly, persistence and feedback OK");
    }
}
