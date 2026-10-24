package com.gsic.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;

import java.util.List;

/**
 * 悬浮抓取按钮：常驻屏幕上层，去其他 App 时点一下即抓取当前界面元素。
 * 抓取同时截屏、标注、保存记录，跳回本 App 展示。
 */
public class CaptureFloat {

    private static android.view.View captureView;
    private static WindowManager wm;
    /** 抓取记录 id 暂存，MainActivity onResume 时取走展示 */
    public static volatile String pendingCaptureId;

    public static boolean isShowing() {
        return captureView != null;
    }

    public static void show(Context ctx) {
        Context ac = ctx.getApplicationContext();
        if (!FloatWindow.hasPermission(ac)) {
            FloatWindow.requestPermission(ctx);
            return;
        }
        if (isShowing()) return;
        try {
            wm = (WindowManager) ac.getSystemService(Context.WINDOW_SERVICE);
            Button btn = new Button(ac);
            btn.setText("🎯");
            btn.setTextSize(20);
            int sz = dp(ac, 56);
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(ac, 28));
            bg.setColor(0xE6427968);
            btn.setBackground(bg);
            btn.setTextColor(0xFFFFFFFF);
            btn.setOnClickListener(v -> {
                // 在后台线程抓取+截屏（此时还在目标 App），避免卡住触摸
                new Thread(() -> {
                    try {
                        List<ElementDumper.El> els = ElementDumper.capture();
                        String pkg = "";
                        try {
                            AgentAccessibilityService s =
                                    AgentAccessibilityService.get();
                            if (s != null && s.getRootInActiveWindow() != null) {
                                pkg = s.getRootInActiveWindow()
                                        .getPackageName().toString();
                            }
                        } catch (Exception ignored) {
                        }
                        String id = new CaptureStore(ac).saveCapture(els, pkg);
                        pendingCaptureId = id;
                    } catch (Exception ignored) {
                    }
                    try {
                        Intent it = new Intent(ac, MainActivity.class);
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        ac.startActivity(it);
                    } catch (Exception ignored) {
                    }
                }).start();
            });

            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    sz, sz, type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            params.x = dp(ac, 8);
            captureView = btn;
            wm.addView(captureView, params);
        } catch (Exception ignored) {
            captureView = null;
        }
    }

    public static void hide() {
        try {
            if (captureView != null && wm != null) wm.removeView(captureView);
        } catch (Exception ignored) {
        }
        captureView = null;
        wm = null;
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
