package com.local.polarh10monitor;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/** Raw-event reader. Samples are never recomputed or smoothed for replay/export. */
public final class ReplayView extends View {
    private final ArrayList<Integer> uv=new ArrayList<>();private final ArrayList<Long> time=new ArrayList<>();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private int offset;private float speed=25,lastX;private long eventTime;private SeekBar position;
    private ReplayView(Context c){super(c);setBackgroundColor(0xfffffafa);}
    public static void show(Activity a,EventHistory.Record record){
        LinearLayout body=new LinearLayout(a);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(16,12,16,12);
        TextView status=new TextView(a);status.setText("Chargement du signal brut…");status.setTextColor(0xffdeebf5);body.addView(status);
        ReplayView plot=new ReplayView(a);plot.eventTime=record.timestampMs;body.addView(plot,new LinearLayout.LayoutParams(-1,Math.round(280*a.getResources().getDisplayMetrics().density)));
        SeekBar seek=new SeekBar(a);plot.position=seek;body.addView(seek);seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int p,boolean user){plot.offset=p;plot.invalidate();}});
        LinearLayout actions=new LinearLayout(a);for(int speed:new int[]{25,50,100}){Button b=new Button(a);b.setText(speed+" mm/s");b.setAllCaps(false);actions.addView(b,new LinearLayout.LayoutParams(0,-2,1));b.setOnClickListener(v->{plot.speed=speed;plot.invalidate();});}body.addView(actions);
        Button share=new Button(a);share.setText("Partager le signal brut (.jsonl)");share.setAllCaps(false);body.addView(share);share.setOnClickListener(v->{Uri raw=ReportFiles.find(a,record.id,"ecg_brut.jsonl");if(raw==null){Toast.makeText(a,"Signal indisponible",Toast.LENGTH_LONG).show();return;}new AlertDialog.Builder(a).setTitle("Partager tes données ECG ?").setMessage("Le fichier contient tes amplitudes ECG et leurs horodatages. Il quittera le téléphone uniquement si tu choisis un destinataire.").setNegativeButton("Annuler",null).setPositiveButton("Choisir",(d,w)->{Intent intent=new Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM,raw).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);a.startActivity(Intent.createChooser(intent,"Partager le signal brut"));}).show();});
        ScrollView scroll=new ScrollView(a);scroll.addView(body);AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Relire le passage").setView(scroll).setPositiveButton("Fermer",null).create();dialog.show();
        new Thread(()->{try{Uri raw=ReportFiles.find(a,record.id,"ecg_brut.jsonl");if(raw==null)throw new IOException("Signal brut non disponible");ArrayList<Integer> samples=new ArrayList<>();ArrayList<Long> times=new ArrayList<>();
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(a.getContentResolver().openInputStream(raw),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null){JSONObject row=new JSONObject(line);if(row.has("ecg")){samples.add(row.getInt("ecg"));times.add(row.getLong("timestamp"));}if(samples.size()>EcgEngine.FS*600)throw new IOException("Fichier trop long");}}
            a.runOnUiThread(()->{if(a.isDestroyed()||!dialog.isShowing())return;plot.uv.addAll(samples);plot.time.addAll(times);seek.setMax(Math.max(0,samples.size()-2));int at=0;while(at<times.size()&&times.get(at)<record.timestampMs-2000)at++;seek.setProgress(Math.min(at,seek.getMax()));status.setText(samples.size()+" échantillons · 130 Hz\nGlisse le tracé ou utilise le curseur. Échelle écran indicative ; le PDF est calibré à l’impression.");plot.invalidate();});
        }catch(Exception e){a.runOnUiThread(()->status.setText("Lecture impossible : "+e.getMessage()));}},"replay-raw").start();
    }
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float mm=getResources().getDisplayMetrics().xdpi/25.4f,mmY=getResources().getDisplayMetrics().ydpi/25.4f,w=getWidth(),h=getHeight(),dx=speed*mm/EcgEngine.FS;
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1);for(int i=0;i*mm<w;i++){paint.setColor(i%5==0?0x55c62843:0x22c62843);canvas.drawLine(i*mm,0,i*mm,h,paint);}for(int i=0;i*mmY<h;i++){paint.setColor(i%5==0?0x55c62843:0x22c62843);canvas.drawLine(0,i*mmY,w,i*mmY,paint);}
        if(uv.size()<2)return;int from=Math.min(offset,uv.size()-2),end=Math.min(uv.size(),from+(int)(w/dx)+2);ArrayList<Integer> center=new ArrayList<>(uv.subList(from,end));Collections.sort(center);int baseline=center.get(center.size()/2);Path p=new Path();for(int i=from;i<end;i++){float x=(i-from)*dx,y=h/2-(uv.get(i)-baseline)*10*mmY/1000;if(i==from||time.get(i)-time.get(i-1)>20)p.moveTo(x,y);else p.lineTo(x,y);}paint.setColor(0xff15212d);paint.setStrokeWidth(1.7f);canvas.drawPath(p,paint);paint.setColor(0xffb52340);for(int i=from;i<end;i++)if(Math.abs(time.get(i)-eventTime)<5)canvas.drawLine((i-from)*dx,0,(i-from)*dx,h,paint);
        paint.setStyle(Paint.Style.FILL);paint.setTextSize(12*getResources().getDisplayMetrics().scaledDensity);paint.setColor(0xff334455);canvas.drawText(new SimpleDateFormat("HH:mm:ss.SSS",Locale.FRANCE).format(new Date(time.get(from)))+" · brut recentré",8,h-12,paint);
    }
    @Override public boolean onTouchEvent(MotionEvent e){if(e.getAction()==MotionEvent.ACTION_DOWN){lastX=e.getX();getParent().requestDisallowInterceptTouchEvent(true);return true;}if(e.getAction()==MotionEvent.ACTION_MOVE){float dx=speed*getResources().getDisplayMetrics().xdpi/25.4f/EcgEngine.FS;int delta=Math.round((lastX-e.getX())/dx);if(delta!=0){position.setProgress(Math.max(0,Math.min(position.getMax(),offset+delta)));lastX=e.getX();}return true;}if(e.getAction()==MotionEvent.ACTION_UP){performClick();return true;}return true;}
    @Override public boolean performClick(){super.performClick();return true;}
}
