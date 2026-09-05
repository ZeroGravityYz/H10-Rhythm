package com.local.polarh10monitor;

import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.os.Build;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.io.File;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class EventHistory {
    private static final String PREFS="event_history";
    private static final String KEY="records_v2";
    private static final int LIMIT=250;

    private EventHistory() {}

    public static synchronized void add(Context context,String id,EcgEngine.DetectionEvent event) {
        LocalRepository db=LocalRepository.get(context);if(db.find("event",id)!=null)return;
        Record record=new Record(id,event.type,event.title,event.detail,event.timestampMs,event.severity,false,"",event.morphology,
                event.morphologyScore,event.morphologyThreshold,event.personalModelReady,event.bpm,event.rmssdMs,event.sdnnMs,event.signalQualityPercent);
        db.put("event",id,event.timestampMs,record.toJson());
    }
    public static synchronized void markReady(Context context,String id){state(context,id,"ready","");}
    public static synchronized void state(Context context,String id,String state,String error){LocalRepository db=LocalRepository.get(context);JSONObject o=db.find("event",id);if(o==null)return;try{o.put("ready",state.equals("ready"));o.put("reportState",state);o.put("reportError",error);db.put("event",id,o.optLong("time"),o);}catch(org.json.JSONException e){throw new IllegalStateException(e);}}
    public static synchronized void review(Context context,String id,String review) {
        if(!java.util.Arrays.asList("normal","anomaly","artifact","").contains(review))throw new IllegalArgumentException("annotation");
        LocalRepository db=LocalRepository.get(context);JSONObject o=db.find("event",id);if(o==null)return;try{o.put("review",review);db.put("event",id,o.optLong("time"),o);db.put("annotation",id,o.optLong("time"),o);}catch(org.json.JSONException e){throw new IllegalStateException(e);}
    }

    public static synchronized List<Record> list(Context context) {
        ArrayList<Record> records=new ArrayList<>();
        try {
            for(JSONObject o:LocalRepository.get(context).list("event"))records.add(Record.fromJson(o));
        } catch(Exception error) {throw new IllegalStateException("Historique illisible ; données conservées",error);}
        records.sort(Comparator.comparingLong((Record r)->r.timestampMs).reversed());
        return Collections.unmodifiableList(records);
    }

    public static int countToday(Context context) {
        Calendar start=Calendar.getInstance();start.set(Calendar.HOUR_OF_DAY,0);start.set(Calendar.MINUTE,0);start.set(Calendar.SECOND,0);start.set(Calendar.MILLISECOND,0);
        int count=0;for(Record record:list(context))if(record.timestampMs>=start.getTimeInMillis())count++;return count;
    }

    /** Supprime en une fois les dossiers de rapports créés par l'application et leur index local. */
    public static synchronized int deleteAllReports(Context context){
        int deleted=0;
        if(Build.VERSION.SDK_INT>=29){Uri collection=MediaStore.Files.getContentUri("external");String selection=MediaStore.MediaColumns.RELATIVE_PATH+" LIKE ?";String[] args={Environment.DIRECTORY_DOCUMENTS+"/PolarH10Lab/%"};ArrayList<Uri> targets=new ArrayList<>();try(Cursor cursor=context.getContentResolver().query(collection,new String[]{MediaStore.MediaColumns._ID},selection,args,null)){if(cursor!=null)while(cursor.moveToNext())targets.add(ContentUris.withAppendedId(collection,cursor.getLong(0)));}catch(Exception error){throw new IllegalStateException("Impossible de lister les rapports",error);}for(Uri uri:targets){int count=context.getContentResolver().delete(uri,null,null);if(count<1)throw new IllegalStateException("Un fichier n’a pas pu être supprimé");deleted+=count;}}
        else{File base=context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);if(base!=null)deleted=deleteTree(new File(base,"PolarH10Monitor"));}
        LocalRepository.get(context).clear("event");context.getSharedPreferences(PREFS,0).edit().remove(KEY).apply();return deleted;
    }

    public static boolean deleteOne(Context context,String id){synchronized(EventStore.DATA_LOCK){synchronized(EventHistory.class){return deleteOneLocked(context,id);}}}
    private static boolean deleteOneLocked(Context context,String id){
        if(!id.matches("[A-Za-z0-9_-]+"))return false;
        try{
            if(Build.VERSION.SDK_INT>=29){Uri collection=MediaStore.Files.getContentUri("external");ArrayList<Uri> uris=new ArrayList<>();
                try(Cursor c=context.getContentResolver().query(collection,new String[]{MediaStore.MediaColumns._ID},MediaStore.MediaColumns.RELATIVE_PATH+"=?",new String[]{"Documents/PolarH10Lab/"+id+"/"},null)){if(c!=null)while(c.moveToNext())uris.add(ContentUris.withAppendedId(collection,c.getLong(0)));}
                for(Uri uri:uris)if(context.getContentResolver().delete(uri,null,null)<1)return false;
            }else{File parent=context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);if(parent==null)return false;File dir=new File(parent,"PolarH10Lab/"+id);deleteTree(dir);if(dir.exists())return false;}
            LocalRepository.get(context).delete("event",id);return true;
        }catch(Exception e){return false;}
    }
    private static int deleteTree(File file){if(file==null||!file.exists())return 0;int count=0;File[] children=file.listFiles();if(children!=null)for(File child:children)count+=deleteTree(child);if(file.delete())count++;else throw new IllegalStateException("Suppression impossible : "+file.getName());return count;}

    /** Retire uniquement les copies de partage IA créées par les anciennes versions. */
    public static int deleteLegacyAiFiles(Context context){if(Build.VERSION.SDK_INT<29)return 0;Uri collection=MediaStore.Files.getContentUri("external");String selection="("+MediaStore.MediaColumns.DISPLAY_NAME+"=? OR "+MediaStore.MediaColumns.DISPLAY_NAME+"=?) AND "+MediaStore.MediaColumns.RELATIVE_PATH+" LIKE ?";String[] args={"prompt_ia.txt","analyse_ecg.txt",Environment.DIRECTORY_DOCUMENTS+"/PolarH10Lab/%"};ArrayList<Uri> targets=new ArrayList<>();try(Cursor cursor=context.getContentResolver().query(collection,new String[]{MediaStore.MediaColumns._ID},selection,args,null)){if(cursor!=null)while(cursor.moveToNext())targets.add(ContentUris.withAppendedId(collection,cursor.getLong(0)));}catch(Exception ignored){}int deleted=0;for(Uri uri:targets)try{deleted+=Math.max(0,context.getContentResolver().delete(uri,null,null));}catch(Exception ignored){}return deleted;}

    public static Aggregate aggregate(Context context,int days){long cutoff=days<=0?0:System.currentTimeMillis()-days*86_400_000L;Aggregate a=new Aggregate();for(Record r:list(context)){if(r.timestampMs<cutoff)continue;a.total++;if("artifact".equals(r.review)){a.artifacts++;continue;}if("normal".equals(r.review))continue;if("ESV".equals(r.type)||"WIDE_PREMATURE".equals(r.type)||"PREMATURE".equals(r.type))a.premature++;else if("ESA".equals(r.type))a.esa++;else if("PAUSE".equals(r.type))a.pauses++;else if("IRREGULAR".equals(r.type))a.irregular++;else if("TACHY".equals(r.type)||"REGULAR_TACHY".equals(r.type))a.fast++;else if("BRADY".equals(r.type))a.slow++;if("artifact".equals(r.review))a.artifacts++;if("anomaly".equals(r.review))a.confirmed++;}return a;}

    public static final class Aggregate{public int total,premature,esa,pauses,irregular,fast,slow,artifacts,confirmed;}

    public static Uri findPdf(Context context,String eventId){return ReportFiles.find(context,eventId,"rapport.pdf");}

    private static void save(Context context,List<Record> source) {
        ArrayList<JSONObject> values=new ArrayList<>();for(Record r:source)values.add(r.toJson());LocalRepository.get(context).replace("event",values,"id","time");
    }

    public static final class Record {
        public final String id,type,title,detail;
        public final long timestampMs;
        public final int severity;
        public boolean ready;
        public String review;
        public final float[] morphology;
        public final double score,threshold;
        public final boolean modelReady;
        public final int bpm;
        public final double rmssd,sdnn,signalQuality;

        Record(String id,String type,String title,String detail,long timestampMs,int severity,boolean ready,String review,
               float[] morphology,double score,double threshold,boolean modelReady,int bpm,double rmssd,double sdnn,double signalQuality){
            this.id=id;this.type=type;this.title=title;this.detail=detail;this.timestampMs=timestampMs;this.severity=severity;
            this.ready=ready;this.review=review;this.morphology=morphology==null?null:morphology.clone();this.score=score;this.threshold=threshold;this.modelReady=modelReady;
            this.bpm=bpm;this.rmssd=rmssd;this.sdnn=sdnn;this.signalQuality=signalQuality;
        }

        JSONObject toJson(){
            JSONObject object=new JSONObject();
            try{
                object.put("id",id);object.put("type",type);object.put("title",title);object.put("detail",detail);object.put("time",timestampMs);
                object.put("severity",severity);object.put("ready",ready);object.put("review",review);object.put("score",score);object.put("threshold",threshold);object.put("model_ready",modelReady);
                object.put("bpm",bpm);object.put("rmssd",rmssd);object.put("sdnn",sdnn);object.put("signal_quality",signalQuality);
                if(morphology!=null){JSONArray values=new JSONArray();for(float value:morphology)values.put(value);object.put("morphology",values);}
            }catch(Exception ignored){}
            return object;
        }

        static Record fromJson(JSONObject object){
            JSONArray values=object.optJSONArray("morphology");float[] morphology=null;
            if(values!=null&&values.length()==MorphologyModel.DIMENSIONS){morphology=new float[values.length()];for(int i=0;i<values.length();i++)morphology[i]=(float)values.optDouble(i);}
            return new Record(object.optString("id"),object.optString("type"),object.optString("title"),object.optString("detail"),object.optLong("time"),
                    object.optInt("severity"),object.optBoolean("ready"),object.optString("review"),morphology,object.optDouble("score"),object.optDouble("threshold"),object.optBoolean("model_ready"),object.optInt("bpm"),object.optDouble("rmssd"),object.optDouble("sdnn"),object.optDouble("signal_quality"));
        }
    }
}
