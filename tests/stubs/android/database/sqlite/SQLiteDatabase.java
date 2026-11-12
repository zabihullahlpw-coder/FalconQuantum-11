package android.database.sqlite;
import android.database.Cursor;import android.content.ContentValues;
/** Deliberately fails if accidentally used. SQL migrations are tested with real Python sqlite3. */
public class SQLiteDatabase {
 public static final int CONFLICT_IGNORE=4,CONFLICT_REPLACE=5;
 public void execSQL(String s){throw new UnsupportedOperationException("No Android SQLite in JVM fixture");}
 public Cursor rawQuery(String s,String[] a){throw new UnsupportedOperationException();}
 public long insert(String t,String n,ContentValues v){throw new UnsupportedOperationException();}
 public long insertWithOnConflict(String t,String n,ContentValues v,int a){throw new UnsupportedOperationException();}
 public int update(String t,ContentValues v,String w,String[] a){throw new UnsupportedOperationException();}
 public int delete(String t,String w,String[] a){throw new UnsupportedOperationException();}
 public void beginTransaction(){throw new UnsupportedOperationException();}
 public void setTransactionSuccessful(){throw new UnsupportedOperationException();}
 public void endTransaction(){throw new UnsupportedOperationException();}
}
