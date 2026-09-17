// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;
import android.media.MediaExtractor;
import android.media.MediaMuxer;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Runs the production mux method with native-API doubles: permissions and errors, not codec validity. */
public final class YoutubeMuxContractTest {
    static int checks;
    static File video,audio,output;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static void reset(){MediaExtractor.reset(video.length(),audio.length());MediaMuxer.reset();}
    static void merge(DownloadControl control) throws Exception {
        Method method=YoutubeDownload.class.getDeclaredMethod("mux",File.class,File.class,File.class,DownloadControl.class);
        method.setAccessible(true);
        try{method.invoke(null,video,audio,output,control);}
        catch(InvocationTargetException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;throw (Error)cause;}
    }
    static DownloadControl control(){return new DownloadControl((n,t)->{});}
    static void released(){
        check(MediaExtractor.instances.stream().allMatch(e->e.released),"All extractors released");
        check(MediaExtractor.descriptors.stream().noneMatch(fd->fd.valid()),"All local descriptors closed");
    }
    static void expect(String message) throws Exception {
        try{merge(control());throw new AssertionError("Expected failure");}
        catch(YoutubeException expected){
            check(TestResources.render("es", DownloadPolicy.friendly(expected)).contains(message),"Specific assembly error reaches queue: "+message);
            check(expected.getCause()!=null,"Original failure retained for diagnosis");
            check(!DownloadPolicy.retryable(expected),"Local assembly failure does not redownload tracks");
        }
        released();
    }
    public static void main(String[] args) throws Exception {
        Path temp=Files.createTempDirectory("youtube-mux-contract-");
        try {
            video=temp.resolve("video.part").toFile();audio=temp.resolve("audio.part").toFile();output=temp.resolve("muxed.mp4").toFile();
            Files.write(video.toPath(),new byte[64]);Files.write(audio.toPath(),new byte[48]);
            Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rwx------"));
            Files.setPosixFilePermissions(video.toPath(),PosixFilePermissions.fromString("rw-------"));
            Files.setPosixFilePermissions(audio.toPath(),PosixFilePermissions.fromString("rw-------"));
            reset();
            if(args.length>0 && "--baseline".equals(args[0])) {
                try{merge(control());throw new AssertionError("Original bug was not reproduced");}
                catch(IOException expected){
                    check(MediaExtractor.paths==1 && MediaExtractor.descriptors.isEmpty(),"1.4.0 uses inaccessible private pathname");
                    check(DownloadPolicy.friendly(expected).startsWith("No se pudo completar la descarga."),"1.4.0 loses actual I/O stage in generic message");
                }
                released();
                System.out.println("YoutubeMuxContractTest BASELINE 1.4.0: private-path failure and generic error reproduced (simulated native contract).");
                return;
            }
            merge(control());
            check(MediaExtractor.paths==0 && MediaExtractor.descriptors.size()==3,"Video, audio and final verification all use descriptors");
            check(MediaMuxer.tracks==2 && MediaMuxer.stopped && MediaMuxer.released,"Both tracks finalized and muxer closed");
            check(MediaMuxer.video.equals(Arrays.asList(0L,66666L,33333L,100000L)),"B-frame presentation timestamps preserved in decode order");
            check(MediaMuxer.audio.equals(Arrays.asList(0L,23219L,46438L,69657L,92876L)),"Audio samples complete and unchanged");
            check(Files.getPosixFilePermissions(video.toPath()).equals(PosixFilePermissions.fromString("rw-------")),"No broader file permissions");
            released();
            reset();MediaExtractor.failOpen=0;expect("abrir la pista de vídeo");
            reset();MediaExtractor.failOpen=1;expect("abrir la pista de sonido");
            reset();MediaMuxer.failCreate=true;expect("crear el MP4 final");
            reset();MediaMuxer.failWrite=true;expect("unir las pistas");
            reset();MediaMuxer.failStop=true;expect("finalizar el MP4");
            reset();MediaExtractor.failOpen=2;expect("comprobar el MP4");
            reset();MediaExtractor.failOpen=1;MediaExtractor.failRelease=0;
            try{merge(control());throw new AssertionError("Expected open failure");}
            catch(YoutubeException error){check(TestResources.render("es",error.getMessage()).contains("sonido") && error.getSuppressed().length==1,"Cleanup error does not hide opening error");}
            released();
            reset();MediaMuxer.failRelease=true;expect("cerrar los archivos");
            reset();MediaExtractor.omitFinalAudio=true;
            try{merge(control());throw new AssertionError("Expected missing final audio");}
            catch(YoutubeException expected){check(TestResources.render("es",expected.getMessage()).contains("sonido"),"Final file without audio not accepted");}
            released();
            reset();DownloadControl cancel=control();MediaMuxer.onWrite=()->cancel.cancel("Cancelada");MediaMuxer.failRelease=true;
            try{merge(cancel);throw new AssertionError("Expected cancellation");}
            catch(DownloadControl.Cancelled expected){check(expected.getSuppressed().length==1,"Cancellation remains cancellation even if cleanup fails");}
            released();
            System.out.println("YoutubeMuxContractTest: "+checks+" comprobaciones (API nativa simulada; no valida códecs en Android)");
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(temp)){
                for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(path);
            }
        }
    }
}
