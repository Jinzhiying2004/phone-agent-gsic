package com.gsic.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v3.31 智能沉淀·报告层：按 1/7/30 天窗口聚合遥测事件，生成 Markdown 报告，
 * 存「我的 → 智能沉淀」供用户一键分享给 Muse 分析后发新版。
 */
public class SedimentReport {

    private static final int[] WINDOWS = {1, 7, 30};
    private static final String CH_ID = "sediment";

    public static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "sediment");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 最近一份报告文件（无则 null） */
    public static File latest(Context ctx) {
        File[] fs = dir(ctx).listFiles();
        if (fs == null || fs.length == 0) return null;
        File best = null;
        for (File f : fs) {
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }

    /** 读报告全文（失败返回 null） */
    public static String readText(File f) {
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
            r.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 全部报告文件（新→旧） */
    public static List<File> all(Context ctx) {
        List<File> out = new ArrayList<>();
        File[] fs = dir(ctx).listFiles();
        if (fs != null) Collections.addAll(out, fs);
        out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    /**
     * 调度检查（App 前台/服务启动时调用，内部按窗口节流）：
     * 某窗口到期且窗口内有事件 → 生成报告并发通知。
     */
    public static void checkSchedule(Context ctx) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(
                    "sediment", Context.MODE_PRIVATE);
            long now = System.currentTimeMillis();
            // 检查节流：最多每小时查一次
            if (now - sp.getLong("last_check", 0) < 3600_000) return;
            sp.edit().putLong("last_check", now).apply();
            Telemetry.prune(ctx);
            // v3.51 步骤5：沉淀前先聚合昨日事件（aggregate-{day}.json，
            // 报告"病因占比"行直接读聚合，不再数点击）
            try {
                Aggregator.writeIfMissing(Telemetry.dir(ctx), Aggregator.yesterday());
            } catch (Exception ignored) {
            }
            for (int days : WINDOWS) {
                long last = sp.getLong("last_gen_" + days, 0);
                if (now - last < days * 24L * 3600 * 1000) continue;
                File f = generate(ctx, days);
                if (f != null) {
                    sp.edit().putLong("last_gen_" + days, now).apply();
                    notifyReady(ctx, days, f);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 聚合最近 days 天事件 → 生成 Markdown 报告文件；无事件返回 null */
    public static File generate(Context ctx, int days) {
        try {
            long since = System.currentTimeMillis() - days * 24L * 3600 * 1000;
            List<JSONObject> evs = readEvents(ctx, since);
            if (evs.isEmpty()) return null;

            int taskTotal = 0, taskOk = 0;
            Map<String, int[]> channels = new HashMap<>(); // ch -> [total, ok]
            Map<String, int[]> tools = new HashMap<>();    // name -> [calls, fails]
            Map<String, Integer> errCount = new HashMap<>();
            List<String> failGoals = new ArrayList<>();
            List<String> routerMiss = new ArrayList<>();
            List<String> chatSamples = new ArrayList<>();
            Map<String, Integer> flags = new HashMap<>();
            int chatCount = 0;
            long durSum = 0;
            int durN = 0;

            for (JSONObject e : evs) {
                String type = e.optString("type");
                switch (type) {
                    case "task_end": {
                        taskTotal++;
                        boolean ok = e.optBoolean("ok");
                        if (ok) taskOk++;
                        String ch = e.optString("ch", "?");
                        int[] c = channels.get(ch);
                        if (c == null) { c = new int[2]; channels.put(ch, c); }
                        c[0]++;
                        if (ok) c[1]++;
                        if (!ok && failGoals.size() < 12) {
                            failGoals.add("- [" + ch + "] "
                                    + Telemetry.cut(e.optString("goal"), 60)
                                    + "（" + e.optString("why") + "）");
                        }
                        long d = e.optLong("ms", 0);
                        if (d > 0) { durSum += d; durN++; }
                        break;
                    }
                    case "tool_call": {
                        String n = e.optString("name", "?");
                        int[] t = tools.get(n);
                        if (t == null) { t = new int[2]; tools.put(n, t); }
                        t[0]++;
                        if (!e.optBoolean("ok")) {
                            t[1]++;
                            String err = Telemetry.cut(e.optString("err"), 50);
                            if (!err.isEmpty()) {
                                String key = n + "：" + err;
                                errCount.put(key, errCount.getOrDefault(key, 0) + 1);
                            }
                        }
                        break;
                    }
                    case "router_miss":
                        if (routerMiss.size() < 10) {
                            routerMiss.add("- " + Telemetry.cut(e.optString("goal"), 40));
                        }
                        break;
                    case "chat":
                        chatCount++;
                        if (chatSamples.size() < 5) {
                            chatSamples.add("- " + Telemetry.cut(e.optString("text"), 40));
                        }
                        break;
                    case "model_flag": {
                        String k = e.optString("flag", "?");
                        flags.put(k, flags.getOrDefault(k, 0) + 1);
                        break;
                    }
                    default:
                        break;
                }
            }

            StringBuilder md = new StringBuilder();
            String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                    .format(new Date());
            md.append("# GSIC 沉淀报告（近 ").append(days).append(" 天，")
                    .append(date).append("）\n");
            md.append("版本：").append(AppInfo.VERSION)
                    .append("；root ").append(AgentLoop.checkRootOnce() ? "有" : "无")
                    .append("；无障碍 ")
                    .append(AgentAccessibilityService.isRunning() ? "开" : "关")
                    .append("\n\n");
            md.append("## 任务\n");
            md.append("- 总数 ").append(taskTotal)
                    .append("，成功 ").append(taskOk)
                    .append("，失败 ").append(taskTotal - taskOk);
            if (durN > 0) md.append("，平均耗时 ").append(durSum / durN / 1000).append(" 秒");
            md.append("\n");
            for (Map.Entry<String, int[]> en : channels.entrySet()) {
                md.append("- ").append(en.getKey()).append("：")
                        .append(en.getValue()[1]).append("/").append(en.getValue()[0])
                        .append(" 成功\n");
            }
            if (!failGoals.isEmpty()) {
                md.append("\n失败任务样本：\n");
                for (String g : failGoals) md.append(g).append("\n");
            }
            md.append("\n## 工具调用（失败率高→优先修）\n");
            List<Map.Entry<String, int[]>> tl = new ArrayList<>(tools.entrySet());
            tl.sort((a, b) -> Integer.compare(
                    b.getValue()[0] == 0 ? 0 : b.getValue()[1] * 100 / b.getValue()[0],
                    a.getValue()[0] == 0 ? 0 : a.getValue()[1] * 100 / a.getValue()[0]));
            for (Map.Entry<String, int[]> en : tl) {
                int[] t = en.getValue();
                md.append("- ").append(en.getKey()).append("：调用 ").append(t[0])
                        .append("，失败 ").append(t[1]);
                if (t[0] > 0) md.append("（失败率 ")
                        .append(t[1] * 100 / t[0]).append("%）");
                md.append("\n");
            }
            // v3.51 步骤5：失败归因直接读聚合——"哪类病因占比最高"
            String failClassLine = topFailClassLine(ctx, days);
            if (failClassLine != null) {
                md.append("\n## 病因占比（遥测聚合）\n").append(failClassLine);
            }
            if (!errCount.isEmpty()) {
                md.append("\n高频错误：\n");
                List<Map.Entry<String, Integer>> el = new ArrayList<>(errCount.entrySet());
                el.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
                int n = 0;
                for (Map.Entry<String, Integer> en : el) {
                    md.append("- ").append(en.getKey()).append(" ×").append(en.getValue()).append("\n");
                    if (++n >= 8) break;
                }
            }
            if (!flags.isEmpty()) {
                md.append("\n## 模型行为标记\n");
                for (Map.Entry<String, Integer> en : flags.entrySet()) {
                    md.append("- ").append(en.getKey()).append("：").append(en.getValue()).append(" 次\n");
                }
            }
            if (!routerMiss.isEmpty()) {
                md.append("\n## 本地路由未命中（考虑加直达/技能卡）\n");
                for (String g : routerMiss) md.append(g).append("\n");
            }
            md.append("\n## 闲聊（").append(chatCount).append(" 次）样本\n");
            for (String g : chatSamples) md.append(g).append("\n");
            md.append("\n---\n本报告将经远控通道由 Muse 自动拉取，它会据此总结并更新软件。\n");

            String name = "sediment-" + date + "-" + days + "d.md";
            File out = new File(dir(ctx), name);
            FileWriter w = new FileWriter(out);
            w.write(md.toString());
            w.close();
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * v3.51 步骤5：读范围内最新的 aggregate-*.json，取 fail_class top-1 占比行。
     * 无聚合文件返回 null（报告降级为不显示该节）。
     */
    private static String topFailClassLine(Context ctx, int days) {
        try {
            long now = System.currentTimeMillis();
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US);
            for (int d = 0; d < days; d++) {
                String day = f.format(new java.util.Date(now - d * 24L * 3600 * 1000));
                JSONObject agg = Aggregator.readAggregate(Telemetry.dir(ctx), day);
                if (agg == null) continue;
                JSONObject tool = agg.optJSONObject("tool_result");
                if (tool == null) continue;
                int fail = tool.optInt("fail", 0);
                if (fail == 0) {
                    return "- 近 " + days + " 天工具调用无失败（" + day + " 聚合）\n";
                }
                String top = tool.optString("top_fail_class", "?");
                JSONObject fc = tool.optJSONObject("fail_class");
                int topN = (fc != null) ? fc.optInt(top, 0) : 0;
                int pct = fail > 0 ? topN * 100 / fail : 0;
                return "- 最高占比病因：" + top + "（" + topN + "/" + fail
                        + "，" + pct + "%，" + day + " 聚合）\n";
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static List<JSONObject> readEvents(Context ctx, long since) {        List<JSONObject> out = new ArrayList<>();
        try {
            File[] fs = Telemetry.dir(ctx).listFiles();
            if (fs == null) return out;
            for (File f : fs) {
                if (!f.getName().startsWith("events-")) continue;
                BufferedReader r = new BufferedReader(new FileReader(f));
                String line;
                while ((line = r.readLine()) != null) {
                    try {
                        JSONObject o = new JSONObject(line);
                        if (o.optLong("ts") >= since) out.add(o);
                    } catch (Exception ignored) {
                    }
                }
                r.close();
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void notifyReady(Context ctx, int days, File f) {
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        CH_ID, "智能沉淀报告", NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            Intent it = new Intent(ctx, MainActivity.class);
            it.putExtra("open_sediment", true);
            it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(ctx, 31, it,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(ctx, CH_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("🧠 智能沉淀报告已生成")
                    .setContentText("近 " + days + " 天运行数据已汇总，Muse 会经远控自动拉取分析")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(3100 + days, n);
        } catch (Exception ignored) {
        }
    }
}
