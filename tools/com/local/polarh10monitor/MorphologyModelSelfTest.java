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
        float[] artifact=new float[MorphologyModel.DIMENSIONS];
        for(int i=0;i<artifact.length;i++)artifact[i]=(i<3?3.5f:-.4f);
        restored.confirmArtifact(artifact);
        restored.confirmArtifact(artifact);
        if(!restored.evaluate(artifact).artifact)throw new AssertionError("artifact prototype failed");
        restored.clearFeedback();
        restored.confirmArtifact(artifact);
        if(restored.artifactCount()!=1)throw new AssertionError("unique feedback rebuild failed");
        restored.clearFeedback();
        restored.confirmAnomaly(unusual);
        if(restored.artifactCount()!=0||restored.confirmedCount()!=1)throw new AssertionError("reversible feedback failed");
        restored.clearFeedback();
        restored.confirmArtifact(artifact);
        MorphologyModel withArtifact=MorphologyModel.deserialize(restored.serialize());
        if(withArtifact.artifactCount()!=1||!withArtifact.evaluate(artifact).artifact)throw new AssertionError("artifact persistence failed");
        System.out.println("Morphology model: baseline, anomaly, artifact, persistence, unique and reversible feedback OK");
    }
}
