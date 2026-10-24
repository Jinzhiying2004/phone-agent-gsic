package com.gsic.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 辅助模型列表：独立于主力大模型，供智能体按需调用。
 * 例如：智谱视觉模型（看屏幕）、备用对话模型等。
 * 存 files/aux_models.json，key 只存本机。
 */
public class AuxModelStore {

    /** 用途：vision=视觉，voice=语音，image=图像，chat=对话 */
    public static final String KIND_VISION = "vision";
    public static final String KIND_VOICE = "voice";
    public static final String KIND_IMAGE = "image";
    public static final String KIND_CHAT = "chat";

    public static String kindName(String kind) {
        if (KIND_VISION.equals(kind)) return "👁 视觉";
        if (KIND_VOICE.equals(kind)) return "🎙 语音";
        if (KIND_IMAGE.equals(kind)) return "🎨 图像";
        return "💬 对话";
    }

    public static class AuxModel {
        public String id;
        public String name;
        public String baseUrl;
        public String apiKey;
        public String model;
        public String kind = KIND_VISION;
    }

    private final File file;
    private final List<AuxModel> models = new ArrayList<>();

    public AuxModelStore(Context ctx) {
        file = new File(ctx.getApplicationContext().getFilesDir(), "aux_models.json");
        load();
    }

    public synchronized List<AuxModel> list() {
        return new ArrayList<>(models);
    }

    /** 取第一个视觉模型（没有返回 null） */
    public synchronized AuxModel getVisionModel() {
        for (AuxModel m : models) {
            if (KIND_VISION.equals(m.kind)) return m;
        }
        return null;
    }

    public synchronized String add(String name, String baseUrl, String apiKey,
                                   String model, String kind) {
        AuxModel m = new AuxModel();
        m.id = UUID.randomUUID().toString();
        m.name = name == null || name.isEmpty() ? "辅助模型" : name;
        m.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        m.apiKey = apiKey == null ? "" : apiKey.trim();
        m.model = model == null ? "" : model.trim();
        m.kind = kind == null ? KIND_VISION : kind;
        models.add(m);
        save();
        return m.id;
    }

    public synchronized boolean update(String id, String name, String baseUrl,
                                       String apiKey, String model, String kind) {
        for (AuxModel m : models) {
            if (m.id.equals(id)) {
                if (name != null && !name.isEmpty()) m.name = name;
                if (baseUrl != null) m.baseUrl = baseUrl.trim();
                if (apiKey != null && !apiKey.isEmpty()) m.apiKey = apiKey.trim();
                if (model != null) m.model = model.trim();
                if (kind != null) m.kind = kind;
                save();
                return true;
            }
        }
        return false;
    }

    public synchronized boolean delete(String id) {
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).id.equals(id)) {
                models.remove(i);
                save();
                return true;
            }
        }
        return false;
    }

    /** 脱敏显示 key */
    public static String maskKey(String key) {
        if (key == null || key.length() < 8) return "未配置";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (AuxModel m : models) {
                JSONObject o = new JSONObject();
                o.put("id", m.id);
                o.put("name", m.name);
                o.put("baseUrl", m.baseUrl);
                o.put("apiKey", m.apiKey);
                o.put("model", m.model);
                o.put("kind", m.kind);
                arr.put(o);
            }
            Files.write(file.toPath(), arr.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private void load() {
        models.clear();
        try {
            if (!file.exists()) return;
            String txt = new String(Files.readAllBytes(file.toPath()),
                    StandardCharsets.UTF_8);
            JSONArray arr = new JSONArray(txt);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                AuxModel m = new AuxModel();
                m.id = o.optString("id", UUID.randomUUID().toString());
                m.name = o.optString("name", "辅助模型");
                m.baseUrl = o.optString("baseUrl", "");
                m.apiKey = o.optString("apiKey", "");
                m.model = o.optString("model", "");
                m.kind = o.optString("kind", KIND_VISION);
                models.add(m);
            }
        } catch (Exception ignored) {
        }
    }
}
