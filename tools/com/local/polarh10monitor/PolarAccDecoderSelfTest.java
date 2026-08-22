package com.local.polarh10monitor;

import java.util.List;

public final class PolarAccDecoderSelfTest {
    public static void main(String[]args){
        byte[]raw=new byte[22];raw[0]=2;raw[9]=1;put16(raw,10,120);put16(raw,12,-240);put16(raw,14,1000);put16(raw,16,-30);put16(raw,18,40);put16(raw,20,980);
        List<PolarAccDecoder.Sample> samples=PolarAccDecoder.decode(raw,10_000,50);require(samples.size()==2&&samples.get(0).x==120&&samples.get(0).y==-240&&samples.get(1).z==980,"raw 16-bit");require(samples.get(0).timestampMs==9980&&samples.get(1).timestampMs==10000,"timestamps");

        byte[]compressed=new byte[22];compressed[0]=2;compressed[9]=(byte)0x81;put16(compressed,10,100);put16(compressed,12,-200);put16(compressed,14,1000);compressed[16]=0x24;compressed[17]=0;int[]deltas={1,-2,3,-1,1,-3};int bit=0;for(int delta:deltas){int value=delta&15;for(int b=0;b<4;b++,bit++)if(((value>>b)&1)!=0)compressed[18+bit/8]|=1<<(bit%8);}
        samples=PolarAccDecoder.decode(compressed,20_000,50);require(samples.size()==3,"delta count");require(samples.get(1).x==101&&samples.get(1).y==-202&&samples.get(1).z==1003,"delta sample 1");require(samples.get(2).x==100&&samples.get(2).y==-201&&samples.get(2).z==1000,"delta sample 2");
        System.out.println("Polar ACC decoder: raw, compressed delta, signed values and timestamps OK");
    }
    private static void put16(byte[]v,int o,int n){v[o]=(byte)n;v[o+1]=(byte)(n>>8);}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
