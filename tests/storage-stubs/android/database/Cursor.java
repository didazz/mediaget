// Test double for isolated storage regression tests, not an Android emulator.
package android.database;
public class Cursor implements AutoCloseable { private final boolean found; private final int number;
public Cursor(boolean found, int number) { this.found=found; this.number=number; }
public boolean moveToFirst() { return found; } public int getInt(int column) { return number; }
public void close() {} }
