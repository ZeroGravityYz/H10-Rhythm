package com.local.polarh10monitor;

import java.util.ArrayList;

public final class EcgEngineSelfTest {
    public static void main(String[] args) {
        ArrayList<EcgEngine.DetectionEvent> events=new ArrayList<>();
        EcgEngine engine=new EcgEngine(events::add);
        ArrayList<Double> beats=new ArrayList<>();double t=.7;boolean pvc=false;
        while(t<25){beats.add(t);if(!pvc&&t>=10.5){t+=.55;beats.add(-t);t+=1.05;pvc=true;}else t+=.80+.018*Math.sin(t*.8);}
        long start=1_780_000_000_000L;
        for(int i=0;i<25*EcgEngine.FS;i++){double now=i/(double)EcgEngine.FS,y=32*Math.sin(2*Math.PI*.22*now)+8*Math.sin(2*Math.PI*17*now);
            for(double marker:beats){boolean ventricular=marker<0;double bt=Math.abs(marker),dt=now-bt;if(dt<-.28)break;if(dt>.42)continue;if(ventricular)y+=-1350*Math.exp(-.5*Math.pow((dt+.025)/.045,2))+900*Math.exp(-.5*Math.pow((dt-.07)/.055,2));else y+=850*Math.exp(-.5*Math.pow(dt/.014,2))-180*Math.exp(-.5*Math.pow((dt-.028)/.018,2))+120*Math.exp(-.5*Math.pow((dt-.19)/.055,2));}
            engine.push((int)Math.round(y),start+Math.round(i*1000.0/EcgEngine.FS));
        }
        boolean esv=false;for(EcgEngine.DetectionEvent e:events){System.out.println(e.type+" | "+e.detail);if(e.type.equals("ESV"))esv=true;}
        EcgEngine.Snapshot s=engine.snapshot();System.out.println("bpm="+s.bpm+" esv="+s.esv+" events="+s.events+" signal="+s.signalGood);
        if(!esv||s.esv<1)throw new AssertionError("L’ESV synthétique n’a pas été détectée");
    }
}
