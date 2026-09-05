package com.local.polarh10monitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Moteur causal mono-dérivation. Les sorties sont des suspicions à relire, jamais des diagnostics. */
public final class EcgEngine {
    public static final int FS = 130;
    public static final String VERSION = "V4.0-beta2-causal-energy+abstention";
    private static final long DECISION_DELAY_SAMPLES = Math.round(FS * 1.2);

    public interface Listener { void onEvent(DetectionEvent event); }

    public static final class DetectionEvent {
        public final String type, title, detail;
        public final long sampleIndex, timestampMs;
        public final int severity;
        public final float[] morphology;
        public final double morphologyScore, morphologyThreshold;
        public final boolean personalModelReady;
        public final int bpm;
        public final double rmssdMs, sdnnMs, signalQualityPercent;
        public DetectionEvent(String type, String title, String detail, long sampleIndex, long timestampMs, int severity) {
            this(type,title,detail,sampleIndex,timestampMs,severity,null,0,0,false,0,0,0,0);
        }
        DetectionEvent(String type, String title, String detail, long sampleIndex, long timestampMs, int severity,
                       float[] morphology, double score, double threshold, boolean modelReady,
                       int bpm, double rmssdMs, double sdnnMs, double signalQualityPercent) {
            this.type = type; this.title = title; this.detail = detail;
            this.sampleIndex = sampleIndex; this.timestampMs = timestampMs; this.severity = severity;
            this.morphology = morphology == null ? null : morphology.clone();
            this.morphologyScore = score; this.morphologyThreshold = threshold; this.personalModelReady = modelReady;
            this.bpm=bpm;this.rmssdMs=rmssdMs;this.sdnnMs=sdnnMs;this.signalQualityPercent=signalQualityPercent;
        }
    }

    public static final class Snapshot {
        public final int bpm, esv, esa, pauses, af, tachy, brady, runs, events, beats;
        public final boolean signalGood, motionAvailable, motionActive;
        public final long samples;
        public final int modelSamples, confirmedExamples;
        public final int artifactExamples, artifactRejected;
        public final int classifiedBeats, uncertainBeats;
        public final boolean modelReady;
        public final double morphologyScore, morphologyThreshold, rmssdMs, sdnnMs, signalQualityPercent;
        Snapshot(int bpm, int esv, int esa, int pauses, int af, int tachy, int brady, int runs, int beats,
                 int events, boolean signalGood, boolean motionAvailable, boolean motionActive, long samples, int modelSamples, int confirmedExamples,
                 int artifactExamples, int artifactRejected, boolean modelReady, double morphologyScore,
                 double morphologyThreshold, double rmssdMs, double sdnnMs, double signalQualityPercent,int classifiedBeats,int uncertainBeats) {
            this.bpm=bpm; this.esv=esv; this.esa=esa; this.pauses=pauses; this.af=af;
            this.tachy=tachy; this.brady=brady; this.runs=runs; this.events=events;
            this.beats=beats;
            this.signalGood=signalGood;this.motionAvailable=motionAvailable;this.motionActive=motionActive;this.samples=samples;
            this.modelSamples=modelSamples;this.confirmedExamples=confirmedExamples;this.modelReady=modelReady;
            this.artifactExamples=artifactExamples;this.artifactRejected=artifactRejected;
            this.classifiedBeats=classifiedBeats;this.uncertainBeats=uncertainBeats;
            this.morphologyScore=morphologyScore;this.morphologyThreshold=morphologyThreshold;
            this.rmssdMs=rmssdMs;this.sdnnMs=sdnnMs;this.signalQualityPercent=signalQualityPercent;
        }
    }

    /** Mesures propres à une fenêtre, utilisées uniquement par le bilan matinal guidé. */
    public static final class FitnessMetrics {
        public final double restingHr,rmssdMs,sdnnMs,beatQualityPercent;
        public final int nnCount;
        FitnessMetrics(double restingHr,double rmssdMs,double sdnnMs,double beatQualityPercent,int nnCount){this.restingHr=restingHr;this.rmssdMs=rmssdMs;this.sdnnMs=sdnnMs;this.beatQualityPercent=beatQualityPercent;this.nnCount=nnCount;}
    }

    private static final class Beat {
        long index, timeMs; double rr, baseline, width; boolean clean,modelObserved,classified; char label='?';float[] morphology;
        String reason="PENDING";double similarity=Double.NaN;float[] features;
        Beat(long index, long timeMs, double baseline, boolean clean) {
            this.index=index; this.timeMs=timeMs; this.baseline=baseline; this.clean=clean;
        }
    }

    private static final class MotionWindow {
        long fromMs,toMs;MotionWindow(long fromMs,long toMs){this.fromMs=fromMs;this.toMs=toMs;}
    }

    private static final class LocalQuality {
        final boolean artifact,dualQrs,excellent;final double baselineShift,outsideRatio;
        LocalQuality(boolean artifact,boolean dualQrs,boolean excellent,double baselineShift,double outsideRatio){
            this.artifact=artifact;this.dualQrs=dualQrs;this.excellent=excellent;this.baselineShift=baselineShift;this.outsideRatio=outsideRatio;
        }
    }

    private final Listener listener;
    private final MorphologyModel morphologyModel;
    private final Biquad high = new Biquad(true, .8, FS);
    private final Biquad low = new Biquad(false, 32, FS);
    private final FloatRing filtered = new FloatRing(FS * 20);
    private final IntRing raw = new IntRing(FS * 20);
    private final Sqi sqi = new Sqi();
    private final StreamingQrs qrs = new StreamingQrs();
    private final ShapeReference shapeReference = new ShapeReference();
    private final ArrayList<Beat> beats = new ArrayList<>();
    private final ArrayList<Double> normalRr = new ArrayList<>();
    private final ArrayList<Double> rrHistory = new ArrayList<>();
    private final ArrayList<MotionWindow> motionWindows = new ArrayList<>();
    private long index=-1, lastR=-1000000, lastPauseIndex=-1000000, pauseArmIndex;
    private double prev2, prev1, signalAmp=260;
    private int bpm, esv, esa, pauses, af, tachy, brady, runs, eventCount, artifactRejected, beatCount;
    private int classifiedBeats,uncertainBeats;
    private long qualityEligibleSamples, qualityGoodSamples;
    private long bradySince=-1, tachySince=-1, abruptTachyUntil=-1, regularTachyDetectedUntil=-1;
    private double lastMorphologyScore;
    private boolean motionAvailable,haveAcceleration;
    private int lastAx,lastAy,lastAz;
    private double motionEnergy;
    private long motionBadUntilMs,lastTimestampMs;
    private final java.util.HashMap<String,Long> cooldown = new java.util.HashMap<>();
    private boolean evaluatingRules;
    private boolean researchRulesOnly,dcInitialized;
    private double dcOffset;
    void researchFeaturesOnly(){researchRulesOnly=true;}

    public EcgEngine(Listener listener) { this(listener,new MorphologyModel()); }
    public EcgEngine(Listener listener, MorphologyModel morphologyModel) {
        this.listener=listener;this.morphologyModel=morphologyModel==null?new MorphologyModel():morphologyModel;
    }

    public synchronized void reset() {
        dcInitialized=false;dcOffset=0;
        qrs.reset(0);shapeReference.reset();
        high.reset(); low.reset(); filtered.clear(); raw.clear(); sqi.reset(); beats.clear(); normalRr.clear(); rrHistory.clear();
        index=-1; lastR=-1000000; lastPauseIndex=-1000000; pauseArmIndex=0; prev2=prev1=0; signalAmp=260; bpm=0;
        esv=esa=pauses=af=tachy=brady=runs=eventCount=artifactRejected=beatCount=0;qualityEligibleSamples=qualityGoodSamples=0;
        classifiedBeats=uncertainBeats=0;
        bradySince=tachySince=abruptTachyUntil=regularTachyDetectedUntil=-1;lastMorphologyScore=0;cooldown.clear();
        motionWindows.clear();motionAvailable=haveAcceleration=false;lastAx=lastAy=lastAz=0;motionEnergy=0;motionBadUntilMs=lastTimestampMs=0;
    }

    /** Ajoute un échantillon accéléromètre H10 en milli-g. Une secousse ouvre une quarantaine temporelle. */
    public synchronized void pushMotion(int xMg,int yMg,int zMg,long timestampMs) {
        motionAvailable=true;
        if(!haveAcceleration){lastAx=xMg;lastAy=yMg;lastAz=zMg;haveAcceleration=true;return;}
        double dx=xMg-lastAx,dy=yMg-lastAy,dz=zMg-lastAz;
        double jerk=Math.sqrt(dx*dx+dy*dy+dz*dz);
        double magnitude=Math.sqrt((double)xMg*xMg+(double)yMg*yMg+(double)zMg*zMg);
        motionEnergy=.84*motionEnergy+.16*jerk;
        lastAx=xMg;lastAy=yMg;lastAz=zMg;
        if(jerk>=115||motionEnergy>=42||Math.abs(magnitude-1000)>=240){
            addMotionWindow(timestampMs-450,timestampMs+1800);
            motionBadUntilMs=Math.max(motionBadUntilMs,timestampMs+1800);
            pauseArmIndex=Math.max(pauseArmIndex,index+FS*2L);
            bradySince=tachySince=-1;
        }
    }

    public synchronized float push(int rawUv, long timestampMs) {
        lastTimestampMs=timestampMs;
        index++;
        // Remove a slowly varying acquisition DC offset BEFORE clipping/SQI.
        // MonitorService still stores the exact original sample, unchanged.
        if(!dcInitialized){dcOffset=rawUv;dcInitialized=true;}
        dcOffset+=.0024*(rawUv-dcOffset);
        rawUv=(int)Math.round(rawUv-dcOffset);
        raw.put(index,rawUv);
        int clipped=Math.max(-2500, Math.min(2500, rawUv));
        double y=low.process(high.process(clipped));
        y=Math.max(-3200, Math.min(3200, y));
        filtered.put(index, (float)y);
        sqi.push(rawUv,clipped,(float)y,index);
        if(sqi.artifactAt==index){pauseArmIndex=index+FS*2L;bradySince=tachySince=-1;}
        if(index>=FS*4L){qualityEligibleSamples++;if(sqi.good(index)&&!motionAt(timestampMs))qualityGoodSamples++;}
        long detected=qrs.push(rawUv);
        if(detected>=0&&detected>lastR){
            // Align to the display/morphology filter; correct the delayed timestamp.
            long at=detected;double best=0;
            for(long j=Math.max(0,detected-3);j<=Math.min(index,detected+5);j++){
                double v=Math.abs(filtered.get(j));if(v>best){best=v;at=j;}
            }
            if(at>lastR)addBeat(at,timestampMs-Math.round((index-at)*1000.0/FS));
        }
        checkPause(timestampMs);
        checkRateEpisodes(timestampMs);
        if(index%8==0)classifyPending();
        return (float)y;
    }

    public synchronized Snapshot snapshot() {
        double[] hrv=hrv();
        double quality=qualityEligibleSamples==0?0:100.0*qualityGoodSamples/qualityEligibleSamples;
        boolean moving=motionAt(lastTimestampMs);
        return new Snapshot(bpm,esv,esa,pauses,af,tachy,brady,runs,beatCount,eventCount,sqi.good(index)&&!moving,motionAvailable,moving,index+1,
                morphologyModel.normalCount(),morphologyModel.confirmedCount(),morphologyModel.artifactCount(),artifactRejected,
                morphologyModel.isReady(),lastMorphologyScore,morphologyModel.threshold(),hrv[0],hrv[1],quality,classifiedBeats,uncertainBeats);
    }

    public synchronized FitnessMetrics fitnessMetricsSince(long sinceSample){
        ArrayList<Double> nn=new ArrayList<>(),heartRates=new ArrayList<>();int eligible=0,pairs=0;double squares=0;Double previous=null;
        for(int i=1;i<beats.size()-1;i++){
            Beat b=beats.get(i),p=beats.get(i-1);
            if(b.index<sinceSample){previous=null;continue;}eligible++;
            boolean valid=b.classified&&p.classified&&b.clean&&p.clean&&b.label=='N'&&p.label=='N'&&b.rr>=300&&b.rr<=2000;
            if(!valid){previous=null;continue;}
            nn.add(b.rr);heartRates.add(60000.0/b.rr);
            if(previous!=null){squares+=Math.pow(b.rr-previous,2);pairs++;}previous=b.rr;
        }
        double quality=eligible==0?0:100.0*nn.size()/eligible;
        if(nn.size()<10||pairs<9)return new FitnessMetrics(0,0,0,quality,nn.size());
        return new FitnessMetrics(median(heartRates),Math.sqrt(squares/pairs),std(nn,mean(nn)),quality,nn.size());
    }
    /** A missing segment breaks every timing chain while retaining session counters. */
    public synchronized void discontinuity(){
        dcInitialized=false;dcOffset=0;
        qrs.reset(index+1);shapeReference.reset();
        high.reset();low.reset();filtered.clear();raw.clear();sqi.reset();beats.clear();normalRr.clear();rrHistory.clear();
        sqi.cleanSince=index;lastR=-1000000;pauseArmIndex=index+FS*5L;prev1=prev2=0;bpm=0;
        bradySince=tachySince=abruptTachyUntil=regularTachyDetectedUntil=-1;
        motionWindows.clear();motionBadUntilMs=0;
    }

    private void addBeat(long at, long timeMs) {
        double base=normalRr.isEmpty()?0:medianTail(normalRr,9);
        Beat b=new Beat(at,timeMs,base,sqi.good(at));
        if(!beats.isEmpty()) {
            double rr=(at-beats.get(beats.size()-1).index)*1000.0/FS;
            b.rr=rr; rrHistory.add(rr); trim(rrHistory,80);
            if(rr>=280&&rr<=2200&&b.clean){normalRr.add(rr);trim(normalRr,24);}
            if(rr>=280&&rr<=2200){int previousBpm=bpm;bpm=(int)Math.round(60000/rr);if(previousBpm>=45&&previousBpm<=125&&bpm>=155&&base>0&&rr/base<.78)abruptTachyUntil=at+FS*30L;}
        }
        beats.add(b);beatCount++;if(beats.size()>600)beats.remove(0);
        lastR=at;
        classifyPending();
        learnStableMorphology();
        checkIrregularRhythm();
    }

    /** Attend 1,2 s afin d'observer la récupération du signal et les paquets mouvement arrivés après le QRS. */
    private void classifyPending(){
        if(beats.size()<3)return;int from=Math.max(1,beats.size()-12);
        for(int i=from;i<beats.size()-1;i++){Beat cur=beats.get(i);if(!cur.classified&&index-cur.index>=DECISION_DELAY_SAMPLES)classifyAt(i);}
    }

    private void classifyAt(int position) {
        classifyDecisionAt(position);classifiedBeats++;
        if(beats.get(position).label=='?')uncertainBeats++;
    }

    private void classifyDecisionAt(int position) {
        if(researchRulesOnly){classifyRulesAt(position);return;}
        Beat cur=beats.get(position);int previousV=esv,previousA=esa;
        evaluatingRules=true;
        try{classifyRulesAt(position);}finally{evaluatingRules=false;esv=previousV;esa=previousA;}
        String ruleReason=cur.reason;char ruleLabel=cur.label;
        boolean safe=cur.features!=null&&!ruleReason.equals("GLOBAL_SQI")&&!ruleReason.equals("WIDTH_OR_SHOCK")
                &&!ruleReason.equals("ENERGY_DISAGREEMENT")&&!ruleReason.equals("LOCAL_SQI")&&!ruleReason.equals("CONTEXT_UNCERTAIN")
                &&!ruleReason.equals("MOTION_UNCERTAIN")&&!ruleReason.equals("USER_FEEDBACK_UNCERTAIN");
        if(!safe)return;
        double[] votes=ExperimentalClassifier.predict(cur.features);int decision=ExperimentalClassifier.confidentClass(votes);
        MorphologyModel.Result morph=morphologyModel.evaluate(cur.morphology);
        String detail=String.format(Locale.FRANCE,"Modèle expérimental %s • RR %.0f ms • largeur estimée %.0f ms. Votes du modèle, pas une probabilité médicale.",ExperimentalClassifier.VERSION,cur.rr,cur.width);
        if(decision==1){
            cur.label='V';cur.reason="MODEL_V_CANDIDATE";esv++;
            emit("ESV","Complexe ventriculaire possible",detail,cur.index,cur.timeMs,1,8_000,cur.morphology,morph);
        }else if(decision==0&&ruleLabel=='N'){
            cur.label='N';cur.reason="NORMAL_AGREEMENT";
        }else{
            cur.label='?';cur.reason=decision==2?"SUPRAVENTRICULAR_UNVALIDATED":decision==3?"DETECTOR_DISAGREEMENT":"MODEL_UNCERTAIN";
            // S-class failed evaluation and must not generate a typed ESA alert.
            if(ruleLabel=='V'||ruleLabel=='A'||ruleReason.equals("SHAPE_UNCERTAIN")||ruleReason.equals("TIMING_UNCERTAIN")){
                emit("PREMATURE","Battement à relire",detail+" Classe indéterminée ; ESA non confirmée.",cur.index,cur.timeMs,0,30_000,cur.morphology,morph);
            }
        }
        checkPatterns(cur);
    }

    private void classifyRulesAt(int position) {
        Beat prev=beats.get(position-1),cur=beats.get(position),next=beats.get(position+1);cur.classified=true;
        double med=cur.baseline>0?cur.baseline:medianTail(normalRr,9);
        cur.reason="BASELINE_UNAVAILABLE";
        if(med<280||med>2200)return;
        double early=(cur.index-prev.index)*1000.0/FS;
        double after=(next.index-cur.index)*1000.0/FS;
        double prem=early/med, pauseRatio=after/med, comp=(early+after)/med;
        cur.width=estimateWidth(cur.index);
        cur.morphology=extractMorphology(cur.index);
        MorphologyModel.Result morph=morphologyModel.evaluate(cur.morphology);
        lastMorphologyScore=morph.score;
        LocalQuality quality=localQuality(cur.index);
        float[] shape=extractShape(cur.index);
        double similarity=shapeReference.similarity(shape);
        cur.similarity=similarity;
        cur.features=new float[48];
        cur.features[0]=(float)prem;cur.features[1]=(float)pauseRatio;cur.features[2]=(float)(prev.rr/med);
        cur.features[3]=(float)(cur.width/200);cur.features[4]=(float)(Double.isFinite(similarity)?similarity:0);
        cur.features[5]=(float)quality.outsideRatio;cur.features[6]=(float)(quality.baselineShift/1000);cur.features[7]=(float)(60000/med/100);
        if(shape!=null)System.arraycopy(shape,0,cur.features,8,40);
        if(!morphologyPlausible(cur.index,cur.width)||!quality.dualQrs||quality.artifact||!cur.clean){
            cur.reason=!cur.clean?"GLOBAL_SQI":!morphologyPlausible(cur.index,cur.width)?"WIDTH_OR_SHOCK":!quality.dualQrs?"ENERGY_DISAGREEMENT":"LOCAL_SQI";artifactRejected++;checkPatterns(cur);return;
        }
        if(morph.artifact||morph.ambiguous){
            cur.reason="USER_FEEDBACK_UNCERTAIN";
            emit("UNCERTAIN","Signal à relire", "Ressemblance avec un exemple annoté ou annotations contradictoires : aucune conclusion automatique.",cur.index,cur.timeMs,0,60_000,cur.morphology,morph);return;
        }
        boolean referenceReady=shapeReference.ready();
        boolean changed=referenceReady&&similarity<.88;
        boolean similar=referenceReady&&similarity>=.94;
        boolean broad=cur.width>=115&&(!referenceReady||cur.width>=shapeReference.width()*1.20||changed);
        if(motionAt(cur.timeMs)&&!quality.excellent){cur.reason="MOTION_UNCERTAIN";artifactRejected++;return;}
        if(cur.index<=abruptTachyUntil&&prem<.72&&pauseRatio<.82&&comp<1.55&&(!morph.ready||!morph.anomaly)){
            cur.reason="RAPID_TRANSITION_UNCERTAIN";checkPatterns(cur);return;
        }
        boolean gradual=(progressiveRsaAt(position,early,med,cur.width)||variableContext(position,med))&&!changed;
        if(!(prev.clean&&next.clean)){cur.reason="CONTEXT_UNCERTAIN";return;}
        String modelDetail=morph.ready?String.format(Locale.FRANCE," • forme personnelle %.2f / seuil %.2f",morph.score,morph.threshold):
                " • modèle personnel en apprentissage ("+morphologyModel.normalCount()+"/"+MorphologyModel.BASELINE_TARGET+")";
        String detail=String.format(Locale.FRANCE,"RR %.0f → %.0f ms • référence %.0f ms • largeur %.0f ms",early,after,med,cur.width)+modelDetail;
        detail+=String.format(Locale.FRANCE," • similarité locale %s",referenceReady?String.format(Locale.FRANCE,"%.2f",similarity):"en constitution");
        if(!gradual&&broad&&((prem<.88&&comp>=1.5&&comp<=2.5&&!similar)||(changed&&prem<1.08&&quality.excellent))){
            cur.label='V';cur.reason="WIDE_ECTOPIC_CANDIDATE";esv++;
            emit("ESV","Extrasystole ventriculaire possible",detail+" • classification expérimentale",cur.index,cur.timeMs,1,8_000,cur.morphology,morph);
        } else if(!gradual&&cur.width<110&&prem<.80&&(pauseRatio>1.03||prem<.65)&&!changed){
            cur.label='A';cur.reason="NARROW_PREMATURE_CANDIDATE";esa++;
            emit("ESA","Battement prématuré fin — ESA possible",detail+" • origine auriculaire non confirmée",cur.index,cur.timeMs,1,8_000,cur.morphology,morph);
        } else if(changed||(!gradual&&prem<.88)){
            cur.reason=changed?"SHAPE_UNCERTAIN":"TIMING_UNCERTAIN";
            emit("PREMATURE", "Battement inhabituel à vérifier",detail,cur.index,cur.timeMs,0,30_000,cur.morphology,morph);
        } else {
            cur.label='N';cur.reason="NO_RULE_ANOMALY";
            // Quarantine: feedback/noise/outliers never enter automatic reference.
            if(prem>=.9&&prem<=1.1&&pauseRatio>=.9&&pauseRatio<=1.1&&quality.excellent&&!motionAt(cur.timeMs)
                    &&cur.width>=23&&cur.width<=200&&(!referenceReady||similar))shapeReference.observe(shape,cur.width);
        }
        checkPatterns(cur);
    }

    private boolean progressiveRsaAt(int position,double rr,double med,double width) {
        if(width>=100||60000/med>=105||position<4)return false;
        double a=beats.get(position-3).rr,b=beats.get(position-2).rr,c=beats.get(position-1).rr;
        return a>b&&b>c&&(a-b)<.22*med&&(b-c)<.22*med&&rr<med;
    }

    private boolean variableContext(int position,double med){
        if(position<8)return false;int changes=0;
        for(int i=position-7;i<position;i++)if(Math.abs(beats.get(i).rr-beats.get(i-1).rr)>.15*med)changes++;
        return changes>=4;
    }

    private void checkPatterns(Beat anchor) {
        if(evaluatingRules)return;
        ArrayList<Character> labels=new ArrayList<>();
        int end=beats.indexOf(anchor)+1;for(int i=Math.max(0,end-12);i<end;i++)if(beats.get(i).classified)labels.add(beats.get(i).label);
        int consecutiveV=0; for(int i=labels.size()-1;i>=0&&labels.get(i)=='V';i--)consecutiveV++;
        if(consecutiveV>=3){emit("WIDE_RUN","Salve de complexes larges — TV non soutenue possible",
                consecutiveV+" battements ventriculaires possibles consécutifs. Origine à confirmer sur ECG médical.",anchor.index,anchor.timeMs,2,60_000);}
        else if(consecutiveV==2){emit("COUPLET","Couplet ventriculaire possible","Deux ESV possibles consécutives.",anchor.index,anchor.timeMs,2,60_000);}
        if(matchesAlternating(labels,6)){emit("BIGEMINY","Bigéminisme possible","Alternance répétée normal/extrasystole sur au moins 6 battements.",anchor.index,anchor.timeMs,1,120_000);}
        if(matchesTrigeminy(labels)){emit("TRIGEMINY","Trigéminisme possible","Une extrasystole possible tous les trois battements.",anchor.index,anchor.timeMs,1,120_000);}
    }

    private boolean matchesAlternating(List<Character> x,int count){
        if(x.size()<count)return false; int s=x.size()-count;
        boolean a=true,b=true; for(int i=0;i<count;i++){char c=x.get(s+i);a&=c==(i%2==0?'V':'N');b&=c==(i%2==0?'N':'V');}return a||b;
    }
    private boolean matchesTrigeminy(List<Character> x){
        if(x.size()<9)return false;int s=x.size()-9;
        for(int phase=0;phase<3;phase++){boolean ok=true;for(int i=0;i<9;i++){char expected=(i%3==phase)?'V':'N';ok&=x.get(s+i)==expected;}if(ok)return true;}return false;
    }

    private void checkPause(long timeMs) {
        if(beats.size()<4||!sqi.good(index)||motionAt(timeMs)||!sqi.cleanFor(index,FS*2L)||lastR<0)return;
        // The detector needs up to 0.34 s to confirm a QRS. Never call that delay a pause.
        double gap=(index-Math.round(.34*FS)-Math.max(lastR,pauseArmIndex))*1000.0/FS;
        if(gap>=2000 && index-lastPauseIndex>FS*5L){lastPauseIndex=index;emit("PAUSE","Pause possible",
                String.format(Locale.FRANCE,"Aucun QRS détecté depuis %.2f s sur signal stable.",gap/1000),index,timeMs,2,30_000);}
    }

    private void checkRateEpisodes(long timeMs) {
        if(!sqi.good(index)||motionAt(timeMs)||bpm==0)return;
        if(bpm<40){if(bradySince<0)bradySince=index;if(index-bradySince>=FS*10L){emit("BRADY","Bradycardie prolongée possible",bpm+" bpm pendant au moins 10 s.",index,timeMs,2,300_000);bradySince=index;}}
        else bradySince=-1;
        if(bpm>150){if(tachySince<0)tachySince=index;if(index-tachySince>=FS*10L&&index>regularTachyDetectedUntil){emit("TACHY","Tachycardie prolongée possible",bpm+" bpm pendant au moins 10 s.",index,timeMs,2,180_000);tachySince=index;}}
        else tachySince=-1;
        if(beats.size()>=10&&bpm>=155&&index<=abruptTachyUntil){
            ArrayList<Double> r=new ArrayList<>();for(int i=beats.size()-8;i<beats.size();i++)if(beats.get(i).rr>0)r.add(beats.get(i).rr);
            double m=mean(r),cv=std(r,m)/m;
            if(r.size()>=7&&cv<.045){regularTachyDetectedUntil=index+FS*60L;emit("REGULAR_TACHY","Tachycardie régulière à début brutal possible",
                    String.format(Locale.FRANCE,"Passage rapide autour de %d bpm (CV RR %.3f) après une accélération brusque. Origine à confirmer.",bpm,cv),index,timeMs,2,300_000);}
        }
    }

    private void checkIrregularRhythm() {
        if(beats.size()<28||!sqi.good(index)||motionAt(lastTimestampMs)||index<=abruptTachyUntil)return;
        long cutoff=index-FS*32L;ArrayList<Double> r=new ArrayList<>();int ectopic=0,unknown=0,total=0;
        long first=-1,last=-1;
        for(int i=1;i<beats.size();i++){Beat b=beats.get(i);if(b.index>=cutoff&&b.classified){
            boolean morphologyOnlyUnknown=b.reason.equals("MODEL_UNCERTAIN")||b.reason.equals("SUPRAVENTRICULAR_UNVALIDATED");
            total++;if(!b.clean||(b.label=='?'&&!morphologyOnlyUnknown)||b.rr<300||b.rr>1800){unknown++;continue;}
            r.add(b.rr);if(first<0)first=b.index;last=b.index;if(b.label=='V'||b.label=='A')ectopic++;
        }}
        if(r.size()<25||ectopic>2||unknown>total*.1||last-first<FS*28L)return;
        double m=mean(r),cv=std(r,m)/m,rm=0;int turns=0;
        for(int i=1;i<r.size();i++)rm+=Math.pow(r.get(i)-r.get(i-1),2);
        rm=Math.sqrt(rm/Math.max(1,r.size()-1))/m;
        for(int i=1;i<r.size()-1;i++){double a=r.get(i)-r.get(i-1),b=r.get(i+1)-r.get(i);if(a*b<0)turns++;}
        double tpr=turns/(double)Math.max(1,r.size()-2);
        if(cv>=.12&&rm>=.10&&tpr>=.55){Beat b=beats.get(beats.size()-1);emit("IRREGULAR","Rythme irrégulier à relire",
                String.format(Locale.FRANCE,"Fenêtre proche de 30 s : CV RR %.3f, RMSSD normalisé %.3f, alternance %.2f. La variabilité RR seule ne confirme pas une FA.",cv,rm,tpr),b.index,b.timeMs,1,600_000);}
    }

    private double estimateWidth(long peak) {
        // Raw, locally detrended envelope avoids measuring the IIR recovery tail as QRS.
        double[] x=detrended(peak,60);int p=30;double max=0;
        for(int i=24;i<=36;i++)if(Math.abs(x[i])>max){max=Math.abs(x[i]);p=i;}
        if(max<1)return 0;double edge=Math.max(18,max*.16);int left=p,right=p,quiet=0;
        for(int i=p-1;i>=Math.max(1,p-23);i--){if(Math.abs(x[i])<edge){if(++quiet>=2){left=i+2;break;}}else{quiet=0;left=i;}}
        quiet=0;for(int i=p+1;i<=Math.min(58,p+23);i++){if(Math.abs(x[i])<edge){if(++quiet>=2){right=i-2;break;}}else{quiet=0;right=i;}}
        return (right-left+1)*1000.0/FS;
    }

    private void learnStableMorphology(){
        if(beats.size()<4)return;Beat candidate=beats.get(beats.size()-3);
        if(candidate.modelObserved||!candidate.classified||candidate.label!='N'||!candidate.clean||sqi.noisy||motionAt(candidate.timeMs)
                ||candidate.baseline<=0||Math.abs(candidate.rr/candidate.baseline-1)>.1||!localQuality(candidate.index).excellent)return;
        candidate.modelObserved=true;candidate.morphology=extractMorphology(candidate.index);morphologyModel.observeNormal(candidate.morphology);
    }

    private boolean morphologyPlausible(long peak,double widthMs){
        // Sous 3 échantillons la "largeur" n'est pas physiologiquement crédible à 130 Hz.
        // La borne haute reste permissive : le filtre et une morphologie biphasique peuvent élargir la mesure.
        if(widthMs<15||widthMs>300)return false;
        int maxAbs=0,maxStep=0,previous=raw.get(peak-FS/2L);
        for(long i=peak-FS/2L+1;i<=peak+FS/2L;i++){
            int value=raw.get(i);maxAbs=Math.max(maxAbs,Math.abs(value));maxStep=Math.max(maxStep,Math.abs(value-previous));previous=value;
        }
        return maxAbs<6000&&maxStep<3200;
    }

    /** Qualité locale centrée sur l'événement, indépendante de la moyenne de toute la session. */
    private LocalQuality localQuality(long peak){
        ArrayList<Double> pre=new ArrayList<>(),post=new ArrayList<>();
        for(long i=peak-58;i<=peak-35;i++)pre.add((double)raw.get(i));
        for(long i=peak+38;i<=peak+61;i++)post.add((double)raw.get(i));
        double preBase=median(pre),postBase=median(post),baselineShift=Math.abs(postBase-preBase);
        int maxAbs=0,maxStep=0,clipped=0,previous=raw.get(peak-70);
        double insideEnergy=0,outsideEnergy=0,qrsAmp=0,noiseSquares=0;int outsideCount=0;
        for(long i=peak-69;i<=peak+70;i++){
            int value=raw.get(i),step=Math.abs(value-previous);previous=value;
            maxAbs=Math.max(maxAbs,Math.abs(value));maxStep=Math.max(maxStep,step);if(Math.abs(value)>=2450)clipped++;
            long distance=Math.abs(i-peak);
            boolean neighbor=false;
            for(int k=beats.size()-1;k>=0;k--){long other=beats.get(k).index;if(other<peak-100)break;if(other!=peak&&Math.abs(i-other)<=20){neighbor=true;break;}}
            if(distance<=26)insideEnergy+=step;else if(!neighbor){outsideEnergy+=step;noiseSquares+=(double)step*step;outsideCount++;}
            if(distance<=26)qrsAmp=Math.max(qrsAmp,Math.abs(filtered.get(i)));
        }
        double outsideRatio=outsideEnergy/Math.max(1,insideEnergy);
        double outsideRms=Math.sqrt(noiseSquares/Math.max(1,outsideCount));
        boolean dualQrs=qrsAmp>=75&&insideEnergy>=Math.max(260,outsideEnergy*.55);
        boolean artifact=maxAbs>=6000||maxStep>=3200||clipped>8
                ||baselineShift>Math.max(450,qrsAmp*.9)
                ||outsideRms>Math.max(90,qrsAmp*.22);
        boolean excellent=!artifact&&clipped==0&&baselineShift<Math.max(220,qrsAmp*.45)&&outsideRms<Math.max(35,qrsAmp*.1);
        return new LocalQuality(artifact,dualQrs,excellent,baselineShift,outsideRatio);
    }

    private void addMotionWindow(long fromMs,long toMs){
        if(!motionWindows.isEmpty()){
            MotionWindow last=motionWindows.get(motionWindows.size()-1);
            if(fromMs<=last.toMs+250){last.toMs=Math.max(last.toMs,toMs);pruneMotion(toMs);return;}
        }
        motionWindows.add(new MotionWindow(fromMs,toMs));pruneMotion(toMs);
    }

    private void pruneMotion(long nowMs){while(motionWindows.size()>1&&motionWindows.get(0).toMs<nowMs-30_000)motionWindows.remove(0);}
    private boolean motionAt(long timestampMs){
        if(!motionAvailable)return false;
        for(int i=motionWindows.size()-1;i>=0;i--){MotionWindow w=motionWindows.get(i);if(timestampMs>w.toMs)break;if(timestampMs>=w.fromMs)return true;}
        return timestampMs<=motionBadUntilMs&&timestampMs>=motionBadUntilMs-2250;
    }

    /** RMSSD et SDNN sur les intervalles NN propres des cinq dernières minutes environ. */
    private double[] hrv(){
        FitnessMetrics m=fitnessMetricsSince(Math.max(0,index-FS*300L));
        return new double[]{m.rmssdMs,m.sdnnMs};
    }

    private float[] extractMorphology(long peak) {
        if(index<peak+FS/2L-1)return null;
        float[] pooled=new float[MorphologyModel.DIMENSIONS];
        float[] window=new float[FS];double mean=0;
        for(int i=0;i<FS;i++){window[i]=filtered.get(peak-FS/2L+i);mean+=window[i];}
        mean/=FS;double dominant=0;for(float value:window)if(Math.abs(value-mean)>Math.abs(dominant))dominant=value-mean;
        double sign=dominant<0?-1:1;
        for(int bin=0;bin<pooled.length;bin++){
            int from=bin*FS/pooled.length,to=(bin+1)*FS/pooled.length;double sum=0;
            for(int i=from;i<to;i++)sum+=(window[i]-mean)*sign;
            pooled[bin]=(float)(sum/Math.max(1,to-from));
        }
        double featureMean=0;for(float value:pooled)featureMean+=value;featureMean/=pooled.length;
        double rms=0;for(float value:pooled)rms+=(value-featureMean)*(value-featureMean);rms=Math.sqrt(rms/pooled.length);
        if(rms<1e-6)return pooled;for(int i=0;i<pooled.length;i++)pooled[i]=(float)((pooled[i]-featureMean)/rms);
        return pooled;
    }

    private float[] extractShape(long peak){
        float[] x=new float[ShapeReference.SIZE];double[] signal=detrended(peak,x.length);double mean=0;
        for(int i=0;i<x.length;i++){x[i]=(float)signal[i];mean+=x[i];}
        mean/=x.length;double norm=0;for(float v:x)norm+=(v-mean)*(v-mean);
        norm=Math.sqrt(norm);if(norm<1)return null;
        for(int i=0;i<x.length;i++)x[i]=(float)((x[i]-mean)/norm);
        return x;
    }

    private double[] detrended(long peak,int length){
        double[] x=new double[length];ArrayList<Double> left=new ArrayList<>(),right=new ArrayList<>();
        for(int i=0;i<5;i++){left.add((double)raw.get(peak-length/2+i));right.add((double)raw.get(peak+length/2-1-i));}
        double a=median(left),b=median(right);
        for(int i=0;i<length;i++){long at=peak-length/2+i;x[i]=(raw.get(at-1)+2.0*raw.get(at)+raw.get(at+1))/4-(a+(b-a)*i/(length-1));}
        return x;
    }

    private void emit(String type,String title,String detail,long at,long time,int severity,long coolMs){
        emit(type,title,detail,at,time,severity,coolMs,null,null);
    }
    private void emit(String type,String title,String detail,long at,long time,int severity,long coolMs,float[] morphology,MorphologyModel.Result result){
        if(evaluatingRules)return;
        long now=at*1000L/FS,last=cooldown.containsKey(type)?cooldown.get(type):Long.MIN_VALUE/2;
        if(now-last<coolMs)return;cooldown.put(type,now);eventCount++;if(type.equals("IRREGULAR"))af++;if(type.equals("BRADY"))brady++;if(type.equals("TACHY")||type.equals("REGULAR_TACHY"))tachy++;if(type.equals("PAUSE"))pauses++;if(type.equals("WIDE_RUN")||type.equals("COUPLET"))runs++;if(listener!=null){double[] hrv=hrv();double quality=qualityEligibleSamples==0?0:100.0*qualityGoodSamples/qualityEligibleSamples;listener.onEvent(new DetectionEvent(type,title,detail,at,time,severity,
                morphology,result==null?0:result.score,result==null?0:result.threshold,result!=null&&result.ready,bpm,hrv[0],hrv[1],quality));}
    }

    private static void trim(ArrayList<?> x,int max){while(x.size()>max)x.remove(0);}
    private static double medianTail(List<Double>x,int n){if(x.isEmpty())return 0;return median(new ArrayList<>(x.subList(Math.max(0,x.size()-n),x.size())));}
    private static double median(List<Double>x){if(x.isEmpty())return 0;ArrayList<Double>y=new ArrayList<>(x);Collections.sort(y);int n=y.size();return n%2==1?y.get(n/2):(y.get(n/2-1)+y.get(n/2))/2;}
    private static double mean(List<Double>x){double s=0;for(double v:x)s+=v;return x.isEmpty()?0:s/x.size();}
    private static double std(List<Double>x,double m){double s=0;for(double v:x)s+=(v-m)*(v-m);return x.size()<2?0:Math.sqrt(s/(x.size()-1));}

    private static final class FloatRing {
        final float[] values;final long[] ids;FloatRing(int n){values=new float[n];ids=new long[n];Arrays.fill(ids,Long.MIN_VALUE);}
        void clear(){Arrays.fill(ids,Long.MIN_VALUE);}void put(long i,float v){int p=(int)Math.floorMod(i,values.length);values[p]=v;ids[p]=i;}
        float get(long i){int p=(int)Math.floorMod(i,values.length);return ids[p]==i?values[p]:0;}
    }

    private static final class IntRing {
        final int[] values;final long[] ids;IntRing(int n){values=new int[n];ids=new long[n];Arrays.fill(ids,Long.MIN_VALUE);}
        void clear(){Arrays.fill(ids,Long.MIN_VALUE);}void put(long i,int v){int p=(int)Math.floorMod(i,values.length);values[p]=v;ids[p]=i;}
        int get(long i){int p=(int)Math.floorMod(i,values.length);return ids[p]==i?values[p]:0;}
    }

    private static final class Sqi {
        final int[] raw=new int[FS];final int[] diff=new int[FS];int pos,count,last;double baseline=20,noiseAbs=20;boolean noisy;long badUntil,cleanSince,artifactAt=-1;
        void reset(){pos=count=last=0;baseline=20;noiseAbs=20;noisy=false;badUntil=0;cleanSince=0;artifactAt=-1;}
        void push(int original,int r,float f,long index){
            int step=Math.abs(original-last);boolean shock=Math.abs(original)>=6000||step>=3200;
            raw[pos]=r;diff[pos]=Math.min(10000,step);last=original;pos=(pos+1)%FS;if(count<FS)count++;
            if(shock){artifactAt=index;badUntil=Math.max(badUntil,index+FS*2L);cleanSince=index+FS*2L;}
            if(index%(FS/4)==0&&count>=FS){int[]a=raw.clone(),d=diff.clone();Arrays.sort(a);Arrays.sort(d);int p2p=a[FS-1]-a[0],p80=d[(int)(FS*.80)],p98=d[(int)(FS*.98)],clipped=0;for(int v:a)if(Math.abs(v)>=2450)clipped++;if(index<FS*4L)baseline=.9*baseline+.1*Math.max(1,p80);boolean bad=p2p>4500||clipped>FS/100||p80>Math.max(180,baseline*3.8)||p98>1800;if(bad){artifactAt=index;badUntil=Math.max(badUntil,index+FS*2L);cleanSince=badUntil;}else if(p80<baseline*1.8)baseline=.995*baseline+.005*p80;noiseAbs=Math.max(8,p80);}
            noisy=index<badUntil;if(noisy)cleanSince=Math.max(cleanSince,badUntil);
        }
        boolean good(long index){return index>=FS*4L&&!noisy&&index-cleanSince>=FS*3L/4;}
        boolean cleanFor(long index,long samples){return good(index)&&index-cleanSince>=samples;}
    }

    private static final class Biquad {
        final double b0,b1,b2,a1,a2;double x1,x2,y1,y2;
        Biquad(boolean high,double hz,double fs){double w=2*Math.PI*hz/fs,cos=Math.cos(w),sin=Math.sin(w),alpha=sin/(2/Math.sqrt(2));double a0=1+alpha;
            if(high){b0=(1+cos)/2/a0;b1=-(1+cos)/a0;b2=b0;}else{b0=(1-cos)/2/a0;b1=(1-cos)/a0;b2=b0;}a1=-2*cos/a0;a2=(1-alpha)/a0;}
        double process(double x){double y=b0*x+b1*x1+b2*x2-a1*y1-a2*y2;x2=x1;x1=x;y2=y1;y1=y;return y;}void reset(){x1=x2=y1=y2=0;}
    }
}
