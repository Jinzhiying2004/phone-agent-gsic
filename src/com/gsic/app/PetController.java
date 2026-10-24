package com.gsic.app;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.List;

/**
 * v3.48 待机光效「M 八爪鱼」控制器（挂在 AgentService 里）。
 * 出现条件：设置开启 + 悬浮窗权限 + 屏幕亮 + 电量 >=20%（或充电中）
 * + 无任务运行（TaskLock 空闲，与流光运行指示互斥，读同一份共享状态）
 * + 桌面 Launcher 在前台持续 5 秒（或非「仅桌面」模式下任意非本 App 前台）。
 * 消失：任务启动 / 切 App / 熄屏 / 进本 App，立即让位。
 * 前台检测优先 root dumpsys，次选无障碍根窗口包名，都拿不到就不显示（不猜）。
 */
public class PetController {

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread thread;
    private Handler bg;
    private volatile boolean started;
    private volatile boolean shown;
    private volatile long launcherSince;
    private volatile float lastX = -1, lastY = -1;
    private String homePkg;
    private String ownPkg;

    private WindowManager wm;
    private PetView petView;
    private View hitView;
    private WindowManager.LayoutParams hitParams;
    private int hitSize;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!started) return;
            try {
                evaluate();
            } catch (Exception ignored) {
            }
            if (bg != null) bg.postDelayed(this, 2000);
        }
    };

    private final Runnable hitSync = new Runnable() {
        @Override
        public void run() {
            if (!shown) return;
            try {
                if (petView != null && hitParams != null && lastX >= 0) {
                    int sz = petView.petSizePx();
                    hitParams.x = (int) (lastX - sz / 2f);
                    hitParams.y = (int) (lastY - sz / 2f);
                    if (sz != hitSize) {
                        hitSize = sz;
                        hitParams.width = sz;
                        hitParams.height = sz;
                    }
                    wm.updateViewLayout(hitView, hitParams);
                }
            } catch (Exception ignored) {
            }
            main.postDelayed(this, 100);
        }
    };

    public PetController(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        ownPkg = this.ctx.getPackageName();
    }

    public void start() {
        if (started) return;
        started = true;
        thread = new HandlerThread("pet-ctl");
        thread.start();
        bg = new Handler(thread.getLooper());
        bg.postDelayed(poll, 4000);
    }

    public void stop() {
        started = false;
        main.post(this::hideNow);
        if (thread != null) thread.quitSafely();
        thread = null;
        bg = null;
    }

    private boolean pref(String key, boolean def) {
        try {
            return ctx.getSharedPreferences("agent", Context.MODE_PRIVATE)
                    .getBoolean(key, def);
        } catch (Exception e) {
            return def;
        }
    }

    private void evaluate() {
        boolean want = false;
        if (pref("pet_enabled", true)
                && FloatWindow.hasPermission(ctx)
                && !TaskLock.isBusy()
                && !FloatWindow.isInApp()
                && screenOn()
                && batteryOk()) {
            String fg = foregroundPkg();
            if (fg != null) {
                if (pref("pet_desktop_only", true)) {
                    want = fg.equals(homePkg());
                } else {
                    want = !fg.equals(ownPkg);
                }
            }
        }
        long now = System.currentTimeMillis();
        if (!want) {
            launcherSince = 0;
            if (shown) main.post(this::hideNow);
            return;
        }
        if (launcherSince == 0) {
            launcherSince = now;
            return;
        }
        if (!shown && now - launcherSince >= 5000) {
            main.post(this::showNow);
        }
    }

    private boolean screenOn() {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isInteractive();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean batteryOk() {
        try {
            Intent bi = ctx.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (bi == null) return true;
            int level = bi.getIntExtra(BatteryManager.EXTRA_LEVEL, 100);
            int scale = bi.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = bi.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
            int pct = scale > 0 ? level * 100 / scale : 100;
            return charging || pct >= 20;
        } catch (Exception e) {
            return true;
        }
    }

    private String homePkg() {
        if (homePkg != null) return homePkg;
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo ri = ctx.getPackageManager().resolveActivity(
                    i, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri != null && ri.activityInfo != null) {
                homePkg = ri.activityInfo.packageName;
            }
        } catch (Exception ignored) {
        }
        if (homePkg == null) homePkg = "com.miui.home";
        return homePkg;
    }

    private String foregroundPkg() {
        // root 通道：dumpsys 取 mResumedActivity 的包名
        try {
            if (Root.findSu() != null) {
                String[] r = RootShell.get().exec(
                        "dumpsys activity activities | grep -m 1 mResumedActivity", 4);
                if (r != null && r.length > 1 && r[1] != null) {
                    String out = r[1];
                    int u = out.indexOf("u0 ");
                    if (u >= 0) {
                        int a = u + 3;
                        int b = out.indexOf('/', a);
                        if (b > a) return out.substring(a, b).trim();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // 无障碍通道：根窗口包名
        try {
            AgentAccessibilityService svc = AgentAccessibilityService.get();
            if (svc != null) {
                android.view.accessibility.AccessibilityNodeInfo root =
                        svc.getRootInActiveWindow();
                if (root != null && root.getPackageName() != null) {
                    return root.getPackageName().toString();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void showNow() {
        if (shown) return;
        try {
            wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            petView = new PetView(ctx);
            petView.setAlpha(0f);
            petView.setPosListener((x, y) -> {
                lastX = x;
                lastY = y;
            });
            WindowManager.LayoutParams cp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            cp.gravity = Gravity.TOP | Gravity.START;
            wm.addView(petView, cp);

            hitSize = petView.petSizePx();
            hitView = new View(ctx);
            hitView.setOnClickListener(v -> {
                if (petView != null) petView.flee();
            });
            hitParams = new WindowManager.LayoutParams(
                    hitSize, hitSize, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            hitParams.gravity = Gravity.TOP | Gravity.START;
            hitParams.x = -hitSize;
            hitParams.y = -hitSize;
            wm.addView(hitView, hitParams);

            shown = true;
            petView.startAnim();
            petView.animate().alpha(1f).setDuration(450).start();
            main.post(hitSync);
        } catch (Exception e) {
            shown = false;
            cleanupViews();
        }
    }

    private void hideNow() {
        if (!shown && petView == null) return;
        shown = false;
        main.removeCallbacks(hitSync);
        cleanupViews();
    }

    private void cleanupViews() {
        try {
            if (petView != null) petView.stopAnim();
        } catch (Exception ignored) {
        }
        try {
            if (wm != null && hitView != null) wm.removeView(hitView);
        } catch (Exception ignored) {
        }
        try {
            if (wm != null && petView != null) wm.removeView(petView);
        } catch (Exception ignored) {
        }
        petView = null;
        hitView = null;
        hitParams = null;
    }
}
