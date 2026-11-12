package org.json;
import java.util.*;
public class JSONArray {
 private final List<Object> v=new ArrayList<Object>();
 public JSONArray(){}
 public JSONArray(String s){throw new UnsupportedOperationException("No network parsing in unit fixtures");}
 public JSONArray put(Object x){v.add(x);return this;}public int length(){return v.size();}
 public JSONObject getJSONObject(int i){return (JSONObject)v.get(i);}public JSONArray getJSONArray(int i){return (JSONArray)v.get(i);}
 public double getDouble(int i){return Double.parseDouble(String.valueOf(v.get(i)));}public long getLong(int i){return Long.parseLong(String.valueOf(v.get(i)));}
 public String optString(int i){try{return getString(i);}catch(Exception e){return "";}}
 public String getString(int i){return String.valueOf(v.get(i));}public String toString(){return v.toString();}public String toString(int n){return toString();}
}
