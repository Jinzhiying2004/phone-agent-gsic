package com.gsic.app;

import android.content.Context;

import java.util.Map;
import java.util.TreeMap;

/**
* v2.85：用户长期记忆（偏好/事实）。
* 存 SharedPreferences，智能体 via remember 动作写入，prompt 注入时读出。
*/
/** v3.11 Issue 8：加 listAll/clearAll 供记忆管理页 */
public class UserMemory {

private static final String PREF = "user_memory";

/** 写入一条记忆（v3.13 Issue 2：值加密存储） */
public static void put(Context ctx, String key, String value) {
if (key == null || key.isEmpty()) return;
new SecurePrefs(ctx, PREF).putSecure(key.trim(),
        value == null ? "" : value.trim());
}

/** 列出全部记忆（key → value） */
public static java.util.Map<String, String> listAll(Context ctx) {
    java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
    try {
        SecurePrefs sp = new SecurePrefs(ctx, PREF);
        java.util.Map<String, ?> all = sp.raw().getAll();
        for (java.util.Map.Entry<String, ?> e : all.entrySet()) {
            if (e.getValue() instanceof String) {
                // v3.13：解密（兼容历史明文）
                out.put(e.getKey(),
                        CryptoUtil.decrypt((String) e.getValue()));
            }
        }
    } catch (Exception ignored) {
    }
    return out;
}

/** 清空全部记忆 */
public static void clearAll(Context ctx) {
    new SecurePrefs(ctx, PREF).raw().edit().clear().apply();
}

/** 删除一条记忆 */
public static void remove(Context ctx, String key) {
if (key == null) return;
new SecurePrefs(ctx, PREF).remove(key.trim());
}

/** 读出全部记忆，拼成 prompt 用文本 */
public static String describeForPrompt(Context ctx) {
SecurePrefs helper = new SecurePrefs(ctx, PREF);
Map<String,?> all = helper.raw().getAll();
if (all.isEmpty()) return "";
StringBuilder sb = new StringBuilder();
sb.append("以下是对该用户的长期记忆，执行任务时必须利用：\n");
TreeMap<String, Object> sorted = new TreeMap<>();
for (Map.Entry<String,?> e: all.entrySet()) {
sorted.put(e.getKey(), e.getValue());
}
for (Map.Entry<String, Object> e: sorted.entrySet()) {
Object v = e.getValue();
// v3.13 Issue 2：解密（兼容历史明文）
String vs = v instanceof String ? CryptoUtil.decrypt((String) v) : String.valueOf(v);
sb.append("- ").append(e.getKey()).append("：").append(vs).append("\n");
}
sb.append("不要重复询问记忆中已有的信息。\n\n");
return sb.toString();
}
}
