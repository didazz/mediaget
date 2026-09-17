// Stub only for compiling the native mux method in isolation; never used by these tests or the APK.
package com.didazz.descargasocial;
final class YoutubeExtractor {
    static final class Plan {YoutubeQuality.Selection selection;}
    static Plan resolve(String url,boolean saver,boolean fresh){throw new AssertionError("No network in native mux contract test");}
}
