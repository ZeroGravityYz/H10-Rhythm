package com.local.polarh10monitor;

import android.content.Context;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Declared activities are distinct from sensor wear and missing activity data. */
public final class TrainingJournal {
    public static String day(long time){return new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT).format(new Date(time));}
    public static void add(Context c,String id,long time,String sport,int minutes,int rpe,boolean completed){
        if(minutes<1||minutes>1440||rpe<1||rpe>10)throw new IllegalArgumentException("Durée ou effort incorrect");
        try{JSONObject o=new JSONObject();o.put("id",id);o.put("time",time);o.put("sport",sport);o.put("minutes",minutes);o.put("rpe",rpe);o.put("completed",completed);o.put("load",minutes*rpe/5.0);LocalRepository.get(c).put("workout",id,time,o);}catch(org.json.JSONException e){throw new IllegalStateException(e);}
    }
    public static void completeDay(Context c,long time){try{JSONObject o=new JSONObject();o.put("time",time);o.put("day",day(time));LocalRepository.get(c).put("activityDay",day(time),time,o);}catch(Exception e){throw new IllegalStateException(e);}}
    public static boolean isComplete(Context c,long time){for(JSONObject o:LocalRepository.get(c).list("activityDay"))if(day(time).equals(o.optString("day")))return true;return false;}
    public static double loadBetween(Context c,long start,long end){
        if(!isComplete(c,start))return Double.NaN;double sum=0;
        for(JSONObject o:LocalRepository.get(c).list("workout")){long t=o.optLong("time");if(t>=start&&t<end)sum+=o.optDouble("load",0);}
        return sum;
    }
    public static void clear(Context c){for(String kind:new String[]{"workout","activityDay","forecast"})LocalRepository.get(c).clear(kind);}
}
