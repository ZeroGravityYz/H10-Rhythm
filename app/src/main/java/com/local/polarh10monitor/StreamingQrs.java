package com.local.polarh10monitor;

import java.util.Arrays;

/** Experimental causal energy detector. No future input, fixed bounded memory.
 * Linear-phase 5-20 Hz FIR, 100 ms RMS envelope, 62 ms peak confirmation.
 * Not an XQRS port: offline filtfilt results must not be attributed to this class.
 * All returned positions refer to the original 130 Hz sample clock.
 */
public final class StreamingQrs {
    private static final int FS=130, TAPS=41, DELAY=20, WINDOW=13, RADIUS=8, SIZE=1024;
    private final double[] kernel=new double[TAPS], input=new double[SIZE], band=new double[SIZE], energy=new double[SIZE];
    private final double[] intervals=new double[8];
    private long index=-1,last=-100000,segmentStart,weakAt=-1;
    private double sum,signal=160,noise=10,lastStrength=160,weakStrength;
    private int intervalCount,intervalPosition;

    public StreamingQrs(){
        double gain=0;
        for(int i=0;i<TAPS;i++){
            int n=i-DELAY;
            double h=n==0?2*(20.0-5)/FS:(Math.sin(2*Math.PI*20*n/FS)-Math.sin(2*Math.PI*5*n/FS))/(Math.PI*n);
            kernel[i]=h*(.54-.46*Math.cos(2*Math.PI*i/(TAPS-1)));
            gain+=kernel[i]*Math.cos(2*Math.PI*12*n/FS);
        }
        for(int i=0;i<TAPS;i++)kernel[i]/=gain;
    }

    public void reset(long nextIndex){
        Arrays.fill(input,0);Arrays.fill(band,0);Arrays.fill(energy,0);Arrays.fill(intervals,0);
        index=nextIndex-1;segmentStart=nextIndex;last=nextIndex-100000;sum=0;signal=160;noise=10;lastStrength=160;
        weakAt=-1;weakStrength=0;intervalCount=intervalPosition=0;
    }

    public long push(int rawUv){
        index++;input[slot(index)]=Math.max(-6000,Math.min(6000,rawUv));
        double y=0;for(int i=0;i<TAPS;i++)y+=kernel[i]*input[slot(index-i)];
        band[slot(index)]=y;
        double old=band[slot(index-WINDOW)];sum=Math.max(0,sum+y*y-old*old);
        energy[slot(index)]=Math.sqrt(sum/WINDOW);
        if(index-segmentStart<TAPS+WINDOW+RADIUS)return -1;
        long p=index-RADIUS;double strength=energy[slot(p)];
        boolean peak=strength>0;
        for(int k=1;k<=RADIUS&&peak;k++)peak=strength>energy[slot(p-k)]&&strength>=energy[slot(p+k)];
        if(peak){
            // Envelope center is delayed by half of its integration window.
            long center=p-(WINDOW-1)/2,best=center;double amp=0;
            for(long j=center-8;j<=center+8;j++){double a=Math.abs(band[slot(j)]);if(a>amp){amp=a;best=j;}}
            long at=best-DELAY;
            double threshold=Math.max(22,noise+.25*(signal-noise));
            boolean refractory=at-last<Math.round(.20*FS);
            boolean repolarization=at-last<Math.round(.36*FS)&&strength<lastStrength*.45;
            if(!refractory&&!repolarization&&strength>=threshold){return accept(at,strength);}
            if(!refractory&&!repolarization){
                noise=.98*noise+.02*Math.min(strength,signal);
                if(strength>=threshold*.5&&strength>weakStrength){weakAt=at;weakStrength=strength;}
            }
        }
        // Recover a missed low-amplitude beat; never replay across a reset.
        double rr=medianInterval();
        if(intervalCount>=3&&index-DELAY-last>Math.max(.9*FS,1.66*rr)&&weakAt>last+FS*.30
                &&index-DELAY-weakAt<rr*1.66&&weakStrength>=Math.max(18,(noise+.25*(signal-noise))*.5)){
            return accept(weakAt,weakStrength);
        }
        return -1;
    }

    private long accept(long at,double strength){
        double rr=at-last;
        if(rr>=FS*.28&&rr<=FS*2.4){intervals[intervalPosition++%intervals.length]=rr;intervalCount=Math.min(intervals.length,intervalCount+1);}
        last=at;lastStrength=strength;signal=.875*signal+.125*strength;
        weakAt=-1;weakStrength=0;return at;
    }
    private double medianInterval(){if(intervalCount==0)return FS*.8;double[] x=Arrays.copyOf(intervals,intervalCount);Arrays.sort(x);return x[x.length/2];}
    private static int slot(long i){return (int)Math.floorMod(i,SIZE);}
}
