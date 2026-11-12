package android.content;
import java.util.*;
public class Context {
 public static final int MODE_PRIVATE=0;private final Map<String,SharedPreferences> prefs=new HashMap<String,SharedPreferences>();
 public SharedPreferences getSharedPreferences(String k,int mode){SharedPreferences p=prefs.get(k);if(p==null){p=new SharedPreferences();prefs.put(k,p);}return p;}
 public Context getApplicationContext(){return this;}
}
