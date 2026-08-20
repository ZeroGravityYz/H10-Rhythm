package com.local.polarh10monitor;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tampon 5 minutes, historique binaire glissant 24 h et dossiers d'événements exportés. */
public final class EventStore {
    public interface Listener { void onReportReady(String name, String location); void onStoreError(String message); }
    private static final int FS=EcgEngine.FS, PRE=60*FS, POST=120*FS, RING=5*60*FS;
    private final Context context; private final Listener listener;
    private final int[] ringUv=new int[RING]; private final long[] ringMs=new long[RING]; private int ringPos,ringCount;
    private final ArrayList<Capture> captures=new ArrayList<>();
    private final ExecutorService writer=Executors.newSingleThreadExecutor();
    private final File continuousDir; private DataOutputStream chunk; private int chunkSamples; private long chunkStart;

    private static final class Capture {
        final EcgEngine.DetectionEvent event; final String id; final ArrayList<Integer> uv=new ArrayList<>(); final ArrayList<Long> ms=new ArrayList<>();
        Capture(EcgEngine.DetectionEvent event,String id){this.event=event;this.id=id;}
    }

    public EventStore(Context context,Listener listener){this.context=context.getApplicationContext();this.listener=listener;continuousDir=new File(context.getFilesDir(),"continuous");continuousDir.mkdirs();}

    public synchronized void startSession(){closeChunk();ringPos=ringCount=0;captures.clear();openChunk(System.currentTimeMillis());pruneOldChunks();}
    public synchronized void stopSession(){closeChunk();ArrayList<Capture> pending=new ArrayList<>(captures);captures.clear();for(Capture c:pending)finalizeAsync(c,true);}

    public synchronized void onRawSample(int uv,long timeMs){
        ringUv[ringPos]=uv;ringMs[ringPos]=timeMs;ringPos=(ringPos+1)%RING;if(ringCount<RING)ringCount++;
        writeChunkSample(uv,timeMs);
        Iterator<Capture> it=captures.iterator();
        while(it.hasNext()){Capture c=it.next();c.uv.add(uv);c.ms.add(timeMs);if(c.uv.size()>=PRE+POST){it.remove();finalizeAsync(c,false);}}
    }

    public synchronized void beginEvent(EcgEngine.DetectionEvent event){
        for(Capture c:captures)if(Math.abs(c.event.timestampMs-event.timestampMs)<15_000&&c.event.type.equals(event.type))return;
        String id=fileTime(event.timestampMs)+"_"+safe(event.type);Capture c=new Capture(event,id);
        int n=Math.min(PRE,ringCount),start=Math.floorMod(ringPos-n,RING);
        for(int i=0;i<n;i++){int p=(start+i)%RING;c.uv.add(ringUv[p]);c.ms.add(ringMs[p]);}
        captures.add(c);EventHistory.add(context,id,event);
    }

    private void openChunk(long now){try{chunkStart=now;chunkSamples=0;File f=new File(continuousDir,fileTime(now)+".ecgbin");chunk=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f)));chunk.write("H10ECG1\n".getBytes(StandardCharsets.US_ASCII));writeLongLe(chunk,now);writeIntLe(chunk,FS);}catch(Exception e){storeError("Impossible d’ouvrir l’historique ECG : "+e.getMessage());chunk=null;}}
    private void writeChunkSample(int uv,long timeMs){try{if(chunk==null)openChunk(timeMs);if(chunk==null)return;writeShortLe(chunk,(short)Math.max(-32768,Math.min(32767,uv)));chunkSamples++;if(chunkSamples>=FS*300){closeChunk();openChunk(timeMs);pruneOldChunks();}}catch(Exception e){storeError("Écriture ECG interrompue : "+e.getMessage());closeChunk();}}
    private void closeChunk(){if(chunk!=null){try{chunk.flush();chunk.close();}catch(Exception ignored){}chunk=null;}}
    private void pruneOldChunks(){File[] files=continuousDir.listFiles();if(files==null)return;long cutoff=System.currentTimeMillis()-24L*3600_000L;for(File f:files)if(f.isFile()&&f.lastModified()<cutoff)f.delete();}

    private void finalizeAsync(Capture c,boolean incomplete){writer.execute(()->{try{exportJsonl(c,incomplete);exportMetadata(c,incomplete);exportPdf(c,incomplete);EventHistory.markReady(context,c.id);if(listener!=null)listener.onReportReady(c.id,"Documents/PolarH10Monitor/"+c.id);}catch(Exception e){storeError("Rapport "+c.id+" non créé : "+e.getMessage());}});}

    private void exportJsonl(Capture c,boolean incomplete)throws IOException{
        try(OutputStream out=createDocument(c.id,"ecg_brut.jsonl","application/x-ndjson")){
            String header="{\"schema\":\"polar-h10-event-v1\",\"sample_rate_hz\":"+FS+",\"unit\":\"uV\",\"event_type\":\""+json(c.event.type)+"\",\"event_time_ms\":"+c.event.timestampMs+",\"incomplete\":"+incomplete+",\"engine\":\""+EcgEngine.VERSION+"\"}\n";out.write(header.getBytes(StandardCharsets.UTF_8));
            for(int i=0;i<c.uv.size();i++){String line="{\"timestamp\":"+c.ms.get(i)+",\"ecg\":"+c.uv.get(i)+"}\n";out.write(line.getBytes(StandardCharsets.UTF_8));}
        }
    }

    private void exportMetadata(Capture c,boolean incomplete)throws IOException{
        String body="{\n  \"id\": \""+json(c.id)+"\",\n  \"type\": \""+json(c.event.type)+"\",\n  \"title\": \""+json(c.event.title)+"\",\n  \"detail\": \""+json(c.event.detail)+"\",\n  \"timestamp_ms\": "+c.event.timestampMs+",\n  \"severity\": "+c.event.severity+",\n  \"sample_rate_hz\": "+FS+",\n  \"samples\": "+c.uv.size()+",\n  \"capture_incomplete\": "+incomplete+",\n  \"engine\": \""+EcgEngine.VERSION+"\",\n  \"personal_model_ready\": "+c.event.personalModelReady+",\n  \"morphology_score\": "+String.format(Locale.ROOT,"%.6f",c.event.morphologyScore)+",\n  \"morphology_threshold\": "+String.format(Locale.ROOT,"%.6f",c.event.morphologyThreshold)+",\n  \"author\": \"Mattéo Leroy\",\n  \"disclaimer\": \"Dépistage expérimental mono-dérivation, relecture médicale requise.\"\n}\n";
        try(OutputStream out=createDocument(c.id,"evenement.json","application/json")){out.write(body.getBytes(StandardCharsets.UTF_8));}
    }

    private void exportPdf(Capture c,boolean incomplete)throws IOException{
        PdfDocument doc=new PdfDocument();Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);int w=595,h=842;
        PdfDocument.Page page=doc.startPage(new PdfDocument.PageInfo.Builder(w,h,1).create());Canvas canvas=page.getCanvas();canvas.drawColor(Color.WHITE);
        p.setColor(Color.rgb(20,28,38));p.setTextSize(21);p.setFakeBoldText(true);canvas.drawText("H10 Rhythm — événement à vérifier",36,48,p);
        p.setTextSize(15);p.setColor(Color.rgb(190,40,65));canvas.drawText(c.event.title,36,82,p);p.setFakeBoldText(false);p.setColor(Color.DKGRAY);p.setTextSize(10);
        canvas.drawText("Heure : "+humanTime(c.event.timestampMs),36,106,p);canvas.drawText("Moteur : "+EcgEngine.VERSION+" • 130 Hz • µV",36,122,p);
        drawWrapped(canvas,p,c.event.detail,36,146,w-72,13);
        p.setTextSize(9);p.setColor(Color.GRAY);drawWrapped(canvas,p,"Développé par Mattéo Leroy. Outil expérimental mono-dérivation : ce document ne constitue pas un diagnostic. Conserver ecg_brut.jsonl pour une éventuelle relecture.",36,194,w-72,12);
        int eventPos=nearestIndex(c.ms,c.event.timestampMs),start=Math.max(0,eventPos-10*FS),stripSamples=(int)(7.2*FS),y=270;
        for(int strip=0;strip<4;strip++){drawStrip(canvas,p,c,start+strip*stripSamples,stripSamples,36,y,w-72,116,eventPos);y+=136;}
        if(incomplete){p.setColor(Color.RED);p.setTextSize(10);canvas.drawText("Capture interrompue avant la fin des 120 secondes post-événement.",36,820,p);}doc.finishPage(page);
        try(OutputStream out=createDocument(c.id,"rapport.pdf","application/pdf")){doc.writeTo(out);}finally{doc.close();}
    }

    private void drawStrip(Canvas c,Paint p,Capture cap,int start,int count,float x,float y,float width,float height,int eventPos){
        p.setStyle(Paint.Style.STROKE);float secWidth=width/7.2f,small=secWidth/25f;
        for(float gx=x;gx<=x+width;gx+=small){int k=Math.round((gx-x)/small);p.setColor(k%5==0?0x55c62843:0x22c62843);p.setStrokeWidth(k%5==0?1f:.5f);c.drawLine(gx,y,gx,y+height,p);}
        for(float gy=y;gy<=y+height;gy+=small){int k=Math.round((gy-y)/small);p.setColor(k%5==0?0x55c62843:0x22c62843);p.setStrokeWidth(k%5==0?1f:.5f);c.drawLine(x,gy,x+width,gy,p);}
        p.setColor(Color.rgb(15,22,30));p.setStrokeWidth(1.2f);android.graphics.Path path=new android.graphics.Path();boolean begun=false;float uvToPt=28.346f/1000f;
        int end=Math.min(cap.uv.size(),start+count);for(int i=Math.max(0,start);i<end;i++){float px=x+(i-start)/(float)Math.max(1,count-1)*width,py=y+height/2-cap.uv.get(i)*uvToPt;if(!begun){path.moveTo(px,py);begun=true;}else path.lineTo(px,py);}c.drawPath(path,p);
        if(eventPos>=start&&eventPos<start+count){float ex=x+(eventPos-start)/(float)Math.max(1,count-1)*width;p.setColor(Color.RED);p.setStrokeWidth(1.5f);c.drawLine(ex,y,ex,y+height,p);}
        p.setStyle(Paint.Style.FILL);p.setColor(Color.DKGRAY);p.setTextSize(8);c.drawText(String.format(Locale.FRANCE,"%+.1f s",(start-eventPos)/(double)FS),x,y-3,p);
    }

    private OutputStream createDocument(String eventId,String name,String mime)throws IOException{
        if(Build.VERSION.SDK_INT>=29){ContentResolver cr=context.getContentResolver();ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.DISPLAY_NAME,name);v.put(MediaStore.MediaColumns.MIME_TYPE,mime);v.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOCUMENTS+"/PolarH10Monitor/"+eventId);Uri uri=cr.insert(MediaStore.Files.getContentUri("external"),v);if(uri==null)throw new IOException("MediaStore indisponible");OutputStream out=cr.openOutputStream(uri,"w");if(out==null)throw new IOException("Flux de fichier indisponible");return out;}
        File dir=new File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),"PolarH10Monitor/"+eventId);if(!dir.exists()&&!dir.mkdirs())throw new IOException("Dossier inaccessible");return new FileOutputStream(new File(dir,name));
    }

    private void storeError(String message){if(listener!=null)listener.onStoreError(message);}
    private static int nearestIndex(ArrayList<Long>x,long target){int best=0;long d=Long.MAX_VALUE;for(int i=0;i<x.size();i++){long n=Math.abs(x.get(i)-target);if(n<d){d=n;best=i;}}return best;}
    private static void drawWrapped(Canvas c,Paint p,String text,float x,float y,float width,float line){String[] words=text.split(" ");String row="";for(String word:words){String test=row.isEmpty()?word:row+" "+word;if(p.measureText(test)>width){c.drawText(row,x,y,p);y+=line;row=word;}else row=test;}if(!row.isEmpty())c.drawText(row,x,y,p);}
    private static String fileTime(long ms){return new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",Locale.ROOT).format(new Date(ms));}
    private static String humanTime(long ms){return new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS",Locale.FRANCE).format(new Date(ms));}
    private static String safe(String s){return s.replaceAll("[^A-Za-z0-9_-]","_");}
    private static String json(String s){return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n");}
    private static void writeShortLe(DataOutputStream o,short v)throws IOException{o.writeByte(v&255);o.writeByte((v>>>8)&255);}
    private static void writeIntLe(DataOutputStream o,int v)throws IOException{for(int i=0;i<4;i++)o.writeByte((v>>>(8*i))&255);}
    private static void writeLongLe(DataOutputStream o,long v)throws IOException{for(int i=0;i<8;i++)o.writeByte((int)(v>>>(8*i))&255);}
}
