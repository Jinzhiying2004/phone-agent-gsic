package com.gsic.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * v3.13 Issue 2：透明加解密的 SharedPreferences 包装。
 * putSecure/getSecure 针对敏感字符串；普通字段走原方法。
 * 读取时自动兼容历史明文（无 ENC: 前缀直接返回）。
 */
public class SecurePrefs {

    private final SharedPreferences sp;

    public SecurePrefs(Context ctx, String name) {
        sp = ctx.getApplicationContext()
                .getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    public void putSecure(String key, String value) {
        sp.edit().putString(key, CryptoUtil.encrypt(value)).apply();
    }

    public String getSecure(String key, String def) {
        String v = sp.getString(key, null);
        if (v == null) return def;
        return CryptoUtil.decrypt(v);
    }

    public boolean hasSecure(String key) {
        return sp.contains(key);
    }

    // ---- 透传普通方法 ----
    public void putString(String k, String v) {
        sp.edit().putString(k, v).apply();
    }

    public String getString(String k, String def) {
        return sp.getString(k, def);
    }

    public void putInt(String k, int v) {
        sp.edit().putInt(k, v).apply();
    }

    public int getInt(String k, int def) {
        return sp.getInt(k, def);
    }

    public void putLong(String k, long v) {
        sp.edit().putLong(k, v).apply();
    }

    public long getLong(String k, long def) {
        return sp.getLong(k, def);
    }

    public void putBoolean(String k, boolean v) {
        sp.edit().putBoolean(k, v).apply();
    }

    public boolean getBoolean(String k, boolean def) {
        return sp.getBoolean(k, def);
    }

    public void remove(String k) {
        sp.edit().remove(k).apply();
    }

    public SharedPreferences raw() {
        return sp;
    }
}
