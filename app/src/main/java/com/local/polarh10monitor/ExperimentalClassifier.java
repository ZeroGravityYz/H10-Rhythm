package com.local.polarh10monitor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPInputStream;

/** Bounded native inference. Votes are not calibrated clinical probabilities. */
final class ExperimentalClassifier {
    static final String VERSION="LAB-multidomain-rf16-20260905";
    private static final Tree[] TREES=load();
    private static final class Tree {
        final int[] feature,left,right;final float[] threshold;final double[][] votes;
        Tree(int n){feature=new int[n];left=new int[n];right=new int[n];threshold=new float[n];votes=new double[n][4];}
    }
    static double[] predict(float[] x){
        if(x==null||x.length!=48)return null;for(float v:x)if(!Float.isFinite(v))return null;
        double[] p=new double[4];
        for(Tree t:TREES){int i=0;while(t.feature[i]>=0)i=x[t.feature[i]]<=t.threshold[i]?t.left[i]:t.right[i];for(int c=0;c<4;c++)p[c]+=t.votes[i][c]/TREES.length;}
        return p;
    }
    static int confidentClass(double[] p){
        if(p==null)return -1;int best=0;for(int i=1;i<4;i++)if(p[i]>p[best])best=i;
        double second=0;for(int i=0;i<4;i++)if(i!=best)second=Math.max(second,p[i]);
        return p[best]>=.85&&p[best]-second>=.25?best:-1;
    }
    private static Tree[] load(){
        byte[] bytes;
        try(GZIPInputStream in=new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(ExperimentalWeights.DATA)))){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
            while((n=in.read(buffer))>=0){if(out.size()+n>100000)throw new IllegalStateException("model too large");out.write(buffer,0,n);}bytes=out.toByteArray();
        }catch(java.io.IOException error){throw new IllegalStateException("invalid model",error);}
        ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int count=Short.toUnsignedInt(b.getShort());if(count!=16)throw new IllegalStateException("forest size");Tree[] trees=new Tree[count];
        for(int k=0;k<count;k++){
            int n=Short.toUnsignedInt(b.getShort());if(n<1||n>255)throw new IllegalStateException("tree size");Tree t=new Tree(n);trees[k]=t;
            for(int i=0;i<n;i++){
                t.feature[i]=b.getShort();t.threshold[i]=b.getFloat();t.left[i]=b.getShort();t.right[i]=b.getShort();
                for(int c=0;c<4;c++)t.votes[i][c]=Short.toUnsignedInt(b.getShort())/65535.0;
                if(t.feature[i]>=0&&(t.feature[i]>=48||t.left[i]<=i||t.right[i]<=i||t.left[i]>=n||t.right[i]>=n))throw new IllegalStateException("invalid tree");
            }
        }
        if(b.hasRemaining())throw new IllegalStateException("model length");return trees;
    }
}
