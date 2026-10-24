package com.gsic.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 结构化任务计划：子任务拆解 + 每子任务的试错记录 + 检查点。
 * 纯 Java（仅依赖 org.json），无 Android 依赖，可在 JVM 直接单测。
 *
 * 设计：
 * - 拆解：任务开始时由一次规划调用（或思考者规划文本）生成子任务列表，
 *   每个子任务有 goal（目标）与 verify（完成验证标准），状态机推进。
 * - 试错：记录每个子任务试过且失败的动作签名，prompt 注入 + 循环层拦截，
 *   同一子任务内不许重复已失败的方法。
 * - 自恢复：子任务完成时记检查点；卡死时可回退到最近检查点续跑。
 */
public class TaskPlan {

    public static final int ST_PENDING = 0;
    public static final int ST_DOING = 1;
    public static final int ST_DONE = 2;
    public static final int ST_FAILED = 3;

    /** 单个子任务连续尝试上限（超了标记失败、跳过） */
    public static final int MAX_ATTEMPTS = 6;

    public static class Subtask {
        public String goal = "";
        public String verify = "";
        public int status = ST_PENDING;
        public int attempts = 0;
        /** 试过且失败的动作签名（do:text/id/cmd），成功后洗白 */
        public final Set<String> triedFailed = new LinkedHashSet<>();
    }

    public static class Checkpoint {
        public int subtask;
        public String screenFp = "";
        public int step;
    }

    private final List<Subtask> subtasks = new ArrayList<>();
    private int current = 0;
    private final List<Checkpoint> checkpoints = new ArrayList<>();

    public boolean empty() {
        return subtasks.isEmpty();
    }

    public int size() {
        return subtasks.size();
    }

    public int current() {
        return current;
    }

    public Subtask currentTask() {
        return (current >= 0 && current < subtasks.size())
                ? subtasks.get(current) : null;
    }

    public List<Subtask> all() {
        return subtasks;
    }

    public int checkpointCount() {
        return checkpoints.size();
    }

    /** 从规划 LLM 调用的 JSON 解析：{"subtasks":[{"goal":"..","verify":".."}]} */
    public static TaskPlan fromJson(String json) {
        TaskPlan p = new TaskPlan();
        try {
            JSONObject o = new JSONObject(json);
            JSONArray arr = o.optJSONArray("subtasks");
            if (arr == null) return p;
            for (int i = 0; i < arr.length() && i < 8; i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) continue;
                Subtask t = new Subtask();
                t.goal = s.optString("goal", "").trim();
                t.verify = s.optString("verify", "").trim();
                if (!t.goal.isEmpty()) p.subtasks.add(t);
            }
        } catch (Exception ignored) {
        }
        return p;
    }

    /** 思考者规划文本的本地启发式切分：按行拆编号/分点 */
    public static TaskPlan fromThinkerText(String text) {
        TaskPlan p = new TaskPlan();
        if (text == null) return p;
        for (String line : text.split("\n")) {
            String t = line.trim()
                    .replaceFirst("^[\\d\uFF10-\uFF19]+[.、:\\)\\]]\\s*", "")
                    .replaceFirst("^[-*\u2022]\\s*", "")
                    .trim();
            if (t.length() < 2) continue;
            if (t.length() > 60) t = t.substring(0, 60);
            Subtask s = new Subtask();
            s.goal = t;
            p.subtasks.add(s);
            if (p.subtasks.size() >= 8) break;
        }
        if (p.subtasks.isEmpty()) {
            Subtask s = new Subtask();
            String t = text.trim();
            s.goal = t.length() > 60 ? t.substring(0, 60) : t;
            p.subtasks.add(s);
        }
        return p;
    }

    /** 纯字符串列表转计划（兼容旧 todoSteps） */
    public static TaskPlan fromGoals(List<String> goals) {
        TaskPlan p = new TaskPlan();
        if (goals != null) {
            for (String g : goals) {
                if (g == null || g.trim().isEmpty()) continue;
                Subtask s = new Subtask();
                s.goal = g.trim();
                p.subtasks.add(s);
                if (p.subtasks.size() >= 8) break;
            }
        }
        return p;
    }

    public static TaskPlan single(String goal) {
        TaskPlan p = new TaskPlan();
        Subtask s = new Subtask();
        s.goal = goal == null ? "" : goal;
        p.subtasks.add(s);
        return p;
    }

    public void start() {
        if (!subtasks.isEmpty()) {
            current = 0;
            subtasks.get(0).status = ST_DOING;
        }
    }

    public void markDone() {
        Subtask t = currentTask();
        if (t != null) t.status = ST_DONE;
        advance();
    }

    public void markFailed() {
        Subtask t = currentTask();
        if (t != null) t.status = ST_FAILED;
        advance();
    }

    private void advance() {
        current++;
        if (current < subtasks.size()) subtasks.get(current).status = ST_DOING;
    }

    public boolean allDone() {
        return current >= subtasks.size();
    }

    public boolean anyFailed() {
        for (Subtask t : subtasks) {
            if (t.status == ST_FAILED) return true;
        }
        return false;
    }

    /**
     * 回退到最近检查点：检查点之后的子任务重置为待做、清空试错记录。
     * 返回 false 表示无可用检查点。
     */
    public boolean rollbackToCheckpoint() {
        if (checkpoints.isEmpty()) return false;
        Checkpoint cp = checkpoints.get(checkpoints.size() - 1);
        if (cp.subtask < 0 || cp.subtask >= subtasks.size()) return false;
        for (int i = cp.subtask; i < subtasks.size(); i++) {
            Subtask t = subtasks.get(i);
            t.status = ST_PENDING;
            t.triedFailed.clear();
            t.attempts = 0;
        }
        current = cp.subtask;
        subtasks.get(current).status = ST_DOING;
        return true;
    }

    public void checkpoint(int step, String screenFp) {
        Checkpoint cp = new Checkpoint();
        cp.subtask = current;
        cp.screenFp = screenFp == null ? "" : screenFp;
        cp.step = step;
        checkpoints.add(cp);
        if (checkpoints.size() > 10) checkpoints.remove(0);
    }

    /** 记录一次尝试；ok=false 记入失败集合，成功则洗白该签名 */
    public void recordTried(String sig, boolean ok) {
        Subtask t = currentTask();
        if (t == null || sig == null) return;
        t.attempts++;
        if (!ok) t.triedFailed.add(sig);
        else t.triedFailed.remove(sig);
    }

    public boolean triedFailed(String sig) {
        Subtask t = currentTask();
        return t != null && sig != null && t.triedFailed.contains(sig);
    }

    public String triedFailedText() {
        Subtask t = currentTask();
        if (t == null || t.triedFailed.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String s : t.triedFailed) sb.append(s).append("✗，");
        return sb.toString();
    }

    /** prompt 注入：计划状态 + 当前子任务验证标准 + 已试失败方法 */
    public String describe() {
        if (subtasks.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n📋 任务计划 (").append(Math.min(current + 1, subtasks.size()))
                .append("/").append(subtasks.size()).append(")：\n");
        for (int i = 0; i < subtasks.size(); i++) {
            Subtask t = subtasks.get(i);
            String icon = t.status == ST_DONE ? "✅ "
                    : t.status == ST_FAILED ? "❌ "
                    : i == current ? "▶ " : "⏳ ";
            sb.append(icon).append(t.goal);
            if (i == current && !t.verify.isEmpty()) {
                sb.append("（验证：").append(t.verify).append(")");
            }
            sb.append("\n");
        }
        String tf = triedFailedText();
        if (!tf.isEmpty()) {
            sb.append("⚠ 本子任务已试过且失败：").append(tf)
                    .append("\n这些方法不许再用，必须换没试过的招。\n");
        }
        return sb.toString();
    }
}
