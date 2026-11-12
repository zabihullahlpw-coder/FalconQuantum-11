package com.falcon.quantum;

import android.content.Context;
/** Test-only in-memory stand-in. The Android Keystore implementation is not used on the JVM. */
public final class SecureKeys {
    private SecureKeys() {}
    public static void save(Context ctx,String env,String key,String secret) {
        if(!"DEMO".equals(env)&&!"LIVE".equals(env)) throw new IllegalArgumentException();
        if(key.length()==0||secret.length()==0) throw new IllegalArgumentException();
        ctx.getSharedPreferences("test-vault",0).edit().putString(env+"_key",key).putString(env+"_secret",secret).commit();
    }
    public static String[] load(Context ctx,String env) {
        if(!"DEMO".equals(env)&&!"LIVE".equals(env)) throw new IllegalArgumentException();
        return new String[]{ctx.getSharedPreferences("test-vault",0).getString(env+"_key",""),
            ctx.getSharedPreferences("test-vault",0).getString(env+"_secret","")};
    }
    public static void saveDesktopLink(Context ctx,String url) {
        ctx.getSharedPreferences("test-vault",0).edit().putString("pc_link",url).commit();
    }
    public static String loadDesktopLink(Context ctx) {
        return ctx.getSharedPreferences("test-vault",0).getString("pc_link","");
    }
    public static void clearDesktopLink(Context ctx) {
        ctx.getSharedPreferences("test-vault",0).edit().remove("pc_link").commit();
    }
    public static void clear(Context ctx,String env) {
        ctx.getSharedPreferences("test-vault",0).edit().remove(env+"_key").remove(env+"_secret").commit();
    }
}
