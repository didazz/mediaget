// Test contract only; never packaged in the APK.
package android.media;
public final class MediaCodec {
    public static final int BUFFER_FLAG_KEY_FRAME=1;
    public static final class BufferInfo {
        public int offset,size,flags;public long presentationTimeUs;
        public void set(int offset,int size,long pts,int flags){this.offset=offset;this.size=size;this.presentationTimeUs=pts;this.flags=flags;}
    }
}
