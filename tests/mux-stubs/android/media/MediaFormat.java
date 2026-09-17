// Test contract only; never packaged in the APK.
package android.media;
public final class MediaFormat {
    public static final String KEY_MIME="mime";
    private final String mime;
    public MediaFormat(String mime){this.mime=mime;}
    public String getString(String key){return mime;}
}
