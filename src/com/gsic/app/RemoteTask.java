package com.gsic.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 远控目标式任务（/task goal 模式）的实时状态。
 *
 * <p>手机端 AgentLoop 跑在后台线程，思考过程（💭）与执行日志经 Listener
 * 写入这里；云端用 {@code GET /task/status} 轮询，App 内远控页用卡片展示，
 * 做到"边跑边看"，不再是跑完才一次性返回。</p>
 *
 * <p>纯 Java + org.json，可独立 JVM 单测（step/think 解析逻辑）。</p>
 */
public class RemoteTask {

    private static int seq = 0;

    public final String taskId;
    public final String goal;
    public final int maxSteps;
    public final long startTime = System.currentTimeMillis();
    public final List<String> logs =
            Collections.synchronizedList(new ArrayList<String>());
    public volatile boolean done = false;
    public volatile boolean ok = false;
    public volatile String error = "";
    /** v3.21：远程停止请求（/task/stop 置位，AgentLoop 每轮检查） */
    public volatile boolean stopRequested = false;
    public volatile JSONArray audit = null;
    // v3.50 第 3 步：低置信拦截状态（/task 返回 needs_clarify，云端决定如何澄清）
    public volatile boolean needsClarify = false;
    public volatile String clarifyQuestion = "";
    public final List<String> clarifyOptions =
            Collections.synchronizedList(new ArrayList<String>());

    public RemoteTask(String goal, int maxSteps) {
        this.taskId = "rt-" + (++seq) + "-" + (startTime % 100000);
        this.goal = goal;
        this.maxSteps = maxSteps;
    }

    public void log(String m) {
        if (m != null) logs.add(m);
    }

    /** 当前步数：从最近一条"🤔 第 N 步"日志解析；无则为 0 */
    public int step() {
        synchronized (logs) {
            for (int i = logs.size() - 1; i >= 0; i--) {
                String l = logs.get(i);
                int a = l.indexOf("第 ");
                int b = l.indexOf(" 步", a);
                if (l.startsWith("🤔") && a > 0 && b > a) {
                    try {
                        return Integer.parseInt(
                                l.substring(a + 2, b).trim());
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return 0;
    }

    /** 最近一次思考：最近一条"💭 ..."日志的正文；无则为空 */
    public String think() {
        synchronized (logs) {
            for (int i = logs.size() - 1; i >= 0; i--) {
                String l = logs.get(i);
                int p = l.indexOf("💭");
                if (p >= 0) return l.substring(p + 2).trim();
            }
        }
        return "";
    }

    /** 最近 n 条日志（按时间顺序） */
    public List<String> tail(int n) {
        synchronized (logs) {
            int from = Math.max(0, logs.size() - n);
            return new ArrayList<>(logs.subList(from, logs.size()));
        }
    }

    public long elapsedSec() {
        return (System.currentTimeMillis() - startTime) / 1000;
    }

    /** 供 /task/status 与 App 内卡片共用的 JSON */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", true);
            o.put("taskId", taskId);
            o.put("goal", goal);
            o.put("maxSteps", maxSteps);
            o.put("done", done);
            o.put("resultOk", ok);
            if (!error.isEmpty()) o.put("error", error);
            o.put("step", step());
            o.put("think", think());
            o.put("elapsedSec", elapsedSec());
            JSONArray arr = new JSONArray();
            for (String l : tail(25)) arr.put(l);
            o.put("log", arr);
            if (audit != null) o.put("audit", audit);
            // v3.50 第 3 步：低置信拦截时带上澄清问题与选项
            if (needsClarify) {
                o.put("needs_clarify", true);
                o.put("question", clarifyQuestion);
                JSONArray opts = new JSONArray();
                synchronized (clarifyOptions) {
                    for (String s : clarifyOptions) opts.put(s);
                }
                o.put("options", opts);
            }
        } catch (Exception ignored) {
        }
        return o;
    }
}
