package com.local.polarh10monitor;
public final class PmdClockSelfTest {
    static byte[] packet(int type,long ns){byte[] b=new byte[49];b[0]=(byte)type;for(int i=0;i<8;i++)b[i+1]=(byte)(ns>>>(8*i));return b;}
    static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args){PmdClock clock=new PmdClock();long t=1_000_000_000_000L,wall=1_800_000_000_000L;
        PmdClock.Frame first=clock.frame(packet(0,t),13,130,wall);require(first.accepted&&!first.gap,"first");
        PmdClock.Frame second=clock.frame(packet(0,t+100_000_000L),13,130,wall+850);require(second.accepted&&!second.gap&&second.firstMs-first.firstMs==100,"arrival jitter changed timing");
        require(!clock.frame(packet(0,t+100_000_000L),13,130,wall+900).accepted,"duplicate accepted");
        require(!clock.frame(packet(0,t),13,130,wall+950).accepted,"out of order accepted");
        PmdClock.Frame acc=clock.frame(packet(2,t+100_000_000L),5,50,wall+1900);require(acc.accepted&&acc.firstMs==wall+20,"ECG and ACC not aligned");
        require(clock.frame(packet(0,t+300_000_000L),13,130,wall+1950).gap,"missing packet hidden");
        require(!clock.frame(new byte[2],13,130,wall).accepted,"malformed");
        clock.reset();require(clock.frame(packet(0,t),13,130,wall+3000).accepted,"reconnect");
        System.out.println("PMD clock OK: jitter, duplicate, order, ECG/ACC alignment, missing packet, reconnect.");
    }
}
