package com.gsic.app;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * v3.55 任务结局蒸馏器（记忆分层·步骤2）。
 * 设计稿：目标工作区 hidden_files/v351-design.md 第二节 2.3；
 * 规格：~/workspace/learning-loop-SPEC.md 第四节（记忆分层 doctrine）。
 *
 * 纯 Java：无 Android 依赖，可在 JVM 直接单测。
 *
 * 纪律（用户亲定）：
 * - experience.json 只是蒸馏原料队列，不再做长期注入源；
 * - 规则蒸馏（零 token）：失败结局/用户纠正/重复目标 三类规则；
 * - 蒸馏不出（成败都平淡、无病因）直接丢弃，不留条目；
 * - 候选进 cognition.json，status=draft；同类候选再出现 votes+1，
 *   votes>=2 自动转正（走 CognitionStore.vote）。
 * - 重复出现的目标 -> 技能卡草稿候选，落 distill_card_queue.jsonl；
 * - v3.58 步骤2：消费方落地（SharedMemory.maybeDraftSkillCard，经
 *   AgentLoop 任务成功结局后调用）：votes>=2 的候选 -> 草稿卡，消费后
 *   从队列移除；超 30 天未复现的 stale 条目顺带清理。
 */
public class Distiller {

    /** 技能卡草稿队列文件名（JSONL，一行一个候选） */
    public static final String CARD_QUEUE_FILE = "distill_card_queue.jsonl";

    /** 任务结局记录（纯数据） */
    public static class TaskRec {
        public String ch = "";
        public String goal = "";
        public boolean ok = true;
        public long ms = 0L;
        public int tools = 0;
        public String note = "";
        /** 失败病因分类（遥测·步骤5 后回填，现可为空） */
        public String failClass = "";
        /** 用户是否中途纠正/叫停（步骤5/7 由调用方回填） */
        public boolean userInterrupted = false;
    }

    /**
     * 规则蒸馏（零 token 纯 Java）。
     *
     * @param task    本次任务结局
     * @param store   认知库（候选写入处）
     * @param recents 经验队列中的近期记录（含本次，供"重复出现"判定）
     * @param dir     files 目录（技能卡草稿队列落盘处）
     * @return store 是否发生变化（调用方据此决定是否 save）
     */
    public static boolean distill(TaskRec task, CognitionStore store,
            List<TaskRec> recents, File dir) {
        if (task == null || store == null) return false;
        String goal = task.goal == null ? "" : task.goal.trim();
        if (goal.isEmpty()) return false;
        boolean changed = false;

        // 规则1：失败结局 -> 自身教训候选
        if (!task.ok) {
            String fc = task.failClass == null ? "" : task.failClass.trim();
            String fcName = failClassName(fc);
            String text;
            if (!fcName.isEmpty()) {
                text = "「" + trim(goal, 40) + "」任务曾因" + fcName
                        + "未完成：" + failAdvice(fc);
            } else {
                text = "「" + trim(goal, 40) + "」任务未完成（用时 "
                        + task.ms / 1000 + " 秒、" + task.tools
                        + " 次工具调用）：下次同类任务先核实前提是否存在再动手，"
                        + "别重复无效探索";
            }
            if (addOrVote(store, CognitionStore.KIND_SELF_LESSION,
                    text, CognitionStore.SRC_TASK_DISTILL)) {
                changed = true;
            }
        }

        // 规则3：用户中途纠正/叫停 -> 用户纠正候选
        if (task.userInterrupted) {
            String text = "在「" + trim(goal, 40)
                    + "」任务中用户中途纠正或叫停";
            String note = task.note == null ? "" : task.note.trim();
            if (!note.isEmpty()) text += "：" + trim(note, 60);
            text += "——用户约束的显式信号，同类任务优先遵循";
            if (addOrVote(store, CognitionStore.KIND_USER_CORRECTION,
                    text, CognitionStore.SRC_TASK_DISTILL)) {
                changed = true;
            }
        }

        // 规则2：重复出现的目标 -> 技能卡草稿候选
        if (recents != null && dir != null) {
            Set<String> mine = bigrams(goal);
            int same = 0;
            for (TaskRec r : recents) {
                if (r == null || r.goal == null || r.goal.trim().isEmpty()) {
                    continue;
                }
                if (jaccard(mine, bigrams(r.goal.trim())) >= 0.5) same++;
            }
            if (same >= 2) {
                appendCardDraft(dir, goal);
                // 卡草稿队列不算 store 变化，返回语义保持为 store 变化
            }
        }

        return changed;
    }

    // ---------------- 内部 ----------------

    /**
     * 新增或投票：同 kind 下文本相似度>=0.6 的已有条目视为同类候选，
     * 走 vote（可能自动转正）；否则新建 draft 候选。
     */
    private static boolean addOrVote(CognitionStore store, String kind,
            String text, String src) {
        Set<String> mine = bigrams(text);
        for (CognitionStore.Item it : store.all()) {
            if (!kind.equals(it.kind)) continue;
            if (jaccard(mine, bigrams(it.text)) >= 0.6) {
                store.vote(it.id); // votes>=2 自动转正
                return true;
            }
        }
        return !store.put(kind, text, src, "").isEmpty();
    }

    /**
     * 技能卡草稿队列消费（v3.58 步骤2）：取与 goal 同类（bigram>=0.5）
     * 且 votes>=2 的候选，成功取出后从队列移除（调用方负责沉淀为草稿卡）；
     * 顺带清理超 30 天未复现的 stale 条目（队列是工作缓冲，不是长期存储）。
     * 只在任务成功结局后由调用方触发（失败轨迹不沉淀为技能卡）。
     *
     * @return 被消费的候选（含 goal/votes/ts/src）；无符合候选返回 null
     */
    public static JSONObject consumeDraftCandidate(File dir, String goal) {
        try {
            if (dir == null || goal == null) return null;
            File f = new File(dir, CARD_QUEUE_FILE);
            if (!f.exists() || f.length() <= 0) return null;
            List<JSONObject> lines = readQueue(f);
            long now = System.currentTimeMillis();
            Set<String> mine = bigrams(goal.trim());
            JSONObject picked = null;
            List<JSONObject> keep = new ArrayList<>();
            for (JSONObject o : lines) {
                if (picked == null
                        && o.optInt("votes", 1) >= 2
                        && jaccard(mine,
                                bigrams(o.optString("goal", ""))) >= 0.5) {
                    picked = o; // 消费：不写回
                    continue;
                }
                if (now - o.optLong("ts", now) > QUEUE_STALE_MS) continue; // stale 清理
                keep.add(o);
            }
            if (picked != null || keep.size() != lines.size()) {
                writeQueue(f, keep);
            }
            return picked;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 队列条目超期：30 天未复现视为不再重复，清理 */
    private static final long QUEUE_STALE_MS = 30L * 24 * 3600 * 1000;

    /** 读整条队列（坏行跳过，与旧 appendCardDraft 口径一致） */
    private static List<JSONObject> readQueue(File f) {
        List<JSONObject> lines = new ArrayList<>();
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int off = 0, r;
            while (off < buf.length
                    && (r = in.read(buf, off, buf.length - off)) > 0) {
                off += r;
            }
            in.close();
            String[] raw = new String(buf, 0, off, "UTF-8").split("\n");
            for (String ln : raw) {
                ln = ln.trim();
                if (ln.isEmpty()) continue;
                try {
                    lines.add(new JSONObject(ln));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return lines;
    }

    /** 全量写回队列 */
    private static void writeQueue(File f, List<JSONObject> lines) {
        try {
            StringBuilder sb = new StringBuilder();
            for (JSONObject o : lines) {
                sb.append(o.toString()).append("\n");
            }
            FileOutputStream out = new FileOutputStream(f);
            out.write(sb.toString().getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
        }
    }

    /** 技能卡草稿队列：同类目标再出现 votes+1，否则追加一行 */
    private static void appendCardDraft(File dir, String goal) {
        try {
            if (dir == null) return;
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, CARD_QUEUE_FILE);
            List<JSONObject> lines =
                    (f.exists() && f.length() > 0) ? readQueue(f)
                            : new ArrayList<JSONObject>();
            boolean hit = false;
            Set<String> mine = bigrams(goal);
            for (JSONObject o : lines) {
                if (!hit && jaccard(mine,
                        bigrams(o.optString("goal", ""))) >= 0.5) {
                    o.put("votes", o.optInt("votes", 1) + 1);
                    o.put("ts", System.currentTimeMillis());
                    hit = true;
                }
            }
            if (!hit) {
                JSONObject o = new JSONObject();
                o.put("goal", goal);
                o.put("ts", System.currentTimeMillis());
                o.put("votes", 1);
                o.put("src", CognitionStore.SRC_TASK_DISTILL);
                lines.add(o);
            }
            writeQueue(f, lines);
        } catch (Exception ignored) {
        }
    }

    /** 失败病因分类中文名（枚举口径见设计稿 2.4；遥测步骤5 回填） */
    private static String failClassName(String c) {
        if ("coord_off".equals(c)) return "坐标定位偏";
        if ("element_missing".equals(c)) return "目标元素不存在";
        if ("page_not_ready".equals(c)) return "页面未加载完";
        if ("permission_denied".equals(c)) return "权限被拒";
        if ("timeout".equals(c)) return "超时";
        return "";
    }

    /** 按病因给下次同类任务的行动建议 */
    private static String failAdvice(String c) {
        if ("coord_off".equals(c)) return "同类任务优先走 vision 定位兜底，不要心算坐标";
        if ("element_missing".equals(c)) return "动手前先确认目标元素在当前界面真实存在";
        if ("page_not_ready".equals(c)) return "操作前多等一轮、确认页面加载完再动手";
        if ("permission_denied".equals(c)) return "先检查所需权限与授权状态再执行";
        if ("timeout".equals(c)) return "超时类任务分段执行、每段及时收尾";
        return "";
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int inter = 0;
        for (String s : a) if (b.contains(s)) inter++;
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0.0 : (double) inter / union;
    }

    /** 与 CognitionStore/SharedMemory 同口径（去空白与标点后按字二元组） */
    private static Set<String> bigrams(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        String t = s.replaceAll("[\\s\\p{P}]", "");
        if (t.length() < 2) {
            if (!t.isEmpty()) out.add(t);
            return out;
        }
        for (int i = 0; i + 2 <= t.length(); i++) {
            out.add(t.substring(i, i + 2));
        }
        return out;
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
