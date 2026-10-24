package com.gsic.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

/**
 * 虚拟屏管理器——后台自动化的核心。
 * 创建 OWN_CONTENT_ONLY 虚拟屏（无需特殊权限），配合 root 的
 * am start --display 启动 App、VdInput 注入点击，实现不打扰主屏的任务执行。
 *
 * 主从关系（2026-10-23 用户亲定）：一个任务只能有一个主显示。
 * 走虚拟屏的任务，虚拟屏是主显示（全部操作/截图/终审都在虚拟屏），
 * 实体屏是从显示——只在右上角小窗实时预览虚拟屏画面，本身不承载任务；
 * 任务结束前不允许切到实体屏（互斥锁定在 AgentLoop/PhoneTools 层执行），
 * 虚拟屏中途失效也不静默回退实体屏（防同一任务在双屏各留一份、点错屏）。
 *
 * 实时帧管线（2026-10-23 修）：ImageReader 帧监听是唯一消费者——
 * 每来一帧就更新缓存（全尺寸帧供模型截图 + 缩略帧供预览），
 * screenshot() 只拷缓存、不再与预览抢帧；预览按缓存版本号以约
 * 120ms 节奏刷新（旧版 700ms 轮询 + 靠模型调截图才更新缓存，
 * 模型不看屏时预览就卡住、动起来也只有约 1.4fps）。
 */
public class VirtualDisplayManager {

    private static final String TAG = "VDManager";
    private static VirtualDisplay vd;
    private static ImageReader reader;
    private static HandlerThread frameThread;
    // 最后一帧缓存（监听驱动）：全尺寸供 screenshot()，缩略供预览
    private static Bitmap lastFullFrame;
    private static Bitmap lastFrame;
    private static long lastFrameVer = 0;
    private static long lastFrameAt = 0;
    private static int displayId = -1;

    /** 创建虚拟屏，返回 displayId（失败返回 -1） */
    public static synchronized int create(Context ctx, int w, int h, int dpi) {
        destroy(); // 先清旧的
        try {
            reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            frameThread = new HandlerThread("vd-frames");
            frameThread.start();
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override public void onImageAvailable(ImageReader r) {
                    Image img = null;
                    try {
                        img = r.acquireLatestImage();
                        if (img == null) return;
                        onNewFrame(imageToBitmap(img));
                    } catch (Exception e) {
                        Log.e(TAG, "帧处理失败: " + e.getMessage());
                    } finally {
                        if (img != null) { try { img.close(); } catch (Exception ignored) {} }
                    }
                }
            }, new Handler(frameThread.getLooper()));
            DisplayManager dm = (DisplayManager) ctx.getSystemService(
                    Context.DISPLAY_SERVICE);
            vd = dm.createVirtualDisplay("agent_vd",
                    w, h, dpi, reader.getSurface(),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            displayId = vd.getDisplay().getDisplayId();
            appCtx = ctx.getApplicationContext();
            if (previewEnabled) startPreview();
            Log.i(TAG, "虚拟屏创建成功 displayId=" + displayId);
            return displayId;
        } catch (Exception e) {
            Log.e(TAG, "虚拟屏创建失败: " + e.getMessage());
            destroy();
            return -1;
        }
    }

    /** 帧监听回调：更新全尺寸缓存 + 缩略缓存（预览尺寸 270 宽，保持 VD 宽高比） */
    private static synchronized void onNewFrame(Bitmap full) {
        if (full == null) return;
        if (vd == null) { // destroy 已先行：在途回调不再复活缓存
            try { full.recycle(); } catch (Exception ignored) {}
            return;
        }
        try {
            int tw = 270;
            int th = Math.max(1, full.getHeight() * tw / full.getWidth());
            Bitmap small = Bitmap.createScaledBitmap(full, tw, th, true);
            if (lastFullFrame != null) lastFullFrame.recycle();
            if (lastFrame != null) lastFrame.recycle();
            lastFullFrame = full;
            lastFrame = small;
            lastFrameVer++;
            lastFrameAt = System.currentTimeMillis();
        } catch (Exception e) {
            try { full.recycle(); } catch (Exception ignored) {}
        }
    }

    /** 销毁虚拟屏 */
    public static synchronized void destroy() {
        stopPreview();
        try {
            if (reader != null) { reader.setOnImageAvailableListener(null, null); }
        } catch (Exception ignored) {}
        try {
            if (vd != null) { vd.release(); vd = null; }
        } catch (Exception ignored) {}
        try {
            if (reader != null) { reader.close(); reader = null; }
        } catch (Exception ignored) {}
        if (frameThread != null) {
            try { frameThread.quitSafely(); } catch (Exception ignored) {}
            frameThread = null;
        }
        displayId = -1;
        if (lastFrame != null) {
            try { lastFrame.recycle(); } catch (Exception ignored) {}
            lastFrame = null;
        }
        if (lastFullFrame != null) {
            try { lastFullFrame.recycle(); } catch (Exception ignored) {}
            lastFullFrame = null;
        }
    }

    /** 预览用：取最后一帧缩略图的副本（无帧返回 null） */
    public static synchronized Bitmap peekLastFrame() {
        if (lastFrame == null) return null;
        try {
            return lastFrame.copy(lastFrame.getConfig(), false);
        } catch (Exception e) {
            return null;
        }
    }

    public static synchronized long lastFrameVersion() {
        return lastFrameVer;
    }

    public static synchronized long lastFrameAgeMs() {
        return lastFrameAt == 0 ? Long.MAX_VALUE
                : System.currentTimeMillis() - lastFrameAt;
    }

    public static synchronized boolean isActive() {
        return vd != null && displayId >= 0;
    }

    public static synchronized int getDisplayId() {
        return displayId;
    }

    /**
     * 从虚拟屏取一帧，返回 Bitmap 副本（失败返回 null）。
     * 帧由监听持续写入缓存，这里只拷缓存：模型不调截图时缓存照样新，
     * 预览与模型也不再互相抢 ImageReader 的帧。
     */
    public static synchronized Bitmap screenshot() {
        if (lastFullFrame == null) return null;
        try {
            return lastFullFrame.copy(lastFullFrame.getConfig(), false);
        } catch (Exception e) {
            Log.e(TAG, "虚拟屏截屏失败: " + e.getMessage());
            return null;
        }
    }

    private static Bitmap imageToBitmap(Image img) {
        Image.Plane[] planes = img.getPlanes();
        java.nio.ByteBuffer buf = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * img.getWidth();
        Bitmap bmp = Bitmap.createBitmap(
                img.getWidth() + rowPadding / pixelStride,
                img.getHeight(), Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(buf);
        return Bitmap.createBitmap(bmp, 0, 0, img.getWidth(), img.getHeight());
    }

    // ---- 悬浮预览（从显示）：App 通道虚拟屏任务时，在实体屏右上角小窗实时显示虚拟屏画面 ----
    // 2026-10-23 用户亲定：面积调小（宽 1/3 屏改为 1/4 屏，面积约 -44%）、
    // 刷新提速（700ms 改为约 120ms，约 8fps；帧本身由监听驱动，不依赖模型调截图）。
    private static volatile boolean previewEnabled = false;
    private static Context appCtx;
    private static android.widget.ImageView previewView;
    private static volatile boolean previewRunning = false;

    public static void setPreviewEnabled(boolean v) {
        previewEnabled = v;
        if (!v) stopPreview();
    }

    private static void startPreview() {
        if (appCtx == null) return;
        try {
            if (!FloatWindow.hasPermission(appCtx)) return;
        } catch (Exception e) {
            return;
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                if (previewView != null || !isActive()) return;
                android.view.WindowManager wm = (android.view.WindowManager)
                        appCtx.getSystemService(Context.WINDOW_SERVICE);
                android.util.DisplayMetrics dm =
                        appCtx.getResources().getDisplayMetrics();
                int w = dm.widthPixels / 4;
                int h = w * 16 / 9;
                final android.widget.ImageView iv =
                        new android.widget.ImageView(appCtx);
                iv.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
                iv.setBackgroundColor(0xFF222222);
                iv.setPadding(2, 2, 2, 2);
                android.view.WindowManager.LayoutParams lp =
                        new android.view.WindowManager.LayoutParams(
                                w, h,
                                android.view.WindowManager.LayoutParams
                                        .TYPE_APPLICATION_OVERLAY,
                                android.view.WindowManager.LayoutParams
                                        .FLAG_NOT_FOCUSABLE
                                        | android.view.WindowManager.LayoutParams
                                        .FLAG_NOT_TOUCHABLE,
                                PixelFormat.TRANSLUCENT);
                lp.gravity = android.view.Gravity.TOP
                        | android.view.Gravity.END;
                lp.x = 8;
                lp.y = 110;
                wm.addView(iv, lp);
                previewView = iv;
                previewRunning = true;
                new Thread(() -> {
                    long shownVer = -1;
                    while (previewRunning && isActive()) {
                        long ver = lastFrameVersion();
                        Bitmap b = ver != shownVer ? peekLastFrame() : null;
                        if (b != null) {
                            shownVer = ver;
                            new android.os.Handler(
                                    android.os.Looper.getMainLooper())
                                    .post(() -> {
                                        try {
                                            if (previewView == iv) {
                                                android.graphics.drawable
                                                        .Drawable old =
                                                        iv.getDrawable();
                                                iv.setImageBitmap(b);
                                                if (old instanceof android
                                                        .graphics.drawable
                                                        .BitmapDrawable) {
                                                    Bitmap ob = ((android
                                                            .graphics.drawable
                                                            .BitmapDrawable) old)
                                                            .getBitmap();
                                                    if (ob != null && ob != b) {
                                                        ob.recycle();
                                                    }
                                                }
                                            } else {
                                                b.recycle();
                                            }
                                        } catch (Exception ignored) {}
                                    });
                        }
                        try {
                            Thread.sleep(120);
                        } catch (Exception ignored) {}
                    }
                }, "vd-preview").start();
            } catch (Exception ignored) {}
        });
    }

    private static void stopPreview() {
        previewRunning = false;
        if (appCtx == null) return;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            try {
                if (previewView != null) {
                    android.view.WindowManager wm = (android.view.WindowManager)
                            appCtx.getSystemService(Context.WINDOW_SERVICE);
                    wm.removeView(previewView);
                    previewView = null;
                }
            } catch (Exception ignored) {}
        });
    }
}
