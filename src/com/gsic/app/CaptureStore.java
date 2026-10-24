package com.gsic.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.DisplayMetrics;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
* 抓取记录：每次抓取自动保存（元素文本 + 带标注的截图）。
* 截图上给可点击元素画编号标记，编号与元素文本中的 [序号] 对应，
* 发给 AI 时一眼就能对上。
*/
public class CaptureStore {

public static class Record {
public String id;
public long time;
public String pkg = "";
public String dump = "";
public String imagePath = "";
public int total;
public int clickables;

public String timeStr() {
return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
.format(new Date(time));
}
}

private final Context appCtx;
private final File dir;
private final File indexFile;

public CaptureStore(Context ctx) {
appCtx = ctx.getApplicationContext();
dir = new File(appCtx.getFilesDir(), "captures");
if (!dir.exists()) dir.mkdirs();
indexFile = new File(dir, "index.json");
}

/** 抓取 + 截图 + 标注 + 保存，一步到位。返回记录 id（失败返回 null） */
public String saveCapture(List<ElementDumper.El> els, String pkg) {
if (els == null || els.isEmpty()) return null;
try {
// 截图（此时还在目标 App 界面）
Bitmap shot = AgentAccessibilityService.screenshot();
String dump = ElementDumper.format(els);
String id = "cap_" + System.currentTimeMillis();

Record r = new Record();
r.id = id;
r.time = System.currentTimeMillis();
r.pkg = pkg == null? "": pkg;
r.dump = dump;
r.total = els.size();
int nClick = 0;
for (ElementDumper.El e: els) if (e.clickable) nClick++;
r.clickables = nClick;

if (shot!= null) {
Bitmap marked = annotate(shot, els);
File img = new File(dir, id + ".png");
FileOutputStream fos = new FileOutputStream(img);
marked.compress(Bitmap.CompressFormat.PNG, 90, fos);
fos.close();
r.imagePath = img.getAbsolutePath();
if (marked!= shot) marked.recycle();
}

List<Record> all = list();
all.add(0, r);
// 最多保留 50 条
while (all.size() > 50) {
Record old = all.remove(all.size() - 1);
if (!old.imagePath.isEmpty()) new File(old.imagePath).delete();
}
saveIndex(all);
return id;
} catch (Exception e) {
return null;
}
}

/** 在截图上标注可点击元素：红框 + 编号徽章（编号=元素文本中的序号） */
public Bitmap annotate(Bitmap shot, List<ElementDumper.El> els) {
Bitmap out = shot.copy(Bitmap.Config.ARGB_8888, true);
Canvas c = new Canvas(out);
DisplayMetrics dm = appCtx.getResources().getDisplayMetrics();
float sx = dm.widthPixels > 0? (float) out.getWidth() / dm.widthPixels: 1f;
float sy = dm.heightPixels > 0? (float) out.getHeight() / dm.heightPixels: 1f;

Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
boxPaint.setStyle(Paint.Style.STROKE);
boxPaint.setStrokeWidth(Math.max(3, out.getWidth() / 360));
boxPaint.setColor(Color.RED);

Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
fillPaint.setStyle(Paint.Style.FILL);
fillPaint.setColor(0x33FF0000);

Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
badgePaint.setStyle(Paint.Style.FILL);
badgePaint.setColor(Color.RED);

Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
textPaint.setColor(Color.WHITE);
textPaint.setTextAlign(Paint.Align.CENTER);
float badgeR = Math.max(28, out.getWidth() / 24f);
textPaint.setTextSize(badgeR * 1.1f);
textPaint.setFakeBoldText(true);

for (ElementDumper.El e: els) {
if (!e.clickable) continue;
Rect b = e.bounds;
float l = b.left * sx, t = b.top * sy, r = b.right * sx, bo = b.bottom * sy;
c.drawRect(l, t, r, bo, fillPaint);
c.drawRect(l, t, r, bo, boxPaint);
// 编号徽章画在左上角
float cx = l + badgeR * 1.1f;
float cy = t + badgeR * 1.1f;
c.drawCircle(cx, cy, badgeR, badgePaint);
Paint.FontMetrics fm = textPaint.getFontMetrics();
float ty = cy - (fm.ascent + fm.descent) / 2;
c.drawText(String.valueOf(e.index), cx, ty, textPaint);
}
return out;
}

public List<Record> list() {
List<Record> out = new ArrayList<>();
try {
if (!indexFile.exists()) return out;
String txt = new String(Files.readAllBytes(indexFile.toPath()),
StandardCharsets.UTF_8);
JSONArray arr = new JSONArray(txt);
for (int i = 0; i < arr.length(); i++) {
JSONObject o = arr.getJSONObject(i);
Record r = new Record();
r.id = o.optString("id");
r.time = o.optLong("time");
r.pkg = o.optString("pkg");
r.dump = o.optString("dump");
r.imagePath = o.optString("imagePath");
r.total = o.optInt("total");
r.clickables = o.optInt("clickables");
out.add(r);
}
} catch (Exception ignored) {
}
return out;
}

public Record get(String id) {
for (Record r: list()) if (r.id.equals(id)) return r;
return null;
}

public void delete(String id) {
List<Record> all = list();
Record target = null;
for (Record r: all) if (r.id.equals(id)) target = r;
if (target == null) return;
all.remove(target);
if (!target.imagePath.isEmpty()) new File(target.imagePath).delete();
saveIndex(all);
}

private void saveIndex(List<Record> all) {
try {
JSONArray arr = new JSONArray();
for (Record r: all) {
JSONObject o = new JSONObject();
o.put("id", r.id);
o.put("time", r.time);
o.put("pkg", r.pkg);
o.put("dump", r.dump);
o.put("imagePath", r.imagePath);
o.put("total", r.total);
o.put("clickables", r.clickables);
arr.put(o);
}
Files.write(indexFile.toPath(),
arr.toString().getBytes(StandardCharsets.UTF_8));
} catch (Exception ignored) {
}
}

/** 发给 AI 生成技能卡的 prompt 模板 */
public static String buildAiPrompt(Record r) {
StringBuilder sb = new StringBuilder();
sb.append("请根据以下手机界面信息，为我生成一个「GSIC」快捷技能卡 JSON。\n\n");
sb.append("\n");
if (!r.pkg.isEmpty()) sb.append("应用包名：").append(r.pkg).append("\n");
sb.append(r.dump).append("\n\n");
sb.append("\n");
sb.append("附带的截图上，红色框+红色数字徽章标记了所有可点击元素，");
sb.append("数字与上面元素列表中的 [序号] 一一对应。\n\n");
sb.append("\n");
sb.append("{\n");
sb.append(" \"name\": \"技能名称（简短中文）\",\n");
sb.append(" \"type\": \"skill\",\n");
sb.append(" \"steps\": [\n");
sb.append(" {\"action\": \"launch\", \"package\": \"应用包名\"},\n");
sb.append(" {\"action\": \"tap_text\", \"text\": \"按钮上的文字\"},\n");
sb.append(" {\"action\": \"input\", \"text\": \"要输入的内容\", \"hint\": \"输入框提示文字\"},\n");
sb.append(" {\"action\": \"wait_text\", \"text\": \"等待出现的文字\", \"timeout\": 10}\n");
sb.append("]\n");
sb.append("}\n\n");
sb.append("\n");
sb.append("launch（启动应用，需 package）/ tap（按坐标，需 x,y）/ ");
sb.append("tap_text（按文字点击，需 text）/ tap_id（按元素序号，需 id，对应截图编号）/ ");
sb.append("wait_text（等待文字出现）/ assert_text（断言文字存在）/ ");
sb.append("input（输入文本）/ swipe（滑动，需 x1,y1,x2,y2）/ ");
sb.append("sleep（等待秒数）/ back（返回）/ screenshot（截屏存档）\n\n");
sb.append("\n");
sb.append("1. 只输出纯 JSON，不要解释、不要 markdown 代码块\n");
sb.append("2. steps 按操作顺序排列，能完成一个完整小任务\n");
sb.append("3. 优先用 tap_text（按文字点），文字不稳定时用 tap_id（截图编号）\n");
return sb.toString();
}
}
