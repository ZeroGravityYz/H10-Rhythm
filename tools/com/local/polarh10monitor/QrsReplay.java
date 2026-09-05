package com.local.polarh10monitor;
import java.nio.*;
import java.nio.file.*;
public final class QrsReplay {
    public static void main(String[] args)throws Exception{
        IntBuffer samples=ByteBuffer.wrap(Files.readAllBytes(Path.of(args[0]))).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        StreamingQrs detector=new StreamingQrs();
        for(int i=0;i<samples.limit();i++){long at=detector.push(samples.get(i));if(at>=0)System.out.println(at+",N");}
    }
}
