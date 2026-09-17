// Test double for isolated storage regression tests, not an Android emulator.
package android.content;
public class ContentValues extends java.util.HashMap<String,Object> {
private static final long serialVersionUID = 1;
public void put(String key, String value) { super.put(key,value); }
public void put(String key, Integer value) { super.put(key,value); }
}
