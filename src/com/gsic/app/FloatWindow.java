package com.gsic.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;

/**
 * 任务运行指示：v3.40 起改为全屏流光边框（无文字胶囊）。
 * 旧胶囊压在屏幕顶部正中，挡按钮视线且会吃掉落在它范围内的点击；
 * 现在只沿屏幕边缘画一道流光，触摸全穿透——流光在转 = agent 在干活。
 * 需要 SYSTEM_ALERT_WINDOW 权限，无权限时静默跳过。
 */
public class FloatWindow {

    private static android.view.View floatView;
    private static WindowManager wm;
    private static FlowLightDrawable flowLight;
    /** 主界面是否在前台：在前台时不弹悬浮窗（界面里已有进度） */
    private static volatile boolean inApp = false;

    public static void setInApp(boolean v) {
        inApp = v;
        // v3.48-p4（用户亲定）：流光边框与任务绑定，不再被「在 App 内」吞掉——
        // 只要有任务在跑就必须显示，进 App 也不收；离开 App 时若任务中途发起
        // 且边框缺失则补上
        if (!v && isActive() && taskCtx != null && floatView == null) {
            show(taskCtx, taskTitle);
        }
    }

    // v3.39：任务级登记——通道任务统一走 begin/end，
    // 任务在 App 前台时发起也不丢窗（离开 App 自动补弹），异常收尾不残留
    // v3.51：彻底修复跨通道丢窗——①登记改引用计数：AgentLoop 中央登记与
    // 通道登记可嵌套，计数未归零不熄灯（App 监管多轮、背靠背任务都不误杀）；
    // ②延迟关窗加代际守卫：旧任务排的关窗指令杀不掉新任务的窗；
    // ③看门狗：登记期间每 2 秒断言窗在，缺了立刻补（不再只靠进度行自愈）。
    private static volatile boolean taskActive = false;
    private static int activeCount = 0;
    private static int gen = 0;
    private static Context taskCtx;
    private static String taskTitle = "";
    /** 最近一次 show 失败原因（诊断用，不再静默吞） */
    public static volatile String lastShowError = "";

    // v3.53 地基·流光真写死：活动心跳——远控旁聊的干活方式是 /action 单步
    // 操控，根本不走 AgentLoop 任务，此前任何版本都不会点亮（实证教训：
    // 测 /task 全过、用户实际用法全黑）。状态源扩为「任务登记 OR 心跳租约」：
    // ApiHandler 对 /action、/screen、/screenshot 打心跳（20 秒租约、请求续租），
    // 只要软件在被操控干活，彩条就必须在。
    private static volatile long pulseUntilMs = 0;

    public static void pulseActivity(Context ctx) {
        try {
            synchronized (FloatWindow.class) {
                if (ctx != null) taskCtx = ctx.getApplicationContext();
                pulseUntilMs = System.currentTimeMillis() + 20000;
            }
            postShow();
            startWatchdog();
        } catch (Exception ignored) {
        }
    }

    /** 彩条该亮的唯一判据：有任务登记，或心跳租约未到期 */
    public static boolean isActive() {
        return taskActive || System.currentTimeMillis() < pulseUntilMs;
    }

    private static void postShow() {
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> {
                    if (isActive() && floatView == null && taskCtx != null) {
                        show(taskCtx, taskTitle);
                    }
                });
    }

    private static android.os.Handler wdHandler;
    private static final Runnable wdTick = new Runnable() {
        @Override
        public void run() {
            if (!isActive()) {
                // 任务与心跳都停了：收窗停表（心跳租约到期走这里熄灯）
                if (floatView != null) hide();
                wdHandler = null;
                return;
            }
            if (floatView == null && taskCtx != null) {
                show(taskCtx, taskTitle);
            }
            if (wdHandler != null) wdHandler.postDelayed(this, 2000);
        }
    };

    private static void startWatchdog() {
        if (wdHandler != null) return;
        wdHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        wdHandler.postDelayed(wdTick, 2000);
    }

    public static void beginTask(Context ctx, String title) {
        try {
            synchronized (FloatWindow.class) {
                taskCtx = ctx.getApplicationContext();
                if (title != null && !title.isEmpty()) taskTitle = title;
                activeCount++;
                gen++;
                taskActive = true;
            }
            postShow();
            startWatchdog();
        } catch (Exception ignored) {
        }
    }

    public static void endTask(String text) {
        final int g;
        synchronized (FloatWindow.class) {
            if (activeCount > 0) activeCount--;
            if (activeCount > 0) return; // 还有外层登记，继续亮
            taskActive = false;
            g = gen;
        }
        try {
            // 无文字版：结束 1.5 秒后收起流光；代际守卫——期间有新任务
            // 登记（gen 变化）则不关，别误杀新任务的窗
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> {
                        synchronized (FloatWindow.class) {
                            if (isActive() || gen != g) return;
                        }
                        hide();
                    }, 1500);
        } catch (Exception ignored) {
        }
    }

    /** 立即熄灯并清零登记（用户 /stop 等强停场景） */
    public static void forceEnd() {
        synchronized (FloatWindow.class) {
            activeCount = 0;
            taskActive = false;
            pulseUntilMs = 0;
            gen++;
        }
        hide();
    }

    /** 断言补窗：活动在进行而窗不在就补上（不重建已存在的窗，避免闪烁） */
    public static void assertShown() {
        if (floatView == null && isActive() && taskCtx != null) postShow();
    }

    public static boolean isInApp() {
        return inApp;
    }

    /** 是否有任务登记在跑（三端任一通道）——供 App 内流光边框轮询 */
    public static boolean isTaskActive() {
        return taskActive;
    }

    public static boolean hasPermission(Context ctx) {
        if (Build.VERSION.SDK_INT < 23) return true;
        return Settings.canDrawOverlays(ctx);
    }

    /** 跳转到悬浮窗权限设置页 */
    public static void requestPermission(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) {
        }
    }

    public static void show(Context ctx, String text) {
        Context ac = ctx.getApplicationContext();
        if (!hasPermission(ac)) return;
        // v3.48-p4：不再因 inApp 跳过——任务在跑就必须有流光，软件内亦然
        hide();
        try {
            wm = (WindowManager) ac.getSystemService(Context.WINDOW_SERVICE);
            // v3.40：全屏流光边框——透明底、零圆角，只沿屏幕边缘流动；
            // FLAG_NOT_TOUCHABLE 触摸全穿透，不挡任何按钮（胶囊版曾吃点击）
            android.view.View glow = new android.view.View(ac);
            flowLight = new FlowLightDrawable(android.graphics.Color.TRANSPARENT,
                    0xFF40C4FF, 0,
                    ac.getResources().getDisplayMetrics().density);
            glow.setBackground(flowLight);
            flowLight.start();
            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            floatView = glow;
            wm.addView(floatView, params);
            lastShowError = "";
        } catch (Exception e) {
            // v3.51：不再静默吞——落日志+遥测，看门狗会按登记状态重试补窗
            floatView = null;
            lastShowError = String.valueOf(e);
            android.util.Log.e("FloatWindow", "show failed", e);
            try {
                org.json.JSONObject d = new org.json.JSONObject();
                d.put("err", lastShowError);
                Telemetry.event(ac, "float", "show_fail", d);
            } catch (Exception ignored2) {
            }
        }
    }

    public static void update(String text) {
        pushLine(text);
    }

    /** v3.40 起无文字显示：pushLine 仅保留自愈（流光缺失时补上） */
    public static void pushLine(String text) {
        if (floatView == null && isActive() && taskCtx != null) {
            postShow();
        }
    }

    /**
     * 从 agent 日志行提取关键行（步骤/思考/关键动作）。v3.40 起悬浮层无文字，
     * 此方法保留供通道侧判断"有关键进展"用，返回值不再渲染。
     */
    public static String briefLine(String msg) {
        if (msg == null) return null;
        String line = null;
        if (msg.startsWith("🤔")) {
            line = msg.length() > 22 ? msg.substring(0, 22) + "…" : msg;
        } else if (msg.startsWith("💭")) {
            String body = msg.substring(2).trim();
            line = "💭 " + (body.length() > 20
                    ? body.substring(0, 20) + "…" : body);
        } else if (msg.startsWith("🖐")) {
            String body = msg.substring(2).trim();
            line = "🖐 " + (body.length() > 20
                    ? body.substring(0, 20) + "…" : body);
        } else if (msg.startsWith("🔍") || msg.startsWith("🔁")
                || msg.startsWith("🧠")) {
            line = msg.length() > 22 ? msg.substring(0, 22) + "…" : msg;
        }
        return line;
    }

    public static void hide() {
        try {
            if (flowLight != null) flowLight.stop();
        } catch (Exception ignored) {
        }
        flowLight = null;
        try {
            if (floatView != null && wm != null) wm.removeView(floatView);
        } catch (Exception ignored) {
        }
        floatView = null;
        wm = null;
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
