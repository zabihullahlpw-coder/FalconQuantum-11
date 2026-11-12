package android.content;
import java.util.*;
/** Test-only preference adapter. Not packaged in Android builds. */
public class SharedPreferences {
 private final Map<String,Object> m=new HashMap<String,Object>();
 public String getString(String k,String d){Object v=m.get(k);return v==null?d:(String)v;}
 public boolean getBoolean(String k,boolean d){Object v=m.get(k);return v==null?d:(Boolean)v;}
 public int getInt(String k,int d){Object v=m.get(k);return v==null?d:((Number)v).intValue();}
 public long getLong(String k,long d){Object v=m.get(k);return v==null?d:((Number)v).longValue();}
 public float getFloat(String k,float d){Object v=m.get(k);return v==null?d:((Number)v).floatValue();}
 @SuppressWarnings("unchecked") public Set<String> getStringSet(String k,Set<String>d){Object v=m.get(k);return v==null?d:(Set<String>)v;}
 public Editor edit(){return new Editor();}
 public class Editor {
  private final Map<String,Object> values=new HashMap<String,Object>();private final Set<String> removed=new HashSet<String>();
  public Editor putString(String k,String v){values.put(k,v);return this;}
  public Editor putLong(String k,long v){values.put(k,v);return this;}
  public Editor putInt(String k,int v){values.put(k,v);return this;}
  public Editor putFloat(String k,float v){values.put(k,v);return this;}
  public Editor putBoolean(String k,boolean v){values.put(k,v);return this;}
  public Editor putStringSet(String k,Set<String>v){values.put(k,new HashSet<String>(v));return this;}
  public Editor remove(String k){removed.add(k);return this;}
  public boolean commit(){for(String k:removed)m.remove(k);m.putAll(values);return true;}
  public void apply(){commit();}
 }
}
