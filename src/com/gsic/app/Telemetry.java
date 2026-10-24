package com.gsic.app;

import android.content.Context;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * v3.31 智能沉淀·遥测层：运行事件按天落盘（JSONL）。
 * v3.51 记忆分层步骤5：schema 改造——
 *   原始 events-*.jsonl 保留 7 天；每日聚合 aggregate-*.json 保留 30 天。
 *   新事件类型：decision_point / tool_result / model_call / supervisory / step_env；
 *   旧 task_start / task_end / tool_call / model_flag / chat / router_miss / task_clarify 保留兼容。
 * 数据只在本机，用户主动分享沉淀报告才会离开手机。
 */
public class Telemetry {

    private static final Object LOCK = new Object();
    /** 原始事件保留期（天）：events-*.jsonl */
    private static final int RAW_KEEP_DAYS = 7;
    /** 每日聚合保留期（天）：aggregate-*.json */
    private static final int AGG_KEEP_DAYS = 30;

    public static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "telemetry");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** 记录一个事件（异步安全：内部同步追加一行） */
    public static void event(Context ctx, String channel, String type, JSONObject data) {
        try {
            JSONObject o = new JSONObject();
            o.put("ts", System.currentTimeMillis());
            o.put("ch", channel == null ? "unknown" : channel);
            o.put("type", type);
            if (data != null) {
                java.util.Iterator<String> it = data.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    o.put(k, data.get(k));
                }
            }
            String day = new SimpleDateFormat("yyyyMMdd", Locale.US)
                    .format(new Date());
            File f = new File(dir(ctx), "events-" + day + ".jsonl");
            synchronized (LOCK) {
                FileWriter w = new FileWriter(f, true);
                w.write(o.toString());
                w.write("\n");
                w.close();
            }
        } catch (Exception ignored) {
        }
    }

    /** 清理过期文件：原始事件 7 天，聚合 30 天 */
    public static void prune(Context ctx) {
        try {
            File[] fs = dir(ctx).listFiles();
            if (fs == null) return;
            long now = System.currentTimeMillis();
            long rawCut = now - RAW_KEEP_DAYS * 24L * 3600 * 1000;
            long aggCut = now - AGG_KEEP_DAYS * 24L * 3600 * 1000;
            for (File f : fs) {
                String n = f.getName();
                if (n.startsWith("events-") && f.lastModified() < rawCut) f.delete();
                else if (n.startsWith("aggregate-") && f.lastModified() < aggCut) f.delete();
            }
        } catch (Exception ignored) {
        }
    }

    /** 截断任意长文本（落盘控量） */
    public static String cut(String s, int max) {
        if (s == null) return "";
        s = s.replace("\n", " ");
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    // ---------------- v3.51 步骤5：新事件类型 helper ----------------

    /** 决策点：路由命中与分数、意图解析、任务书置信度、计划分步 */
    public static void decisionPoint(Context ctx, String ch,
                                    String routeName, double routeScore,
                                    String intentObject, String intentAction,
                                    double taskbookConfidence,
                                    java.util.List<String> planSteps) {
        try {
            JSONObject d = new JSONObject();
            d.put("route_name", routeName == null ? "" : routeName);
            d.put("route_score", routeScore);
            d.put("intent_object", cut(intentObject, 80));
            d.put("intent_action", cut(intentAction, 80));
            d.put("taskbook_confidence", taskbookConfidence);
            org.json.JSONArray ps = new org.json.JSONArray();
            if (planSteps != null) {
                for (String s : planSteps) ps.put(cut(s, 100));
            }
            d.put("plan_steps", ps);
            event(ctx, ch, "decision_point", d);
        } catch (Exception ignored) {
        }
    }

    /**
     * 工具执行结果。screen_delta：动作前后画面变化量
     * （8x8 均值哈希汉明距离；无截图时 -1）。detail 截断 200 字。
     */
    public static void toolResult(Context ctx, String ch,
                                  String action, boolean ok, String failClass,
                                  int screenDelta, long ms, String detail) {
        try {
            JSONObject d = new JSONObject();
            d.put("action", action == null ? "" : action);
            d.put("ok", ok);
            if (failClass != null && !failClass.isEmpty()) d.put("fail_class", failClass);
            d.put("screen_delta", screenDelta);
            d.put("ms", ms);
            d.put("detail", cut(detail, 200));
            event(ctx, ch, "tool_result", d);
        } catch (Exception ignored) {
        }
    }

    /** 模型调用画像：不记 prompt 原文；token 未知时为 -1 */
    public static void modelCall(Context ctx, String ch,
                                 String model, long latencyMs,
                                 long tokIn, long tokOut,
                                 boolean timeout, int retry) {
        try {
            JSONObject d = new JSONObject();
            d.put("model", model == null ? "" : model);
            d.put("latency_ms", latencyMs);
            d.put("tok_in", tokIn);
            d.put("tok_out", tokOut);
            d.put("timeout", timeout);
            d.put("retry", retry);
            event(ctx, ch, "model_call", d);
        } catch (Exception ignored) {
        }
    }

    /** 监管事件：看门狗触发规则、介入/终审裁决与依据 */
    public static void supervisory(Context ctx, String ch,
                                   String rule, String verdict, String basis) {
        try {
            JSONObject d = new JSONObject();
            d.put("rule", rule == null ? "" : rule);
            d.put("verdict", verdict == null ? "" : verdict);
            d.put("basis", cut(basis, 120));
            event(ctx, ch, "supervisory", d);
        } catch (Exception ignored) {
        }
    }

    /** 每步环境：前台包名、网络是否通、本步耗时、任务累计耗时 */
    public static void stepEnv(Context ctx, String ch,
                               String fgApp, boolean netOk,
                               long stepMs, long taskElapsedMs) {
        try {
            JSONObject d = new JSONObject();
            d.put("fg_app", fgApp == null ? "" : fgApp);
            d.put("net_ok", netOk);
            d.put("step_ms", stepMs);
            d.put("task_elapsed_ms", taskElapsedMs);
            event(ctx, ch, "step_env", d);
        } catch (Exception ignored) {
        }
    }

    /**
     * 失败病因分类（供 Distiller 步骤5 遥测回填 + 聚合归因）。
     * ok=true 返回 null；否则按错误文本关键词归类，未命中返回 "unknown"。
     * fail_class 枚举：coord_off / element_missing / page_not_ready /
     * permission_denied / timeout / unknown。
     */
    public static String classifyFail(String action, boolean ok, String text) {
        if (ok) return null;
        String t = (text == null ? "" : text);
        String low = t.toLowerCase();
        if (t.contains("超时") || low.contains("timeout") || low.contains("timed out")
                || low.contains("sockettimeout")) return "timeout";
        if (t.contains("权限") || low.contains("permission")) return "permission_denied";
        if (t.contains("未加载") || t.contains("加载中") || low.contains("not ready"))
            return "page_not_ready";
        if (t.contains("未找到") || t.contains("不存在") || low.contains("not found")
                || low.contains("nosuchelement")) return "element_missing";
        if (t.contains("偏") || t.contains("偏移") || low.contains("offset")) return "coord_off";
        return "unknown";
    }
}
