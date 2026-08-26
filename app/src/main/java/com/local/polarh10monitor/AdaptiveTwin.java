package com.local.polarh10monitor;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Modèle dose-réponse personnel, local et explicable.
 *
 * Le modèle apprend uniquement sur des paires de bilans matinaux propres. Il doit battre la
 * référence naïve « demain ressemble à aujourd'hui » avant d'être autorisé à produire une
 * prévision. Les résultats décrivent une réponse physiologique, jamais une maladie.
 */
public final class AdaptiveTwin {
    private static final String PREFS="adaptive_twin_v1", MODEL="model_v1",RATINGS="session_ratings_v1",EXPERIMENT="experiment_v1";
    private static final int FEATURES=10;

    private AdaptiveTwin(){}

    static double predictForTest(double[][] features,double[] targets,double[] query){ArrayList<Sample> samples=new ArrayList<>();for(int i=0;i<Math.min(features.length,targets.length);i++)samples.add(new Sample(features[i],targets[i],features[i][1],features[i][8]*60));LinearModel model=LinearModel.fit(samples);if(model==null)throw new IllegalStateException("Modèle non ajusté");return model.predict(query);}

    public static Report analyze(Context context,FitnessInsights.Summary summary){
        ArrayList<FitnessInsights.Morning> mornings=new ArrayList<>();
        for(FitnessInsights.Morning m:FitnessInsights.mornings(context))if(valid(m))mornings.add(m);
        mornings.sort(Comparator.comparingLong(m->m.timestampMs));
        Baseline baseline=Baseline.from(mornings);
        ArrayList<Sample> samples=new ArrayList<>();
        List<SessionHistory.Record> sessions=SessionHistory.list(context);Map<Long,SessionRating> ratings=ratings(context);
        for(int i=0;i+1<mornings.size();i++){
            FitnessInsights.Morning from=mornings.get(i),to=mornings.get(i+1);
            long gap=to.timestampMs-from.timestampMs;
            if(gap<18L*3_600_000L||gap>42L*3_600_000L)continue;
            double load=loadBetween(sessions,ratings,from.timestampMs,to.timestampMs);
            double state=baseline.state(from),target=baseline.state(to);
            samples.add(new Sample(features(from,state,load),target,state,load));
        }

        Evaluation evaluation=evaluate(samples);
        LinearModel model=LinearModel.fit(samples);
        boolean enough=samples.size()>=21&&evaluation.count>=10;
        boolean reliable=enough&&evaluation.mae+.04<evaluation.naiveMae&&model!=null;
        FitnessInsights.Morning current=summary.hasToday?summary.current:null;
        double currentState=current==null?0:baseline.state(current);
        Dose dose=chooseDose(summary,current,currentState,model,reliable,evaluation);
        int confidence=confidence(samples.size(),evaluation,reliable);
        String stage=samples.size()<4?"Collecte initiale":samples.size()<21?"Apprentissage personnel":reliable?"Prévision validée":"Modèle en vérification";
        String status;
        if(samples.size()<4)status="Il faut encore des bilans effectués à des jours différents pour apprendre ta réponse.";
        else if(samples.size()<21)status="Le modèle apprend sur "+samples.size()+" transition(s) entre deux matins propres. Il en faut au moins 21 avant d'autoriser une prévision ML.";
        else if(reliable)status="Le modèle personnel prédit mieux que la référence naïve sur les journées déjà observées.";
        else status="Le modèle n'est pas encore meilleur qu'une prévision simple : ses conseils restent prudents et non-ML.";

        ArrayList<String> factors=factors(summary,current,currentState,dose);
        ArrayList<Driver> drivers=drivers(mornings,baseline);
        String experiment=experimentText(experiment(context),drivers,mornings,baseline);
        persist(context,samples.size(),evaluation,reliable,confidence,model);
        return new Report(stage,status,reliable,samples.size(),evaluation.count,confidence,evaluation.mae,
                evaluation.naiveMae,dose.low,dose.high,dose.title,dose.detail,dose.forecast,
                dose.interval,factors,drivers,experiment,currentState);
    }

    public static void clear(Context context){context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear().apply();}

    public static SessionHistory.Record latestSessionToRate(Context context){
        Map<Long,SessionRating> saved=ratings(context);long cutoff=System.currentTimeMillis()-7L*86_400_000L;
        for(SessionHistory.Record r:SessionHistory.list(context))if(r.startMs>=cutoff&&r.endMs-r.startMs>=5*60_000L&&!saved.containsKey(r.startMs))return r;
        return null;
    }

    public static synchronized void saveSessionRating(Context context,long sessionStart,int rpe,boolean completed){
        Map<Long,SessionRating> all=new HashMap<>(ratings(context));all.put(sessionStart,new SessionRating(sessionStart,Math.max(1,Math.min(10,rpe)),completed));
        ArrayList<SessionRating> ordered=new ArrayList<>(all.values());ordered.sort(Comparator.comparingLong((SessionRating r)->r.sessionStart).reversed());
        JSONArray array=new JSONArray();for(int i=0;i<Math.min(180,ordered.size());i++)array.put(ordered.get(i).json());
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(RATINGS,array.toString()).apply();
    }

    public static ExperimentState experiment(Context context){try{return ExperimentState.from(new JSONObject(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(EXPERIMENT,"{}")));}catch(Exception ignored){return new ExperimentState("","",0,false);}}
    public static void startSleepExperiment(Context context){ExperimentState state=new ExperimentState("sleep_earlier_"+System.currentTimeMillis(),"Coucher 30 minutes plus tôt",System.currentTimeMillis(),true);context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(EXPERIMENT,state.json().toString()).apply();}
    public static void stopExperiment(Context context){context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove(EXPERIMENT).apply();}

    private static boolean valid(FitnessInsights.Morning m){return m!=null&&m.quality>=80&&m.nnCount>=90&&m.restingHr>25&&m.rmssd>2;}

    private static double[] features(FitnessInsights.Morning m,double state,double load){
        double l=Math.min(2.5,Math.max(0,load/60.0));
        return new double[]{1,clip(state,-3,3),(m.sleepQuality-3)/2.0,(m.stress-3)/2.0,
                (m.soreness-3)/2.0,m.symptoms?1:0,m.alcohol?1:0,m.hardTraining?1:0,l,l*l};
    }

    private static double loadBetween(List<SessionHistory.Record> sessions,Map<Long,SessionRating> ratings,long start,long end){
        double load=0;
        for(SessionHistory.Record r:sessions){
            if(r.startMs<start||r.endMs>end)continue;
            double sessionLoad=r.moderateMs/60_000.0+2.0*r.vigorousMs/60_000.0;SessionRating rating=ratings.get(r.startMs);
            if(rating!=null){sessionLoad*=.65+.07*rating.rpe;if(!rating.completed)sessionLoad*=.75;}
            load+=sessionLoad;
        }
        return Math.min(180,load);
    }

    private static Map<Long,SessionRating> ratings(Context context){HashMap<Long,SessionRating> out=new HashMap<>();try{JSONArray a=new JSONArray(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(RATINGS,"[]"));for(int i=0;i<a.length();i++){SessionRating r=SessionRating.from(a.getJSONObject(i));out.put(r.sessionStart,r);}}catch(Exception ignored){}return out;}

    private static Evaluation evaluate(List<Sample> samples){
        double error=0,naive=0;int count=0;
        for(int i=6;i<samples.size();i++){
            LinearModel m=LinearModel.fit(samples.subList(0,i));if(m==null)continue;
            Sample s=samples.get(i);double prediction=m.predict(s.x);
            error+=Math.abs(prediction-s.y);naive+=Math.abs(s.previousState-s.y);count++;
        }
        double mae=count==0?0:error/count,naiveMae=count==0?0:naive/count;
        double residual=0;int residualCount=0;LinearModel all=LinearModel.fit(samples);
        if(all!=null)for(Sample s:samples){double d=s.y-all.predict(s.x);residual+=d*d;residualCount++;}
        return new Evaluation(count,mae,naiveMae,residualCount<2?1:Math.sqrt(residual/(residualCount-FEATURES>1?residualCount-FEATURES:residualCount)));
    }

    private static Dose chooseDose(FitnessInsights.Summary summary,FitnessInsights.Morning current,double state,LinearModel model,boolean reliable,Evaluation eval){
        if(current==null)return new Dose(0,0,"Mesure nécessaire","Réalise le bilan matinal avant de choisir une charge.","Aucune prévision aujourd'hui.","");
        if(current.symptoms)return new Dose(0,20,"Repos ou activité très légère","Des symptômes ont été déclarés : le modèle ne propose pas de séance soutenue.","Réponse de demain non estimée en présence de symptômes.","Décision de sécurité");
        int conservativeHigh=state<-.9||current.stress>=4||current.soreness>=4?30:state<-.35?45:65;
        int conservativeLow=Math.max(0,conservativeHigh-25);
        if(!reliable||model==null){
            String detail=(state<-.9?"La réponse du jour est sous ta plage habituelle. ":"Fourchette prudente pendant que le modèle vérifie ses prédictions. ")+planFor(summary.profile,conservativeLow,conservativeHigh);
            return new Dose(conservativeLow,conservativeHigh,"Charge prudente",detail,"Prévision ML masquée tant qu'elle ne bat pas la référence naïve.","Apprentissage en cours");
        }
        int bestHigh=15;double bestPrediction=-9;
        for(int load=0;load<=120;load+=5){double predicted=model.predict(features(current,state,load));double lower=predicted-1.28*Math.max(.25,eval.rmse);if(lower>=-.85){bestHigh=load;bestPrediction=predicted;}}
        bestHigh=Math.min(bestHigh,Math.max(25,conservativeHigh+25));int low=Math.max(0,bestHigh-25);
        double predicted=model.predict(features(current,state,(low+bestHigh)/2.0));double margin=1.28*Math.max(.25,eval.rmse);
        String response=predicted<-.65?"probablement sous ta plage habituelle":predicted>.65?"probablement au-dessus de ta plage habituelle":"probablement proche de ta plage habituelle";
        String interval=String.format(Locale.FRANCE,"intervalle personnel %.1f à %.1f",predicted-margin,predicted+margin);
        return new Dose(low,bestHigh,"Dose personnelle",planFor(summary.profile,low,bestHigh),"Avec cette charge, la réponse de demain est "+response+".",interval);
    }

    private static String planFor(FitnessInsights.Profile profile,int low,int high){String sport=profile.sport.toLowerCase(Locale.ROOT);if(high<=20)return "Repos, marche tranquille ou mobilité selon tes sensations.";if(sport.contains("course")||sport.contains("running"))return low+" à "+high+" min de course majoritairement facile ; garde les passages soutenus dans la fourchette affichée.";if(sport.contains("vélo")||sport.contains("velo")||sport.contains("cycl"))return Math.max(20,low+10)+" à "+(high+20)+" min de vélo facile à modéré.";if(sport.contains("muscu")||sport.contains("force")||sport.contains("fitness"))return "Séance de "+Math.max(25,low)+" à "+Math.max(40,high)+" min, effort perçu autour de 6/10 ; la note après séance corrigera la charge cardiaque incomplète.";return low+" à "+high+" min d'activité facile à modérée, sans dépasser tes sensations habituelles.";}

    private static ArrayList<String> factors(FitnessInsights.Summary s,FitnessInsights.Morning current,double state,Dose dose){
        ArrayList<String> out=new ArrayList<>();
        if(current==null){out.add("Aucune décision sans bilan matinal comparable.");return out;}
        if(s.baselineHr>0)out.add(String.format(Locale.FRANCE,"FC au repos %.0f bpm • référence %.0f.",current.restingHr,s.baselineHr));
        if(s.baselineRmssd>0)out.add(String.format(Locale.FRANCE,"RMSSD %.0f ms • référence %.0f.",current.rmssd,s.baselineRmssd));
        if(state<-.8)out.add("Réponse combinée FC/VFC nettement sous la plage personnelle.");else if(state>.8)out.add("Réponse combinée FC/VFC au-dessus de la plage personnelle.");else out.add("Réponse combinée FC/VFC proche du fonctionnement habituel.");
        if(current.sleepQuality<=2)out.add("Sommeil déclaré faible aujourd'hui.");
        if(current.stress>=4)out.add("Stress déclaré élevé aujourd'hui.");
        if(current.soreness>=4)out.add("Fatigue musculaire déclarée élevée.");
        if(current.alcohol)out.add("Alcool récent déclaré : facteur traité séparément dans l'apprentissage.");
        out.add("Charge proposée : "+dose.low+"–"+dose.high+" minutes équivalentes modérées.");
        return out;
    }

    private static ArrayList<Driver> drivers(List<FitnessInsights.Morning> mornings,Baseline baseline){
        ArrayList<Driver> out=new ArrayList<>();
        addDriver(out,"Sommeil faible",mornings,baseline,m->m.sleepQuality<=2);
        addDriver(out,"Stress élevé",mornings,baseline,m->m.stress>=4);
        addDriver(out,"Fatigue musculaire",mornings,baseline,m->m.soreness>=4);
        addDriver(out,"Alcool récent",mornings,baseline,m->m.alcohol);
        addDriver(out,"Séance difficile récente",mornings,baseline,m->m.hardTraining);
        out.sort(Comparator.comparingDouble((Driver d)->Math.abs(d.effect)).reversed());
        return out;
    }

    private interface Exposure{boolean yes(FitnessInsights.Morning m);}
    private static void addDriver(List<Driver> out,String name,List<FitnessInsights.Morning> all,Baseline baseline,Exposure exposure){
        double exposed=0,control=0;int a=0,b=0;
        for(FitnessInsights.Morning m:all){if(exposure.yes(m)){exposed+=baseline.state(m);a++;}else{control+=baseline.state(m);b++;}}
        double effect=a==0||b==0?0:exposed/a-control/b;boolean usable=a>=3&&b>=5;
        String interpretation=!usable?"Pas encore assez de journées comparables":effect<-.35?"Associé à une réponse plus basse":effect>.35?"Associé à une réponse plus haute":"Aucun effet net observé";
        out.add(new Driver(name,effect,a,b,usable,interpretation));
    }

    private static String experimentText(ExperimentState experiment,List<Driver> drivers,List<FitnessInsights.Morning> mornings,Baseline baseline){
        if(experiment.active){double followed=0,other=0;int yes=0,no=0;for(FitnessInsights.Morning m:mornings){if(!experiment.id.equals(m.experimentId))continue;if(m.experimentFollowed){followed+=baseline.state(m);yes++;}else{other+=baseline.state(m);no++;}}if(yes>=3&&no>=3){double effect=followed/yes-other/no;return experiment.title+" • "+yes+" jour(s) respecté(s), "+no+" non respecté(s). Réponse observée "+(effect>.25?"plus favorable":effect<-.25?"moins favorable":"sans différence nette")+" ("+String.format(Locale.FRANCE,"%+.2f",effect)+" écart-type).";}return experiment.title+" • "+(yes+no)+" / 12 matin(s) documenté(s). Le résultat restera masqué jusqu'à au moins 3 journées dans chaque groupe.";}
        if(mornings.size()<7)return "Continue les bilans standardisés : une première hypothèse personnelle apparaîtra après sept journées propres.";
        for(Driver d:drivers)if(!d.usable&&d.exposed>0)return "Hypothèse à documenter : « "+d.name.toLowerCase(Locale.FRANCE)+" ». Il manque des journées comparables pour savoir si l'association se répète.";
        for(Driver d:drivers)if(d.usable&&Math.abs(d.effect)>=.35)return "À vérifier sur les prochaines semaines : "+d.name.toLowerCase(Locale.FRANCE)+" est "+(d.effect<0?"associé à une réponse moins favorable":"associé à une réponse plus favorable")+". Ce n'est pas encore une preuve de causalité.";
        return "Aucun facteur dominant n'apparaît encore. Le modèle continuera à comparer des journées semblables.";
    }

    private static int confidence(int samples,Evaluation e,boolean reliable){
        if(samples<4)return Math.min(20,samples*5);if(!reliable)return Math.min(55,20+samples*2);
        double gain=e.naiveMae<=0?0:(e.naiveMae-e.mae)/e.naiveMae;
        return (int)Math.round(Math.min(92,58+Math.min(22,samples)+Math.max(0,12*gain)));
    }

    private static void persist(Context context,int samples,Evaluation e,boolean reliable,int confidence,LinearModel model){
        try{JSONObject o=new JSONObject();o.put("version",1);o.put("updated",System.currentTimeMillis());o.put("samples",samples);o.put("evaluated",e.count);o.put("mae",e.mae);o.put("naiveMae",e.naiveMae);o.put("reliable",reliable);o.put("confidence",confidence);JSONArray weights=new JSONArray();if(model!=null)for(double v:model.beta)weights.put(v);o.put("weights",weights);context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putString(MODEL,o.toString()).apply();}catch(Exception ignored){}
    }

    private static double clip(double value,double min,double max){return Math.max(min,Math.min(max,value));}
    private static double median(List<Double> source){if(source.isEmpty())return 0;ArrayList<Double>x=new ArrayList<>(source);Collections.sort(x);int n=x.size();return n%2==1?x.get(n/2):(x.get(n/2-1)+x.get(n/2))/2;}

    private static final class Baseline{
        final double hr,hrScale,logHrv,hrvScale;
        Baseline(double hr,double hrScale,double logHrv,double hrvScale){this.hr=hr;this.hrScale=hrScale;this.logHrv=logHrv;this.hrvScale=hrvScale;}
        static Baseline from(List<FitnessInsights.Morning> all){ArrayList<Double> hrs=new ArrayList<>(),hrv=new ArrayList<>();for(FitnessInsights.Morning m:all){hrs.add(m.restingHr);hrv.add(Math.log(m.rmssd));}double h=median(hrs),v=median(hrv);ArrayList<Double> hd=new ArrayList<>(),vd=new ArrayList<>();for(double x:hrs)hd.add(Math.abs(x-h));for(double x:hrv)vd.add(Math.abs(x-v));return new Baseline(h,Math.max(2,1.4826*median(hd)),v,Math.max(.08,1.4826*median(vd)));}
        double state(FitnessInsights.Morning m){double hrvZ=(Math.log(m.rmssd)-logHrv)/hrvScale,hrZ=(m.restingHr-hr)/hrScale;return clip(.58*hrvZ-.42*hrZ,-3,3);}
    }

    private static final class Sample{final double[]x;final double y,previousState,load;Sample(double[]x,double y,double previousState,double load){this.x=x;this.y=y;this.previousState=previousState;this.load=load;}}
    private static final class SessionRating{final long sessionStart;final int rpe;final boolean completed;SessionRating(long sessionStart,int rpe,boolean completed){this.sessionStart=sessionStart;this.rpe=rpe;this.completed=completed;}JSONObject json(){JSONObject o=new JSONObject();try{o.put("session",sessionStart);o.put("rpe",rpe);o.put("completed",completed);}catch(Exception ignored){}return o;}static SessionRating from(JSONObject o){return new SessionRating(o.optLong("session"),o.optInt("rpe",5),o.optBoolean("completed",true));}}
    private static final class Evaluation{final int count;final double mae,naiveMae,rmse;Evaluation(int count,double mae,double naiveMae,double rmse){this.count=count;this.mae=mae;this.naiveMae=naiveMae;this.rmse=rmse;}}
    private static final class Dose{final int low,high;final String title,detail,forecast,interval;Dose(int low,int high,String title,String detail,String forecast,String interval){this.low=low;this.high=high;this.title=title;this.detail=detail;this.forecast=forecast;this.interval=interval;}}

    private static final class LinearModel{
        final double[]beta;LinearModel(double[]beta){this.beta=beta;}
        double predict(double[]x){double out=0;for(int i=0;i<Math.min(beta.length,x.length);i++)out+=beta[i]*x[i];return clip(out,-3.5,3.5);}
        static LinearModel fit(List<Sample> samples){if(samples.size()<4)return null;double[][]a=new double[FEATURES][FEATURES];double[]b=new double[FEATURES];for(Sample s:samples)for(int i=0;i<FEATURES;i++){b[i]+=s.x[i]*s.y;for(int j=0;j<FEATURES;j++)a[i][j]+=s.x[i]*s.x[j];}for(int i=0;i<FEATURES;i++)a[i][i]+=i==0?.2:2.0;double[]solution=solve(a,b);return solution==null?null:new LinearModel(solution);}
        private static double[] solve(double[][]matrix,double[]target){int n=target.length;double[][]m=new double[n][n+1];for(int i=0;i<n;i++){System.arraycopy(matrix[i],0,m[i],0,n);m[i][n]=target[i];}for(int col=0;col<n;col++){int pivot=col;for(int row=col+1;row<n;row++)if(Math.abs(m[row][col])>Math.abs(m[pivot][col]))pivot=row;if(Math.abs(m[pivot][col])<1e-9)return null;double[]tmp=m[col];m[col]=m[pivot];m[pivot]=tmp;double div=m[col][col];for(int j=col;j<=n;j++)m[col][j]/=div;for(int row=0;row<n;row++)if(row!=col){double f=m[row][col];for(int j=col;j<=n;j++)m[row][j]-=f*m[col][j];}}double[]x=new double[n];for(int i=0;i<n;i++)x[i]=m[i][n];return x;}
    }

    public static final class Driver{
        public final String name,interpretation;public final double effect;public final int exposed,control;public final boolean usable;
        Driver(String name,double effect,int exposed,int control,boolean usable,String interpretation){this.name=name;this.effect=effect;this.exposed=exposed;this.control=control;this.usable=usable;this.interpretation=interpretation;}
    }

    public static final class ExperimentState{public final String id,title;public final long startMs;public final boolean active;ExperimentState(String id,String title,long startMs,boolean active){this.id=id;this.title=title;this.startMs=startMs;this.active=active;}JSONObject json(){JSONObject o=new JSONObject();try{o.put("id",id);o.put("title",title);o.put("start",startMs);o.put("active",active);}catch(Exception ignored){}return o;}static ExperimentState from(JSONObject o){return new ExperimentState(o.optString("id"),o.optString("title"),o.optLong("start"),o.optBoolean("active"));}}

    public static final class Report{
        public final String stage,status,doseTitle,doseDetail,forecast,interval,experiment;public final boolean reliable;
        public final int samples,evaluated,confidence,doseLow,doseHigh;public final double modelMae,naiveMae,currentState;
        public final List<String>factors;public final List<Driver>drivers;
        Report(String stage,String status,boolean reliable,int samples,int evaluated,int confidence,double modelMae,double naiveMae,int doseLow,int doseHigh,String doseTitle,String doseDetail,String forecast,String interval,List<String>factors,List<Driver>drivers,String experiment,double currentState){this.stage=stage;this.status=status;this.reliable=reliable;this.samples=samples;this.evaluated=evaluated;this.confidence=confidence;this.modelMae=modelMae;this.naiveMae=naiveMae;this.doseLow=doseLow;this.doseHigh=doseHigh;this.doseTitle=doseTitle;this.doseDetail=doseDetail;this.forecast=forecast;this.interval=interval;this.factors=Collections.unmodifiableList(factors);this.drivers=Collections.unmodifiableList(drivers);this.experiment=experiment;this.currentState=currentState;}
    }
}
