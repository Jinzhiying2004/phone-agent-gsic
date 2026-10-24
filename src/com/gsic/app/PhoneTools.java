package com.gsic.app;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * v3.26 DSH 式工具层：原生 function schema + 富信息结果。
 * - observe 是模型之眼：模型自己决定何时看屏（不再每步强制喂图）
 * - 动作工具返回新状态摘要/失败带可恢复上下文（找不着字→返回当前可见文本列表）
 * - 权限分级沿用 ActionRisk + TaskRunner 审批流
 */
public class PhoneTools {

    public static class Exec {
        public String text;   // 给模型的工具结果文本
        public Bitmap shot;   // observe 附带的截图（harness 负责以图片消息注入）
        public boolean ok;
    }

    public static JSONArray schemas() {
        JSONArray arr = new JSONArray();
        arr.put(fn("observe", "看屏幕：返回截图和当前界面控件列表。需要眼睛时调用",
                new String[0]));
        arr.put(fn("tap_text", "按可见文字点击按钮",
                req("text"), prop("text", "string", "按钮文字，如\"设置\"")));
        arr.put(fn("tap", "按坐标点击。x/y 是 0-1000 归一化坐标（左上角 (0,0)，右下角 (1000,1000)），对着 observe 截图按比例估算即可，绝不要报像素值",
                req("x", "y"),
                prop("x", "integer", "横坐标（0-1000 归一化）"), prop("y", "integer", "纵坐标（0-1000 归一化）")));
        arr.put(fn("vision", "看屏幕回答问题：定位图标坐标、判断画面状态（坐标会以 0-1000 归一化返回，可直接给 tap 用）",
                req("question"), prop("question", "string", "如\"快门按钮的坐标是多少？\"")));
        arr.put(fn("input", "在已聚焦的输入框输入文本",
                req("text"), prop("text", "string", "要输入的文本")));
        arr.put(fn("swipe", "滑动（坐标同 tap：0-1000 归一化）",
                req("from", "to"),
                prop("from", "array", "起点[x,y]（0-1000）"), prop("to", "array", "终点[x,y]（0-1000）")));
        arr.put(fn("back", "返回上一页", new String[0]));
        arr.put(fn("home", "回到桌面/主屏（关闭当前应用回到桌面）", new String[0]));
        arr.put(fn("launch", "启动应用",
                req("package"), prop("package", "string", "包名，如com.tencent.mm")));
        arr.put(fn("sleep", "等待几秒",
                req("s"), prop("s", "integer", "秒数")));
        arr.put(fn("wait_text", "等待指定文本出现",
                req("text"), prop("text", "string", "文本"),
                prop("timeout", "integer", "超时秒，默认10")));
        arr.put(fn("use_card", "调用已保存的技能卡",
                req("id"), prop("id", "string", "技能卡id")));
        // v3.59 步骤4：就地修卡——use_card 失败后先对地图、再修卡（修卡走草稿→复验→转正）。
        arr.put(fn("repair_card", "就地修卡：调卡(use_card)失败后用它修卡；只传 id 即读取该卡当前内容与失败记录+版本快照列表（不改卡），传 content+note 即修卡（旧版本自动快照，修后打回草稿、复验成功一次自动转正），传 rollback=<版本> 即回滚到历史快照（回滚前也自动快照，打回草稿待复验；与 content 互斥）",
                req("id"), prop("id", "string", "技能卡id"),
                prop("content", "string", "修好的卡片内容全文；留空=只读当前内容与失败记录"),
                prop("note", "string", "修卡原因/病因（如哪一步失效、因何失效）"),
                prop("name", "string", "可选：新卡名，留空不变"),
                prop("trigger", "string", "可选：新触发语，留空不变"),
                prop("summary", "string", "可选：新摘要，留空不变"),
                prop("rollback", "integer", "回滚到指定快照版本；与 content 互斥；读卡时返回版本列表")));
        arr.put(fn("termux", "内置 Termux（bash/python3/git）：能写脚本解决的优先写脚本，"
                + "不要手点界面；改软件先找它的配置文件/数据文件（用 read_file 读），"
                + "改完用脚本验证状态",
                req("cmd"), prop("cmd", "string", "命令")));
        arr.put(fn("shell", "执行 root shell 命令",
                req("cmd"), prop("cmd", "string", "命令")));
        arr.put(fn("search", "联网搜索",
                req("query"), prop("query", "string", "搜索词"),
                prop("count", "integer", "结果数，默认5")));
        // 学习梯子步骤1：第 3 级——两轮搜完无果时问用户一句（选项式）。
        // App 通道弹全局悬浮窗选项（120 秒超时）；TG/远控通道转结论内提问。
        arr.put(fn("ask_user", "问用户一句（选项式）：学习梯子第 3 级，"
                + "两轮搜索无果后用它向用户求助；用户选完按选择继续；"
                + "时机：只问下一步动作真需要的空位，不提前问后续步骤，多空位一次问一个；"
                + "问前先查设备已有信息（日历/联系人/通知/词典/当前屏面），能自己定的不问；"
                + "敏感动作的\"行不行\"不走它，直接调对应工具走风险确认通道",
                req("question"), prop("question", "string", "要问用户的问题"),
                prop("options", "string", "候选选项（JSON 数组字符串，如[\"自动选择\",\"日本\"]；不填=开放式提问）"),
                prop("hint", "string", "可选：给用户的补充说明（如已试过的方法）")));
        arr.put(fn("read_file", "读文件（本软件私有目录之外自动 root 提权读，"
                + "如其他 App 的 /data/data；找配置优先用它）",
                req("path"), prop("path", "string", "路径")));
        arr.put(fn("write_file", "写文件（域外自动 root 提权原位写，"
                + "保留原文件属主与权限）",
                req("path", "content"),
                prop("path", "string", "路径"), prop("content", "string", "内容")));
        // v3.57 本地编程能力：片段编辑，按原文片段精确替换，只改几行/大文件时用它（不用整写）。
        // 域外路径自动 root 提权原位写；找不到片段时失败并给出候选行提示。
        arr.put(fn("edit_file", "片段编辑文件：按原文片段精确替换",
                req("path", "old", "new"),
                prop("path", "string", "路径"),
                prop("old", "string", "待替换原文片段，必须精确匹配（含缩进）"),
                prop("new", "string", "替换后文本"),
                prop("occurrence", "integer", "第几次出现，默认1")));
        arr.put(fn("fetch_url", "抓网页正文",
                req("url"), prop("url", "string", "网址")));
        arr.put(fn("remember", "记住用户信息，下次直接用",
                req("key", "value"),
                prop("key", "string", "键"), prop("value", "string", "值")));
        arr.put(fn("vd_start", "开启虚拟屏：后续操作在虚拟屏里进行，不打扰主屏。"
                + "主显示在任务开始时已锁定，锁定实体屏的任务调用会被拒绝", new String[0]));
        arr.put(fn("vd_stop", "关闭虚拟屏（主显示锁定为虚拟屏的任务在结束前调用会被拒绝，不能中途切到实体屏）", new String[0]));
        return arr;
    }

    /** 工具调用 → TaskRunner step JSON。observe 不走 TaskRunner。 */
    public static String toolToStep(String name, String argsJson) {
        if ("observe".equals(name)) return null;
        try {
            JSONObject args = new JSONObject(argsJson == null ? "{}" : argsJson);
            JSONObject step = new JSONObject();
            step.put("do", name);
            for (String k : new String[]{"text", "x", "y", "question", "from", "to",
                    "package", "s", "timeout", "id", "cmd", "query", "count",
                    "path", "content", "url", "key", "value",
                    "old", "new", "occurrence", "options", "hint"}) {
                if (args.has(k)) step.put(k, args.get(k));
            }
            return step.toString();
        } catch (Exception e) {
            return "{\"do\":\"" + name + "\"}";
        }
    }

    /**
     * 执行工具并返回富结果。TaskRunner 由调用方（AgentSession）配置好传入。
     */
    /** 当前会话通道（AgentLoop 每次任务开始时设置）：vd 仅 app 可用 */
    public static volatile String sessionChannel = "";

    /**
     * 主显示锁定（2026-10-23 用户亲定主从关系）：AgentLoop 在任务开始时
     * 按通道与虚拟屏创建结果钉死为 "vd" 或 "main"，任务结束才清。
     * 锁定后 vd_start/vd_stop 一律拒绝——一个任务不能同时存在于
     * 虚拟屏和实体屏，中途跨屏会让同一应用在双屏各留一份、点错屏。
     */
    public static volatile String sessionDisplayLock = "";

    public static Exec execute(Context ctx, TaskRunner runner, String name,
                               String argsJson, boolean vdMode) {
        Exec e = new Exec();
        try {
            if ("vd_start".equals(name)) {
                // 主从互斥：本任务主显示已锁定为实体屏时，禁止中途另开虚拟屏
                if ("main".equals(sessionDisplayLock)) {
                    e.ok = false;
                    e.text = "本任务主显示已锁定为实体屏，任务结束前不能再开虚拟屏"
                            + "（一个任务不能同时存在于虚拟屏和实体屏）。"
                            + "请继续在实体屏完成本任务；需要虚拟屏请另起一个任务。";
                    return e;
                }
                if ("vd".equals(sessionDisplayLock)) {
                    e.ok = true;
                    e.text = "本任务主显示已锁定为虚拟屏且正在运行（displayId="
                            + VirtualDisplayManager.getDisplayId() + "），继续在虚拟屏执行即可。";
                    return e;
                }
                // v3.36：虚拟屏仅 App 通道可用，TG/远控直接在主屏执行
                if ("tg".equals(sessionChannel) || "remote".equals(sessionChannel)) {
                    e.ok = false;
                    e.text = "当前通道（TG/远控）不使用虚拟屏，直接在主屏执行任务即可。";
                    return e;
                }
                // 动态虚拟屏：用户在设置里关闭虚拟屏功能则拒绝
                boolean allowed = ctx.getSharedPreferences("agent", Context.MODE_PRIVATE)
                        .getBoolean("task_vd_default", true);
                if (!allowed) {
                    e.ok = false;
                    e.text = "虚拟屏功能已在设置中关闭，直接在主屏操作。";
                    return e;
                }
                if (VirtualDisplayManager.isActive()) {
                    e.ok = true;
                    e.text = "虚拟屏已在运行（displayId="
                            + VirtualDisplayManager.getDisplayId() + "）。";
                    return e;
                }
                int did = VirtualDisplayManager.create(ctx, 720, 1280, 320);
                if (did < 0) {
                    e.ok = false;
                    e.text = "虚拟屏创建失败，直接在主屏操作。";
                    return e;
                }
                runner.setVdMode(true);
                e.ok = true;
                e.text = "🖥 虚拟屏已开启（displayId=" + did
                        + "）。后续操作都在虚拟屏里进行，主屏不受打扰；"
                        + "想看虚拟屏画面调 observe，用完记得 vd_stop。";
                return e;
            }
            if ("vd_stop".equals(name)) {
                // 主从互斥：虚拟屏主显示任务结束前禁止切到实体屏
                if ("vd".equals(sessionDisplayLock)) {
                    e.ok = false;
                    e.text = "本任务主显示已锁定为虚拟屏，任务结束前不能切到实体屏"
                            + "（一个任务不能同时存在于虚拟屏和实体屏；实体屏右上角"
                            + "小窗只是实时预览，不承载任务）。请继续在虚拟屏完成"
                            + "本任务；确需操作实体屏当前状态，请先收尾本任务、"
                            + "在结论里说明，再另起一个实体屏任务。";
                    return e;
                }
                runner.setVdMode(false);
                try {
                    VirtualDisplayManager.destroy();
                } catch (Exception ignored) {
                }
                e.ok = true;
                e.text = "虚拟屏已关闭，回到主屏操作。";
                return e;
            }
            if ("observe".equals(name)) {
                Bitmap shot = null;
                String desc;
                if (vdMode) {
                    // 虚拟屏：截图源是 VirtualDisplayManager（带重试，刚建屏可能暂无帧）
                    for (int i = 0; i < 5 && shot == null; i++) {
                        shot = VirtualDisplayManager.screenshot();
                        if (shot == null) {
                            try { Thread.sleep(600); } catch (Exception ignored) {}
                        }
                    }
                    desc = shot != null ? "（虚拟屏截图见附图）"
                            : "（虚拟屏暂无画面，可能目标应用还没启动，先 launch）";
                } else {
                    List<AgentAccessibilityService.NodeInfo> nodes =
                            AgentAccessibilityService.dumpScreen();
                    try {
                        shot = ScreenshotUtil.capture(ctx);
                    } catch (Exception ignored) {
                    }
                    desc = nodes.isEmpty()
                            ? "（读不到控件树，靠截图判断）"
                            : "控件列表：\n" + compactList(nodes);
                }
                e.ok = shot != null || !desc.contains("暂无画面");
                e.text = desc + (shot == null ? "\n截图为空" : "");
                e.shot = shot;
                return e;
            }
            // v3.38：坐标全链路 0-1000 归一化 → 像素。模型看的是 720p 缩放图，
            // 直报像素必然系统性偏移，这里按当前屏（虚拟屏/主屏）真实尺寸换算
            if ("tap".equals(name) || "swipe".equals(name)) {
                try {
                    JSONObject a = new JSONObject(
                            argsJson == null ? "{}" : argsJson);
                    int[] dim = screenDim(ctx, vdMode);
                    if ("tap".equals(name)) {
                        a.put("x", normToPx(a.optDouble("x", 0), dim[0]));
                        a.put("y", normToPx(a.optDouble("y", 0), dim[1]));
                    } else {
                        JSONArray f = a.optJSONArray("from");
                        JSONArray t = a.optJSONArray("to");
                        if (f != null && f.length() >= 2) {
                            f.put(0, normToPx(f.optDouble(0), dim[0]));
                            f.put(1, normToPx(f.optDouble(1), dim[1]));
                        }
                        if (t != null && t.length() >= 2) {
                            t.put(0, normToPx(t.optDouble(0), dim[0]));
                            t.put(1, normToPx(t.optDouble(1), dim[1]));
                        }
                    }
                    argsJson = a.toString();
                } catch (Exception ignored) {
                }
            }
            String stepJson = toolToStep(name, argsJson);
            if (stepJson == null) {
                e.ok = false;
                e.text = "未知工具：" + name;
                return e;
            }
            boolean ok = runner.execOneStep(stepJson);
            String out = runner.getLastOutput();
            e.ok = ok;
            StringBuilder sb = new StringBuilder(ok ? "成功" : "失败");
            if (out != null && !out.isEmpty()) sb.append("：").append(trim(out, "use_card".equals(name) ? 2600 : 600));
            // DSH 式富上下文：失败带恢复信息，成功带新状态摘要
            String extra = enrich(ctx, name, argsJson, ok, vdMode);
            if (!extra.isEmpty()) sb.append("\n").append(extra);
            e.text = sb.toString();
            return e;
        } catch (Exception ex) {
            e.ok = false;
            e.text = "执行异常：" + ex.getMessage();
            return e;
        }
    }

    /** 0-1000 归一化 → 像素；入参 >1000 视为已是像素，钳制即可（兼容两种回答） */
    private static int normToPx(double v, int dim) {
        if (v > 1000) return Math.max(0, Math.min((int) v, dim - 1));
        return Math.max(0, Math.min((int) (v * dim / 1000.0), dim - 1));
    }

    /** 当前操作屏的真实像素尺寸（虚拟屏固定 720x1280，主屏取真实分辨率） */
    private static int[] screenDim(Context ctx, boolean vdMode) {
        if (vdMode && VirtualDisplayManager.isActive()) return new int[]{720, 1280};
        try {
            android.view.WindowManager wm = (android.view.WindowManager)
                    ctx.getSystemService(Context.WINDOW_SERVICE);
            android.graphics.Point pt = new android.graphics.Point();
            wm.getDefaultDisplay().getRealSize(pt);
            return new int[]{pt.x, pt.y};
        } catch (Exception e) {
            return new int[]{1080, 1920};
        }
    }

    /** 失败→可恢复上下文；成功→新状态摘要 */
    private static String enrich(Context ctx, String name, String argsJson,
                                 boolean ok, boolean vdMode) {
        try {
            if (vdMode) {
                return ok ? "（虚拟屏：用 observe 看新画面确认）" : "（虚拟屏：用 observe 看当前画面）";
            }
            List<AgentAccessibilityService.NodeInfo> nodes =
                    AgentAccessibilityService.dumpScreen();
            if (!ok && "tap_text".equals(name)) {
                String want;
                try {
                    want = new JSONObject(argsJson).optString("text", "");
                } catch (Exception ex) {
                    want = "";
                }
                return "没找到文字「" + want + "」。当前可见文本：\n" + compactList(nodes);
            }
            if (!ok && "launch".equals(name)) {
                return "启动失败：检查包名是否正确、应用是否已安装。";
            }
            if (ok && ("tap_text".equals(name) || "tap".equals(name)
                    || "back".equals(name) || "home".equals(name)
                    || "swipe".equals(name) || "launch".equals(name))) {
                String s = compactList(nodes);
                return s.isEmpty() ? "" : "新界面：\n" + s;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** 控件列表压成紧凑文本（最多 25 条） */
    private static String compactList(List<AgentAccessibilityService.NodeInfo> nodes) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (AgentAccessibilityService.NodeInfo nd : nodes) {
            String t = nd.text == null ? "" : nd.text.trim();
            if (t.isEmpty() || t.length() > 24) continue;
            sb.append("- ").append(t);
            if (nd.clickable) sb.append(" [可点]");
            sb.append("\n");
            if (++n >= 25) break;
        }
        return sb.toString().trim();
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    // ---- schema 构造小工具 ----
    private static JSONObject fn(String name, String desc, String[] required, JSONObject... props) {
        try {
            JSONObject f = new JSONObject();
            f.put("name", name);
            f.put("description", desc);
            JSONObject p = new JSONObject();
            p.put("type", "object");
            JSONObject pr = new JSONObject();
            for (JSONObject o : props) pr.put(o.getString("_k"), o);
            p.put("properties", pr);
            JSONArray r = new JSONArray();
            for (String s : required) r.put(s);
            p.put("required", r);
            f.put("parameters", p);
            JSONObject w = new JSONObject();
            w.put("type", "function");
            w.put("function", f);
            return w;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONObject prop(String k, String type, String desc) {
        try {
            JSONObject o = new JSONObject();
            o.put("_k", k);
            o.put("type", type);
            o.put("description", desc);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String[] req(String... keys) { return keys; }
}
