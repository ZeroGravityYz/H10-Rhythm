package com.local.polarh10monitor;

import android.content.Context;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;

/** Immutable predictions scored only against later, verified observations and matching loads. */
public final class ForecastLedger {
    public static final class Score{public int count;public double mae,naive,recent,margin;public boolean ready;}
    public static void save(Context c,FitnessInsights.Morning from,double load,double prediction,double naive,double recent,double[] baseline){
        String id=String.valueOf(from.timestampMs);
        // A forecast is never overwritten after seeing its outcome.
        for(JSONObject o:LocalRepository.get(c).list("forecast"))if(id.equals(o.optString("id")))return;
        try{JSONObject o=new JSONObject();o.put("id",id);o.put("time",System.currentTimeMillis());o.put("from",from.timestampMs);o.put("load",load);o.put("prediction",prediction);o.put("naive",naive);o.put("recent",recent);
            for(int i=0;i<4;i++)o.put("b"+i,baseline[i]);LocalRepository.get(c).putIfAbsent("forecast",id,o.getLong("time"),o);
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    public static Score score(Context c){Score score=new Score();ArrayList<Double> errors=new ArrayList<>();java.util.List<FitnessInsights.Morning> mornings=FitnessInsights.mornings(c);
        for(JSONObject o:LocalRepository.get(c).list("forecast")){
            if(errors.size()>=60)break;long from=o.optLong("from");
            FitnessInsights.Morning target=null;
            for(FitnessInsights.Morning m:mornings)if(m.continuityVerified&&m.quality>=80&&m.nnCount>=90&&m.rmssd>2&&m.timestampMs>o.optLong("time")&&m.timestampMs-from>=18*3600000L&&m.timestampMs-from<=42*3600000L&&(target==null||m.timestampMs<target.timestampMs))target=m;
            if(target==null)continue;double load=TrainingJournal.loadBetween(c,from,target.timestampMs);
            if(!Double.isFinite(load)||Math.abs(load-o.optDouble("load"))>Math.max(5,.2*load))continue;
            double y=.58*(Math.log(target.rmssd)-o.optDouble("b2"))/o.optDouble("b3")-.42*(target.restingHr-o.optDouble("b0"))/o.optDouble("b1");y=Math.max(-3,Math.min(3,y));
            double error=Math.abs(y-o.optDouble("prediction"));errors.add(error);score.mae+=error;score.naive+=Math.abs(y-o.optDouble("naive"));score.recent+=Math.abs(y-o.optDouble("recent"));
        }
        score.count=errors.size();if(score.count>0){score.mae/=score.count;score.naive/=score.count;score.recent/=score.count;Collections.sort(errors);score.margin=errors.get(Math.min(errors.size()-1,(int)Math.ceil(.9*(errors.size()+1))-1));}
        score.ready=ForecastPolicy.ready(score.count,score.mae,score.naive,score.recent);
        return score;
    }
}
