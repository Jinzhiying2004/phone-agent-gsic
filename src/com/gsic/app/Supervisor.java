package com.gsic.app;

import org.json.JSONObject;

/**
 * 监管者：独立于执行 agent 的第二视角。
 * 设计原则（不武断）：
 * - 保守判定：只有明确证据表明未完成才否决，不确定时倾向确认
 * - 分级评估：给出完成度百分比，而非简单的 是/否
 * - 针对性指导：明确指出缺什么、下一步具体做什么，而非"继续"二字
 * - 卡住识别：连续两轮卡在同一问题时停手，不烧 token
 */
public class Supervisor {

    public static class Verdict {
        /** 任务是否真正完成（保守判定） */
        public boolean completed;
        /** 完成度 0-100 */
        public int progressPct;
        /** 进度与情况描述 */
        public String progress = "";
        /** 最终结果点评 */
        public String review = "";
        /** 具体缺什么（可操作的具体描述，无则空） */
        public String missing = "";
        /** 建议的具体下一步（无则空） */
        public String nextAction = "";
        /** v3.50 意图层：任务书完成判据是否达成（无任务书时与 completed 一致） */
        public boolean criterionMet = true;
        public boolean ok = false;
    }

    public static Verdict review(LlmClient client, String goal, boolean workerSaid,
                                 String history, String finalScreen) {
        return review(client, goal, workerSaid, history, finalScreen, null);
    }

    /** v3.38：带最终截图的复核（完成与否由监管者看图裁定） */
    public static Verdict review(LlmClient client, String goal, boolean workerSaid,
                                 String history, String finalScreen,
                                 android.graphics.Bitmap finalShot) {
        return review(client, goal, workerSaid, history, finalScreen, finalShot, null);
    }

    /**
     * v3.50 意图层：终审判据绑定。
     * 传入有效任务书时，prompt 追加"只按任务书完成判据裁决"铁律，输出加 criterion_met，
     * completed 与 criterion_met 强制一致（防模型嘴上说判据没达成、结论却写完成）。
     * book 为 null/无效时走老路，行为不变。
     */
    public static Verdict review(LlmClient client, String goal, boolean workerSaid,
                                 String history, String finalScreen,
                                 android.graphics.Bitmap finalShot, TaskBook book) {
        Verdict v = new Verdict();
        try {
            boolean bindCriterion = book != null && book.valid()
                    && book.criteria != null && !book.criteria.trim().isEmpty();
            StringBuilder pb = new StringBuilder();
            pb.append("你是手机自动化任务的监管者，负责独立复核任务完成情况。")
                    .append(finalShot != null ? "已附任务结束时的手机截图，结合截图判断。" : "")
                    .append("\n\n")
                    .append("用户目标：").append(goal).append("\n")
                    .append("执行 agent 自认：").append(workerSaid ? "已完成" : "未完成").append("\n")
                    .append("执行过程：\n").append(history).append("\n")
                    .append("任务结束时的手机界面：\n").append(finalScreen).append("\n\n");
            if (bindCriterion) {
                pb.append("本次任务书：对象=").append(book.target)
                        .append("；动作=").append(book.action)
                        .append("；完成判据=").append(book.criteria)
                        .append("（只认这一条）。\n")
                        .append("裁决铁律：只按上面这条完成判据裁决，不按\"做了多少步/有没有进展\"裁决；")
                        .append("判据无可观测证据 → completed=false。\n\n");
            }
            pb.append("判定原则（重要）：\n")
                    .append("1. 保守判定：只有在有明确证据表明目标未达成时，才判 completed=false；")
                    .append("证据不足或目标已基本达成时，判 completed=true\n")
                    .append("2. 不要吹毛求疵：细节不完美但目标已达成，算完成，在 review 里说明即可\n")
                    .append("3. 不要武断：否决必须给出具体的 missing 和 next_action，")
                    .append("说不清缺什么就不要否决\n")
                    .append("4. 按目标对象状态核对，不按工作量核对：做了很多步、有进展都不算完成证据，")
                    .append("只认目标对象当前状态的证据\n")
                    .append("5. 领取/奖励类目标：历史或界面出现『领取成功/已领取/奖励已到账』，")
                    .append("即该对象已达成，判 completed=true；执行者此后继续看的")
                    .append("『再看N个提前得明日/第X+1日』是 App 的链条推销、属于目标外，")
                    .append("不因为它还在继续操作就判未完成\n")
                    .append("6. 无操作证据不判完成：执行过程若没有任何实际操作目标 App 的动作")
                    .append("（只有 observe/查技能卡/读说明），执行者却声称已在应用内完成操作")
                    .append("（看广告、点领取等），属于无证据叙述，判 completed=false，")
                    .append("missing 写明它声称做了但过程里没有的操作\n\n")
                    .append("只输出纯 JSON：\n")
                    .append("{\"completed\":true,")
                    .append("\"progress_pct\":85,")
                    .append("\"progress\":\"一句话进度描述\",")
                    .append("\"review\":\"一句话结果点评\",")
                    .append("\"missing\":\"具体缺什么（无则空字符串）\",")
                    .append("\"next_action\":\"建议的具体下一步（无则空字符串）\"");
            if (bindCriterion) {
                pb.append(",\"criterion_met\":true");
            }
            pb.append("}");
            LlmClient.Result r = null;
            if (finalShot != null) {
                try {
                    r = client.chatVision(pb.toString(), finalShot, "");
                } catch (Exception ignored) {
                    r = null;
                }
            }
            if (r == null || !r.ok) r = client.chat(pb.toString());
            if (!r.ok) return v;
            JSONObject o = AgentLoop.extractJson(r.text);
            if (o == null) return v;
            v.completed = o.optBoolean("completed", workerSaid);
            v.progressPct = Math.max(0, Math.min(100, o.optInt("progress_pct",
                    v.completed ? 100 : 50)));
            v.progress = o.optString("progress", "");
            v.review = o.optString("review", "");
            v.missing = o.optString("missing", "");
            v.nextAction = o.optString("next_action", "");
            if (bindCriterion) {
                v.criterionMet = o.optBoolean("criterion_met", v.completed);
                // 防模型嘴上说判据没达成、结论却写完成：completed 与 criterion_met 强制一致
                v.completed = v.criterionMet;
            } else {
                v.criterionMet = v.completed;
            }
            // 一致性修正：说完成但完成度低，或说未完成但完成度满，都以 completed 为准微调
            if (v.completed && v.progressPct < 60) v.progressPct = 80;
            v.ok = true;
        } catch (Exception ignored) {
        }
        return v;
    }

    /**
     * 实时监管：任务执行中每隔几步调用，判断是否偏离/卡住。
     * 返回干预意见（null 或空 = 一切正常，无需干预）。
     * 轻量 prompt，省 token。
     */
    public static String monitor(LlmClient client, String goal,
                                 String recentHistory, int step) {
        try {
            String prompt = "你是手机自动化任务的实时监管者。任务正在执行中，"
                    + "请判断执行是否正常。\n\n"
                    + "用户目标：" + goal + "\n"
                    + "当前第 " + step + " 步，最近几步：\n"
                    + recentHistory + "\n\n"
                    + "只输出纯 JSON：\n"
                    + "{\"status\":\"ok\"或\"warning\"或\"stuck\","
                    + "\"reason\":\"为什么这样判（具体指出哪几步有问题、错在哪，ok 时为空）\","
                    + "\"advice\":\"一句话干预意见（ok 时为空字符串）\"}";
            LlmClient.Result r = client.chat(prompt);
            if (!r.ok) return null;
            JSONObject o = AgentLoop.extractJson(r.text);
            if (o == null) return null;
            String status = o.optString("status", "ok");
            if ("ok".equals(status)) return null;
            String reason = o.optString("reason", "");
            String advice = o.optString("advice", "");
            if (advice.isEmpty() && reason.isEmpty()) return null;
            // v2.91：打断必须给理由，让 agent 明白错在哪
            StringBuilder sb = new StringBuilder();
            sb.append("【监管者实时提醒】（第").append(step).append("步，")
                    .append("stuck".equals(status) ? "疑似卡住" : "注意").append("）\n");
            if (!reason.isEmpty()) sb.append("理由：").append(reason).append("\n");
            if (!advice.isEmpty()) sb.append("建议：").append(advice);
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }


    /** 介入裁决结果 */
    public static class Intervention {
        /** guide=给指引继续 / abort=判定中止 / continue=无异常放行 */
        public String action = "continue";
        public String message = "";
        public boolean ok = false;
    }

    /**
     * 执行中介入裁决：执行 agent 疑似死循环/卡住/幻觉时由监管者（最强模型）判断。
     * 与 monitor 不同：这里握有中止权，目标客观不可达或幻觉无路时判 abort。
     */
    public static Intervention intervene(LlmClient client, String goal,
                                         String trigger, String recent,
                                         String thoughts,
                                         android.graphics.Bitmap lastShot) {
        Intervention it = new Intervention();
        try {
            String prompt = "你是手机自动化任务的监管者。执行 agent 正在执行任务，"
                    + "本地监测发现异常，现在由你裁决。"
                    + (lastShot != null ? "已附上它最新一次看屏的截图，结合截图判断。"
                    : "") + "\n\n"
                    + "用户目标：" + goal + "\n"
                    + "触发原因：" + trigger + "\n"
                    + "最近执行记录（工具 → 结果）：\n" + recent + "\n"
                    + "执行 agent 最近的想法与动作：\n" + thoughts + "\n\n"
                    + "判断要点：无用循环不只是重复同一个动作——换着花样做无效尝试、"
                    + "界面长时间没有变化、离目标没有更近，都算原地打转；"
                    + "结合截图看它是不是在臆想不存在的界面元素（幻觉）、"
                    + "有没有错过显而易见的入口。\n"
                    + "特别注意领取/奖励类任务：若界面已出现『领取成功/已领取』，"
                    + "或出现『再看N个提前得下一日』推销，说明本次目标已达成、"
                    + "执行者在追目标外链条——判 guide 并明确要求它立即收尾结束，"
                    + "不要判 continue 放行它继续看广告。\n"
                    + "请判断属于哪种情况，只输出纯 JSON：\n"
                    + "{\"action\":\"guide|abort|continue\",\"message\":\"...\"}\n"
                    + "- continue：执行其实正常，或小问题它自己能纠正，message 写一句判断即可\n"
                    + "- guide：它走偏了、在打转或在臆想界面元素，message 给它明确具体的新做法"
                    + "（指出错在哪、应该改做什么，不要空泛地说继续努力）\n"
                    + "- abort：目标客观上无法完成，或它在基于不存在的界面元素反复尝试"
                    + "且没有可行的路，message 写明无法完成的原因（这是给用户看的）";
            LlmClient.Result r = null;
            if (lastShot != null) {
                try {
                    r = client.chatVision(prompt, lastShot, "");
                } catch (Exception ignored) {
                    r = null;
                }
            }
            if (r == null || !r.ok) r = client.chat(prompt);
            if (!r.ok) return it;
            JSONObject o = AgentLoop.extractJson(r.text);
            if (o == null) return it;
            String a = o.optString("action", "continue");
            if (!"guide".equals(a) && !"abort".equals(a)) a = "continue";
            it.action = a;
            it.message = o.optString("message", "");
            it.ok = true;
        } catch (Exception ignored) {
        }
        return it;
    }

    /** 生成给 agent 的结构化反馈（聪明指导，而非"继续"二字） */
    public static String buildFeedback(Verdict v) {
        StringBuilder sb = new StringBuilder();
        sb.append("完成度约 ").append(v.progressPct).append("%。");
        if (!v.progress.isEmpty()) sb.append(v.progress);
        if (!v.missing.isEmpty()) {
            sb.append("\n还缺：").append(v.missing);
        }
        if (!v.nextAction.isEmpty()) {
            sb.append("\n建议下一步：").append(v.nextAction);
        }
        sb.append("\n注意：不要重复已完成的步骤，只处理缺失的部分。");
        return sb.toString();
    }

    /** 纯逻辑：判断两轮是否卡在同一问题（可独立测试） */
    public static boolean isStuck(String lastMissing, String curMissing) {
        if (lastMissing == null || curMissing == null) return false;
        String a = lastMissing.trim();
        String b = curMissing.trim();
        if (a.isEmpty() || b.isEmpty()) return false;
        // 完全相同，或一方包含另一方核心（取前 10 字比较）
        if (a.equals(b)) return true;
        String ka = a.length() > 10 ? a.substring(0, 10) : a;
        String kb = b.length() > 10 ? b.substring(0, 10) : b;
        return ka.equals(kb);
    }

    public static String conclude(boolean workerSaid, Verdict v) {
        if (!v.ok) return "🔍 监管者复核失败（网络或解析异常），以执行结果为准";
        StringBuilder sb = new StringBuilder("🔍 监管者判定：");
        sb.append(v.completed ? "任务已完成" : "任务未完成");
        sb.append("（完成度约 ").append(v.progressPct).append("%）");
        if (!v.progress.isEmpty()) sb.append("\n进度：").append(v.progress);
        if (!v.review.isEmpty()) sb.append("\n点评：").append(v.review);
        if (!v.completed) {
            if (!v.missing.isEmpty()) sb.append("\n缺失：").append(v.missing);
            if (!v.nextAction.isEmpty()) sb.append("\n建议：").append(v.nextAction);
            if (workerSaid) sb.append("\n⚠️ 执行 agent 自认完成，但监管者要求针对性补足");
        } else if (!workerSaid) {
            sb.append("\n✅ 虽然执行中遇到问题，但监管者确认目标实际已达成");
        }
        return sb.toString();
    }
}
