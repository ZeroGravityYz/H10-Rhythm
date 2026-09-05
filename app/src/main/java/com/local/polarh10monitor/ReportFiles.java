package com.local.polarh10monitor;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.List;

/** Read-only grants restricted to one report. Supports Android 8–9 private exports. */
public final class ReportFiles extends ContentProvider {
    public static Uri find(Context c,String id,String name){
        if(!valid(id,name))return null;
        if(Build.VERSION.SDK_INT<29){File file=local(c,id,name);return file.isFile()?new Uri.Builder().scheme("content").authority(c.getPackageName()+".reports").appendPath(id).appendPath(name).build():null;}
        Uri collection=MediaStore.Files.getContentUri("external");
        try(Cursor cursor=c.getContentResolver().query(collection,new String[]{MediaStore.MediaColumns._ID},
            MediaStore.MediaColumns.DISPLAY_NAME+"=? AND "+MediaStore.MediaColumns.RELATIVE_PATH+"=?",
            new String[]{name,"Documents/PolarH10Lab/"+id+"/"},null)){
            return cursor!=null&&cursor.moveToFirst()?ContentUris.withAppendedId(collection,cursor.getLong(0)):null;
        }
    }
    private static boolean valid(String id,String name){return id!=null&&id.matches("[A-Za-z0-9_-]+")&&("rapport.pdf".equals(name)||"ecg_brut.jsonl".equals(name)||"evenement.json".equals(name));}
    private static File local(Context c,String id,String name){File base=c.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);if(base==null)throw new IllegalStateException("Stockage indisponible");return new File(base,"PolarH10Lab/"+id+"/"+name);}
    private File resolve(Uri uri)throws FileNotFoundException{List<String> p=uri.getPathSegments();if(p.size()!=2||!valid(p.get(0),p.get(1)))throw new FileNotFoundException("Fichier inconnu");return local(getContext(),p.get(0),p.get(1));}
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){return uri.getLastPathSegment()!=null&&uri.getLastPathSegment().endsWith(".pdf")?"application/pdf":"text/plain";}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("Lecture seule");return ParcelFileDescriptor.open(resolve(uri),ParcelFileDescriptor.MODE_READ_ONLY);}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){try{File f=resolve(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor c=new MatrixCursor(columns);Object[] values=new Object[columns.length];for(int i=0;i<columns.length;i++)values[i]=columns[i].equals(OpenableColumns.DISPLAY_NAME)?f.getName():columns[i].equals(OpenableColumns.SIZE)?f.length():null;c.addRow(values);return c;}catch(FileNotFoundException e){return null;}}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
}
