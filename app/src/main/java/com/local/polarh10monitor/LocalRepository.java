package com.local.polarh10monitor;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Versioned local records. Migration and its completion marker commit atomically. */
public final class LocalRepository extends SQLiteOpenHelper {
    private static LocalRepository instance;
    private final Context context;
    public static synchronized LocalRepository get(Context context) {
        if(instance==null) { LocalRepository candidate=new LocalRepository(context.getApplicationContext()); try{candidate.migrate();instance=candidate;}catch(RuntimeException e){candidate.close();throw e;} }
        return instance;
    }
    private LocalRepository(Context context){super(context,"rhythm.db",null,1);this.context=context;setWriteAheadLoggingEnabled(true);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE records(kind TEXT NOT NULL,id TEXT NOT NULL,time INTEGER NOT NULL,payload TEXT NOT NULL,favorite INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(kind,id))");
        db.execSQL("CREATE INDEX records_time ON records(kind,time DESC)");
        db.execSQL("CREATE INDEX timeline_time ON records(time DESC)");
        db.execSQL("CREATE TABLE migrations(name TEXT PRIMARY KEY)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){throw new IllegalStateException("Migration non définie");}
    private void migrate(){SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{
        try(Cursor c=db.rawQuery("SELECT name FROM migrations WHERE name='preferences-v1'",null)){if(c.moveToFirst()){db.setTransactionSuccessful();return;}}
        copy(db,"event","event_history","records_v2","id","time");
        copy(db,"session","session_history","sessions_v1","start","start");
        copy(db,"morning","fitness_local_v1","mornings_v1","time","time");
        db.execSQL("INSERT INTO migrations VALUES('preferences-v1')");db.setTransactionSuccessful();
    }catch(Exception e){throw new IllegalStateException("Import local interrompu ; données originales conservées",e);}finally{db.endTransaction();}}
    private void copy(SQLiteDatabase db,String kind,String prefs,String key,String idKey,String timeKey)throws Exception{
        JSONArray a=new JSONArray(context.getSharedPreferences(prefs,0).getString(key,"[]"));
        for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);String id=o.getString(idKey);long time=o.optLong(timeKey);put(db,kind,id,time,o);
            if(kind.equals("event")&&!o.optString("review").isEmpty())put(db,"annotation",id,time,o);
        }
    }
    private static void put(SQLiteDatabase db,String kind,String id,long time,JSONObject payload){ContentValues v=new ContentValues();v.put("kind",kind);v.put("id",id);v.put("time",time);v.put("payload",payload.toString());
        if(db.update("records",v,"kind=? AND id=?",new String[]{kind,id})==0)db.insertOrThrow("records",null,v);
    }
    public synchronized void put(String kind,String id,long time,JSONObject payload){put(getWritableDatabase(),kind,id,time,payload);}
    public synchronized boolean putIfAbsent(String kind,String id,long time,JSONObject payload){ContentValues v=new ContentValues();v.put("kind",kind);v.put("id",id);v.put("time",time);v.put("payload",payload.toString());return getWritableDatabase().insertWithOnConflict("records",null,v,SQLiteDatabase.CONFLICT_IGNORE)!=-1;}
    public synchronized void replace(String kind,List<JSONObject> values,String idKey,String timeKey){SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{for(JSONObject o:values)put(db,kind,o.optString(idKey),o.optLong(timeKey),o);db.setTransactionSuccessful();}finally{db.endTransaction();}}
    public synchronized List<JSONObject> list(String kind){return query(kind,"",0,false,10000);}
    public synchronized List<JSONObject> query(String kind,String search,long since,boolean favorite,int limit){
        ArrayList<JSONObject> out=new ArrayList<>();String where="kind=? AND time>=?";ArrayList<String> args=new ArrayList<>();args.add(kind);args.add(String.valueOf(since));
        if(!search.isEmpty()){where+=" AND payload LIKE ? ESCAPE '\\'";args.add("%"+search.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");}
        if(favorite)where+=" AND favorite=1";
        try(Cursor c=getReadableDatabase().query("records",new String[]{"payload","favorite"},where,args.toArray(new String[0]),null,null,"time DESC",String.valueOf(limit))){while(c.moveToNext()){try{JSONObject o=new JSONObject(c.getString(0));o.put("favorite",c.getInt(1)==1);out.add(o);}catch(Exception e){throw new IllegalStateException("Enregistrement illisible",e);}}}return out;
    }
    public synchronized List<JSONObject> timeline(String kind,String search,long since,long until,boolean favorites,int limit){
        ArrayList<JSONObject> out=new ArrayList<>();ArrayList<String> args=new ArrayList<>();
        String where="kind IN ('event','morning','session','workout') AND time>=? AND time<?";args.add(String.valueOf(since));args.add(String.valueOf(until));
        if(!kind.equals("*")){where+=" AND kind=?";args.add(kind);}if(favorites)where+=" AND favorite=1";
        if(!search.isEmpty()){where+=" AND instr(lower(payload),lower(?))>0";args.add(search);}
        try(Cursor c=getReadableDatabase().query("records",new String[]{"kind","id","payload","favorite"},where,args.toArray(new String[0]),null,null,"time DESC",String.valueOf(limit))){while(c.moveToNext())try{JSONObject o=new JSONObject(c.getString(2));o.put("_kind",c.getString(0));o.put("_id",c.getString(1));o.put("favorite",c.getInt(3)==1);out.add(o);}catch(Exception e){throw new IllegalStateException(e);}}return out;
    }
    public synchronized JSONObject find(String kind,String id){try(Cursor c=getReadableDatabase().query("records",new String[]{"payload"},"kind=? AND id=?",new String[]{kind,id},null,null,null)){return c.moveToFirst()?new JSONObject(c.getString(0)):null;}catch(org.json.JSONException e){throw new IllegalStateException(e);}}
    public synchronized void replaceDay(String kind,String id,long time,JSONObject value,long from,long until){SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{db.delete("records","kind=? AND time>=? AND time<?",new String[]{kind,String.valueOf(from),String.valueOf(until)});put(db,kind,id,time,value);db.setTransactionSuccessful();}finally{db.endTransaction();}}
    public synchronized void favorite(String kind,String id,boolean value){ContentValues v=new ContentValues();v.put("favorite",value?1:0);getWritableDatabase().update("records",v,"kind=? AND id=?",new String[]{kind,id});}
    public synchronized void delete(String kind,String id){getWritableDatabase().delete("records","kind=? AND id=?",new String[]{kind,id});}
    public synchronized void clear(String kind){getWritableDatabase().delete("records","kind=?",new String[]{kind});}
}
