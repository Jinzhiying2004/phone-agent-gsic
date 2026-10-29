package com.gsic.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP API 路由 + token 鉴权。
 * token 首次启动时随机生成，存在 SharedPreferences，App 界面可见。
 */
public class ApiHandler implements AgentHttpServer.Handler {

    private final Context ctx;
    private final TokenManager tokenManager;

    public ApiHandler(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        // v3.11 Issue 1：Token 走 TokenManager（轮换/过期/设备绑定）
        this.tokenManager = new TokenManager(this.ctx);
    }

    public String getToken() {
        return tokenManager.getToken();
    }

    /** v3.11：吊销 token */
    public void revokeToken() {
        tokenManager.revoke();
    }

    /** v3.11：轮换 token */
    public String rotateToken() {
        return tokenManager.rotate();
    }

    // ---------- 远控目标式任务注册表（/task 异步 + /task/status 轮询 + App 内卡片共用） ----------
    private static final java.util.LinkedHashMap<String, RemoteTask> remoteTasks =
            new java.util.LinkedHashMap<>();

    public static synchronized void putRemoteTask(RemoteTask rt) {
        remoteTasks.put(rt.taskId, rt);
        while (remoteTasks.size() > 5) {
            String eldest = remoteTasks.keySet().iterator().next();
            remoteTasks.remove(eldest);
        }
    }

    public static synchronized RemoteTask getRemoteTask(String taskId) {
        return taskId == null ? null : remoteTasks.get(taskId);
    }

    /** 最近一个远控任务（App 内卡片 / 不带 taskId 的 status 查询用） */
    public static synchronized RemoteTask getLatestRemoteTask() {
        RemoteTask latest = null;
        for (RemoteTask rt : remoteTasks.values()) latest = rt;
        return latest;
    }

    // ---------- 远控任务悬浮窗：启动显示进度，结束留 5 秒后隐藏 ----------
    private static void floatShow(Context appCtx, String text) {
        // v3.39：走任务级登记（在 App 前台发起也不丢窗）
        FloatWindow.beginTask(appCtx, text);
    }

    private static void floatEnd(String text) {
        FloatWindow.endTask(text);
    }

    /** v3.45：远控轮次尾巴——审计工具步数，解析失败按 0 步。 */
    private static int auditSteps(AgentLoop loop) {
        try {
            return new JSONArray(loop.getAuditJson()).length();
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public String handle(String method, String path, Map<String, String> query,
                         Map<String, String> headers, String body,
                         byte[] rawBody) throws Exception {
        String tok = query.get("token");
        if (tok == null) tok = headers.get("x-token");
        // v3.11：token 走 TokenManager（支持轮换/过期）
        if (!tokenManager.getToken().equals(tok)) {
            return new JSONObject().put("ok", false).put("error", "unauthorized").toString();
        }
        switch (path) {
            case "/status": {
                boolean firstCore = !TermsGate.isFirstRemoteTold(ctx);
                if (firstCore) TermsGate.markFirstRemoteTold(ctx);
                JSONObject st = new JSONObject()
                        .put("ok", true)
                        .put("version", 2)
                        .put("accessibility", AgentAccessibilityService.isRunning())
                        .put("service", AgentService.isRunning())
                        .put("terms_accepted", TermsGate.isAccepted(ctx))
                        .put("weekly_sediment_due", TermsGate.weeklyDue(ctx))
                        .put("core_first", firstCore)
                        .put("cf_tunnel_running", CloudflareTunnel.isRunning())
                        .put("cf_tunnel_url", CloudflareTunnel.getUrl())
                        .put("cf_tunnel_proxy", CloudflareTunnel.isProxyMode(ctx));
                if (firstCore) st.put("core", TermsGate.CORE_TEXT);
                return st.toString();
            }
            case "/core":
                return new JSONObject()
                        .put("ok", true)
                        .put("name", "GSIC")
                        .put("core", TermsGate.CORE_TEXT)
                        .put("stage", "parasitic")
                        .put("compute_required", true)
                        .put("weekly_sediment_to", "GitHub Issues")
                        .put("terms_accepted", TermsGate.isAccepted(ctx))
                        .toString();
            case "/sediment/weekly": {
                if (!TermsGate.isAccepted(ctx)) {
                    return new JSONObject().put("ok", false).put("error", "terms_not_accepted").toString();
                }
                boolean due = TermsGate.weeklyDue(ctx);
                boolean remindedOnce = false;
                if (due && !TermsGate.weeklyRemindedForDue(ctx)) {
                    TermsGate.markWeeklyReminded(ctx);
                    remindedOnce = true;
                }
                return new JSONObject()
                        .put("ok", true)
                        .put("due", due)
                        .put("reminded_once", remindedOnce)
                        .put("summary", TermsGate.weeklySummary(ctx))
                        .put("upload_to", "GitHub Issues")
                        .toString();
            }
            case "/sediment/weekly_ack": {
                if (!"POST".equalsIgnoreCase(method)) {
                    return new JSONObject().put("ok", false).put("error", "POST required").toString();
                }
                String issueUrl = null;
                try { issueUrl = new JSONObject(body).optString("issue_url", null); } catch (Exception ignored) {}
                TermsGate.markWeeklyAck(ctx, issueUrl);
                return new JSONObject().put("ok", true).put("issue_url", issueUrl == null ? JSONObject.NULL : issueUrl).toString();
            }
            case "/screen": {
                // v3.53：远控单步操控也是在干活——打活动心跳点亮彩条
                FloatWindow.pulseActivity(ctx);
                List<AgentAccessibilityService.NodeInfo> items =
                        AgentAccessibilityService.dumpScreen();
                JSONArray arr = new JSONArray();
                for (int idx = 0; idx < items.size(); idx++) {
                    AgentAccessibilityService.NodeInfo ni = items.get(idx);
                    arr.put(new JSONObject()
                            .put("id", idx)
                            .put("text", ni.text)
                            .put("desc", ni.desc)
                            .put("clickable", ni.clickable)
                            .put("cx", ni.cx)
                            .put("cy", ni.cy));
                }
                return new JSONObject().put("ok", true).put("nodes", arr).toString();
            }
            case "/action": {
                if (!TermsGate.isAccepted(ctx)) {
                    return new JSONObject().put("ok", false).put("error", "terms_not_accepted").toString();
                }
                if (!"POST".equalsIgnoreCase(method)) {
                    return new JSONObject().put("ok", false).put("error", "POST required").toString();
                }
                // v3.53：单步操控心跳（远控旁聊的主要干活方式，此前永不点亮彩条）
                FloatWindow.pulseActivity(ctx);
                List<String> logs = new ArrayList<>();
                TaskRunner r = new TaskRunner(ctx, logs::add);
                r.setRemote(true);
                boolean ok = r.execOneStep(body);
                return new JSONObject().put("ok", ok).put("log", new JSONArray(logs)).toString();
            }
            case "/task": {
                if (!TermsGate.isAccepted(ctx)) {
                    return new JSONObject().put("ok", false).put("error", "terms_not_accepted").toString();
                }
                if (!"POST".equalsIgnoreCase(method)) {
                    return new JSONObject().put("ok", false).put("error", "POST required").toString();
                }
                // v2.45 goal 模式：{"goal":"...","maxSteps":30} → 手机端自主规划执行
                // （远控智能体不再只能跑固定步骤，目标式任务在手机端拆解/试错/自恢复）
                JSONObject bj = null;
                try {
                    bj = new JSONObject(body);
                } catch (Exception ignored) {
                }
                if (bj != null && bj.has("goal") && !bj.has("steps")) {
                    // v3.11 Issue 14：三通道互斥
                    if (!TaskLock.tryAcquire("remote")) {
                        return new JSONObject().put("ok", false).put("error",
                                "任务忙（" + TaskLock.current()
                                        + " 通道正在执行），请稍后再试").toString();
                    }
                    final String goal = bj.getString("goal");
                    final int maxSteps = bj.optInt("maxSteps", AgentLoop.NO_LIMIT);
                    // v3.30 三端同步：本地路由先行（零token直达，如回桌面/截图），
                    // 与 App、TG 顺序一致，不需要 Key
                    try {
                        LocalIntentRouter.RouteResult rr =
                                LocalIntentRouter.tryRoute(ctx, goal);
                        if (rr != null && rr.handled) {
                            // v3.51 步骤5：本地路由决策点打点（零 token 直达）
                            Telemetry.decisionPoint(ctx, "remote", "local_route", 1.0,
                                    goal, "local_route", 1.0, null);
                            TaskLock.release("remote");
                            return new JSONObject().put("ok", true)
                                    .put("via", "local")
                                    .put("log", new JSONArray()
                                            .put(rr.message)).toString();
                        }
                    } catch (Exception ignored) {
                    }
                    LlmStore ls = new LlmStore(ctx);
                    if (!ls.hasKey()) {
                        TaskLock.release("remote");
                        return new JSONObject().put("ok", false).put("error",
                                "手机端未配置大模型，无法执行目标式任务").toString();
                    }
                    // wait=true：同步等结果（老行为，兼容旧脚本）；
                    // 默认异步：立即返回 taskId，用 GET /task/status 轮询看思考与执行过程
                    if (bj.optBoolean("wait", false)) {
                        List<String> logs = new ArrayList<>();
                        floatShow(ctx.getApplicationContext(), "🎯 远控任务：" + goal);
                        AgentLoop loop = new AgentLoop(ctx, ls.client(),
                                new AgentLoop.Listener() {
                                    @Override
                                    public void onLog(String m) {
                                        logs.add(m);
                                        String line = FloatWindow.briefLine(m);
                                        if (line != null) FloatWindow.pushLine(line);
                                    }

                                    @Override
                                    public boolean isStopped() {
                                        return false;
                                    }
                                });
                        loop.setRemote(true);
                        loop.setChannel("remote");
                        loop.setSupervisorClient(ls.supervisorClient());
                        String sharedCtx = SharedMemory.recentText(ctx, 3);
                        if (!sharedCtx.isEmpty()) {
                            loop.setConversationContext(sharedCtx);
                        }
                        boolean ok;
                        try {
                            ok = loop.run(goal, maxSteps);
                            // v3.50 第 3 步：低置信拦截——同步模式直接返回
                            // needs_clarify（不记经验轮次，任务未实际执行）
                            if (loop.needsClarify()) {
                                floatEnd("❓ 远控任务待澄清");
                                return new JSONObject().put("ok", true)
                                        .put("needs_clarify", true)
                                        .put("question", loop.clarifyQuestion())
                                        .put("options", new JSONArray(
                                                loop.clarifyOptions()))
                                        .put("log", new JSONArray(logs))
                                        .toString();
                            }
                            // v3.45：结果尾巴三端对齐——带审计步数
                            SharedMemory.addRound(ctx, "remote", goal,
                                    (ok ? "任务完成" : "任务未完成")
                                            + "，共" + auditSteps(loop) + "步");
                        } finally {
                            // v3.32：异常也要释放，否则远控永久"任务忙"
                            TaskLock.release("remote");
                        }
                        // v3.56 记忆分层·步骤7：远控会话边界——任务结束清共享轮次。
                        // needs_clarify 路径已在 try 内提前 return，不经过此处，
                        // 澄清往返的跟进 /task 仍能读到上一轮上下文。
                        SharedMemory.clear(ctx);
                        floatEnd(ok ? "✅ 远控任务完成" : "⏹ 远控任务结束");
                        JSONObject resp = new JSONObject().put("ok", ok)
                                .put("log", new JSONArray(logs));
                        try {
                            resp.put("audit", new JSONArray(loop.getAuditJson()));
                        } catch (Exception ignored) {
                        }
                        return resp.toString();
                    }
                    final RemoteTask rt = new RemoteTask(goal, maxSteps);
                    putRemoteTask(rt);
                    final Context appCtx = ctx.getApplicationContext();
                    final LlmClient client = ls.client();
                    floatShow(appCtx, "🎯 远控任务：" + goal);
                    new Thread(() -> {
                        AgentLoop loop = new AgentLoop(appCtx, client,
                                new AgentLoop.Listener() {
                                    @Override
                                    public void onLog(String m) {
                                        rt.log(m);
                                        String line = FloatWindow.briefLine(m);
                                        if (line != null) FloatWindow.pushLine(line);
                                    }

                                    @Override
                                    public boolean isStopped() {
                                        // v3.21：/task/stop 可远程停止
                                        return rt.stopRequested;
                                    }
                                });
                        loop.setRemote(true);
                        loop.setChannel("remote");
                        loop.setSupervisorClient(ls.supervisorClient());
                        String sharedCtx2 = SharedMemory.recentText(appCtx, 3);
                        if (!sharedCtx2.isEmpty()) {
                            loop.setConversationContext(sharedCtx2);
                        }
                        rt.log("🎯 远控任务启动：" + goal);
                        try {
                            boolean ok = loop.run(goal, maxSteps);
                            // v3.50 第 3 步：低置信拦截——记到 RemoteTask，
                            // 云端经 /task/status 取澄清问题；不记经验轮次
                            if (loop.needsClarify()) {
                                rt.needsClarify = true;
                                rt.clarifyQuestion = loop.clarifyQuestion();
                                rt.clarifyOptions.addAll(loop.clarifyOptions());
                                rt.ok = false;
                                rt.log("❓ 任务书置信度低，等待澄清");
                            } else {
                                rt.ok = ok;
                                // v3.45：结果尾巴三端对齐——带审计步数
                                SharedMemory.addRound(appCtx, "remote", goal,
                                        (ok ? "任务完成" : "任务未完成")
                                                + "，共" + auditSteps(loop) + "步");
                                try {
                                    rt.audit = new JSONArray(loop.getAuditJson());
                                } catch (Exception ignored) {
                                }
                            }
                        } catch (Exception e) {
                            rt.error = String.valueOf(e.getMessage());
                        } finally {
                            rt.done = true;
                            TaskLock.release("remote"); // v3.11 Issue 14
                            rt.log(rt.needsClarify ? "❓ 远控任务待澄清"
                                    : (rt.ok ? "✅ 远控任务完成"
                                            : "⏹ 远控任务结束"));
                            floatEnd(rt.needsClarify ? "❓ 远控任务待澄清"
                                    : (rt.ok ? "✅ 远控任务完成"
                                            : "⏹ 远控任务结束"));
                            // v3.56 记忆分层·步骤7：远控任务结束清共享轮次；
                            // 待澄清时不清，等澄清跟进的 /task 接着读上下文。
                            if (!rt.needsClarify) {
                                try {
                                    SharedMemory.clear(appCtx);
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    }, "remote-task-" + rt.taskId).start();
                    return new JSONObject().put("ok", true).put("started", true)
                            .put("taskId", rt.taskId).toString();
                }
                List<String> logs = new ArrayList<>();
                TaskRunner r = new TaskRunner(ctx, logs::add);
                r.setRemote(true);
                boolean ok = r.run(body);
                return new JSONObject().put("ok", ok).put("log", new JSONArray(logs)).toString();
            }
            case "/task/status": {
                // 远控目标式任务的实时进度：思考过程 + 执行过程边跑边看
                // ?taskId=xxx（不带则返回最近一个任务）
                if (!"GET".equalsIgnoreCase(method)) {
                    return new JSONObject().put("ok", false).put("error", "GET required").toString();
                }
                String tid = query.get("taskId");
                RemoteTask rt = (tid == null || tid.isEmpty())
                        ? getLatestRemoteTask() : getRemoteTask(tid);
                if (rt == null) {
                    return new JSONObject().put("ok", false)
                            .put("error", "无远控任务").toString();
                }
                return rt.toJson().toString();
            }
            case "/task/stop": {
                // v3.21：远程停止目标式任务
                // POST {"taskId":"rt-1-xxx"}，不带 taskId 则停最近一个
                if (!"POST".equalsIgnoreCase(method)) {
                    return new JSONObject().put("ok", false)
                            .put("error", "POST required").toString();
                }
                JSONObject bj = null;
                try {
                    bj = new JSONObject(body);
                } catch (Exception ignored) {
                }
                String tid = bj != null ? bj.optString("taskId", "") : "";
                RemoteTask rt = (tid == null || tid.isEmpty())
                        ? getLatestRemoteTask() : getRemoteTask(tid);
                if (rt == null) {
                    return new JSONObject().put("ok", false)
                            .put("error", "无远控任务").toString();
                }
                rt.stopRequested = true;
                rt.log("⏹ 收到远程停止请求");
                return new JSONObject().put("ok", true)
                        .put("stopped", true)
                        .put("taskId", rt.taskId).toString();
            }
            case "/patch": {
                // 热补丁推送：body 为 patch zip 二进制（rawBody，未经字符串转换）
                if (!"POST".equalsIgnoreCase(method)) {
                    return new JSONObject().put("ok", false).put("error", "POST required").toString();
                }
                PatchManager.PatchResult pr = PatchManager.stagePatch(ctx, rawBody);
                JSONObject r = new JSONObject().put("ok", pr.ok);
                if (!pr.ok) {
                    r.put("error", pr.error);
                } else {
                    r.put("tag", pr.tag);
                    // v3.22-hotfix：先回响应，再延迟重启使补丁生效
                    // （双保险：精确闹钟 + 杀进程靠服务拉起，MIUI 可靠）
                    final Context appCtx = ctx.getApplicationContext();
                    new Thread(() -> {
                        try {
                            Thread.sleep(1500);
                        } catch (InterruptedException ignored) {
                        }
                        PatchManager.scheduleRestart(appCtx, 800);
                        try {
                            Thread.sleep(400);
                        } catch (InterruptedException ignored) {
                        }
                        PatchManager.killForRestart();
                    }).start();
                }
                return r.toString();
            }
            case "/screenshot": {
                if (!"GET".equalsIgnoreCase(method)) {
                    return new JSONObject().put("ok", false).put("error", "GET required").toString();
                }
                // v3.53：操控中的截图同样算活动（远控旁聊靠它盯屏）
                FloatWindow.pulseActivity(ctx);
                try {
                    byte[] png = null;
                    String via = null;
                    // v3.15：?vd=1 截虚拟屏（云端驱动虚拟屏任务时用）
                    boolean wantVd = "1".equals(query.get("vd"));
                    if (wantVd && VirtualDisplayManager.isActive()) {
                        android.graphics.Bitmap vdb =
                                VirtualDisplayManager.screenshot();
                        if (vdb != null) {
                            java.io.ByteArrayOutputStream baos =
                                    new java.io.ByteArrayOutputStream();
                            vdb.compress(android.graphics.Bitmap.CompressFormat.PNG,
                                    100, baos);
                            png = baos.toByteArray();
                            vdb.recycle();
                            via = "virtual_display";
                        }
                    }
                    // 1) 无障碍截屏（Android 11+，无需 root）
                    android.graphics.Bitmap bmp = AgentAccessibilityService.screenshot();
                    if (bmp != null) {
                        java.io.ByteArrayOutputStream baos =
                                new java.io.ByteArrayOutputStream();
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,
                                100, baos);
                        png = baos.toByteArray();
                        bmp.recycle();
                        via = "accessibility";
                    }
                    // 2) 兜底：root 下 screencap
                    if (png == null) {
                        java.io.File f = new java.io.File(ctx.getCacheDir(), "shot.png");
                        if (f.exists()) f.delete();
                        Process p = Root.exec("screencap -p " + f.getAbsolutePath());
                        p.waitFor();
                        if (f.exists() && f.length() > 0) {
                            png = java.nio.file.Files.readAllBytes(f.toPath());
                            f.delete();
                            via = "root";
                        }
                    }
                    if (png == null) {
                        return new JSONObject().put("ok", false)
                                .put("error", "screenshot failed").toString();
                    }
                    // compact=1：降分辨率 + JPEG 压缩（给 LLM/远程看屏省 token 流量）
                    boolean compact = "1".equals(query.get("compact"));
                    if (compact) {
                        try {
                            android.graphics.Bitmap shotBmp =
                                    android.graphics.BitmapFactory.decodeByteArray(
                                            png, 0, png.length);
                            byte[] jpg = ScreenshotUtil.toJpegSmall(shotBmp, 720, 70);
                            if (shotBmp != null) shotBmp.recycle();
                            if (jpg != null) {
                                String b64 = android.util.Base64.encodeToString(
                                        jpg, android.util.Base64.NO_WRAP);
                                return new JSONObject().put("ok", true)
                                        .put("via", via).put("compact", true)
                                        .put("jpg", b64).toString();
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    String b64 = android.util.Base64.encodeToString(
                            png, android.util.Base64.NO_WRAP);
                    return new JSONObject().put("ok", true)
                            .put("via", via).put("png", b64).toString();
                } catch (Exception e) {
                    return new JSONObject().put("ok", false)
                            .put("error", String.valueOf(e.getMessage())).toString();
                }
            }
            case "/sediment/list": {
                // v3.33：智能沉淀远控通道——云端 Muse 默认经此拉取报告
                // 顺带触发一次调度检查（内部节流），TG 重度用户不开 App 也能生成
                try {
                    SedimentReport.checkSchedule(ctx);
                } catch (Exception ignored) {
                }
                JSONArray arr = new JSONArray();
                try {
                    for (java.io.File f : SedimentReport.all(ctx)) {
                        arr.put(new JSONObject()
                                .put("name", f.getName())
                                .put("size", f.length())
                                .put("mtime", f.lastModified()));
                    }
                } catch (Exception ignored) {
                }
                return new JSONObject().put("ok", true)
                        .put("reports", arr).toString();
            }
            case "/sediment/latest": {
                java.io.File f = SedimentReport.latest(ctx);
                if (f == null) {
                    return new JSONObject().put("ok", false)
                            .put("error", "暂无沉淀报告").toString();
                }
                String text = SedimentReport.readText(f);
                if (text == null) {
                    return new JSONObject().put("ok", false)
                            .put("error", "读取失败").toString();
                }
                return new JSONObject().put("ok", true)
                        .put("name", f.getName())
                        .put("report", text).toString();
            }
            case "/sediment/get": {
                String name = query.get("name");
                if (name == null || name.contains("/") || name.contains("..")) {
                    return new JSONObject().put("ok", false)
                            .put("error", "name 参数不合法").toString();
                }
                java.io.File f = new java.io.File(SedimentReport.dir(ctx), name);
                if (!f.exists()) {
                    return new JSONObject().put("ok", false)
                            .put("error", "报告不存在").toString();
                }
                String text = SedimentReport.readText(f);
                if (text == null) {
                    return new JSONObject().put("ok", false)
                            .put("error", "读取失败").toString();
                }
                return new JSONObject().put("ok", true)
                        .put("name", name)
                        .put("report", text).toString();
            }
            default:
                return new JSONObject().put("ok", false).put("error", "unknown path").toString();
        }
    }
}
