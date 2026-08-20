package com.local.polarh10monitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayDeque;

/** Canvas 60 FPS. Les paquets BLE sont mis en file puis révélés à leur cadence réelle de 130 Hz. */
public final class EcgCanvasView extends View {
    private static final int CAPACITY=EcgEngine.FS*6;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tracePath=new Path();
    private final float[] displayed=new float[CAPACITY];
    private final ArrayDeque<Float> pending=new ArrayDeque<>(EcgEngine.FS*2);
    private int displayPos,displayCount;private long receivedTotal=-1,lastFrameNs;private double sampleBudget;private boolean animating;

    public EcgCanvasView(Context c){super(c);init();}
    public EcgCanvasView(Context c,AttributeSet a){super(c,a);init();}
    private void init(){setBackgroundColor(0xfffffafa);}

    public void setSamples(float[] source,long totalSamples){
        if(source==null)return;
        long delta=receivedTotal<0?source.length:totalSamples-receivedTotal;
        if(receivedTotal<0||delta<0||delta>source.length){
            pending.clear();displayPos=displayCount=0;int delay=Math.min(source.length,EcgEngine.FS/2),direct=source.length-delay;
            for(int i=0;i<direct;i++)appendDisplayed(source[i]);for(int i=direct;i<source.length;i++)pending.addLast(source[i]);
        }else if(delta>0){
            int add=(int)Math.min(delta,source.length),from=source.length-add;for(int i=from;i<source.length;i++)pending.addLast(source[i]);
        }
        receivedTotal=totalSamples;
        while(pending.size()>EcgEngine.FS*2){Float v=pending.pollFirst();if(v!=null)appendDisplayed(v);}
        if(!animating&&isAttachedToWindow())startAnimation();
    }

    private void appendDisplayed(float value){displayed[displayPos]=value;displayPos=(displayPos+1)%displayed.length;if(displayCount<displayed.length)displayCount++;}
    private void startAnimation(){animating=true;lastFrameNs=System.nanoTime();postOnAnimation(frame);}
    private final Runnable frame=new Runnable(){@Override public void run(){if(!animating)return;long now=System.nanoTime();double dt=Math.min(.050,Math.max(0,(now-lastFrameNs)/1_000_000_000.0));lastFrameNs=now;double rate=EcgEngine.FS*(pending.size()>EcgEngine.FS?1.06:1.0);sampleBudget+=dt*rate;int take=Math.min((int)sampleBudget,pending.size());for(int i=0;i<take;i++){Float v=pending.pollFirst();if(v!=null)appendDisplayed(v);}sampleBudget-=take;postInvalidateOnAnimation();postOnAnimation(this);}};
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();if(!animating)startAnimation();}
    @Override protected void onDetachedFromWindow(){animating=false;removeCallbacks(frame);super.onDetachedFromWindow();}

    @Override protected void onDraw(Canvas c){
        super.onDraw(c);float w=getWidth(),h=getHeight(),xdpi=getResources().getDisplayMetrics().xdpi,ydpi=getResources().getDisplayMetrics().ydpi,mmX=xdpi/25.4f,mmY=ydpi/25.4f;
        for(float x=0;x<=w;x+=mmX){int k=Math.round(x/mmX);paint.setColor(k%5==0?0x55c62843:0x22c62843);paint.setStrokeWidth(k%5==0?1.4f:.7f);c.drawLine(x,0,x,h,paint);}
        for(float y=h/2%mmY;y<=h;y+=mmY){int k=Math.round((y-h/2)/mmY);paint.setColor(k%5==0?0x55c62843:0x22c62843);paint.setStrokeWidth(k%5==0?1.4f:.7f);c.drawLine(0,y,getWidth(),y,paint);}
        if(displayCount>=2){float pxPerSample=25f*mmX/EcgEngine.FS,uvToPx=10f*mmY/1000f;int visible=Math.min(displayCount,(int)Math.ceil(w/pxPerSample)+1),start=Math.floorMod(displayPos-visible,displayed.length);tracePath.reset();for(int j=0;j<visible;j++){float x=w-(visible-1-j)*pxPerSample,y=h/2-displayed[(start+j)%displayed.length]*uvToPx;if(j==0)tracePath.moveTo(x,y);else tracePath.lineTo(x,y);}paint.setColor(0xff101820);paint.setStrokeWidth(Math.max(1.8f,getResources().getDisplayMetrics().density));paint.setStyle(Paint.Style.STROKE);paint.setStrokeJoin(Paint.Join.ROUND);c.drawPath(tracePath,paint);paint.setStyle(Paint.Style.FILL);}
        paint.setTextSize(10*getResources().getDisplayMetrics().scaledDensity);paint.setColor(0xff596574);c.drawText("25 mm/s • 10 mm/mV • rendu 60 FPS",10,h-10,paint);
    }
}
