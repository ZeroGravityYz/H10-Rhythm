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
        ArrayList<Record> records=new ArrayList<>(list(context));
        for(Record record:records)if(record.id.equals(id))return;
        records.add(0,new Record(id,event.type,event.title,event.detail,event.timestampMs,event.severity,false,"",event.morphology,
                event.morphologyScore,event.morphologyThreshold,event.personalModelReady,event.bpm,event.rmssdMs,event.sdnnMs,event.signalQualityPercent));
        save(context,records);
    }

    public static synchronized void markReady(Context context,String id) {
        ArrayList<Record> records=new ArrayList<>(list(context));
        for(Record record:records)if(record.id.equals(id))record.ready=true;
        save(context,records);
    }

    public static synchronized void review(Context context,String id,String review) {
        ArrayList<Record> records=new ArrayList<>(list(context));
        for(Record record:records)if(record.id.equals(id))record.review=review;
        save(context,records);
    }

    public static synchronized List<Record> list(Context context) {
        ArrayList<Record> records=new ArrayList<>();
        try {
            String encoded=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY,"[]");
            JSONArray array=new JSONArray(encoded);
            for(int i=0;i<array.length();i++)records.add(Record.fromJson(array.getJSONObject(i)));
        } catch(Exception ignored) {}
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
        if(Build.VERSION.SDK_INT>=29){Uri collection=MediaStore.Files.getContentUri("external");String selection=MediaStore.MediaColumns.RELATIVE_PATH+" LIKE ?";String[] args={Environment.DIRECTORY_DOCUMENTS+"/PolarH10Monitor/%"};ArrayList<Uri> targets=new ArrayList<>();try(Cursor cursor=context.getContentResolver().query(collection,new String[]{MediaStore.MediaColumns._ID},selection,args,null)){if(cursor!=null)while(cursor.moveToNext())targets.add(ContentUris.withAppendedId(collection,cursor.getLong(0)));}catch(Exception ignored){}for(Uri uri:targets)try{deleted+=Math.max(0,context.getContentResolver().delete(uri,null,null));}catch(Exception ignored){}}
        else{File base=context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);if(base!=null)deleted=deleteTree(new File(base,"PolarH10Monitor"));}
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().remove(KEY).apply();return deleted;
    }

    private static int deleteTree(File file){if(file==null||!file.exists())return 0;int count=0;File[] children=file.listFiles();if(children!=null)for(File child:children)count+=deleteTree(child);if(file.delete())count++;return count;}

    /** Retire uniquement les copies de partage IA créées par les anciennes versions. */
    public static int deleteLegacyAiFiles(Context context){if(Build.VERSION.SDK_INT<29)return 0;Uri collection=MediaStore.Files.getContentUri("external");String selection="("+MediaStore.MediaColumns.DISPLAY_NAME+"=? OR "+MediaStore.MediaColumns.DISPLAY_NAME+"=?) AND "+MediaStore.MediaColumns.RELATIVE_PATH+" LIKE ?";String[] args={"prompt_ia.txt","analyse_ecg.txt",Environment.DIRECTORY_DOCUMENTS+"/PolarH10Monitor/%"};ArrayList<Uri> targets=new ArrayList<>();try(Cursor cursor=context.getContentResolver().query(collection,new String[]{MediaStore.MediaColumns._ID},selection,args,null)){if(cursor!=null)while(cursor.moveToNext())targets.add(ContentUris.withAppendedId(collection,cursor.getLong(0)));}catch(Exception ignored){}int deleted=0;for(Uri uri:targets)try{deleted+=Math.max(0,context.getContentResolver().delete(uri,null,null));}catch(Exception ignored){}return deleted;}

    public static Aggregate aggregate(Context context,int days){long cutoff=days<=0?0:System.currentTimeMillis()-days*86_400_000L;Aggregate a=new Aggregate();for(Record r:list(context)){if(r.timestampMs<cutoff)continue;a.total++;if("ESV".equals(r.type)||"WIDE_PREMATURE".equals(r.type)||"PREMATURE".equals(r.type))a.premature++;else if("ESA".equals(r.type))a.esa++;else if("PAUSE".equals(r.type))a.pauses++;else if("IRREGULAR".equals(r.type))a.irregular++;else if("TACHY".equals(r.type)||"REGULAR_TACHY".equals(r.type))a.fast++;else if("BRADY".equals(r.type))a.slow++;if("artifact".equals(r.review))a.artifacts++;if("anomaly".equals(r.review))a.confirmed++;}return a;}

    public static final class Aggregate{public int total,premature,esa,pauses,irregular,fast,slow,artifacts,confirmed;}

    public static Uri findPdf(Context context,String eventId) {
        Uri collection=MediaStore.Files.getContentUri("external");
        String relative="Documents/PolarH10Monitor/"+eventId+"/";
        String selection=MediaStore.MediaColumns.DISPLAY_NAME+"=? AND "+MediaStore.MediaColumns.RELATIVE_PATH+"=?";
        try(Cursor cursor=context.getContentResolver().query(collection,new String[]{MediaStore.MediaColumns._ID},selection,new String[]{"rapport.pdf",relative},null)){
            if(cursor!=null&&cursor.moveToFirst())return ContentUris.withAppendedId(collection,cursor.getLong(0));
        }catch(Exception ignored){}
        return null;
    }

    private static void save(Context context,List<Record> source) {
        JSONArray array=new JSONArray();int count=0;
        for(Record record:source){if(count++>=LIMIT)break;array.put(record.toJson());}
        SharedPreferences.Editor editor=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit();editor.putString(KEY,array.toString()).apply();
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
