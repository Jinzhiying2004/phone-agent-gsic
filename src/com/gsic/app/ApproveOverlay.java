package com.gsic.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v3.14：全局审批悬浮窗。
 * 监管者/风险审批弹窗不再只在 App 内显示，而是走系统悬浮窗
 * （TYPE_APPLICATION_OVERLAY），任务在虚拟屏运行时用户在主屏也能看到并决策。
 *
 * 需要 SYSTEM_ALERT_WINDOW 权限；无权限时返回 null，由调用方降级处理。
 * 结果：1=允许一次，0=拒绝，-1=停止任务，-2=超时/异常。
 */
public class ApproveOverlay {

    public static final int ALLOW_ONCE = 1;
    public static final int DENY = 0;
    public static final int STOP_TASK = -1;
    public static final int FAILED = -2;

    /** 超时（秒）：用户长时间不决策则自动拒绝，避免任务无限卡住 */
    private static final long TIMEOUT_SEC = 120;

    public static boolean canShow(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                return Settings.canDrawOverlays(ctx);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 打开悬浮窗权限设置页 */
    public static void openPermissionSettings(Context ctx) {
        try {
            Intent it = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + ctx.getPackageName()));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
        } catch (Exception ignored) {
        }
    }

    /**
     * 高风险动作审批（全局悬浮窗）。
     * 结果：1=允许一次，0=拒绝，-1=停止任务，-2=失败/无权限。
     */
    public static int ask(Context ctx, String action, String detail) {
        String[] options = {"允许一次", "拒绝", "停止任务"};
        int[] map = {ALLOW_ONCE, DENY, STOP_TASK};
        int r = askCustom(ctx, "🛡 高风险动作确认（全局）",
                "思考执行员请求执行：\n" + action
                        + "\n\n参数：" + detail
                        + "\n\n允许执行一次吗？",
                options, 1);
        if (r < 0) return r == -2 ? FAILED : DENY;
        return map[r];
    }

    /**
     * 通用全局询问。返回选项下标；-1=超时（按 defaultIdx 处理），-2=失败/无权限。
     */
    public static int askCustom(Context ctx, String title, String message,
                                String[] options, int defaultIdx) {
        Context appCtx = ctx.getApplicationContext();
        if (!canShow(appCtx)) return -2;

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicInteger result = new AtomicInteger(-1);
        final View[] holder = new View[1];

        Runnable show = () -> {
            try {
                WindowManager wm = (WindowManager)
                        appCtx.getSystemService(Context.WINDOW_SERVICE);
                if (wm == null) {
                    result.set(-2);
                    latch.countDown();
                    return;
                }
                LinearLayout root = new LinearLayout(appCtx);
                root.setOrientation(LinearLayout.VERTICAL);
                int pad = dp(appCtx, 20);
                root.setPadding(pad, pad, pad, pad);
                root.setBackgroundColor(0xF2FFFFFF);

                TextView titleV = new TextView(appCtx);
                titleV.setText(title);
                titleV.setTextSize(17);
                titleV.setTextColor(0xFF1A1A1A);
                titleV.setPadding(0, 0, 0, dp(appCtx, 8));
                root.addView(titleV);

                TextView msg = new TextView(appCtx);
                msg.setText(message + "\n\n（" + TIMEOUT_SEC
                        + "秒无操作自动按默认处理）");
                msg.setTextSize(14);
                msg.setTextColor(0xFF333333);
                msg.setPadding(0, 0, 0, dp(appCtx, 12));
                root.addView(msg);

                LinearLayout row = new LinearLayout(appCtx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.END);

                for (int i = 0; i < options.length; i++) {
                    final int idx = i;
                    Button b = new Button(appCtx);
                    b.setText(options[i]);
                    b.setOnClickListener(v -> {
                        result.set(idx);
                        remove(wm, holder[0]);
                        latch.countDown();
                    });
                    row.addView(b);
                }
                root.addView(row);

                int type = Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.CENTER;
                holder[0] = root;
                wm.addView(root, lp);
            } catch (Exception e) {
                latch.countDown();
            }
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            show.run();
        } else {
            new Handler(Looper.getMainLooper()).post(show);
        }

        try {
            latch.await(TIMEOUT_SEC, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 超时后确保移除悬浮窗，按默认选项处理
        if (latch.getCount() > 0) {
            result.set(defaultIdx);
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    WindowManager wm = (WindowManager)
                            appCtx.getSystemService(Context.WINDOW_SERVICE);
                    remove(wm, holder[0]);
                } catch (Exception ignored) {
                }
            });
        }
        return result.get();
    }

    private static void remove(WindowManager wm, View v) {
        if (wm != null && v != null) {
            try {
                wm.removeView(v);
            } catch (Exception ignored) {
            }
        }
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
