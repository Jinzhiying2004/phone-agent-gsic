package com.gsic.app;

import android.content.Context;
import android.provider.Settings;
import java.util.UUID;

/**
 * v3.11 Issue 1：远控 Token 管理
 * - 首次启动生成，存 SharedPreferences("agent")
 * - 支持有效期配置（7/30天/永久），到期自动轮换
 * - 绑定设备指纹（ANDROID_ID），换机/重装自动失效
 * - 一键吊销
 * - 日志脱敏（只显示后4位）
 * v3.13 Issue 2：token 本体改走 Keystore 加密存储。
 */
public class TokenManager {

    private static final String PREF = "agent";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_TOKEN_TIME = "token_time";
    private static final String KEY_TOKEN_DEVICE = "token_device";
    private static final String KEY_TOKEN_EXPIRY = "token_expiry_days";

    /** 有效期选项：0=永久，7=7天，30=30天 */
    public static final int EXPIRY_NEVER = 0;
    public static final int EXPIRY_7_DAYS = 7;
    public static final int EXPIRY_30_DAYS = 30;

    private final Context ctx;
    private final SecurePrefs sp;

    public TokenManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.sp = new SecurePrefs(this.ctx, PREF);
    }

    /** 获取当前有效 token（自动处理过期轮换和设备变更） */
    public synchronized String getToken() {
        String token = sp.getSecure(KEY_TOKEN, null);
        long tokenTime = sp.getLong(KEY_TOKEN_TIME, 0);
        String tokenDevice = sp.getString(KEY_TOKEN_DEVICE, null);
        String curDevice = getDeviceId();

        // 设备变了 → 失效重发
        if (token != null && tokenDevice != null && !tokenDevice.equals(curDevice)) {
            token = null;
        }

        // 过期检查
        int expiryDays = sp.getInt(KEY_TOKEN_EXPIRY, EXPIRY_NEVER);
        if (token != null && expiryDays > 0 && tokenTime > 0) {
            long ageMs = System.currentTimeMillis() - tokenTime;
            if (ageMs > (long) expiryDays * 24 * 3600 * 1000) {
                token = null; // 过期，重新生成
            }
        }

        if (token == null) {
            token = generateToken();
            sp.putSecure(KEY_TOKEN, token);
            sp.putLong(KEY_TOKEN_TIME, System.currentTimeMillis());
            sp.putString(KEY_TOKEN_DEVICE, curDevice);
        }
        return token;
    }

    /** 一键吊销：清除 token，下次 getToken() 会重新生成 */
    public synchronized void revoke() {
        sp.remove(KEY_TOKEN);
        sp.remove(KEY_TOKEN_TIME);
        sp.remove(KEY_TOKEN_DEVICE);
    }

    /** 手动轮换：立即生成新 token */
    public synchronized String rotate() {
        revoke();
        return getToken();
    }

    /** 设置有效期（天数，0=永久） */
    public void setExpiryDays(int days) {
        sp.putInt(KEY_TOKEN_EXPIRY, days);
    }

    public int getExpiryDays() {
        return sp.getInt(KEY_TOKEN_EXPIRY, EXPIRY_NEVER);
    }

    /** Token 剩余有效期（毫秒），永久返回 -1 */
    public long getRemainingMs() {
        int expiryDays = getExpiryDays();
        if (expiryDays <= 0) return -1;
        long tokenTime = sp.getLong(KEY_TOKEN_TIME, 0);
        if (tokenTime == 0) return -1;
        long expiryMs = tokenTime + (long) expiryDays * 24 * 3600 * 1000;
        return Math.max(0, expiryMs - System.currentTimeMillis());
    }

    /** 脱敏显示：只显示后4位 */
    public static String mask(String token) {
        if (token == null || token.length() < 8) return "****";
        return "****" + token.substring(token.length() - 4);
    }

    private String generateToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String getDeviceId() {
        try {
            String id = Settings.Secure.getString(
                    ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
            return id != null ? id : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}
