package com.gsic.app;

import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;

/**
 * 截图压缩：发给 LLM 或经网络分享前，先降分辨率 + 转 JPEG，
 * 省 token、省流量（对标 GHD 论文把截图缩到 420×896 的思路）。
 *
 * <p>本地存档仍用原图 PNG（TaskRunner screenshot 步骤不变）。
 */
public class ScreenshotUtil {

    /**
     * 两级截屏：无障碍（免 root，Android 11+）→ root screencap 兜底。
     * 对标 ApiHandler /screenshot 的成功路径；TaskRunner.vision 用它，
     * 避免无障碍截屏失败的机型上视觉能力直接瘫痪。
     * 都失败返回 null。
     */
    /**
     * v3.28：通道优先级 root > 无障碍（保底）。
     * 有 root 时 root screencap 优先（MIUI 无障碍截屏常被系统拦截）；
     * 无 root 时降级无障碍截屏。
     */
    public static Bitmap capture(android.content.Context ctx) {
        // 优先：root screencap
        try {
            if (Root.findSu() != null) {
                java.io.File f = new java.io.File(ctx.getCacheDir(), "shot_eye.png");
                if (f.exists()) f.delete();
                Process p = Root.exec("screencap -p " + f.getAbsolutePath());
                if (p != null) p.waitFor();
                if (f.exists() && f.length() > 0) {
                    Bitmap bmp = android.graphics.BitmapFactory.decodeFile(
                            f.getAbsolutePath());
                    f.delete();
                    if (bmp != null) return bmp;
                }
            }
        } catch (Exception ignored) {
        }
        // 保底：无障碍截屏
        try {
            Bitmap bmp = AgentAccessibilityService.screenshot();
            if (bmp != null) return bmp;
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 按 maxDim 等比缩放尺寸：长边不超过 maxDim。
     * 纯函数，可独立测试。返回 [w, h]。
     */
    public static int[] fitSize(int w, int h, int maxDim) {
        if (w <= 0 || h <= 0 || maxDim <= 0) return new int[]{w, h};
        int longSide = Math.max(w, h);
        if (longSide <= maxDim) return new int[]{w, h};
        double scale = (double) maxDim / longSide;
        return new int[]{(int) Math.round(w * scale),
                (int) Math.round(h * scale)};
    }

    /** 缩放 + JPEG 压缩，返回字节数组；失败返回 null。 */
    public static byte[] toJpegSmall(Bitmap bmp, int maxDim, int quality) {
        if (bmp == null || bmp.isRecycled()) return null;
        try {
            int[] size = fitSize(bmp.getWidth(), bmp.getHeight(), maxDim);
            Bitmap small = bmp;
            boolean scaled = size[0] != bmp.getWidth()
                    || size[1] != bmp.getHeight();
            if (scaled) {
                small = Bitmap.createScaledBitmap(bmp, size[0], size[1], true);
                if (small == null) return null;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, quality, bos);
            if (scaled) small.recycle();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
