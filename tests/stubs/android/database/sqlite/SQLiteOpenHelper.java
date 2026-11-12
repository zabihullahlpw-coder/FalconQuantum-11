package android.database.sqlite;
import android.content.Context;
public abstract class SQLiteOpenHelper {
 public SQLiteOpenHelper(Context c,String n,Object f,int v){}
 public abstract void onCreate(SQLiteDatabase d);public abstract void onUpgrade(SQLiteDatabase d,int a,int b);
 public SQLiteDatabase getReadableDatabase(){throw new UnsupportedOperationException();}
 public SQLiteDatabase getWritableDatabase(){throw new UnsupportedOperationException();}
 public void close(){}
}
