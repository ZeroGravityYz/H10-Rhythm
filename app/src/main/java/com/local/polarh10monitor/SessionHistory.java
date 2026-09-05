package com.local.polarh10monitor;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Résumés de sessions locaux pour le tableau de bord. Aucun ECG brut n'est copié ici. */
public final class SessionHistory {
    private static final String PREFS="session_history",KEY="sessions_v1";
    private static final int LIMIT=365;
    private SessionHistory(){}

    public static synchronized void add(Context context,Record record){
        if(record==null||record.startMs<=0||record.endMs-record.startMs<30_000||record.avgBpm<=0)return;
        LocalRepository.get(context).put("session",String.valueOf(record.startMs),record.startMs,record.json());
    }

    public static synchronized List<Record> list(Context context){
        ArrayList<Record> out=new ArrayList<>();for(JSONObject o:LocalRepository.get(context).list("session"))out.add(Record.from(o));
        out.sort(Comparator.comparingLong((Record r)->r.startMs).reversed());return Collections.unmodifiableList(out);
    }

    public static Aggregate aggregate(Context context,int days){
        long cutoff=days<=0?0:System.currentTimeMillis()-days*86_400_000L;Aggregate a=new Aggregate();double weightedHr=0,weightedQuality=0,rmssd=0,sdnn=0;int hrvCount=0;long hrDuration=0;
        for(Record r:list(context)){if(r.endMs<cutoff)continue;long duration=Math.max(1,r.endMs-r.startMs);a.sessions++;a.durationMs+=duration;a.moderateMs+=r.moderateMs;a.vigorousMs+=r.vigorousMs;a.events+=r.events;a.maxBpm=Math.max(a.maxBpm,r.maxBpm);if(r.minBpm>0)a.minBpm=a.minBpm==0?r.minBpm:Math.min(a.minBpm,r.minBpm);if(r.avgBpm>0){weightedHr+=r.avgBpm*duration;hrDuration+=duration;}weightedQuality+=r.signalQuality*duration;if(r.rmssd>0){rmssd+=r.rmssd;sdnn+=r.sdnn;hrvCount++;}}
        if(a.durationMs>0){a.avgBpm=hrDuration==0?0:weightedHr/hrDuration;a.signalQuality=weightedQuality/a.durationMs;}if(hrvCount>0){a.rmssd=rmssd/hrvCount;a.sdnn=sdnn/hrvCount;}return a;
    }

    public static synchronized void clear(Context context){LocalRepository.get(context).clear("session");context.getSharedPreferences("session_history",0).edit().remove("sessions_v1").apply();LocalRepository.get(context).clear("forecast");}

    public static final class Aggregate{public int sessions,events,minBpm,maxBpm;public long durationMs,moderateMs,vigorousMs;public double avgBpm,signalQuality,rmssd,sdnn;}

    public static final class Record{
        public final long startMs,endMs,moderateMs,vigorousMs;public final int avgBpm,minBpm,maxBpm,events;public final double rmssd,sdnn,signalQuality;
        public Record(long startMs,long endMs,int avgBpm,int minBpm,int maxBpm,int events,double rmssd,double sdnn,double signalQuality){this(startMs,endMs,avgBpm,minBpm,maxBpm,events,rmssd,sdnn,signalQuality,0,0);}
        public Record(long startMs,long endMs,int avgBpm,int minBpm,int maxBpm,int events,double rmssd,double sdnn,double signalQuality,long moderateMs,long vigorousMs){this.startMs=startMs;this.endMs=endMs;this.avgBpm=avgBpm;this.minBpm=minBpm;this.maxBpm=maxBpm;this.events=events;this.rmssd=rmssd;this.sdnn=sdnn;this.signalQuality=signalQuality;this.moderateMs=moderateMs;this.vigorousMs=vigorousMs;}
        JSONObject json(){JSONObject o=new JSONObject();try{o.put("start",startMs);o.put("end",endMs);o.put("avg",avgBpm);o.put("min",minBpm);o.put("max",maxBpm);o.put("events",events);o.put("rmssd",rmssd);o.put("sdnn",sdnn);o.put("quality",signalQuality);o.put("moderateMs",moderateMs);o.put("vigorousMs",vigorousMs);}catch(Exception ignored){}return o;}
        static Record from(JSONObject o){return new Record(o.optLong("start"),o.optLong("end"),o.optInt("avg"),o.optInt("min"),o.optInt("max"),o.optInt("events"),o.optDouble("rmssd"),o.optDouble("sdnn"),o.optDouble("quality"),o.optLong("moderateMs"),o.optLong("vigorousMs"));}
    }
}
