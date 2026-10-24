package com.gsic.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 核心感知与执行层：读屏（剪枝后的结构化节点）+ 点击/手势/输入。
 * 全部在手机本地完成，不经过大模型 —— 这就是零 token 的关键。
 */
public class AgentAccessibilityService extends AccessibilityService {

    public static class NodeInfo {
        public String text = "";
        public String desc = "";
        public boolean clickable;
        public int cx, cy;
        public int area;
        AccessibilityNodeInfo node; // 仅点击时使用
    }

    private static volatile AgentAccessibilityService instance;

    public static boolean isRunning() {
        return instance != null;
    }

    /** 供 ElementDumper 等获取服务实例 */
    public static AgentAccessibilityService get() {
        return instance;
    }

    /**
     * 无障碍截屏（API 30+，无需 root）。
     * 同步等待最多 10 秒，成功返回 Bitmap，失败返回 null。
     */
    public static android.graphics.Bitmap screenshot() {
        AgentAccessibilityService s = instance;
        if (s == null || Build.VERSION.SDK_INT < 30) return null;
        final android.graphics.Bitmap[] out = new android.graphics.Bitmap[1];
        final CountDownLatch latch = new CountDownLatch(1);
        java.util.concurrent.Executor exec =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            s.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, exec,
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult result) {
                            try {
                                android.hardware.HardwareBuffer hb =
                                        result.getHardwareBuffer();
                                android.graphics.Bitmap hw =
                                        android.graphics.Bitmap.wrapHardwareBuffer(
                                                hb, result.getColorSpace());
                                if (hw != null) {
                                    out[0] = hw.copy(
                                            android.graphics.Bitmap.Config.ARGB_8888,
                                            false);
                                }
                                hb.close();
                            } catch (Exception ignored) {
                            } finally {
                                latch.countDown();
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            latch.countDown();
                        }
                    });
            latch.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        } finally {
            ((java.util.concurrent.ExecutorService) exec).shutdown();
        }
        return out[0];
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        // v3.22-hotfix：开机自启远控服务。无障碍服务由系统在开机后自动绑定，
        // 在此顺手拉起 AgentService（热补丁方案，无需改 Manifest）。
        // 注意：仅在用户已启用无障碍服务时生效。
        try {
            android.content.Intent i = new android.content.Intent(
                    this, AgentService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            android.util.Log.i("AgentA11y",
                    "已拉起 AgentService（开机自启）");
        } catch (Exception e) {
            android.util.Log.w("AgentA11y", "拉起 AgentService 失败: " + e);
        }
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    /** 抓屏并剪枝：只保留可点击或有文本/描述的节点 */
    public static List<NodeInfo> dumpScreen() {
        List<NodeInfo> out = new ArrayList<>();
        AgentAccessibilityService s = instance;
        if (s == null) return out;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return out;
        walk(root, out, new Rect());
        return out;
    }

    private static void walk(AccessibilityNodeInfo n, List<NodeInfo> out, Rect r) {
        if (n == null) return;
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        String text = t == null ? "" : t.toString().trim();
        String desc = d == null ? "" : d.toString().trim();
        boolean clickable = n.isClickable();
        if (clickable || !text.isEmpty() || !desc.isEmpty()) {
            n.getBoundsInScreen(r);
            int w = r.width(), h = r.height();
            if (w > 0 && h > 0) {
                NodeInfo ni = new NodeInfo();
                ni.text = text;
                ni.desc = desc;
                ni.clickable = clickable;
                ni.cx = r.centerX();
                ni.cy = r.centerY();
                ni.area = w * h;
                ni.node = n;
                out.add(ni);
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            walk(n.getChild(i), out, r);
        }
    }

    /** 按关键词找节点：优先完全匹配、优先可点击、优先面积小 */
    public static NodeInfo find(List<NodeInfo> items, String keyword) {
        NodeInfo best = null;
        long bestScore = Long.MAX_VALUE;
        for (NodeInfo it : items) {
            if (!it.text.contains(keyword) && !it.desc.contains(keyword)) continue;
            long score = it.area;
            if (!it.clickable) score += 10_000_000L;
            if (!(it.text.equals(keyword) || it.desc.equals(keyword))) score += 1_000_000_000L;
            if (score < bestScore) {
                bestScore = score;
                best = it;
            }
        }
        return best;
    }

    /** 点击节点：自身不可点则向上找可点击的父节点 */
    public static boolean clickNode(NodeInfo ni) {
        AccessibilityNodeInfo n = ni.node;
        while (n != null) {
            if (n.isClickable()) {
                return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            n = n.getParent();
        }
        return false;
    }

    public static boolean tapAt(int x, int y) {
        return gesture(x, y, x, y, 80);
    }

    public static boolean swipe(int x1, int y1, int x2, int y2, int ms) {
        return gesture(x1, y1, x2, y2, ms);
    }

    private static boolean gesture(int x1, int y1, int x2, int y2, int ms) {
        AgentAccessibilityService s = instance;
        if (s == null || Build.VERSION.SDK_INT < 24) return false;
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, Math.max(ms, 50));
        GestureDescription gd = new GestureDescription.Builder().addStroke(stroke).build();
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        s.dispatchGesture(gd, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription g) {
                ok[0] = true;
                latch.countDown();
            }

            @Override
            public void onCancelled(GestureDescription g) {
                latch.countDown();
            }
        }, null);
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok[0];
    }

    public static boolean goBack() {
        AgentAccessibilityService s = instance;
        return s != null && s.performGlobalAction(GLOBAL_ACTION_BACK);
    }

    /** 向当前输入框写入文本（支持中文），比 input text 可靠得多 */
    public static boolean setText(String text) {
        AgentAccessibilityService s = instance;
        if (s == null) return false;
        AccessibilityNodeInfo root = s.getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo target = findEditable(root);
        if (target == null) {
            target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        }
        if (target == null) return false;
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }

    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isEditable() && n.isFocused()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findEditable(n.getChild(i));
            if (r != null) return r;
        }
        return null;
    }
}
