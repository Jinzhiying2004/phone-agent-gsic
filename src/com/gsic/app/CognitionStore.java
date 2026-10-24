package com.gsic.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * v3.55 认知库（记忆分层·长期记忆的唯一形态）。
 * 设计稿：目标工作区 hidden_files/v351-design.md 第二节 2.1；
 * 规格：~/workspace/learning-loop-SPEC.md 第四节（记忆分层 doctrine）。
 *
 * 纯 Java：文件 IO 走 java.io.File（调用方传 ctx.getFilesDir()），
 * 无 Android 依赖，可在 JVM 直接单测。文件格式：
 * cognition.json = {"items":[{"id":"c-0001","kind":"..","text":"..",
 *   "src":"..","ts":..,"votes":1,"status":"active|draft","pkg":".."}]}
 *
 * 纪律（用户亲定）：
 * - 长期记忆只存认知（对用户的认知 + 对自身的认知），任务执行流水
 *   禁止入库——流水走 Distiller 蒸馏，蒸馏不出就丢。
 * - 只有 status=active 的条目参与注入；draft 只做投票计数。
 * - 转正条件：votes>=2（同类候选再出现自动 +1），或用户明确认可
 *   （confirm）。src=user_seed（用户亲定/种子迁移）直接 active。
 */
public class CognitionStore {

    public static final String FILE_NAME = "cognition.json";
    public static final int MAX_ITEMS = 500;

    /** 条目类型（设计稿 2.1 八类） */
    public static final String KIND_USER_DICT = "user_dict";
    public static final String KIND_USER_PREF = "user_pref";
    public static final String KIND_USER_CORRECTION = "user_correction";
    public static final String KIND_SELF_CAP = "self_cap";
    public static final String KIND_SELF_DEVICE = "self_device";
    public static final String KIND_SELF_LESSION = "self_lesson";
    public static final String KIND_ICON_WORD = "icon_word";
    public static final String KIND_UI_MAP = "ui_map";

    /** 来源 */
    public static final String SRC_USER_SEED = "user_seed";
    public static final String SRC_TASK_DISTILL = "task_distill";
    public static final String SRC_NIGHT_REVIEW = "night_review";

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DRAFT = "draft";

    /** 图标词典通用符号种子（kind=icon_word）。text 格式 "图标名=含义" */
    public static final String[] ICON_SEEDS = {
        "放大镜=搜索",
        "三横线（汉堡菜单）=菜单或更多选项",
        "齿轮=设置",
        "漏斗=筛选或过滤",
        "铃铛=通知或消息",
        "纸飞机=发送",
        "回形针=附件",
        "加号=新增或添加",
        "叉号=关闭",
        "左箭头=返回上一页",
        "三点=更多操作",
        "星星=收藏",
        "心形=喜欢或点赞",
        "分享箭头=分享",
    };

    public static class Item {
        public String id = "";
        public String kind = "";
        public String text = "";
        public String src = "";
        public long ts = 0L;
        public int votes = 1;
        public String status = STATUS_DRAFT;
        /** 仅 ui_map 用：App 包名 */
        public String pkg = "";

        public Item() {
        }
    }

    private final List<Item> items = new ArrayList<>();
    private int nextId = 1;

    /** 从目录加载；文件缺失/解析失败返回空库（不抛异常） */
    public static CognitionStore load(File dir) {
        CognitionStore s = new CognitionStore();
        try {
            if (dir == null) return s;
            File f = new File(dir, FILE_NAME);
            if (!f.exists()) return s;
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int off = 0, r;
            while (off < buf.length
                    && (r = in.read(buf, off, buf.length - off)) > 0) {
                off += r;
            }
            in.close();
            if (off == 0) return s;
            JSONObject root = new JSONObject(new String(buf, 0, off, "UTF-8"));
            JSONArray arr = root.optJSONArray("items");
            if (arr == null) return s;
            for (int i = 0; i < arr.length() && s.items.size() < MAX_ITEMS; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String kind = o.optString("kind", "").trim();
                String text = o.optString("text", "").trim();
                if (!isKind(kind) || text.isEmpty()) continue;
                Item it = new Item();
                it.id = o.optString("id", "").trim();
                it.kind = kind;
                it.text = text;
                it.src = o.optString("src", "").trim();
                it.ts = o.optLong("ts", 0L);
                it.votes = Math.max(1, o.optInt("votes", 1));
                it.status = STATUS_ACTIVE.equals(o.optString("status", ""))
                        ? STATUS_ACTIVE : STATUS_DRAFT;
                it.pkg = KIND_UI_MAP.equals(kind)
                        ? o.optString("pkg", "").trim() : "";
                s.items.add(it);
                int n = parseIdNum(it.id);
                if (n >= s.nextId) s.nextId = n + 1;
            }
        } catch (Exception ignored) {
        }
        return s;
    }

    /** 持久化到目录 */
    public synchronized void save(File dir) {
        try {
            if (dir == null) return;
            if (!dir.exists()) dir.mkdirs();
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("id", it.id);
                o.put("kind", it.kind);
                o.put("text", it.text);
                o.put("src", it.src);
                o.put("ts", it.ts);
                o.put("votes", it.votes);
                o.put("status", it.status);
                if (!it.pkg.isEmpty()) o.put("pkg", it.pkg);
                arr.put(o);
            }
            root.put("items", arr);
            File f = new File(dir, FILE_NAME);
            FileOutputStream out = new FileOutputStream(f);
            out.write(root.toString().getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * 新增认知条目。非法 kind/空 text 拒绝并返回空串。
     * src=user_seed 直接 active（用户亲定即认可）；其余 draft 起步。
     * pkg 只对 ui_map 有意义。返回条目 id。
     */
    public synchronized String put(String kind, String text, String src,
            String pkg) {
        if (!isKind(kind) || text == null || text.trim().isEmpty()) return "";
        Item it = new Item();
        it.id = "c-" + String.format("%04d", nextId++);
        it.kind = kind;
        it.text = text.trim();
        it.src = src == null ? "" : src.trim();
        it.ts = System.currentTimeMillis();
        it.votes = 1;
        it.status = SRC_USER_SEED.equals(it.src)
                ? STATUS_ACTIVE : STATUS_DRAFT;
        it.pkg = KIND_UI_MAP.equals(kind) && pkg != null ? pkg.trim() : "";
        items.add(it);
        while (items.size() > MAX_ITEMS) items.remove(0);
        return it.id;
    }

    /** 投票：同类候选再出现调用；votes>=2 自动转正。返回转正后状态 */
    public synchronized String vote(String id) {
        Item it = find(id);
        if (it == null) return "";
        it.votes++;
        if (it.votes >= 2) it.status = STATUS_ACTIVE;
        return it.status;
    }

    /** 用户明确认可 → 直接转正 */
    public synchronized boolean confirm(String id) {
        Item it = find(id);
        if (it == null) return false;
        it.status = STATUS_ACTIVE;
        return true;
    }

    /** 删除条目（认知条目留删除能力，供后续修卡机制复用） */
    public synchronized boolean remove(String id) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(id)) {
                items.remove(i);
                return true;
            }
        }
        return false;
    }

    public synchronized int size() {
        return items.size();
    }

    /** 全部条目（含 draft，供 Distiller 去重投票遍历） */
    public synchronized List<Item> all() {
        return new ArrayList<>(items);
    }

    public synchronized List<Item> active() {
        List<Item> out = new ArrayList<>();
        for (Item it : items) {
            if (STATUS_ACTIVE.equals(it.status)) out.add(it);
        }
        return out;
    }

    /**
     * 相关认知注入：对 active 条目做 bigram Jaccard>=0.15 取最相关 2 条
     *（算法复用 SharedMemory.relevantExperience 一套口径）。
     * "内部参考"头保留：注入只进任务上下文，禁入最终答案。
     */
    public synchronized String relevant(String goal) {
        if (goal == null || goal.isEmpty()) return "";
        Set<String> mine = bigrams(goal);
        if (mine.isEmpty()) return "";
        List<double[]> scored = new ArrayList<>();
        List<Item> act = active();
        for (int i = 0; i < act.size(); i++) {
            Item it = act.get(i);
            Set<String> theirs = bigrams(it.text);
            if (theirs.isEmpty()) continue;
            int inter = 0;
            for (String g : mine) if (theirs.contains(g)) inter++;
            double j = (double) inter
                    / (mine.size() + theirs.size() - inter);
            if (j >= 0.15) scored.add(new double[]{j, i});
        }
        if (scored.isEmpty()) return "";
        scored.sort((a, b) -> Double.compare(b[0], a[0]));
        StringBuilder sb = new StringBuilder();
        sb.append("[内部参考·勿向用户复述：以下为长期认知库中的相关认知，"
                + "仅供本次任务规划借鉴；与本次任务无关的条目请忽略，"
                + "最终回答用户时不要引用或复述这段内容。]\n");
        int n = Math.min(2, scored.size());
        for (int k = 0; k < n; k++) {
            Item it = act.get((int) scored.get(k)[1]);
            sb.append("- 「").append(kindName(it.kind)).append("」")
                    .append(trim(it.text, 60)).append("\n");
        }
        return sb.toString().trim();
    }

    /** 常驻注入：active 的 user_dict 条目（供步骤 3 接管 MasterDict 注入） */
    public synchronized String dictLines() {
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            if (STATUS_ACTIVE.equals(it.status)
                    && KIND_USER_DICT.equals(it.kind)) {
                sb.append("- ").append(it.text).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /** 钉住块用紧凑行：k=短义；…（供步骤 3 接管 MasterDict.describeShort） */
    public synchronized String dictShort() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Item it : items) {
            if (!STATUS_ACTIVE.equals(it.status)
                    || !KIND_USER_DICT.equals(it.kind)) {
                continue;
            }
            String key = keyOf(it.text);
            if (key.isEmpty()) continue;
            int i = it.text.indexOf('=');
            String v = i < 0 ? "" : it.text.substring(i + 1).trim();
            if (!first) sb.append("；");
            first = false;
            sb.append(key).append("=").append(trim(v, 30));
        }
        return sb.toString();
    }

    /** 图标词典：active 的 icon_word 条目全文（供视觉/规划提示词注入） */
    public synchronized String iconWords() {
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            if (STATUS_ACTIVE.equals(it.status)
                    && KIND_ICON_WORD.equals(it.kind)) {
                sb.append("- ").append(it.text).append("\n");
            }
        }
        return sb.toString().trim();
    }

    /** 界面地图：某包名的 active ui_map 条目 */
    public synchronized List<Item> uiMap(String pkg) {
        List<Item> out = new ArrayList<>();
        if (pkg == null) return out;
        for (Item it : items) {
            if (STATUS_ACTIVE.equals(it.status)
                    && KIND_UI_MAP.equals(it.kind)
                    && pkg.equals(it.pkg)) {
                out.add(it);
            }
        }
        return out;
    }

    /**
     * 主人词典种子迁移（步骤 3 接线：AgentLoop 任务入口调用）：
     * MasterDict 的用户亲定种子（src=user/user_seed）转为 user_dict 条目
     * （src=user_seed，直接 active），text 格式为 k=v。按 key（= 前部分）
     * 幂等：已存在同 key 条目时，若文本变化则更新（种子改版=改条目，
     * 不改代码，与 MasterDict.load 的种子覆盖语义一致），否则跳过；
     * 返回新增+更新的条目数。
     */
    public synchronized int importSeeds(MasterDict dict) {
        if (dict == null) return 0;
        int n = 0;
        for (MasterDict.Item si : dict.all()) {
            if (!SRC_USER_SEED.equals(si.src)
                    && !"user".equals(si.src)) {
                continue;
            }
            String key = si.k == null ? "" : si.k.trim();
            if (key.isEmpty()) continue;
            String text = key + "="
                    + (si.v == null ? "" : si.v.trim());
            Item found = null;
            for (Item it : items) {
                if (KIND_USER_DICT.equals(it.kind)
                        && key.equals(keyOf(it.text))) {
                    found = it;
                    break;
                }
            }
            if (found == null) {
                put(KIND_USER_DICT, text, SRC_USER_SEED, "");
                n++;
            } else if (!text.equals(found.text)) {
                found.text = text;
                found.src = SRC_USER_SEED;
                found.status = STATUS_ACTIVE;
                found.ts = System.currentTimeMillis();
                n++;
            }
        }
        return n;
    }

    /**
     * 图标词典种子幂等导入（步骤 4 接线：任务入口调用）。
     * 通用符号语义转 icon_word 条目（src=user_seed，直接 active）；
     * 已存在同 key 条目时跳过（不重复、不覆盖用户改过的内容）。
     * 返回新增条目数。
     */
    public synchronized int importIconSeeds() {
        int n = 0;
        for (String seed : ICON_SEEDS) {
            String key = keyOf(seed);
            if (key.isEmpty()) continue;
            boolean exists = false;
            for (Item it : items) {
                if (KIND_ICON_WORD.equals(it.kind)
                        && key.equals(keyOf(it.text))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                put(KIND_ICON_WORD, seed, SRC_USER_SEED, "");
                n++;
            }
        }
        return n;
    }

    /**
     * 界面地图写入/覆盖 API（步骤 4；探索规程的自动触发落后续版本）。
     * 按 (pkg, key) 幂等：key 取 text 在 '=' 或 '：' 之前的部分，
     * 都没有时取前 16 字；命中则覆盖 text 并刷新 ts，否则新增。
     * 同一 key 多次探索只保留最新版。返回条目 id。
     */
    public synchronized String putUiMap(String pkg, String text) {
        if (pkg == null || pkg.trim().isEmpty()
                || text == null || text.trim().isEmpty()) return "";
        String key = keyOfUi(text);
        for (Item it : items) {
            if (KIND_UI_MAP.equals(it.kind) && pkg.equals(it.pkg)
                    && key.equals(keyOfUi(it.text))) {
                it.text = text.trim();
                it.ts = System.currentTimeMillis();
                if (STATUS_DRAFT.equals(it.status)) it.status = STATUS_ACTIVE;
                return it.id;
            }
        }
        // 探索写入是经画面变化验证过的观察结论（错了下次探索覆盖修正），
        // 直接 active；不确定的蒸馏候选才走 draft/votes 转正。
        String id = put(KIND_UI_MAP, text.trim(), SRC_TASK_DISTILL, pkg.trim());
        Item created = find(id);
        if (created != null) created.status = STATUS_ACTIVE;
        return id;
    }

    /** ui_map 条目的 key：'=' 或 '：' 之前；都没有时取前 16 字 */
    private static String keyOfUi(String text) {
        if (text == null) return "";
        String t = text.trim();
        int i = t.indexOf('=');
        int j = t.indexOf('：');
        int k = i < 0 ? j : (j < 0 ? i : Math.min(i, j));
        if (k > 0) return t.substring(0, k).trim();
        return t.length() > 16 ? t.substring(0, 16) : t;
    }

    /**
     * 图标词典注入块（步骤 4 接线：视觉提示词组装处 + 规划提示词）。
     * 内容 = active 的 icon_word 通用义 + 该包 ui_map；
     * 同包名同图标名下 ui_map 覆盖通用义（该图标的通用行被丢弃，
     * 以地图文本为准）。pkg 为空时只出通用义。
     * 无条目时返回空串，调用方直接跳过拼接。
     */
    public synchronized String iconBlock(String pkg) {
        List<Item> maps = (pkg == null || pkg.isEmpty())
                ? new ArrayList<Item>() : uiMap(pkg);
        StringBuilder gen = new StringBuilder();
        for (Item it : items) {
            if (!STATUS_ACTIVE.equals(it.status)
                    || !KIND_ICON_WORD.equals(it.kind)) continue;
            String key = keyOf(it.text);
            boolean overridden = false;
            if (!key.isEmpty()) {
                for (Item m : maps) {
                    if (m.text != null && m.text.contains(key)) {
                        overridden = true;
                        break;
                    }
                }
            }
            if (overridden) continue;
            if (gen.length() > 0) gen.append("；");
            gen.append(it.text);
        }
        StringBuilder sb = new StringBuilder();
        if (gen.length() > 0) {
            sb.append("[图标词典] 以下常见图标的通常含义"
                    + "（定位纯图标按钮时参考）：").append(gen);
        }
        if (!maps.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("[界面地图 ").append(pkg).append("]"
                    + "（该 App 的特殊含义，以地图为准）：");
            for (Item m : maps) {
                sb.append("\n- ").append(trim(m.text, 120));
            }
        }
        return sb.toString();
    }

    /** user_dict 文本 "k=v" 的 key 部分（= 前）；无 = 时取全文 */
    private static String keyOf(String text) {
        if (text == null) return "";
        int i = text.indexOf('=');
        return (i < 0 ? text : text.substring(0, i)).trim();
    }

    // ---------------- 内部 ----------------

    private Item find(String id) {
        if (id == null) return null;
        for (Item it : items) {
            if (id.equals(it.id)) return it;
        }
        return null;
    }

    private static boolean isKind(String k) {
        return KIND_USER_DICT.equals(k) || KIND_USER_PREF.equals(k)
                || KIND_USER_CORRECTION.equals(k) || KIND_SELF_CAP.equals(k)
                || KIND_SELF_DEVICE.equals(k) || KIND_SELF_LESSION.equals(k)
                || KIND_ICON_WORD.equals(k) || KIND_UI_MAP.equals(k);
    }

    private static String kindName(String k) {
        if (KIND_USER_DICT.equals(k)) return "用户词典";
        if (KIND_USER_PREF.equals(k)) return "用户偏好";
        if (KIND_USER_CORRECTION.equals(k)) return "用户纠正";
        if (KIND_SELF_CAP.equals(k)) return "自身能力";
        if (KIND_SELF_DEVICE.equals(k)) return "设备认知";
        if (KIND_SELF_LESSION.equals(k)) return "自身教训";
        if (KIND_ICON_WORD.equals(k)) return "图标词典";
        if (KIND_UI_MAP.equals(k)) return "界面地图";
        return k;
    }

    private static int parseIdNum(String id) {
        try {
            if (id != null && id.startsWith("c-")) {
                return Integer.parseInt(id.substring(2));
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** 与 SharedMemory.bigrams 同口径（去空白与标点后按字二元组） */
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
