package com.local.polarh10monitor;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Profil de forme et référence physiologique personnels.
 *
 * Les calculs restent volontairement explicables : statistiques robustes, tendance glissante et
 * contexte déclaré. Cette classe ne pose aucun diagnostic et ne transmet aucune donnée.
 */
public final class FitnessInsights {
    private static final String PREFS="fitness_local_v1",PROFILE="profile_v1",MORNINGS="mornings_v1";
    private static final int MORNING_LIMIT=180;

    private FitnessInsights(){}

    public static Profile profile(Context context){
        String raw=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(PROFILE,"");
        return Profile.from(raw);
    }

    public static void saveProfile(Context context,Profile profile){
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(PROFILE,profile.json().toString()).apply();
    }

    public static synchronized void addMorning(Context context,Morning record){
        LocalRepository db=LocalRepository.get(context);long day=dayStart(record.timestampMs);
        Calendar next=Calendar.getInstance();next.setTimeInMillis(day);next.add(Calendar.DATE,1);
        db.replaceDay("morning",String.valueOf(record.timestampMs),record.timestampMs,record.json(),day,next.getTimeInMillis());
    }

    public static List<Morning> mornings(Context context){
        ArrayList<Morning> out=new ArrayList<>();
        for(JSONObject o:LocalRepository.get(context).list("morning"))out.add(Morning.from(o));
        out.sort(Comparator.comparingLong((Morning item)->item.timestampMs).reversed());return Collections.unmodifiableList(out);
    }

    public static synchronized void clear(Context context){context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear().apply();LocalRepository.get(context).clear("morning");}

    public static Summary summarize(Context context){
        Profile p=profile(context);List<Morning> all=mornings(context);Morning current=null;for(Morning m:all)if(m.continuityVerified){current=m;break;}
        boolean today=current!=null&&dayStart(current.timestampMs)==dayStart(System.currentTimeMillis());
        ArrayList<Morning> reference=new ArrayList<>();
        long cutoff=System.currentTimeMillis()-60L*86_400_000L;
        for(Morning m:all)if(m!=current&&m.continuityVerified&&m.timestampMs>=cutoff&&m.quality>=80&&m.rmssd>0&&m.restingHr>0)reference.add(m);

        double baseHr=medianMorning(reference,true),baseRmssd=medianMorning(reference,false);
        if(baseHr<=0&&current!=null)baseHr=current.restingHr;if(baseRmssd<=0&&current!=null)baseRmssd=current.rmssd;
        double hrMad=madMorning(reference,true,baseHr),logRmssdMedian=medianLogRmssd(reference),logMad=madLogRmssd(reference,logRmssdMedian);
        double hrZ=0,hrvZ=0;
        if(today&&reference.size()>=4){hrZ=(current.restingHr-baseHr)/Math.max(2.0,hrMad*1.4826);hrvZ=(Math.log(Math.max(1,current.rmssd))-logRmssdMedian)/Math.max(.08,logMad*1.4826);}

        int score=82;ArrayList<String> why=new ArrayList<>(),advice=new ArrayList<>();
        String state="Bilan du jour à réaliser",stateDetail="Trois minutes au calme permettent de comparer ta journée à ta propre référence.";
        if(today){
            if(reference.size()<4){state="Référence en construction";stateDetail="Encore "+Math.max(0,7-reference.size()-1)+" bilan(s) propre(s) pour commencer une comparaison personnelle.";score=72;why.add("La mesure du jour est conservée, mais la référence personnelle est encore courte.");}
            else{
                score-=Math.round((float)Math.max(0,hrZ)*11);score-=Math.round((float)Math.max(0,-hrvZ)*13);
                if(current.sleepQuality<=2){score-=10;why.add("Sommeil déclaré moins réparateur que souhaité.");}
                if(current.stress>=4){score-=8;why.add("Niveau de stress déclaré élevé.");}
                if(current.soreness>=4){score-=7;why.add("Fatigue musculaire déclarée importante.");}
                if(current.alcohol){score-=6;why.add("L’alcool peut modifier la FC de repos et la VFC.");}
                if(current.hardTraining){score-=7;why.add("Une séance difficile récente peut expliquer une récupération réduite.");}
                if(current.symptoms){score-=18;why.add("Des symptômes ont été déclarés aujourd’hui.");}
                if(hrZ>1.0)why.add(String.format(Locale.FRANCE,"FC de repos %.0f bpm au-dessus de la référence.",current.restingHr-baseHr));
                else if(hrZ<-.8)why.add("FC de repos plus basse que la référence personnelle.");
                if(hrvZ<-.9)why.add("VFC inférieure à la plage personnelle habituelle.");
                else if(hrvZ>.9)why.add("VFC supérieure à la référence personnelle.");
                score=Math.max(20,Math.min(96,score));
                boolean marked=(hrZ>1.5&&hrvZ<-1.3)||current.symptoms;
                if(marked){state="Écart inhabituel";stateDetail="Allège la journée et vérifie le sommeil, la charge récente et d’éventuels symptômes.";}
                else if(score<55){state="Récupération réduite";stateDetail="Une journée légère est plus cohérente avec les signaux et le contexte du jour.";}
                else if(score<70){state="Charge accumulée";stateDetail="La récupération paraît un peu diminuée, sans cause spécifique identifiable.";}
                else if(score<86){state="Proche de tes habitudes";stateDetail="Les indicateurs sont globalement proches de ta référence personnelle.";}
                else{state="Mesures favorables";stateDetail="Les indicateurs du jour sont favorables par rapport à ton fonctionnement habituel.";}
            }
        }
        if(why.isEmpty())why.add(today?"Aucun écart important n’est visible dans les données disponibles.":"Aucune interprétation n’est produite sans bilan matinal standardisé.");
        if(today&&current.symptoms)advice.add("En présence de symptômes, privilégie le repos et demande un avis médical si nécessaire.");
        else if(score<70)advice.add("Choisis aujourd’hui une activité facile, ou du repos si tu ne te sens pas bien.");
        else advice.add("Si tes sensations sont bonnes, tu peux suivre la séance prévue sans chercher à dépasser tes habitudes.");
        String goal=p.goal.toLowerCase(Locale.ROOT);
        if(goal.contains("endurance"))advice.add("Pour l’endurance, privilégie la régularité et augmente la charge progressivement plutôt qu’en une seule séance.");
        else if(goal.contains("reprise"))advice.add("Pour une reprise, commence sous ton niveau maximal perçu et augmente seulement si la récupération reste stable.");
        else if(goal.contains("poids")||goal.contains("mincir"))advice.add("Pour la gestion du poids, la constance hebdomadaire compte davantage qu’une séance isolée très intense.");
        else if(goal.contains("force")||goal.contains("muscle"))advice.add("Pour la force, conserve des jours de récupération entre deux sollicitations importantes du même groupe musculaire.");
        advice.add("Compare surtout les tendances sur plusieurs jours, jamais une valeur isolée.");
        advice.add("Douleur thoracique, malaise, essoufflement important ou perte de connaissance : arrête l’effort et contacte les urgences.");

        SessionHistory.Aggregate week=SessionHistory.aggregate(context,7);
        int observedModerate=(int)Math.round(week.moderateMs/60_000.0),observedVigorous=(int)Math.round(week.vigorousMs/60_000.0);
        int equivalent=observedModerate+2*observedVigorous;
        int declared=p.weeklyModerate+2*p.weeklyVigorous;
        int activityBasis=Math.max(equivalent,declared);
        int adaptation=0; // Kept for storage/API compatibility; not a physiological fitness score.
        String training=p.complete()?"Habitudes renseignées":"Profil à compléter";
        String confidence=all.size()>=28?"Élevée":all.size()>=14?"Bonne":all.size()>=7?"En progression":"Provisoire";
        double trend7=trend(all,7),trendPrevious=trendRange(all,7,28);double hrvTrend=trendPrevious>0?100*(trend7-trendPrevious)/trendPrevious:0;
        return new Summary(p,current,today,all.size(),reference.size(),state,stateDetail,score,confidence,training,adaptation,
                baseHr,baseRmssd,hrZ,hrvZ,hrvTrend,observedModerate,observedVigorous,why,advice);
    }

    private static double trend(List<Morning> all,int days){return trendRange(all,0,days);}
    private static double trendRange(List<Morning> all,int fromDays,int toDays){long now=System.currentTimeMillis(),newer=now-fromDays*86_400_000L,older=now-toDays*86_400_000L;ArrayList<Double>x=new ArrayList<>();for(Morning m:all)if(m.continuityVerified&&m.timestampMs<=newer&&m.timestampMs>=older&&m.rmssd>0&&m.quality>=80)x.add(m.rmssd);return median(x);}
    private static double medianMorning(List<Morning>x,boolean hr){ArrayList<Double>v=new ArrayList<>();for(Morning m:x)v.add(hr?m.restingHr:m.rmssd);return median(v);}
    private static double madMorning(List<Morning>x,boolean hr,double center){ArrayList<Double>v=new ArrayList<>();for(Morning m:x)v.add(Math.abs((hr?m.restingHr:m.rmssd)-center));return median(v);}
    private static double medianLogRmssd(List<Morning>x){ArrayList<Double>v=new ArrayList<>();for(Morning m:x)v.add(Math.log(Math.max(1,m.rmssd)));return median(v);}
    private static double madLogRmssd(List<Morning>x,double center){ArrayList<Double>v=new ArrayList<>();for(Morning m:x)v.add(Math.abs(Math.log(Math.max(1,m.rmssd))-center));return median(v);}
    private static double median(List<Double>x){if(x.isEmpty())return 0;ArrayList<Double>v=new ArrayList<>(x);Collections.sort(v);int n=v.size();return n%2==1?v.get(n/2):(v.get(n/2-1)+v.get(n/2))/2;}
    private static long dayStart(long timestamp){Calendar c=Calendar.getInstance();c.setTimeInMillis(timestamp);c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);return c.getTimeInMillis();}

    public static final class Profile{
        public final int age,heightCm,weightKg,weeklyModerate,weeklyVigorous,strengthDays,trainingYears;
        public final String sex,sport,goal;public final boolean hrMedication;
        public Profile(int age,int heightCm,int weightKg,String sex,String sport,String goal,int weeklyModerate,int weeklyVigorous,int strengthDays,int trainingYears,boolean hrMedication){this.age=age;this.heightCm=heightCm;this.weightKg=weightKg;this.sex=sex==null?"":sex;this.sport=sport==null?"":sport;this.goal=goal==null?"":goal;this.weeklyModerate=weeklyModerate;this.weeklyVigorous=weeklyVigorous;this.strengthDays=strengthDays;this.trainingYears=trainingYears;this.hrMedication=hrMedication;}
        public boolean complete(){return age>=16&&age<=100&&heightCm>=120&&heightCm<=230&&weightKg>=35&&weightKg<=250;}
        public double bmi(){return complete()?weightKg/Math.pow(heightCm/100.0,2):0;}
        JSONObject json(){JSONObject o=new JSONObject();try{o.put("age",age);o.put("height",heightCm);o.put("weight",weightKg);o.put("sex",sex);o.put("sport",sport);o.put("goal",goal);o.put("moderate",weeklyModerate);o.put("vigorous",weeklyVigorous);o.put("strength",strengthDays);o.put("years",trainingYears);o.put("medication",hrMedication);}catch(Exception ignored){}return o;}
        static Profile from(String raw){try{JSONObject o=new JSONObject(raw);return new Profile(o.optInt("age"),o.optInt("height"),o.optInt("weight"),o.optString("sex"),o.optString("sport"),o.optString("goal"),o.optInt("moderate"),o.optInt("vigorous"),o.optInt("strength"),o.optInt("years"),o.optBoolean("medication"));}catch(Exception ignored){return new Profile(0,0,0,"","","",0,0,0,0,false);}}
    }

    public static final class Morning{
        public boolean continuityVerified;public final long timestampMs;public final double restingHr,rmssd,sdnn,quality;public final int nnCount,sleepQuality,stress,soreness;public final boolean symptoms,alcohol,hardTraining,experimentFollowed;public final String experimentId;
        public Morning(long timestampMs,double restingHr,double rmssd,double sdnn,double quality,int nnCount,int sleepQuality,int stress,int soreness,boolean symptoms,boolean alcohol,boolean hardTraining){this(timestampMs,restingHr,rmssd,sdnn,quality,nnCount,sleepQuality,stress,soreness,symptoms,alcohol,hardTraining,"",false);}
        public Morning(long timestampMs,double restingHr,double rmssd,double sdnn,double quality,int nnCount,int sleepQuality,int stress,int soreness,boolean symptoms,boolean alcohol,boolean hardTraining,String experimentId,boolean experimentFollowed){this.timestampMs=timestampMs;this.restingHr=restingHr;this.rmssd=rmssd;this.sdnn=sdnn;this.quality=quality;this.nnCount=nnCount;this.sleepQuality=sleepQuality;this.stress=stress;this.soreness=soreness;this.symptoms=symptoms;this.alcohol=alcohol;this.hardTraining=hardTraining;this.experimentId=experimentId==null?"":experimentId;this.experimentFollowed=experimentFollowed;}
        JSONObject json(){JSONObject o=new JSONObject();try{o.put("continuityVerified",continuityVerified);o.put("time",timestampMs);o.put("hr",restingHr);o.put("rmssd",rmssd);o.put("sdnn",sdnn);o.put("quality",quality);o.put("nn",nnCount);o.put("sleep",sleepQuality);o.put("stress",stress);o.put("soreness",soreness);o.put("symptoms",symptoms);o.put("alcohol",alcohol);o.put("hard",hardTraining);o.put("experiment",experimentId);o.put("experimentFollowed",experimentFollowed);}catch(Exception ignored){}return o;}
        static Morning from(JSONObject o){Morning result=new Morning(o.optLong("time"),o.optDouble("hr"),o.optDouble("rmssd"),o.optDouble("sdnn"),o.optDouble("quality"),o.optInt("nn"),o.optInt("sleep",3),o.optInt("stress",3),o.optInt("soreness",3),o.optBoolean("symptoms"),o.optBoolean("alcohol"),o.optBoolean("hard"),o.optString("experiment"),o.optBoolean("experimentFollowed"));result.continuityVerified=o.optBoolean("continuityVerified",false);return result;}
    }

    public static final class Summary{
        public final Profile profile;public final Morning current;public final boolean hasToday;public final int totalMornings,referenceCount,readinessScore,adaptationScore,observedModerate,observedVigorous;
        public final String state,stateDetail,confidence,trainingProfile;public final double baselineHr,baselineRmssd,hrZ,hrvZ,hrvTrendPercent;public final List<String> why,advice;
        Summary(Profile profile,Morning current,boolean hasToday,int totalMornings,int referenceCount,String state,String stateDetail,int readinessScore,String confidence,String trainingProfile,int adaptationScore,double baselineHr,double baselineRmssd,double hrZ,double hrvZ,double hrvTrendPercent,int observedModerate,int observedVigorous,List<String>why,List<String>advice){this.profile=profile;this.current=current;this.hasToday=hasToday;this.totalMornings=totalMornings;this.referenceCount=referenceCount;this.state=state;this.stateDetail=stateDetail;this.readinessScore=readinessScore;this.confidence=confidence;this.trainingProfile=trainingProfile;this.adaptationScore=adaptationScore;this.baselineHr=baselineHr;this.baselineRmssd=baselineRmssd;this.hrZ=hrZ;this.hrvZ=hrvZ;this.hrvTrendPercent=hrvTrendPercent;this.observedModerate=observedModerate;this.observedVigorous=observedVigorous;this.why=Collections.unmodifiableList(why);this.advice=Collections.unmodifiableList(advice);}
    }
}
