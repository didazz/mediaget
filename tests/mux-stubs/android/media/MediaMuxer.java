// Test sequencing and resource ownership only; does not encode an MP4.
package android.media;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
public final class MediaMuxer {
    public static final class OutputFormat {public static final int MUXER_OUTPUT_MPEG_4=0;}
    public static boolean failCreate,failWrite,failStop,failRelease,released,stopped;
    public static Runnable onWrite;
    public static final List<Long> video=new ArrayList<>(),audio=new ArrayList<>();
    public static int tracks;private final String path;
    public static void reset(){failCreate=failWrite=failStop=failRelease=released=stopped=false;onWrite=null;video.clear();audio.clear();tracks=0;}
    public MediaMuxer(String path,int format) throws IOException {
        if(failCreate)throw new IOException("Injected output open failure");this.path=path;
    }
    public int addTrack(MediaFormat format){return tracks++;}
    public void start(){if(tracks!=2)throw new AssertionError("Must include both tracks");}
    public void writeSampleData(int index,ByteBuffer buffer,MediaCodec.BufferInfo info){
        if(failWrite)throw new IllegalStateException("Injected sample write failure");
        if(info.offset!=0 || info.size!=1)throw new AssertionError("Wrong sample window");
        (index==0?video:audio).add(info.presentationTimeUs);if(onWrite!=null)onWrite.run();
    }
    public void stop(){
        if(failStop)throw new IllegalStateException("Injected stop failure");
        try(FileOutputStream out=new FileOutputStream(path)){out.write(new byte[32]);}
        catch(IOException e){throw new IllegalStateException(e);}stopped=true;
    }
    public void release(){released=true;if(failRelease)throw new IllegalStateException("Injected muxer release failure");}
}
