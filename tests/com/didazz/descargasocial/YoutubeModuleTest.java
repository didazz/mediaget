// SPDX-License-Identifier: GPL-3.0-or-later
package com.didazz.descargasocial;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic tests of production URL, quality, range, transfer and temporary-file logic. */
public final class YoutubeModuleTest {
    static int checks;
    interface Action { void run() throws Exception; }
    static void check(boolean v,String label) { checks++; if(!v) throw new AssertionError(label); }
    static void fails(Action a,String label) throws Exception {
        try { a.run(); } catch(IOException | IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError(label);
    }
    static YoutubeQuality.Track track(int height,int fps,long size,boolean audio) {
        return new YoutubeQuality.Track("https://media.googlevideo.com/test","avc1.640028",height*16/9,
                height,fps,2000000,height,size,true,audio,false);
    }
    static YoutubeQuality.Track audio(int rate,boolean original) {
        return new YoutubeQuality.Track("https://media.googlevideo.com/audio","mp4a.40.2",0,0,0,
                rate,140,500,false,true,original);
    }
    static void links() throws Exception {
        String id="aqz-KE-bpKQ", canonical="https://www.youtube.com/watch?v="+id;
        for(String url:Arrays.asList(canonical,canonical+"&list=PLtest&t=5",
                "https://youtu.be/"+id+"?si=tracking","https://m.youtube.com/watch?v="+id,
                "https://www.youtube.com/shorts/"+id,"https://youtube.com/live/"+id,
                "https://www.youtube-nocookie.com/embed/"+id,"https://music.youtube.com/watch?v="+id)) {
            check(canonical.equals(YoutubeLink.canonical(url)),"Canonical single video");
            check(SocialLinkParser.detectPlatform(url)==SocialPlatform.YOUTUBE,"Independent YouTube routing");
        }
        for(String url:Arrays.asList("http://youtu.be/"+id,"https://youtu.be/short",
                "https://www.youtube.com/playlist?list=PLtest","https://youtube.com/@channel",
                "https://youtube.com.evil.invalid/watch?v="+id,"https://user@youtu.be/"+id,
                "https://youtu.be:8443/"+id,canonical+"&v=xxxxxxxxxxx",canonical+"%2Fextra")) {
            check(YoutubeLink.videoId(url)==null,"Reject unsupported or ambiguous links");
        }
        check(canonical.equals(SocialLinkParser.extractSupportedUrl("Mira: "+canonical+".")),"Share text");
        check(SocialLinkParser.detectPlatform("https://youtube.com.evil.invalid/watch?v="+id)==null,"No impersonation fallback");
        check(YoutubeHttp.allowedHost("rr1---sn-test.googlevideo.com",true),"Media CDN allowed");
        check(!YoutubeHttp.allowedHost("googlevideo.com.evil.invalid",true),"Media suffix boundary");
        check(!YoutubeHttp.allowedHost("www.youtube.com",true),"Watch page not media");
        check(!YoutubeHttp.allowedHost("accounts.google.com",false),"Account endpoint excluded");
        check(YoutubeHttp.allowedHost("youtubei.googleapis.com",false),"Metadata endpoint");
        MediaItem saved=MediaItem.youtube(canonical,true,null,640,360);
        check(saved.isYoutubeDataSaver() && saved.getPlatform()==SocialPlatform.YOUTUBE,"Quality captured in immutable item");
        check(!new MediaItem("https://example.invalid/media",true,null).isYoutubeDataSaver(),"Existing media unaffected");
    }
    static void quality() throws Exception {
        YoutubeQuality.Track hd=track(1080,60,5000,false), low=track(360,30,1000,true), a=audio(128000,true);
        YoutubeQuality.Selection best=YoutubeQuality.select(Arrays.asList(low,hd,a),false);
        check(best.video==hd && best.audio==a,"HD must include sound");
        check(best.totalBytes()==5500,"Combined byte budget");
        check(YoutubeQuality.select(Arrays.asList(low,hd,a),true).video==low,"Saver prefers 360p");
        check(YoutubeQuality.select(Arrays.asList(low,hd),false).video==low,"No silent high-quality fallback");
        fails(()->YoutubeQuality.select(Arrays.asList(hd),false),"Silent video rejected");
        YoutubeQuality.Track translated=audio(256000,false);
        check(YoutubeQuality.select(Arrays.asList(hd,a,translated),false).audio==a,"Original audio preferred");
        YoutubeQuality.Track quiet=audio(48000,true);
        check(YoutubeQuality.select(Arrays.asList(hd,a,quiet),true).audio==quiet,"Saver picks lighter original audio");
        YoutubeQuality.Track av1=new YoutubeQuality.Track("https://media.googlevideo.com/4k","av01",3840,2160,60,9000000,401,9000,true,false,false);
        check(YoutubeQuality.select(Arrays.asList(hd,a,av1),false).video==hd,"Compatible AVC chosen over AV1");
        check(YoutubeQuality.select(Arrays.asList(track(720,30,3000,true),low),false).video.height==720,"Combined format valid");
        check(YoutubeQuality.select(Arrays.asList(track(720,30,3000,true),track(480,30,2000,true)),true).video.height==480,"Saver fallback if 360 absent");
        check(YoutubeQuality.select(Arrays.asList(track(1080,30,5000,false),hd,a),false).video==hd,"Frame-rate preference");
        fails(()->YoutubeQuality.select(Arrays.asList(track(1080,30,MediaValidator.MAX_VIDEO_BYTES,false),a),false),"Audio included in 2 GiB limit");
        fails(()->YoutubeQuality.select(Arrays.asList(track(1080,30,0,true)),false),"Unknown size excluded");
    }
    static final class Reply extends HttpURLConnection {
        final byte[] bytes; final int status; final String range; final long declared; boolean closed;
        Reply(byte[] bytes,int status,String range,long declared) throws Exception {
            super(new URL("https://media.googlevideo.com/test")); this.bytes=bytes;this.status=status;this.range=range;this.declared=declared;
        }
        public void connect() { }
        public boolean usingProxy(){return false;}
        public void disconnect(){closed=true;}
        public int getResponseCode(){return status;}
        public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}
        public String getHeaderField(String key){return "Content-Range".equalsIgnoreCase(key)?range:null;}
        public String getContentType(){return "video/mp4";}
        public long getContentLengthLong(){return declared;}
    }
    static byte[] mp4(int size) {
        byte[] bytes=new byte[size]; for(int i=0;i<size;i++) bytes[i]=(byte)i;
        bytes[0]=bytes[1]=bytes[2]=0;bytes[3]=24;
        System.arraycopy(new byte[]{'f','t','y','p','i','s','o','m'},0,bytes,4,8);return bytes;
    }
    static Reply reply(byte[] bytes,int status,String range,long length) throws IOException {
        try{return new Reply(bytes,status,range,length);}catch(Exception e){throw new IOException(e);}
    }
    static void transfers() throws Exception {
        check(YoutubeRange.expectedBytes(206,"bytes 8-15/20",8,15,20)==8,"Exact 206 range");
        check(YoutubeRange.expectedBytes(200,null,0,7,20)==20,"Initial 200 may deliver full file");
        fails(()->YoutubeRange.expectedBytes(200,null,8,15,20),"Later 200 cannot be appended");
        fails(()->YoutubeRange.expectedBytes(206,"bytes 0-7/20",8,15,20),"Overlapping range rejected");
        fails(()->YoutubeRange.expectedBytes(206,"bytes 8-15/21",8,15,20),"Changing total rejected");
        fails(()->YoutubeRange.expectedBytes(206,null,0,7,20),"Missing range rejected");
        fails(()->YoutubeRange.expectedBytes(200,null,0,-1,0),"Empty total rejected");
        fails(()->YoutubeRange.expectedBytes(403,null,0,7,20),"Denial never treated as media");
        byte[] data=mp4(8*1024*1024+64); AtomicInteger requests=new AtomicInteger(); List<Reply> replies=new ArrayList<>();
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        YoutubeTransfer.download(track(360,30,data.length,true),out,(url,headers)->{
            String[] limits=headers.get("Range").substring(6).split("-");
            int start=Integer.parseInt(limits[0]),end=Integer.parseInt(limits[1]);requests.incrementAndGet();
            Reply r=reply(Arrays.copyOfRange(data,start,end+1),206,"bytes "+start+"-"+end+"/"+data.length,end-start+1);replies.add(r);return r;
        });
        check(requests.get()==2,"Large file split into ordinary 8 MiB ranges");
        check(Arrays.equals(data,out.toByteArray()),"No duplication, loss or ordering change");
        check(replies.stream().allMatch(r->r.closed),"Every connection closed");
        byte[] small=mp4(100); Reply complete=reply(small,200,null,100);
        ByteArrayOutputStream full=new ByteArrayOutputStream();
        YoutubeTransfer.download(track(360,30,100,true),full,(u,h)->complete);
        check(Arrays.equals(small,full.toByteArray()),"Full response accepted only as full file");
        Reply truncated=reply(mp4(70),206,"bytes 0-99/100",100);
        fails(()->YoutubeTransfer.download(track(360,30,100,true),new ByteArrayOutputStream(),(u,h)->truncated),"Truncated file rejected");
        check(truncated.closed,"Truncation closes connection");
        fails(()->YoutubeTransfer.download(track(360,30,100,true),new ByteArrayOutputStream(),
                (u,h)->reply(mp4(101),206,"bytes 0-99/100",-1)),"Undeclared extra bytes rejected");
        fails(()->YoutubeTransfer.download(track(360,30,100,true),new ByteArrayOutputStream(),
                (u,h)->reply(new byte[100],206,"bytes 0-99/100",100)),"HTML/non-MP4 cannot be published");
        fails(()->YoutubeTransfer.download(track(360,30,100,true),new ByteArrayOutputStream(),
                (u,h)->reply(small,206,"bytes 0-99/100",99)),"Wrong Content-Length rejected");
        Thread.currentThread().interrupt();
        try { fails(()->YoutubeTransfer.download(track(360,30,100,true),new ByteArrayOutputStream(),
                (u,h)->{throw new AssertionError("Cancelled task opened a connection");}),"Cancellation before network"); }
        finally {Thread.interrupted();}
    }
    static void cleanupAndProgress() throws Exception {
        Path temp=Files.createTempDirectory("youtube-work-test-");
        try {
            File a=YoutubeWorkFiles.folder(temp.toFile(),"a"),b=YoutubeWorkFiles.folder(temp.toFile(),"b");
            a.mkdir();b.mkdir();Files.write(new File(a,"video.part").toPath(),new byte[]{1});Files.write(new File(b,"audio.part").toPath(),new byte[]{2});
            YoutubeWorkFiles.clean(temp.toFile(),a.getPath());
            check(!a.exists() && new File(b,"audio.part").exists(),"Cleanup isolated by task");
            YoutubeWorkFiles.clean(temp.toFile(),a.getPath());check(!a.exists(),"Missing stage recovery idempotent");
            Files.write(new File(b,"unrelated.txt").toPath(),new byte[]{3});
            fails(()->YoutubeWorkFiles.clean(temp.toFile(),b.getPath()),"Unexpected file preserved");
            check(new File(b,"audio.part").exists(),"Validate all files before deleting any");
            fails(()->YoutubeWorkFiles.folder(temp.toFile(),"../b"),"Invalid task ID rejected");
            Path link=temp.resolve("youtube-link"); Files.createSymbolicLink(link,b.toPath());
            fails(()->YoutubeWorkFiles.clean(temp.toFile(),link.toString()),"Symlink to another task rejected");
            long[] progress={0,0};DownloadControl control=new DownloadControl((n,t)->{progress[0]=n;progress[1]=t;});
            control.attach();
            try {
                control.beginAttempt();control.startPhase("Vídeo",100);control.wrap(new ByteArrayOutputStream()).write(new byte[10]);
                check(progress[0]==10 && progress[1]==100,"Video phase bytes");
                control.startPhase("Sonido",50);check(progress[0]==0 && progress[1]==50,"Independent audio phase resets total");
                check("Sonido".equals(control.phase()) && DownloadControl.current()==control,"Phase belongs to attached worker");
                control.beginAttempt();check(control.phase()==null,"Retry clears stale phase");
            } finally {control.detach();}
            check(DownloadControl.current()==null,"No control leak into next task");
        } finally {
            // Only this test invocation's fixtures; never follow symlinks.
            try(java.util.stream.Stream<Path> paths=Files.walk(temp)) {
                for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }
    public static void main(String[] args) throws Exception {
        links();quality();transfers();cleanupAndProgress();
        System.out.println("YoutubeModuleTest: "+checks+" comprobaciones");
    }
}
