// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;
import java.io.*;
import java.nio.file.*;
import java.util.*;
/** Real filesystem regression for Android's data-directory aliases. No native API doubles. */
public final class YoutubeCacheAliasTest {
    static int checks;
    static void check(boolean v,String m){checks++;if(!v)throw new AssertionError(m);}
    public static void main(String[] args) throws Exception {
        Path temp=Files.createTempDirectory("youtube-alias-test-");
        try {
            Path real=temp.resolve("data/com.example/cache");Files.createDirectories(real);
            Path alias=temp.resolve("user-zero");Files.createSymbolicLink(alias,temp.resolve("data"));
            File cache=alias.resolve("com.example/cache").toFile();
            File stage=new File(cache,"youtube-old");stage.mkdir();
            Files.write(new File(stage,"video.part").toPath(),new byte[]{1});
            Files.write(new File(stage,"audio.part").toPath(),new byte[]{2});
            String stored=stage.getAbsolutePath();
            if(args.length>0 && "--baseline".equals(args[0])) {
                for(int i=0;i<2;i++) {
                    try{YoutubeWorkFiles.clean(cache,stored);throw new AssertionError("Expected old cleanup failure");}
                    catch(IOException e){check(e.getMessage().contains("no pertenece"),"Legitimate alias refused on every recovery");}
                }
                check(stage.exists(),"Failed cleanup leaves stage, blocking subsequent recovery");
                System.out.println("YoutubeCacheAliasTest BASELINE 1.4.1: repeated cleanup failure reproduced with real files and directory alias.");
                return;
            }
            YoutubeWorkFiles.clean(cache,stored);
            check(!stage.exists(),"Old alias marker safely recovered");
            YoutubeWorkFiles.clean(cache,stored);check(!stage.exists(),"Recovery remains idempotent after cleanup");
            File fresh=YoutubeWorkFiles.folder(cache,"fresh");fresh.mkdir();
            check(fresh.getAbsoluteFile().equals(fresh.getCanonicalFile()),"New stages use canonical cache root");
            Files.write(new File(fresh,"muxed.mp4").toPath(),new byte[]{3});
            YoutubeWorkFiles.clean(cache,fresh.getPath());check(!fresh.exists(),"New canonical marker cleaned via aliased context");
            File other=new File(real.toFile(),"youtube-other");other.mkdir();
            File untouched=new File(other,"audio.part");Files.write(untouched.toPath(),new byte[]{4});
            Path link=real.resolve("youtube-link");Files.createSymbolicLink(link,other.toPath());
            try{YoutubeWorkFiles.clean(cache,link.toString());throw new AssertionError("Leaf link accepted");}
            catch(IOException expected){check(untouched.exists(),"Link to another task rejected without deleting it");}
            File stage2=new File(real.toFile(),"youtube-stage");stage2.mkdir();
            Path mediaLink=new File(stage2,"video.part").toPath();Files.createSymbolicLink(mediaLink,untouched.toPath());
            try{YoutubeWorkFiles.clean(cache,stage2.getPath());throw new AssertionError("Media link accepted");}
            catch(IOException expected){check(untouched.exists(),"Link to another task's audio rejected");}
            File outside=temp.resolve("youtube-outside").toFile();outside.mkdir();
            try{YoutubeWorkFiles.clean(cache,outside.getPath());throw new AssertionError("Outside stage accepted");}
            catch(IOException expected){check(outside.exists(),"Outside folder never removed");}
            System.out.println("YoutubeCacheAliasTest: "+checks+" comprobaciones con archivos reales y enlaces de directorio");
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(temp)){
                for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(path);
            }
        }
    }
}
