package com.local.polarh10monitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Petit graphique local : FC de repos et VFC sont normalisées séparément pour montrer leurs tendances. */
public final class FitnessTrendView extends View {
    private final Paint grid=new Paint(1),hrPaint=new Paint(1),hrvPaint=new Paint(1),label=new Paint(1),legendHr=new Paint(1),legendHrv=new Paint(1),point=new Paint(1);
    private List<FitnessInsights.Morning> values=Collections.emptyList();
    public FitnessTrendView(Context context){super(context);grid.setColor(0xff223548);grid.setStrokeWidth(dp(1));hrPaint.setColor(0xffff667d);hrPaint.setStrokeWidth(dp(2.2f));hrPaint.setStyle(Paint.Style.STROKE);hrPaint.setStrokeCap(Paint.Cap.ROUND);hrPaint.setStrokeJoin(Paint.Join.ROUND);hrvPaint.setColor(0xff65a9ff);hrvPaint.setStrokeWidth(dp(2.2f));hrvPaint.setStyle(Paint.Style.STROKE);hrvPaint.setStrokeCap(Paint.Cap.ROUND);hrvPaint.setStrokeJoin(Paint.Join.ROUND);label.setTextSize(dp(10));label.setColor(0xff8fa1b3);legendHr.setColor(0xffff667d);legendHr.setTextSize(dp(10));legendHr.setFakeBoldText(true);legendHr.setStyle(Paint.Style.FILL);legendHrv.setColor(0xff65a9ff);legendHrv.setTextSize(dp(10));legendHrv.setFakeBoldText(true);legendHrv.setStyle(Paint.Style.FILL);point.setStyle(Paint.Style.FILL);}
    public void setValues(List<FitnessInsights.Morning> source){ArrayList<FitnessInsights.Morning>x=new ArrayList<>(source.subList(0,Math.min(30,source.size())));Collections.reverse(x);values=x;invalidate();}
    @Override protected void onDraw(Canvas c){super.onDraw(c);float left=dp(12),right=getWidth()-dp(12),top=dp(34),bottom=getHeight()-dp(18);drawLegend(c,left);for(int i=0;i<=3;i++){float y=top+(bottom-top)*i/3f;c.drawLine(left,y,right,y,grid);}if(values.isEmpty()){label.setTextAlign(Paint.Align.CENTER);label.setTextSize(dp(10));c.drawText("Pas encore de tendance",getWidth()/2f,(top+bottom)/2-dp(4),label);label.setTextSize(dp(9));c.drawText("Réalise deux bilans matinaux comparables",getWidth()/2f,(top+bottom)/2+dp(14),label);label.setTextAlign(Paint.Align.LEFT);return;}if(values.size()==1){float x=(left+right)/2f,y=(top+bottom)/2f;point.setColor(0xffff667d);c.drawCircle(x-dp(7),y,dp(4),point);point.setColor(0xff65a9ff);c.drawCircle(x+dp(7),y,dp(4),point);label.setTextAlign(Paint.Align.CENTER);c.drawText("Premier bilan enregistré",getWidth()/2f,bottom-dp(6),label);label.setTextAlign(Paint.Align.LEFT);return;}drawLine(c,true,left,right,top,bottom,hrPaint);drawLine(c,false,left,right,top,bottom,hrvPaint);}
    private void drawLegend(Canvas c,float left){point.setColor(0xffff667d);c.drawCircle(left+dp(4),dp(14),dp(4),point);c.drawText("FC au repos",left+dp(13),dp(18),legendHr);float second=left+dp(112);point.setColor(0xff65a9ff);c.drawCircle(second+dp(4),dp(14),dp(4),point);c.drawText("VFC RMSSD",second+dp(13),dp(18),legendHrv);}
    private void drawLine(Canvas c,boolean hr,float left,float right,float top,float bottom,Paint paint){double min=Double.MAX_VALUE,max=-Double.MAX_VALUE;for(FitnessInsights.Morning m:values){double v=hr?m.restingHr:m.rmssd;min=Math.min(min,v);max=Math.max(max,v);}double padding=Math.max(1,(max-min)*.12);min-=padding;max+=padding;Path path=new Path();for(int i=0;i<values.size();i++){double v=hr?values.get(i).restingHr:values.get(i).rmssd;float x=left+(right-left)*i/Math.max(1,values.size()-1);float y=bottom-(float)((v-min)/(max-min))*(bottom-top);if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}c.drawPath(path,paint);FitnessInsights.Morning last=values.get(values.size()-1);double lastValue=hr?last.restingHr:last.rmssd;float lastY=bottom-(float)((lastValue-min)/(max-min))*(bottom-top);point.setColor(paint.getColor());c.drawCircle(right,lastY,dp(3.2f),point);}
    private float dp(float v){return v*getResources().getDisplayMetrics().density;}
}
