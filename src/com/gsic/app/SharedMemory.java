package com.gsic.app;

import android.content.Context;

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
* v3.44 一份共享记忆（用户定调：一个智能体、三种人格）。
* App / Telegram / 远控 共用同一份：
* 1) 对话轮次（shared_rounds.json）——当前会话的轮次；
* 2) 任务经验（experience.json）——蒸馏原料队列（步骤2 起不再做长期注入源）。
*
* v3.55 记忆分层·步骤2（设计稿 v351-design.md 2.2/2.3；规格 SPEC 第四节）：
* 短期记忆只活当前对话，新会话清空；长期记忆的唯一形态是
* cognition.json（CognitionStore）。经验队列每次任务结局入队后就地走
* Distiller 规则蒸馏（零 token）：蒸馏出的候选进 cognition.json draft
*（votes>=2 转正），蒸馏不出的直接丢弃。任务流水禁止入库。
* 会话边界（三端独立，各人格短期不互串；长期认知三端共享）——步骤7 已接线（v3.56）：
* App：MainActivity 新会话入口或显式 /new -> clear()；
* TG：/new 或连续 30 分钟无消息 -> clear()；
* 远控：每次任务结束且客户端断开 -> clear()。
* （v3.56 落地：TG 加 30 分钟无消息自动新会话；远控 goal 任务结束清，待澄清不清；
* App 的 /clear 与新对话按钮此前已接。）
*/
public class SharedMemory {

private static final int MAX_ROUNDS = 30;
/** 蒸馏原料队列上限（步骤2 起 200->50：只保留近期结局供蒸馏与"重复出现"判定） */
private static final int MAX_EXP = 50;

// ---------------- 对话轮次 ----------------

public static synchronized void addRound(Context ctx, String ch,
String goal, String result) {
try {
JSONObject root = read(ctx, "shared_rounds.json");
JSONArray arr = root.optJSONArray("rounds");
if (arr == null) arr = new JSONArray();
JSONObject r = new JSONObject();
r.put("ts", System.currentTimeMillis());
r.put("ch", ch);
r.put("goal", trim(goal, 80));
r.put("result", trim(result, 120));
arr.put(r);
while (arr.length() > MAX_ROUNDS) arr.remove(0);
root.put("rounds", arr);
write(ctx, "shared_rounds.json", root);
} catch (Exception ignored) {
}
}

/** v3.45：三端共用的结果尾巴——答案/明细截 60 字，超长加省略号。 */
public static String tail60(String s) {
if (s == null || s.isEmpty()) return "";
return s.length() > 60 ? s.substring(0, 60) + "…" : s;
}

/** 最近 N 轮拼成上下文文本；没有返回空串。 */
public static synchronized String recentText(Context ctx, int maxRounds) {
try {
JSONObject root = read(ctx, "shared_rounds.json");
JSONArray arr = root.optJSONArray("rounds");
if (arr == null || arr.length() == 0) return "";
StringBuilder sb = new StringBuilder();
sb.append("\n");
int from = Math.max(0, arr.length() - maxRounds);
for (int i = from; i < arr.length(); i++) {
JSONObject r = arr.getJSONObject(i);
sb.append("用户：").append(r.optString("goal")).append("\n");
sb.append("助手：").append(r.optString("result")).append("\n");
}
return sb.toString().trim();
} catch (Exception e) {
return "";
}
}

/** 当前会话清空：步骤7 按三端会话边界接线（见类注释）。
* App 新会话入口或显式 /new；TG 的 /new 或 30 分钟无消息；
* 远控每次任务结束且客户端断开。三端任一通道新开对话，共享轮次一并清。 */
public static synchronized void clear(Context ctx) {
try {
JSONObject root = new JSONObject();
root.put("rounds", new JSONArray());
write(ctx, "shared_rounds.json", root);
} catch (Exception ignored) {
}
}

// ---------------- 任务经验 ----------------

/**
* 任务结局入蒸馏原料队列（步骤2 起不再做长期注入源）。
* 旧调用方签名不变，userInterrupted 默认 false。
* @see #addExperience(Context, String, String, boolean, long, int, String, boolean)
*/
public static synchronized void addExperience(Context ctx, String ch,
String goal, boolean ok,
long ms, int tools,
String note) {
addExperience(ctx, ch, goal, ok, ms, tools, note, false);
}

/**
* 8 参数版：userInterrupted 由调用方在"用户中途纠正/叫停"时置 true
*（遥测·步骤5/7 回填 failClass 与中断信号）。
*/
public static synchronized void addExperience(Context ctx, String ch,
String goal, boolean ok,
long ms, int tools,
String note, boolean userInterrupted) {
try {
JSONObject root = read(ctx, "experience.json");
JSONArray arr = root.optJSONArray("items");
if (arr == null) arr = new JSONArray();
long now = System.currentTimeMillis();
String g = trim(goal, 80);
// 10 分钟内同目标同结局的重试只更新最新一条，不刷屏
if (arr.length() > 0) {
JSONObject last = arr.getJSONObject(arr.length() - 1);
if (g.equals(last.optString("goal"))
&& ok == last.optBoolean("ok")
&& now - last.optLong("ts") < 10 * 60_000L) {
last.put("ts", now);
last.put("ms", ms);
last.put("tools", tools);
root.put("items", arr);
write(ctx, "experience.json", root);
return;
}
}
JSONObject it = new JSONObject();
it.put("ts", now);
it.put("ch", ch);
it.put("goal", g);
it.put("ok", ok);
it.put("ms", ms);
it.put("tools", tools);
it.put("note", trim(note, 60));
arr.put(it);
while (arr.length() > MAX_EXP) arr.remove(0);
root.put("items", arr);
write(ctx, "experience.json", root);
// 记忆分层步骤2：就地蒸馏（零 token 纯规则）。蒸馏出的候选进
// cognition.json（draft/votes>=2 转正），蒸馏不出的直接丢弃。
try {
Distiller.TaskRec rec = new Distiller.TaskRec();
rec.ch = ch == null ? "" : ch;
rec.goal = g;
rec.ok = ok;
rec.ms = ms;
rec.tools = tools;
rec.note = trim(note, 60);
rec.userInterrupted = userInterrupted;
List<Distiller.TaskRec> recents = new ArrayList<>();
for (int i = 0; i < arr.length(); i++) {
JSONObject o = arr.optJSONObject(i);
if (o == null) continue;
Distiller.TaskRec r = new Distiller.TaskRec();
r.goal = o.optString("goal", "");
recents.add(r);
}
CognitionStore store = CognitionStore.load(ctx.getFilesDir());
if (Distiller.distill(rec, store, recents, ctx.getFilesDir())) {
store.save(ctx.getFilesDir());
}
} catch (Exception ignored) {
}
} catch (Exception ignored) {
}
}

/**
* v3.58 步骤2：Distiller 技能卡草稿队列消费 -> 成功轨迹自动沉淀为草稿卡。
* 调用点：AgentLoop.run() 任务成功结局后（distill 已有调用点 addExperience 之后）。
* 规则（设计稿 v358-design.md 3.2；SPEC 第三节技能卡自维护）：
* - 只沉淀成功轨迹（ok=false 直接返回 null）；
* - 候选 votes>=2 才沉淀（重复出现，防单次偶然成功污染好卡）；
* - 沉淀为草稿卡（status=draft），同类任务下次调卡成功一次才转正；
* - 已有同类卡（卡名 bigram Jaccard>=0.6）时跳过，防重复沉淀；
* - content=本轮步骤轨迹（上限 4000 字），summary=结局摘要+复现次数，
*   appPkg=本轮主前台包名（界面地图联动锚点，步骤3 用）。
* 三端横扫：SkillCardStore 是共享层（files/ 目录三端共用），草稿卡三端可见；
* AgentLoop/TgBot/远控的任务结局都走本方法（AgentLoop.run 统一调用点）。
* @return 新草稿卡 id；无沉淀返回 null
*/
public static synchronized String maybeDraftSkillCard(Context ctx, String goal,
boolean ok, String steps, String mainPkg, String note) {
try {
if (ctx == null || !ok) return null;
String g = goal == null ? "" : goal.trim();
if (g.isEmpty()) return null;
File dir = ctx.getFilesDir();
JSONObject cand = Distiller.consumeDraftCandidate(dir, g);
if (cand == null) return null;
int votes = cand.optInt("votes", 2);
SkillCardStore store = new SkillCardStore(ctx);
if (hasSimilarCard(store.listByPriority(), g)) return null;
String name = g.length() > 40 ? g.substring(0, 40) + "…" : g;
java.util.List<String> kws =
new java.util.ArrayList<>(SkillCardStore.keywords(g));
java.util.Collections.sort(kws);
String trigger = joinWith(kws, " ");
String content = steps == null ? "" : steps;
if (content.length() > CARD_STEPS_CAP) {
content = content.substring(0, CARD_STEPS_CAP) + "…";
}
String summary = note == null ? "" : note.trim();
if (summary.length() > 120) summary = summary.substring(0, 120) + "…";
if (!summary.isEmpty()) summary += "；";
summary += "同类任务重复出现 " + votes
+ " 次，成功轨迹自动沉淀为草稿卡（待同类复验成功后转正）";
String pkg = mainPkg == null ? "" : mainPkg.trim();
return store.addDraft(name, SkillCardStore.TYPE_SKILL,
SkillCardStore.SRC_AGENT, trigger, content, summary, pkg);
} catch (Exception e) {
return null;
}
}

/** 草稿卡 content（步骤轨迹）上限：防单次超长轨迹膨胀主文件 */
private static final int CARD_STEPS_CAP = 4000;

private static String joinWith(java.util.List<String> list, String sep) {
StringBuilder sb = new StringBuilder();
for (String s : list) {
if (s == null || s.isEmpty()) continue;
if (sb.length() > 0) sb.append(sep);
sb.append(s);
}
return sb.toString();
}

/**
* 已有同类卡判定（防重复沉淀）：卡名与目标 bigram Jaccard>=0.6
* 视为同类（与 Distiller.addOrVote 同口径）。纯函数，可独立测试。
*/
static boolean hasSimilarCard(List<SkillCardStore.Card> cards, String goal) {
if (cards == null || goal == null) return false;
Set<String> mine = bigrams(goal);
if (mine.isEmpty()) return false;
for (SkillCardStore.Card c : cards) {
if (c == null || c.name == null) continue;
if (jaccard(mine, bigrams(c.name)) >= 0.6) return true;
}
return false;
}

/** 按目标相似度找最相关的认知注入；无返回空串。
* 记忆分层步骤3：experience.json 停做注入源（只保留为蒸馏原料队列，
* 见 addExperience），相关认知改走长期认知库 CognitionStore.relevant：
* 对 active 条目 bigram Jaccard>=0.15 取最相关 2 条。"内部参考"头保留
* （注入只进任务上下文，禁入最终答案）。签名不变，调用方无需改动。 */
public static synchronized String relevantExperience(Context ctx,
String goal) {
try {
if (ctx == null || goal == null) return "";
CognitionStore store = CognitionStore.load(ctx.getFilesDir());
return store.relevant(goal);
} catch (Exception e) {
return "";
}
}

/**
 * 云端操控沉淀（用户 2026-10-22 亲定跨通道复现）：远控旁聊以单步操控
 * 完成指令后，把成功/失败做法以 JSONL 追加到 files/cloud_ops.jsonl；
 * 这里按目标相似度取最相关的 2 条拼成注入文本，让 TG/App 任务直接
 * 复现云端趟通的路（失败条目带尝试与结果，避免重复踩坑）。
 */
public static synchronized String relevantCloudOps(Context ctx,
String goal) {
if (ctx == null) return "";
return relevantCloudOpsIn(ctx.getFilesDir(), goal);
}

/** File 版核心（便于 JVM 单测）：从目录读 cloud_ops.jsonl 按相似度取做法 */
public static synchronized String relevantCloudOpsIn(java.io.File dir,
String goal) {
try {
if (dir == null || goal == null || goal.isEmpty()) return "";
java.io.File f = new java.io.File(dir, "cloud_ops.jsonl");
if (!f.exists() || f.length() == 0) return "";
byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
String[] lines = new String(data, "UTF-8").split("\n");
Set<String> mine = bigrams(goal);
if (mine.isEmpty()) return "";
List<double[]> scored = new ArrayList<>();
List<JSONObject> items = new ArrayList<>();
int from = Math.max(0, lines.length - 200); // 只看最近 200 条
for (int i = from; i < lines.length; i++) {
String ln = lines[i].trim();
if (ln.isEmpty()) continue;
try {
JSONObject o = new JSONObject(ln);
Set<String> theirs = bigrams(o.optString("goal"));
if (theirs.isEmpty()) continue;
int inter = 0;
for (String g : mine) if (theirs.contains(g)) inter++;
double j = (double) inter / (mine.size() + theirs.size() - inter);
if (j >= 0.15) {
scored.add(new double[]{j, items.size()});
items.add(o);
}
} catch (Exception ignored) {
}
}
if (scored.isEmpty()) return "";
scored.sort((a, b) -> Double.compare(b[0], a[0]));
StringBuilder sb = new StringBuilder();
sb.append("【内部参考·勿向用户复述：以下为远控旁聊单步操控沉淀的做法，"
+ "同类指令优先按成功做法复现；失败做法写明了坑与替代尝试，"
+ "别重复踩。最终回答用户时不要引用这段内容。】\n");
int n = Math.min(2, scored.size());
for (int k = 0; k < n; k++) {
JSONObject o = items.get((int) scored.get(k)[1]);
boolean ok = o.optBoolean("ok", true);
sb.append("- 「").append(o.optString("goal")).append("」")
.append(ok ? "已走通" : "曾失败").append("：")
.append(o.optString("approach"));
String notes = o.optString("notes");
if (!notes.isEmpty()) sb.append("（注意：").append(notes).append("）");
sb.append("\n");
}
return sb.toString().trim();
} catch (Exception e) {
return "";
}
}

// ---------------- 工具 ----------------

private static String chName(String ch) {
if ("tg".equals(ch)) return "Telegram";
if ("remote".equals(ch)) return "远控";
if ("app".equals(ch)) return "App";
return ch;
}

private static Set<String> bigrams(String s) {
Set<String> out = new HashSet<>();
if (s == null) return out;
String t = s.replaceAll("[\\s\\p{P}]", "");
if (t.length() < 2) {
if (!t.isEmpty()) out.add(t);
return out;
}
for (int i = 0; i + 2 <= t.length(); i++) out.add(t.substring(i, i + 2));
return out;
}

private static double jaccard(Set<String> a, Set<String> b) {
if (a.isEmpty() || b.isEmpty()) return 0.0;
int inter = 0;
for (String s : a) if (b.contains(s)) inter++;
int union = a.size() + b.size() - inter;
return union == 0 ? 0.0 : (double) inter / union;
}

private static String trim(String s, int max) {
if (s == null) return "";
s = s.trim();
return s.length() > max? s.substring(0, max) + "…": s;
}

private static JSONObject read(Context ctx, String name) {
try {
File f = new File(ctx.getFilesDir(), name);
if (!f.exists()) return new JSONObject();
FileInputStream in = new FileInputStream(f);
byte[] buf = new byte[(int) f.length()];
int off = 0, r;
while (off < buf.length
&& (r = in.read(buf, off, buf.length - off)) > 0) {
off += r;
}
in.close();
if (off == 0) return new JSONObject();
return new JSONObject(new String(buf, 0, off, "UTF-8"));
} catch (Exception e) {
return new JSONObject();
}
}

private static void write(Context ctx, String name, JSONObject root) {
try {
File f = new File(ctx.getFilesDir(), name);
FileOutputStream out = new FileOutputStream(f);
out.write(root.toString().getBytes("UTF-8"));
out.close();
} catch (Exception ignored) {
}
}
}
