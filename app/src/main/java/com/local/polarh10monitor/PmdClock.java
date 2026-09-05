package com.local.polarh10monitor;

/** Maps the sensor clock to wall time once; arrival jitter cannot change sample intervals. */
public final class PmdClock {
    private long anchorNs=Long.MIN_VALUE,anchorWall;
    private final long[] last=new long[3];
    public void reset(){anchorNs=Long.MIN_VALUE;java.util.Arrays.fill(last,0);}
    public static long timestamp(byte[] packet){long n=0;for(int i=0;i<8;i++)n|=(packet[1+i]&255L)<<(8*i);return n;}
    public Frame frame(byte[] packet,int count,int hz,long arrival){
        if(packet==null||packet.length<10||count<1||hz<1)return new Frame(false,false,0,0);
        int type=packet[0]&255;if(type>=last.length)return new Frame(false,false,0,0);
        long end=timestamp(packet),period=Math.round(1_000_000_000.0/hz);
        if(end<=0)return new Frame(false,true,0,0);
        if(anchorNs==Long.MIN_VALUE){anchorNs=end;anchorWall=arrival;}
        if(last[type]!=0&&end<=last[type])return new Frame(false,false,0,0);
        boolean gap=last[type]!=0&&Math.abs((end-last[type])-count*period)>period*2;
        last[type]=end;long first=end-(count-1)*period;
        return new Frame(true,gap,anchorWall+Math.round((first-anchorNs)/1_000_000.0),end);
    }
    public static final class Frame{public final boolean accepted,gap;public final long firstMs,endNs;Frame(boolean a,boolean g,long f,long e){accepted=a;gap=g;firstMs=f;endNs=e;}}
}
