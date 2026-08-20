package com.local.polarh10monitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Moteur causal mono-dérivation. Les sorties sont des suspicions à relire, jamais des diagnostics. */
public final class EcgEngine {
    public static final int FS = 130;
    public static final String VERSION = "V2.0-rules+personal-metric-2026-08";

    public interface Listener { void onEvent(DetectionEvent event); }

    public static final class DetectionEvent {
        public final String type, title, detail;
        public final long sampleIndex, timestampMs;
        public final int severity;
        public final float[] morphology;
        public final double morphologyScore, morphologyThreshold;
        public final boolean personalModelReady;
        public DetectionEvent(String type, String title, String detail, long sampleIndex, long timestampMs, int severity) {
            this(type,title,detail,sampleIndex,timestampMs,severity,null,0,0,false);
        }
        DetectionEvent(String type, String title, String detail, long sampleIndex, long timestampMs, int severity,
                       float[] morphology, double score, double threshold, boolean modelReady) {
            this.type = type; this.title = title; this.detail = detail;
            this.sampleIndex = sampleIndex; this.timestampMs = timestampMs; this.severity = severity;
            this.morphology = morphology == null ? null : morphology.clone();
            this.morphologyScore = score; this.morphologyThreshold = threshold; this.personalModelReady = modelReady;
        }
    }

    public static final class Snapshot {
        public final int bpm, esv, esa, pauses, af, tachy, brady, runs, events;
        public final boolean signalGood;
        public final long samples;
        public final int modelSamples, confirmedExamples;
        public final boolean modelReady;
        public final double morphologyScore, morphologyThreshold;
        Snapshot(int bpm, int esv, int esa, int pauses, int af, int tachy, int brady, int runs,
                 int events, boolean signalGood, long samples, int modelSamples, int confirmedExamples,
                 boolean modelReady, double morphologyScore, double morphologyThreshold) {
            this.bpm=bpm; this.esv=esv; this.esa=esa; this.pauses=pauses; this.af=af;
            this.tachy=tachy; this.brady=brady; this.runs=runs; this.events=events;
            this.signalGood=signalGood; this.samples=samples;
            this.modelSamples=modelSamples;this.confirmedExamples=confirmedExamples;this.modelReady=modelReady;
            this.morphologyScore=morphologyScore;this.morphologyThreshold=morphologyThreshold;
        }
    }

    private static final class Beat {
        long index, timeMs; double rr, baseline, width; boolean clean,modelObserved; char label='N';float[] morphology;
        Beat(long index, long timeMs, double baseline, boolean clean) {
            this.index=index; this.timeMs=timeMs; this.baseline=baseline; this.clean=clean;
        }
    }

    private final Listener listener;
    private final MorphologyModel morphologyModel;
    private final Biquad high = new Biquad(true, .8, FS);
    private final Biquad low = new Biquad(false, 32, FS);
    private final FloatRing filtered = new FloatRing(FS * 20);
    private final Sqi sqi = new Sqi();
    private final ArrayList<Beat> beats = new ArrayList<>();
    private final ArrayList<Double> normalRr = new ArrayList<>();
    private final ArrayList<Double> rrHistory = new ArrayList<>();
    private long index=-1, lastR=-1000000, lastPauseIndex=-1000000;
    private double prev2, prev1, signalAmp=260;
    private int bpm, esv, esa, pauses, af, tachy, brady, runs, eventCount;
    private long bradySince=-1, tachySince=-1;
    private double lastMorphologyScore;
    private final java.util.HashMap<String,Long> cooldown = new java.util.HashMap<>();

    public EcgEngine(Listener listener) { this(listener,new MorphologyModel()); }
    public EcgEngine(Listener listener, MorphologyModel morphologyModel) {
        this.listener=listener;this.morphologyModel=morphologyModel==null?new MorphologyModel():morphologyModel;
    }

    public void reset() {
        high.reset(); low.reset(); filtered.clear(); sqi.reset(); beats.clear(); normalRr.clear(); rrHistory.clear();
        index=-1; lastR=-1000000; lastPauseIndex=-1000000; prev2=prev1=0; signalAmp=260; bpm=0;
        esv=esa=pauses=af=tachy=brady=runs=eventCount=0; bradySince=tachySince=-1;lastMorphologyScore=0;cooldown.clear();
    }

    public float push(int rawUv, long timestampMs) {
        index++;
        int clipped=Math.max(-2500, Math.min(2500, rawUv));
        double y=low.process(high.process(clipped));
        y=Math.max(-3200, Math.min(3200, y));
        filtered.put(index, (float)y);
        sqi.push(clipped, (float)y, index);
        double a=Math.abs(y);
        if (prev1>prev2 && prev1>=a) considerPeak(index-1, prev1, timestampMs);
        prev2=prev1; prev1=a;
        checkPause(timestampMs);
        checkRateEpisodes(timestampMs);
        return (float)y;
    }

    public Snapshot snapshot() {
        return new Snapshot(bpm,esv,esa,pauses,af,tachy,brady,runs,eventCount,sqi.good(index),index+1,
                morphologyModel.normalCount(),morphologyModel.confirmedCount(),morphologyModel.isReady(),
                lastMorphologyScore,morphologyModel.threshold());
    }

    private void considerPeak(long peak, double amp, long timestampMs) {
        if (sqi.noisy) return;
        double since=(peak-lastR)*1000.0/FS;
        double threshold=Math.max(75, Math.max(sqi.noiseAbs*4, signalAmp*.30));
        if (amp<threshold || since<220) return;
        if (since<350 && amp<threshold*1.75) return;
        long best=peak; double bestAmp=amp;
        for(long i=Math.max(0,peak-4);i<=Math.min(index,peak+2);i++) {
            double v=Math.abs(filtered.get(i)); if(v>bestAmp){bestAmp=v;best=i;}
        }
        if ((best-lastR)*1000.0/FS<220) return;
        signalAmp=.875*signalAmp+.125*bestAmp;
        addBeat(best,timestampMs);
    }

    private void addBeat(long at, long timeMs) {
        double base=normalRr.isEmpty()?0:medianTail(normalRr,9);
        Beat b=new Beat(at,timeMs,base,sqi.good(at));
        if(!beats.isEmpty()) {
            double rr=(at-beats.get(beats.size()-1).index)*1000.0/FS;
            b.rr=rr; rrHistory.add(rr); trim(rrHistory,80);
            if(base==0 || (rr/base>=.82 && rr/base<=1.18)){normalRr.add(rr);trim(normalRr,24);}
            if(rr>=280&&rr<=2200)bpm=(int)Math.round(60000/rr);
        }
        beats.add(b); if(beats.size()>80)beats.remove(0);
        lastR=at;
        if(beats.size()>=3) classifyMiddle();
        learnStableMorphology();
        checkIrregularRhythm();
    }

    private void classifyMiddle() {
        Beat next=beats.get(beats.size()-1), cur=beats.get(beats.size()-2), prev=beats.get(beats.size()-3);
        double med=cur.baseline>0?cur.baseline:medianTail(normalRr,9);
        if(med<300||med>1800)return;
        double early=(cur.index-prev.index)*1000.0/FS;
        double after=(next.index-cur.index)*1000.0/FS;
        double prem=early/med, pauseRatio=after/med, comp=(early+after)/med;
        cur.width=estimateWidth(cur.index);
        cur.morphology=extractMorphology(cur.index);
        MorphologyModel.Result morph=morphologyModel.evaluate(cur.morphology);
        lastMorphologyScore=morph.score;
        if(prem>=.72 || progressiveRsa(early,med,cur.width)) {
            cur.label='N';
            checkPatterns(); return;
        }
        if(!(prev.clean&&cur.clean&&next.clean&&!sqi.noisy)){cur.label='?';return;}
        String modelDetail=morph.ready?String.format(Locale.FRANCE," • forme personnelle %.2f / seuil %.2f",morph.score,morph.threshold):
                " • modèle personnel en apprentissage ("+morphologyModel.normalCount()+"/"+MorphologyModel.BASELINE_TARGET+")";
        String detail=String.format(Locale.FRANCE,"RR %.0f → %.0f ms • référence %.0f ms • largeur %.0f ms",early,after,med,cur.width)+modelDetail;
        if(cur.width>=115&&pauseRatio>1.15&&comp>=1.68&&comp<=2.32){
            if(morph.ready&&!morph.anomaly){cur.label='?';emit("WIDE_PREMATURE","Battement prématuré à vérifier",detail+" • forme proche de ton rythme habituel",cur.index,cur.timeMs,0,8_000,cur.morphology,morph);}
            else{cur.label='V';esv++;emit("ESV","Extrasystole ventriculaire possible",detail,cur.index,cur.timeMs,1,8_000,cur.morphology,morph);}
        } else if(cur.width>=115){
            cur.label=morph.ready&&morph.anomaly?'V':'?';if(cur.label=='V')esv++;
            emit("WIDE_PREMATURE", "Battement prématuré large à vérifier", detail+" • pause compensatrice non démontrée",cur.index,cur.timeMs,0,8_000,cur.morphology,morph);
        } else if(cur.width<100&&pauseRatio>1.08&&(!morph.ready||!morph.anomaly)){
            cur.label='A'; esa++; emit("ESA", "Extrasystole auriculaire possible", detail, cur.index,cur.timeMs,1,8_000,cur.morphology,morph);
        } else {
            cur.label='?'; emit("PREMATURE", "Battement inhabituel à vérifier",detail,cur.index,cur.timeMs,0,8_000,cur.morphology,morph);
        }
        checkPatterns();
    }

    private boolean progressiveRsa(double rr,double med,double width) {
        if(width>=100||60000/med>=105||rrHistory.size()<4)return false;
        int n=rrHistory.size(); double a=rrHistory.get(n-4),b=rrHistory.get(n-3),c=rrHistory.get(n-2);
        return a>b&&b>c&&(a-b)<.22*med&&(b-c)<.22*med&&rr<med;
    }

    private void checkPatterns() {
        ArrayList<Character> labels=new ArrayList<>();
        for(int i=Math.max(0,beats.size()-12);i<beats.size()-1;i++)labels.add(beats.get(i).label);
        int consecutiveV=0; for(int i=labels.size()-1;i>=0&&labels.get(i)=='V';i--)consecutiveV++;
        Beat anchor=beats.get(beats.size()-2);
        if(consecutiveV>=3){runs++;emit("WIDE_RUN","Salve de complexes larges — TV non soutenue possible",
                consecutiveV+" battements ventriculaires possibles consécutifs. Origine à confirmer sur ECG médical.",anchor.index,anchor.timeMs,2,60_000);}
        else if(consecutiveV==2){runs++;emit("COUPLET","Couplet ventriculaire possible","Deux ESV possibles consécutives.",anchor.index,anchor.timeMs,2,60_000);}
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
        if(beats.size()<4||!sqi.good(index)||lastR<0)return;
        double gap=(index-lastR)*1000.0/FS;
        if(gap>=2000 && index-lastPauseIndex>FS*5L){lastPauseIndex=index;pauses++;emit("PAUSE","Pause possible",
                String.format(Locale.FRANCE,"Aucun QRS détecté depuis %.2f s sur signal stable.",gap/1000),index,timeMs,2,30_000);}
    }

    private void checkRateEpisodes(long timeMs) {
        if(!sqi.good(index)||bpm==0)return;
        if(bpm<40){if(bradySince<0)bradySince=index;if(index-bradySince>=FS*10L){brady++;emit("BRADY","Bradycardie prolongée possible",bpm+" bpm pendant au moins 10 s.",index,timeMs,2,300_000);bradySince=index;}}
        else bradySince=-1;
        if(bpm>150){if(tachySince<0)tachySince=index;if(index-tachySince>=FS*10L){tachy++;emit("TACHY","Tachycardie prolongée possible",bpm+" bpm pendant au moins 10 s.",index,timeMs,2,180_000);tachySince=index;}}
        else tachySince=-1;
        if(beats.size()>=10&&bpm>120){
            ArrayList<Double> r=new ArrayList<>();for(int i=beats.size()-8;i<beats.size();i++)if(beats.get(i).rr>0)r.add(beats.get(i).rr);
            double m=mean(r),cv=std(r,m)/m;
            if(r.size()>=7&&cv<.055){tachy++;emit("REGULAR_TACHY","Tachycardie régulière — TSV possible",
                    String.format(Locale.FRANCE,"Rythme régulier autour de %d bpm (CV RR %.3f). Début/fin et contexte à vérifier.",bpm,cv),index,timeMs,2,300_000);}
        }
    }

    private void checkIrregularRhythm() {
        if(beats.size()<28||!sqi.good(index))return;
        long cutoff=index-FS*30L;ArrayList<Double> r=new ArrayList<>();int ectopic=0;
        for(int i=1;i<beats.size();i++){Beat b=beats.get(i);if(b.index>=cutoff&&b.rr>=300&&b.rr<=1800){r.add(b.rr);if(b.label=='V'||b.label=='A')ectopic++;}}
        if(r.size()<25||ectopic>2)return;
        double m=mean(r),cv=std(r,m)/m,rm=0;int turns=0;
        for(int i=1;i<r.size();i++)rm+=Math.pow(r.get(i)-r.get(i-1),2);
        rm=Math.sqrt(rm/Math.max(1,r.size()-1))/m;
        for(int i=1;i<r.size()-1;i++){double a=r.get(i)-r.get(i-1),b=r.get(i+1)-r.get(i);if(a*b<0)turns++;}
        double tpr=turns/(double)Math.max(1,r.size()-2);
        if(cv>=.12&&rm>=.10&&tpr>=.55){af++;Beat b=beats.get(beats.size()-1);emit("IRREGULAR","Rythme très irrégulier — FA possible",
                String.format(Locale.FRANCE,"Fenêtre 30 s : CV RR %.3f, RMSSD normalisé %.3f, alternance %.2f. Relecture humaine requise.",cv,rm,tpr),b.index,b.timeMs,2,600_000);}
    }

    private double estimateWidth(long peak) {
        ArrayList<Double> baseVals=new ArrayList<>();
        for(long i=peak-30;i<=peak-21;i++)baseVals.add((double)filtered.get(i));
        for(long i=peak+23;i<=peak+31;i++)baseVals.add((double)filtered.get(i));
        double base=median(baseVals);ArrayList<Double> dev=new ArrayList<>();for(double v:baseVals)dev.add(Math.abs(v-base));double noise=Math.max(3,median(dev));
        long p=peak;double max=0;for(long i=peak-8;i<=peak+8;i++){double v=Math.abs(filtered.get(i)-base);if(v>max){max=v;p=i;}}
        if(max<1)return 0;double edge=Math.max(18,Math.max(noise*4,max*.14));long left=p,right=p;int quiet=0;
        for(long i=p-1;i>=p-24;i--){if(Math.abs(filtered.get(i)-base)<edge){if(++quiet>=2){left=i+2;break;}}else{quiet=0;left=i;}}
        quiet=0;for(long i=p+1;i<=p+24;i++){if(Math.abs(filtered.get(i)-base)<edge){if(++quiet>=2){right=i-2;break;}}else{quiet=0;right=i;}}
        return Math.max(0,(right-left+1)*1000.0/FS);
    }

    private void learnStableMorphology(){
        if(beats.size()<4)return;Beat candidate=beats.get(beats.size()-3);
        if(candidate.modelObserved||candidate.label!='N'||!candidate.clean||sqi.noisy)return;
        candidate.modelObserved=true;candidate.morphology=extractMorphology(candidate.index);morphologyModel.observeNormal(candidate.morphology);
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

    private void emit(String type,String title,String detail,long at,long time,int severity,long coolMs){
        emit(type,title,detail,at,time,severity,coolMs,null,null);
    }
    private void emit(String type,String title,String detail,long at,long time,int severity,long coolMs,float[] morphology,MorphologyModel.Result result){
        long now=System.currentTimeMillis(),last=cooldown.containsKey(type)?cooldown.get(type):0;
        if(now-last<coolMs)return;cooldown.put(type,now);eventCount++;if(listener!=null)listener.onEvent(new DetectionEvent(type,title,detail,at,time,severity,
                morphology,result==null?0:result.score,result==null?0:result.threshold,result!=null&&result.ready));
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

    private static final class Sqi {
        final int[] raw=new int[FS];final int[] diff=new int[FS];int pos,count,last;double baseline=20,noiseAbs=20;boolean noisy;long badUntil;
        void reset(){pos=count=last=0;baseline=20;noiseAbs=20;noisy=false;badUntil=0;}
        void push(int r,float f,long index){raw[pos]=r;diff[pos]=Math.abs(r-last);last=r;pos=(pos+1)%FS;if(count<FS)count++;if(index%(FS/4)!=0||count<FS)return;int[]a=raw.clone(),d=diff.clone();Arrays.sort(a);Arrays.sort(d);int p2p=a[FS-1]-a[0],p80=d[(int)(FS*.80)],clipped=0;for(int v:a)if(Math.abs(v)>=2450)clipped++;if(index<FS*4L){baseline=.9*baseline+.1*Math.max(1,p80);}boolean bad=p2p>5000||clipped>FS/100||p80>Math.max(180,baseline*3.8);if(bad)badUntil=index+FS*3L;else if(p80<baseline*1.8)baseline=.995*baseline+.005*p80;noiseAbs=Math.max(8,p80);noisy=index<badUntil;}
        boolean good(long index){return index>=FS*4L&&!noisy;}
    }

    private static final class Biquad {
        final double b0,b1,b2,a1,a2;double x1,x2,y1,y2;
        Biquad(boolean high,double hz,double fs){double w=2*Math.PI*hz/fs,cos=Math.cos(w),sin=Math.sin(w),alpha=sin/(2/Math.sqrt(2));double a0=1+alpha;
            if(high){b0=(1+cos)/2/a0;b1=-(1+cos)/a0;b2=b0;}else{b0=(1-cos)/2/a0;b1=(1-cos)/a0;b2=b0;}a1=-2*cos/a0;a2=(1-alpha)/a0;}
        double process(double x){double y=b0*x+b1*x1+b2*x2-a1*y1-a2*y2;x2=x1;x1=x;y2=y1;y1=y;return y;}void reset(){x1=x2=y1=y2=0;}
    }
}
