package com.gsic.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.io.File;

/**
 * v3.42 常驻自启入口：开机（BOOT_COMPLETED）、覆盖安装更新
 * （MY_PACKAGE_REPLACED）、看门狗闹钟（com.gsic.app.WATCHDOG）
 * 都从这里拉起 AgentService，不再需要用户手动打开 App。
 * 用户在 App 里手动点「停止服务」会留下 svc_off 标记文件，
 * 所有自启路径（含 Magisk root 守护脚本）都尊重该标记，不跟用户抢。
 */
public class BootReceiver extends BroadcastReceiver {

    public static boolean userStopped(Context ctx) {
        try {
            return new File(ctx.getFilesDir(), "svc_off").exists();
        } catch (Exception e) {
            return false;
        }
    }

    public static void setUserStopped(Context ctx, boolean stopped) {
        try {
            File f = new File(ctx.getFilesDir(), "svc_off");
            if (stopped) {
                if (!f.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.createNewFile();
                }
            } else if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        } catch (Exception ignored) {
        }
    }

    // v3.52 地基·自启动自验：开机回执——BOOT_COMPLETED 落时间戳，
    // AgentService 起来后确认，形成「开机→自启成功」证据链，自检卡可见，
    // 不再只靠"应该起来了"（假生效教训：地基能力必须自带证据）
    public static void noteBoot(Context ctx) {
        try {
            ctx.getSharedPreferences("agent", Context.MODE_PRIVATE).edit()
                    .putLong("boot_ts", System.currentTimeMillis()).apply();
        } catch (Exception ignored) {
        }
    }

    public static void noteServiceUp(Context ctx) {
        try {
            android.content.SharedPreferences p =
                    ctx.getSharedPreferences("agent", Context.MODE_PRIVATE);
            long boot = p.getLong("boot_ts", 0);
            long now = System.currentTimeMillis();
            android.content.SharedPreferences.Editor e = p.edit();
            e.putLong("svc_up_ts", now);
            if (boot > p.getLong("boot_ok_ts", 0) && now - boot < 15 * 60 * 1000L) {
                e.putLong("boot_ok_ts", now);
            }
            e.apply();
        } catch (Exception ignored) {
        }
    }

    public static String receiptText(Context ctx) {
        try {
            android.content.SharedPreferences p =
                    ctx.getSharedPreferences("agent", Context.MODE_PRIVATE);
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(
                    "MM-dd HH:mm", java.util.Locale.getDefault());
            long ok = p.getLong("boot_ok_ts", 0);
            if (ok > 0) return "🚀 开机自启：上次成功 " + f.format(new java.util.Date(ok));
            long boot = p.getLong("boot_ts", 0);
            if (boot > 0) return "🚀 开机自启：已收到开机广播 "
                    + f.format(new java.util.Date(boot)) + "，服务确认待验证";
            return "🚀 开机自启：暂无开机回执（装后未重启过）";
        } catch (Exception e) {
            return "🚀 开机自启：读取失败";
        }
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            if (intent != null
                    && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                noteBoot(ctx);
            }
        } catch (Exception ignored) {
        }
        if (userStopped(ctx)) return;
        if (AgentService.isRunning()) return;
        try {
            Intent i = new Intent(ctx, AgentService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Exception ignored) {
        }
    }
}
