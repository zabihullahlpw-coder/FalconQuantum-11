package org.json;
import java.util.*;
/** Minimal test adapter; Android provides the real org.json at runtime. */
public class JSONObject {
 public static final Object NULL=new Object();private final Map<String,Object> m=new LinkedHashMap<String,Object>();
 public JSONObject(){}
 public JSONObject(String text){throw new UnsupportedOperationException("Fixtures construct JSON objects directly; not a parser");}
 public JSONObject put(String k,Object v){m.put(k,v);return this;}
 public Object get(String k){if(!m.containsKey(k))throw new IllegalArgumentException(k);return m.get(k);}
 public boolean has(String k){return m.containsKey(k);}
 public String getString(String k){return String.valueOf(get(k));}
 public long getLong(String k){return Long.parseLong(getString(k));}
 public double getDouble(String k){return Double.parseDouble(getString(k));}
 public String optString(String k){return optString(k,"");}
 public String optString(String k,String d){Object v=m.get(k);return v==null?d:String.valueOf(v);}
 public double optDouble(String k,double d){try{return getDouble(k);}catch(Exception e){return d;}}
 public long optLong(String k,long d){try{return getLong(k);}catch(Exception e){return d;}}
 public long optLong(String k){return optLong(k,0);}
 public int optInt(String k,int d){return (int)optLong(k,d);}
 public boolean optBoolean(String k,boolean d){Object v=m.get(k);return v==null?d:Boolean.parseBoolean(String.valueOf(v));}
 public JSONArray optJSONArray(String k){Object v=m.get(k);return v instanceof JSONArray?(JSONArray)v:null;}
 public JSONObject optJSONObject(String k){Object v=m.get(k);return v instanceof JSONObject?(JSONObject)v:null;}
 public JSONArray getJSONArray(String k){return (JSONArray)get(k);}
 public String toString(){return m.toString();}public String toString(int n){return toString();}
}
