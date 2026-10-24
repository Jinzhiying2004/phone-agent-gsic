package com.gsic.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 技能卡存储：agent 执行完任务后自动生成的总结，拿来即用，免重复思考。
 *
 * 两套来源（优先级：user > agent）：
 * - user：用户手动创建，或一次任务后主动要求记录（/save）
 * - agent：agent 执行完任务后自主决策生成
 *
 * 两种类型：
 * - script：脚本类（shell/termux 自动化），content 为 shell 脚本
 * - skill：skill 类（任务步骤 JSON），content 为 {"steps":[...]}
 *
 * 分开存储（用户 2026-10-22 亲定）：步骤型/脚本型（确定性回放）存
 * files/skill_cards.json；说明型（自然语言说明、由智能体读后执行）存
 * files/skill_cards_guide.json。对外仍是统一列表/按 id 查询；load 时
 * 自愈分流——任一文件里混进错分区的卡都会被重新归位落盘（兼容旧版
 * 单文件与外部直接写文件的情况）。
 */
public class SkillCardStore {

    public static final String TYPE_SCRIPT = "script";
    public static final String TYPE_SKILL = "skill";
    public static final String SRC_USER = "user";
    public static final String SRC_AGENT = "agent";

    /** 技能卡生命周期（v3.58 步骤1）：draft 草稿（待复验）/ official 已转正 */
    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_OFFICIAL = "official";
    /** 版本历史每卡上限 */
    public static final int VERSION_HISTORY_CAP = 20;

    public static class Card {
        public String id;
        public String name;
        public String type;
        public String source;
        public String trigger;
        public String content;
        public String summary;
        public int useCount;
        public long updatedAt;
        /** 生命周期：draft 草稿（待复验）/ official 已转正；旧卡默认 official */
        public String status = STATUS_OFFICIAL;
        /** 内容修订号（单调递增，回滚也递增） */
        public int version = 1;
        /** 草稿卡复验成功计数 */
        public int verifyCount = 0;
        /** 最近一次失败/修卡原因（就地修卡依据） */
        public String repairNote = "";
        /** 关联 App 包名（界面地图联动锚点） */
        public String appPkg = "";

        public boolean isDraft() {
            return STATUS_DRAFT.equals(status);
        }

        public String statusLabel() {
            return isDraft() ? "草稿（待复验）" : "已转正";
        }

        public int priority() {
            return SRC_USER.equals(source) ? 10 : 5;
        }

        public String sourceLabel() {
            return SRC_USER.equals(source) ? "用户" : "自主";
        }

        public String typeLabel() {
            return TYPE_SCRIPT.equals(type) ? "脚本" : "技能";
        }

        /**
         * 说明型卡：content 是自然语言操作说明而非步骤 JSON——只能由
         * 智能体读说明后用工具执行，TaskRunner 单步回放不了（与
         * TaskRunner.use_card 的 proseCard 判据保持一致）。
         */
        public boolean isProse() {
            if (TYPE_SCRIPT.equals(type)) return false;
            String ct = content == null ? "" : content.trim();
            return !(ct.startsWith("[") || ct.startsWith("{"));
        }
    }

    private final File file;
    private final File guideFile;
    private final File verFile;
    private final List<Card> cards = new ArrayList<>();
    private final java.util.Map<String, List<VersionSnap>> versions =
            new java.util.HashMap<>();

    /**
     * 版本快照（v3.58 生命周期步骤1）：修卡/回滚前压入历史。
     * 与 Card 分离存 skill_card_versions.json（冷数据，不进主文件）。
     */
    public static class VersionSnap {
        public int version;
        public long savedAt;
        public String name = "";
        public String trigger = "";
        public String content = "";
        public String summary = "";
        public String note = "";

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("version", version);
                o.put("savedAt", savedAt);
                o.put("name", name);
                o.put("trigger", trigger);
                o.put("content", content);
                o.put("summary", summary);
                o.put("note", note);
            } catch (Exception ignored) {
            }
            return o;
        }

        static VersionSnap fromJson(JSONObject o) {
            VersionSnap sn = new VersionSnap();
            sn.version = o.optInt("version", 1);
            sn.savedAt = o.optLong("savedAt", 0);
            sn.name = o.optString("name", "");
            sn.trigger = o.optString("trigger", "");
            sn.content = o.optString("content", "");
            sn.summary = o.optString("summary", "");
            sn.note = o.optString("note", "");
            return sn;
        }
    }

    /**
     * 纯函数：给卡片拍快照并压入历史，返回更新后的历史列表（JVM 可单测）。
     * 超过 VERSION_HISTORY_CAP 时丢弃最老快照。
     */
    static List<VersionSnap> snapshotFor(List<VersionSnap> hist, Card c, String note) {
        if (hist == null) hist = new ArrayList<>();
        VersionSnap sn = new VersionSnap();
        sn.version = c.version;
        sn.savedAt = System.currentTimeMillis();
        sn.name = c.name == null ? "" : c.name;
        sn.trigger = c.trigger == null ? "" : c.trigger;
        sn.content = c.content == null ? "" : c.content;
        sn.summary = c.summary == null ? "" : c.summary;
        sn.note = note == null ? "" : note;
        hist.add(sn);
        while (hist.size() > VERSION_HISTORY_CAP) hist.remove(0);
        return hist;
    }

    /**
     * 纯函数：调卡结局状态机（JVM 可单测）。
     * success 且为草稿 → 转正（复验成功一次即转正），返回 "promoted"；
     * 失败 → 打回草稿并记修卡原因（就地修卡标记），返回 "needs_repair"；
     * success 且已转正 → 仅计数，返回 "ok"。
     */
    static String recordUseOutcome(Card c, boolean success, String failNote) {
        if (c == null) return "missing";
        c.useCount++;
        if (success) {
            if (STATUS_DRAFT.equals(c.status)) {
                c.status = STATUS_OFFICIAL;
                c.verifyCount++;
                c.repairNote = "";
                return "promoted";
            }
            return "ok";
        }
        c.status = STATUS_DRAFT;
        c.repairNote = failNote == null ? "" : failNote;
        return "needs_repair";
    }

    public SkillCardStore(Context ctx) {
        File dir = ctx.getApplicationContext().getFilesDir();
        file = new File(dir, "skill_cards.json");
        guideFile = new File(dir, "skill_cards_guide.json");
        verFile = new File(dir, "skill_card_versions.json");
        load();
        loadVersions();
    }

    public synchronized String add(String name, String type, String source,
                                   String trigger, String content, String summary) {
        Card c = new Card();
        c.id = "card_" + System.currentTimeMillis();
        c.name = name;
        c.type = type;
        c.source = source;
        c.trigger = trigger == null ? "" : trigger;
        c.content = content == null ? "" : content;
        c.summary = summary == null ? "" : summary;
        c.useCount = 0;
        c.updatedAt = System.currentTimeMillis();
        cards.add(0, c);
        save();
        return c.id;
    }

    /**
     * 技能卡生命周期（v3.58 步骤1）：成功轨迹自动沉淀为草稿卡。
     * 草稿卡同样可被调卡（否则永无复验机会），调卡成功一次即转正；
     * 用户手动创建的卡走 add()（source=user 默认已转正，用户亲定即信任）。
     */
    public synchronized String addDraft(String name, String type, String source,
                                        String trigger, String content, String summary,
                                        String appPkg) {
        String id = add(name, type, source, trigger, content, summary);
        Card c = get(id);
        if (c != null) {
            c.status = STATUS_DRAFT;
            c.appPkg = appPkg == null ? "" : appPkg;
            save();
        }
        return id;
    }

    /**
     * 调卡结局落账：计数 + 草稿转正/失败打回草稿（v3.58 步骤1；use_card 接线在步骤3）。
     * 返回 promoted（复验转正）/ needs_repair（打回待修卡）/ ok / missing。
     */
    public synchronized String recordUse(String id, boolean success, String failNote) {
        Card c = get(id);
        String r = recordUseOutcome(c, success, failNote);
        if (c != null) save();
        return r;
    }

    /** 草稿转正（手动/管理入口；自动转正在 recordUse 里）。 */
    public synchronized boolean promote(String id) {
        Card c = get(id);
        if (c == null) return false;
        c.status = STATUS_OFFICIAL;
        c.verifyCount++;
        c.repairNote = "";
        save();
        return true;
    }

    /**
     * 就地修卡：快照旧版本 → 更新内容 → version++ → 打回草稿待复验
     *（SPEC：修卡同样走草稿→复验→转正）。
     */
    public synchronized boolean repair(String id, String name, String trigger,
                                       String content, String summary, String note) {
        Card c = get(id);
        if (c == null) return false;
        List<VersionSnap> hist = versionsFor(id);
        snapshotFor(hist, c, note == null ? "" : ("修卡前快照：" + note));
        if (name != null) c.name = name;
        if (trigger != null) c.trigger = trigger;
        if (content != null) c.content = content;
        if (summary != null) c.summary = summary;
        c.version++;
        c.status = STATUS_DRAFT;
        c.repairNote = note == null ? "" : note;
        c.updatedAt = System.currentTimeMillis();
        save();
        saveVersions();
        return true;
    }

    /**
     * 回滚到某版本：快照当前 → 恢复目标版本内容 → version++（单调）
     * → 打回草稿待复验。目标版本不存在返回 false。
     */
    public synchronized boolean rollback(String id, int version) {
        Card c = get(id);
        if (c == null) return false;
        List<VersionSnap> hist = versionsFor(id);
        VersionSnap target = null;
        for (VersionSnap sn : hist) {
            if (sn.version == version) target = sn;
        }
        if (target == null) return false;
        snapshotFor(hist, c, "回滚前快照（目标 v" + version + "）");
        c.name = target.name;
        c.trigger = target.trigger;
        c.content = target.content;
        c.summary = target.summary;
        c.version++;
        c.status = STATUS_DRAFT;
        c.repairNote = "回滚到 v" + version + "，待复验";
        c.updatedAt = System.currentTimeMillis();
        save();
        saveVersions();
        return true;
    }

    /** 版本历史（只读拷贝）。 */
    public synchronized List<VersionSnap> versions(String id) {
        return new ArrayList<>(versionsFor(id));
    }

    private List<VersionSnap> versionsFor(String id) {
        List<VersionSnap> h = versions.get(id);
        if (h == null) {
            h = new ArrayList<>();
            versions.put(id, h);
        }
        return h;
    }

    /** 按优先级排序：用户在前，自主在后；同级按使用次数 */
    public synchronized List<Card> listByPriority() {
        List<Card> out = new ArrayList<>(cards);
        out.sort((a, b) -> {
            int p = Integer.compare(b.priority(), a.priority());
            if (p != 0) return p;
            p = Integer.compare(b.useCount, a.useCount);
            if (p != 0) return p;
            return Long.compare(b.updatedAt, a.updatedAt);
        });
        return out;
    }

    public synchronized Card get(String id) {
        for (Card c : cards) if (c.id.equals(id)) return c;
        return null;
    }

    public synchronized void incrementUse(String id) {
        Card c = get(id);
        if (c != null) {
            c.useCount++;
            save();
        }
    }

    /** 可持续迭代更新 */
    public synchronized boolean update(String id, String name, String trigger,
                                       String content, String summary) {
        Card c = get(id);
        if (c == null) return false;
        if (name != null) c.name = name;
        if (trigger != null) c.trigger = trigger;
        if (content != null) c.content = content;
        if (summary != null) c.summary = summary;
        c.updatedAt = System.currentTimeMillis();
        save();
        return true;
    }

    /**
     * v3.12 Issue 12：导出单张卡为可分享 JSON。
     * 格式：{"skill_card":1,"name":...,"type":...,"trigger":...,"content":...,"summary":...}
     */
    public synchronized String exportJson(String id) {
        Card c = get(id);
        if (c == null) return null;
        try {
            JSONObject o = new JSONObject();
            o.put("skill_card", 1);
            o.put("name", c.name);
            o.put("type", c.type);
            o.put("trigger", c.trigger);
            o.put("content", c.content);
            o.put("summary", c.summary);
            return o.toString(2);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * v3.12 Issue 12：校验导入的 JSON。
     * 返回 null 表示通过，否则返回错误原因。
     * 检查：结构完整 + content 可解析 + 风险动作提示。
     */
    public static String validateImport(JSONObject o) {
        if (o == null) return "不是合法 JSON";
        if (o.optInt("skill_card", 0) != 1 && !o.has("name")) {
            return "缺少 skill_card 标记或 name 字段，可能不是技能卡 JSON";
        }
        String name = o.optString("name", "").trim();
        if (name.isEmpty()) return "name 为空";
        String type = o.optString("type", "skill");
        if (!TYPE_SKILL.equals(type) && !TYPE_SCRIPT.equals(type)) {
            return "type 非法（应为 skill/script）： " + type;
        }
        String content = o.optString("content", "");
        if (content.isEmpty()) return "content 为空";
        // content 结构检查
        try {
            if (TYPE_SKILL.equals(type)) {
                // skill 类：content 应为 {"steps":[...]} 或步骤数组
                String t = content.trim();
                if (t.startsWith("{")) {
                    JSONObject cj = new JSONObject(t);
                    if (!cj.has("steps")) return "skill 卡的 content 缺少 steps";
                } else if (t.startsWith("[")) {
                    new JSONArray(t);
                }
                // 其余（不以 {/[ 开头）= 说明型卡的自然语言说明，合法
            }
        } catch (Exception e) {
            return "content 解析失败：" + e.getMessage();
        }
        return null; // 通过
    }

    /**
     * v3.12 Issue 12：扫描高风险动作，返回警告文本（无风险返回空串）。
     */
    public static String scanRisk(String type, String content) {
        StringBuilder warn = new StringBuilder();
        try {
            String t = content == null ? "" : content.toLowerCase();
            // 危险关键词
            String[] risky = {"\"shell\"", "\"do\":\"shell\"", "rm -rf", "pm uninstall",
                    "am force-stop", "reboot", "format", "dd if="};
            for (String k : risky) {
                if (t.contains(k)) {
                    warn.append("含高风险内容：").append(k).append("；");
                    break;
                }
            }
            // 统计步骤数
            if (TYPE_SKILL.equals(type)) {
                String tc = content.trim();
                int n = 0;
                if (tc.startsWith("{")) {
                    n = new JSONObject(tc).optJSONArray("steps").length();
                } else if (tc.startsWith("[")) {
                    n = new JSONArray(tc).length();
                }
                if (n > 30) warn.append("步骤过多（").append(n).append("步）；");
            }
        } catch (Exception ignored) {
        }
        return warn.toString();
    }

    /**
     * v3.12 Issue 12：从已校验的 JSON 导入，返回新卡 id。
     */
    public synchronized String importValidated(JSONObject o) {
        String name = o.optString("name", "未命名技能");
        String type = o.optString("type", TYPE_SKILL);
        String trigger = o.optString("trigger", "");
        String content = o.optString("content", "");
        String summary = o.optString("summary", "导入的技能卡");
        return add(name, type, SRC_USER, trigger, content, summary);
    }

    public synchronized boolean delete(String id) {
        for (int i = 0; i < cards.size(); i++) {
            if (cards.get(i).id.equals(id)) {
                cards.remove(i);
                save();
                return true;
            }
        }
        return false;
    }

    /** 给 prompt 用的精简描述（按优先级） */
    public synchronized String describeForPrompt() {
        return describeCards(listByPriority());
    }

    /**
     * 按需注入：只返回与目标关键词相关的技能卡（省 token，对标 Hermes 按需加载）。
     * 无匹配时回退为全量（不让 agent 断粮），最多 5 张。
     */
    public synchronized String describeForPrompt(String goal) {
        if (goal == null || goal.trim().isEmpty()) return describeForPrompt();
        List<Card> matched = matchCards(new ArrayList<>(cards), goal);
        if (matched.isEmpty()) return describeForPrompt();
        if (matched.size() > 5) matched = matched.subList(0, 5);
        return describeCards(matched);
    }

    private static String describeCards(List<Card> list) {
        if (list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Card c : list) {
            sb.append("- [").append(c.id).append("] ").append(c.name)
                    .append("（").append(c.typeLabel()).append("·")
                    .append(c.sourceLabel());
            if (SRC_USER.equals(c.source)) sb.append("·优先");
            if (c.useCount > 0) sb.append("·已用").append(c.useCount).append("次");
            if (STATUS_DRAFT.equals(c.status)) sb.append("·草稿（待复验，调卡成功后转正）");
            sb.append("）触发：").append(c.trigger)
                    .append(" —— ").append(c.summary).append("\n");
        }
        return sb.toString();
    }

    /**
     * 关键词匹配：目标含卡片名/触发语，或卡片关键词出现在目标里即命中。
     * 中文按二元字串（bigram）匹配，避免分词不一致导致漏召回；
     * 英文数字按词匹配。按命中数×优先级排序。纯函数，可独立测试。
     */
    static List<Card> matchCards(List<Card> all, String goal) {
        String g = goal.toLowerCase();
        java.util.Set<String> kws = keywords(goal);
        List<Card> hit = new ArrayList<>();
        final java.util.Map<Card, Integer> score = new java.util.HashMap<>();
        for (Card c : all) {
            int s = 0;
            String hay = ((c.name == null ? "" : c.name) + " "
                    + (c.trigger == null ? "" : c.trigger) + " "
                    + (c.summary == null ? "" : c.summary)).toLowerCase();
            for (String kw : kws) {
                if (hay.contains(kw)) s++;
            }
            // 中文 bigram：卡片名/触发语的每两字在目标里出现即算相关
            String nt = ((c.name == null ? "" : c.name)
                    + (c.trigger == null ? "" : c.trigger)).toLowerCase();
            for (int i = 0; i + 1 < nt.length(); i++) {
                String bi = nt.substring(i, i + 2);
                if (bi.trim().length() == 2 && g.contains(bi)) s++;
            }
            // 卡片名/触发语直接出现在目标里（如触发语"设闹钟"，目标"帮我设个闹钟"）
            if (c.name != null && c.name.length() >= 2 && g.contains(c.name.toLowerCase())) s += 2;
            if (c.trigger != null && c.trigger.length() >= 2
                    && g.contains(c.trigger.toLowerCase())) s += 2;
            if (s > 0) {
                hit.add(c);
                score.put(c, s);
            }
        }
        hit.sort((a, b) -> {
            int p = Integer.compare(score.get(b), score.get(a));
            if (p != 0) return p;
            p = Integer.compare(b.priority(), a.priority());
            if (p != 0) return p;
            return Integer.compare(b.useCount, a.useCount);
        });
        return hit;
    }

    /** 目标分词：按非中英文数字切分，保留长度≥2 的词。纯函数。 */
    static java.util.Set<String> keywords(String goal) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (goal == null) return out;
        String[] parts = goal.toLowerCase()
                .split("[^\\p{IsHan}a-z0-9]+");
        for (String p : parts) {
            if (p.length() >= 2) out.add(p);
        }
        return out;
    }

    /** 按分区落盘：说明型只进 guide 文件，其余只进主文件 */
    private void save() {
        writeFile(file, false);
        writeFile(guideFile, true);
    }

    private void writeFile(File f, boolean guide) {
        try {
            JSONArray arr = new JSONArray();
            for (Card c : cards) {
                if (c.isProse() != guide) continue;
                JSONObject o = new JSONObject();
                o.put("id", c.id);
                o.put("name", c.name);
                o.put("type", c.type);
                o.put("source", c.source);
                o.put("trigger", c.trigger);
                o.put("content", c.content);
                o.put("summary", c.summary);
                o.put("useCount", c.useCount);
                o.put("updatedAt", c.updatedAt);
                o.put("status", c.status);
                o.put("version", c.version);
                o.put("verifyCount", c.verifyCount);
                o.put("repairNote", c.repairNote);
                o.put("appPkg", c.appPkg);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("cards", arr);
            Files.write(f.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /** 版本历史落盘（冷数据独立文件） */
    private void saveVersions() {
        try {
            JSONObject root = new JSONObject();
            JSONObject vs = new JSONObject();
            for (java.util.Map.Entry<String, List<VersionSnap>> e
                    : versions.entrySet()) {
                JSONArray arr = new JSONArray();
                for (VersionSnap sn : e.getValue()) arr.put(sn.toJson());
                vs.put(e.getKey(), arr);
            }
            root.put("versions", vs);
            Files.write(verFile.toPath(),
                    root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private void loadVersions() {
        try {
            if (!verFile.exists()) return;
            String txt = new String(Files.readAllBytes(verFile.toPath()),
                    StandardCharsets.UTF_8);
            JSONObject vs = new JSONObject(txt).optJSONObject("versions");
            if (vs == null) return;
            java.util.Iterator<String> keys = vs.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                JSONArray arr = vs.optJSONArray(id);
                if (arr == null) continue;
                List<VersionSnap> h = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    h.add(VersionSnap.fromJson(arr.getJSONObject(i)));
                }
                versions.put(id, h);
            }
        } catch (Exception ignored) {
        }
    }

    private void load() {
        // 先读说明型文件（说明型卡以此为准）、再读主文件，并按 id 去重：
        // 外部写入者若用旧快照把说明型卡复活回主文件，只会被重新归位，
        // 不会越滚越多（2026-10-22 迁移首夜实测：主文件被反复复活、
        // guide 一度滚到 18 张重复卡）
        boolean misplaced = loadFrom(guideFile, true);
        misplaced |= loadFrom(file, false);
        // 自愈分流：旧单文件或外部写错分区的卡，重新归位落盘
        if (misplaced) save();
    }

    /** 读一份卡文件进内存；返回 true 表示其中有放错分区的卡 */
    private boolean loadFrom(File f, boolean guide) {
        boolean misplaced = false;
        try {
            if (!f.exists()) return false;
            String txt = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            JSONArray arr = new JSONObject(txt).optJSONArray("cards");
            if (arr == null) return false;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Card c = new Card();
                c.id = o.optString("id");
                c.name = o.optString("name");
                c.type = o.optString("type", TYPE_SKILL);
                c.source = o.optString("source", SRC_AGENT);
                c.trigger = o.optString("trigger", "");
                c.content = o.optString("content", "");
                c.summary = o.optString("summary", "");
                c.useCount = o.optInt("useCount", 0);
                c.updatedAt = o.optLong("updatedAt", 0);
                c.status = o.optString("status", STATUS_OFFICIAL);
                c.version = o.optInt("version", 1);
                c.verifyCount = o.optInt("verifyCount", 0);
                c.repairNote = o.optString("repairNote", "");
                c.appPkg = o.optString("appPkg", "");
                if (c.isProse() != guide) misplaced = true;
                if (get(c.id) != null) continue; // 同 id 已加载（去重）
                cards.add(c);
            }
        } catch (Exception ignored) {
        }
        return misplaced;
    }
}
