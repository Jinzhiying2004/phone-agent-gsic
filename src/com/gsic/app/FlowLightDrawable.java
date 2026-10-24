package com.gsic.app;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.animation.LinearInterpolator;

/**
 * 渐变流光边框 Drawable：一道渐变光带沿圆角矩形边框持续流动。
 * 有流光 = 任务运行中；无流光 = 任务已停止。
 * 用于任务悬浮窗和智能体输入栏。
 */
public class FlowLightDrawable extends Drawable {

    private final float cornerRadius;
    private final int lightColor;
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path borderPath = new Path();
    private final PathMeasure pathMeasure = new PathMeasure();
    private final Path segmentPath = new Path();
    private float fraction = 0f;
    private ValueAnimator animator;
    private boolean pathDirty = true;

    public FlowLightDrawable(int baseColor, int lightColor, float cornerRadiusDp,
                             float density) {
        this.lightColor = lightColor;
        this.cornerRadius = cornerRadiusDp * density;
        basePaint.setColor(baseColor);
        basePaint.setStyle(Paint.Style.FILL);
        flowPaint.setStyle(Paint.Style.STROKE);
        flowPaint.setStrokeCap(Paint.Cap.ROUND);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeCap(Paint.Cap.ROUND);
        haloPaint.setStyle(Paint.Style.STROKE);
        haloPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    /** 开始流光动画 */
    public void start() {
        if (animator != null && animator.isRunning()) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(4200); // v3.41：放慢（用户反馈太快）
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            fraction = (float) a.getAnimatedValue();
            invalidateSelf();
        });
        animator.start();
    }

    /** 停止流光动画 */
    public void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    public boolean isRunning() {
        return animator != null && animator.isRunning();
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        pathDirty = true;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;

        // 底色圆角矩形
        RectF rf = new RectF(b);
        canvas.drawRoundRect(rf, cornerRadius, cornerRadius, basePaint);

        // 边框路径
        if (pathDirty) {
            borderPath.reset();
            float inset = Math.max(4, b.width() / 80f);
            RectF inner = new RectF(b.left + inset, b.top + inset,
                    b.right - inset, b.bottom - inset);
            float cr = Math.max(1, cornerRadius - inset);
            borderPath.addRoundRect(inner, cr, cr, Path.Direction.CW);
            pathMeasure.setPath(borderPath, false);
            pathDirty = false;
        }

        float len = pathMeasure.getLength();
        if (len <= 0) return;

        // v3.48-p2：常亮微光边线——整圈先描一条静态细线（三成亮度），
        // 保证任何时刻都看得出「任务进行中」，不依赖光带位置
        float coreW0 = Math.max(7, b.width() / 50f);
        edgePaint.setColor(Color.argb(72, Color.red(lightColor),
                Color.green(lightColor), Color.blue(lightColor)));
        edgePaint.setStrokeWidth(Math.max(3f, coreW0 * 0.30f));
        canvas.drawPath(borderPath, edgePaint);

        // 取光带段：头部在 fraction 处，尾部拖 18% 周长
        float head = len * fraction;
        float tailLen = len * 0.22f; // v3.41：光带拉长更醒目
        float tail = head - tailLen;
        segmentPath.reset();
        if (tail >= 0) {
            pathMeasure.getSegment(tail, head, segmentPath, true);
        } else {
            // 跨过起点：分两段取
            pathMeasure.getSegment(0, head, segmentPath, true);
            pathMeasure.getSegment(len + tail, len, segmentPath, true);
        }

        // 渐变：尾部透明 → 中部光色 → 头部亮白
        float[] pos = new float[2];
        float[] tailPos = new float[2];
        pathMeasure.getPosTan(head, pos, null);
        pathMeasure.getPosTan(tail >= 0 ? tail : len + tail, tailPos, null);
        LinearGradient grad = new LinearGradient(
                tailPos[0], tailPos[1], pos[0], pos[1],
                new int[]{Color.TRANSPARENT, lightColor, Color.WHITE},
                new float[]{0f, 0.6f, 1f},
                Shader.TileMode.CLAMP);
        flowPaint.setShader(grad);
        // v3.41：加粗 + 外层光晕（亮背景上也看得清）
        float coreW = Math.max(7, b.width() / 50f);
        LinearGradient haloGrad = new LinearGradient(
                tailPos[0], tailPos[1], pos[0], pos[1],
                new int[]{Color.TRANSPARENT,
                        Color.argb(80, Color.red(lightColor),
                                Color.green(lightColor), Color.blue(lightColor)),
                        Color.argb(120, 255, 255, 255)},
                new float[]{0f, 0.6f, 1f},
                Shader.TileMode.CLAMP);
        haloPaint.setShader(haloGrad);
        haloPaint.setStrokeWidth(coreW * 2.6f);
        canvas.drawPath(segmentPath, haloPaint);
        flowPaint.setStrokeWidth(coreW);
        canvas.drawPath(segmentPath, flowPaint);
    }

    @Override
    public void setAlpha(int alpha) {
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
