package com.gsic.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * 底部 tab 图标（v3.58）：单色细线条矢量图标，代码绘制，无 emoji、无图片资源。
 * 24 单位网格、圆头线帽、统一 1.7 线宽，Lucide/Material Symbols 式极简几何：
 * 主页=房屋轮廓 远控=广播波纹 智能体=四角星(闪光) 终端=>_ 我的=人像轮廓。
 * 选中态：主色胶囊底 + 白色图标；未选中：透明底 + 中性灰图标。
 */
public class TabIconView extends View {

    public static final int HOME = 0;
    public static final int REMOTE = 1;
    public static final int AGENT = 2;
    public static final int TERM = 3;
    public static final int MY = 4;

    private static final int IDLE_COLOR = 0xFF666666;

    private final int kind;
    private boolean active = false;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TabIconView(Context ctx, int kind) {
        super(ctx);
        this.kind = kind;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        bg.setStyle(Paint.Style.FILL);
        setClickable(true);
    }

    public void setActive(boolean a) {
        if (a == active) return;
        active = a;
        invalidate();
    }

    public boolean isActive() {
        return active;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        int iconColor = IDLE_COLOR;
        if (active) {
            bg.setColor(DyColor.primary(getContext()));
            float insetX = dp(3);
            float insetY = dp(4);
            float radius = (h - insetY * 2) / 2f;
            canvas.drawRoundRect(new RectF(insetX, insetY, w - insetX, h - insetY),
                    radius, radius, bg);
            iconColor = Color.WHITE;
        }
        stroke.setColor(iconColor);
        fill.setColor(iconColor);
        float iconSize = dp(23);
        float scale = iconSize / 24f;
        stroke.setStrokeWidth(1.7f);
        canvas.save();
        canvas.translate(w / 2f - 12f * scale, h / 2f - 12f * scale);
        canvas.scale(scale, scale);
        Path p = new Path();
        switch (kind) {
            case HOME:
                // 房屋轮廓：屋顶+墙体一笔，底部中间留门洞缺口
                p.moveTo(5f, 10.8f);
                p.lineTo(12f, 4.4f);
                p.lineTo(19f, 10.8f);
                p.lineTo(19f, 19.6f);
                p.lineTo(14.7f, 19.6f);
                p.lineTo(14.7f, 15.2f);
                p.lineTo(9.3f, 15.2f);
                p.lineTo(9.3f, 19.6f);
                p.lineTo(5f, 19.6f);
                p.close();
                canvas.drawPath(p, stroke);
                break;
            case REMOTE: {
                // 广播波纹：中心实点 + 左右各两道弧
                canvas.drawCircle(12f, 12f, 1.5f, fill);
                RectF inner = new RectF(6.4f, 6.4f, 17.6f, 17.6f);
                RectF outer = new RectF(3.2f, 3.2f, 20.8f, 20.8f);
                canvas.drawArc(inner, -55f, 110f, false, stroke);
                canvas.drawArc(inner, 125f, 110f, false, stroke);
                canvas.drawArc(outer, -55f, 110f, false, stroke);
                canvas.drawArc(outer, 125f, 110f, false, stroke);
                break;
            }
            case AGENT:
                // 四角星（闪光）：现代 AI 标识
                p.moveTo(12f, 3.6f);
                p.cubicTo(12.8f, 8.6f, 15.4f, 11.2f, 20.4f, 12f);
                p.cubicTo(15.4f, 12.8f, 12.8f, 15.4f, 12f, 20.4f);
                p.cubicTo(11.2f, 15.4f, 8.6f, 12.8f, 3.6f, 12f);
                p.cubicTo(8.6f, 11.2f, 11.2f, 8.6f, 12f, 3.6f);
                p.close();
                canvas.drawPath(p, stroke);
                break;
            case TERM:
                // >_ ：折线 + 短横
                p.moveTo(5.4f, 6.2f);
                p.lineTo(11.2f, 12f);
                p.lineTo(5.4f, 17.8f);
                p.moveTo(13.2f, 18.4f);
                p.lineTo(19f, 18.4f);
                canvas.drawPath(p, stroke);
                break;
            case MY: default:
                // 人像：圆头 + 肩线弧
                canvas.drawCircle(12f, 7.9f, 3.3f, stroke);
                p.moveTo(5.2f, 19.8f);
                p.cubicTo(6f, 15.9f, 8.9f, 14.1f, 12f, 14.1f);
                p.cubicTo(15.1f, 14.1f, 18f, 15.9f, 18.8f, 19.8f);
                canvas.drawPath(p, stroke);
                break;
        }
        canvas.restore();
    }
}
