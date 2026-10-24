package com.gsic.app;

import android.content.Context;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 原生 Telegram Bot（Bot API 长轮询，无第三方依赖）。
 * <p>
 * 手机即 Bot：用户在 Telegram 里给机器人发指令，手机执行后回消息。
 * 不依赖 Tailscale，纯公网通道。安全：只有绑定的主人 chatId 能指挥，
 * 首次 /start 自动绑定，陌生人消息直接忽略。
 * <p>
 * 指令：
 * /start 绑定主人 ｜ /help 帮助 ｜ /status 状态
 * /task {json} 执行任务 ｜ /agent 目标 AI 自动操控 ｜ /shot 截屏
 * 直接发文字 = /agent 目标
 */
public class TgBot {

    public interface Listener {
        void onLog(String s);
    }

    private static final String API = "https://api.telegram.org/bot";
    private static final int MSG_LIMIT = 4000;

    private final Context ctx;
    private final TgStore store;
    private final LlmStore llmStore;
    private final Listener listener;
    private volatile boolean running;
    // v2.98：Telegram 任务级停止标志
    private volatile boolean tgTaskStop = false;
    private volatile boolean tgTaskRunning = false;
    // v2.98：流式推送节流
    private long tgLastPushMs = 0;
    // v3.56 记忆分层·步骤7：TG 会话边界——/new 之外，连续 30 分钟无消息也开新会话
    private long tgLastMsgMs = 0;
    private static final long TG_IDLE_NEW_SESSION_MS = 30L * 60 * 1000;
    // v3.38：TG 最近对话（给 agent 补上下文，帮它理解指代与真实意图）
    private Thread thread;
    private long offset = 0;
    // v3.50 第 3 步：低置信澄清接龙——chatId → 待澄清（原目标+选项+触发消息 id），
    // 用户下一条非指令文本即视为澄清回答
    private static class ClarifyPending {
        final String goal;
        final java.util.List<String> options;
        final long replyTo;
        ClarifyPending(String g, java.util.List<String> o, long r) {
            goal = g;
            options = o != null ? o : new java.util.ArrayList<String>();
            replyTo = r;
        }
    }
    private final java.util.Map<Long, ClarifyPending> pendingClarify =
            new java.util.concurrent.ConcurrentHashMap<>();
    // 学习梯子步骤2：当前运行中的 Bot 实例（TaskRunner 的 ask_user 同步提问用）。
    // 只在 start()/stop() 里赋值，任务线程只读。
    private static volatile TgBot currentInstance = null;

    /** 返回当前运行中的 TgBot，没有运行返回 null。 */
    public static TgBot current() {
        return currentInstance;
    }

    public TgBot(Context ctx, TgStore store, LlmStore llmStore, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.store = store;
        this.llmStore = llmStore;
        this.listener = listener;
    }

    private void log(String s) {
        if (listener != null) listener.onLog(s);
    }

    public synchronized void start() {
        if (running) return;
        if (!store.hasToken()) {
            log("未配置 Bot Token");
            return;
        }
        running = true;
        currentInstance = this;
        thread = new Thread(this::loop, "tgbot");
        thread.start();
        log("Telegram Bot 已启动，等待指令…");
    }

    public synchronized void stop() {
        running = false;
        if (currentInstance == this) currentInstance = null;
        if (thread != null) thread.interrupt();
        log("Telegram Bot 已停止");
    }

    public boolean isRunning() {
        return running;
    }

    /** 长轮询主循环（跑在 bot 线程） */
    private void loop() {
        int failStreak = 0;
        while (running) {
            try {
                JSONObject resp = apiGet("getUpdates?offset=" + offset + "&timeout=30");
                if (resp == null || !resp.optBoolean("ok")) {
                    failStreak++;
                    // v3.03：连续失败才打日志，偶发网络抖动不刷屏
                    if (failStreak >= 3) log("轮询异常（连续" + failStreak + "次）");
                    sleepQuiet(3000);
                    continue;
                }
                failStreak = 0;
                JSONArray result = resp.optJSONArray("result");
                if (result == null) continue;
                for (int i = 0; i < result.length(); i++) {
                    if (!running) break;
                    JSONObject u = result.optJSONObject(i);
                    if (u == null) continue;
                    offset = Math.max(offset, u.optLong("update_id", 0) + 1);
                    handleUpdate(u);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                if (e.getCause() instanceof InterruptedException) break;
                if (!running) break;
                failStreak++;
                if (failStreak >= 3) log("轮询异常：" + e.getMessage() + "（连续" + failStreak + "次）");
                sleepQuiet(5000);
            } catch (Exception e) {
                if (!running) break;
                failStreak++;
                if (failStreak >= 3) log("轮询异常：" + e.getMessage() + "（连续" + failStreak + "次）");
                sleepQuiet(5000);
            }
        }
        running = false;
    }

    private void handleUpdate(JSONObject u) {
        JSONObject msg = u.optJSONObject("message");
        if (msg == null) return;
        long chatId = msg.optJSONObject("chat") != null
                ? msg.optJSONObject("chat").optLong("id", 0) : 0;
        String text = msg.optString("text", "").trim();
        // v3.49：记住触发这条指令的消息 id，执行完的自动回复引用它（reply_parameters），
        // 用户一眼看出是哪条指令的结果
        long incomingId = msg.optLong("message_id", 0);
        if (chatId == 0 || text.isEmpty()) return;

        // 主人绑定
        if (!store.hasOwner()) {
            if (text.equals("/start")) {
                store.bindOwner(chatId);
                log("已绑定主人 chatId=" + chatId);
                sendMessage(chatId, "绑定成功 ✅\n\n"
                        + "直接发文字我就照做（如\"打开微信\")。\n"
                        + "指令：/status 状态 /task {json} 任务 /shot 截屏 /help 帮助");
            }
            return; // 未绑定时其他消息一律忽略
        }
        if (chatId != store.getOwner()) return; // 陌生人忽略

        // v3.56 记忆分层·步骤7：连续 30 分钟无消息视为新会话——
        // 清共享轮次（长期认知库不动），等价于一次自动 /new
        long nowMs = System.currentTimeMillis();
        if (tgLastMsgMs != 0 && nowMs - tgLastMsgMs > TG_IDLE_NEW_SESSION_MS) {
            try {
                new ChatStore(ctx).newSession();
                SharedMemory.clear(ctx);
            } catch (Exception ignored) {
            }
            log("TG 30 分钟无消息，自动开新会话（共享上下文已清空）");
        }
        tgLastMsgMs = nowMs;

        // 学习梯子步骤2：同步提问挂起中——下一条非指令文本即投递为回答；
        // 任何 "/" 指令都打断等待（唤醒任务线程），再走正常指令流程
        if (AskGate.hasPending(chatId)) {
            if (!text.startsWith("/")) {
                AskGate.deliver(chatId, text);
                log("❓ TG 提问回答已投递");
                return;
            }
            AskGate.cancel(chatId);
            log("❓ TG 提问被指令打断");
        }
        // v3.50 第 3 步：澄清接龙——有待澄清时，下一条非指令文本即视为澄清回答；
        // 任何 "/" 指令都先清掉待澄清（/new、/stop 等已有清理语义兜底）
        if (!text.startsWith("/")) {
            ClarifyPending cp = pendingClarify.remove(chatId);
            if (cp != null) {
                String answer = parseClarifyAnswer(text, cp.options);
                log("❓ TG 澄清回答：" + brief(answer));
                runAgentCmd(chatId,
                        cp.goal + "（用户明确：" + answer + "）", cp.replyTo);
                return;
            }
        } else {
            pendingClarify.remove(chatId);
        }

        log("📩 " + brief(text)); if (!TermsGate.isAccepted(ctx) && !text.equals("/help") && !text.equals("/start") && !text.equals("/status")) { sendMessage(chatId, "请先在 App 内同意使用协议，否则无法使用。"); return; }
        try {
            if (text.equals("/help") || text.equals("/start")) {
                sendMessage(chatId, helpText());
            } else if (text.equals("/status")) {
                sendMessage(chatId, statusText());
            } else if (text.equals("/new") || text.startsWith("/new ")) {
                // v3.22：真 /new——新开一个没有上下文的对话（清会话，清 TG 侧待处理状态；长期记忆不动）
                // v3.43：/new 后可直接跟指令（旧版只认整句等于 /new，跟了文字整句漏进任务目标）
                try {
                    new ChatStore(ctx).newSession();
                    SharedMemory.clear(ctx);
                } catch (Exception ignored) {
                }
                String rest = text.length() > 4 ? text.substring(4).trim() : "";
                if (rest.isEmpty()) {
                    sendMessage(chatId, "🆕 新对话开始了，之前的上下文已清空。想让我做什么？");
                } else {
                    sendMessage(chatId, "🆕 新对话开始了，上下文已清空。");
                    runAgentCmd(chatId, rest, incomingId);
                }
            } else if (text.equals("/shot")) {
                sendMessage(chatId, "截屏中…");
                sendScreenshot(chatId);
            } else if (isScreenshotAsk(text)) {
                // v3.22-hotfix：自然语言截图直达（"截图"/"截屏"/"当前界面截图给我"等），不进 AgentLoop，不卡无障碍检查
                sendScreenshot(chatId);
            } else if (text.equals("/stop")) {
                // v2.98：强行打断正在跑的任务
                if (!tgTaskRunning) {
                    sendMessage(chatId, "没有正在执行的任务");
                } else if (tgTaskStop) {
                    sendMessage(chatId, "已在停止中…");
                } else {
                    tgTaskStop = true;
                    // 学习梯子步骤2：/task 也可能在工作线程跑（同步提问挂起中），
                    // 直接停掉它的 TaskRunner
                    try {
                        if (tgTaskRunner != null) tgTaskRunner.stop();
                    } catch (Exception ignored) {
                    }
                    sendMessage(chatId, "🛑 已请求停止，正在打断…");
                }
            } else if (text.startsWith("/task")) {
                runTaskCmd(chatId, text.substring(5).trim(), incomingId);
            } else if (text.startsWith("/agent")) {
                runAgentCmd(chatId, text.substring(6).trim(), incomingId);
            } else {
                runAgentCmd(chatId, text, incomingId); // 直接发文字 = 智能执行
            }
        } catch (Exception e) {
            sendMessage(chatId, "执行异常：" + e.getMessage());
        }
    }

    private String helpText() {
        return "📱 手机 Agent 指令\n\n"
                + "/status —— 服务状态\n"
                + "/new —— 新开对话（清上下文）\n"
                + "/shot —— 当前截屏\n"
                + "/stop —— 强行打断当前任务\n"
                + "/task {\"steps\":[...]} —— 执行任务 JSON\n"
                + "/agent 打开微信 —— AI 看屏自动操控\n"
                + "直接发文字 = /agent\n"
                + "执行完的结果会自动引用你那条指令回复\n\n"
                + "解绑：在 App 里点「解绑主人」";
    }

    private String statusText() {
        boolean svc = AgentService.isRunning();
        boolean acc = AgentAccessibilityService.isRunning();
        return "📊 状态\n"
                + "无障碍：" + (acc ? "运行中 ✅" : "未开启 ❌") + "\n"
                + "远控服务：" + (svc ? "运行中 ✅" : "未启动 ❌") + "\n"
                + "大模型：" + (llmStore.hasKey() ? "已配置 ✅" : "未配置 ❌") + "\n"
                + "Telegram：运行中 ✅";
    }

    private void runTaskCmd(long chatId, String json) {
        runTaskCmd(chatId, json, 0);
    }

    /** /task：执行任务 JSON（v3.49：结果引用触发消息回复） */
    private void runTaskCmd(long chatId, String json, long replyTo) {
        if (json.isEmpty()) {
            sendMessage(chatId, "用法：/task {\"steps\":[...]}");
            return;
        }
        // 学习梯子步骤2：/task 移到工作线程——ask_user 同步提问要挂起等回复，
        // 轮询线程一旦阻塞，回复永远收不进来（死锁）。/agent 在 v3.32 已同理迁移。
        // v3.32 同理：三通道真互斥，/task 也走 TaskLock
        if (!TaskLock.tryAcquire("tg")) {
            sendMessage(chatId, "⏳ 任务忙：" + TaskLock.current()
                    + " 通道正在执行，请稍后再试");
            return;
        }
        tgTaskStop = false;
        tgTaskRunning = true;
        sendMessage(chatId, "▶ 开始执行任务…", replyTo);
        new Thread(() -> runTaskJson(chatId, json, replyTo), "tg-task").start();
    }

    // 当前在工作线程跑的 /task runner（/stop 联动用）
    private volatile TaskRunner tgTaskRunner = null;

    /** /task 真正的执行体（跑在 tg-task 工作线程） */
    private void runTaskJson(long chatId, String json, long replyTo) {
        StringBuilder logs = new StringBuilder();
        TaskRunner runner = new TaskRunner(ctx, s -> {
            logs.append(s).append("\n");
            log("[task] " + s);
        });
        // 远程任务：ASK 策略的高风险动作无审批人时拦截
        runner.setRemote(true);
        // 学习梯子步骤2：TG 通道标识——ask_user 走同步提问而非结论降级
        runner.setChannel("tg");
        tgTaskRunner = runner;
        boolean ok;
        try {
            ok = runner.run(json);
        } catch (Exception e) {
            sendMessage(chatId, "任务解析失败：" + e.getMessage(), replyTo);
            tgTaskRunning = false;
            tgTaskRunner = null;
            TaskLock.release("tg");
            return;
        }
        tgTaskRunning = false;
        tgTaskRunner = null;
        TaskLock.release("tg");
        sendMessage(chatId, (ok ? "✅ 任务完成\n\n" : "⏹ 任务结束（未完成）\n\n")
                + trimLog(logs.toString()), replyTo);
    }

    // v3.43：像反馈/陈述的句子（「没打开」「登录退了」「完成了，不过…」）不是新指令，
    // 原样当目标执行只会产出假完成。给 agent 加核实框架再执行。
    private static final java.util.regex.Pattern P_FEEDBACK =
            java.util.regex.Pattern.compile(
                    "没打开|没有打开|打不开|没反应|没成功|没完成|失败了|退了|掉线|"
                    + "还是不行|怎么还没|完成了[，,、]?不过|已经完成了");

    private String wrapFeedbackGoal(String goal) {
        try {
            if (goal != null && P_FEEDBACK.matcher(goal).find()) {
                return "【系统提示】用户这句话像是对之前任务的反馈或状态陈述，"
                        + "不一定是新的操作指令。原文：「" + goal + "」。"
                        + "请先用 observe 核实当前实际状态，结合上文判断用户原本的意图；"
                        + "确有问题就实际操作补救，情况与用户所说不同则以观察事实为准，"
                        + "并在最终回复里如实说明。不要把这句话本身当成要执行的动作。";
            }
        } catch (Exception ignored) {
        }
        return goal;
    }

    /**
     * v3.50 第 3 步：解析澄清回答。
     * ①序号（1-4 / ①②③④）→ 取对应选项；②选项文本命中 → 取该选项；
     * ③否则视为用户自由补充的说明，原样返回。
     */
    private String parseClarifyAnswer(String text,
                                      java.util.List<String> options) {
        // 学习梯子步骤2：解析逻辑上移到 AskGate.parseAnswer，三处共用
        //（澄清接龙 / 同步提问），此处只保留兼容壳
        return AskGate.parseAnswer(text, options);
    }

    /** 主人 chatId（TaskRunner 的 ask_user 同步提问要知道发给谁）。 */
    public long getOwnerChatId() {
        return store.getOwner();
    }

    /**
     * 学习梯子步骤2：TG 通道任务内同步提问。
     * 发出选项式问题后阻塞当前线程，等主人下一条回复或超时。
     * 返回：解析后的用户回答；超时返回 null；被新指令打断返回 ""。
     * 必须在任务线程（如 tg-agent/tg-task）调用，禁止在轮询线程调用。
     */
    public String askUserSync(long chatId, String payload,
            java.util.List<String> options, long timeoutMs) {
        if (chatId == 0) return null;
        AskGate.Pending p = AskGate.register(chatId, options);
        try {
            String q = "❓ 需要确认一下\n" + payload
                    + "\n（回复序号或选项即可；换话题发 /new）";
            sendMessage(chatId, q);
            log("[ask] TG 提问已发送，等待用户回复（"
                    + (timeoutMs / 1000) + "s）");
            boolean ok = p.latch.await(timeoutMs,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!ok) {
                log("[ask] TG 提问超时");
                return null;
            }
            if (p.cancelled) {
                log("[ask] TG 提问被新指令打断");
                return "";
            }
            String ans = AskGate.parseAnswer(p.rawAnswer, options);
            log("[ask] TG 用户回答：" + ans);
            return ans == null ? "" : ans;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            AskGate.unregister(chatId, p);
        }
    }

    private void runAgentCmd(long chatId, String goal) {
        runAgentCmd(chatId, goal, 0);
    }

    /** /agent：AI 看屏自动操控（v3.49：进度与结果引用触发消息回复） */
    private void runAgentCmd(long chatId, String goal, long replyTo) {
        if (goal.isEmpty()) {
            sendMessage(chatId, "用法：/agent 打开时钟设个 7 点的闹钟");
            return;
        }
        // v3.30：三端对齐 App——高频意图本地路由先行，闲聊分流放后
        // （之前顺序反了："回桌面"被 isChat 误判进闲聊，TG 只聊天不执行）
        // 高频意图先走本地路由（零 token）
        try {
            LocalIntentRouter.RouteResult rr =
                    LocalIntentRouter.tryRoute(ctx, goal);
            if (rr != null) {
                sendMessage(chatId, (rr.handled ? "✅ " : "⚠️ ") + rr.message);
                if (rr.handled) {
                    // v3.51 步骤5：本地路由决策点打点（零 token 直达）
                    Telemetry.decisionPoint(ctx, "tg", "local_route", 1.0,
                            goal, "local_route", 1.0, null);
                    return;
                }
            } else {
                try {
                    org.json.JSONObject rd = new org.json.JSONObject();
                    rd.put("goal", goal);
                    Telemetry.event(ctx, "tg", "router_miss", rd);
                } catch (Exception ignored2) {
                }
            }
        } catch (Exception ignored) {
        }
        // v3.00：闲聊分流（和 App 内一致）
        // v3.04：TG 流式恢复（LlmClient null bug 已修）
        // v3.06：加 typing 指示器
        if (LocalIntentRouter.isChat(goal)) {
            try {
                org.json.JSONObject cd = new org.json.JSONObject();
                cd.put("text", goal);
                Telemetry.event(ctx, "tg", "chat", cd);
            } catch (Exception ignored) {
            }
            sendTyping(chatId);
            String prompt = "你是GSIC，一个友好、简洁的手机助手。用户说：" + goal
                    + "\n用一句话友好回应，如果用户没说具体要做什么，问他想让我干什么。不要输出JSON。"
 + "【重要】你现在是纯聊天模式、没有任何操作手机的工具，绝不能声称已经帮用户"
                    + "打开/关闭/设置/发送/删除了任何东西；如果用户是在让你操作手机，"
                    + "直接告诉他：把这条单独发给我，我按任务执行。"; prompt = prompt + TermsGate.CORE_TEXT;
            final long msgId = sendMessageReturnId(chatId, "🤖 …", replyTo);
            final StringBuilder full = new StringBuilder("🤖 ");
            final long[] lastEdit = {0};
            final long[] lastTyping = {0};
            LlmClient.Result r = llmStore.client().chatStream(prompt, token -> {
                full.append(token);
                long now = System.currentTimeMillis();
                // typing 指示器每 4 秒刷一次（TG 5 秒过期）
                if (now - lastTyping[0] > 4000) {
                    lastTyping[0] = now;
                    sendTyping(chatId);
                }
                // 1.5 秒节流编辑，防 TG 限流
                if (now - lastEdit[0] > 1500) {
                    lastEdit[0] = now;
                    editMessage(chatId, msgId, full.toString());
                }
            });
            // 最终确保完整
            if (r.ok) {
                editMessage(chatId, msgId, full.toString());
            } else {
                editMessage(chatId, msgId, "出错了：" + r.text);
            }
            return;
        }
        if (!llmStore.hasKey()) {
            sendMessage(chatId, "先在 App「大模型配置」里填好 API Key");
            return;
        }
        // v3.22-hotfix：去掉一刀切的无障碍检查。无 root 且无障碍未开时 AgentLoop 内部会给出准确提示；
        // 有 root 时截屏/点击都能走 root 通道，不再提前拒绝所有任务。
        boolean noA11yNoRoot = !AgentAccessibilityService.isRunning()
                && !AgentLoop.checkRootOnce();
        if (noA11yNoRoot) {
            sendMessage(chatId, "⚠️ 无障碍未开启且无 root，无法操控屏幕。截屏、开 App、音量等可用上方直达指令，或去 App 里开启无障碍。");
            // 不直接 return：仍进 AgentLoop，让 prompt 里的能力段告诉模型真实情况，由它决定能做多少
        }
        // v3.43：意图门——反馈/陈述句不原样当目标，给 agent 加核实框架
        final String execGoal = wrapFeedbackGoal(goal);
        sendMessage(chatId, "🎯 收到：" + goal + "\n开始执行…", replyTo);
        // v3.06：任务执行时也显示 typing
        sendTyping(chatId);
        // v3.32：三通道真互斥——App/远控占用时不再并发抢屏
        if (!TaskLock.tryAcquire("tg")) {
            sendMessage(chatId, "⏳ 任务忙：" + TaskLock.current()
                    + " 通道正在执行，请稍后再试");
            return;
        }
        // v2.98：重置停止标志，标记任务运行中
        tgTaskStop = false;
        tgTaskRunning = true;
        // v3.32：任务移到工作线程——之前在轮询线程同步执行，
        // 任务期间（最长 5 分钟）/stop 与任何消息都收不进来
        new Thread(() -> runAgentTask(chatId, execGoal, replyTo), "tg-agent").start();
    }

    // v3.36：TG 任务也要有悬浮窗提示（与远控同款）
    private void floatShow(String text) {
        // v3.39：走任务级登记（在 App 前台发起也不丢窗）
        FloatWindow.beginTask(ctx, text);
    }

    private void floatEnd(String text) {
        FloatWindow.endTask(text);
    }

    private void runAgentTask(long chatId, String goal, long replyTo) {
        StringBuilder logs = new StringBuilder();
        // v3.37：过程直播——一条消息原地编辑，实时显示执行到哪一步
        // v3.49：进度消息引用触发指令（reply_parameters），执行完的总结编辑同一条，天然挂在原指令下
        final long progressId = sendMessageReturnId(chatId,
                "🎯 " + goal + "\n⏳ 开始执行…", replyTo);
        final long progStart = System.currentTimeMillis();
        final int[] progSteps = {0};
        final long[] progLastEdit = {0};
        final String[] progLastText = {""};
        // v3.44：一份共享记忆——上下文改读三端共用的持久化轮次
        final String convCtx = SharedMemory.recentText(ctx, 3);
        AgentLoop loop = new AgentLoop(ctx, llmStore.client(),
                new AgentLoop.Listener() {
                    @Override
                    public void onLog(String msg) {
                        logs.append(msg).append("\n");
                        log("[agent] " + msg);
                        try {
                            String fl = FloatWindow.briefLine(msg);
                            if (fl != null) FloatWindow.pushLine(fl);
                        } catch (Exception ignored) {
                        }
                        // v3.37：节流 2 秒编辑同一条进度消息（原 shouldStream 逐条发已废弃）
                        if (msg.startsWith("🖐")) progSteps[0]++;
                        if (progressId > 0) {
                            long now = System.currentTimeMillis();
                            if (now - progLastEdit[0] > 2000) {
                                progLastEdit[0] = now;
                                sendTyping(chatId);
                                String txt = buildProgressText(goal,
                                        logs.toString(), progSteps[0], progStart);
                                if (!txt.equals(progLastText[0])) {
                                    progLastText[0] = txt;
                                    editMessage(chatId, progressId, txt);
                                }
                            }
                        }
                    }

                    @Override
                    public boolean isStopped() {
                        // v2.98：支持 /stop 强行打断
                        return !running || tgTaskStop;
                    }
                });
        // 远程（Telegram）进来：高风险动作无审批人时默认拦截，不自动放行
        loop.setRemote(true);
        loop.setChannel("tg");
        if (!convCtx.isEmpty()) loop.setConversationContext(convCtx);
        loop.setSupervisorClient(llmStore.supervisorClient());
        loop.setApprover((action, detail) -> {
            sendMessage(chatId, "⛔ 远程拦截高风险动作：" + action
                    + "\n在手机 App 上执行可弹窗确认。");
            return false;
        });
        floatShow("🎯 TG任务：" + goal);
        boolean done;
        try {
            done = loop.run(goal, AgentLoop.NO_LIMIT);
        } catch (Exception e) {
            if (progressId > 0) {
                editMessage(chatId, progressId, "❌ 执行异常：" + e.getMessage());
            }
            sendMessage(chatId, "执行异常：" + e.getMessage(), replyTo);
            floatEnd("❌ TG任务异常");
            tgTaskRunning = false;
            TaskLock.release("tg");
            return;
        } finally {
            tgTaskRunning = false;
            TaskLock.release("tg");
        }
        // v3.50 第 3 步：低置信拦截——发澄清问题并登记接龙，不记经验轮次（任务未实际执行）
        if (loop.needsClarify()) {
            String q = "❓ 需要确认一下\n" + loop.clarifyQuestion()
                    + "\n（回复序号即可；换话题发 /new）";
            pendingClarify.put(chatId,
                    new ClarifyPending(goal, loop.clarifyOptions(), replyTo));
            floatEnd("❓ TG任务待澄清");
            if (progressId > 0) {
                editMessage(chatId, progressId, q);
            } else {
                sendMessage(chatId, q, replyTo);
            }
            log("[agent] ❓ 低置信拦截，已发澄清等待用户回复");
            return;
        }
        floatEnd(done ? "✅ TG任务完成" : "⏹ TG任务结束");
        // v3.37：结尾只发简短总结（状态+答复+用时/步数），不再倒整段日志
        String ans = extractFinalAnswer(logs.toString());
        StringBuilder fin = new StringBuilder();
        fin.append(done ? "🎉 任务完成" : "⏹ 任务结束（未完成）");
        if (tgTaskStop) fin.append("（被 /stop 打断）");
        fin.append("\n🎯 ").append(goal).append("\n");
        if (!ans.isEmpty()) fin.append("🤖 ").append(ans).append("\n");
        fin.append("⏱ 用时 ").append(elapsedText(progStart))
                .append(" · 共 ").append(progSteps[0]).append(" 步");
        {
            // v3.45：尾巴逻辑收敛到 SharedMemory.tail60，三端统一
            String tail = SharedMemory.tail60(ans);
            SharedMemory.addRound(ctx, "tg", goal,
                    (done ? "任务完成" : "任务未完成")
                            + (tail.isEmpty() ? "" : "，" + tail));
        }
        if (progressId > 0) {
            editMessage(chatId, progressId, fin.toString());
        } else {
            sendMessage(chatId, fin.toString());
        }
    }

    // v3.37：进度消息文本（目标 + 状态 + 步数/用时 + 日志尾部）
    private String buildProgressText(String goal, String logs,
                                     int steps, long startMs) {
        StringBuilder sb = new StringBuilder();
        sb.append("🎯 ").append(goal).append("\n");
        sb.append("⏳ 执行中… 第 ").append(steps).append(" 步 · 已用 ")
                .append(elapsedText(startMs)).append("\n\n");
        String tail = logs.length() > 2600
                ? logs.substring(logs.length() - 2600) : logs;
        sb.append(tail);
        return sb.toString();
    }

    private static String elapsedText(long startMs) {
        long sec = (System.currentTimeMillis() - startMs) / 1000;
        return sec >= 60 ? (sec / 60) + "分" + (sec % 60) + "秒" : sec + "秒";
    }

    /** 从日志里提最后一条模型答复（🤖 开头） */
    private String extractFinalAnswer(String logs) {
        String ans = "";
        for (String line : logs.split("\n")) {
            if (line.startsWith("🤖 ")) ans = line.substring(2).trim();
        }
        return ans;
    }

    // v2.98：判断哪些消息值得实时推送（关键节点，不刷屏）
    private boolean shouldStream(String msg) {
        return msg.startsWith("💭") || msg.startsWith("📍")
                || msg.startsWith("🔍") || msg.startsWith("🤖")
                || msg.startsWith("✅") || msg.startsWith("❌");
    }

    /** 判断是否为截图请求（自然语言） */
    private boolean isScreenshotAsk(String text) {
        if (text == null) return false;
        String t = text.trim();
        // 精确匹配常见说法
        if (t.equals("截图") || t.equals("截屏") || t.equals("截个屏") || t.equals("截一张屏")) return true;
        // 包含关键词
        return (t.contains("截图") || t.contains("截屏")) && (t.contains("当前") || t.contains("界面") || t.contains("屏幕") || t.length() <= 6);
    }

    /** /shot：截屏发照片（v3.22-hotfix：改用 ScreenshotUtil.capture，无障碍→root 两级，不再只依赖无障碍） */
    private void sendScreenshot(long chatId) {
        try {
            Bitmap bmp = ScreenshotUtil.capture(ctx);
            if (bmp == null) {
                sendMessage(chatId, "截屏失败（无障碍未开启且 root 截屏也失败）");
                return;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            // 压缩后再发：省流量（本地看仍是原图）
            byte[] small = ScreenshotUtil.toJpegSmall(bmp, 720, 70);
            byte[] data = small != null ? small : bos.toByteArray();
            if (small == null) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
                data = bos.toByteArray();
            }
            boolean ok = sendPhoto(chatId, data, "📸 当前屏幕");
            if (!ok) sendMessage(chatId, "照片发送失败");
        } catch (Exception e) {
            sendMessage(chatId, "截屏异常：" + e.getMessage());
        }
    }

    // ---------- Bot API 底层 ----------

    private JSONObject apiGet(String path) throws Exception {
        URL url = new URL(API + store.getToken() + "/" + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(45000);
        int code = c.getResponseCode();
        String body = readAll(c, code);
        if (code != 200) {
            log("Telegram API 错误 HTTP " + code);
            return null;
        }
        return new JSONObject(body);
    }

    private void sendMessage(long chatId, String text) {
        sendMessage(chatId, text, 0);
    }

    /** v3.49：发送并引用某条消息回复（replyTo>0 时带 reply_parameters） */
    private void sendMessage(long chatId, String text, long replyTo) {
        sendMessageReturnId(chatId, text, replyTo);
    }

    /** v3.02：发送并返回 message_id（用于流式编辑） */
    private long sendMessageReturnId(long chatId, String text) {
        return sendMessageReturnId(chatId, text, 0);
    }

    /** v3.49：同上，replyTo>0 时引用触发消息（执行完的自动回复挂在原指令下） */
    private long sendMessageReturnId(long chatId, String text, long replyTo) {
        if (text == null || text.isEmpty()) return 0;
        long msgId = 0;
        try {
            // 超长分段（只返回第一段的 id）
            for (int i = 0; i < text.length(); i += MSG_LIMIT) {
                String part = text.substring(i, Math.min(i + MSG_LIMIT, text.length()));
                String params = "chat_id=" + chatId
                        + "&text=" + URLEncoder.encode(part, "UTF-8");
                if (replyTo > 0) {
                    params += "&reply_parameters=" + URLEncoder.encode(
                            "{\"message_id\":" + replyTo + "}", "UTF-8");
                }
                URL url = new URL(API + store.getToken() + "/sendMessage");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("Content-Type",
                        "application/x-www-form-urlencoded");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(params.getBytes(StandardCharsets.UTF_8));
                }
                int code = c.getResponseCode();
                String resp = readAll(c, code);
                if (code != 200) {
                    log("发送消息失败 HTTP " + code);
                } else if (msgId == 0) {
                    try {
                        org.json.JSONObject o = new org.json.JSONObject(resp);
                        msgId = o.optJSONObject("result").optLong("message_id", 0);
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            log("发送消息异常：" + e.getMessage());
        }
        return msgId;
    }

    /** v3.06：发送 typing 指示器（原生流式体验） */
    private void sendTyping(long chatId) {
        try {
            String params = "chat_id=" + chatId + "&action=typing";
            URL url = new URL(API + store.getToken() + "/sendChatAction");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded");
            try (OutputStream os = c.getOutputStream()) {
                os.write(params.getBytes(StandardCharsets.UTF_8));
            }
            readAll(c, c.getResponseCode());
        } catch (Exception ignored) {
        }
    }

    /** v3.02：编辑消息（流式更新用） */
    private void editMessage(long chatId, long msgId, String text) {
        if (msgId == 0 || text == null) return;
        try {
            String params = "chat_id=" + chatId
                    + "&message_id=" + msgId
                    + "&text=" + URLEncoder.encode(
                            text.length() > MSG_LIMIT
                                    ? text.substring(0, MSG_LIMIT) : text,
                            "UTF-8");
            URL url = new URL(API + store.getToken() + "/editMessageText");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded");
            try (OutputStream os = c.getOutputStream()) {
                os.write(params.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            readAll(c, code);
            // 429 或内容未变都静默忽略（节流已做）
        } catch (Exception e) {
            log("编辑消息异常：" + e.getMessage());
        }
    }

    private boolean sendPhoto(long chatId, byte[] png, String caption) {
        String boundary = "----PhoneAgent" + System.currentTimeMillis();
        try {
            URL url = new URL(API + store.getToken() + "/sendPhoto");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setRequestProperty("Content-Type",
                    "multipart/form-data; boundary=" + boundary);
            try (OutputStream os = c.getOutputStream()) {
                writePart(os, boundary, "chat_id",
                        String.valueOf(chatId).getBytes(StandardCharsets.UTF_8), null);
                writePart(os, boundary, "caption",
                        caption.getBytes(StandardCharsets.UTF_8), null);
                writePart(os, boundary, "photo", png, "shot.png");
                os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            readAll(c, code);
            return code == 200;
        } catch (Exception e) {
            log("发送照片异常：" + e.getMessage());
            return false;
        }
    }

    private void writePart(OutputStream os, String boundary, String name,
                           byte[] data, String filename) throws Exception {
        StringBuilder h = new StringBuilder("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(name).append("\"");
        if (filename != null) h.append("; filename=\"").append(filename).append("\"");
        h.append("\r\n");
        if (filename != null) h.append("Content-Type: image/png\r\n");
        h.append("\r\n");
        os.write(h.toString().getBytes(StandardCharsets.UTF_8));
        os.write(data);
        os.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private String readAll(HttpURLConnection c, int code) throws Exception {
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toString("UTF-8");
    }

    private static String trimLog(String s) {
        if (s.length() <= MSG_LIMIT) return s;
        return "…(日志过长，只显示末尾)\n" + s.substring(s.length() - MSG_LIMIT);
    }

    private static String brief(String s) {
        s = s.replace("\n", " ");
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
