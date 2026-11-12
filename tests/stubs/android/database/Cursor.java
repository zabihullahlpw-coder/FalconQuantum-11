package android.database;
public interface Cursor {
 boolean moveToFirst();boolean moveToNext();long getLong(int i);int getInt(int i);double getDouble(int i);String getString(int i);boolean isNull(int i);int getColumnCount();String getColumnName(int i);void close();
}
