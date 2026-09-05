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
    private static final int FS=EcgEngine.FS, PRE=60*FS, POST=30*FS, RING=5*60*FS;
    static final Object DATA_LOCK=new Object();
    private final Context context; private final Listener listener;
    private final int[] ringUv=new int[RING]; private final long[] ringMs=new long[RING]; private int ringPos,ringCount;
    private final ArrayList<Capture> captures=new ArrayList<>();
    private final ExecutorService writer=Executors.newSingleThreadExecutor();
    private final File continuousDir; private DataOutputStream chunk; private int chunkSamples; private long chunkStart,nextStorageRetry,lastErrorAt;

    private static final class Capture {
        final EcgEngine.DetectionEvent event; final String id;final long generation; final ArrayList<Integer> uv=new ArrayList<>(); final ArrayList<Long> ms=new ArrayList<>();
        long targetEndMs;boolean truncatedPre;
        Capture(EcgEngine.DetectionEvent event,String id,long generation){this.event=event;this.id=id;this.generation=generation;}
    }

    public EventStore(Context context,Listener listener){this.context=context.getApplicationContext();this.listener=listener;continuousDir=new File(context.getFilesDir(),"continuous");continuousDir.mkdirs();}

    /** Efface l'historique ECG glissant privé. À appeler uniquement lorsque la surveillance est arrêtée. */
    public static int deleteContinuousHistory(Context context){return deleteTree(new File(context.getFilesDir(),"continuous"));}

    /** Attend les écritures déjà engagées, invalide celles en attente puis efface toutes les traces historiques. */
    public static int deleteAllStoredData(Context context){synchronized(DATA_LOCK){long next=generation(context)+1;context.getSharedPreferences("storage_state",Context.MODE_PRIVATE).edit().putLong("generation",next).commit();int deleted=EventHistory.deleteAllReports(context);deleted+=deleteContinuousHistory(context);SessionHistory.clear(context);LocalRepository.get(context).clear("forecast");return deleted;}}

    public synchronized void markGap(){stopSession();ringPos=ringCount=0;}
    public void shutdown(){writer.shutdown();}
    public synchronized void startSession(){closeChunk();ringPos=ringCount=0;captures.clear();openChunk(System.currentTimeMillis());pruneOldChunks();}
    public synchronized void stopSession(){closeChunk();ArrayList<Capture> pending=new ArrayList<>(captures);captures.clear();for(Capture c:pending)finalizeAsync(c,true);}

    public synchronized void onRawSample(int uv,long timeMs){
        ringUv[ringPos]=uv;ringMs[ringPos]=timeMs;ringPos=(ringPos+1)%RING;if(ringCount<RING)ringCount++;
        writeChunkSample(uv,timeMs);
        Iterator<Capture> it=captures.iterator();
        while(it.hasNext()){Capture c=it.next();c.uv.add(uv);c.ms.add(timeMs);if(timeMs>=c.targetEndMs){it.remove();finalizeAsync(c,c.truncatedPre);}}
    }

    public synchronized void beginEvent(EcgEngine.DetectionEvent event){
        // Preserve different event types, even when their context windows overlap.
        for(Capture c:captures)if(c.event.type.equals(event.type)&&Math.abs(c.event.timestampMs-event.timestampMs)<2_000)return;
        String id=fileTime(event.timestampMs)+"_"+safe(event.type)+"_"+java.util.UUID.randomUUID().toString().substring(0,8);Capture c=new Capture(event,id,generation(context));
        int n=ringCount,start=Math.floorMod(ringPos-n,RING);long from=event.timestampMs-60_000;
        for(int i=0;i<n;i++){int p=(start+i)%RING;if(ringMs[p]>=from){c.uv.add(ringUv[p]);c.ms.add(ringMs[p]);}}
        c.truncatedPre=c.ms.isEmpty()||c.ms.get(0)-from>20;c.targetEndMs=event.timestampMs+30_000;
        captures.add(c);EventHistory.add(context,id,event);
    }

    private void openChunk(long now){if(System.currentTimeMillis()<nextStorageRetry)return;try{if(!continuousDir.exists()&&!continuousDir.mkdirs())throw new IOException("Dossier inaccessible");chunkStart=now;chunkSamples=0;File f=new File(continuousDir,fileTime(now)+"_"+java.util.UUID.randomUUID().toString().substring(0,8)+".ecgbin");chunk=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f)));chunk.write("H10ECG2\n".getBytes(StandardCharsets.US_ASCII));writeLongLe(chunk,now);writeIntLe(chunk,FS);}catch(Exception e){nextStorageRetry=System.currentTimeMillis()+30_000;storeError("Impossible d’ouvrir l’historique ECG : "+e.getMessage());chunk=null;}}
    private void writeChunkSample(int uv,long timeMs){try{if(chunk==null)openChunk(timeMs);if(chunk==null)return;writeLongLe(chunk,timeMs);writeIntLe(chunk,uv);chunkSamples++;if(chunkSamples>=FS*300){closeChunk();openChunk(timeMs);pruneOldChunks();}}catch(Exception e){nextStorageRetry=System.currentTimeMillis()+30_000;storeError("Écriture ECG interrompue : "+e.getMessage());closeChunk();}}
    private void closeChunk(){if(chunk!=null){try{chunk.flush();chunk.close();}catch(Exception ignored){}chunk=null;}}
    private void pruneOldChunks(){File[] files=continuousDir.listFiles();if(files==null)return;long cutoff=System.currentTimeMillis()-Math.max(24,Math.min(168,context.getSharedPreferences("settings",0).getInt("retentionHours",24)))*3600_000L;for(File f:files)if(f.isFile()&&f.lastModified()<cutoff)f.delete();}

    private void finalizeAsync(Capture c,boolean incomplete){writer.execute(()->{synchronized(DATA_LOCK){if(c.generation!=generation(context)||LocalRepository.get(context).find("event",c.id)==null)return;try{EventHistory.state(context,c.id,"writing","");exportJsonl(c,incomplete);exportMetadata(c,incomplete);exportPdf(c,incomplete);EventHistory.markReady(context,c.id);if(listener!=null)listener.onReportReady(c.id,"Documents/PolarH10Lab/"+c.id);}catch(Exception e){EventHistory.state(context,c.id,"failed",e.getMessage());storeError("Rapport "+c.id+" non créé : "+e.getMessage());}}});}

    /** Retry only from preserved raw samples, never from a fabricated waveform. Call off the UI thread. */
    public static String retryReport(Context context,String id){synchronized(DATA_LOCK){EventStore store=new EventStore(context,null);try{
        org.json.JSONObject payload=LocalRepository.get(context).find("event",id);if(payload==null)return "Ce passage a été supprimé.";
        Uri raw=ReportFiles.find(context,id,"ecg_brut.jsonl");if(raw==null)return "Le signal brut n’est pas disponible. Le rapport ne peut pas être reconstruit.";
        EventHistory.Record r=EventHistory.Record.fromJson(payload);EcgEngine.DetectionEvent event=new EcgEngine.DetectionEvent(r.type,r.title,r.detail,0,r.timestampMs,r.severity,r.morphology,r.score,r.threshold,r.modelReady,r.bpm,r.rmssd,r.sdnn,r.signalQuality);
        Capture cap=new Capture(event,id,generation(context));boolean incomplete=true;
        try(java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.InputStreamReader(context.getContentResolver().openInputStream(raw),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null){org.json.JSONObject row=new org.json.JSONObject(line);if(row.has("schema"))incomplete=row.optBoolean("incomplete",true);if(row.has("ecg")){cap.uv.add(row.getInt("ecg"));cap.ms.add(row.getLong("timestamp"));}if(cap.uv.size()>FS*600)throw new IOException("Capture trop longue");}}
        if(cap.uv.isEmpty())return "Aucun échantillon brut disponible.";
        EventHistory.state(context,id,"writing","");store.exportMetadata(cap,incomplete);store.exportPdf(cap,incomplete);EventHistory.markReady(context,id);return "Rapport reconstruit.";
    }catch(Exception e){EventHistory.state(context,id,"failed",e.getMessage());return "Création impossible : "+e.getMessage();}finally{store.shutdown();}}}

    private void exportJsonl(Capture c,boolean incomplete)throws IOException{
        try(OutputStream out=createDocument(c.id,"ecg_brut.jsonl","application/x-ndjson")){
            String header="{\"schema\":\"polar-h10-event-v1\",\"sample_rate_hz\":"+FS+",\"unit\":\"uV\",\"event_type\":\""+json(c.event.type)+"\",\"event_time_ms\":"+c.event.timestampMs+",\"incomplete\":"+incomplete+",\"engine\":\""+EcgEngine.VERSION+"\"}\n";out.write(header.getBytes(StandardCharsets.UTF_8));
            for(int i=0;i<c.uv.size();i++){String line="{\"timestamp\":"+c.ms.get(i)+",\"ecg\":"+c.uv.get(i)+"}\n";out.write(line.getBytes(StandardCharsets.UTF_8));}
        }
    }

    private void exportMetadata(Capture c,boolean incomplete)throws IOException{
        String body="{\n  \"id\": \""+json(c.id)+"\",\n  \"type\": \""+json(c.event.type)+"\",\n  \"title\": \""+json(c.event.title)+"\",\n  \"detail\": \""+json(c.event.detail)+"\",\n  \"timestamp_ms\": "+c.event.timestampMs+",\n  \"severity\": "+c.event.severity+",\n  \"sample_rate_hz\": "+FS+",\n  \"samples\": "+c.uv.size()+",\n  \"capture_incomplete\": "+incomplete+",\n  \"engine\": \""+EcgEngine.VERSION+"\",\n  \"heart_rate_bpm\": "+c.event.bpm+",\n  \"hrv_rmssd_ms\": "+String.format(Locale.ROOT,"%.2f",c.event.rmssdMs)+",\n  \"hrv_sdnn_ms\": "+String.format(Locale.ROOT,"%.2f",c.event.sdnnMs)+",\n  \"signal_quality_percent\": "+String.format(Locale.ROOT,"%.2f",c.event.signalQualityPercent)+",\n  \"personal_model_ready\": "+c.event.personalModelReady+",\n  \"morphology_score\": "+String.format(Locale.ROOT,"%.6f",c.event.morphologyScore)+",\n  \"morphology_threshold\": "+String.format(Locale.ROOT,"%.6f",c.event.morphologyThreshold)+",\n  \"author\": \"Mattéo Leroy\",\n  \"disclaimer\": \"Dépistage expérimental mono-dérivation, relecture médicale requise.\"\n}\n";
        try(OutputStream out=createDocument(c.id,"evenement.json","application/json")){out.write(body.getBytes(StandardCharsets.UTF_8));}
    }

    private void exportPdf(Capture cap,boolean incomplete)throws IOException{
        PdfDocument doc=new PdfDocument();Paint p=new Paint(3);int eventPos=nearestIndex(cap.ms,cap.event.timestampMs);
        final float left=36,width=523,pt=72f/25.4f;int count=(int)Math.floor(width/(25*pt)*FS);
        p.setTextSize(11);ArrayList<String> lines=new ArrayList<>();
        String metadata=cap.event.title+"\n\nHeure : "+humanTime(cap.event.timestampMs)+"\n"+FS+" Hz · ECG mono-dérivation\n\n"+cap.event.detail+
            "\n\n"+String.format(Locale.FRANCE,"FC %s · RMSSD %s · SDNN %s\nQualité estimée de session : %.0f %%",cap.event.bpm>0?cap.event.bpm+" bpm":"non disponible",metric(cap.event.rmssdMs),metric(cap.event.sdnnMs),cap.event.signalQualityPercent)+
            "\n\n"+(incomplete?"Capture incomplète : arrêt ou interruption du flux.":"Capture terminée.")+
            "\n\nLa ligne rouge repère le passage signalé. Le tracé est brut, recentré verticalement pour la lecture. Les amplitudes qui dépassent le cadre sont coupées à l’affichage, jamais dans le fichier brut."+
            "\n\nCes repérages sont expérimentaux. Une annotation utilisateur n’est pas une confirmation médicale. L’absence d’alerte n’exclut pas un problème. Les intervalles RR perturbés ne sont pas utilisés pour la VFC."+
            "\n\nConserver le JSONL avec ce PDF pour une relecture des données originales. Impression à 100 % : 25 mm/s et 10 mm/mV.";
        for(String paragraph:metadata.split("\n",-1)){if(paragraph.isEmpty()){lines.add("");continue;}while(!paragraph.isEmpty()){int n=p.breakText(paragraph,true,width,null);if(n<=0)n=1;lines.add(paragraph.substring(0,n));paragraph=paragraph.substring(n);}}
        int perPage=43,coverPages=Math.max(1,(lines.size()+perPage-1)/perPage),tracePages=Math.max(1,(int)Math.ceil(cap.uv.size()/(double)(count*5))),total=coverPages+tracePages;
        try{
            for(int pageNo=0;pageNo<total;pageNo++){
                PdfDocument.Page page=doc.startPage(new PdfDocument.PageInfo.Builder(595,842,pageNo+1).create());Canvas c=page.getCanvas();c.drawColor(Color.WHITE);
                p.setColor(Color.rgb(20,28,38));p.setTextSize(18);p.setFakeBoldText(true);c.drawText("H10 Rhythm · Passage enregistré",left,40,p);p.setFakeBoldText(false);
                if(pageNo<coverPages){p.setTextSize(11);for(int i=pageNo*perPage;i<Math.min(lines.size(),(pageNo+1)*perPage);i++)c.drawText(lines.get(i),left,80+(i%perPage)*16,p);}
                else{int trace=pageNo-coverPages;p.setTextSize(10);c.drawText(humanTime(cap.event.timestampMs)+" · Données brutes · "+FS+" Hz",left,65,p);
                    for(int strip=0;strip<5;strip++){int start=(trace*5+strip)*count;if(start>=cap.uv.size())break;drawStrip(c,p,cap,start,count,left,100+strip*130,width,105,eventPos);}
                }
                p.setColor(Color.DKGRAY);p.setStyle(Paint.Style.FILL);p.setTextSize(8);c.drawText("25 mm/s · 10 mm/mV à 100 % · Repérage automatique à relire · "+EcgEngine.VERSION,left,790,p);
                c.drawText("Page "+(pageNo+1)+"/"+total,left,824,p);doc.finishPage(page);
            }
            try(OutputStream out=createDocument(cap.id,"rapport.pdf","application/pdf")){doc.writeTo(out);}
        }finally{doc.close();}
    }
    private void drawStrip(Canvas c,Paint p,Capture cap,int start,int count,float x,float y,float width,float height,int eventPos){
        float mm=72f/25.4f;int save=c.save();c.clipRect(x,y,x+width,y+height);p.setStyle(Paint.Style.STROKE);
        for(int i=0;i*mm<=width;i++){p.setColor(i%5==0?0x55c62843:0x22c62843);p.setStrokeWidth(.4f);c.drawLine(x+i*mm,y,x+i*mm,y+height,p);}
        for(int i=0;i*mm<=height;i++){p.setColor(i%5==0?0x55c62843:0x22c62843);c.drawLine(x,y+i*mm,x+width,y+i*mm,p);}
        int end=Math.min(cap.uv.size(),start+count),center=robustCenter(cap.uv,start,end);android.graphics.Path path=new android.graphics.Path();
        for(int i=start;i<end;i++){float px=x+(i-start)*25*mm/FS,py=y+height/2-(cap.uv.get(i)-center)*10*mm/1000;
            if(i==start||cap.ms.get(i)-cap.ms.get(i-1)>20)path.moveTo(px,py);else path.lineTo(px,py);}
        p.setColor(Color.BLACK);p.setStrokeWidth(.7f);c.drawPath(path,p);
        if(eventPos>=start&&eventPos<end){p.setColor(Color.RED);float at=x+(eventPos-start)*25*mm/FS;c.drawLine(at,y,at,y+height,p);}
        c.restoreToCount(save);p.setStyle(Paint.Style.FILL);p.setTextSize(8);p.setColor(Color.DKGRAY);
        c.drawText(humanTime(cap.ms.get(start))+" · brut recentré pour l'affichage",x,y-5,p);
    }

    private OutputStream createDocument(String eventId,String name,String mime)throws IOException{
        if(Build.VERSION.SDK_INT>=29){ContentResolver cr=context.getContentResolver();Uri existing=ReportFiles.find(context,eventId,name);if(existing!=null){OutputStream existingOut=cr.openOutputStream(existing,"wt");if(existingOut==null)throw new IOException("Fichier inaccessible");return existingOut;}ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.DISPLAY_NAME,name);v.put(MediaStore.MediaColumns.MIME_TYPE,mime);v.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOCUMENTS+"/PolarH10Lab/"+eventId);Uri uri=cr.insert(MediaStore.Files.getContentUri("external"),v);if(uri==null)throw new IOException("MediaStore indisponible");OutputStream out=cr.openOutputStream(uri,"w");if(out==null)throw new IOException("Flux de fichier indisponible");return out;}
        File dir=new File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),"PolarH10Lab/"+eventId);if(!dir.exists()&&!dir.mkdirs())throw new IOException("Dossier inaccessible");return new FileOutputStream(new File(dir,name));
    }

    private void storeError(String message){long now=System.currentTimeMillis();if(now-lastErrorAt<30_000)return;lastErrorAt=now;if(listener!=null)listener.onStoreError(message);}
    private static long generation(Context context){return context.getSharedPreferences("storage_state",Context.MODE_PRIVATE).getLong("generation",0);}
    private static int deleteTree(File file){if(file==null||!file.exists())return 0;int count=0;File[]children=file.listFiles();if(children!=null)for(File child:children)count+=deleteTree(child);if(file.delete())count++;else throw new IllegalStateException("Suppression impossible : "+file.getName());return count;}
    private static int nearestIndex(ArrayList<Long>x,long target){int best=0;long d=Long.MAX_VALUE;for(int i=0;i<x.size();i++){long n=Math.abs(x.get(i)-target);if(n<d){d=n;best=i;}}return best;}
    private static float drawWrapped(Canvas c,Paint p,String text,float x,float y,float width,float line){String[] words=text.split(" ");String row="";for(String word:words){String test=row.isEmpty()?word:row+" "+word;if(p.measureText(test)>width){if(!row.isEmpty())c.drawText(row,x,y,p);y+=line;row=word;}else row=test;}if(!row.isEmpty())c.drawText(row,x,y,p);return y;}
    private static int robustCenter(ArrayList<Integer> values,int start,int end){if(end<=start)return 0;ArrayList<Integer> sample=new ArrayList<>();int step=Math.max(1,(end-start)/200);for(int i=start;i<end;i+=step)sample.add(values.get(i));java.util.Collections.sort(sample);return sample.get(sample.size()/2);}
    private static String metric(double value){return value>0?String.format(Locale.FRANCE,"%.0f ms",value):"--";}
    private static String fileTime(long ms){return new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",Locale.ROOT).format(new Date(ms));}
    private static String humanTime(long ms){return new SimpleDateFormat("dd/MM/yyyy HH:mm:ss.SSS",Locale.FRANCE).format(new Date(ms));}
    private static String safe(String s){return s.replaceAll("[^A-Za-z0-9_-]","_");}
    private static String json(String s){return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n");}
    private static void writeShortLe(DataOutputStream o,short v)throws IOException{o.writeByte(v&255);o.writeByte((v>>>8)&255);}
    private static void writeIntLe(DataOutputStream o,int v)throws IOException{for(int i=0;i<4;i++)o.writeByte((v>>>(8*i))&255);}
    private static void writeLongLe(DataOutputStream o,long v)throws IOException{for(int i=0;i<8;i++)o.writeByte((int)(v>>>(8*i))&255);}
}
