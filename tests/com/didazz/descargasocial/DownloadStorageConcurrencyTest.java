// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

import android.content.*;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.net.Uri;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Exercises the production storage implementation with a memory MediaStore and real legacy files. */
public final class DownloadStorageConcurrencyTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if(!value) throw new AssertionError(message); }
    static void await(CountDownLatch latch) throws Exception {
        if(!latch.await(5,TimeUnit.SECONDS)) throw new AssertionError("Worker did not reach checkpoint");
    }
    static Thread worker(Context context, DownloadControl control, String id, AtomicReference<Throwable> error) {
        return worker(context,control,id,error,new MediaItem("https://example.invalid/"+id,true,null));
    }
    static Thread worker(Context context, DownloadControl control, String id, AtomicReference<Throwable> error,MediaItem item) {
        Thread thread = new Thread(() -> {
            control.attach();
            try(DownloadStorage storage = new DownloadStorage(context,control,id)) {
                storage.saveMedia(item, "same-content",1);
            } catch(Throwable failure) { error.set(failure); } finally { control.detach(); }
        });
        thread.start(); return thread;
    }
    static void join(Thread thread) throws Exception {
        thread.join(5000); check(!thread.isAlive(),"Worker finished");
    }
    static int files(File directory, String suffix) {
        File[] files = directory.listFiles((d,n)->n.endsWith(suffix));
        return files==null?0:files.length;
    }
    static void concurrent(int sdk, boolean cancelFirst) throws Exception {
        Build.VERSION.SDK_INT=sdk;
        Context context=new Context();
        Path temp=Files.createTempDirectory("social-storage-test-");
        Environment.root=temp.toFile();
        CountDownLatch entered=new CountDownLatch(2), releaseA=new CountDownLatch(1), releaseB=new CountDownLatch(1);
        SocialExtractor.writer=(item,out)->{
            entered.countDown();
            boolean a=item.getUrl().endsWith("a");
            await(a?releaseA:releaseB);
            out.write(new byte[]{(byte)(a?1:2),3,4,5});
        };
        DownloadControl a=new DownloadControl((bytes,expected)->{}), b=new DownloadControl((bytes,expected)->{});
        AtomicReference<Throwable> errorA=new AtomicReference<>(),errorB=new AtomicReference<>();
        Thread first=worker(context,a,"a",errorA), second=worker(context,b,"b",errorB);
        await(entered);
        SharedPreferences prefs=context.getSharedPreferences("download_storage",0);
        check(prefs.getAll().size()==2,"Both independent pending markers survive construction");
        if(sdk>=29) check(context.getContentResolver().snapshot().size()==2,"Other live MediaStore target not recovered");
        else check(files(new File(temp.toFile(),"DescargaSocial"),".part")==2,"Other live temporary file not removed");
        if(cancelFirst) a.cancel("test cancellation");
        releaseA.countDown(); join(first);
        check(prefs.getAll().size()==1,"Closing one only clears its marker");
        check(!b.isCancelled(),"Other control not cancelled");
        if(cancelFirst) check(errorA.get()!=null,"Cancelled write cannot publish");
        else check(errorA.get()==null,"First saves successfully");
        releaseB.countDown(); join(second);
        check(errorB.get()==null,"Second survives first finishing or cancelling");
        check(prefs.getAll().isEmpty(),"All own markers cleared");
        if(sdk>=29) {
            List<ContentResolver.Row> rows=context.getContentResolver().snapshot();
            check(rows.size()==(cancelFirst?1:2),"Only complete requested files remain");
            Set<Object> names=new HashSet<>();
            for(ContentResolver.Row row:rows) {
                check(Integer.valueOf(0).equals(row.values.get(MediaStore.MediaColumns.IS_PENDING)),"Published complete");
                check(row.bytes.size()==4,"No partial or interleaved bytes");
                names.add(row.values.get(MediaStore.MediaColumns.DISPLAY_NAME));
            }
            check(names.size()==rows.size(),"Concurrent same-name requests get distinct names");
        } else {
            File folder=new File(temp.toFile(),"DescargaSocial");
            check(files(folder,".part")==0,"No leftover legacy partials");
            check(files(folder,".mp4")==(cancelFirst?1:2),"No overwrites of completed legacy files");
            for(File f:folder.listFiles()) check(f.length()==4,"Complete independent legacy files");
        }
        // Remove only fixtures created by this test invocation.
        try(java.util.stream.Stream<Path> paths=Files.walk(temp)) {
            for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(p);
        }
    }
    static void recover() throws Exception {
        Build.VERSION.SDK_INT=29;
        Context context=new Context();
        ContentResolver resolver=context.getContentResolver();
        ContentValues pending=new ContentValues(); pending.put(MediaStore.MediaColumns.IS_PENDING,1);
        ContentValues complete=new ContentValues(); complete.put(MediaStore.MediaColumns.IS_PENDING,0);
        Uri collection=MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri orphan=resolver.insert(collection,pending), good=resolver.insert(collection,complete);
        SharedPreferences prefs=context.getSharedPreferences("download_storage",0);
        prefs.edit().putString("pending_uri:old",orphan.toString()).putString("pending_uri",good.toString()).commit();
        try(DownloadStorage storage=new DownloadStorage(context,new DownloadControl((n,t)->{}),"new")) {
            storage.recoverPending();
            check(resolver.snapshot().size()==1,"Only orphan partial removed; upgraded completed file preserved");
            check(resolver.snapshot().get(0).values.get(MediaStore.MediaColumns.IS_PENDING).equals(0),"Complete row retained");
        }
        check(prefs.getAll().isEmpty(),"Recovered markers retired");
    }
    static void youtube(int sdk) throws Exception {
        Build.VERSION.SDK_INT=sdk;Context context=new Context();
        Path temp=Files.createTempDirectory("youtube-storage-test-");Environment.root=temp.toFile();
        CountDownLatch entered=new CountDownLatch(2),releaseA=new CountDownLatch(1),releaseB=new CountDownLatch(1);
        YoutubeDownload.writer=(item,out,dir)->{
            Files.write(new File(dir,"video.part").toPath(),new byte[]{1,2});
            Files.write(new File(dir,"audio.part").toPath(),new byte[]{3,4});
            entered.countDown();await(dir.getName().endsWith("a")?releaseA:releaseB);
            out.write(new byte[]{1,2,3,4});
        };
        DownloadControl a=new DownloadControl((n,t)->{}),b=new DownloadControl((n,t)->{});
        AtomicReference<Throwable> ea=new AtomicReference<>(),eb=new AtomicReference<>();
        MediaItem media=MediaItem.youtube("https://www.youtube.com/watch?v=aqz-KE-bpKQ",false,null,1920,1080);
        Thread first=worker(context,a,"a",ea,media),second=worker(context,b,"b",eb,media);await(entered);
        SharedPreferences prefs=context.getSharedPreferences("download_storage",0);
        check(prefs.getAll().size()==4,"Both stages and public targets marked independently");
        File cache=context.getCacheDir();
        check(new File(cache,"youtube-a/video.part").exists() && new File(cache,"youtube-b/audio.part").exists(),"Active staging survives other worker construction");
        a.cancel("Cancelled test");releaseA.countDown();join(first);
        check(ea.get()!=null,"Cancelled YouTube task not published");
        check(!new File(cache,"youtube-a").exists(),"Cancelled staging removed");
        check(new File(cache,"youtube-b/audio.part").exists(),"Other task audio preserved");
        check(prefs.getAll().size()==2,"Only cancelled task markers removed");
        releaseB.countDown();join(second);check(eb.get()==null,"Other YouTube task completes");
        check(!new File(cache,"youtube-b").exists() && prefs.getAll().isEmpty(),"Success clears staging and markers");
        if(sdk>=29) check(context.getContentResolver().snapshot().size()==1,"Only complete result in MediaStore");
        else check(files(new File(temp.toFile(),"DescargaSocial"),".mp4")==1,"Only complete legacy result");
        File orphan=new File(cache,"youtube-orphan");orphan.mkdir();Files.write(new File(orphan,"muxed.mp4").toPath(),new byte[]{1});
        prefs.edit().putString("pending_cache:orphan",orphan.getAbsolutePath()).commit();
        try(DownloadStorage next=new DownloadStorage(context,new DownloadControl((n,t)->{}),"new")) {
            next.recoverPending();check(!orphan.exists(),"Process-death staging recovered");
        }
        check(prefs.getAll().isEmpty(),"Orphan cache marker retired");
        try(java.util.stream.Stream<Path> paths=Files.walk(temp)) {
            for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(p);
        }
    }
    public static void main(String[] args) throws Exception {
        concurrent(29,true); concurrent(29,false);
        concurrent(28,true); concurrent(28,false);
        recover();
        youtube(29);youtube(28);
        aliasRecovery(29);aliasRecovery(28);preserveFailure();
        System.out.println("DownloadStorageConcurrencyTest: "+checks+" comprobaciones");
    }

    static void aliasRecovery(int sdk) throws Exception {
        Build.VERSION.SDK_INT=sdk;
        Path temp=Files.createTempDirectory("social-storage-alias-");
        try {
            Path real=temp.resolve("real");Files.createDirectories(real.resolve("cache"));
            Path alias=temp.resolve("alias");Files.createSymbolicLink(alias,real);
            Environment.root=alias.toFile();Context context=new Context();File cache=context.getCacheDir();
            File orphan=new File(cache,"youtube-old");orphan.mkdir();Files.write(new File(orphan,"video.part").toPath(),new byte[]{1});
            SharedPreferences prefs=context.getSharedPreferences("download_storage",0);
            prefs.edit().putString("pending_cache:old",orphan.getAbsolutePath()).commit();
            DownloadControl control=new DownloadControl((n,t)->{});control.attach();
            try(DownloadStorage storage=new DownloadStorage(context,control,"new")) {
                check(!orphan.exists() && prefs.getAll().isEmpty(),"Aliased pending stage recovered before new request");
                YoutubeDownload.writer=(item,out,dir)->{
                    check(dir.getAbsolutePath().equals(dir.getCanonicalPath()),"New stage resolves trusted root alias");
                    Files.write(new File(dir,"audio.part").toPath(),new byte[]{2});out.write(new byte[]{1,2,3,4});
                };
                storage.saveMedia(MediaItem.youtube("https://www.youtube.com/watch?v=aqz-KE-bpKQ",false,null,1920,1080),"test",1);
            } finally {control.detach();}
            check(prefs.getAll().isEmpty(),"Successful request clears all new pending markers");
            check(new File(cache,"youtube-new").exists()==false,"No leftover stage after alias recovery");
            if(sdk>=29)check(context.getContentResolver().snapshot().size()==1,"Complete output published after alias recovery");
            else check(files(new File(alias.toFile(),"DescargaSocial"),".mp4")==1,"Complete legacy output after alias recovery");
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(temp)){
                for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(path);
            }
        }
    }
    static void preserveFailure() throws Exception {
        Build.VERSION.SDK_INT=29;Path temp=Files.createTempDirectory("social-storage-errors-");Environment.root=temp.toFile();
        Context context=new Context();DownloadControl control=new DownloadControl((n,t)->{});control.attach();
        java.net.SocketException original=new java.net.SocketException("Connection reset");
        try(DownloadStorage storage=new DownloadStorage(context,control,"failing")) {
            YoutubeDownload.writer=(item,out,dir)->{Files.write(new File(dir,"unrecognized.txt").toPath(),new byte[]{1});throw original;};
            try{storage.saveMedia(MediaItem.youtube("https://www.youtube.com/watch?v=aqz-KE-bpKQ",false,null,1,1),"test",1);throw new AssertionError("Expected failure");}
            catch(java.net.SocketException failure){
                check(failure==original,"Original transfer failure retained");
                check(failure.getSuppressed().length==1 && failure.getSuppressed()[0] instanceof StorageException,"Cleanup failure retained separately");
                check(!DownloadPolicy.retryable(failure),"Unclean stage prevents automatic retry");
                check(DownloadDiagnostics.report(failure,"Descargando vídeo").contains("TEMP_CONTENT"),"Details identify blocked cleanup");
            }
            check(context.getContentResolver().snapshot().isEmpty(),"No incomplete result published");
            File dir=new File(context.getCacheDir(),"youtube-failing");check(dir.exists(),"Unknown content not deleted");
            // Remove only the unrecognized fixture we deliberately created, then recover normally.
            Files.delete(new File(dir,"unrecognized.txt").toPath());storage.recoverPending();
            check(!dir.exists() && context.getSharedPreferences("download_storage",0).getAll().isEmpty(),"Pending recovery can finish without clearing app data");
        } finally {
            control.detach();try(java.util.stream.Stream<Path> paths=Files.walk(temp)){
                for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(path);
            }
        }
    }
}
