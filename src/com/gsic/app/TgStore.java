package com.gsic.app;

import android.content.Context;

/**
 * Telegram Bot 配置存储（App 私有 SharedPreferences）。
 * token 只存本地、只发往 api.telegram.org，界面脱敏显示，绝不打日志。
 * owner 为绑定的主人 chatId（/start 首次绑定），只接受主人的指令。
 * v3.13 Issue 2：token 改走 Keystore 加密存储。
 */
public class TgStore {

    private final SecurePrefs pref;

    public TgStore(Context ctx) {
        pref = new SecurePrefs(ctx, "tgbot");
    }

    public void saveToken(String token) {
        pref.putSecure("token", token == null ? "" : token.trim());
    }

    public String getToken() {
        return pref.getSecure("token", "");
    }

    public boolean hasToken() {
        return !getToken().isEmpty();
    }

    public String getMaskedToken() {
        String t = getToken();
        if (t.length() <= 8) return "****";
        return t.substring(0, 4) + "****" + t.substring(t.length() - 4);
    }

    public long getOwner() {
        return pref.getLong("owner", 0);
    }

    public boolean hasOwner() {
        return getOwner() != 0;
    }

    public void bindOwner(long chatId) {
        pref.putLong("owner", chatId);
    }

    public void unbindOwner() {
        pref.remove("owner");
    }
}
