package com.local.polarh10monitor;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EcgReplay {
    private static final Pattern VOLTAGE=Pattern.compile("\\\"voltage\\\"\\s*:\\s*(-?\\d+)");

    public static void main(String[] files)throws Exception{
        for(String file:files)replay(Path.of(file));
    }

    private static void replay(Path file)throws Exception{
        Map<String,Integer> events=new LinkedHashMap<>();MorphologyModel model=new MorphologyModel();
        EcgEngine engine=new EcgEngine(event->events.merge(event.type,1,Integer::sum),model);long sample=0,start=1_700_000_000_000L;
        try(BufferedReader reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){
            String line;while((line=reader.readLine())!=null){Matcher matcher=VOLTAGE.matcher(line);while(matcher.find()){engine.push(Integer.parseInt(matcher.group(1)),start+sample*1000/EcgEngine.FS);sample++;}}
        }
        EcgEngine.Snapshot snapshot=engine.snapshot();System.out.println(file.getFileName()+" | samples="+sample+" | bpm="+snapshot.bpm+" | model="+snapshot.modelSamples+"/500 | threshold="+String.format(java.util.Locale.ROOT,"%.3f",snapshot.morphologyThreshold)+" | events="+events);
    }
}
