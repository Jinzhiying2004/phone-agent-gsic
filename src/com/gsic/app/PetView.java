package com.gsic.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * v3.48 待机光效「M 八爪鱼」渲染层。
 * 形象 = 软件图标本体活化：静止形态是图标原稿（pet_rest.webp），
 * 运动形态是 M 散开的八爪鱼（pet_octo.webp），两图交叉淡入切换。
 * 行为状态机：REST 停靠 → CRAWL 贴边爬行 / CHASE 追光点 / PEEK 探头 /
 * SLEEP 角落睡觉（冒 Zzz、低帧率）/ FLEE 被点惊跳躲角落。
 * 光尾、光点、Zzz 都程序绘制，不用视频/帧序列。
 */
public class PetView extends View {

    public interface PosListener {
        void onPos(float x, float y);
    }

    private static final int ST_REST = 0, ST_CRAWL = 1, ST_SLEEP = 2,
            ST_CHASE = 3, ST_FLEE = 4, ST_PEEK = 5;

    private final Bitmap bmpRest, bmpOcto;
    private final float density;
    private final Random rnd = new Random();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<float[]> trail = new ArrayList<>();

    private int viewW, viewH;
    private float sizePx;       // 八爪鱼形态的绘制宽
    private float inset;        // 贴边路径内缩
    private float segW, segH, perim;

    private float sPos;         // 沿边路径弧长位置
    private float sTarget;
    private int dirSign = 1;
    private float speedDp = 60;
    private int state = ST_REST;
    private long stateUntil;
    private boolean pendingSleep;
    private float blend;        // 0=图标形态 1=八爪鱼形态
    private float jumpStart;    // 惊跳动画起点（uptime 秒）
    private float dotS;         // 追逐光点的路径位置
    private long chaseDeadline;
    private float burstS = -1;  // 抓到光点的迸溅动画
    private long burstAt;

    private boolean running;
    private long lastTick;
    private PosListener posListener;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.08f, (now - lastTick) / 1000f);
            lastTick = now;
            step(dt, now);
            invalidate();
            if (posListener != null) {
                float[] xy = posOf(sPos);
                posListener.onPos(xy[0], xy[1]);
            }
            postDelayed(this, state == ST_SLEEP ? 125 : 33); // 睡觉 8fps，其余 30fps
        }
    };

    public PetView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        bmpRest = loadAsset(ctx, "pet_rest.webp");
        bmpOcto = loadAsset(ctx, "pet_octo.webp");
        zPaint.setColor(0xFFBDEFFF);
        zPaint.setTextSize(15 * density);
        zPaint.setShadowLayer(6, 0, 0, 0xFF40C4FF);
    }

    private static Bitmap loadAsset(Context ctx, String name) {
        try {
            InputStream is = ctx.getAssets().open(name);
            Bitmap b = BitmapFactory.decodeStream(is);
            is.close();
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    public void setPosListener(PosListener l) {
        posListener = l;
    }

    public int petSizePx() {
        return (int) (sizePx > 0 ? sizePx : 110 * density);
    }

    public void startAnim() {
        if (running) return;
        running = true;
        lastTick = SystemClock.uptimeMillis();
        state = ST_REST;
        stateUntil = lastTick + 2500;
        post(ticker);
    }

    public void stopAnim() {
        running = false;
        removeCallbacks(ticker);
    }

    /** 被点中：惊跳 + 快速躲到最近的角落 */
    public void flee() {
        if (perim <= 0) return;
        jumpStart = SystemClock.uptimeMillis() / 1000f;
        sTarget = nearestCorner(sPos);
        dirSign = wrapDelta(sTarget - sPos) >= 0 ? 1 : -1;
        speedDp = 520;
        state = ST_FLEE;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        viewW = w;
        viewH = h;
        sizePx = Math.min(w, h) * 0.27f;
        inset = sizePx * 0.42f;
        segW = Math.max(10, w - 2 * inset);
        segH = Math.max(10, h - 2 * inset);
        perim = 2 * (segW + segH);
        if (sPos == 0) sPos = segW + segH / 2; // 初始停在右边中间
    }

    /** 路径弧长 → 屏幕坐标（顺时针：上→右→下→左） */
    private float[] posOf(float s) {
        float v = ((s % perim) + perim) % perim;
        if (v < segW) return new float[]{inset + v, inset};
        v -= segW;
        if (v < segH) return new float[]{inset + segW, inset + v};
        v -= segH;
        if (v < segW) return new float[]{inset + segW - v, inset + segH};
        v -= segW;
        return new float[]{inset, inset + segH - v};
    }

    private float wrapDelta(float d) {
        float v = ((d % perim) + perim) % perim;
        return v > perim / 2 ? v - perim : v;
    }

    private float nearestCorner(float s) {
        float[] cs = {0, segW, segW + segH, 2 * segW + segH};
        float best = 0, bd = Float.MAX_VALUE;
        for (float c : cs) {
            float d = Math.abs(wrapDelta(c - s));
            if (d < bd) {
                bd = d;
                best = c;
            }
        }
        return best;
    }

    private boolean nearCorner(float s) {
        return Math.abs(wrapDelta(nearestCorner(s) - s)) < sizePx * 0.7f;
    }

    private void goTo(float target, float spd, int st) {
        sTarget = target;
        dirSign = wrapDelta(target - sPos) >= 0 ? 1 : -1;
        speedDp = spd;
        state = st;
    }

    private void step(float dt, long now) {
        // 形态混合向目标推进
        float blendTarget = (state == ST_CRAWL || state == ST_CHASE
                || state == ST_FLEE) ? 1f : (state == ST_PEEK ? 0.55f : 0f);
        float rate = dt * 3.2f;
        blend += Math.max(-rate, Math.min(rate, blendTarget - blend));

        switch (state) {
            case ST_REST:
                if (now >= stateUntil) decideNext(now);
                break;
            case ST_PEEK:
                if (now >= stateUntil) rest(3000 + rnd.nextInt(4000), now);
                break;
            case ST_SLEEP:
                if (now >= stateUntil) rest(1800, now);
                break;
            case ST_CRAWL:
            case ST_FLEE:
            case ST_CHASE: {
                float target = state == ST_CHASE ? dotS : sTarget;
                float diff = wrapDelta(target - sPos);
                float move = speedDp * density * dt;
                if (state == ST_CHASE) {
                    // 光点也在往前跑
                    dotS += dirSign * 118 * density * dt;
                    if (now > chaseDeadline) {
                        rest(2500, now);
                        break;
                    }
                }
                if (Math.abs(diff) <= Math.max(move, 2)) {
                    sPos = target;
                    if (state == ST_CHASE) {
                        burstS = sPos;
                        burstAt = now;
                        rest(2600 + rnd.nextInt(2500), now);
                    } else if (state == ST_CRAWL && pendingSleep) {
                        pendingSleep = false;
                        state = ST_SLEEP;
                        stateUntil = now + 9000 + rnd.nextInt(6000);
                    } else {
                        rest(3200 + rnd.nextInt(4500), now);
                    }
                } else {
                    sPos += Math.signum(diff) * move;
                }
                break;
            }
        }
        // 光尾采样
        float[] xy = posOf(sPos);
        if (trail.isEmpty() || Math.hypot(
                xy[0] - trail.get(trail.size() - 1)[0],
                xy[1] - trail.get(trail.size() - 1)[1]) > 5 * density) {
            trail.add(new float[]{xy[0], xy[1]});
            if (trail.size() > 16) trail.remove(0);
        }
    }

    private void rest(long durMs, long now) {
        state = ST_REST;
        stateUntil = now + durMs;
    }

    private void decideNext(long now) {
        float r = rnd.nextFloat();
        if (r < 0.52f) {
            // 爬去一个随机停靠点
            goTo(rnd.nextFloat() * perim, 58 + rnd.nextInt(22), ST_CRAWL);
        } else if (r < 0.68f) {
            // 追光点：光点出现在前方一段距离
            dirSign = rnd.nextBoolean() ? 1 : -1;
            dotS = sPos + dirSign * (170 + rnd.nextInt(220)) * density;
            speedDp = 175;
            state = ST_CHASE;
            chaseDeadline = now + 6000;
        } else if (r < 0.83f) {
            // 睡觉：不在角落就先爬去最近角落
            if (nearCorner(sPos)) {
                state = ST_SLEEP;
                stateUntil = now + 9000 + rnd.nextInt(6000);
            } else {
                pendingSleep = true;
                goTo(nearestCorner(sPos), 66, ST_CRAWL);
            }
        } else {
            state = ST_PEEK;
            stateUntil = now + 2200;
        }
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);
        if (bmpOcto == null || bmpRest == null || perim <= 0) return;
        long now = SystemClock.uptimeMillis();
        float t = now / 1000f;
        float[] xy = posOf(sPos);

        // 全息光尾
        int n = trail.size();
        for (int i = 0; i < n; i++) {
            float[] p = trail.get(i);
            float f = (i + 1f) / n;
            int hue = (int) ((t * 46 + i * 16) % 360);
            trailPaint.setColor(Color.HSVToColor(
                    (int) (66 * f * blend), new float[]{hue, 0.55f, 1f}));
            cv.drawCircle(p[0], p[1], (4 + 20 * f) * density * (0.4f + 0.6f * blend),
                    trailPaint);
        }

        // 追逐光点
        if (state == ST_CHASE) {
            float[] dp = posOf(dotS);
            int hue = (int) ((t * 90) % 360);
            trailPaint.setColor(Color.HSVToColor(70, new float[]{hue, 0.4f, 1f}));
            cv.drawCircle(dp[0], dp[1], 15 * density, trailPaint);
            trailPaint.setColor(Color.HSVToColor(235, new float[]{hue, 0.25f, 1f}));
            cv.drawCircle(dp[0], dp[1], 6.5f * density, trailPaint);
        }
        // 抓到迸溅
        if (burstS >= 0 && now - burstAt < 380) {
            float[] bp = posOf(burstS);
            float f = (now - burstAt) / 380f;
            trailPaint.setColor(Color.HSVToColor((int) ((1 - f) * 160),
                    new float[]{(t * 70) % 360, 0.5f, 1f}));
            cv.drawCircle(bp[0], bp[1], (10 + 30 * f) * density, trailPaint);
        } else {
            burstS = -1;
        }

        // 身体：两形态交叉淡入 + 呼吸律动 + 前倾
        float breath = 1f + 0.022f * (float) Math.sin(t * 1.7);
        float wOcto = sizePx * (0.82f + 0.23f * blend) * breath;
        float tilt = 5.5f * (float) Math.sin(t * 1.3) * (0.3f + blend);
        float ox = xy[0], oy = xy[1];
        if (state == ST_PEEK) {
            // 探头：向屏内探出一点
            float[] c = {viewW / 2f, viewH / 2f};
            float dx = c[0] - ox, dy = c[1] - oy;
            float len = (float) Math.hypot(dx, dy);
            if (len > 1) {
                float k = sizePx * 0.16f / len;
                ox += dx * k;
                oy += dy * k;
            }
        }
        if (jumpStart > 0) {
            float jf = t - jumpStart;
            if (jf < 0.45f) {
                oy -= (float) Math.sin(jf / 0.45f * Math.PI) * sizePx * 0.20f;
            } else {
                jumpStart = 0;
            }
        }
        cv.save();
        cv.translate(ox, oy);
        cv.rotate(tilt);
        if (blend < 0.985f && bmpRest != null) {
            paint.setAlpha((int) (255 * (1f - blend)));
            float wr = wOcto * 0.92f * (bmpRest.getWidth() * 1f / bmpOcto.getWidth())
                    * (400f / 300f) * 0.75f;
            float hr = wr * bmpRest.getHeight() / (float) bmpRest.getWidth();
            cv.drawBitmap(bmpRest, -wr / 2, -hr / 2, paint);
        }
        if (blend > 0.015f) {
            paint.setAlpha((int) (255 * blend));
            float ho = wOcto * bmpOcto.getHeight() / (float) bmpOcto.getWidth();
            cv.drawBitmap(bmpOcto, -wOcto / 2, -ho / 2, paint);
        }
        paint.setAlpha(255);
        cv.restore();

        // 睡觉 Zzz
        if (state == ST_SLEEP) {
            for (int i = 0; i < 3; i++) {
                float ph = ((now / 26f + i * 44) % 130) / 130f;
                zPaint.setTextSize((13 + i * 4 + ph * 4) * density);
                zPaint.setAlpha((int) (200 * (1 - ph)));
                cv.drawText("Z", xy[0] + sizePx * 0.28f + ph * 16 * density,
                        xy[1] - sizePx * 0.34f - ph * 44 * density, zPaint);
            }
            zPaint.setAlpha(255);
        }
    }
}
