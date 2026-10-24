package com.gsic.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.app.ActivityOptions;
import android.util.Log;

/**
 * v2.54-spike：虚拟屏可行性验证。
 * 测试：建虚拟屏 → 把 App 启动上去 → 截到图。
 * 每一步捕获异常并返回具体信息，用于判断路是否走得通。
 */
public class VirtualDisplayTest {

    private static final String TAG = "VDTest";
    private static VirtualDisplay vd;
    private static ImageReader reader;

    /** 运行完整可行性测试，返回每步结果 */
    public static String run(Context ctx) {
        StringBuilder sb = new StringBuilder();
        // Step 1: 创建 ImageReader
        try {
            reader = ImageReader.newInstance(720, 1280, PixelFormat.RGBA_8888, 2);
            sb.append("1.ImageReader OK\n");
        } catch (Exception e) {
            sb.append("1.ImageReader FAIL: ").append(e.getMessage()).append("\n");
            return sb.toString();
        }
        // Step 2: 创建虚拟屏（先试 PUBLIC，再试 OWN_CONTENT_ONLY）
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        int[] flagsToTry = {
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
        };
        String[] flagNames = {"PUBLIC", "OWN_CONTENT_ONLY"};
        boolean created = false;
        for (int i = 0; i < flagsToTry.length && !created; i++) {
            try {
                vd = dm.createVirtualDisplay("agent_vd_" + flagNames[i],
                        720, 1280, 160, reader.getSurface(), flagsToTry[i]);
                int did = vd.getDisplay().getDisplayId();
                sb.append("2.虚拟屏 OK flag=").append(flagNames[i])
                        .append(" displayId=").append(did).append("\n");
                created = true;
                // Step 3: 尝试把 App 启动到虚拟屏
                try {
                    Intent it = ctx.getPackageManager()
                            .getLaunchIntentForPackage("com.luna.music");
                    if (it == null) {
                        sb.append("3.找不到测试 App\n");
                    } else {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ActivityOptions opts = ActivityOptions.makeBasic();
                        opts.setLaunchDisplayId(did);
                        ctx.startActivity(it, opts.toBundle());
                        sb.append("3.启动 App 到虚拟屏 OK\n");
                    }
                } catch (Exception e3) {
                    sb.append("3.启动 App FAIL: ")
                            .append(e3.getClass().getSimpleName())
                            .append(": ").append(e3.getMessage()).append("\n");
                }
                // Step 4: 尝试从 ImageReader 取一帧
                try {
                    Thread.sleep(3000); // 等 App 启动
                    android.media.Image img = reader.acquireLatestImage();
                    if (img != null) {
                        sb.append("4.截到虚拟屏图像 OK ")
                                .append(img.getWidth()).append("x")
                                .append(img.getHeight()).append("\n");
                        img.close();
                    } else {
                        sb.append("4.未取到图像（null，可能无内容渲染）\n");
                    }
                } catch (Exception e4) {
                    sb.append("4.取图 FAIL: ").append(e4.getMessage()).append("\n");
                }
            } catch (Exception e2) {
                sb.append("2.虚拟屏 FAIL flag=").append(flagNames[i])
                        .append(": ").append(e2.getClass().getSimpleName())
                        .append(": ").append(e2.getMessage()).append("\n");
            }
        }
        if (!created) {
            sb.append("结论：建不出虚拟屏，此路不通\n");
        }
        Log.i(TAG, sb.toString());
        return sb.toString();
    }

    /** 保持虚拟屏并返回 displayId（供 root am start 用） */
    public static String createOnly(Context ctx) {
        StringBuilder sb = new StringBuilder();
        try {
            if (reader == null) {
                reader = ImageReader.newInstance(720, 1280,
                        PixelFormat.RGBA_8888, 2);
            }
            DisplayManager dm = (DisplayManager) ctx.getSystemService(
                    Context.DISPLAY_SERVICE);
            if (vd == null) {
                vd = dm.createVirtualDisplay("agent_vd",
                        720, 1280, 160, reader.getSurface(),
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            }
            int did = vd.getDisplay().getDisplayId();
            sb.append("虚拟屏就绪 displayId=").append(did);
        } catch (Exception e) {
            sb.append("FAIL: ").append(e.getMessage());
        }
        return sb.toString();
    }

    /** 从现有 ImageReader 取一帧并存文件，返回路径+亮度分析 */
    public static String snapSave(Context ctx) {
        try {
            if (reader == null) return "reader 为空，先 vd_create";
            android.media.Image img = reader.acquireLatestImage();
            if (img == null) return "null（无内容渲染）";
            android.graphics.Bitmap bmp = imageToBitmap(img);
            img.close();
            // 快速分析：采样100个点算平均亮度，全黑=没渲染
            long sum = 0;
            int n = 0;
            for (int y = 0; y < bmp.getHeight(); y += bmp.getHeight() / 10) {
                for (int x = 0; x < bmp.getWidth(); x += bmp.getWidth() / 10) {
                    int p = bmp.getPixel(x, y);
                    sum += (android.graphics.Color.red(p)
                            + android.graphics.Color.green(p)
                            + android.graphics.Color.blue(p)) / 3;
                    n++;
                }
            }
            long avg = n > 0 ? sum / n : 0;
            java.io.File f = new java.io.File(ctx.getCacheDir(),
                    "vd_snap_" + System.currentTimeMillis() + ".jpg");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, fos);
            fos.close();
            bmp.recycle();
            return "saved:" + f.getAbsolutePath() + " 平均亮度=" + avg
                    + (avg < 10 ? "（疑似黑屏/无渲染）" : "（有内容渲染）");
        } catch (Exception e) {
            return "FAIL: " + e.getMessage();
        }
    }

    private static android.graphics.Bitmap imageToBitmap(android.media.Image img) {
        android.media.Image.Plane[] planes = img.getPlanes();
        java.nio.ByteBuffer buf = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * img.getWidth();
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                img.getWidth() + rowPadding / pixelStride,
                img.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(buf);
        return android.graphics.Bitmap.createBitmap(bmp,
                0, 0, img.getWidth(), img.getHeight());
    }

    /** 释放虚拟屏 */
    public static void release() {
        try {
            if (vd != null) { vd.release(); vd = null; }
        } catch (Exception ignored) {}
        try {
            if (reader != null) { reader.close(); reader = null; }
        } catch (Exception ignored) {}
    }
}
