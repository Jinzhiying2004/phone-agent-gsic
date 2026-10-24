package com.gsic.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
* 用户画像：从智能体记忆（对话+任务记录）中不定时自动总结。
* 存 files/user_profile.json。
* 包含：一句话总结、兴趣、习惯、偏好、常用任务、任务记忆。
*/
public class UserProfile {

public String summary = "";
public List<String> interests = new ArrayList<>();
public List<String> habits = new ArrayList<>();
public List<String> preferences = new ArrayList<>();
public List<String> frequentTasks = new ArrayList<>();
public long updatedAt = 0;

private final File file;

public UserProfile(Context ctx) {
file = new File(ctx.getApplicationContext().getFilesDir(), "user_profile.json");
load();
}

public boolean isEmpty() {
return summary.isEmpty() && interests.isEmpty() && habits.isEmpty()
&& preferences.isEmpty() && frequentTasks.isEmpty();
}

public String updatedStr() {
if (updatedAt == 0) return "未生成";
return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
.format(new Date(updatedAt));
}

/** 用 LLM 生成的新画像覆盖（后台线程调用） */
public synchronized void updateFromJson(String jsonStr) {
try {
JSONObject o = AgentLoop.extractJson(jsonStr);
if (o == null) return;
summary = o.optString("summary", summary);
interests = toList(o.optJSONArray("interests"));
habits = toList(o.optJSONArray("habits"));
preferences = toList(o.optJSONArray("preferences"));
frequentTasks = toList(o.optJSONArray("frequentTasks"));
updatedAt = System.currentTimeMillis();
save();
} catch (Exception ignored) {
}
}

private List<String> toList(JSONArray arr) {
List<String> out = new ArrayList<>();
if (arr == null) return out;
for (int i = 0; i < arr.length(); i++) {
String s = arr.optString(i, "").trim();
if (!s.isEmpty()) out.add(s);
}
return out;
}

/** 供 prompt 使用的精简描述 */
public synchronized String describeForPrompt() {
if (isEmpty()) return "";
StringBuilder sb = new StringBuilder("");
if (!summary.isEmpty()) sb.append(summary).append("；");
if (!preferences.isEmpty()) {
sb.append("偏好：").append(String.join("、", preferences)).append("；");
}
if (!frequentTasks.isEmpty()) {
sb.append("常用任务：").append(String.join("、", frequentTasks)).append("。");
}
return sb.toString();
}

private void save() {
try {
JSONObject o = new JSONObject();
o.put("summary", summary);
o.put("interests", new JSONArray(interests));
o.put("habits", new JSONArray(habits));
o.put("preferences", new JSONArray(preferences));
o.put("frequentTasks", new JSONArray(frequentTasks));
o.put("updatedAt", updatedAt);
Files.write(file.toPath(), o.toString().getBytes(StandardCharsets.UTF_8));
} catch (Exception ignored) {
}
}

private void load() {
try {
if (!file.exists()) return;
String txt = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
JSONObject o = new JSONObject(txt);
summary = o.optString("summary", "");
interests = toList(o.optJSONArray("interests"));
habits = toList(o.optJSONArray("habits"));
preferences = toList(o.optJSONArray("preferences"));
frequentTasks = toList(o.optJSONArray("frequentTasks"));
updatedAt = o.optLong("updatedAt", 0);
} catch (Exception ignored) {
}
}
}
