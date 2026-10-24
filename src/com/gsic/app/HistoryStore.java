package com.gsic.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 运行记录：每次任务执行的时间 / 技能 / 结果，保留最近 100 条。 */
public class HistoryStore {

    public static class Entry {
        public long t;
        /** 类型：task=思考执行员任务，skill=技能，card=技能卡 */
        public String type;
        public String title;
        public boolean ok;
        /** 详情：用时/步数/监管者结论等 */
        public String detail;
        public String timeStr;
        /** 运行状态：running=进行中，done=结束（兼容旧数据默认 done） */
        public String status = "done";
        /** 审计明细：每步动作 JSON 数组字符串 [{n,do,ok,risk,out}] */
        public String stepsJson = "";
        // 兼容旧字段
        public String skill;
    }

    private final File file;

    public HistoryStore(Context ctx) {
        file = new File(ctx.getApplicationContext().getFilesDir(), "history.json");
    }

    public synchronized void append(String skill, boolean ok) {
        appendEntry("skill", skill, ok, "");
    }

    /** 思考执行员任务记录 */
    public synchronized void appendTask(String goal, boolean ok, String detail) {
        appendEntry("task", goal, ok, detail, "done");
    }

    /**
     * 任务开始：创建一条 running 记录，返回 id（时间戳）。
     * 结束时调 finishTask 更新为 done。
     */
    public synchronized long startTask(String goal) {
        long id = System.currentTimeMillis();
        try {
            JSONArray arr = load();
            arr.put(new JSONObject()
                    .put("t", id)
                    .put("type", "task")
                    .put("title", goal)
                    .put("ok", false)
                    .put("detail", "🟢 运行中…")
                    .put("status", "running"));
            while (arr.length() > 100) arr.remove(0);
            SkillStore.writeFile(file, arr.toString());
        } catch (Exception ignored) {
        }
        return id;
    }

    /** 任务结束：把 running 记录更新为最终结果 */
    public synchronized void finishTask(long id, String goal,
                                        boolean ok, String detail) {
        try {
            JSONArray arr = load();
            boolean updated = false;
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject o = arr.getJSONObject(i);
                if (o.optLong("t", 0) == id
                        && "running".equals(o.optString("status"))) {
                    o.put("ok", ok);
                    o.put("detail", detail == null ? "" : detail);
                    o.put("status", "done");
                    updated = true;
                    break;
                }
            }
            if (!updated) {
                arr.put(new JSONObject()
                        .put("t", System.currentTimeMillis())
                        .put("type", "task")
                        .put("title", goal)
                        .put("ok", ok)
                        .put("detail", detail == null ? "" : detail)
                        .put("status", "done"));
            }
            while (arr.length() > 100) arr.remove(0);
            SkillStore.writeFile(file, arr.toString());
        } catch (Exception ignored) {
        }
    }

    /** 更新 running 记录的详情（进度播报） */
    public synchronized void updateTaskProgress(long id, String detail) {        try {
            JSONArray arr = load();
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject o = arr.getJSONObject(i);
                if (o.optLong("t", 0) == id
                        && "running".equals(o.optString("status"))) {
                    o.put("detail", detail);
                    break;
                }
            }
            SkillStore.writeFile(file, arr.toString());
        } catch (Exception ignored) {
        }
    }

    /** 审计：给指定记录补上每步动作明细（AgentLoop.getAuditJson() 的输出） */
    public synchronized void setSteps(long id, String stepsJson) {
        try {
            JSONArray arr = load();
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject o = arr.getJSONObject(i);
                if (o.optLong("t", 0) == id) {
                    o.put("steps", stepsJson == null ? "" : stepsJson);
                    break;
                }
            }
            SkillStore.writeFile(file, arr.toString());
        } catch (Exception ignored) {
        }
    }

    /** 技能卡执行记录 */
    public synchronized void appendCard(String name, boolean ok, String detail) {
        appendEntry("card", name, ok, detail);
    }

    private synchronized void appendEntry(String type, String title, boolean ok,
                                          String detail) {
        appendEntry(type, title, ok, detail, "done");
    }

    private synchronized void appendEntry(String type, String title, boolean ok,
                                          String detail, String status) {
        try {
            JSONArray arr = load();
            arr.put(new JSONObject()
                    .put("t", System.currentTimeMillis())
                    .put("type", type)
                    .put("title", title)
                    .put("ok", ok)
                    .put("detail", detail == null ? "" : detail)
                    .put("status", status));
            while (arr.length() > 100) arr.remove(0);
            SkillStore.writeFile(file, arr.toString());
        } catch (Exception ignored) {
        }
    }

    public List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray arr = load();
            SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject o = arr.getJSONObject(i);
                Entry e = new Entry();
                e.t = o.getLong("t");
                e.skill = o.optString("skill", "");
                // 兼容旧数据：只有 skill 字段的视为 skill 类型
                e.type = o.optString("type",
                        e.skill.isEmpty() ? "task" : "skill");
                e.title = o.optString("title",
                        e.skill.isEmpty() ? "" : e.skill);
                e.ok = o.optBoolean("ok", false);
                e.detail = o.optString("detail", "");
                e.status = o.optString("status", "done");
                e.stepsJson = o.optString("steps", "");
                e.timeStr = f.format(new Date(e.t));
                out.add(e);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private JSONArray load() {
        try {
            if (file.exists()) return new JSONArray(SkillStore.readFile(file));
        } catch (Exception ignored) {
        }
        return new JSONArray();
    }
}
