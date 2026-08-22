package com.local.polarh10monitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Banc de test continu et déterministe : rythme, ectopie et artefacts traversent le pipeline complet. */
public final class EcgScenarioSuite {
    private static final long START=1_780_000_000_000L;
    private static final class Beat { final double time;final boolean ventricular;Beat(double time,boolean ventricular){this.time=time;this.ventricular=ventricular;} }
    private interface Noise { double at(double time,int sample); }
    private interface Movement { int[] at(double time,int sample); }
    private static final class Outcome { final ArrayList<EcgEngine.DetectionEvent> events;final EcgEngine.Snapshot snapshot;Outcome(ArrayList<EcgEngine.DetectionEvent> e,EcgEngine.Snapshot s){events=e;snapshot=s;} }

    public static void main(String[] args){
        Outcome normal=run(70,regular(70,.80,.035),null,new MorphologyModel());
        require(normal.events.isEmpty(),"rythme sinusal variable",normal);
        if(normal.snapshot.rmssdMs<=0)throw new AssertionError("VFC absente sur rythme variable");

        ArrayList<Beat> pvc=regular(35,.80,.015);replaceWithPremature(pvc,12.0,.53,1.07,true);
        Outcome pvcResult=run(35,pvc,null,new MorphologyModel());require(has(pvcResult,"ESV")||has(pvcResult,"WIDE_PREMATURE"),"ESV isolée",pvcResult);

        ArrayList<Beat> pac=regular(35,.80,.015);replaceWithPremature(pac,12.0,.54,.94,false);
        Outcome pacResult=run(35,pac,null,new MorphologyModel());require(has(pacResult,"ESA")||has(pacResult,"PREMATURE"),"ESA isolée",pacResult);

        Outcome contact=run(35,regular(35,.80,.02),(t,i)->i==12*EcgEngine.FS+7?120000:i==12*EcgEngine.FS+8?-90000:0,new MorphologyModel());require(contact.events.isEmpty(),"choc de contact",contact);
        Outcome movement=run(35,regular(35,.80,.02),(t,i)->t>=12&&t<16?1900*Math.sin(2*Math.PI*21*t)+900*Math.sin(2*Math.PI*7.3*t):0,new MorphologyModel());require(noCardiacEvents(movement),"mouvement continu",movement);
        Outcome plateau=run(35,regular(35,.80,.02),(t,i)->{
            if(t<12||t>=13.3)return 0;if(t<12.18)return 2300*(t-12)/.18;if(t<12.62)return 2300;
            if(t<12.78)return 2300-4500*(t-12.62)/.16;return -2200*(1-(t-12.78)/.52);
        },new MorphologyModel());require(noCardiacEvents(plateau),"plateau lent de ceinture",plateau);

        ArrayList<Beat> movedPvc=regular(35,.80,.015);replaceWithPremature(movedPvc,12.0,.53,1.07,true);
        Movement shake=(time,sample)->time>=12.0&&time<13.0?new int[]{(sample%2==0?420:-260),120,920}:new int[]{0,0,1000};
        Outcome quarantined=run(35,movedPvc,null,new MorphologyModel(),shake);require(noCardiacEvents(quarantined),"ESV apparente pendant secousse mise en quarantaine",quarantined);

        ArrayList<Beat> afterMotionPvc=regular(38,.80,.015);replaceWithPremature(afterMotionPvc,17.0,.53,1.07,true);
        Outcome afterMotion=run(38,afterMotionPvc,null,new MorphologyModel(),shake);require(has(afterMotion,"ESV")||has(afterMotion,"WIDE_PREMATURE"),"ESV après retour au calme",afterMotion);

        ArrayList<Beat> gradual=new ArrayList<>();double t=.7;while(t<70){gradual.add(new Beat(t,false));double progress=Math.min(1,t/55);t+=.78-(.30*progress);}
        Outcome exercise=run(70,gradual,null,new MorphologyModel());if(has(exercise,"REGULAR_TACHY"))throw failure("accélération sinusale progressive classée TSV",exercise);
        System.out.println("OK  effort progressif sans fausse TSV | "+summary(exercise));

        ArrayList<Beat> abrupt=new ArrayList<>();t=.7;while(t<18){abrupt.add(new Beat(t,false));t+=.80;}while(t<42){abrupt.add(new Beat(t,false));t+=.35;}Outcome fast=run(42,abrupt,null,new MorphologyModel());require(has(fast,"TACHY")||has(fast,"REGULAR_TACHY"),"tachycardie brusque prolongée",fast);for(EcgEngine.DetectionEvent e:fast.events)if(!"TACHY".equals(e.type)&&!"REGULAR_TACHY".equals(e.type))throw failure("fausse classification pendant la tachycardie: "+e.type,fast);
        Outcome slow=run(42,regular(42,1.72,.025),null,new MorphologyModel());require(has(slow,"BRADY"),"bradycardie prolongée",slow);

        ArrayList<Beat> irregular=new ArrayList<>();double[] pattern={.62,1.05,.70,1.15,.64,1.00,.74,1.12};t=.7;int k=0;while(t<70){irregular.add(new Beat(t,false));t+=pattern[k++%pattern.length];}Outcome variable=run(70,irregular,null,new MorphologyModel());require(has(variable,"IRREGULAR"),"rythme très irrégulier",variable);

        ArrayList<Beat> pauseBeats=regular(35,.80,.015);pauseBeats.removeIf(b->b.time>=12&&b.time<14.7);Outcome pause=run(35,pauseBeats,null,new MorphologyModel());require(has(pause,"PAUSE"),"pause propre > 2 s",pause);

        MorphologyModel learned=new MorphologyModel();ArrayList<Beat> longRun=regular(450,.80,.025);replaceWithPremature(longRun,420,.52,1.08,true);Outcome integrated=run(450,longRun,null,learned);if(!learned.isReady())throw new AssertionError("Le profil personnel n'est pas prêt après 500 battements propres");require(hasReadyMorphologyEvent(integrated),"ESV après apprentissage ML",integrated);

        System.out.println("14 scenarios continus OK: rythme, ectopies, contact, bruit, plateau, mouvement H10, effort, épisodes longs, pause et ML prêt.");
    }

    private static Outcome run(double duration,List<Beat> beats,Noise noise,MorphologyModel model){return run(duration,beats,noise,model,null);}
    private static Outcome run(double duration,List<Beat> beats,Noise noise,MorphologyModel model,Movement movement){ArrayList<EcgEngine.DetectionEvent> events=new ArrayList<>();EcgEngine engine=new EcgEngine(events::add,model);Random random=new Random(42);int cursor=0,total=(int)Math.ceil(duration*EcgEngine.FS);for(int i=0;i<total;i++){double now=i/(double)EcgEngine.FS;long timestamp=START+Math.round(i*1000.0/EcgEngine.FS);if(movement!=null){int[]a=movement.at(now,i);engine.pushMotion(a[0],a[1],a[2],timestamp);}while(cursor<beats.size()&&beats.get(cursor).time<now-.45)cursor++;double value=28*Math.sin(2*Math.PI*.22*now)+5*Math.sin(2*Math.PI*17*now)+random.nextGaussian()*2.2;for(int j=Math.max(0,cursor-1);j<Math.min(beats.size(),cursor+4);j++){Beat b=beats.get(j);double d=now-b.time;if(d<-.3||d>.42)continue;if(b.ventricular)value+=-1450*g(d+.025,.043)+880*g(d-.072,.052);else value+=880*g(d,.014)-175*g(d-.031,.018)+115*g(d-.19,.05);}if(noise!=null)value+=noise.at(now,i);engine.push((int)Math.round(value),timestamp);}return new Outcome(events,engine.snapshot());}
    private static double g(double x,double sigma){return Math.exp(-.5*x*x/(sigma*sigma));}
    private static ArrayList<Beat> regular(double duration,double rr,double variability){ArrayList<Beat> out=new ArrayList<>();double t=.7;while(t<duration){out.add(new Beat(t,false));t+=rr+variability*Math.sin(t*.57);}return out;}
    private static void replaceWithPremature(ArrayList<Beat> beats,double around,double early,double after,boolean ventricular){int position=0;while(position<beats.size()&&beats.get(position).time<around)position++;if(position<=0||position>=beats.size()-1)return;double previous=beats.get(position-1).time;beats.remove(position);double ectopic=previous+early;beats.add(position,new Beat(ectopic,ventricular));double next=ectopic+after;beats.set(position+1,new Beat(next,false));for(int i=position+2;i<beats.size();i++)beats.set(i,new Beat(next+(i-position-1)*.80,false));}
    private static boolean has(Outcome o,String type){for(EcgEngine.DetectionEvent e:o.events)if(type.equals(e.type))return true;return false;}
    private static boolean noCardiacEvents(Outcome o){for(EcgEngine.DetectionEvent e:o.events)if(!"PAUSE".equals(e.type))return false;return !has(o,"PAUSE");}
    private static boolean hasReadyMorphologyEvent(Outcome o){for(EcgEngine.DetectionEvent e:o.events)if(("ESV".equals(e.type)||"WIDE_PREMATURE".equals(e.type))&&e.personalModelReady)return true;return false;}
    private static void require(boolean ok,String scenario,Outcome outcome){if(!ok)throw failure("scenario manqué: "+scenario,outcome);System.out.println("OK  "+scenario+" | "+summary(outcome));}
    private static AssertionError failure(String message,Outcome o){return new AssertionError(message+" | "+summary(o));}
    private static String summary(Outcome o){StringBuilder b=new StringBuilder("events=");for(EcgEngine.DetectionEvent e:o.events)b.append(e.type).append(',');return b+" beats="+o.snapshot.beats+" bpm="+o.snapshot.bpm+" SQI="+Math.round(o.snapshot.signalQualityPercent)+"%";}
}
