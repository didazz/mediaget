// Test double for isolated storage regression tests, not an Android emulator.
package android.content;
public class Context { public static final int MODE_PRIVATE=0;
private final SharedPreferences preferences=new SharedPreferences();
private final ContentResolver resolver=new ContentResolver();
public Context getApplicationContext(){return this;}
public SharedPreferences getSharedPreferences(String name,int mode){return preferences;}
public ContentResolver getContentResolver(){return resolver;}
public java.io.File getCacheDir(){java.io.File f=new java.io.File(android.os.Environment.root,"cache"); f.mkdirs(); return f;}
}
