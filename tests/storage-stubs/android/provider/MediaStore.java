// Test double for isolated storage regression tests, not an Android emulator.
package android.provider;
import android.net.Uri;
public final class MediaStore { public static final String VOLUME_EXTERNAL_PRIMARY="external_primary";
public static final class MediaColumns { public static final String IS_PENDING="pending",DISPLAY_NAME="name",
MIME_TYPE="mime",RELATIVE_PATH="path",_ID="id"; }
public static final class Downloads { public static Uri getContentUri(String volume) {
return Uri.parse("content://media/"+volume+"/downloads"); } } }
