package com.gsic.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 云端大模型配置存储：多密钥 + 多提供商。
 * 存 files/llm_profiles.json：{activeId, profiles:[{id,name,provider,baseUrl,apiKey,model}]}
 * 首次运行从旧版 SharedPreferences("llm") 迁移单配置为"默认"档案。
 * apiKey 只存本地、只发往用户自己填的 baseUrl，界面脱敏显示，绝不打日志。
 * v3.13 Issue 2：落盘时经 Keystore 加密（ENC: 前缀），读取自动解密兼容明文。
 */
public class LlmStore {

    /** 预设：名称 -> {baseUrl, 默认模型} */
    public static final String[][] PRESETS = {
            {"OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"},
            {"DeepSeek", "https://api.deepseek.com/v1", "deepseek-flash"},
            {"智谱", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"},
            {"自定义", "", ""},
    };

    /** 各服务商的可选模型（聊天框切换用；用户也可在配置里手填） */
    public static final java.util.Map<String, String[]> PROVIDER_MODELS =
            new java.util.HashMap<>();
    static {
        PROVIDER_MODELS.put("OpenAI",
                new String[]{"gpt-4o-mini", "gpt-4o", "gpt-4-turbo", "o1-mini"});
        PROVIDER_MODELS.put("DeepSeek",
                new String[]{"deepseek-flash", "deepseek-v4-pro"});
        PROVIDER_MODELS.put("智谱",
                new String[]{"glm-4-flash", "glm-4-air", "glm-4-plus",
                        "glm-4-long"});
        PROVIDER_MODELS.put("自定义", new String[]{});
    }

    /** 当前服务商的可选模型列表 */
    public String[] getProviderModels() {
        String[] ms = PROVIDER_MODELS.get(getProvider());
        return ms == null ? new String[]{} : ms;
    }

    public static class Profile {
        public String id;
        public String name;
        public String provider;
        public String baseUrl;
        public String apiKey;
        public String model;
        /** 视觉模型（为空则用 model；智谱推荐 glm-4v-flash） */
        public String visionModel;
    }

    private final File file;
    private final SharedPreferences oldPref;
    private final List<Profile> profiles = new ArrayList<>();
    private String activeId;
    private String supervisorId = "";

    public LlmStore(Context ctx) {
        Context ac = ctx.getApplicationContext();
        file = new File(ac.getFilesDir(), "llm_profiles.json");
        oldPref = ac.getSharedPreferences("llm", Context.MODE_PRIVATE);
        load();
        if (profiles.isEmpty()) {
            // 迁移旧单配置
            Profile p = new Profile();
            p.id = UUID.randomUUID().toString();
            p.name = "默认";
            p.provider = oldPref.getString("provider", "自定义");
            p.baseUrl = oldPref.getString("base_url", "");
            p.apiKey = oldPref.getString("api_key", "");
            p.model = oldPref.getString("model", "");
            profiles.add(p);
            activeId = p.id;
            save();
        }
        if (getActive() == null && !profiles.isEmpty()) {
            activeId = profiles.get(0).id;
            save();
        }
    }

    public synchronized List<Profile> list() {
        return new ArrayList<>(profiles);
    }

    public synchronized Profile getActive() {
        for (Profile p : profiles) if (p.id.equals(activeId)) return p;
        return null;
    }

    public synchronized void setActive(String id) {
        for (Profile p : profiles) {
            if (p.id.equals(id)) {
                activeId = id;
                save();
                return;
            }
        }
    }

    public synchronized String add(String name, String provider, String baseUrl,
                                   String apiKey, String model) {
        Profile p = new Profile();
        p.id = UUID.randomUUID().toString();
        p.name = name == null || name.isEmpty() ? "配置" + (profiles.size() + 1) : name;
        p.provider = provider == null ? "自定义" : provider;
        p.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        p.apiKey = apiKey == null ? "" : apiKey.trim();
        p.model = model == null ? "" : model.trim();
        profiles.add(p);
        activeId = p.id;
        save();
        return p.id;
    }

    public synchronized boolean update(String id, String name, String provider,
                                       String baseUrl, String apiKey, String model) {
        for (Profile p : profiles) {
            if (p.id.equals(id)) {
                if (name != null && !name.isEmpty()) p.name = name;
                if (provider != null) p.provider = provider;
                if (baseUrl != null) p.baseUrl = baseUrl.trim();
                if (apiKey != null) p.apiKey = apiKey.trim();
                if (model != null) p.model = model.trim();
                save();
                return true;
            }
        }
        return false;
    }

    public synchronized boolean update(String id, String name, String provider,
                                       String baseUrl, String apiKey,
                                       String model, String visionModel) {
        for (Profile p : profiles) {
            if (p.id.equals(id)) {
                if (name != null && !name.isEmpty()) p.name = name;
                if (provider != null) p.provider = provider;
                if (baseUrl != null) p.baseUrl = baseUrl.trim();
                if (apiKey != null) p.apiKey = apiKey.trim();
                if (model != null) p.model = model.trim();
                if (visionModel != null) p.visionModel = visionModel.trim();
                save();
                return true;
            }
        }
        return false;
    }

    public synchronized boolean delete(String id) {
        if (profiles.size() <= 1) return false; // 至少保留一个
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(id)) {
                profiles.remove(i);
                if (activeId.equals(id)) activeId = profiles.get(0).id;
                save();
                return true;
            }
        }
        return false;
    }

    // ---- 兼容旧接口：全部代理到当前激活档案 ----

    /** 旧 save()：更新当前激活档案 */
    public void save(String provider, String baseUrl, String apiKey, String model) {
        Profile p = getActive();
        if (p != null) update(p.id, null, provider, baseUrl, apiKey, model);
    }

    public String getProvider() {
        Profile p = getActive();
        return p == null ? "自定义" : p.provider;
    }

    public String getBaseUrl() {
        Profile p = getActive();
        return p == null ? "" : p.baseUrl;
    }

    public String getModel() {
        Profile p = getActive();
        return p == null ? "" : p.model;
    }

    /** 视觉模型（为空则调用方回退到主 model） */
    public String getVisionModel() {
        Profile p = getActive();
        if (p == null || p.visionModel == null) return "";
        return p.visionModel;
    }

    /** 内部使用：取完整 key（绝不对 UI/日志暴露） */
    String getApiKeyRaw() {
        Profile p = getActive();
        return p == null ? "" : p.apiKey;
    }

    public boolean hasKey() {
        return !getApiKeyRaw().isEmpty();
    }

    /** 脱敏显示：sk-****abcd */
    public String getMaskedKey() {
        String k = getApiKeyRaw();
        if (k.isEmpty()) return "未设置";
        if (k.length() <= 8) return "****";
        return k.substring(0, 3) + "****" + k.substring(k.length() - 4);
    }

    public String getActiveName() {
        Profile p = getActive();
        return p == null ? "" : p.name;
    }

    public LlmClient client() {
        return new LlmClient(getBaseUrl(), getApiKeyRaw(), getModel());
    }

    /** 监管者档案：默认主力（active），可在模型配置里长按任意档案指定 */
    public synchronized Profile supervisorProfile() {
        if (supervisorId != null && !supervisorId.isEmpty()) {
            for (Profile p : profiles) if (p.id.equals(supervisorId)) return p;
        }
        return getActive();
    }

    public synchronized void setSupervisorId(String id) {
        supervisorId = id == null ? "" : id;
        save();
    }

    public synchronized String getSupervisorId() {
        return supervisorId == null ? "" : supervisorId;
    }

    /** 监管者专用 client（默认最强模型=主力档案） */
    public LlmClient supervisorClient() {
        Profile p = supervisorProfile();
        return p != null ? clientFor(p) : client();
    }

    /** 用指定档案测试连接（不切换激活） */
    public LlmClient clientFor(Profile p) {
        return new LlmClient(p.baseUrl, p.apiKey, p.model);
    }

    private void save() {
        try {
            JSONObject root = new JSONObject();
            root.put("activeId", activeId);
            root.put("supervisorId", supervisorId == null ? "" : supervisorId);
            JSONArray arr = new JSONArray();
            for (Profile p : profiles) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                o.put("provider", p.provider);
                o.put("baseUrl", p.baseUrl);
                // v3.13 Issue 2：apiKey 落盘加密
                o.put("apiKey", CryptoUtil.encrypt(p.apiKey));
                o.put("model", p.model);
                o.put("visionModel", p.visionModel == null ? "" : p.visionModel);
                arr.put(o);
            }
            root.put("profiles", arr);
            Files.write(file.toPath(), root.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private void load() {
        try {
            if (!file.exists()) return;
            String txt = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(txt);
            activeId = root.optString("activeId", "");
            supervisorId = root.optString("supervisorId", "");
            JSONArray arr = root.optJSONArray("profiles");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Profile p = new Profile();
                p.id = o.optString("id", UUID.randomUUID().toString());
                p.name = o.optString("name", "配置");
                p.provider = o.optString("provider", "自定义");
                p.baseUrl = o.optString("baseUrl", "");
                p.apiKey = CryptoUtil.decrypt(o.optString("apiKey", ""));
                p.model = o.optString("model", "");
                p.visionModel = o.optString("visionModel", "");
                profiles.add(p);
            }
        } catch (Exception ignored) {
        }
    }
}
