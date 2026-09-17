// Test double for isolated storage regression tests, not an Android emulator.
package android.content;
import java.util.*;
public class SharedPreferences {
private final Map<String,String> values = new HashMap<>();
public synchronized String getString(String key,String fallback) { return values.containsKey(key)?values.get(key):fallback; }
public synchronized Map<String,?> getAll() { return new HashMap<>(values); }
public Editor edit() { return new Editor(); }
public final class Editor {
private final Map<String,String> changes=new HashMap<>();
public Editor putString(String key,String value) { changes.put(key,value); return this; }
public Editor remove(String key) { changes.put(key,null); return this; }
public boolean commit() { synchronized(SharedPreferences.this) {
for(Map.Entry<String,String> e:changes.entrySet()) { if(e.getValue()==null) values.remove(e.getKey()); else values.put(e.getKey(),e.getValue()); }
} return true; } }
}
