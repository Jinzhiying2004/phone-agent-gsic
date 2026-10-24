package com.gsic.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v3.50 意图层：结构化任务书。
 *
 * 开跑前由一次短 LLM 调用（或零 token 启发式兜底）生成，把用户一句话解析成
 * {对象/动作/完成判据/停止线/禁区/置信度}；判据钉在上下文之外、每轮重注；
 * 终审只按判据裁决；停止线由任务书长出来；低置信不硬跑、走澄清。
 *
 * 纯 Java（仅依赖 org.json），无 Android 依赖，可在 JVM 直接单测。
 * 与 LlmClient 解耦：生成时传 ChatFn，调用方用 llm::chat 适配即可。
 */
public class TaskBook {

    /** 低置信默认阈值：低于此值不进 runSession，走澄清（prefs taskbook_conf_threshold 可调） */
    public static final double CONF_THRESHOLD_DEFAULT = 0.4;

    /** 钉住块裁剪上限（设计稿第十二节：每轮 +150 token，判据块裁剪） */
    private static final int MAX_CRITERIA = 60;
    private static final int MAX_STOPLINE = 60;

    public static class StopLine {
        public String signal = "";
        public String action = "";

        public StopLine() {
        }

        public StopLine(String signal, String action) {
            this.signal = signal == null ? "" : signal;
            this.action = action == null ? "" : action;
        }
    }

    /**
     * 一问一答函数式接口：与 LlmClient 解耦。
     * 实现返回模型输出的原始文本；失败/空返回 null 或空串。
     */
    public interface ChatFn {
        String chat(String prompt);
    }

    /** 任务对象：如"汽水音乐今日免费听歌时长" */
    public String target = "";
    /** 动作：领取 / 打开 / 查询 / 设置 / 播放… */
    public String action = "";
    /** 完成判据：核心字段，必须是可观测的手机界面/系统状态 */
    public String criteria = "";
    /** 停止线：判据达成后必须做的事 + 目标外链条识别 */
    public List<StopLine> stopLines = new ArrayList<>();
    /** 禁区：不许做的事 */
    public List<String> forbidden = new ArrayList<>();
    /** 置信度 0~1：模型对自己理解的把握 */
    public double confidence = 0.5;
    /** 置信度低的原因（一句话，给澄清问题用） */
    public String doubt = "";
    /** 低置信时必填：2~4 个澄清选项 */
    public List<String> clarifyOptions = new ArrayList<>();

    /** 解析结果是否可用：target/criteria 至少其一非空 */
    public boolean valid() {
        return !target.isEmpty() || !criteria.isEmpty();
    }

    /** 是否需要澄清拦截：有效且置信度低于阈值 */
    public boolean needsClarify(double threshold) {
        return valid() && confidence < threshold;
    }

    // ---------------- 生成 ----------------

    /**
     * 开跑前一次短 LLM 调用；失败/解析无效返回 null，调用方走 fromHeuristic 兜底。
     */
    public static TaskBook generate(ChatFn chat, String goal, String dictText) {
        if (chat == null || goal == null || goal.trim().isEmpty()) return null;
        try {
            String text = chat.chat(buildPrompt(goal, dictText));
            TaskBook b = fromJson(text);
            return b.valid() ? b : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 任务书生成 prompt（可单测）：用户原句 + 主人词典全文 + 任务类型先验 */
    public static String buildPrompt(String goal, String dictText) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是GSIC的意图解析器。把用户的一句话转成结构化任务书，")
                .append("只输出纯 JSON（不要 markdown 代码块、不要解释）。\n\n");
        sb.append("用户原句：").append(goal.trim()).append("\n\n");
        sb.append("主人词典（用户亲定的个人语义，解指代时必须用）：\n");
        if (dictText == null || dictText.trim().isEmpty()) {
            sb.append("（无）\n");
        } else {
            sb.append(dictText.trim()).append("\n");
        }
        sb.append("\n任务类型先验：领取类（含领取/免费/奖励/时长）/操作类（含打开/启动/设置）")
                .append("/查询类（含查/多少/是什么/怎么）/改造类（含改/修/配置软件）/问询类（其余）。\n");
        // v3.57 本地编程能力：动作先验「文件/脚本优先」
        sb.append("动作先验：改造类任务的动作为\"改造\"，优先「文件/脚本优先」"
                + "（read_file 找配置 + edit_file 改，或 termux 写 python 脚本批量处理），"
                + "不要进界面手点。\n\n");
        sb.append("输出 JSON（字段全要）：\n");
        sb.append("{\"target\":\"任务对象（如 汽水音乐今日免费听歌时长）\",")
                .append("\"action\":\"动作（领取/打开/查询/设置/播放…）\",")
                .append("\"criteria\":\"完成判据\",")
                .append("\"stop_lines\":[{\"signal\":\"触发信号\",\"action\":\"触发后动作\"}],")
                .append("\"forbidden\":[\"禁区事项\"],")
                .append("\"confidence\":0.8,")
                .append("\"doubt\":\"置信度低的原因，无则空\",")
                .append("\"options\":[\"低置信时的澄清选项1\",\"选项2\"]}\n\n");
        sb.append("要求：\n");
        sb.append("1. criteria 必须是可观测的手机界面/系统状态")
                .append("（出现某文案、某开关变为开、某文件生成），不许写\"任务完成\"这种空话；")
                .append("改造类判据示例：目标配置文件含某行/某键值。\n");
        sb.append("2. stop_lines 至少一条\"判据达成后的收尾动作\"；领取/奖励类必须有一条：")
                .append("\"「再看N个提前得明日/提前享」是目标外链条，出现即拒绝并收尾\"。\n");
        sb.append("3. confidence 标尺：对象明确且可达 ≥0.7；对象多义/指代不明 ≤0.4")
                .append(" 并在 doubt 写清卡在哪；options 在 confidence<0.4 时必填 2~4 个选项。\n");
sb.append("4. forbidden 默认至少含\"不许做与目标无关的充值/订阅\"。");
        sb.append("5. 判据如含状态查询分支（如状态/开关/是否）：把状态证据类判据写在最前面。\n");
        return sb.toString();
    }

    /** 从 LLM JSON 解析；容忍 ```json 代码块与前后杂文本；解析失败返回无效 book */
    public static TaskBook fromJson(String json) {
        TaskBook b = new TaskBook();
        if (json == null) return b;
        try {
            String t = json.trim();
            int s = t.indexOf('{');
            int e = t.lastIndexOf('}');
            if (s < 0 || e <= s) return b;
            JSONObject o = new JSONObject(t.substring(s, e + 1));
            b.target = o.optString("target", "").trim();
            b.action = o.optString("action", "").trim();
            b.criteria = o.optString("criteria", "").trim();
            b.doubt = o.optString("doubt", "").trim();
            b.confidence = clamp01(parseDouble(o, "confidence", 0.5));
            JSONArray stops = o.optJSONArray("stop_lines");
            if (stops != null) {
                for (int i = 0; i < stops.length() && b.stopLines.size() < 6; i++) {
                    JSONObject sl = stops.optJSONObject(i);
                    if (sl == null) continue;
                    String sig = sl.optString("signal", "").trim();
                    String act = sl.optString("action", "").trim();
                    if (!sig.isEmpty() || !act.isEmpty()) {
                        b.stopLines.add(new StopLine(sig, act));
                    }
                }
            }
            JSONArray forb = o.optJSONArray("forbidden");
            if (forb != null) {
                for (int i = 0; i < forb.length() && b.forbidden.size() < 8; i++) {
                    String f = forb.optString(i, "").trim();
                    if (!f.isEmpty()) b.forbidden.add(f);
                }
            }
            JSONArray opts = o.optJSONArray("options");
            if (opts != null) {
                for (int i = 0; i < opts.length() && b.clarifyOptions.size() < 4; i++) {
                    String op = opts.optString(i, "").trim();
                    if (!op.isEmpty()) b.clarifyOptions.add(op);
                }
            }
        } catch (Exception ignored) {
        }
        return b;
    }

    /**
     * 零 token 本地兜底：关键词启发式。
     * LLM 调用失败/离线时不断链，判据与停止线按任务类型给保守默认值。
     */
    public static TaskBook fromHeuristic(String goal) {
        TaskBook b = new TaskBook();
        if (goal == null) goal = "";
        String g = goal.trim();
        b.target = trim(g, 40);
        b.forbidden.add("不许做与目标无关的充值/订阅");
        b.forbidden.add("任务结束回手机桌面");
        if (containsAny(g, "领取", "免费", "奖励", "时长", "签到")) {
            b.action = "领取";
            b.criteria = "界面出现「领取成功/已领取」字样";
            b.stopLines.add(new StopLine("出现「领取成功/已领取」",
                    "立即收尾报告，不再继续看广告或做后续任务"));
            b.stopLines.add(new StopLine("出现「再看N个/提前得/提前享明日」",
                    "目标外链条，拒绝并收尾"));
            b.confidence = 0.7;
        } else if (containsAny(g, "改掉", "修改", "配置", "修复")) {
            // v3.57 本地编程能力：改造类（零 token 本地兜底也懂文件/脚本优先）
            b.action = "改造";
            b.criteria = "目标配置文件含某行/某键值（read_file 查状态验证）";
            b.stopLines.add(new StopLine("判据达成", "收尾报告并回桌面"));
            b.confidence = 0.6;
        } else if (containsAny(g, "打开", "启动", "进入")) {
            b.action = "打开";
            b.criteria = "目标应用进入前台（dumpsys/截屏确认）";
            b.stopLines.add(new StopLine("判据达成", "收尾报告并回桌面"));
            b.confidence = 0.7;
        } else if (containsAny(g, "查", "多少", "是什么", "怎么", "是否", "有没有")) {
            b.action = "查询";
            b.criteria = "拿到答案文本";
            b.stopLines.add(new StopLine("判据达成", "收尾报告并回桌面"));
            b.confidence = 0.7;
        } else {
            b.action = "执行";
            b.criteria = "按用户原句字面达成";
            b.stopLines.add(new StopLine("判据达成", "收尾报告并回桌面"));
            b.confidence = 0.3;
        }
        return b;
    }

    // ---------------- 钉住注入 ----------------

    /**
     * 钉住块：每轮 user 消息首段重注（活在 messages 之外，防 compactMessages 挤掉）。
     * dictShort 为主人词典紧凑行（可空）。
     */
    public String describe(String dictShort) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n📌 任务书（每轮重申，以此为准）：\n");
        sb.append("对象：").append(target.isEmpty() ? "（未定）" : target)
                .append("｜动作：").append(action.isEmpty() ? "（未定）" : action).append("\n");
        sb.append("完成判据：").append(trim(criteria.isEmpty() ? "（未定）" : criteria, MAX_CRITERIA)).append("\n");
if (hasStateBranch(criteria)) sb.append("⚡状态证据优先：判据含状态查询——状态证据满足即可收尾，不必走完 GUI 路径\n");
        if (!stopLines.isEmpty()) {
            sb.append("停止线：");
            for (int i = 0; i < stopLines.size(); i++) {
                StopLine sl = stopLines.get(i);
                if (i > 0) sb.append("；");
                sb.append("①②③④⑤⑥".charAt(Math.min(i, 5)))
                        .append(trim(sl.signal + "→" + sl.action, MAX_STOPLINE));
            }
            sb.append("\n");
        }
        if (!forbidden.isEmpty()) {
            sb.append("禁区：").append(join(forbidden, "；")).append("\n");
        }
        if (dictShort != null && !dictShort.trim().isEmpty()) {
            sb.append("主人词典：").append(dictShort.trim()).append("\n");
        }
        if (doubt != null && !doubt.trim().isEmpty()) {
            sb.append("⚠ 存疑：").append(doubt.trim()).append("\n");
        }
        return sb.toString();
    }

    /** 钉住块（不带词典行） */
    public String describe() {
        return describe(null);
    }

    // ---------------- 低置信澄清 ----------------

    /** 澄清问题文本：低置信拦截时三端各渲染 */
    public String clarifyQuestion() {
        StringBuilder sb = new StringBuilder();
        sb.append("「").append(target.isEmpty() ? "这个任务" : target).append("」不太确定");
        if (doubt != null && !doubt.trim().isEmpty()) {
            sb.append("（").append(doubt.trim()).append("）");
        }
        if (!clarifyOptions.isEmpty()) {
            sb.append("，是指：\n");
            for (int i = 0; i < clarifyOptions.size(); i++) {
                sb.append("①②③④".charAt(Math.min(i, 3))).append(" ")
                        .append(clarifyOptions.get(i)).append("\n");
            }
            sb.append("请回复序号。");
        } else {
            sb.append("，请补充说明。");
        }
        return sb.toString();
    }

    // ---------------- 停止线检查 ----------------

    /**
     * v3.50 第 2 步：把描述性 signal 拆成可字面匹配的候选子串。
     * LLM/启发式写的 signal 常是"出现「领取成功」"这类描述，直接 contains 整句永远命中不了；
     * 这里优先取「」内片段、再按 /、；， 切出"领取成功""已领取"等可匹配文本。
     * 无引号时按分隔符切分取长度≥2 的片段兜底。
     */
    static List<String> signalCandidates(String signal) {
        List<String> out = new ArrayList<>();
        if (signal == null) return out;
        Matcher m = Pattern.compile("「([^」]+)」").matcher(signal);
        boolean quoted = false;
        while (m.find()) {
            quoted = true;
            for (String p : m.group(1).split("[/、；;，,]")) {
                p = p.trim();
                if (p.length() >= 2 && !out.contains(p)) out.add(p);
            }
        }
        if (!quoted) {
            for (String p : signal.split("[/、；;，,]")) {
                p = p.trim();
                if (p.length() >= 2 && !out.contains(p)) out.add(p);
            }
            if (out.isEmpty() && signal.trim().length() >= 2) out.add(signal.trim());
        }
        return out;
    }

    /**
     * 通用停止线检查（设计稿第六节）：命中某条 signal 返回其 action，未命中返回 null。
     * screenText 为本轮界面/工具观察文本，historyTail 为最近历史尾巴（可空）。
     */
    public String checkStop(String screenText, String historyTail) {
        if (stopLines.isEmpty()) return null;
        String hay = (screenText == null ? "" : screenText)
                + "\n" + (historyTail == null ? "" : historyTail);
        for (StopLine sl : stopLines) {
            if (sl.signal == null || sl.signal.isEmpty()) continue;
            for (String cand : signalCandidates(sl.signal)) {
                if (hay.contains(cand)) return sl.action;
            }
        }
        return null;
    }

    /** 停止线检查（只要界面文本） */
    public String checkStop(String screenText) {
        return checkStop(screenText, null);
    }

    // ---------------- 步骤6：状态证据优先 + 判据分支收尾提醒 ----------------
/** 判据是否含"状态查询"分支：状态/开关/是否/答案类证据满足即可收尾 */
static boolean hasStateBranch(String criteria) {
return containsAny(criteria, "状态", "开关", "是否", "答案");
}
/** 判据按 或/； 切成分支，再取每个分支的可字面匹配关键词 */
public List<String> criteriaBranches() {
List<String> out = new ArrayList<>();
if (criteria == null) return out;
for (String part : criteria.split("或|；|\\||\\n")) {
for (String cand : signalCandidates(part)) {
if (cand.length() >= 2 && !out.contains(cand)) out.add(cand);
}
}
return out;
}
/**
* 判据分支证据命中：某分支关键词命中最近工具观察文本时返回该分支，否则 null。
* 零 token 纯字符串检查（供 AgentLoop 每轮收尾提醒）。
*/
public String branchHit(String roundObs) {
if (roundObs == null || roundObs.isEmpty()) return null;
for (String b : criteriaBranches()) {
if (roundObs.contains(b)) return b;
}
return null;
}
// ---------------- 工具 ----------------

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    private static double parseDouble(JSONObject o, String key, double def) {
        try {
            Object v = o.opt(key);
            if (v instanceof Number) return ((Number) v).doubleValue();
            if (v instanceof String) return Double.parseDouble(((String) v).trim());
        } catch (Exception ignored) {
        }
        return def;
    }

    private static boolean containsAny(String s, String... keys) {
        if (s == null) return false;
        for (String k : keys) {
            if (s.contains(k)) return true;
        }
        return false;
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static String join(List<String> list, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(list.get(i));
        }
        return sb.toString();
    }
}
