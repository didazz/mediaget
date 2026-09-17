// Simulates the documented permission boundary, NOT Android's native MP4 reader.
package android.media;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
public final class MediaExtractor {
    public static final int SAMPLE_FLAG_SYNC=1,SAMPLE_FLAG_ENCRYPTED=2;
    public static final long[] VIDEO_TIMES={0,66666,33333,100000},AUDIO_TIMES={0,23219,46438,69657,92876};
    public static final List<MediaExtractor> instances=new ArrayList<>();
    public static final List<FileDescriptor> descriptors=new ArrayList<>();
    public static int paths,failOpen=-1,failRelease=-1;
    public static boolean omitFinalAudio,encrypted;
    public static long[] lengths;
    public final int id=instances.size();public boolean released;private int sample;
    public MediaExtractor(){instances.add(this);}
    public static void reset(long videoSize,long audioSize){
        instances.clear();descriptors.clear();paths=0;failOpen=-1;failRelease=-1;omitFinalAudio=false;encrypted=false;
        lengths=new long[]{videoSize,audioSize,32};
    }
    public void setDataSource(String path) throws IOException {
        paths++;
        throw new IOException("Other process cannot read the app's private path");
    }
    public void setDataSource(FileDescriptor fd,long offset,long length) throws IOException {
        if(!fd.valid() || offset!=0 || length!=lengths[id])throw new AssertionError("Expected an open, bounded descriptor");
        descriptors.add(fd);
        if(id==failOpen)throw new IOException("Injected local open failure");
    }
    public int getTrackCount(){return id==2 && !omitFinalAudio?2:1;}
    public MediaFormat getTrackFormat(int track){return new MediaFormat(id==1 || id==2 && track==1?"audio/mp4a-latm":"video/avc");}
    public Map<Object,Object> getPsshInfo(){return Collections.emptyMap();}
    public void selectTrack(int i){}
    public long getSampleTime(){long[] times=id==0?VIDEO_TIMES:AUDIO_TIMES;return sample<times.length?times[sample]:-1;}
    public int readSampleData(ByteBuffer b,int offset){b.put(offset,(byte)id);return 1;}
    public int getSampleFlags(){return encrypted?SAMPLE_FLAG_ENCRYPTED:sample==0?SAMPLE_FLAG_SYNC:0;}
    public boolean advance(){sample++;return getSampleTime()>=0;}
    public void release(){released=true;if(id==failRelease)throw new IllegalStateException("Injected extractor release failure");}
}
