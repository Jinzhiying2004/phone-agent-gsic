package com.gsic.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
* v3.26 DSH(DeepSeek Harness) 式智能体：会话即状态，工具即接口。
* <ul>
* <li>循环：messages + 原生工具调用。模型自己决定看屏（observe）、调哪个工具、何时结束（文本回复=答复）。</li>
* <li>harness 只做四件事：执行工具、注入观察、权限分级、护栏（停止/超时/步数/零动作拦截）。</li>
* <li>没有规划器/监管者/计划状态机/硬编码规则——那些已整体下线。</li>
* </ul>
* 对外 API 与旧版保持一致（构造/setters/run/getAuditJson），三端无需改调用。
*/
public class AgentLoop {
// v3.34：步数不设限——模型自己决定何时结束；999 仅为防死循环熔断，正常任务碰不到
public static final int NO_LIMIT = 999;

public interface Listener {
void onLog(String msg);

boolean isStopped();

/** 超时/无进展时询问用户是否继续，返回 true=继续 */
default boolean askContinue(String reason) {
return true;
}
}

private final Context ctx;
private final LlmClient llm;
private final Listener listener;

// ---- 通道配置（setters 保持旧 API） ----
private boolean allowAdvanced = true;
private ActionRisk.Policy riskPolicy = null;
private TaskRunner.Approver approver = null;
/** v3.51 步骤5：遥测打点状态——本任务内各病因计数、上一步时间戳、工具截图哈希 */
private final java.util.Map<String, Integer> runFailClass = new java.util.HashMap<String, Integer>();
private long lastStepT0 = 0;
private long toolLastHash = 0;
private boolean remote = false;
private String conversationContext = "";
private String planHint = "";
private String supervisorHint = "";
private boolean supervisorEnabled = true;
private LlmClient supervisorClient = null;
private android.graphics.Bitmap supShot = null;
private int supFinalRounds = 0;
private int lastActionsDone = 0;
private int lastMutationsDone = 0;
private boolean stoppedByUser = false;
private java.util.function.Supplier<String> feedbackSupplier;
private boolean vdMode = false;
// 主从互斥（2026-10-23 用户亲定）：本任务主显示是否锁定为虚拟屏。
// runSession 开始时按通道与创建结果钉死，任务内不再变更：
// true=全部操作/截图/终审只走虚拟屏，实体屏仅右上角小窗预览（从显示）；
// false=主显示锁定实体屏。VD 中途失效不回退实体屏（防双屏各留一份）。
private boolean taskPrimaryVd = false;

// v3.50 意图层：任务书（run() 开跑前生成，runSession() 每轮钉住+停止线检查；null=生成失败走老路）
private TaskBook taskBook = null;
private MasterDict masterDict = null;
private CognitionStore cogStore = null;
private String dictShort = "";
/** 本次任务的任务书（供 MainActivity 终审判据绑定 / 后续低置信拦截用；可能为 null） */
public TaskBook getTaskBook() { return taskBook; }
// v3.50 第 3 步：低置信拦截——置信度低于阈值时 run() 不进 runSession，三端各自渲染澄清
private double confThreshold = TaskBook.CONF_THRESHOLD_DEFAULT;
/** 本次 run() 是否被低置信拦截（三端调用方用 needsClarify() 判别，区别于执行失败） */
private boolean clarifyState = false;
/** run() 是否被低置信拦截：true=任务书已生成但置信度不足，三端应渲染澄清而非判失败 */
public boolean needsClarify() { return clarifyState; }
/** 澄清问题文本（needsClarify()==true 时有效） */
public String clarifyQuestion() { return taskBook != null ? taskBook.clarifyQuestion() : ""; }
/** 澄清选项（可能为空，空时三端请用户自由补充） */
public java.util.List<String> clarifyOptions() {
return taskBook != null ? taskBook.clarifyOptions : new java.util.ArrayList<String>();
}

private TaskRunLogger taskLogger = null;
private String lastHistory = "";
private final java.util.List<JSONObject> auditSteps = new java.util.ArrayList<>();
/** v3.58 步骤2：本轮各步骤前台包名投票（取众数为主前台包名，草稿卡 appPkg 用） */
private final java.util.Map<String, Integer> fgPkgVotes = new java.util.HashMap<>();

/** v3.58 步骤2：本轮主前台包名（各步骤出现次数最多者；无记录返回空串） */
public String getMainPkg() {
String best = "";
int bestN = 0;
for (java.util.Map.Entry<String, Integer> e : fgPkgVotes.entrySet()) {
if (e.getValue() != null && e.getValue() > bestN) {
bestN = e.getValue();
best = e.getKey();
}
}
return best;
}

public AgentLoop(Context ctx, LlmClient llm, Listener listener) {
this.ctx = ctx.getApplicationContext();
this.llm = llm;
this.listener = listener;
}

// ---- setters（旧签名保留；规划/监管类在新架构下只作上下文提示，不再驱动流程） ----
public void setAllowAdvanced(boolean v) { allowAdvanced = v;}
public void setRiskPolicy(ActionRisk.Policy p) { riskPolicy = p;}
public void setApprover(TaskRunner.Approver a) { approver = a;}
public void setRemote(boolean r) { remote = r;}
public void setSupervisorEnabled(boolean v) { supervisorEnabled = v;}
public void setSupervisorClient(LlmClient c) { supervisorClient = c;}
public void setSupervisorFeedback(String fb) {
supervisorHint = fb == null? "": fb;
}
public void setFeedbackSupplier(java.util.function.Supplier<String> sup) {
feedbackSupplier = sup;
}
public void setConversationContext(String c) {
conversationContext = c == null? "": c;
}
public void setPlan(String plan) { planHint = plan == null? "": plan;}

private String channel = "unknown";
/** 智能沉淀：标记来源通道（app/tg/remote） */
public void setChannel(String ch) { channel = ch == null ? "unknown" : ch; }
public void setTodoSteps(java.util.List<String> steps) { /* 新架构模型自主推进，忽略 */}

public String getLastHistory() { return lastHistory;}

public String getTaskLogPath() {
return taskLogger!= null? taskLogger.getPath(): null;
}

/** 审计：每次工具调用明细（name/ok），供运行记录存档 */
public String getAuditJson() {
try {
JSONArray arr = new JSONArray();
for (JSONObject o: auditSteps) arr.put(o);
return arr.toString();
} catch (Exception e) {
return "[]";
}
}

private void log(String s) {
if (listener!= null) listener.onLog(s);
}

private boolean streamOutput() {
try {
return ctx.getSharedPreferences("agent", Context.MODE_PRIVATE)
.getBoolean("stream_output", true);
} catch (Exception e) {
return true;
}
}

private static Boolean cachedRoot = null;

/** root 检测（缓存） */
static boolean checkRootOnce() {
if (cachedRoot!= null) return cachedRoot;
boolean ok = false;
try {
Process pr = Runtime.getRuntime().exec(new String[]{"su", "-c", "exit"});
ok = pr.waitFor() == 0;
} catch (Exception ignored) {
}
cachedRoot = ok;
return ok;
}

/**
* 在调用线程执行（调用方负责后台线程）。
*
* @return true=完成，false=中断/失败/达最大步数
*/
/** 本次任务是否操作过主屏（决定结束时要不要回桌面） */
private boolean touchedMain = false;

public boolean run(String goal, int maxSteps) {
// v3.36：虚拟屏按通道分工——App 通道自动开屏（带悬浮预览），
// TG/远控主屏执行。结束统一清理残屏。
touchedMain = false;
supFinalRounds = 0;
stoppedByUser = false;
try {
// v3.51：流光登记收归执行核心——三端（App/TG/远控）都经 run() 执行，
// 在此统一 begin，finally 统一 end；通道侧登记保留作嵌套外层（引用计数）
FloatWindow.beginTask(ctx, "🎯 " + goal);
Telemetry.event(ctx, channel, "task_start", taskData(goal, ""));
long runStartMs = System.currentTimeMillis();
// v3.51 步骤5：模型画像打点——本任务内所有 LlmClient 调用经 sink 记 model_call
// （不记 prompt 原文；监管者/任务书短调用同样覆盖）
final String sinkCh = channel;
LlmClient.setTelemetrySink(new LlmClient.ModelCallSink() {
    @Override public void onCall(String model, long latencyMs, long tokIn, long tokOut,
                                 boolean timeout, int retries) {
        Telemetry.modelCall(ctx, sinkCh, model, latencyMs,
                tokIn, tokOut, timeout, retries);
    }
});
// v3.51 步骤5：本任务打点状态清零
runFailClass.clear();
toolLastHash = 0;
lastStepT0 = 0;
// v3.50 意图层：开跑前一次短调用生成任务书（失败/无效→零 token 启发式兜底，再失败走老路）
// v3.50 第 3 步：低置信拦截——置信度 < 阈值（prefs taskbook_conf_threshold，默认 0.4）时不进 runSession，
// 三端各自渲染澄清（App 弹选项对话框 / TG 发澄清消息接龙 / 远控 /task 返回 needs_clarify）。
taskBook = null;
masterDict = null;
dictShort = "";
clarifyState = false;
try {
confThreshold = ctx.getSharedPreferences("agent", Context.MODE_PRIVATE)
.getFloat("taskbook_conf_threshold",
(float) TaskBook.CONF_THRESHOLD_DEFAULT);
} catch (Exception ignored) {
confThreshold = TaskBook.CONF_THRESHOLD_DEFAULT;
}
try {
masterDict = MasterDict.load(ctx.getFilesDir());
// 记忆分层步骤3：主人词典种子一次性迁入认知库（src=user_seed 直接 active；
// importSeeds 按 key 幂等，已迁过返回 0 不重复写）。常驻注入数据源切换：
// MasterDict 硬编码 seeds 只保留为种子来源，任务书/钉住块改读认知库
// active user_dict（CognitionStore.dictLines/dictShort），种子更新=改条目。
// 记忆分层步骤4：图标词典通用符号种子幂等导入（src=user_seed 直接 active）。
cogStore = CognitionStore.load(ctx.getFilesDir());
if (cogStore.importSeeds(masterDict) + cogStore.importIconSeeds() > 0) {
cogStore.save(ctx.getFilesDir());
}
dictShort = cogStore.dictShort();
final String dictFull = cogStore.dictLines();
TaskBook b = TaskBook.generate(new TaskBook.ChatFn() {
@Override public String chat(String prompt) {
try {
LlmClient.Result r = llm.chat(prompt);
return (r != null && r.ok) ? r.text : null;
} catch (Exception e) {
return null;
}
}
}, goal, dictFull);
if (b == null) b = TaskBook.fromHeuristic(goal);
if (b != null && b.valid()) {
taskBook = b;
log("📋 任务书：对象=" + b.target + "｜动作=" + b.action
+ "｜判据=" + b.criteria + "（置信度 " + b.confidence + "）");
}
} catch (Exception e) {
taskBook = null;
}
if (taskBook == null) log("📋 任务书生成失败，按原流程执行");
// v3.51 步骤5：决策点打点——路由命中与分数、意图解析、任务书置信度、计划分步
// （本地路由命中的任务不进 run()，由三端通道侧各自打点 route_name=local_route）
try {
    java.util.List<String> planSteps = new java.util.ArrayList<String>();
    if (taskBook != null && taskBook.valid()) {
        if (!taskBook.criteria.isEmpty()) planSteps.add(taskBook.criteria);
        for (TaskBook.StopLine sl : taskBook.stopLines) {
            if (sl != null && !sl.signal.isEmpty()) planSteps.add(sl.signal);
        }
    }
    Telemetry.decisionPoint(ctx, channel, "llm_agent", 0.0,
            taskBook != null ? taskBook.target : "",
            taskBook != null ? taskBook.action : "",
            taskBook != null ? taskBook.confidence : -1.0,
            planSteps);
} catch (Exception ignored) {}
// v3.50 第 3 步：低置信拦截——不硬跑，返回澄清态（三端调用方判 needsClarify() 渲染澄清）
if (taskBook != null && taskBook.needsClarify(confThreshold)) {
clarifyState = true;
log("❓ 任务书置信度低（" + taskBook.confidence + " < " + confThreshold
+ "），不硬跑，等待澄清");
try {
org.json.JSONObject td = taskData(goal, "低置信拦截");
td.put("confidence", taskBook.confidence);
td.put("threshold", confThreshold);
Telemetry.event(ctx, channel, "task_clarify", td);
} catch (Exception ignored) {
}
return false; // finally 照常清理；经验库不记（任务未实际执行）
}
try {
String exp = SharedMemory.relevantExperience(ctx, goal);
if (!exp.isEmpty()) {
conversationContext = conversationContext.isEmpty()
? exp : conversationContext + "\n" + exp;
log("🧠 已注入长期认知（认知库相关条目）");
}
} catch (Exception ignored) {
}
// 跨通道复现（2026-10-22 用户亲定）：远控旁聊单步操控沉淀的做法
// （cloud_ops.jsonl）与三端任务经验同等待遇，注入供复现
try {
String ops = SharedMemory.relevantCloudOps(ctx, goal);
if (!ops.isEmpty()) {
conversationContext = conversationContext.isEmpty()
? ops : conversationContext + "\n" + ops;
log("☁ 已注入云端操控沉淀（远控旁聊实操）");
}
} catch (Exception ignored) {
}
boolean ok = runSession(goal, maxSteps);
// v3.38：agent 判未完成时监管者再看一眼最终界面——阴差阳错已达成的改判完成
if (!ok && supervisorEnabled && !stoppedByUser && lastActionsDone > 0) {
Supervisor.Verdict fv = finalReview(goal, false, lastHistory);
if (fv != null && fv.ok && fv.completed) {
log("🔍 监管者改判：目标实际已达成（约 " + fv.progressPct + "%）");
ok = true;
if (taskLogger != null) taskLogger.correctFinish(true, "监管者终审改判完成（约 " + fv.progressPct + "%），最终结果以完成为准，与遥测 task_end 一致");
}
}
try {
org.json.JSONObject td = taskData(goal, ok ? "" : "\u672a\u5b8c\u6210");
td.put("ok", ok);
Telemetry.event(ctx, channel, "task_end", td);
} catch (Exception ignored) {
}
try {
SharedMemory.addExperience(ctx, channel, goal, ok,
System.currentTimeMillis() - runStartMs, lastActionsDone,
expNote(ok));
} catch (Exception ignored) {
}
// v3.58 步骤2：Distiller 技能卡草稿队列消费 -> 成功轨迹自动沉淀为草稿卡
// （只沉淀成功轨迹；候选 votes>=2 才沉淀；草稿卡待同类复验成功后转正）
try {
String draftId = SharedMemory.maybeDraftSkillCard(ctx, goal, ok,
lastHistory, getMainPkg(), expNote(ok));
if (draftId != null) {
log("📇 成功轨迹已沉淀为技能卡草稿（同类任务复验成功后转正）：" + draftId);
}
} catch (Exception ignored) {}
return ok;
} finally {
// v3.51 步骤5：模型画像打点只活本任务，退出即卸 sink
LlmClient.setTelemetrySink(null);
// v3.51：与 run() 开头登记配对（引用计数，通道外层登记未结束不熄灯）
FloatWindow.endTask("");
boolean wasInVd = false;
try {
wasInVd = VirtualDisplayManager.isActive();
if (wasInVd) {
VirtualDisplayManager.destroy();
log("虚拟屏已销毁");
}
} catch (Exception ignored) {
}
vdMode = false;
taskPrimaryVd = false;
PhoneTools.sessionDisplayLock = "";
if (supShot != null) {
try { supShot.recycle(); } catch (Exception ignored) {}
supShot = null;
}
// 2026-10-22 用户亲定废止自动回桌面：「不必所有任务执行完都要
// 回到桌面」——任务停在结束时界面，除非目标本身要求回桌面。
// （旧铁律 2026-10-17 立、v3.18 起限主屏任务，此处正式移除）
}
}

/**
* DSH 式会话循环。状态就是 messages 本身；工具见 {@link PhoneTools}。
* 完成判定：模型在至少调用过一次工具后给出文本回复，或直接调 finish 语义的文本答复。
* 零动作拦截：一次工具都没调就想用文字结束 → 提醒一次；再犯判失败（防"口头完成"）。
*/
/** 8x8 平均哈希：判断两帧界面是否几乎一样（监管看门狗用） */
private static long avgHash(android.graphics.Bitmap b) {
try {
android.graphics.Bitmap s = android.graphics.Bitmap.createScaledBitmap(b, 8, 8, true);
int[] px = new int[64];
s.getPixels(px, 0, 8, 0, 0, 8, 8);
long[] gray = new long[64];
long sum = 0;
for (int i = 0; i < 64; i++) {
int c = px[i];
gray[i] = (android.graphics.Color.red(c) + android.graphics.Color.green(c) + android.graphics.Color.blue(c)) / 3;
sum += gray[i];
}
long avg = sum / 64;
long h = 0;
for (int i = 0; i < 64; i++) if (gray[i] >= avg) h |= (1L << i);
if (s != b) s.recycle();
return h;
} catch (Exception e) {
return 0;
}
}

private long sessionStartMs = 0;

private org.json.JSONObject taskData(String goal, String whyTag) {
try {
org.json.JSONObject d = new org.json.JSONObject();
d.put("goal", Telemetry.cut(goal, 60));
if (sessionStartMs > 0) d.put("ms", System.currentTimeMillis() - sessionStartMs);
if (whyTag != null && !whyTag.isEmpty()) d.put("why", whyTag);
return d;
} catch (Exception e) {
return null;
}
}

private boolean runSession(String goal, int maxSteps) {
sessionStartMs = System.currentTimeMillis();
fgPkgVotes.clear(); // v3.58 步骤2：本轮前台包投票清零
log("🎯 目标：" + goal);
try {
taskLogger = new TaskRunLogger(ctx, goal);
} catch (Exception ignored) {
}
TaskRunner runner = new TaskRunner(ctx, listener::onLog);
if (riskPolicy!= null) runner.setRiskPolicy(riskPolicy);
runner.setApprover(approver);
runner.setRemote(remote);
runner.setCognitionStore(cogStore);
// v3.36 通道分工：只有 App 内命令走虚拟屏（自动开屏 + 右上角悬浮预览）；
// TG/远控在主屏正常执行（PhoneTools 会拒绝其 vd_start）。
PhoneTools.sessionChannel = channel;
VirtualDisplayManager.setPreviewEnabled("app".equals(channel));
runner.setVdMode(false);
// 主从关系钉死：先默认主显示=实体屏；App 通道成功建屏后改钉虚拟屏。
// 降级只发生在此刻（任何动作执行之前），任务开始后不再跨屏。
taskPrimaryVd = false;
PhoneTools.sessionDisplayLock = "main";
if ("app".equals(channel)) {
try {
boolean vdOn = ctx.getSharedPreferences("agent", Context.MODE_PRIVATE)
.getBoolean("task_vd_default", true);
if (vdOn) {
int did = VirtualDisplayManager.create(ctx, 720, 1280, 320);
if (did >= 0) {
runner.setVdMode(true);
vdMode = true;
taskPrimaryVd = true;
PhoneTools.sessionDisplayLock = "vd";
log("🖥 App 通道：主显示锁定虚拟屏（实体屏右上角小窗仅实时预览）");
} else {
log("虚拟屏创建失败，本任务改在实体屏执行（主显示锁定实体屏）");
}
}
} catch (Exception ignored) {
}
}
try {
String rp = ctx.getSharedPreferences("agent", Context.MODE_PRIVATE)
.getString("remote_risk_policy", "deny");
runner.setRemoteRiskPolicy(rp);
} catch (Exception ignored) {
}

JSONArray messages = new JSONArray();
StringBuilder hist = new StringBuilder();
try {
JSONObject sys = new JSONObject();
sys.put("role", "system");
sys.put("content", sessionSystemPrompt(goal));
messages.put(sys);
JSONObject u = new JSONObject();
u.put("role", "user");
StringBuilder ub = new StringBuilder("任务：").append(goal);
if (!planHint.isEmpty()) ub.append("\n（参考计划，仅供参考，可灵活调整：").append(planHint).append("）");
if (!supervisorHint.isEmpty()) ub.append("\n（上次未完成反馈：").append(supervisorHint).append("）");
u.put("content", ub.toString());
messages.put(u);
} catch (Exception e) {
log("会话初始化失败：" + e.getMessage());
return false;
}

int actionsDone = 0; // 实际执行的工具数（不含 observe）
int textOnlyStrikes = 0; // 零动作想结束的次数
int mutationsDone = 0; // v3.43：成功的状态改变类工具数（完成硬门槛用）
int zeroMutStrikes = 0; // v3.43：只有观察/空转却想结束的次数
int adWaits = 0; // 广告预算：奖励类任务的长等待（看广告）次数
boolean rewardGoal = goal.contains("广告") || goal.contains("奖励")
|| goal.contains("免费") || goal.contains("领取");
int adBudget = 4;
try {
adBudget = ctx.getSharedPreferences("agent", Context.MODE_PRIVATE)
.getInt("ad_budget_per_task", 4);
} catch (Exception ignored) {
}
boolean claimSeen = false; // 硬停止线：已出现「领取成功/已领取」证据
int claimTurn = 0;
int postClaimActs = 0; // 证据出现后又执行的状态改变动作数
int upsellWarns = 0; // 链条推销（再看N个提前得…）已提醒次数
boolean engagedApp = false; // 已有真实应用内操作（launch/tap/vision 等），防说明文本误触发证据扫描
int turn = 0;
// v3.50 意图层状态：任务书通用停止线（book 有效且有停止线时启用；否则走旧硬编码兜底）
final boolean useBookStop = taskBook != null && taskBook.valid()
&& !taskBook.stopLines.isEmpty();
boolean stopLineFired = false;
int stopFireTurn = 0;
int postStopActs = 0;
String stopLineAction = "";
java.util.Set<String> bookBranchReminded = new java.util.HashSet<>(); // 步骤6：判据分支收尾提醒已提醒集合（每分支只提醒一次）
// v3.35 监管看门狗状态：只在死循环/连续失败/幻觉信号时请最强模型介入
java.util.List<String> wdSigs = new java.util.ArrayList<>();
java.util.List<String> wdRecent = new java.util.ArrayList<>();
java.util.Map<String, Integer> wdTapMiss = new java.util.HashMap<>();
int wdFailStreak = 0;
int wdCooldown = 0;
int wdInterventions = 0;
int wdLastCheckTurn = 0;
long wdLastHash = 0;
int wdSameScreen = 0;
boolean wdActed = false;
while (turn < maxSteps) {
turn++;
if (listener!= null && listener.isStopped()) {
stoppedByUser = true;
log("已手动停止");
lastHistory = hist.toString();
return false;
}
// v3.51 步骤5：每步环境打点（前台包/网络/步耗时/任务累计耗时）
// fgPackage 复用 TaskRunner 的 10s TTL 缓存，零额外开销；
// v3.58 步骤2：顺带给本轮主前台包名投票（草稿卡 appPkg 界面地图锚点用）
try {
long stepT0 = System.currentTimeMillis();
long prevMs = lastStepT0 > 0 ? stepT0 - lastStepT0 : 0;
lastStepT0 = stepT0;
String stepFg = runner.fgPackage();
if (stepFg != null && !stepFg.isEmpty()) {
fgPkgVotes.put(stepFg, fgPkgVotes.getOrDefault(stepFg, 0) + 1);
}
Telemetry.stepEnv(ctx, channel, stepFg, netOk(),
prevMs, stepT0 - sessionStartMs);
} catch (Exception ignored) {}
// 用户实时反馈（App 通道）
try {
String fb = feedbackSupplier!= null? feedbackSupplier.get(): null;
if (fb!= null &&!fb.trim().isEmpty()) {
log("💬 用户补充：" + fb);
JSONObject fm = new JSONObject();
fm.put("role", "user");
fm.put("content", "用户补充：" + fb);
messages.put(fm);
}
} catch (Exception ignored) {
}

if (taskLogger!= null) taskLogger.stepStart(turn);
// v3.50 意图层：任务书钉住——每轮 user 消息首段重注（活在 messages 之外，防 compactMessages 挤掉判据）
StringBuilder roundObs = new StringBuilder(); // 本轮工具观察文本（停止线检查用）
if (taskBook != null && taskBook.valid()) {
try {
JSONObject pinMsg = new JSONObject();
pinMsg.put("role", "user");
pinMsg.put("content", taskBook.describe(dictShort));
messages.put(pinMsg);
} catch (Exception ignored) {
}
}
log("🤔 第 " + turn + " 轮思考中...");
LlmClient.ToolReply tr = llm.chatTools(messages, toolsForSession());
if (!tr.ok) {
if (tr.httpToolsRejected) {
log("⛔ 当前模型端点不支持工具调用（function calling），agent 无法执行。"
+ "请在「大模型配置」换一个支持工具调用的模型（如 DeepSeek 官方）。");
try {
org.json.JSONObject fd = new org.json.JSONObject();
fd.put("flag", "tools_rejected");
Telemetry.event(ctx, channel, "model_flag", fd);
} catch (Exception ignored) {
}
} else {
log("大脑请求失败：" + tr.error);
}
lastHistory = hist.toString();
return false;
}
appendAssistant(messages, tr);
if (tr.text!= null &&!tr.text.trim().isEmpty()) {
if (streamOutput()) log("💭 " + tr.text.trim());
if (taskLogger!= null) taskLogger.think(tr.text.trim());
hist.append("💭 ").append(tr.text.trim()).append("\n");
if (rewardGoal && engagedApp && !claimSeen && hasClaimEvidence(tr.text)) {
claimSeen = true;
claimTurn = turn;
log("🎯 检测到领取成功证据（执行者叙述），硬停止线已触发");
}
}

if (tr.calls.isEmpty()) {
// 模型给文本：有过实际动作=最终答复；零动作=口头完成，拦截
if (actionsDone > 0) {
String ans = tr.text == null? "": tr.text.trim();
if (!ans.isEmpty()) log("🤖 " + ans);
// v3.38：完成与否由监管者终审（看最终界面+截图裁定），不再由执行 agent 自证
boolean reviewConfirmed = false;
if (supervisorEnabled) {
Supervisor.Verdict fv = finalReview(goal, true, hist.toString());
if (fv != null && fv.ok && !fv.completed) {
if (supFinalRounds >= 2) {
log("⛔ 监管者终审多次判定未完成，任务结束");
if (taskLogger!= null) taskLogger.finish(false, "监管者终审未通过");
lastHistory = hist.toString();
return false;
}
supFinalRounds++;
log("🔍 监管者终审：尚未完成（约 " + fv.progressPct + "%）"
+ (fv.missing.isEmpty()? "": "，还缺：" + fv.missing));
try {
JSONObject rm = new JSONObject();
rm.put("role", "user");
rm.put("content", "【监管者终审】任务还没有完成。完成度约 "
+ fv.progressPct + "%。"
+ (fv.missing.isEmpty()? "": "还缺：" + fv.missing + "。")
+ (fv.nextAction.isEmpty()? "": "建议下一步：" + fv.nextAction + "。")
+ "不要重复已完成的步骤，只补缺失部分。");
messages.put(rm);
} catch (Exception ignored) {
}
continue;
}
if (fv != null && fv.ok) {
reviewConfirmed = true;
log("🔍 监管者终审：确认完成（约 " + fv.progressPct + "%）");
} else {
// v3.43：终审不可用不再回退自判放行——按未完成处理（如实告知）
log("⛔ 监管者终审不可用，本次完成未经核实，按未完成处理");
if (taskLogger!= null) taskLogger.finish(false, "终审不可用，未核实");
lastHistory = hist.toString();
return false;
}
}
if (mutationsDone == 0 && !reviewConfirmed) {
// v3.43 硬门槛：只有观察/空转、没有任何成功的状态改变操作，
// 且无监管终审背书 → 不准判完成（纯核实类任务靠终审确认放行）
zeroMutStrikes++;
if (zeroMutStrikes >= 2) {
log("⛔ 只有观察/空转、没有任何实际执行，任务未完成");
if (taskLogger!= null) taskLogger.finish(false, "无实际执行");
lastHistory = hist.toString();
return false;
}
log("⚠️ 还没有任何改变状态的实际操作，已提醒它先执行再下结论");
try {
JSONObject n = new JSONObject();
n.put("role", "user");
n.put("content", "系统记录显示你至今没有任何一次成功的状态改变操作"
+ "（tap/input/launch/shell 等），只有观察不算完成任务。"
+ "请实际执行；如果这个任务确实只需核实查看，"
+ "请在回复中明确说明你核实到的事实，等待监管者终审确认。");
messages.put(n);
} catch (Exception ignored) {
}
continue;
}
log("✅ 任务完成");
if (taskLogger!= null) {
taskLogger.finish(true, ans);
log("📝 任务日志已保存");
}
lastHistory = hist.toString();
return true;
}
textOnlyStrikes++;
try {
org.json.JSONObject fd = new org.json.JSONObject();
fd.put("flag", "text_only");
Telemetry.event(ctx, channel, "model_flag", fd);
} catch (Exception ignored) {
}
if (textOnlyStrikes >= 2) {
log("⛔ 模型两次只说不做，任务失败");
if (taskLogger!= null) taskLogger.finish(false, "只说不做");
lastHistory = hist.toString();
return false;
}
log("⚠️ 模型还没动手就想下结论，已提醒它实际执行");
try {
JSONObject n = new JSONObject();
n.put("role", "user");
n.put("content", "系统记录显示你还没有调用任何工具，口头完成无效。"
+ "请调用工具实际执行任务（需要看屏幕先调 observe），做完再回复。");
messages.put(n);
} catch (Exception ignored) {
}
continue;
}

// 执行本轮工具调用
boolean upsellSeen = false;
for (JSONObject call: tr.calls) {
if (listener!= null && listener.isStopped()) {
stoppedByUser = true;
log("已手动停止");
lastHistory = hist.toString();
return false;
}
String name = call.optString("name");
String argsJson = call.optString("argsJson", "{}");
String callId = call.optString("id");
log("🖐 " + name);
hist.append("🖐 ").append(name).append(" ").append(argsJson).append("\n");
boolean vdNow = taskPrimaryVd;
if (taskPrimaryVd && !VirtualDisplayManager.isActive()) {
// 主显示失效不回退实体屏：回退会让同一任务在双屏各留一份、后续点错屏。
log("⛔ 虚拟屏主显示已失效，任务中止（不回退实体屏，防双屏错乱）");
lastHistory = hist.toString();
return false;
}
// v3.51 步骤5：工具耗时（tool_result 用）
long toolT0 = System.currentTimeMillis();
PhoneTools.Exec ex = PhoneTools.execute(ctx, runner, name, argsJson, vdNow);
long toolMs = System.currentTimeMillis() - toolT0;
if (!vdNow && !"observe".equals(name)) touchedMain = true;
vdMode = vdNow;
if (!"observe".equals(name)) {
actionsDone++;
lastActionsDone = actionsDone;
}
if (ex.ok && isMutationTool(name)) {
// 说明型卡只回说明、没执行任何动作，不算状态改变（防冒充已执行）
boolean prose = "use_card".equals(name) && ex.text != null
&& ex.text.contains("说明型技能卡");
if (!prose) {
mutationsDone++;
lastMutationsDone = mutationsDone;
}
}
if (ex.ok && ("launch".equals(name) || "tap".equals(name)
|| "tap_text".equals(name) || "vision".equals(name)
|| "input".equals(name) || "swipe".equals(name))) engagedApp = true;
// v3.50：任务书停止线启用时，旧的领取证据/链条推销硬编码检测停用（由任务书通用停止线接管）
if (!useBookStop && rewardGoal && !"use_card".equals(name)) {
// use_card 返回的是卡片说明文本，含「领取成功」等教学措辞，扫它必误触发
if (!claimSeen && hasClaimEvidence(ex.text)) {
claimSeen = true;
claimTurn = turn;
log("🎯 检测到领取成功证据（界面/工具结果），硬停止线已触发");
}
if (hasUpsell(ex.text)) upsellSeen = true;
if (claimSeen && turn > claimTurn && (isMutationTool(name) || "sleep".equals(name))) postClaimActs++;
}
if (rewardGoal && "sleep".equals(name) && ex.ok) {
int secs = 0;
try {
secs = new JSONObject(argsJson).optInt("s", 0);
} catch (Exception ignored) {
}
if (secs >= 8) {
adWaits++;
log("📺 广告等待计数：" + adWaits + "/" + adBudget);
if (adWaits > adBudget) {
log("⛔ 广告预算超限：已等待 " + adWaits + " 次长广告，奖励链条可能无止境，任务中止（如今日奖励已到账，监管者终审会改判完成）");
if (taskLogger != null) taskLogger.finish(false, "广告预算超限（已看 " + adWaits + " 次广告）");
lastHistory = hist.toString();
return false;
}
}
}
// v3.50 意图层：累积本轮观察文本（停止线检查用，防过长裁剪）；
// 停止线触发后仍调状态改变工具/sleep 计数（与 p2 领取硬停止线同口径）
try {
if (ex.text != null && roundObs.length() < 4000) {
roundObs.append(ex.text.length() > 600 ? ex.text.substring(0, 600) : ex.text).append("\n");
}
} catch (Exception ignored) {
}
if (useBookStop && stopLineFired && turn > stopFireTurn
&& (isMutationTool(name) || "sleep".equals(name))) postStopActs++;
// v3.35 看门狗记录：动作签名/成败/结果摘要（监管介入时回放）
if (!"observe".equals(name)) {
String sig = name + "|" + argsJson.replaceAll("\\s+", "");
wdSigs.add(sig);
if (wdSigs.size() > 12) wdSigs.remove(0);
if (ex.ok) wdFailStreak = 0; else wdFailStreak++;
if ("tap_text".equals(name) && !ex.ok) {
try {
String tgt = new JSONObject(argsJson).optString("text", "");
if (!tgt.isEmpty()) {
Integer mc = wdTapMiss.get(tgt);
wdTapMiss.put(tgt, mc == null ? 1 : mc + 1);
}
} catch (Exception ignored) {}
}
}
if (!"observe".equals(name) && !"sleep".equals(name)) wdActed = true;
if ("observe".equals(name) && ex.shot != null) {
try {
long h = avgHash(ex.shot);
if (wdLastHash != 0) {
if (Long.bitCount(h ^ wdLastHash) <= 3) {
if (wdActed) wdSameScreen++;
} else {
wdSameScreen = 0;
}
}
wdLastHash = h;
wdActed = false;
android.graphics.Bitmap copy = android.graphics.Bitmap.createScaledBitmap(ex.shot, 360, 640, true);
if (supShot != null) supShot.recycle();
supShot = copy;
} catch (Exception ignored) {}
}
{
String rs = ex.text == null ? "" : ex.text.replace("\n", " ");
if (rs.length() > 80) rs = rs.substring(0, 80);
wdRecent.add(name + " → " + (ex.ok ? "成功" : "失败") + "：" + rs);
if (wdRecent.size() > 14) wdRecent.remove(0);
}
try {
JSONObject au = new JSONObject();
au.put("n", turn);
au.put("do", name);
au.put("ok", ex.ok);
au.put("risk", ActionRisk.levelFor(name).label);
auditSteps.add(au);
try {
org.json.JSONObject td = new org.json.JSONObject();
td.put("name", name);
td.put("ok", ex.ok);
if (!ex.ok) td.put("err", Telemetry.cut(ex.text, 90));
Telemetry.event(ctx, channel, "tool_call", td);
} catch (Exception ignored2) {
}
// v3.51 步骤5：工具结果打点——动作/成败/病因分类/画面变化量/耗时
// screen_delta：相邻两次工具截图的 8x8 均值哈希汉明距离（无截图时 -1）
try {
int screenDelta = -1;
String failClass = Telemetry.classifyFail(name, ex.ok, ex.text);
if (ex.shot != null) {
long h = avgHash(ex.shot);
if (toolLastHash != 0) screenDelta = (int) Long.bitCount(h ^ toolLastHash);
toolLastHash = h;
}
if (failClass != null) {
Integer c = runFailClass.get(failClass);
runFailClass.put(failClass, c == null ? 1 : c + 1);
}
Telemetry.toolResult(ctx, channel, name, ex.ok, failClass,
screenDelta, toolMs, ex.text);
} catch (Exception ignored3) {
}
} catch (Exception ignored) {
}
try {
JSONObject tm = new JSONObject();
tm.put("role", "tool");
tm.put("tool_call_id", callId);
tm.put("content", ex.text == null? "": ex.text);
messages.put(tm);
// observe 的截图以图片消息跟上（只留最新一帧，旧图剥除省 token）
if (ex.shot!= null) {
stripImages(messages);
JSONObject im = new JSONObject();
im.put("role", "user");
im.put("content", "observe 截图：");
im.put("image_b64", bitmapToJpegB64(ex.shot));
messages.put(im);
try { ex.shot.recycle();} catch (Exception ignored) {}
}
} catch (Exception ignored) {
}
}
// 硬停止线执行：领取证据已出现还继续操作 → 提醒收尾；再犯强制截停并终审裁定
if (claimSeen) {
try {
JSONObject hm = new JSONObject();
hm.put("role", "user");
hm.put("content", "【系统硬停止线】已有证据显示本次目标对象已领取成功。"
+ "『再看N个提前得明日/第X+1日』是目标外链条推销，禁止跟进。"
+ "现在只做收尾：关掉广告/弹窗（有『坚持退出』点它），然后用文字回复报告完成，"
+ "不要再看任何广告、不要再点任何奖励入口。");
messages.put(hm);
} catch (Exception ignored) {
}
if (postClaimActs >= 2) {
log("🛑 领取成功后执行者仍继续操作 " + postClaimActs + " 次，强制截停并终审");
Supervisor.Verdict cfv = supervisorEnabled ? finalReview(goal, false, hist.toString()) : null;
if (cfv == null || !cfv.ok || cfv.completed) {
if (taskLogger != null) taskLogger.finish(true, "已领取（领取成功证据截停）");
lastHistory = hist.toString();
return true;
}
log("🔍 终审认为尚未完成（" + cfv.progressPct + "%），撤销硬停止线，按终审意见继续");
claimSeen = false;
postClaimActs = 0;
try {
JSONObject rm2 = new JSONObject();
rm2.put("role", "user");
rm2.put("content", "【监管者终审】刚才的领取证据不构成目标完成。还缺："
+ cfv.missing + "。请只补缺失部分，不要重复已完成步骤。");
messages.put(rm2);
} catch (Exception ignored) {
}
}
}
if (upsellSeen && upsellWarns < 2) {
upsellWarns++;
log("🎯 检测到链条推销（再看N个提前得…），已提醒不跟进（第 " + upsellWarns + " 次）");
try {
JSONObject um = new JSONObject();
um.put("role", "user");
um.put("content", "【系统提示】界面出现『再看N个视频/广告提前得明日/下一日奖励』——这是目标外链条推销。"
+ "不要点『再看』继续看；点『坚持退出』或关闭弹窗。"
+ "若本次目标对象还没领取，回到正常领取流程；已领取就收尾报告。");
messages.put(um);
} catch (Exception ignored) {
}
}
// v3.50 意图层：任务书通用停止线命中 → 注入收尾要求；
// 触发后仍调工具≥2 次 → 强制截停并终审（p2 领取硬停止线的一般化；旧硬编码在 book 缺失时兜底）
if (useBookStop && !stopLineFired) {
String fired = null;
try {
String h = hist.toString();
fired = taskBook.checkStop(roundObs.toString(),
h.length() > 800 ? h.substring(h.length() - 800) : h);
} catch (Exception ignored) {
}
if (fired != null && !fired.isEmpty()) {
stopLineFired = true;
stopFireTurn = turn;
stopLineAction = fired;
log("🛑 任务书停止线触发：" + fired);
try {
JSONObject sm = new JSONObject();
sm.put("role", "user");
sm.put("content", "【任务书停止线】已触发——" + fired
+ "。按任务书要求立即收尾：用文字回复报告结果，不要再调用任何工具。");
messages.put(sm);
} catch (Exception ignored) {
}
}
}
if (stopLineFired && postStopActs >= 2) {
log("🛑 任务书停止线触发后仍继续操作 " + postStopActs + " 次，强制截停并终审");
Supervisor.Verdict cfv = supervisorEnabled ? finalReview(goal, false, hist.toString()) : null;
if (cfv == null || !cfv.ok || cfv.completed) {
if (taskLogger != null) taskLogger.finish(true, "任务书停止线截停：" + stopLineAction);
lastHistory = hist.toString();
return true;
}
log("🔍 终审认为尚未完成（" + cfv.progressPct + "%），撤销停止线，按终审意见继续");
stopLineFired = false;
postStopActs = 0;
try {
JSONObject rm3 = new JSONObject();
rm3.put("role", "user");
rm3.put("content", "【监管者终审】刚才的停止线信号不构成目标完成。还缺："
+ cfv.missing + "。请只补缺失部分，不要重复已完成步骤。");
messages.put(rm3);
} catch (Exception ignored) {
}
}
// 步骤6：判据分支收尾提醒——任务书某判据分支关键词已命中最近工具结果时，软提醒一次"可收尾"（不截停）
if (useBookStop && !stopLineFired && taskBook != null) {
try {
String hit = taskBook.branchHit(roundObs.toString());
if (hit != null && !bookBranchReminded.contains(hit)) {
bookBranchReminded.add(hit);
log("💡 任务书判据分支「" + hit + "」已有证据，可考虑收尾（提醒一次）");
JSONObject bm = new JSONObject();
bm.put("role", "user");
bm.put("content", "【任务书收尾提醒】判据分支「" + hit + "」已有证据（最近工具结果出现「" + hit + "」）。"
+ "如果目标已达成，直接收尾报告；还差其他判据分支请继续，不要重复已满足的步骤。");
messages.put(bm);
}
} catch (Exception ignored) {
}
}
// v3.35 监管看门狗：有异常信号才触发最强模型裁决，无信号零打扰
if (supervisorEnabled) {
String trigger = null;
if (wdCooldown > 0) {
wdCooldown--;
} else {
java.util.Map<String, Integer> cnt = new java.util.HashMap<>();
for (String sg : wdSigs) {
Integer c = cnt.get(sg);
cnt.put(sg, c == null ? 1 : c + 1);
}
for (java.util.Map.Entry<String, Integer> e : cnt.entrySet()) {
if (e.getValue() >= 3) { trigger = "疑似死循环：动作「" + e.getKey() + "」已重复 " + e.getValue() + " 次"; break; }
}
if (trigger == null && wdSigs.size() >= 6) {
int n = wdSigs.size();
String sa = wdSigs.get(n - 6), sb2 = wdSigs.get(n - 5);
if (!sa.equals(sb2) && sa.equals(wdSigs.get(n - 4)) && sb2.equals(wdSigs.get(n - 3))
&& sa.equals(wdSigs.get(n - 2)) && sb2.equals(wdSigs.get(n - 1))) {
trigger = "疑似死循环：两个动作交替重复（" + sa + " / " + sb2 + "）";
}
}
if (trigger == null && wdFailStreak >= 4) {
trigger = "陷入困境：已连续 " + wdFailStreak + " 次工具调用失败";
}
if (trigger == null && wdSameScreen >= 2) {
trigger = "无进展：连续多次操作后界面几乎没有变化（动作不起作用或在原地打转）";
}
if (trigger == null) {
for (java.util.Map.Entry<String, Integer> e : wdTapMiss.entrySet()) {
if (e.getValue() >= 2) { trigger = "疑似幻觉：反复查找界面上不存在的文本「" + e.getKey() + "」（已失败 " + e.getValue() + " 次）"; break; }
}
}
if (trigger == null && turn - wdLastCheckTurn >= 10) {
trigger = "例行复核（已执行 " + turn + " 轮）";
}
}
if (trigger != null) {
wdLastCheckTurn = turn;
if (wdInterventions >= 3) {
log("🛡 监管者已介入 3 次仍无改善，任务结束：" + trigger);
// v3.51 步骤5：监管中止打点
try {
Telemetry.supervisory(ctx, channel, "watchdog", "abort", trigger);
} catch (Exception ignored) {}
if (taskLogger != null) taskLogger.finish(false, "监管多次介入无改善");
lastHistory = hist.toString();
return false;
}
wdInterventions++;
LlmClient sup = supervisorClient != null ? supervisorClient : llm;
StringBuilder rb = new StringBuilder();
for (String line : wdRecent) rb.append(line).append("\n");
String histAll = hist.toString();
String thoughts = histAll.length() > 1500 ? histAll.substring(histAll.length() - 1500) : histAll;
Supervisor.Intervention iv = Supervisor.intervene(sup, goal, trigger, rb.toString(), thoughts, supShot);
try {
org.json.JSONObject fd = new org.json.JSONObject();
fd.put("flag", "supervisor");
fd.put("trigger", Telemetry.cut(trigger, 60));
fd.put("action", iv.ok ? iv.action : "unavailable");
Telemetry.event(ctx, channel, "model_flag", fd);
} catch (Exception ignored) {}
// v3.51 步骤5：监管事件打点（规则/裁决/依据）
try {
String verdict = !iv.ok ? "unavailable" : iv.action;
Telemetry.supervisory(ctx, channel, "watchdog", verdict,
trigger + " | " + (iv.ok ? iv.message : "监管者不可用"));
} catch (Exception ignored2) {}
if (!iv.ok) {
log("🛡 监管者暂时不可用（" + trigger + "），继续执行");
wdCooldown = 8;
} else if ("abort".equals(iv.action)) {
log("🛡 监管者判定中止：" + iv.message);
if (taskLogger != null) taskLogger.finish(false, "监管者中止：" + iv.message);
lastHistory = hist.toString();
return false;
} else if ("guide".equals(iv.action)) {
log("🛡 监管者介入：" + iv.message);
try {
JSONObject gm = new JSONObject();
gm.put("role", "user");
gm.put("content", "【监管者介入】" + iv.message + "\n请按这个指引调整做法，不要重复刚才的失败路径。");
messages.put(gm);
} catch (Exception ignored) {}
wdCooldown = 8;
} else {
log("🛡 监管者复核：无异常，继续（" + trigger + "）");
wdCooldown = 8;
}
wdSigs.clear();
wdTapMiss.clear();
wdFailStreak = 0;
wdSameScreen = 0;
}
}
// 上下文防膨胀：回合过多时压缩中段（保留 system+任务+最近 24 条）
compactMessages(messages);
}
log("⏹ 轮次用尽（" + maxSteps + "），任务未完成");
if (taskLogger!= null) taskLogger.finish(false, "轮次用尽");
lastHistory = hist.toString();
return false;
}

/** 本次会话的工具表：高级工具（shell/文件/网络）需 allowAdvanced */
private JSONArray toolsForSession() {
JSONArray all = PhoneTools.schemas();
if (allowAdvanced) return all;
JSONArray out = new JSONArray();
try {
for (int i = 0; i < all.length(); i++) {
JSONObject w = all.getJSONObject(i);
String n = w.getJSONObject("function").optString("name");
if (n.equals("shell") || n.equals("read_file") || n.equals("write_file")
|| n.equals("edit_file")
|| n.equals("fetch_url") || n.equals("search")) continue;
out.put(w);
}
} catch (Exception e) {
return all;
}
return out;
}

/** system prompt：只有环境与能力（DSH 风格，零流程规则） */
private String sessionSystemPrompt(String goal) {
StringBuilder p = new StringBuilder();
p.append("你是「GSIC」，在一台安卓手机上自主办事。\n");p.append(TermsGate.CORE_TEXT);
boolean hasRoot = checkRootOnce();
boolean a11yOn = AgentAccessibilityService.isRunning();
p.append("环境：").append(android.os.Build.MANUFACTURER).append(" ")
.append(android.os.Build.MODEL)
.append("，Android ").append(android.os.Build.VERSION.RELEASE)
.append("；root ").append(hasRoot? "有": "无")
.append("；无障碍 ").append(a11yOn? "已开": "未开").append("。");
if (hasRoot) p.append("截屏与点击有 root 通道兜底。");
if (taskPrimaryVd) {
p.append("本任务主显示锁定为虚拟屏：全部操作只在虚拟屏进行，不打扰实体屏；实体屏右上角小窗只是实时预览（从显示），不承载任务。任务结束前不能切到实体屏（vd_stop 会被拒绝），不要尝试。确需实体屏当前状态的活，请收尾时在结论里说明、另起一个实体屏任务。");
} else {
p.append("本任务主显示锁定为实体屏：直接在实体屏操作，不要尝试开虚拟屏（vd_start 会被拒绝）。");
}
p.append("先理解用户真正要什么再动手：结合对话上下文和当前界面理解指代与言外之意，按目标推进，不要逐字死磕字面步骤；此路不通时换方法继续朝目标走。");
p.append("\n你通过工具看屏和操作（执行通道 root 优先，无 root 才用无障碍）。完成任务后，用一段文字回复用户结果（不再调工具即结束）。");
// v3.57 本地编程能力：编程优先——改软件先找配置/数据文件，能写脚本别手点
p.append("改软件/系统层时：先用 read_file 找它的配置与数据文件（/data/data/<包名>/shared_prefs/*.xml、databases、files），读懂再改；能改文件或写脚本（termux 里 python3/bash）解决的，不要进界面手点；改完用 shell/termux 查状态验证，不要只看界面。");
p.append("领取/奖励类任务的停止线：界面出现『领取成功/已领取』就代表本次目标对象已到账，立即收尾报告；『再看N个提前得明日/下一份』是 App 的链条推销，属于目标外，一律不跟进，点『坚持退出』退出。");
// v3.59 步骤4：技能卡修卡规程——调卡失败后先对地图、再就地修卡（SPEC：修卡走草稿→复验→转正）
p.append("技能卡修卡规程（use_card 返回失败时执行，不要盲目重调同一张卡）："
+ "①先读上条工具结果里附带的[界面地图·供修卡参考]，对照界面地图/图标词典找出卡片失效的步骤与病因（如控件文本变了、入口位置挪了）；"
+ "②用 repair_card 只传 id 读出该卡的当前内容与失败记录，再用 repair_card(id+修好的内容全文+病因 note) 就地修卡——只改失效的步骤，不要重写整张卡；"
+ "③修卡后自动打回草稿：本次或之后再调卡成功一次即自动转正；若暂无复验条件，在结论里注明『已修卡、待复验』；"
+ "④同一张卡连续两次修卡仍失败就停手，在结论里写明病因交用户/开发处理，不要反复试错；"
+ "⑤若修卡后复验仍失败、或新版不如旧版，可用 repair_card(id+rollback=<版本>) 回滚到历史快照（只传 id 读卡时返回版本列表），回滚后同样打回草稿待复验。");

// 学习梯子步骤1：不会就先学，按梯子爬（查卡→搜两轮→问用户），不硬跑
p.append(LearningLadder.ladderPrompt());
if (cogStore != null) {
String icons = cogStore.iconBlock(null);
if (!icons.isEmpty()) p.append("\n").append(icons);
}
String cards = skillCardsSection(goal);
if (!cards.isEmpty()) p.append("\n").append(cards);
if (!conversationContext.isEmpty()) {
p.append("\n").append(conversationContext);
}
try {
String mem = UserMemory.describeForPrompt(ctx);
if (!mem.isEmpty()) p.append("\n").append(mem);
} catch (Exception ignored) {
}
return p.toString();
}

private String skillCardsSection(String goal) {
try {
String d = new SkillCardStore(ctx).describeForPrompt(goal);
if (d.isEmpty()) return "";
return "可用技能卡（use_card 调用）：\n" + d + "\n";
} catch (Exception e) {
return "";
}
}

// ---- messages 小工具 ----

private void appendAssistant(JSONArray messages, LlmClient.ToolReply tr) {
try {
JSONObject am = new JSONObject();
am.put("role", "assistant");
am.put("content", tr.text == null? "": tr.text);
if (!tr.calls.isEmpty()) {
JSONArray tcs = new JSONArray();
for (JSONObject call: tr.calls) {
JSONObject fn = new JSONObject();
fn.put("name", call.optString("name"));
fn.put("arguments", call.optString("argsJson", "{}"));
JSONObject tc = new JSONObject();
tc.put("id", call.optString("id"));
tc.put("type", "function");
tc.put("function", fn);
tcs.put(tc);
}
am.put("tool_calls", tcs);
}
messages.put(am);
} catch (Exception ignored) {
}
}

private void stripImages(JSONArray messages) {
try {
for (int i = 0; i < messages.length(); i++) {
JSONObject m = messages.getJSONObject(i);
if (m.has("image_b64")) m.remove("image_b64");
}
} catch (Exception ignored) {
}
}

private void compactMessages(JSONArray messages) {
try {
final int LIMIT = 40, KEEP = 26;
if (messages.length() <= LIMIT) return;
JSONArray kept = new JSONArray();
for (int i = 0; i < 2 && i < messages.length(); i++) {
kept.put(messages.getJSONObject(i));
}
// 中段被压缩的回合给一条占位说明，避免 tool_call/result 断裂：从 assistant 边界切
int start = messages.length() - KEEP;
while (start < messages.length()
&&!"assistant".equals(messages.getJSONObject(start).optString("role"))) {
start++;
}
JSONObject note = new JSONObject();
note.put("role", "user");
note.put("content", "（较早的若干轮已省略，任务状态以最近消息为准）");
kept.put(note);
for (int i = start; i < messages.length(); i++) {
kept.put(messages.getJSONObject(i));
}
while (messages.length() > 0) messages.remove(0);
for (int i = 0; i < kept.length(); i++) messages.put(kept.getJSONObject(i));
} catch (Exception ignored) {
}
}

/** 领取成功证据（硬停止线）：只认明确的完成态措辞 */
private static boolean hasClaimEvidence(String s) {
if (s == null) return false;
return s.contains("领取成功") || s.contains("已领取") || s.contains("领取完成");
}

/** 链条推销信号：「再看N个提前得明日/第X+1日」类目标外诱导 */
private static boolean hasUpsell(String s) {
if (s == null) return false;
return s.contains("提前得") || s.contains("提前解锁") || s.contains("提前获得")
|| (s.contains("再看") && s.contains("提前"));
}

/** v3.43：状态改变类工具（完成硬门槛只认这些的成功调用） */
private static boolean isMutationTool(String n) {
return "tap".equals(n) || "tap_text".equals(n) || "input".equals(n)
|| "launch".equals(n) || "shell".equals(n) || "termux".equals(n)
|| "swipe".equals(n) || "back".equals(n) || "key".equals(n)
|| "home".equals(n) || "write_file".equals(n) || "use_card".equals(n)
|| "vd_start".equals(n) || "vd_stop".equals(n);
}

/** v3.38：监管者终审（最终控件树 + 最新截图；不可用返回 null） */
private Supervisor.Verdict finalReview(String goal, boolean workerSaid,
String history) {
try {
String screenText = "";
if (taskPrimaryVd) {
// 主从互斥：虚拟屏任务的终审只认虚拟屏截图，实体屏控件树是另一个屏的状态，混入会误判
screenText = "（虚拟屏主显示任务：实体屏控件树不适用，以附带的虚拟屏截图为唯一界面证据）";
} else {
try {
java.util.List<AgentAccessibilityService.NodeInfo> nodes =
AgentAccessibilityService.dumpScreen();
if (nodes != null && !nodes.isEmpty()) screenText = serialize(nodes);
} catch (Exception ignored) {
}
if (screenText.isEmpty()) screenText = "（无控件树信息，见截图）";
}
LlmClient sup = supervisorClient != null? supervisorClient: llm;
// v3.50 意图层：终审判据绑定——把任务书传给监管者，只按任务书完成判据裁决
Supervisor.Verdict v = Supervisor.review(sup, goal, workerSaid,
history == null? "": history, screenText, supShot, taskBook);
// v3.43：终审结果打点——区分终审判错与终审缺席（旧版遥测分不清）
try {
org.json.JSONObject fd = new org.json.JSONObject();
fd.put("flag", "final_review");
fd.put("result", v == null? "null": (!v.ok? "error":
(v.completed? "pass": "reject")));
if (v != null) fd.put("pct", v.progressPct);
Telemetry.event(ctx, channel, "model_flag", fd);
} catch (Exception ignored) {
}
// v3.51 步骤5：终审裁决打点（规则/裁决/依据）
try {
String verdict = v == null ? "null"
: (!v.ok ? "error" : (v.completed ? "pass" : "reject"));
String basis = "完成度约" + (v == null ? "?" : v.progressPct + "%")
+ (v != null && !v.missing.isEmpty() ? "，还缺：" + v.missing : "");
Telemetry.supervisory(ctx, channel, "final_review", verdict, basis);
} catch (Exception ignored2) {
}
return v;
} catch (Exception e) {
return null;
}
}

private static String bitmapToJpegB64(android.graphics.Bitmap bmp) {
try {
android.graphics.Bitmap use = bmp;
if (bmp.getWidth() > 720) {
use = android.graphics.Bitmap.createScaledBitmap(bmp, 720,
(int) (bmp.getHeight() * 720.0 / bmp.getWidth()), true);
}
java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
use.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, bos);
return android.util.Base64.encodeToString(bos.toByteArray(),
android.util.Base64.NO_WRAP);
} catch (Exception e) {
return "";
}
}

// ---- 静态工具（MainActivity 等沿用） ----

/** 控件树序列化：[编号] 文字 (可点) 每行一条 */
static String serialize(List<AgentAccessibilityService.NodeInfo> nodes) {
StringBuilder sb = new StringBuilder();
for (int i = 0; i < nodes.size(); i++) {
AgentAccessibilityService.NodeInfo n = nodes.get(i);
String t = n.text.isEmpty()? n.desc: n.text;
t = t.replace('\n', ' ').trim();
if (t.isEmpty()) continue;
if (t.length() > 24) t = t.substring(0, 24);
sb.append('[').append(i).append("] ").append(t);
if (n.clickable) sb.append(" (可点)");
sb.append('\n');
}
return sb.toString();
}

/** 从模型文本中抠出第一个 JSON 对象（旧通道零散调用沿用） */
static JSONObject extractJson(String text) {
if (text == null) return null;
int a = text.indexOf('{');
int b = text.lastIndexOf('}');
if (a < 0 || b <= a) return null;
try {
return new JSONObject(text.substring(a, b + 1));
} catch (Exception e) {
return null;
}
}

private static void sleepQuiet(long ms) {
try {
Thread.sleep(ms);
} catch (InterruptedException e) {
Thread.currentThread().interrupt();
}
}

// ---------------- v3.51 步骤5：遥测打点辅助 ----------------

/**
 * 经验备注：失败任务附带本轮最高频病因（步骤5 遥测回填，供 Distiller 读 note 时参考）。
 * ok 的任务备注为空（与旧口径一致）。
 */
private String expNote(boolean ok) {
if (ok) return "";
String top = topFailClass();
return top == null ? "未完成" : "未完成（主因：" + top + "）";
}

/** 本任务内最高频 fail_class，无失败返回 null */
private String topFailClass() {
String best = null;
int bestN = 0;
for (java.util.Map.Entry<String, Integer> e : runFailClass.entrySet()) {
if (e.getValue() > bestN) {
bestN = e.getValue();
best = e.getKey();
}
}
return best;
}

/** step_env 网络字段：ConnectivityManager 读当前连接态，零网络 I/O */
private boolean netOk() {
try {
android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
return ni != null && ni.isConnected();
} catch (Exception e) {
return false;
}
}
}
