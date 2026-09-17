// Test double for isolated storage regression tests, not an Android emulator.
package com.didazz.descargasocial;
import java.io.OutputStream;
/** Test transport only; never packaged in the APK. */
public final class SocialExtractor {
public interface Writer { void write(MediaItem item,OutputStream output) throws Exception; }
public static volatile Writer writer;
public static void downloadToStream(MediaItem item,OutputStream output) throws Exception { writer.write(item,output); }
}
