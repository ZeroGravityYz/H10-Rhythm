package com.local.polarh10monitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Décode les trames accéléromètre PMD Polar sans dépendre d'Android. */
public final class PolarAccDecoder {
    private PolarAccDecoder() {}

    public static final class Sample {
        public final int x,y,z;public final long timestampMs;
        Sample(int x,int y,int z,long timestampMs){this.x=x;this.y=y;this.z=z;this.timestampMs=timestampMs;}
    }

    public static List<Sample> decode(byte[] packet,long arrivalMs,int sampleRateHz){
        if(packet==null||packet.length<13||(packet[0]&255)!=2)return Collections.emptyList();
        int frame=packet[9]&255,frameType=frame&0x7f,bytes=frameType+1;
        if(bytes<1||bytes>3)return Collections.emptyList();
        ArrayList<int[]> values=(frame&0x80)==0?decodeRaw(packet,bytes):decodeDelta(packet,bytes);
        if(values.isEmpty())return Collections.emptyList();
        double period=1000.0/Math.max(1,sampleRateHz);ArrayList<Sample> out=new ArrayList<>(values.size());
        for(int i=0;i<values.size();i++){int[]v=values.get(i);out.add(new Sample(v[0],v[1],v[2],arrivalMs-Math.round((values.size()-1-i)*period)));}
        return out;
    }

    private static ArrayList<int[]> decodeRaw(byte[]packet,int bytes){
        ArrayList<int[]> out=new ArrayList<>();int count=(packet.length-10)/(bytes*3);
        for(int i=0;i<count;i++){int o=10+i*bytes*3;out.add(new int[]{signed(packet,o,bytes),signed(packet,o+bytes,bytes),signed(packet,o+2*bytes,bytes)});}return out;
    }

    private static ArrayList<int[]> decodeDelta(byte[]packet,int bytes){
        ArrayList<int[]> out=new ArrayList<>();if(packet.length<10+bytes*3)return out;int offset=10;
        int[]current={signed(packet,offset,bytes),signed(packet,offset+bytes,bytes),signed(packet,offset+2*bytes,bytes)};offset+=bytes*3;out.add(current.clone());
        while(offset+2<=packet.length){int header=(packet[offset]&255)|((packet[offset+1]&255)<<8);offset+=2;int bits=header&15,count=header>>>4;if(bits==0||count==0)break;int blockBytes=(bits*count*3+7)/8;if(offset+blockBytes>packet.length)break;int bit=0;
            for(int n=0;n<count;n++){for(int axis=0;axis<3;axis++){int delta=bits(packet,offset,bit,bits);bit+=bits;if((delta&(1<<(bits-1)))!=0)delta|=(-1<<bits);current[axis]+=delta;}out.add(current.clone());}offset+=blockBytes;
        }return out;
    }

    private static int signed(byte[]v,int offset,int bytes){int value=0;for(int i=0;i<bytes;i++)value|=(v[offset+i]&255)<<(8*i);int shift=32-bytes*8;return value<<shift>>shift;}
    private static int bits(byte[]v,int offset,int bitOffset,int count){int value=0;for(int i=0;i<count;i++){int absolute=bitOffset+i;if(((v[offset+absolute/8]>>(absolute%8))&1)!=0)value|=1<<i;}return value;}
}
