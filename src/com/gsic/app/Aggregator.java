package com.gsic.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * v3.51 记忆分层步骤5：遥测聚合器。
 *
 * 纯 Java，无 Android 依赖，可 JVM 单测。
 * 每日生成 aggregate-{day}.json：
 *   fail_class 计数，模型画像均值（延迟/token/超时率），监管触发计数，
 *   各步耗时分布，任务总时长构成。
 * 夜间沉淀（sediment）改读聚合 + active 认知条目，失败归因直接出
 * "哪类病因占比最高"，不再数点击。
 */
public class Aggregator {

    /** 聚合指定天的 events-{day}.jsonl；文件缺失返回仅含骨架的空对象 */
    public static JSONObject aggregate(File telemetryDir, String day) {
        JSONObject out = new JSONObject();
        try {
            aggregateInto(out, telemetryDir, day);
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void aggregateInto(JSONObject out, File telemetryDir, String day)
            throws Exception {
        out.put("day", day);
        out.put("generated_at", System.currentTimeMillis());

        List<JSONObject> evs = readDay(telemetryDir, day);
        out.put("events", evs.size());

        Map<String, Integer> channels = new TreeMap<String, Integer>();
        int decisionPoints = 0;

        // tool_result
        int toolTotal = 0, toolOk = 0;
        Map<String, Integer> failClass = new TreeMap<String, Integer>();

        // model_call
        int modelCalls = 0;
        long latSum = 0;
        long tokInSum = 0, tokOutSum = 0;
        int tokN = 0, timeouts = 0;

        // supervisory
        int supTotal = 0;
        Map<String, Integer> supByRule = new TreeMap<String, Integer>();
        Map<String, Integer> supVerdicts = new TreeMap<String, Integer>();

        // step_env
        List<Long> stepMs = new ArrayList<Long>();

        // task_start/task_end 按通道 FIFO 配对算时长
        Map<String, List<Long>> pendingStart = new HashMap<String, List<Long>>();
        List<Long> taskDur = new ArrayList<Long>();
        int taskUnpairedEnd = 0;

        for (JSONObject e : evs) {
            String ch = e.optString("ch", "?");
            bump(channels, ch);
            String type = e.optString("type", "");
            if ("decision_point".equals(type)) {
                decisionPoints++;
            } else if ("tool_result".equals(type)) {
                toolTotal++;
                if (e.optBoolean("ok", false)) {
                    toolOk++;
                } else {
                    bump(failClass, e.optString("fail_class", "unknown"));
                }
            } else if ("model_call".equals(type)) {
                modelCalls++;
                long lat = e.optLong("latency_ms", -1);
                if (lat >= 0) latSum += lat;
                long ti = e.optLong("tok_in", -1), to = e.optLong("tok_out", -1);
                if (ti >= 0 && to >= 0) { tokInSum += ti; tokOutSum += to; tokN++; }
                if (e.optBoolean("timeout", false)) timeouts++;
            } else if ("supervisory".equals(type)) {
                supTotal++;
                bump(supByRule, e.optString("rule", "?"));
                bump(supVerdicts, e.optString("verdict", "?"));
            } else if ("step_env".equals(type)) {
                long ms = e.optLong("step_ms", -1);
                if (ms >= 0) stepMs.add(ms);
            } else if ("task_start".equals(type)) {
                List<Long> q = pendingStart.get(ch);
                if (q == null) { q = new ArrayList<Long>(); pendingStart.put(ch, q); }
                q.add(e.optLong("ts", 0));
            } else if ("task_end".equals(type)) {
                List<Long> q = pendingStart.get(ch);
                if (q != null && !q.isEmpty()) {
                    long s = q.remove(0);
                    long end = e.optLong("ts", 0);
                    if (end > s) taskDur.add(end - s);
                } else {
                    taskUnpairedEnd++;
                }
            }
        }

        out.put("channels", new JSONObject(channels));
        out.put("decision_points", decisionPoints);

        JSONObject tool = new JSONObject();
        tool.put("total", toolTotal);
        tool.put("ok", toolOk);
        tool.put("fail", toolTotal - toolOk);
        tool.put("fail_class", new JSONObject(failClass));
        tool.put("top_fail_class", topKey(failClass));
        out.put("tool_result", tool);

        JSONObject model = new JSONObject();
        model.put("calls", modelCalls);
        model.put("avg_latency_ms", modelCalls == 0 ? -1 : latSum / modelCalls);
        model.put("avg_tok_in", tokN == 0 ? -1 : tokInSum / tokN);
        model.put("avg_tok_out", tokN == 0 ? -1 : tokOutSum / tokN);
        model.put("timeout_rate", modelCalls == 0 ? 0.0
                : ((double) timeouts) / modelCalls);
        out.put("model", model);

        JSONObject sup = new JSONObject();
        sup.put("total", supTotal);
        sup.put("by_rule", new JSONObject(supByRule));
        sup.put("verdicts", new JSONObject(supVerdicts));
        out.put("supervisory", sup);

        JSONObject steps = new JSONObject();
        steps.put("count", stepMs.size());
        if (!stepMs.isEmpty()) {
            java.util.Collections.sort(stepMs);
            long sum = 0, min = Long.MAX_VALUE, max = 0;
            for (long v : stepMs) { sum += v; if (v < min) min = v; if (v > max) max = v; }
            steps.put("avg_ms", sum / stepMs.size());
            steps.put("min_ms", min);
            steps.put("max_ms", max);
            steps.put("p50_ms", stepMs.get(stepMs.size() / 2));
            steps.put("p90_ms", stepMs.get((int) (stepMs.size() * 0.9)));
        }
        out.put("steps", steps);

        JSONObject tasks = new JSONObject();
        tasks.put("count", taskDur.size());
        tasks.put("unpaired_end", taskUnpairedEnd);
        if (!taskDur.isEmpty()) {
            java.util.Collections.sort(taskDur);
            long sum = 0, min = Long.MAX_VALUE, max = 0;
            for (long v : taskDur) { sum += v; if (v < min) min = v; if (v > max) max = v; }
            tasks.put("avg_ms", sum / taskDur.size());
            tasks.put("min_ms", min);
            tasks.put("max_ms", max);
            tasks.put("p50_ms", taskDur.get(taskDur.size() / 2));
        }
        out.put("tasks", tasks);
    }

    /**
     * 聚合并落盘 aggregate-{day}.json；已存在则不覆盖。
     * 返回写入（或已存在）的文件；失败返回 null。
     */
    public static File writeIfMissing(File telemetryDir, String day) {
        try {
            File f = new File(telemetryDir, "aggregate-" + day + ".json");
            if (f.exists()) return f;
            JSONObject agg = aggregate(telemetryDir, day);
            if (agg.optInt("events", 0) == 0) return null; // 无事件不落空文件
            FileWriter w = new FileWriter(f);
            w.write(agg.toString(2));
            w.write("\n");
            w.close();
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    /** 读某天 aggregate 文件（供沉淀报告"病因占比"行用），缺失返回 null */
    public static JSONObject readAggregate(File telemetryDir, String day) {
        try {
            File f = new File(telemetryDir, "aggregate-" + day + ".json");
            if (!f.exists()) return null;
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            r.close();
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            return null;
        }
    }

    /** 当天日期串 yyyyMMdd */
    public static String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
    }

    /** 昨天日期串 yyyyMMdd */
    public static String yesterday() {
        return new SimpleDateFormat("yyyyMMdd", Locale.US)
                .format(new Date(System.currentTimeMillis() - 24L * 3600 * 1000));
    }

    // ---------------- 内部 ----------------

    private static List<JSONObject> readDay(File dir, String day) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        try {
            File f = new File(dir, "events-" + day + ".jsonl");
            if (!f.exists()) return out;
            BufferedReader r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try { out.add(new JSONObject(line)); } catch (Exception ignored) {}
            }
            r.close();
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void bump(Map<String, Integer> m, String k) {
        if (k == null || k.isEmpty()) k = "?";
        Integer v = m.get(k);
        m.put(k, v == null ? 1 : v + 1);
    }

    private static String topKey(Map<String, Integer> m) {
        String best = "";
        int bestN = 0;
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            if (e.getValue() > bestN) { bestN = e.getValue(); best = e.getKey(); }
        }
        return best;
    }
}
