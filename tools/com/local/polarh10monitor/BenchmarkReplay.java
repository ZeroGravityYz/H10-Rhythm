package com.local.polarh10monitor;
import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
/** Benchmark adapter only; does not change engine decisions or use reference labels. */
public final class BenchmarkReplay {
    public static void main(String[] args)throws Exception{
        byte[] raw=Files.readAllBytes(Path.of(args[0]));IntBuffer samples=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        EcgEngine engine=new EcgEngine(e->{});if(args.length>1&&args[1].equals("features"))engine.researchFeaturesOnly();Field field=EcgEngine.class.getDeclaredField("beats");field.setAccessible(true);
        Class<?> type=Class.forName("com.local.polarh10monitor.EcgEngine$Beat");Field index=type.getDeclaredField("index"),label=type.getDeclaredField("label"),classified=type.getDeclaredField("classified");index.setAccessible(true);label.setAccessible(true);classified.setAccessible(true);HashSet<Long> seen=new HashSet<>();
        Field reason=type.getDeclaredField("reason"),width=type.getDeclaredField("width"),similarity=type.getDeclaredField("similarity"),features=type.getDeclaredField("features");reason.setAccessible(true);width.setAccessible(true);similarity.setAccessible(true);features.setAccessible(true);
        PrintWriter out=new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.out)));
        for(int i=0;i<samples.limit();i++){engine.push(samples.get(i),1_800_000_000_000L+Math.round(i*1000.0/130));if(i%16!=0)continue;List<?> beats=(List<?>)field.get(engine);for(int k=Math.max(0,beats.size()-24);k<beats.size();k++){Object beat=beats.get(k);if(!classified.getBoolean(beat))continue;long at=index.getLong(beat);if(seen.add(at)){
            out.print(at+","+label.getChar(beat)+","+reason.get(beat)+","+width.getDouble(beat)+","+similarity.getDouble(beat));
            if(args.length>1&&args[1].equals("features")){float[] x=(float[])features.get(beat);if(x!=null)for(float v:x)out.print(","+v);}
            out.println();
        }}}
        out.flush();
    }
}
