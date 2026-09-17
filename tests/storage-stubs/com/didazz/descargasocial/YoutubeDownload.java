// Test transport for staging/cleanup only; never packaged in the APK.
package com.didazz.descargasocial;
import java.io.*;
public final class YoutubeDownload {
    public interface Writer { void write(MediaItem item,OutputStream output,File directory) throws Exception; }
    public static volatile Writer writer;
    static void downloadToStream(MediaItem item,OutputStream output,File directory) throws Exception {
        writer.write(item,output,directory);
    }
}
