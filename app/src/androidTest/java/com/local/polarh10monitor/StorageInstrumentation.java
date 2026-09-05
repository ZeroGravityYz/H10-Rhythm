package com.local.polarh10monitor;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.*;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/** Run on an emulator/test install. All fixtures use an isolated database and preferences. */
public final class StorageInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle status=new Bundle();status.putString("class",getClass().getName());status.putString("test","isolatedStorageRegression");status.putInt("numtests",1);status.putInt("current",1);sendStatus(1,status);
        try{runStorage();sendStatus(0,status);Bundle done=new Bundle();done.putString("stream","OK (1 integration test)\n");finish(Activity.RESULT_OK,done);}
        catch(Throwable error){status.putString("stack",android.util.Log.getStackTraceString(error));sendStatus(-2,status);Bundle done=new Bundle();done.putString("stream","FAILURES\n"+error);finish(Activity.RESULT_CANCELED,done);}
    }
    private static void require(boolean b,String message){if(!b)throw new AssertionError(message);}
    private void runStorage()throws Exception{
        Fixture c=new Fixture(getTargetContext());Field singleton=LocalRepository.class.getDeclaredField("instance");singleton.setAccessible(true);Object original=singleton.get(null);LocalRepository db=null;
        try{
            String event="{\"id\":\"fixture_event\",\"type\":\"NOTE\",\"time\":123456,\"review\":\"artifact\"}";
            c.getSharedPreferences("event_history",0).edit().putString("records_v2","["+event+"]").commit();
            c.getSharedPreferences("session_history",0).edit().putString("sessions_v1","[{\"start\":123456,\"end\":183456,\"avg\":70}]").commit();
            Constructor<LocalRepository> constructor=LocalRepository.class.getDeclaredConstructor(Context.class);constructor.setAccessible(true);db=constructor.newInstance(c);Method migrate=LocalRepository.class.getDeclaredMethod("migrate");migrate.setAccessible(true);migrate.invoke(db);singleton.set(null,db);
            require(db.list("event").size()==1&&db.list("session").size()==1,"legacy migration");
            require(db.find("annotation","fixture_event")!=null,"annotation migration");
            migrate.invoke(db);require(db.list("event").size()==1,"migration duplicated data");
            require(c.getSharedPreferences("event_history",0).contains("records_v2"),"migration removed its source");
            db.delete("event","fixture_event");require(db.find("annotation","fixture_event")!=null,"deleting a report forgot its learned example");
            ArrayList<JSONObject> records=new ArrayList<>();for(int i=0;i<10000;i++)records.add(new JSONObject().put("id","fixture_"+i).put("time",1000+i).put("title","passage "+i));
            db.replace("event",records,"id","time");db.favorite("event","fixture_9999",true);
            long begin=android.os.SystemClock.elapsedRealtime();require(db.timeline("event","passage 9999",0,Long.MAX_VALUE,true,50).size()==1,"indexed filter");
            android.util.Log.i("H10_TEST","10k-history query ms="+(android.os.SystemClock.elapsedRealtime()-begin));
            FitnessInsights.Morning from=new FitnessInsights.Morning(System.currentTimeMillis()-1000,60,50,40,100,100,3,3,3,false,false,false);from.continuityVerified=true;
            ForecastLedger.save(c,from,20,.25,0,0,new double[]{60,2,Math.log(50),.1});
            ForecastLedger.save(c,from,20,999,0,0,new double[]{60,2,Math.log(50),.1});
            require(db.find("forecast",String.valueOf(from.timestampMs)).getDouble("prediction")==.25,"forecast overwritten after creation");
            require(!ForecastLedger.score(c).ready,"forecast published before future observations");
            require(Double.isNaN(TrainingJournal.loadBetween(c,from.timestampMs,from.timestampMs+86400000L)),"unknown day assumed rest");
            TrainingJournal.completeDay(c,from.timestampMs);require(TrainingJournal.loadBetween(c,from.timestampMs,from.timestampMs+86400000L)==0,"explicit rest not usable");
            EventStore store=new EventStore(c,null);store.startSession();store.onRawSample(-8388608,1800000000000L);store.onRawSample(8388607,1800000000008L);store.stopSession();store.shutdown();
            File[] chunks=new File(c.getFilesDir(),"continuous").listFiles();require(chunks!=null&&chunks.length==1,"raw chunk missing");
            try(DataInputStream in=new DataInputStream(new FileInputStream(chunks[0]))){byte[] magic=new byte[8];in.readFully(magic);require(new String(magic,java.nio.charset.StandardCharsets.US_ASCII).equals("H10ECG2\n"),"raw schema");in.skipBytes(12);long t=Long.reverseBytes(in.readLong());int uv=Integer.reverseBytes(in.readInt());require(t==1800000000000L&&uv==-8388608,"negative 24-bit clipped");in.skipBytes(8);require(Integer.reverseBytes(in.readInt())==8388607,"positive 24-bit clipped");}
        }finally{singleton.set(null,original);if(db!=null)db.close();c.clean();}
    }
    private static final class Fixture extends ContextWrapper {
        final String prefix="h10_test_"+UUID.randomUUID()+"_";final File root;final HashSet<String> preferences=new HashSet<>();
        Fixture(Context c){super(c);root=new File(c.getCacheDir(),prefix);if(!root.mkdirs())throw new IllegalStateException("fixture");}
        @Override public Context getApplicationContext(){return this;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){preferences.add(prefix+name);return super.getSharedPreferences(prefix+name,mode);}
        @Override public File getFilesDir(){return root;}
        @Override public File getDatabasePath(String name){return new File(root,name);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name),factory);}
        @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler){return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(),factory,handler);}
        void clean(){for(String name:preferences)getBaseContext().deleteSharedPreferences(name);erase(root);}
        private void erase(File f){File[] children=f.listFiles();if(children!=null)for(File child:children)erase(child);if(!f.delete()&&f.exists())throw new IllegalStateException("fixture cleanup");}
    }
}
