package com.gsic.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 热补丁管理（Nuwa 式整类替换）：
 * - 启动时 tryLoadPatch：校验通过则注入补丁 dex
 * - 收到推送时 stagePatch：校验 meta（appCode 一致 + sha256）→ 原子落盘
 * - 生效靠 scheduleRestart 重启 App（attachBaseContext 重新注入）
 *
 * 补丁包格式 patch-<appCode>-<tag>.zip：
 *   patch.dex  —— 仅含变更类的 dex（d8 差分编译）
 *   meta.json  —— {"appCode":69,"tag":"p1","sha256":"…","classes":[…]}
 *
 * 限制：只能热修复 dex 变更；AndroidManifest / res / assets 变更必须整包安装，
 * make_patch.py 会在检测到这些变更时直接拒绝打补丁。
 */
public final class PatchManager {

    private static final String TAG = "PatchManager";
    private static final String DIR = "patches";
    private static final String DEX = "patch.dex";
    private static final String META = "applied.json";
    private static final String BACKUP_DEX = "backup/patch.dex.bak";
    private static final String BACKUP_META = "backup/applied.json.bak";

    /**
     * v3.13 Issue 3：补丁签名验签公钥（RSA-2048 裸公钥 DER）。
     * 私钥由开发者离线保管（~/.patch_keys/），make_patch.py 打包时签名。
     */
    private static final String PATCH_PUBKEY_B64 =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAst7wbMohj+lFvYwHNw4Zu3+Aa2uWsMaVj5ObR4r4t3tgoVgBoCGOTICtblQj1N4s4lltRXCfQxvoxIf7jTtG8NnCJnM+GfRaRicYuIST7/ElTtwm8RfL17/O5iv2LBIwaFkGSS3viLQMbB6tEJDnpOX2KDYf94vLdASO9jEIxAQugZteolkm+LrWmLCx13OaFruKvmr+2Zr8LuXZyiPBe7iyUHbFbOzpvnz5FTFpcKWOGKE4i/y6O5NFvHMjOKZKHbv+491Ev9+Wlc2HSnOX8rpf6Sx5/UtgW0wNQJi/4bB4DMAhALSySSkU1k07m8Zr+4kkwyxqGl/Q0li1eVT5vwIDAQAB";

    private PatchManager() {
    }

    public static final class PatchResult {
        public final boolean ok;
        public final String error;
        public final String tag;

        PatchResult(boolean ok, String error, String tag) {
            this.ok = ok;
            this.error = error;
            this.tag = tag;
        }
    }

    /** 启动时调用：有已应用且校验通过的补丁则注入 */
    public static void tryLoadPatch(Context ctx) {
        // v3.13 Issue 3：禁用开关
        try {
            if (!ctx.getSharedPreferences("agent", Context.MODE_PRIVATE)
                    .getBoolean("patch_enabled", true)) {
                Log.i(TAG, "热补丁已禁用，跳过加载");
                return;
            }
        } catch (Exception ignored) {
        }
        try {
            File dir = new File(ctx.getFilesDir(), DIR);
            File dex = new File(dir, DEX);
            File meta = new File(dir, META);
            if (!dex.exists() || !meta.exists()) return;
            JSONObject m = new JSONObject(readFile(meta));
            int appCode = m.optInt("appCode", -1);
            if (appCode != UpdateManager.currentCode(ctx)) {
                Log.i(TAG, "补丁 appCode=" + appCode + " 与当前版本不一致，跳过");
                return;
            }
            String expect = m.optString("sha256", "");
            if (expect.isEmpty() || !expect.equalsIgnoreCase(sha256(dex))) {
                Log.w(TAG, "补丁 sha256 校验失败，跳过加载");
                return;
            }
            DexInjector.injectFirst(ctx, dex);
            String tag = m.optString("tag", "");
            ctx.getSharedPreferences("patch", Context.MODE_PRIVATE).edit()
                    .putString("loaded_tag", tag).apply();
            Log.i(TAG, "热补丁已加载: " + tag);
        } catch (Exception e) {
            // 反射被系统限制等情况：降级为不加载补丁，保证 App 能启动
            Log.w(TAG, "补丁加载失败，已降级: " + e);
        }
    }

    /** 当前已加载的补丁 tag（无补丁返回空串） */
    public static String loadedTag(Context ctx) {
        return ctx.getSharedPreferences("patch", Context.MODE_PRIVATE)
                .getString("loaded_tag", "");
    }

    /** 校验并落盘推送来的补丁包 */
    public static PatchResult stagePatch(Context ctx, byte[] zipBytes) {
        if (zipBytes == null || zipBytes.length < 100) {
            return new PatchResult(false, "补丁包为空或过小", null);
        }
        try {
            File dir = new File(ctx.getFilesDir(), DIR);
            if (!dir.exists()) dir.mkdirs();

            byte[] dexBytes = null;
            String metaStr = null;
            try (ZipInputStream zin = new ZipInputStream(
                    new java.io.ByteArrayInputStream(zipBytes))) {
                ZipEntry e;
                byte[] buf = new byte[65536];
                while ((e = zin.getNextEntry()) != null) {
                    String n = e.getName();
                    // 防路径穿越：只接受顶层两个固定文件名
                    if (!"patch.dex".equals(n) && !"meta.json".equals(n)) {
                        zin.closeEntry();
                        continue;
                    }
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    int r;
                    while ((r = zin.read(buf)) > 0) bos.write(buf, 0, r);
                    if ("patch.dex".equals(n)) dexBytes = bos.toByteArray();
                    else metaStr = bos.toString("UTF-8");
                    zin.closeEntry();
                }
            }
            if (dexBytes == null || metaStr == null) {
                return new PatchResult(false, "补丁包缺少 patch.dex 或 meta.json", null);
            }
            JSONObject m = new JSONObject(metaStr);
            int appCode = m.optInt("appCode", -1);
            if (appCode != UpdateManager.currentCode(ctx)) {
                return new PatchResult(false,
                        "补丁 appCode=" + appCode + " 与当前版本不匹配", null);
            }
            String tag = m.optString("tag", "");
            if (tag.isEmpty()) {
                return new PatchResult(false, "补丁缺少 tag", null);
            }
            if (!sha256(dexBytes).equalsIgnoreCase(m.optString("sha256", ""))) {
                return new PatchResult(false, "补丁 sha256 校验失败", null);
            }
            // v3.13 Issue 3：RSA 签名校验（fail-closed，无签名直接拒绝）
            String sig = m.optString("sig", "");
            if (!verifySignature(tag, m.optString("sha256", ""), sig)) {
                return new PatchResult(false, "补丁签名校验失败（非官方补丁）", null);
            }
            // v3.13 Issue 3：落盘前备份旧补丁，供回滚
            backupCurrent(dir);
            // 原子落盘：先写 tmp 再改名
            writeFile(new File(dir, DEX + ".tmp"), dexBytes);
            writeFile(new File(dir, META + ".tmp"), metaStr.getBytes("UTF-8"));
            if (!new File(dir, DEX + ".tmp").renameTo(new File(dir, DEX))
                    || !new File(dir, META + ".tmp").renameTo(new File(dir, META))) {
                return new PatchResult(false, "补丁落盘失败", null);
            }
            try {
                new BehaviorLog(ctx).log(BehaviorLog.SYS, "热补丁",
                        "收到补丁 " + tag + "，校验通过，准备重启生效");
            } catch (Exception ignored) {
            }
            return new PatchResult(true, null, tag);
        } catch (Exception e) {
            return new PatchResult(false, "应用补丁失败: " + e.getMessage(), null);
        }
    }

    /**
     * v3.13 Issue 3：RSA 签名校验。
     * 签名内容 = tag + ":" + sha256(dex)，算法 SHA256withRSA。
     */
    public static boolean verifySignature(String tag, String sha256hex,
                                          String sigB64) {
        if (tag == null || sha256hex == null || sigB64 == null
                || sigB64.isEmpty()) {
            return false;
        }
        try {
            byte[] pubDer = android.util.Base64.decode(PATCH_PUBKEY_B64,
                    android.util.Base64.DEFAULT);
            java.security.spec.X509EncodedKeySpec spec =
                    new java.security.spec.X509EncodedKeySpec(pubDer);
            java.security.PublicKey pubKey =
                    java.security.KeyFactory.getInstance("RSA").generatePublic(spec);
            java.security.Signature sig =
                    java.security.Signature.getInstance("SHA256withRSA");
            sig.initVerify(pubKey);
            String payload = tag + ":" + sha256hex.toLowerCase();
            sig.update(payload.getBytes("UTF-8"));
            byte[] sigBytes = android.util.Base64.decode(sigB64,
                    android.util.Base64.DEFAULT);
            return sig.verify(sigBytes);
        } catch (Exception e) {
            Log.w(TAG, "签名校验异常: " + e);
            return false;
        }
    }

    /** v3.13 Issue 3：落盘前备份当前补丁 */
    private static void backupCurrent(File dir) {
        try {
            File bakDir = new File(dir, "backup");
            if (!bakDir.exists()) bakDir.mkdirs();
            copyIfExists(new File(dir, DEX), new File(dir, BACKUP_DEX));
            copyIfExists(new File(dir, META), new File(dir, BACKUP_META));
        } catch (Exception e) {
            Log.w(TAG, "备份旧补丁失败: " + e);
        }
    }

    private static void copyIfExists(File src, File dst) throws Exception {
        if (!src.exists()) return;
        try (java.io.InputStream in = new java.io.FileInputStream(src);
             java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
    }

    /**
     * v3.13 Issue 3：一键回滚到上一个补丁。
     * 返回 null 表示成功，否则返回错误原因。
     */
    public static String rollbackPatch(Context ctx) {
        try {
            File dir = new File(ctx.getFilesDir(), DIR);
            File bakDex = new File(dir, BACKUP_DEX);
            File bakMeta = new File(dir, BACKUP_META);
            if (!bakDex.exists()) {
                return "没有可回滚的备份";
            }
            copyIfExists(bakDex, new File(dir, DEX));
            if (bakMeta.exists()) {
                copyIfExists(bakMeta, new File(dir, META));
            } else {
                // noinspection ResultOfMethodCallIgnored
                new File(dir, META).delete();
            }
            try {
                new BehaviorLog(ctx).log(BehaviorLog.SYS, "热补丁", "已回滚到上一个补丁");
            } catch (Exception ignored) {
            }
            return null;
        } catch (Exception e) {
            return "回滚失败: " + e.getMessage();
        }
    }

    /** v3.13 Issue 3：是否有可回滚的备份 */
    public static boolean hasBackup(Context ctx) {
        return new File(new File(ctx.getFilesDir(), DIR), BACKUP_DEX).exists();
    }

    /** 排期重启 App 使补丁生效（先回响应再退进程） */
    /**
     * v3.22-hotfix：热更新后自动重启（双保险）。
     * 原逻辑只用 AlarmManager.set()，在 MIUI 神隐模式下会被延迟、
     * 进程杀掉后拉不起来。现在：
     * 1) 精确闹钟（setExactAndAllowWhileIdle + RTC_WAKEUP），MIUI 也能准时触发；
     * 2) 杀进程后靠前台服务 START_STICKY 由系统自动拉起（更可靠，不弹界面打扰用户）。
     * 任一生效即可；Activity 用 CLEAR_TOP 避免重复启动。
     */
    public static void scheduleRestart(Context ctx, long delayMs) {
        try {
            Intent launch = ctx.getPackageManager()
                    .getLaunchIntentForPackage(ctx.getPackageName());
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                PendingIntent pi = PendingIntent.getActivity(ctx, 0, launch,
                        PendingIntent.FLAG_CANCEL_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE);
                AlarmManager am = (AlarmManager) ctx.getSystemService(
                        Context.ALARM_SERVICE);
                if (am != null) {
                    long when = System.currentTimeMillis() + delayMs;
                    try {
                        am.setExactAndAllowWhileIdle(
                                AlarmManager.RTC_WAKEUP, when, pi);
                    } catch (Exception e) {
                        am.set(AlarmManager.RTC_WAKEUP, when, pi);
                    }
                    Log.i(TAG, "已排期精确重启闹钟");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "排期重启失败: " + e);
        }
    }

    /**
     * v3.22-hotfix：杀进程触发重启。调用后当前进程退出，
     * 系统会自动重启前台服务（START_STICKY），新进程在
     * attachBaseContext 重新注入补丁。比 System.exit 更彻底。
     */
    public static void killForRestart() {
        try {
            Log.i(TAG, "热更新完成，杀进程重启使补丁生效");
        } catch (Exception ignored) {
        }
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0); // 兜底
    }

    public static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
        }
        return hex(md.digest());
    }

    public static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(data);
        return hex(md.digest());
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 15, 16))
                .append(Character.forDigit(x & 15, 16));
        return sb.toString();
    }

    private static String readFile(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            return bos.toString("UTF-8");
        }
    }

    private static void writeFile(File f, byte[] data) throws Exception {
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(data);
        }
    }
}
