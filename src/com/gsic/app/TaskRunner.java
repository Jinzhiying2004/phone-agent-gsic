package com.gsic.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

/** 任务执行器：解析 JSON 任务并本地执行。运行期零 token、零联网。 */
public class TaskRunner {

    public interface Listener {
        void onLog(String msg);
    }

    /**
     * 高风险动作审批人：ASK 策略的动作执行前调用，返回 true=放行。
     * 本地 UI 用弹窗向用户确认；无审批人时：远程任务默认拦截，
     * 本地受信任务（定时任务）记录警告后放行。
     */
    public interface Approver {
        boolean approve(String action, String detail);
    }

    private final Context ctx;
    private final Listener listener;
    private volatile boolean stopped = false;
    private String lastOutput = "";
    private Approver approver = null;
    private boolean remote = false;
    // 学习梯子步骤2：任务通道（见 setChannel）
    private String channel = "";
    private ActionRisk.Policy riskPolicy = null;
    // v3.10 Issue 4：远程高危动作策略（deny=一律拒绝，allow_log=允许但记录）
    private String remoteRiskPolicy = "deny";
    // v3.14：虚拟屏模式——主屏动作透明路由到 vd_*，用户主屏不受打扰
    private boolean vdMode = false;

    // 学习梯子步骤1：每任务独立的爬梯记账（联网搜索 2 轮硬上限 + 问用户状态）
    // TaskRunner 按任务构造（AgentLoop/TgBot/ApiHandler 每次任务 new 一个），
    // 故实例字段即等于每任务预算。
    private final LearningLadder ladder = new LearningLadder();

    // 记忆分层步骤 4：认知库（图标词典注入用），由 AgentLoop.setCognitionStore 传入
    private CognitionStore cogStore = null;
    public void setCognitionStore(CognitionStore c) { cogStore = c; }

    // 前台包名缓存（10s TTL；取不到时返回空串，只出通用图标义）
    private long fgPkgTs = 0;
    private String fgPkgCache = "";
    /** v3.51 步骤5：遥测 step_env 的 fg_app 字段——复用 10s TTL 缓存，零额外开销 */
    public String fgPackage() { return foregroundPkgCached(); }
    private String foregroundPkgCached() {
        long now = System.currentTimeMillis();
        if (fgPkgTs > 0 && now - fgPkgTs < 10000) return fgPkgCache;
        fgPkgTs = now;
        String p = "";
        try {
            if (Root.findSu() != null) {
                String[] r = RootShell.get().exec(
                        "dumpsys activity activities | grep -m 1 mResumedActivity", 3);
                if (r != null && r.length > 1 && r[1] != null) {
                    String out = r[1];
                    int u = out.indexOf("u0 ");
                    if (u >= 0) {
                        int a = u + 3;
                        int b = out.indexOf('/', a);
                        if (b > a) p = out.substring(a, b).trim();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        fgPkgCache = p;
        return p;
    }

    public TaskRunner(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        this.riskPolicy = new ActionRisk.Policy(this.ctx);
    }

    /** 设置审批人（本地 UI 传入弹窗确认实现） */
    public void setApprover(Approver approver) {
        this.approver = approver;
    }

    /** 标记是否为远程（HTTP / Telegram）进来的任务：无审批人时 ASK 策略直接拦截 */
    public void setRemote(boolean remote) {
        this.remote = remote;
    }

    /**
     * 学习梯子步骤2：设置任务通道（app/tg/remote）。
     * ask_user 按通道选提问方式：app 走悬浮窗，tg 走同步提问，
     * remote（HTTP 远控）无聊天回路只能结论降级。
     * AgentLoop 会同步 PhoneTools.sessionChannel；直接 new TaskRunner 的
     * 路径（如 TgBot /task）由调用方显式 setChannel。
     */
    public void setChannel(String ch) {
        this.channel = ch == null ? "" : ch;
    }

    /** v3.10 Issue 4：设置远程高危动作策略 */
    public void setRemoteRiskPolicy(String policy) {
        this.remoteRiskPolicy = policy;
    }

    /** v3.14：设置虚拟屏模式（AgentLoop 在任务开始时根据配置调用） */
    public void setVdMode(boolean vdMode) {
        this.vdMode = vdMode;
    }

    public boolean isVdMode() {
        return vdMode;
    }

    /**
     * v3.14：虚拟屏透明改写。tap/swipe/back/launch/input → vd_*；
     * tap_text → 视觉定位 + vd_tap；其余（shell/termux/sleep 等）直通。
     * 返回 null 表示改写失败（调用方直接返回 false）。
     */
    private JSONObject rewriteForVd(JSONObject st) throws Exception {
        String do_ = st.getString("do");
        switch (do_) {
            case "tap": {
                JSONObject o = new JSONObject();
                o.put("do", "vd_tap");
                o.put("x", st.optDouble("x", 0));
                o.put("y", st.optDouble("y", 0));
                return o;
            }
            case "swipe": {
                JSONArray f = st.getJSONArray("from"), t = st.getJSONArray("to");
                JSONObject o = new JSONObject();
                o.put("do", "vd_swipe");
                o.put("x1", f.getInt(0));
                o.put("y1", f.getInt(1));
                o.put("x2", t.getInt(0));
                o.put("y2", t.getInt(1));
                o.put("duration", st.optInt("ms", 400));
                return o;
            }
            case "back": {
                JSONObject o = new JSONObject();
                o.put("do", "vd_key");
                o.put("keycode", 4);
                return o;
            }
            case "home": {
                JSONObject o = new JSONObject();
                o.put("do", "vd_key");
                o.put("keycode", 3);
                return o;
            }
            case "launch": {
                JSONObject o = new JSONObject();
                o.put("do", "vd_launch");
                o.put("package", st.getString("package"));
                return o;
            }
            case "input": {
                JSONObject o = new JSONObject();
                o.put("do", "vd_input");
                o.put("text", st.getString("text"));
                return o;
            }
            case "tap_text": {
                return vdTapText(st.optString("text", ""));
            }
            default:
                return st;
        }
    }

    /** v3.14：虚拟屏 tap_text——视觉问坐标再 vd_tap */
    private JSONObject vdTapText(String text) throws Exception {
        if (text.isEmpty()) {
            log("tap_text 缺少 text 参数");
            return null;
        }
        android.graphics.Bitmap bmp = VirtualDisplayManager.screenshot();
        if (bmp == null) {
            log("虚拟屏截屏失败，无法定位文字");
            return null;
        }
        log("虚拟屏找字: " + text);
        // v3.21：归一化定位（0-1000），避免模型直接估算像素的缩放误差
        int[] xy = visionLocate(text, bmp);
        if (xy == null) {
            log("虚拟屏定位失败: " + text);
            return null;
        }
        JSONObject o = new JSONObject();
        o.put("do", "vd_tap");
        o.put("x", xy[0]);
        o.put("y", xy[1]);
        return o;
    }

    /**
     * v3.21：归一化视觉定位。让模型以 0-1000 归一化坐标回答中心点，
     * 再换算成像素。直接问像素坐标时模型常因缩放估算偏差点不中
     * （如把 y=965 估成 1030，小按钮就点空）。
     * 返回 {x_px, y_px}，失败返回 null。
     */
    private int[] visionLocate(String text,
                               android.graphics.Bitmap bmp) {
        String q = "找出界面上文字为「" + text + "」的可点击元素，"
                + "以 0-1000 归一化坐标回答它的中心点"
                + "（左上角为 (0,0)，右下角为 (1000,1000)），"
                + "只回答 JSON {\"x\": 数字, \"y\": 数字}，不要解释。";
        String ans = visionAsk(q, bmp);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"x\"\\s*:\\s*(\\d+)[^0-9\"']*\"y\"\\s*:\\s*(\\d+)")
                .matcher(ans);
        if (!m.find()) {
            // 兼容模型返回 (x, y) 格式
            m = java.util.regex.Pattern
                    .compile("\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)")
                    .matcher(ans);
            if (!m.find()) return null;
        }
        int nx = Integer.parseInt(m.group(1));
        int ny = Integer.parseInt(m.group(2));
        int w = bmp.getWidth(), h = bmp.getHeight();
        // 归一化转像素；任一值>1000 说明模型直接给了像素，整体按像素钳制
        int px, py;
        if (nx <= 1000 && ny <= 1000) {
            px = nx * w / 1000;
            py = ny * h / 1000;
        } else {
            px = Math.min(nx, w - 1);
            py = Math.min(ny, h - 1);
        }
        log("虚拟屏定位 " + text + ": 归一化(" + nx + "," + ny
                + ") → 像素(" + px + "," + py + ")");
        return new int[]{px, py};
    }

    /** v3.14：虚拟屏 wait_text——轮询视觉判断（最多 6 次，间隔 3 秒） */
    private boolean vdWaitText(String kw, int timeoutSec) {
        log("虚拟屏等待文本: " + kw);
        long end = System.currentTimeMillis() + timeoutSec * 1000L;
        int polls = 0;
        while (System.currentTimeMillis() < end && polls < 6) {
            polls++;
            android.graphics.Bitmap bmp = VirtualDisplayManager.screenshot();
            if (bmp != null) {
                String ans = visionAsk(
                        "界面上是否出现了文字「" + kw + "」？只回答 yes 或 no。",
                        bmp);
                if (ans.trim().toLowerCase().startsWith("yes")) {
                    log("虚拟屏等到文本: " + kw);
                    return true;
                }
            }
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        log("虚拟屏等待超时: " + kw);
        return false;
    }

    /** v3.14：虚拟屏 assert_text——单次视觉判断 */
    private boolean vdAssertText(String kw) {
        android.graphics.Bitmap bmp = VirtualDisplayManager.screenshot();
        if (bmp == null) return false;
        String ans = visionAsk(
                "界面上是否出现了文字「" + kw + "」？只回答 yes 或 no。", bmp);
        boolean ok = ans.trim().toLowerCase().startsWith("yes");
        log("虚拟屏断言 " + kw + ": " + (ok ? "通过" : "失败"));
        return ok;
    }

    /**
     * v3.14：虚拟屏视觉问答（复用 vision 动作的模型优先级：
     * 辅助视觉模型 > 主力视觉模型 > 主力模型）。
     * v3.17：辅助模型调用失败时自动降级用主力模型（baseUrl 配错等场景）。
     */
    private String visionAsk(String question,
                             android.graphics.Bitmap bmp) {
        // v3.21：统一要求归一化坐标，避免像素估算偏差
        String q = question + "\n\n（如需回答位置，请用 0-1000 归一化坐标，"
                + "左上角为原点；不要估算像素值。）";
        // 记忆分层步骤 4：图标词典注入（通用义 + 前台 App 界面地图覆盖）
        try {
            if (cogStore != null) {
                String ib = cogStore.iconBlock(foregroundPkgCached());
                if (!ib.isEmpty()) q = q + "\n\n" + ib;
            }
        } catch (Exception ignored) {
        }
        // 1) 辅助视觉模型
        try {
            AuxModelStore aux = new AuxModelStore(ctx);
            AuxModelStore.AuxModel vm = aux.getVisionModel();
            if (vm != null && !vm.apiKey.isEmpty() && !vm.baseUrl.isEmpty()) {
                LlmClient vc = new LlmClient(vm.baseUrl, vm.apiKey, vm.model);
                LlmClient.Result vr = vc.chatVision(q, bmp, vm.model);
                if (vr.ok) return vr.text;
                log("辅助视觉模型失败，降级用主力模型: "
                        + truncate(vr.text, 60));
            }
        } catch (Exception e) {
            log("辅助视觉模型异常，降级用主力模型");
        }
        // 2) 主力模型兜底
        try {
            LlmStore ls = new LlmStore(ctx);
            if (!ls.hasKey()) {
                log("虚拟屏视觉：未配置模型");
                return "";
            }
            String vBase = ls.getBaseUrl();
            String vKey = ls.getApiKeyRaw();
            String vModel = ls.getVisionModel();
            if (vModel.isEmpty()) vModel = ls.getModel();
            LlmClient vc = new LlmClient(vBase, vKey, vModel);
            LlmClient.Result vr = vc.chatVision(q, bmp, vModel);
            return vr.ok ? vr.text : "";
        } catch (Exception e) {
            return "";
        }
    }

    public void setRiskPolicy(ActionRisk.Policy policy) {
        if (policy != null) this.riskPolicy = policy;
    }

    /** 上一步动作的输出（shell/读文件/抓网页用，agent 回传给大脑） */
    public String getLastOutput() {
        return lastOutput;
    }

    public void stop() {
        stopped = true;
    }

    private void log(String s) {
        if (listener != null) listener.onLog(s);
    }

    /** root 命令：走常驻 RootShell 桥（复用 su 会话，不再每次 spawn） */
    /** 执行 root 命令，返回输出；失败返回 __ERR__+原因 */
    private String rootCmdOut(String cmd) {
        try {
            String[] r = RootShell.get().exec(cmd, 30);
            if (!"0".equals(r[0])) return "__ERR__exit=" + r[0] + " " + r[1].trim();
            return r[1].trim();
        } catch (Exception e) {
            return "__ERR__" + e.getMessage();
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }

    /** v3.59 步骤4：空串转 null（SkillCardStore.repair 用 null 表示"该字段不变"）。 */
    private static String nullIfEmpty(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s;
    }

    // ===== v3.57 本地编程能力：root 文件通道 =====

    /** 路径是否在应用自家域内（私有目录 /data/data/<包名>/ 下 + 外部专属目录）。纯判定在 FileEditKit.isInAppDomain，可测。 */
    private boolean inAppDomain(String path) {
        try {
            String canon = new java.io.File(path).getCanonicalPath();
            String dataDir = new java.io.File(ctx.getApplicationInfo().dataDir).getCanonicalPath();
            String ec = null;
            java.io.File ext = ctx.getExternalFilesDir(null);
            if (ext != null) ec = ext.getCanonicalPath();
            return FileEditKit.isInAppDomain(canon, dataDir, ec);
        } catch (Exception e) {
            return false;
        }
    }

    /** shell 单引号转义（委托 FileEditKit，行为不变）。 */
    static String shQuote(String s) {
        return FileEditKit.shQuote(s);
    }

    /** 纯函数：找 text 中 old 的第 occ 次出现位置；找不到返回 -1（委托 FileEditKit）。 */
    static int findNth(String text, String old, int occ) {
        return FileEditKit.findNth(text, old, occ);
    }

    static String md5Hex(byte[] data) {
        return FileEditKit.md5Hex(data);
    }

    /**
     * root 读文件（域外路径专用）。注意禁用裸 cat：
     * RootShell 用行首 marker 判命令结束，文件无尾换行时 cat 输出与 marker 粘连必超时；
     * 一律走 base64 输出、JVM 侧解码。失败返回 null。
     */
    private String rootReadFile(String path) {
        if (!hasRoot()) {
            log("域外读文件需要 root，当前无 root：" + path);
            return null;
        }
        String out = rootCmdOut("base64 '" + shQuote(path) + "' 2>/dev/null; echo");
        if (out == null || out.startsWith("__ERR__")) return null;
        try {
            byte[] raw = android.util.Base64.decode(out, android.util.Base64.DEFAULT);
            return new String(raw, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * root 写文件（域外路径专用）：原文件存在时先备份为 path + ".bak"（备份成功才继续）；
     * 内容经 base64 单行原位截断写入，写完用 md5 校验；任一步失败返回 false。
     * 内容过大（>200KB）拒绝，避免 shell 行溢出。
     */
    private boolean rootWriteFile(String path, String content, boolean backup) {
        if (!hasRoot()) {
            log("域外写文件需要 root，当前无 root：" + path);
            return false;
        }
        String q = "'" + shQuote(path) + "'";
        try {
            byte[] bytes = content.getBytes("UTF-8");
            String wantMd5 = md5Hex(bytes);
            if (wantMd5.isEmpty()) {
                log("md5 计算失败，中止写入");
                return false;
            }
            String ex = rootCmdOut("test -e " + q + " && echo yes || echo no");
            if ("yes".equals(ex) && backup) {
                String bk = rootCmdOut("cp " + q + " " + q + ".bak 2>/dev/null && md5sum " + q + ".bak");
                if (bk == null || bk.startsWith("__ERR__")) {
                    log("备份失败，中止写入：" + path + "（" + bk + "）");
                    return false;
                }
                log("已备份：" + path + ".bak");
            }
            String b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
            if (b64.length() > 200 * 1024) {
                log("内容过大（" + bytes.length + " 字节），拒绝经 root 通道写入");
                return false;
            }
            String w = rootCmdOut("printf '%s' '" + b64 + "' | base64 -d > " + q);
            if (w.startsWith("__ERR__")) {
                log("root 写入失败：" + w);
                return false;
            }
            String got = rootCmdOut("md5sum " + q);
            if (!got.startsWith(wantMd5)) {
                log("写后校验失败（md5 不一致），备份在 " + path + ".bak");
                return false;
            }
            log("root 写文件成功：" + path + "（" + bytes.length + " 字节，md5 校验通过）");
            return true;
        } catch (Exception e) {
            log("root 写文件异常：" + e.getMessage());
            return false;
        }
    }

    // v2.90：root 优先策略——有 root 用命令，无 root 用无障碍
    private Boolean cachedRoot = null;

    private boolean hasRoot() {
        if (cachedRoot == null) {
            cachedRoot = Root.findSu() != null;
        }
        return cachedRoot;
    }

    private boolean rootCmd(String cmd) {
        try {
            String[] r = RootShell.get().exec(cmd, 30);
            String out = r[1].trim();
            if (!out.isEmpty()) {
                log("root 输出: " + (out.length() > 200 ? out.substring(0, 200) + "..." : out));
            }
            return "0".equals(r[0]);
        } catch (Exception e) {
            log("root 执行异常: " + e.getMessage());
            return false;
        }
    }

    // ===== v2.60 虚拟屏 helpers =====
    /** VdInput dex 路径 */
    private String vdDexPath(android.content.Context c) {
        return new java.io.File(c.getFilesDir(), "vdinput.dex").getAbsolutePath();
    }

    /** 纯 ASCII 文本（适合 input text） */
    private boolean isAsciiText(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c > 126) return false;
        }
        return true;
    }

    /** 主屏 root 输入中文：剪贴板 + Ctrl+V 粘贴（VdInput 在 display 0 注入） */
    private boolean rootPasteMainScreen(String text) {
        if (!ensureVdDex(ctx)) return false;
        try {
            String b64 = android.util.Base64.encodeToString(
                    text.getBytes("UTF-8"), android.util.Base64.NO_WRAP);
            String out = rootCmdOut("CLASSPATH=" + vdDexPath(ctx)
                    + " app_process /system/bin VdInput input 0 " + b64 + " 2>&1");
            log("root 输入(粘贴): " + out.trim());
            return out.contains("paste=true");
        } catch (Exception e) {
            log("root 输入异常: " + e.getMessage());
            return false;
        }
    }

    /** 仅释放 vdinput.dex（不要求虚拟屏已创建，主屏也用得上） */
    private boolean ensureVdDex(android.content.Context c) {
        java.io.File dex = new java.io.File(c.getFilesDir(), "vdinput.dex");
        int curVer = c.getSharedPreferences("agent",
                android.content.Context.MODE_PRIVATE).getInt("vd_dex_ver", 0);
        if (dex.exists() && curVer == VD_DEX_VER) return true;
        try {
            java.io.InputStream in = c.getAssets().open("vdinput.dex");
            java.io.FileOutputStream out = new java.io.FileOutputStream(dex);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.close();
            c.getSharedPreferences("agent",
                    android.content.Context.MODE_PRIVATE).edit()
                    .putInt("vd_dex_ver", VD_DEX_VER).apply();
            log("VdInput dex 已释放");
        } catch (Exception e) {
            log("dex 释放失败: " + e.getMessage());
            return false;
        }
        return true;
    }

    /** VdInput dex 版本：改 tools/VdInput.java 后 +1，触发重新释放 */
    private static final int VD_DEX_VER = 2;

    /** 确保 VdInput dex 已从 assets 拷贝 */
    private boolean ensureVdInput(android.content.Context c) {
        if (!VirtualDisplayManager.isActive()) {
            log("虚拟屏未创建，先 vd_create");
            return false;
        }
        return ensureVdDex(c);
    }

    /** 取 App 的启动 Activity 类名 */
    private String getLaunchActivity(android.content.Context c, String pkg) {
        try {
            android.content.Intent it = c.getPackageManager()
                    .getLaunchIntentForPackage(pkg);
            if (it != null && it.getComponent() != null) {
                return it.getComponent().getClassName();
            }
        } catch (Exception ignored) {}
        return "";
    }

    public static String loadAsset(Context ctx, String name) {
        try {
            InputStream is = ctx.getAssets().open("tasks/" + name);
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 供 HTTP API 调用的单步执行 */
    public boolean execOneStep(String stepJson) {
        try {
            JSONObject st = new JSONObject(stepJson);
            log("API 单步: " + st.optString("do"));
            return runStep(st, null, 0);
        } catch (Exception e) {
            log("单步异常: " + e.getMessage());
            return false;
        }
    }

    public boolean run(String taskJson) {
        // v3.62：地基①补登记——steps 模式此前零 FloatWindow 调用，任务运行时无彩条；
        // 与 AgentLoop v3.51 同模式：run 开头 begin、finally end（引用计数支持嵌套登记）。
        String name = "steps任务";
        try { name = new JSONObject(taskJson).optString("name", name); } catch (Exception ignored) {}
        FloatWindow.beginTask(ctx, "⚙️ " + name);
        try {
            JSONObject task = new JSONObject(taskJson);
            JSONObject params = task.optJSONObject("params");
            return runTask(task, params, 0);
        } catch (Exception e) {
            log("任务异常: " + e.getMessage());
            return false;
        } finally {
            FloatWindow.endTask("");
        }
    }

    /** 递归执行任务；depth 防子技能无限嵌套 */
    private boolean runTask(JSONObject task, JSONObject params, int depth)
            throws Exception {
        if (depth > 5) {
            log("技能嵌套过深，已停止");
            return false;
        }
        JSONArray steps = task.getJSONArray("steps");
        log("开始任务：" + task.optString("name", "未命名")
                + (depth > 0 ? "（子技能 depth=" + depth + "）" : ""));
        for (int i = 0; i < steps.length(); i++) {
            if (stopped) {
                log("已停止");
                return false;
            }
            JSONObject st = substitute(steps.getJSONObject(i), params);
            String do_ = st.getString("do");
            log("步骤 " + (i + 1) + "/" + steps.length() + ": " + do_);
            boolean ok = runStep(st, params, depth);
            if (!ok && !st.optBoolean("optional", false)) {
                log("任务中断于步骤 " + (i + 1));
                return false;
            }
            Thread.sleep((long) (st.optDouble("after", 0.8) * 1000));
        }
        log("任务完成 ✓");
        return true;
    }

    /**
     * 变量替换：步骤中所有字符串里的 {{key}} 用 params 的值替换。
     * 纯逻辑，可独立测试。
     */
    static JSONObject substitute(JSONObject st, JSONObject params) throws Exception {
        if (params == null) return st;
        JSONObject out = new JSONObject();
        java.util.Iterator<String> keys = st.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            out.put(k, substituteValue(st.get(k), params));
        }
        return out;
    }

    private static Object substituteValue(Object v, JSONObject params) throws Exception {
        if (v instanceof String) {
            String s = (String) v;
            java.util.Iterator<String> keys = params.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                Object pv = params.opt(k);
                if (pv != null) {
                    s = s.replace("{{" + k + "}}", String.valueOf(pv));
                }
            }
            return s;
        } else if (v instanceof JSONObject) {
            JSONObject jo = (JSONObject) v;
            JSONObject out = new JSONObject();
            java.util.Iterator<String> keys = jo.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                out.put(k, substituteValue(jo.get(k), params));
            }
            return out;
        } else if (v instanceof JSONArray) {
            JSONArray ja = (JSONArray) v;
            JSONArray out = new JSONArray();
            for (int i = 0; i < ja.length(); i++) {
                out.put(substituteValue(ja.get(i), params));
            }
            return out;
        }
        return v;
    }

    private AgentAccessibilityService.NodeInfo waitText(String kw, int timeoutSec)
            throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < end) {
            if (stopped) return null;
            List<AgentAccessibilityService.NodeInfo> items =
                    AgentAccessibilityService.dumpScreen();
            AgentAccessibilityService.NodeInfo hit =
                    AgentAccessibilityService.find(items, kw);
            if (hit != null) return hit;
            Thread.sleep(1000);
        }
        return null;
    }

    /**
     * 风险门：按 ActionRisk 编译期分级查用户策略。
     * ALLOW 放行；DENY 拒绝；ASK 需要审批人确认——无审批人时远程任务拦截、
     * 本地受信任务记警告放行。返回 true=允许执行。
     */
    private boolean riskGate(String do_, JSONObject st) {
        String key = do_;
        // v3.57：文件动作按路径选风险键——域外路径的 read_file/write_file
        // 自动按 read_file_root/write_file_root 定级（防用普通读动作读别家私有数据）。
        if (("read_file".equals(do_) || "write_file".equals(do_)) && st != null) {
            try {
                if (st.has("path") && !inAppDomain(st.getString("path"))) key = do_ + "_root";
            } catch (Exception ignored) { }
        }
        ActionRisk.Level lv = ActionRisk.levelFor(key);
        int pol = riskPolicy.get(lv);
        if (pol == ActionRisk.ALLOW) return true;
        if (pol == ActionRisk.DENY) {
            log("⛔ 动作被策略拒绝：" + key + "（" + lv.label + "）");
            return false;
        }
        // ASK
        String detail = truncate(st.toString(), 160);
        if (approver != null) {
            boolean ok = approver.approve(key + "（" + lv.label + "）", detail);
            if (!ok) log("⛔ 已拒绝高风险动作：" + key);
            return ok;
        }
        if (remote) {
            // v3.10 Issue 4：远程策略可配置
            if ("allow_log".equals(remoteRiskPolicy)) {
                log("⚠️ 远程高危动作按策略放行（已记录）：" + do_);
                return true;
            }
            log("⛔ 远程任务无审批人，拦截" + lv.label + "动作：" + do_);
            return false;
        }
        log("⚠️ " + lv.label + "动作无审批人（定时任务），按本地信任放行：" + do_);
        return true;
    }

    private boolean runStep(JSONObject st, JSONObject params, int depth) throws Exception {
        String do_ = st.getString("do");
        // v3.14：虚拟屏模式——主屏动作透明改写为 vd_*（LLM 无需感知）
        if (vdMode && VirtualDisplayManager.isActive()) {
            if ("wait_text".equals(do_)) {
                return vdWaitText(st.optString("text", ""),
                        st.optInt("timeout", 15));
            }
            if ("assert_text".equals(do_)) {
                return vdAssertText(st.optString("text", ""));
            }
            st = rewriteForVd(st);
            if (st == null) return false;
            do_ = st.getString("do");
        }
        // ---- 风险门：编译期分级 + 用户三档策略（策略在代码里，不在 prompt 里） ----
        if (!riskGate(do_, st)) return false;
        switch (do_) {
            case "launch": {
                String pkg = st.getString("package");
                log("启动 " + pkg);
                PackageManager pm = ctx.getPackageManager();
                Intent it = pm.getLaunchIntentForPackage(pkg);
                if (it == null) {
                    log("找不到启动项: " + pkg);
                    return false;
                }
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(it);
                return true;
            }
            case "tap": {
                int x = st.getInt("x"), y = st.getInt("y");
                log("点击 (" + x + "," + y + ")");
                // v2.90：有 root 则命令优先（更稳），无 root 走无障碍
                if (hasRoot()) {
                    if (rootCmd("input tap " + x + " " + y)) return true;
                    log("root 点击失败，回退无障碍");
                }
                return AgentAccessibilityService.tapAt(x, y);
            }
            case "tap_text": {
                String kw = st.getString("text");
                int to = st.optInt("timeout", 10);
                log("等待并点击: " + kw);
                AgentAccessibilityService.NodeInfo hit = waitText(kw, to);
                if (hit == null) {
                    log("超时未找到: " + kw
                            + "；提示：文本不在控件树时别猜坐标乱点，"
                            + "用 vision 按截图问出坐标再 tap");
                    return false;
                }
                // v2.90：有 root 则直接命令点坐标（最稳）
                if (hasRoot()) {
                    if (rootCmd("input tap " + hit.cx + " " + hit.cy)) return true;
                    log("root 点击失败，回退无障碍");
                }
                if (AgentAccessibilityService.clickNode(hit)) return true;
                log("节点点击失败，改点坐标 (" + hit.cx + "," + hit.cy + ")");
                return AgentAccessibilityService.tapAt(hit.cx, hit.cy);
            }
            case "wait_text": {
                String kw = st.getString("text");
                int to = st.optInt("timeout", 15);
                log("等待文本: " + kw);
                AgentAccessibilityService.NodeInfo hit = waitText(kw, to);
                if (hit == null) {
                    log("超时: " + kw);
                    return false;
                }
                return true;
            }
            case "assert_text": {
                String kw = st.getString("text");
                List<AgentAccessibilityService.NodeInfo> items =
                        AgentAccessibilityService.dumpScreen();
                boolean ok = AgentAccessibilityService.find(items, kw) != null;
                log("断言 " + kw + ": " + (ok ? "通过" : "失败"));
                return ok;
            }
            case "input": {
                String s = st.getString("text");
                log("输入: " + s);
                // v3.28：通道优先级 root > 无障碍。root 分两路：
                // 纯 ASCII 走 input text；含中文走剪贴板粘贴（主屏 display 0）
                if (hasRoot()) {
                    boolean ok;
                    if (isAsciiText(s)) {
                        ok = rootCmd("input text " + s.replace(" ", "%s")
                                .replace("'", "").replace("\"", ""));
                    } else {
                        ok = rootPasteMainScreen(s);
                    }
                    if (ok) return true;
                    log("root 输入失败，回退无障碍");
                }
                return AgentAccessibilityService.setText(s);
            }
            case "swipe": {
                JSONArray f = st.getJSONArray("from"), t = st.getJSONArray("to");
                int ms = st.optInt("ms", 400);
                log("滑动");
                // v2.90：有 root 则命令优先
                if (hasRoot()) {
                    if (rootCmd("input swipe " + f.getInt(0) + " " + f.getInt(1)
                            + " " + t.getInt(0) + " " + t.getInt(1) + " " + ms)) return true;
                    log("root 滑动失败，回退无障碍");
                }
                return AgentAccessibilityService.swipe(
                        f.getInt(0), f.getInt(1), t.getInt(0), t.getInt(1), ms);
            }
            case "sleep": {
                Thread.sleep(st.optInt("s", 2) * 1000L);
                return true;
            }
            case "back": {
                // v2.90：有 root 则命令优先
                if (hasRoot()) {
                    if (rootCmd("input keyevent 4")) return true;
                }
                return AgentAccessibilityService.goBack();
            }
            case "home": {
                // 回到桌面（KEYCODE_HOME=3）
                if (hasRoot()) return rootCmd("input keyevent 3");
                try {
                    android.content.Intent i = new android.content.Intent(
                            android.content.Intent.ACTION_MAIN);
                    i.addCategory(android.content.Intent.CATEGORY_HOME);
                    i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                    return true;
                } catch (Exception e) {
                    return false;
                }
            }
            case "shell": {
                String cmd = st.getString("cmd");
                log("执行: " + cmd);
                String out = rootCmdOut(cmd);
                lastOutput = truncate(out, 1500);
                if (!lastOutput.isEmpty()) log("输出: " + lastOutput);
                return !out.startsWith("__ERR__");
            }
            case "termux": {
                // 内置 Termux 环境执行命令（优先于 UI 操作）
                String cmd = st.getString("cmd");
                try {
                    BootstrapManager bm = new BootstrapManager(ctx);
                    // 免 root：直接用 staging 目录（官方 Termux 不需要 root）
                    if (!bm.isInstalled()) {
                        log("Termux 环境未安装，先在终端页下载安装");
                        return false;
                    }
                    // v3.57：bootstrap 已装但 python/git 运行时未就绪 → 后台懒装，
                    // 本次返回明确指引（ok=false，但附恢复路径，不算死失败）
                    if (!bm.isRuntimeReady()) {
                        bm.ensureRuntime(new BootstrapManager.ProgressListener() {
                            public void onProgress(int percent) {}
                            public void onDone(boolean ok, String msg) {
                                log("Termux runtime 安装：" + msg);
                            }
                        });
                        lastOutput = "python3/git 正在后台安装（约几十 MB），"
                                + "本次先用 bash/awk，或稍后重试";
                        log(lastOutput);
                        return false;
                    }
                    String prefix = bm.stagingDir().getAbsolutePath();
                    String bash = prefix + "/bin/bash";
                    java.io.File bashFile = new java.io.File(bash);
                    if (!bashFile.exists()) {
                        log("找不到 Termux bash: " + bash);
                        return false;
                    }
                    // 确保可执行
                    if (!bashFile.canExecute()) {
                        bashFile.setExecutable(true);
                    }
                    log("termux 执行: " + cmd);
                    ProcessBuilder pb = new ProcessBuilder(bash, "-c", cmd);
                    // 用干净环境：只保留 Termux 需要的变量，避免 App 环境干扰
                    pb.environment().clear();
                    pb.environment().putAll(bm.termuxEnv());
                    pb.redirectErrorStream(true);
                    Process pr = pb.start();
                    // 最多等 60 秒
                    boolean done = pr.waitFor(60,
                            java.util.concurrent.TimeUnit.SECONDS);
                    if (!done) {
                        pr.destroyForcibly();
                        log("termux 执行超时（60s）");
                        return false;
                    }
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(
                                    pr.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (sb.length() > 6000) break;
                        sb.append(line).append("\n");
                    }
                    br.close();
                    int code = pr.exitValue();
                    lastOutput = truncate(sb.toString().trim(), 1500);
                    if (!lastOutput.isEmpty()) log("输出: " + lastOutput);
                    String saveAs = st.optString("save_as", "");
                    if (!saveAs.isEmpty()) params.put(saveAs, lastOutput);
                    return code == 0;
                } catch (Exception e) {
                    log("termux 执行失败: " + e.getMessage());
                    return false;
                }
            }
            case "search": {
                // 学习梯子第 2 级：每任务联网搜索上限 2 轮，无果不硬耗（SPEC 一）
                if (ladder.searchBudgetLeft() <= 0) {
                    lastOutput = "本任务已用完 2 轮搜索预算（学习梯子第 2 级上限）。"
                            + "不要再调 search；下一步走梯子第 3 级：用 ask_user 把问题"
                            + "（带候选选项）问用户，问完按用户选择做。";
                    log(lastOutput);
                    return false;
                }
                // 联网搜索（DuckDuckGo，无需 key），结果可存变量供后续步骤用
                String query = st.getString("query");
                int count = st.optInt("count", 5);
                if (count < 1) count = 1;
                if (count > 10) count = 10;
                try {
                    String url = "https://html.duckduckgo.com/html/?q="
                            + java.net.URLEncoder.encode(query, "UTF-8");
                    java.net.HttpURLConnection c =
                            (java.net.HttpURLConnection)
                                    new java.net.URL(url).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(20000);
                    c.setRequestProperty("User-Agent",
                            "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36");
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(
                                    c.getInputStream(), "UTF-8"));
                    StringBuilder html = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (html.length() > 200000) break;
                        html.append(line).append("\n");
                    }
                    br.close();
                    String h = html.toString();
                    // 解析结果：标题 + 链接 + 摘要
                    java.util.List<String> titles = new java.util.ArrayList<>();
                    java.util.List<String> links = new java.util.ArrayList<>();
                    java.util.List<String> snips = new java.util.ArrayList<>();
                    java.util.regex.Matcher m =
                            java.util.regex.Pattern.compile(
                                    "class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>")
                                    .matcher(h);
                    while (m.find() && titles.size() < count) {
                        String href = m.group(1);
                        String title = m.group(2)
                                .replaceAll("<[^>]+>", "").trim();
                        // DuckDuckGo 跳转链接里藏着真实 url
                        String real = href;
                        int uIdx = href.indexOf("udg=");
                        if (uIdx >= 0) {
                            try {
                                real = java.net.URLDecoder.decode(
                                        href.substring(uIdx + 4).split("&")[0],
                                        "UTF-8");
                            } catch (Exception ignored) {
                            }
                        }
                        titles.add(title);
                        links.add(real);
                    }
                    java.util.regex.Matcher sm =
                            java.util.regex.Pattern.compile(
                                    "class=\"result__snippet\"[^>]*>(.*?)</a>")
                                    .matcher(h);
                    while (sm.find() && snips.size() < count) {
                        snips.add(sm.group(1)
                                .replaceAll("<[^>]+>", "").trim());
                    }
                    StringBuilder sb = new StringBuilder();
                    sb.append("搜索「").append(query).append("」共 ")
                            .append(titles.size()).append(" 条结果：\n");
                    for (int i = 0; i < titles.size(); i++) {
                        sb.append("\n").append(i + 1).append(". ")
                                .append(titles.get(i)).append("\n");
                        sb.append("   ").append(links.get(i)).append("\n");
                        if (i < snips.size() && !snips.get(i).isEmpty())
                            sb.append("   ").append(snips.get(i)).append("\n");
                    }
                    lastOutput = truncate(sb.toString(), 2000);
                    log(lastOutput);
                    String saveAs = st.optString("save_as", "");
                    if (!saveAs.isEmpty()) params.put(saveAs, lastOutput);
                    boolean found = !titles.isEmpty();
                    ladder.recordSearch(found);
                    return found;
                } catch (Exception e) {
                    ladder.recordSearch(false);
                    log("搜索失败: " + e.getMessage());
                    return false;
                }
            }
            case "ask_user": {
                // 学习梯子第 3 级：两轮搜完无果，问用户一句（选项式）
                String question = st.optString("question", "").trim();
                if (question.isEmpty()) {
                    lastOutput = "ask_user 缺少 question 参数：请说清要问用户什么，"
                            + "并用 options 给出候选选项（JSON 数组字符串）。";
                    log(lastOutput);
                    return false;
                }
                java.util.List<String> optList = new java.util.ArrayList<String>();
                String optsRaw = st.optString("options", "").trim();
                if (!optsRaw.isEmpty()) {
                    try {
                        org.json.JSONArray ja = new org.json.JSONArray(optsRaw);
                        for (int i = 0; i < ja.length(); i++)
                            optList.add(ja.optString(i, ""));
                    } catch (Exception e) {
                        // 非 JSON 时按逗号/换行切分兜底
                        for (String p : optsRaw.split("[,\n]")) optList.add(p);
                    }
                }
                String hint = st.optString("hint", "").trim();
                String[] opts = LearningLadder.capOptions(
                        optList.toArray(new String[0]));
                String payload = LearningLadder.formatAsk(question, opts)
                        + (hint.isEmpty() ? "" : "\n补充说明：" + hint);
                ladder.markAsked();
                // 学习梯子步骤2：通道解析——实例字段优先（TgBot /task 显式设置），
                // 否则回退 AgentLoop 维护的 PhoneTools.sessionChannel
                String ch = channel;
                if (ch == null || ch.isEmpty()) {
                    try {
                        ch = PhoneTools.sessionChannel;
                    } catch (Exception ignored) {
                    }
                }
                boolean useOverlay = ("app".equals(ch)
                        || ch == null || ch.isEmpty()) && !remote;
                if (useOverlay) {
                    // App 通道：全局悬浮窗选项式提问（120 秒超时）
                    try {
                        String[] buttons = opts.length == 0
                                ? new String[]{"知道了"} : opts;
                        int r = ApproveOverlay.askCustom(ctx,
                                "需要你确认一下", payload,
                                buttons, buttons.length - 1);
                        if (r >= 0 && r < buttons.length) {
                            lastOutput = "用户选择了：「" + buttons[r]
                                    + "」。请按此选择继续。";
                            log("用户回答: " + buttons[r]);
                        } else if (r == -1) {
                            lastOutput = "用户 120 秒内未回应（按默认处理）。"
                                    + "请在结论中说明情况并收尾，不要重复提问。";
                            log("询问超时，用户未回应");
                        } else {
                            lastOutput = "无法弹出询问（无悬浮窗权限）。"
                                    + "请把下面的选项式问题写进最终结论，"
                                    + "用户回复后另起任务继续：\n" + payload;
                            log("悬浮窗不可用，转结论提问");
                        }
                        return r >= 0;
                    } catch (Exception e) {
                        lastOutput = "询问失败（" + e.getMessage() + "）。"
                                + "请把选项式问题写进最终结论：\n" + payload;
                        log(lastOutput);
                        return false;
                    }
                }
                if ("tg".equals(ch)) {
                    // 学习梯子步骤2：TG 同步提问——任务线程挂起，
                    // 等主人下一条回复（120s 超时），轮询线程继续收消息不死锁
                    try {
                        TgBot bot = TgBot.current();
                        if (bot != null && bot.isRunning()) {
                            String ans = bot.askUserSync(
                                    bot.getOwnerChatId(), payload,
                                    optList, 120000L);
                            if (ans != null && !ans.isEmpty()) {
                                lastOutput = "用户选择了：「" + ans
                                        + "」。请按此选择继续。";
                                log("用户回答: " + ans);
                                return true;
                            }
                            if (ans == null) {
                                lastOutput = "用户 120 秒内未回应（按默认处理）。"
                                        + "请在结论中说明情况并收尾，不要重复提问。";
                                log("TG 提问超时，用户未回应");
                            } else {
                                lastOutput = "用户取消了提问（发送了新指令）。"
                                        + "请停止追问并收尾。";
                                log("TG 提问被新指令打断");
                            }
                            return false;
                        }
                        log("TG Bot 未运行，转结论提问");
                    } catch (Exception e) {
                        log("TG 提问异常（" + e.getMessage() + "），转结论提问");
                    }
                }
                // 远控通道：HTTP 异步 taskId 模式无聊天回路，任务内无同步提问能力，
                // 诚实降级——模型把选项式问题写进最终结论，用户回复即新任务。
                lastOutput = "当前通道（" + ch + "）不支持任务内同步提问。"
                        + "请把下面的选项式问题原样写进最终结论，"
                        + "用户回复后另起任务继续：\n" + payload;
                log(ch + " 通道转结论提问");
                return false;
            }
            case "vision": {
                // 视觉问答：截屏发给视觉模型（看不到的按钮/判断前后摄像头就用它）
                String question = st.optString("question",
                        "描述这张截图，指出关键可点击元素的位置");
                try {
                    // v3.14：虚拟屏模式下截虚拟屏
                    // 两级截屏：无障碍 → root 兜底（部分机型无障碍截屏直接失败）
                    android.graphics.Bitmap shot = (vdMode
                            && VirtualDisplayManager.isActive())
                            ? VirtualDisplayManager.screenshot()
                            : ScreenshotUtil.capture(ctx);
                    if (shot == null) {
                        log("截屏失败（无障碍与 root 均不可用），无法调用视觉模型");
                        return false;
                    }
                    // v3.13 Issue 11：先看有没有可用模型，没有则本地降级
                    AuxModelStore aux = new AuxModelStore(ctx);
                    AuxModelStore.AuxModel vm = aux.getVisionModel();
                    boolean hasAux = vm != null && !vm.apiKey.isEmpty()
                            && !vm.baseUrl.isEmpty();
                    LlmStore ls0 = new LlmStore(ctx);
                    boolean hasMain = ls0.hasKey();
                    if (!hasAux && !hasMain) {
                        // 本地降级：从无障碍节点树找文字坐标（零依赖）
                        String local = visionLocalFallback(question);
                        if (local != null) {
                            lastOutput = local;
                            log("👁 本地降级命中: " + local);
                            String saveAs0 = st.optString("save_as", "");
                            if (!saveAs0.isEmpty()) params.put(saveAs0, lastOutput);
                            return true;
                        }
                        log("未配置大模型 key，本地降级也未找到匹配文字");
                        return false;
                    }
                    // v3.17：走 visionAsk（辅助失败自动降级主力）
                    // v3.38：坐标统一口径——要求视觉模型以 0-1000 归一化回答，
                    // 与 tap 的归一化入参对齐，避免缩放图像素与设备像素混用
                    log("👁 视觉提问: " + question);
                    String vans = visionAsk(question
                            + "（如需给出坐标，请用 0-1000 归一化坐标：左上角 (0,0)，"
                            + "右下角 (1000,1000)，不要用像素坐标）", shot);
                    boolean vok = !vans.isEmpty();
                    lastOutput = vok ? vans : "视觉调用失败（辅助与主力均不可用）";
                    log("👁 视觉回答: " + lastOutput);
                    String saveAs = st.optString("save_as", "");
                    if (!saveAs.isEmpty()) params.put(saveAs, lastOutput);
                    return vok;
                } catch (Exception e) {
                    log("视觉调用异常: " + e.getMessage());
                    return false;
                }
            }
            case "tap_id": {
                // 按 /screen 返回的节点 id 点击（legend 机制）
                int id = st.getInt("id");
                List<AgentAccessibilityService.NodeInfo> items =
                        AgentAccessibilityService.dumpScreen();
                if (id < 0 || id >= items.size()) {
                    log("节点 id 越界: " + id + "（共 " + items.size() + " 个）");
                    return false;
                }
                AgentAccessibilityService.NodeInfo ni = items.get(id);
                log("点击节点 #" + id + ": " + ni.text);
                if (AgentAccessibilityService.clickNode(ni)) return true;
                log("节点点击失败，改点坐标 (" + ni.cx + "," + ni.cy + ")");
                if (AgentAccessibilityService.tapAt(ni.cx, ni.cy)) return true;
                return rootCmd("input tap " + ni.cx + " " + ni.cy);
            }
            case "read_file": {
                String rp = st.getString("path");
                try {
                    java.io.File rf = new java.io.File(rp);
                    byte[] data = java.nio.file.Files.readAllBytes(rf.toPath());
                    String txt = new String(data, "UTF-8");
                    lastOutput = truncate(txt, 2000);
                    log("读文件 " + rp + "（" + data.length + " 字节）");
                    if (!lastOutput.isEmpty()) log("内容: " + lastOutput);
                    return true;
                } catch (Exception e) {
                    lastOutput = "";
                    log("读文件失败: " + e.getMessage());
                    return false;
                }
            }
            case "write_file": {
                String wp = st.getString("path");
                String content = st.optString("content", "");
                try {
                    java.io.File wf = new java.io.File(wp);
                    if (wf.getParentFile() != null) wf.getParentFile().mkdirs();
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(wf);
                    fos.write(content.getBytes("UTF-8"));
                    fos.close();
                    lastOutput = "";
                    log("写文件成功: " + wp);
                    return true;
                } catch (Exception e) {
                    lastOutput = "";
                    log("写文件失败: " + e.getMessage());
                    return false;
                }
            }
            case "read_file_root": {
                // v3.57：域外 root 读（riskGate 已按路径升级定级）
                String rp2 = st.getString("path");
                String txt2 = rootReadFile(rp2);
                if (txt2 == null) {
                    lastOutput = "";
                    log("root 读文件失败: " + rp2);
                    return false;
                }
                lastOutput = truncate(txt2, 2000);
                log("root 读文件 " + rp2);
                if (!lastOutput.isEmpty()) log("内容: " + lastOutput);
                return true;
            }
            case "write_file_root": {
                // v3.57：域外 root 写（备份 + md5 校验）
                String wp2 = st.getString("path");
                String content2 = st.optString("content", "");
                boolean ok2 = rootWriteFile(wp2, content2, true);
                lastOutput = ok2 ? ("write_file_root ok: " + wp2) : "";
                return ok2;
            }
            case "edit_file": {
                // v3.57：片段编辑。域内走本地读写；域外自动 root 提权原位写（先备份）。
                String ep = st.getString("path");
                String old = st.getString("old");
                String newS = st.getString("new");
                int occ = st.optInt("occurrence", 1);
                // 设计 3.2：无意义调用提前拒绝（省一次读写）
                if (old == null || old.isEmpty()) {
                    log("edit_file 拒绝：old 为空");
                    lastOutput = "";
                    return false;
                }
                if (old.equals(newS)) {
                    log("edit_file 拒绝：old 与 new 相同（无意义调用）");
                    lastOutput = "";
                    return false;
                }
                boolean domain = inAppDomain(ep);
                String text = null;
                if (domain) {
                    try {
                        text = new String(java.nio.file.Files.readAllBytes(
                                new java.io.File(ep).toPath()), "UTF-8");
                    } catch (Exception e) {
                        log("读文件失败: " + e.getMessage());
                    }
                } else {
                    text = rootReadFile(ep);
                    if (text == null) log("root 读文件失败: " + ep);
                }
                if (text == null) {
                    lastOutput = "";
                    return false;
                }
                String updated = FileEditKit.applyEdit(text, old, newS, occ);
                if (updated == null) {
                    // 找不到：给出候选行提示（old 首非空行命中的行），纯逻辑见 FileEditKit
                    String hint = FileEditKit.editHints(text, old, 10);
                    log("片段未找到（第 " + occ + " 次出现）" +
                            (!hint.isEmpty() ? "，疑似候选行：\n" + hint : "，无候选行"));
                    lastOutput = hint;
                    return false;
                }
                boolean ok;
                if (domain) {
                    try {
                        java.io.File ef = new java.io.File(ep);
                        if (ef.getParentFile() != null) ef.getParentFile().mkdirs();
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(ef);
                        fos.write(updated.getBytes("UTF-8"));
                        fos.close();
                        log("片段编辑成功（域内）: " + ep + "（第 " + occ + " 次出现）");
                        ok = true;
                    } catch (Exception e) {
                        log("片段编辑写回失败: " + e.getMessage());
                        ok = false;
                    }
                } else {
                    ok = rootWriteFile(ep, updated, true);
                    if (ok) log("片段编辑成功（域外 root，备份已留）: " + ep);
                }
                lastOutput = ok ? ("edit_file ok: " + ep) : "";
                return ok;
            }
            case "fetch_url": {
                String url = st.getString("url");
                try {
                    java.net.HttpURLConnection c =
                            (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(20000);
                    c.setRequestProperty("User-Agent", "gsic/1.0");
                    int code = c.getResponseCode();
                    java.io.InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(in, "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (sb.length() > 8000) break;
                        sb.append(line).append('\n');
                    }
                    br.close();
                    // 去 HTML 标签，省 token
                    String txt = sb.toString().replaceAll("<script[^>]*>.*?</script>", " ")
                            .replaceAll("<style[^>]*>.*?</style>", " ")
                            .replaceAll("<[^>]+>", " ")
                            .replaceAll("\\s+", " ").trim();
                    lastOutput = truncate(txt, 2000);
                    log("抓取 " + url + " HTTP " + code);
                    if (!lastOutput.isEmpty()) log("正文: " + lastOutput);
                    return code < 400;
                } catch (Exception e) {
                    lastOutput = "";
                    log("抓取失败: " + e.getMessage());
                    return false;
                }
            }
            // (vd_test 已被 v2.60 正式版替代，移除)
            // ===== v2.60：虚拟屏后台自动化 =====
            case "vd_create": {
                int w = st.optInt("w", 720);
                int h = st.optInt("h", 1280);
                int dpi = st.optInt("dpi", 320);
                int did = VirtualDisplayManager.create(ctx, w, h, dpi);
                if (did < 0) {
                    log("虚拟屏创建失败");
                    return false;
                }
                log("虚拟屏已创建 displayId=" + did);
                lastOutput = String.valueOf(did);
                return true;
            }
            case "vd_destroy": {
                VirtualDisplayManager.destroy();
                log("虚拟屏已销毁");
                return true;
            }
            case "vd_launch": {
                // root 启动 App 到虚拟屏
                if (!VirtualDisplayManager.isActive()) {
                    log("虚拟屏未创建，先 vd_create");
                    return false;
                }
                String pkg = st.optString("package", "");
                if (pkg.isEmpty()) {
                    log("缺少 package 参数");
                    return false;
                }
                int did = VirtualDisplayManager.getDisplayId();
                String out = rootCmdOut("am start --display " + did
                        + " -n " + pkg + "/"
                        + getLaunchActivity(ctx, pkg) + " 2>&1");
                log("启动到虚拟屏: " + out.trim());
                return !out.contains("Error");
            }
            case "vd_tap": {
                if (!ensureVdInput(ctx)) return false;
                int did = VirtualDisplayManager.getDisplayId();
                float x = (float) st.optDouble("x", 0);
                float y = (float) st.optDouble("y", 0);
                String out = rootCmdOut("CLASSPATH=" + vdDexPath(ctx)
                        + " app_process /system/bin VdInput tap "
                        + did + " " + x + " " + y + " 2>&1");
                log("虚拟屏点击: " + out.trim());
                return out.contains("down=true");
            }
            case "vd_swipe": {
                if (!ensureVdInput(ctx)) return false;
                int did = VirtualDisplayManager.getDisplayId();
                float x1 = (float) st.optDouble("x1", 0);
                float y1 = (float) st.optDouble("y1", 0);
                float x2 = (float) st.optDouble("x2", 0);
                float y2 = (float) st.optDouble("y2", 0);
                int dur = st.optInt("duration", 500);
                String out = rootCmdOut("CLASSPATH=" + vdDexPath(ctx)
                        + " app_process /system/bin VdInput swipe "
                        + did + " " + x1 + " " + y1 + " "
                        + x2 + " " + y2 + " " + dur + " 2>&1");
                log("虚拟屏滑动: " + out.trim());
                return out.contains("ok=true");
            }
            case "vd_key": {
                if (!ensureVdInput(ctx)) return false;
                int did = VirtualDisplayManager.getDisplayId();
                int kc = st.optInt("keycode", 4);
                String out = rootCmdOut("CLASSPATH=" + vdDexPath(ctx)
                        + " app_process /system/bin VdInput key "
                        + did + " " + kc + " 2>&1");
                log("虚拟屏按键: " + out.trim());
                return out.contains("down=true");
            }
            case "vd_input": {
                // v3.14：虚拟屏文本输入（剪贴板 + Ctrl+V，支持中文；先聚焦输入框）
                if (!ensureVdInput(ctx)) return false;
                int did = VirtualDisplayManager.getDisplayId();
                String text = st.optString("text", "");
                if (text.isEmpty()) {
                    log("vd_input 缺少 text 参数");
                    return false;
                }
                String b64;
                try {
                    b64 = android.util.Base64.encodeToString(
                            text.getBytes("UTF-8"),
                            android.util.Base64.NO_WRAP);
                } catch (Exception e) {
                    log("vd_input 编码失败");
                    return false;
                }
                String out = rootCmdOut("CLASSPATH=" + vdDexPath(ctx)
                        + " app_process /system/bin VdInput input "
                        + did + " " + b64 + " 2>&1");
                log("虚拟屏输入: " + out.trim());
                return out.contains("paste=true");
            }
            case "vd_screenshot": {
                String path = st.optString("path", "");
                android.graphics.Bitmap bmp = VirtualDisplayManager.screenshot();
                if (bmp == null) {
                    log("虚拟屏截屏失败");
                    return false;
                }
                try {
                    java.io.File f = path.isEmpty()
                            ? new java.io.File(ctx.getCacheDir(),
                                    "vd_" + System.currentTimeMillis() + ".jpg")
                            : new java.io.File(path);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG,
                            80, fos);
                    fos.close();
                    bmp.recycle();
                    log("虚拟屏截屏已保存: " + f.getAbsolutePath());
                    lastOutput = f.getAbsolutePath();
                    return true;
                } catch (Exception e) {
                    log("保存失败: " + e.getMessage());
                    return false;
                }
            }
            case "screenshot": {
                // 任务内截屏存档（无障碍优先，无需 root）
                String path = st.optString("path", "");
                android.graphics.Bitmap bmp = AgentAccessibilityService.screenshot();
                if (bmp == null) {
                    log("截屏失败");
                    return false;
                }
                try {
                    java.io.File f = path.isEmpty()
                            ? new java.io.File(ctx.getCacheDir(),
                                    "task_" + System.currentTimeMillis() + ".png")
                            : new java.io.File(path);
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
                    fos.close();
                    bmp.recycle();
                    log("截屏已保存: " + f.getAbsolutePath());
                    return true;
                } catch (Exception e) {
                    log("截屏保存失败: " + e.getMessage());
                    return false;
                }
            }
            // ============ v2.78：硬件/软件/系统底层远控 ============
            case "screen": {
                // 屏幕亮/灭：{"do":"screen","action":"on|off"}
                String a = st.optString("action", "on");
                String r = "on".equals(a)
                        ? rootCmdOut("input keyevent 224")
                        : rootCmdOut("input keyevent 26");
                log("屏幕" + a + ": " + r);
                return true;
            }
            case "brightness": {
                // 亮度 0-255：{"do":"brightness","value":128}
                int v = st.optInt("value", 128);
                v = Math.max(0, Math.min(255, v));
                String r = rootCmdOut(
                        "settings put system screen_brightness " + v);
                log("亮度=" + v + ": " + r);
                return true;
            }
            case "volume": {
                // 音量：{"do":"volume","stream":"media","value":8}
                String stream = st.optString("stream", "media");
                Integer vol = st.has("value") ? st.optInt("value") : null;
                String r = HardwareControl.volume(ctx, stream, vol);
                log("音量 " + stream + ": " + r);
                return true;
            }
            case "vibrate": {
                // 震动：{"do":"vibrate","duration":200}
                long ms = st.optLong("duration", 200);
                String r = HardwareControl.vibrate(ctx, ms);
                log("震动 " + ms + "ms: " + r);
                return true;
            }
            case "torch": {
                // 手电筒：{"do":"torch","on":true}
                boolean on = st.optBoolean("on", true);
                String r = HardwareControl.torch(ctx, on);
                log("手电筒 " + (on ? "开" : "关") + ": " + r);
                return true;
            }
            case "wifi": {
                // WiFi：{"do":"wifi","on":true}
                boolean on = st.optBoolean("on", true);
                String r = rootCmdOut(
                        "svc wifi " + (on ? "enable" : "disable"));
                log("WiFi " + (on ? "开" : "关") + ": " + r);
                return true;
            }
            case "bluetooth": {
                // 蓝牙：{"do":"bluetooth","on":true}
                boolean on = st.optBoolean("on", true);
                String r = rootCmdOut(
                        "svc bluetooth " + (on ? "enable" : "disable"));
                log("蓝牙 " + (on ? "开" : "关") + ": " + r);
                return true;
            }
            case "airplane": {
                // 飞行模式：{"do":"airplane","on":true}
                boolean on = st.optBoolean("on", true);
                rootCmdOut("settings put global airplane_mode_on "
                        + (on ? "1" : "0"));
                String r = rootCmdOut(
                        "am broadcast -a android.intent.action.AIRPLANE_MODE "
                        + "--ez state " + on);
                log("飞行模式 " + (on ? "开" : "关") + ": " + r);
                return true;
            }
            case "app_install": {
                // 安装 APK：{"do":"app_install","path":"/sdcard/x.apk"}
                String p = st.optString("path", "");
                if (p.isEmpty()) {
                    log("app_install 缺少 path");
                    return false;
                }
                String r = rootCmdOut("pm install -r \"" + p + "\"");
                log("安装: " + r);
                return r.contains("Success");
            }
            case "app_uninstall": {
                // 卸载：{"do":"app_uninstall","package":"com.x"}
                String pkg = st.optString("package", "");
                if (pkg.isEmpty()) {
                    log("app_uninstall 缺少 package");
                    return false;
                }
                String r = rootCmdOut("pm uninstall " + pkg);
                log("卸载 " + pkg + ": " + r);
                return r.contains("Success");
            }
            case "app_kill": {
                // 强行停止：{"do":"app_kill","package":"com.x"}
                String pkg = st.optString("package", "");
                if (pkg.isEmpty()) {
                    log("app_kill 缺少 package");
                    return false;
                }
                String r = rootCmdOut("am force-stop " + pkg);
                log("强行停止 " + pkg + ": " + r);
                return true;
            }
            case "app_clear": {
                // 清除数据：{"do":"app_clear","package":"com.x"}
                String pkg = st.optString("package", "");
                if (pkg.isEmpty()) {
                    log("app_clear 缺少 package");
                    return false;
                }
                String r = rootCmdOut("pm clear " + pkg);
                log("清除数据 " + pkg + ": " + r);
                return r.contains("Success");
            }
            case "app_list": {
                // 应用列表：{"do":"app_list"}
                String r = rootCmdOut("pm list packages -f");
                log("应用列表:\n" + r);
                return true;
            }
            case "reboot": {
                // 重启/关机：{"do":"reboot","mode":""} mode: recovery/bootloader/shutdown
                String mode = st.optString("mode", "");
                String cmd = "shutdown".equals(mode) ? "reboot -p"
                        : "recovery".equals(mode) ? "reboot recovery"
                        : "bootloader".equals(mode) ? "reboot bootloader"
                        : "reboot";
                log("执行 " + cmd + "（手机将重启）");
                rootCmdOut(cmd);
                return true;
            }
            case "sys_get": {
                // 读系统设置：{"do":"sys_get","namespace":"system","key":"screen_brightness"}
                String ns = st.optString("namespace", "system");
                String key = st.optString("key", "");
                if (key.isEmpty()) {
                    log("sys_get 缺少 key");
                    return false;
                }
                String r = rootCmdOut("settings get " + ns + " " + key);
                log(ns + "/" + key + " = " + r.trim());
                return true;
            }
            case "sys_put": {
                // 写系统设置：{"do":"sys_put","namespace":"system","key":"k","value":"v"}
                String ns = st.optString("namespace", "system");
                String key = st.optString("key", "");
                String val = st.optString("value", "");
                if (key.isEmpty()) {
                    log("sys_put 缺少 key");
                    return false;
                }
                String r = rootCmdOut(
                        "settings put " + ns + " " + key + " \"" + val + "\"");
                log("设置 " + ns + "/" + key + "=" + val + ": " + r);
                return true;
            }
            case "getprop": {
                // 系统属性：{"do":"getprop","key":"ro.build.version.release"}
                String key = st.optString("key", "");
                String r = rootCmdOut("getprop " + key);
                log("getprop " + key + ":\n" + r.trim());
                return true;
            }
            case "ps": {
                // 进程列表：{"do":"ps","filter":"com.x"}
                String f = st.optString("filter", "");
                String r = rootCmdOut("ps -A"
                        + (f.isEmpty() ? "" : " | grep " + f));
                log("进程:\n" + r);
                return true;
            }
            case "kill": {
                // 杀进程：{"do":"kill","pid":1234}
                int pid = st.optInt("pid", 0);
                if (pid <= 0) {
                    log("kill 缺少 pid");
                    return false;
                }
                String r = rootCmdOut("kill -9 " + pid);
                log("kill " + pid + ": " + r);
                return true;
            }
            case "remember": {
                // v2.85：写长期记忆 {"do":"remember","key":"喜欢的歌手","value":"周杰伦"}
                String k = st.optString("key", "");
                String v = st.optString("value", "");
                if (k.isEmpty()) {
                    log("remember 缺少 key");
                    return false;
                }
                UserMemory.put(ctx, k, v);
                log("已记住：" + k + "＝" + v);
                return true;
            }
            case "use_card": {
                // v3.58 步骤3：调卡 + 生命周期状态机落账 + 失败时界面地图联动。
                // 计数由 recordUse 一次完成（旧的 incrementUse 已移除，避免双计数）。
                String cardId = st.getString("id");
                SkillCardStore cardStore = new SkillCardStore(ctx);
                SkillCardStore.Card card = cardStore.get(cardId);
                if (card == null) {
                    log("技能卡不存在: " + cardId);
                    return false;
                }
                if (card.isDraft()) {
                    log("📇 调卡提示：该卡为草稿卡（待复验），"
                            + "本次调卡成功一次后自动转正");
                }
                log("📇 使用技能卡：" + card.name
                        + "（" + card.typeLabel() + "·" + card.sourceLabel()
                        + "·" + card.statusLabel() + "）");
                String[] failNoteOut = new String[1]; // 失败时由 execUseCard 填入修卡原因
                boolean cardDone = execUseCard(st, params, depth, card, failNoteOut);
                if (!stopped) {
                    // 用户主动中止不落账：避免「被叫停」误记为「卡坏了」
                    String outcome = cardStore.recordUse(card.id, cardDone, failNoteOut[0]);
                    if ("promoted".equals(outcome)) {
                        log("📇 草稿卡复验成功，已转正");
                    } else if ("needs_repair".equals(outcome)) {
                        log("📇 调卡失败，已打回草稿待修卡："
                                + (failNoteOut[0] == null ? "" : failNoteOut[0]));
                    }
                    if (!cardDone && card.appPkg != null && !card.appPkg.isEmpty()
                            && cogStore != null) {
                        // 卡的步骤失效时先对地图：把关联 App 的界面地图/图标词典
                        // 注入 lastOutput，供修卡方先读地图、再调 repair 修卡
                        String ib = cogStore.iconBlock(card.appPkg);
                        if (!ib.isEmpty()) {
                            lastOutput = lastOutput + "\n[界面地图·供修卡参考]"
                                    + "卡的步骤失效，先对地图再改卡：\n" + ib;
                            log("📇 已注入关联 App（" + card.appPkg
                                    + "）的界面地图/图标词典供修卡参考");
                        }
                    }
                }
                return cardDone;
            }
            case "repair_card": {
                // v3.59 步骤4：就地修卡。只传 id=读卡（返回当前内容+状态+失败记录，不改卡）；
                // 传 content+note=修卡（旧版本快照→打回草稿→复验转正）。
                String rcId = st.getString("id");
                SkillCardStore rcStore = new SkillCardStore(ctx);
                SkillCardStore.Card rc = rcStore.get(rcId);
                if (rc == null) {
                    log("修卡失败：技能卡不存在: " + rcId);
                    return false;
                }
                // v3.63：rollback 接线——传 rollback=<版本> 回滚到历史快照
                // （SkillCardStore.rollback：回滚前先快照当前，version 单调++，
                // 打回草稿待复验）。与 content 互斥。
                String rbVer = nullIfEmpty(st.optString("rollback"));
                if (rbVer != null) {
                    int rbTarget;
                    try {
                        rbTarget = Integer.parseInt(rbVer.trim());
                    } catch (NumberFormatException e) {
                        log("回滚失败：rollback 须为整数版本号: " + rbVer);
                        lastOutput = "回滚失败：rollback 须为整数版本号。";
                        return false;
                    }
                    boolean rbOk = rcStore.rollback(rcId, rbTarget);
                    if (rbOk) {
                        log("📇 已回滚技能卡到快照 v" + rbTarget
                                + "（回滚前当前版本已自动快照）："
                                + rc.name + " → 打回草稿，复验成功一次后自动转正");
                        lastOutput = "回滚成功：已回到快照 v" + rbTarget
                                + "。之后再调卡成功一次自动转正。";
                    } else {
                        log("回滚失败：版本号不存在或卡不存在（id=" + rcId
                                + ", rollback=" + rbTarget + "）");
                        lastOutput = "回滚失败：版本号 " + rbTarget
                                + " 不存在。可用 repair_card 只传 id 读出版本快照列表。";
                    }
                    return rbOk;
                }
                String newContent = nullIfEmpty(st.optString("content"));
                if (newContent == null) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("卡片[").append(rc.id).append("] ").append(rc.name)
                            .append("（").append(rc.typeLabel()).append("·")
                            .append(rc.sourceLabel()).append("·").append(rc.statusLabel())
                            .append("·v").append(rc.version).append("）\n")
                            .append("触发：").append(rc.trigger == null ? "" : rc.trigger).append("\n")
                            .append("内容：\n").append(rc.content == null ? "" : rc.content);
                    if (rc.repairNote != null && !rc.repairNote.isEmpty())
                        sb.append("\n失败记录：").append(rc.repairNote);
                    java.util.List<SkillCardStore.VersionSnap> rbHist =
                            rcStore.versions(rcId);
                    if (!rbHist.isEmpty()) {
                        sb.append("\n版本快照（repair_card 传 rollback=<版本> 回滚）：");
                        java.text.SimpleDateFormat rbFmt =
                                new java.text.SimpleDateFormat("MM-dd HH:mm",
                                        java.util.Locale.getDefault());
                        for (SkillCardStore.VersionSnap sn : rbHist) {
                            sb.append("\nv").append(sn.version).append(" · ")
                              .append(rbFmt.format(new java.util.Date(sn.savedAt)))
                              .append(" · ")
                              .append(sn.note == null ? "" : sn.note);
                        }
                    }
                    lastOutput = sb.toString();
                    log("📇 已读出技能卡内容（未改动）: " + rc.name);
                    return true;
                }
                String rcNote = st.optString("note");
                boolean ok = rcStore.repair(rcId,
                        nullIfEmpty(st.optString("name")),
                        nullIfEmpty(st.optString("trigger")),
                        newContent,
                        nullIfEmpty(st.optString("summary")),
                        rcNote == null ? "" : rcNote);
                if (ok) {
                    log("📇 已修卡（旧版本已快照，可回滚）：" + rc.name
                            + " → 打回草稿，复验成功一次后自动转正"
                            + ((rcNote == null || rcNote.isEmpty()) ? "" : "；病因：" + rcNote));
                    lastOutput = "修卡成功：已打回草稿（v" + (rc.version + 1)
                            + "）。之后再调卡成功一次自动转正。";
                } else {
                    log("修卡失败：repair 返回 false: " + rcId);
                }
                return ok;
            }
            case "skill": {
                // 子技能调用：按 name 找已启用的技能，带 params 执行
                String name = st.getString("name");
                JSONObject subParams = st.optJSONObject("params");
                // 子技能 params 继承父级未指定的键
                if (params != null) {
                    JSONObject merged = new JSONObject();
                    java.util.Iterator<String> keys = params.keys();
                    while (keys.hasNext()) {
                        String k = keys.next();
                        merged.put(k, params.get(k));
                    }
                    if (subParams != null) {
                        java.util.Iterator<String> sk = subParams.keys();
                        while (sk.hasNext()) {
                            String k = sk.next();
                            merged.put(k, subParams.get(k));
                        }
                    }
                    subParams = merged;
                }
                log("调用子技能: " + name);
                SkillStore.Skill skill = new SkillStore(ctx).findByName(name);
                if (skill == null || !skill.enabled) {
                    log("子技能不存在或未启用: " + name);
                    return false;
                }
                try {
                    JSONObject subTask = new JSONObject(skill.json);
                    return runTask(subTask, subParams, depth + 1);
                } catch (Exception e) {
                    log("子技能解析失败: " + e.getMessage());
                    return false;
                }
            }
            default:
                log("未知步骤: " + do_);
                return false;
        }
    }

    /**
     * v3.58 步骤3：use_card 的实际执行体（从 runStep 的 case 中抽出，
     * 使「执行」与「状态机落账」分离——落账统一在 case 收尾处做一次）。
     * 失败时把修卡原因填入 failNoteOut[0]；返回 true=调卡成功。
     */
    private boolean execUseCard(JSONObject st, JSONObject params, int depth,
                                SkillCardStore.Card card, String[] failNoteOut)
            throws Exception {
        // 调用技能卡：script 型跑 shell，skill 型跑步骤
        if (SkillCardStore.TYPE_SCRIPT.equals(card.type)) {
            String out = rootCmdOut(card.content);
            lastOutput = truncate(out, 1500);
            if (!lastOutput.isEmpty()) log("输出: " + lastOutput);
            boolean ok = !out.startsWith("__ERR__");
            if (!ok) failNoteOut[0] = buildCardFailNote("script", 0, null, lastOutput);
            return ok;
        }
        // v2.52：技能卡铁律——无论成功失败，结束必回桌面
        boolean cardOk = true;
        // 兼容两种格式：{"steps":[...]} 或裸数组 [...]
        JSONArray csteps = null;
        String ct = card.content == null ? "" : card.content.trim();
        boolean proseCard = !(ct.startsWith("[") || ct.startsWith("{"));
        if (proseCard) {
            // 说明型卡：content 是自然语言操作说明（非步骤 JSON），
            // 不是执行失败——把全文作为指令回给 agent 按说明逐步执行
            lastOutput = "说明型技能卡《" + card.name
                    + "》：本卡是操作说明，未自动执行任何动作。"
                    + "请严格按下述说明、用你自己的工具逐步完成，"
                    + "结束条件以说明为准；说明与本次目标冲突处以用户本次目标为准。"
                    + "[注意：以下是一段文字说明，不是手机屏幕上的内容；"
                    + "不要用 tap_text 去点击说明里的文字，按说明打开对应 App 后用 vision 定位再 tap]\n"
                    + card.content;
            log("📇 说明型技能卡，已把说明全文回给执行者：" + card.name);
            return true;
        }
        try {
            if (ct.startsWith("[")) {
                csteps = new JSONArray(ct);
            } else {
                JSONObject cj = new JSONObject(ct);
                csteps = cj.optJSONArray("steps");
            }
        } catch (Exception e) {
            lastOutput = "技能卡解析异常：" + e.getMessage();
            log("技能卡解析异常: " + e.getMessage());
            failNoteOut[0] = buildCardFailNote("parse", 0, e.getMessage(), lastOutput);
            return false;
        }
        if (csteps == null || csteps.length() == 0) {
            lastOutput = "技能卡步骤为空（JSON 里没有 steps）";
            log("技能卡步骤为空");
            failNoteOut[0] = buildCardFailNote("empty", 0, null, lastOutput);
            return false;
        }
        try {
            for (int ci = 0; ci < csteps.length(); ci++) {
                if (stopped) return false;
                JSONObject cs = TaskRunner.substitute(csteps.getJSONObject(ci), params);
                if (!runStep(cs, params, depth + 1)) {
                    // v2.53：可选步骤失败只跳过，不中断整卡
                    if (cs.optBoolean("optional", false)) {
                        log("可选步骤跳过 (" + (ci + 1) + ")");
                        continue;
                    }
                    log("技能卡步骤失败于 " + (ci + 1));
                    lastOutput = "技能卡《" + card.name + "》执行失败于第 "
                            + (ci + 1) + " 步：" + cs.toString();
                    cardOk = false;
                    failNoteOut[0] = buildCardFailNote("step", ci + 1,
                            cs.optString("do", "?") + " " + truncate(cs.toString(), 200),
                            lastOutput);
                    break;
                }
            }
        } finally {
            // v3.18：虚拟屏下不回桌面（没碰过主屏）
            if (!cardOk && !vdMode) {
                try {
                    rootCmd("input keyevent 3");
                    log("🏠 技能卡异常结束，已回桌面");
                } catch (Exception ignored) {
                }
            }
        }
        if (cardOk) lastOutput = "技能卡《" + card.name + "》步骤全部执行完成";
        return cardOk;
    }

    /**
     * v3.58 步骤3（纯函数，JVM 可单测）：调卡失败的修卡原因落账文案。
     * kind: parse / empty / script / step；stepNo 仅 step 型用（1-based）；
     * stepSum 为失败步骤摘要，output 为执行输出/错误文本（供病因分类）。
     */
    static String buildCardFailNote(String kind, int stepNo, String stepSum, String output) {
        String c = Telemetry.classifyFail("use_card", false, output);
        if (c == null || c.isEmpty()) c = "unknown";
        if ("parse".equals(kind)) {
            return "技能卡解析异常：" + (stepSum == null ? "" : stepSum) + "；病因：" + c;
        }
        if ("empty".equals(kind)) {
            return "技能卡步骤为空（JSON 里没有 steps）；病因：" + c;
        }
        if ("script".equals(kind)) {
            String o = output == null ? "" : output;
            if (o.length() > 120) o = o.substring(0, 120) + "…";
            return "脚本执行失败：" + o + "；病因：" + c;
        }
        String s = stepSum == null ? "" : stepSum;
        if (s.length() > 160) s = s.substring(0, 160) + "…";
        return "第" + stepNo + "步失败：" + s + "；病因：" + c;
    }
    /**
     * v3.13 Issue 11：本地视觉降级（零依赖）。
     * 从问题中提取候选文字，在无障碍节点树里找匹配节点的中心坐标。
     * 命中返回 "(x, y)" 字符串，未命中返回 null。
     * 覆盖最常用的"X 按钮/文字在哪"场景；图标识别仍需视觉模型。
     */
    private String visionLocalFallback(String question) {
        try {
            if (question == null || question.isEmpty()) return null;
            // 候选词：引号内文字优先，其次按非中英文数字切分取长度>=2 的词
            java.util.List<String> cands = new java.util.ArrayList<>();
            java.util.regex.Matcher qm = java.util.regex.Pattern
                    .compile("[\"\"']([^\"\"']{2,20})[\"\"']").matcher(question);
            while (qm.find()) cands.add(qm.group(1));
            for (String part : question.split("[^\\p{IsHan}a-zA-Z0-9]+")) {
                if (part.length() >= 2 && !cands.contains(part)) cands.add(part);
            }
            if (cands.isEmpty()) return null;
            java.util.List<AgentAccessibilityService.NodeInfo> nodes =
                    AgentAccessibilityService.dumpScreen();
            for (String cand : cands) {
                String cl = cand.toLowerCase();
                for (AgentAccessibilityService.NodeInfo ni : nodes) {
                    String t = ni.text == null ? "" : ni.text.toLowerCase();
                    String d = ni.desc == null ? "" : ni.desc.toLowerCase();
                    if ((!t.isEmpty() && (t.contains(cl) || cl.contains(t)))
                            || (!d.isEmpty() && (d.contains(cl) || cl.contains(d)))) {
                        return "(" + ni.cx + ", " + ni.cy + ")";
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
