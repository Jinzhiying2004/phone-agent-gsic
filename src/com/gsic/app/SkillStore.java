package com.gsic.app;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 技能中心存储：技能 = 可复用的 JSON 任务（Hermes Skill 思想的零 token 本地版）。
 * 存于 filesDir/skills/*.json，首次运行内置示例技能。
 */
public class SkillStore {

    public static class Skill {
        public String id;      // 文件名
        public String name;
        public String desc;
        public boolean enabled;
        public String json;    // 原始 JSON
    }

    private final Context ctx;
    private final File dir;

    public SkillStore(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        dir = new File(this.ctx.getFilesDir(), "skills");
        if (!dir.exists()) {
            dir.mkdirs();
            try {
                InputStream is = this.ctx.getAssets().open("tasks/example_settings.json");
                copyStream(is, new File(dir, "example_settings.json"));
                is.close();
            } catch (Exception ignored) {
            }
        }
    }

    public List<Skill> list() {        List<Skill> out = new ArrayList<>();
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (fs == null) return out;
        Arrays.sort(fs, (a, b) -> a.getName().compareTo(b.getName()));
        for (File f : fs) {
            try {
                String raw = readFile(f);
                JSONObject o = new JSONObject(raw);
                if (!o.has("steps")) continue;
                Skill s = new Skill();
                s.id = f.getName();
                s.json = raw;
                s.name = o.optString("name", f.getName().replace(".json", ""));
                s.desc = o.optString("desc", "");
                s.enabled = o.optBoolean("enabled", true);
                out.add(s);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** 按 name 查找技能（供任务内 skill 步骤调用） */
    public Skill findByName(String name) {
        for (Skill s : list()) {
            if (s.name.equals(name)) return s;
        }
        return null;
    }

    public void setEnabled(Skill s, boolean enabled) {
        try {
            JSONObject o = new JSONObject(s.json);
            o.put("enabled", enabled);
            String raw = o.toString(2);
            writeFile(new File(dir, s.id), raw);
            s.enabled = enabled;
            s.json = raw;
        } catch (Exception ignored) {
        }
    }

    public boolean delete(Skill s) {
        return new File(dir, s.id).delete();
    }

    /** 从系统文件选择器导入，返回技能名，失败返回 null */
    public String importFrom(Uri uri) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            InputStream is = cr.openInputStream(uri);
            String raw = readStream(is);
            is.close();
            JSONObject o = new JSONObject(raw);
            if (!o.has("steps") || !(o.opt("steps") instanceof JSONArray)) return null;
            String base = o.optString("name", "imported")
                    .replaceAll("[^a-zA-Z0-9一-鿿_\\- ]", "").trim();
            if (base.isEmpty()) base = "imported";
            if (base.length() > 40) base = base.substring(0, 40);
            String id = base + "_" + (System.currentTimeMillis() % 100000) + ".json";
            if (!o.has("enabled")) o.put("enabled", true);
            writeFile(new File(dir, id), o.toString(2));
            return o.optString("name", base);
        } catch (Exception e) {
            return null;
        }
    }

    static String readFile(File f) throws Exception {
        FileInputStream is = new FileInputStream(f);
        String s = readStream(is);
        is.close();
        return s;
    }

    static String readStream(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = is.read(buf)) > 0) bos.write(buf, 0, r);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    static void writeFile(File f, String s) throws Exception {
        FileOutputStream os = new FileOutputStream(f);
        os.write(s.getBytes(StandardCharsets.UTF_8));
        os.close();
    }

    private static void copyStream(InputStream is, File dst) throws Exception {
        FileOutputStream os = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int r;
        while ((r = is.read(buf)) > 0) os.write(buf, 0, r);
        os.close();
    }
}
