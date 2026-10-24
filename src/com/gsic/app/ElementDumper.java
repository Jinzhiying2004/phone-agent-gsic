package com.gsic.app;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 界面元素抓取：比纯文本更详细，自主识别可点击元素。
 * 输出：类名 / 文本 / 描述 / resource-id /  bounds / 是否可点。
 */
public class ElementDumper {

    public static class El {
        public int index;
        public String cls = "";
        public String text = "";
        public String desc = "";
        public String resId = "";
        public Rect bounds = new Rect();
        public boolean clickable;
    }

    /** 抓取当前前台界面的所有元素 */
    public static List<El> capture() {
        List<El> out = new ArrayList<>();
        try {
            AgentAccessibilityService s = AgentAccessibilityService.get();
            if (s == null) return out;
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null) return out;
            walk(root, out, new Rect());
            for (int i = 0; i < out.size(); i++) out.get(i).index = i;
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void walk(AccessibilityNodeInfo n, List<El> out, Rect r) {
        if (n == null) return;
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        String text = t == null ? "" : t.toString().trim();
        String desc = d == null ? "" : d.toString().trim();
        boolean clickable = n.isClickable();
        // 有文本/描述，或可点击的才收录
        if (clickable || !text.isEmpty() || !desc.isEmpty()) {
            n.getBoundsInScreen(r);
            if (r.width() > 0 && r.height() > 0) {
                El e = new El();
                CharSequence cn = n.getClassName();
                String full = cn == null ? "" : cn.toString();
                int dot = full.lastIndexOf('.');
                e.cls = dot >= 0 ? full.substring(dot + 1) : full;
                e.text = text;
                e.desc = desc;
                try {
                    String rid = n.getViewIdResourceName();
                    if (rid != null) {
                        int slash = rid.lastIndexOf('/');
                        e.resId = slash >= 0 ? rid.substring(slash + 1) : rid;
                    }
                } catch (Exception ignored) {
                }
                e.bounds = new Rect(r);
                e.clickable = clickable;
                out.add(e);
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            walk(n.getChild(i), out, r);
        }
    }

    /** 格式化输出：可点击元素优先展示 */
    public static String format(List<El> els) {
        if (els.isEmpty()) return "未抓到元素（确认无障碍已开启并切到目标应用）";
        List<El> clickables = new ArrayList<>();
        List<El> others = new ArrayList<>();
        for (El e : els) {
            if (e.clickable) clickables.add(e);
            else others.add(e);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("📱 界面元素（共 ").append(els.size())
                .append(" 个，可点击 ").append(clickables.size()).append(" 个）\n");
        if (!clickables.isEmpty()) {
            sb.append("\n—— 可点击元素 ——\n");
            for (El e : clickables) sb.append(line(e)).append("\n");
        }
        if (!others.isEmpty()) {
            sb.append("\n—— 文本元素 ——\n");
            int n = 0;
            for (El e : others) {
                if (n++ >= 30) {
                    sb.append("…还有 ").append(others.size() - 30).append(" 个\n");
                    break;
                }
                sb.append(line(e)).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private static String line(El e) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(e.index).append("] ").append(e.cls);
        String label = !e.text.isEmpty() ? e.text : e.desc;
        if (!label.isEmpty()) {
            if (label.length() > 20) label = label.substring(0, 20) + "…";
            sb.append(" \"").append(label).append("\"");
        }
        if (!e.resId.isEmpty()) sb.append(" id=").append(e.resId);
        sb.append(" [").append(e.bounds.left).append(",").append(e.bounds.top)
                .append("][")
                .append(e.bounds.right).append(",").append(e.bounds.bottom)
                .append("]");
        return sb.toString();
    }

    /** 一键抓取并格式化 */
    public static String dump() {
        return format(capture());
    }
}
