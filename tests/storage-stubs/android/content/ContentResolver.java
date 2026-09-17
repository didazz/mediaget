// Test double for isolated storage regression tests, not an Android emulator.
package android.content;
import java.io.*;
import java.util.*;
import android.net.Uri;
import android.database.Cursor;
import android.provider.MediaStore;
public class ContentResolver {
public static final class Row { public final ContentValues values; public final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
Row(ContentValues values) { this.values=new ContentValues(); this.values.putAll(values); } }
public final Map<String,Row> rows=new LinkedHashMap<>();
private long serial;
public synchronized Uri insert(Uri collection,ContentValues values) {
Uri uri=Uri.parse(collection.toString()+"/"+(++serial)); rows.put(uri.toString(),new Row(values)); return uri; }
public synchronized Cursor query(Uri uri,String[] projection,String selection,String[] args,String order) {
if(selection!=null) { for(Row row:rows.values()) {
if(args[0].equals(row.values.get(MediaStore.MediaColumns.RELATIVE_PATH))
&&args[1].equals(row.values.get(MediaStore.MediaColumns.DISPLAY_NAME))) return new Cursor(true,1);
} return new Cursor(false,0); }
Row row=rows.get(uri.toString());
return new Cursor(row!=null,row==null?0:(Integer)row.values.get(MediaStore.MediaColumns.IS_PENDING)); }
public synchronized OutputStream openOutputStream(Uri uri,String mode) { return rows.get(uri.toString()).bytes; }
public synchronized int update(Uri uri,ContentValues values,String selection,String[] args) {
Row row=rows.get(uri.toString()); if(row==null) return 0; row.values.putAll(values); return 1; }
public synchronized int delete(Uri uri,String selection,String[] args) { return rows.remove(uri.toString())==null?0:1; }
public synchronized java.util.List<Row> snapshot() { return new ArrayList<>(rows.values()); }
}
