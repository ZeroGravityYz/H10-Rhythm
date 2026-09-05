package com.local.polarh10monitor;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.Toast;
import java.util.*;

/** Separate units and real calendar spacing; never interpolate missing days. */
public final class FitnessTrendView extends View {
    private final Paint p=new Paint(3);private List<FitnessInsights.Morning> all=Collections.emptyList(),values=Collections.emptyList();private int days=30;
    public FitnessTrendView(Context context){super(context);setContentDescription("Tendances de fréquence au repos et de VFC. Touche un point pour ses valeurs.");}
    public void setValues(List<FitnessInsights.Morning> source){all=new ArrayList<>(source);filter();}
    public void setDays(int days){this.days=days;filter();}
    private void filter(){ArrayList<FitnessInsights.Morning> list=new ArrayList<>();long cutoff=System.currentTimeMillis()-days*86400000L;for(FitnessInsights.Morning m:all)if(m.timestampMs>=cutoff&&m.restingHr>0&&m.rmssd>0)list.add(m);list.sort(Comparator.comparingLong(m->m.timestampMs));values=list;invalidate();}
    @Override protected void onDraw(Canvas c){super.onDraw(c);p.setStyle(Paint.Style.FILL);p.setColor(0xffacbfce);p.setTextSize(sp(12));
        if(values.isEmpty()){c.drawText("Pas de bilan sur ces "+days+" jours.",dp(8),dp(45),p);c.drawText("Les tendances apparaîtront après tes mesures.",dp(8),dp(68),p);return;}
        panel(c,true,dp(24),getHeight()/2f-dp(27),0xffff7589);panel(c,false,getHeight()/2f+dp(24),getHeight()-dp(30),0xff79b4fa);
        p.setTextSize(sp(10));p.setColor(0xff9bafbf);String first=date(values.get(0).timestampMs),last=date(values.get(values.size()-1).timestampMs);c.drawText(first,dp(48),getHeight()-dp(8),p);c.drawText(last,getWidth()-p.measureText(last)-dp(10),getHeight()-dp(8),p);
    }
    private void panel(Canvas c,boolean hr,float top,float bottom,int color){float left=dp(48),right=getWidth()-dp(12);p.setStyle(Paint.Style.FILL);p.setColor(color);p.setTextSize(sp(12));c.drawText(hr?"FC au repos · bpm":"VFC RMSSD · ms",dp(8),top-dp(7),p);
        double min=Double.MAX_VALUE,max=-Double.MAX_VALUE;for(FitnessInsights.Morning m:values){double v=hr?m.restingHr:m.rmssd;min=Math.min(min,v);max=Math.max(max,v);}double pad=Math.max(2,(max-min)*.18);min=Math.max(0,min-pad);max+=pad;
        p.setTextSize(sp(10));for(int k=0;k<3;k++){float y=top+(bottom-top)*k/2;p.setColor(0xff273e50);c.drawLine(left,y,right,y,p);p.setColor(0xffa1b4c5);c.drawText(String.format(Locale.FRANCE,"%.0f",max-(max-min)*k/2),dp(5),y+dp(4),p);}
        long t0=values.get(0).timestampMs,t1=values.get(values.size()-1).timestampMs;Path line=new Path();for(int i=0;i<values.size();i++){FitnessInsights.Morning m=values.get(i);float x=t1==t0?(left+right)/2:left+(right-left)*(m.timestampMs-t0)/(float)(t1-t0);float y=bottom-(float)(((hr?m.restingHr:m.rmssd)-min)/(max-min))*(bottom-top);if(i==0||m.timestampMs-values.get(i-1).timestampMs>42*3600000L)line.moveTo(x,y);else line.lineTo(x,y);p.setStyle(Paint.Style.FILL);p.setColor(color);c.drawCircle(x,y,dp(3),p);}p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2));p.setColor(color);c.drawPath(line,p);p.setStyle(Paint.Style.FILL);
    }
    private static String date(long t){return new java.text.SimpleDateFormat("dd MMM",Locale.FRANCE).format(new Date(t));}
    @Override public boolean onTouchEvent(MotionEvent e){if(e.getAction()==MotionEvent.ACTION_UP){performClick();if(!values.isEmpty()){long first=values.get(0).timestampMs,last=values.get(values.size()-1).timestampMs;long target=first+(long)((last-first)*Math.max(0,Math.min(1,(e.getX()-dp(48))/Math.max(1,getWidth()-dp(60)))));FitnessInsights.Morning best=values.get(0);for(FitnessInsights.Morning m:values)if(Math.abs(m.timestampMs-target)<Math.abs(best.timestampMs-target))best=m;Toast.makeText(getContext(),date(best.timestampMs)+" · "+Math.round(best.restingHr)+" bpm · RMSSD "+Math.round(best.rmssd)+" ms",Toast.LENGTH_LONG).show();}return true;}return true;}
    @Override public boolean performClick(){super.performClick();return true;}
    private float dp(float v){return v*getResources().getDisplayMetrics().density;}private float sp(float v){return v*getResources().getDisplayMetrics().scaledDensity;}
}
