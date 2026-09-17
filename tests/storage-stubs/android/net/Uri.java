// Test double for isolated storage regression tests, not an Android emulator.
package android.net;
public final class Uri { private final java.net.URI uri;
private Uri(String text) { uri = java.net.URI.create(text); }
public static Uri parse(String text) { return new Uri(text); }
public String getScheme() { return uri.getScheme(); } public String getAuthority() { return uri.getAuthority(); }
public String getPath() { return uri.getPath(); } public String toString() { return uri.toString(); } }
