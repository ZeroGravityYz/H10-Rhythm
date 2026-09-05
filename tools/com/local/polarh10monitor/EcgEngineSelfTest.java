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
        boolean esv=false;for(EcgEngine.DetectionEvent e:events){System.out.println(e.type+" | "+e.detail);if(e.type.equals("ESV")||e.type.equals("PREMATURE"))esv=true;}
        EcgEngine.Snapshot s=engine.snapshot();System.out.println("bpm="+s.bpm+" esv="+s.esv+" events="+s.events+" signal="+s.signalGood);
        if(!esv)throw new AssertionError("L’ESV synthétique n’a pas été signalée pour relecture");

        ArrayList<EcgEngine.DetectionEvent> contactEvents=new ArrayList<>();EcgEngine contactEngine=new EcgEngine(contactEvents::add);
        for(int i=0;i<30*EcgEngine.FS;i++){double now=i/(double)EcgEngine.FS,y=25*Math.sin(2*Math.PI*.2*now);double phase=(now-.7-.015*Math.sin(now*.5))%0.8;if(phase<0)phase+=.8;y+=900*Math.exp(-.5*Math.pow(phase/.014,2))-170*Math.exp(-.5*Math.pow((phase-.03)/.018,2));if(i==12*EcgEngine.FS+17)y=120000;if(i==12*EcgEngine.FS+18)y=-90000;contactEngine.push((int)Math.round(y),start+Math.round(i*1000.0/EcgEngine.FS));}
        EcgEngine.Snapshot contact=contactEngine.snapshot();
        if(!contactEvents.isEmpty())throw new AssertionError("Le choc de contact a créé une fausse alerte : "+contactEvents.get(0).type);
        if(contact.signalQualityPercent>=100)throw new AssertionError("Le choc de contact n’a pas dégradé le SQI");
        if(contact.rmssdMs<=0||contact.sdnnMs<=0)throw new AssertionError("La VFC n’a pas été calculée");
        System.out.println("Contact artifact rejected; no false alert/pause; HRV available");
    }
}
