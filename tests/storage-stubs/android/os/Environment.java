// Test double for isolated storage regression tests, not an Android emulator.
package android.os;
import java.io.File;
public final class Environment { public static final String DIRECTORY_DOWNLOADS = "Download";
public static File root;
public static File getExternalStoragePublicDirectory(String name) { return root; } }
