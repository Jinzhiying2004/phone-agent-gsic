package com.gsic.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * OpenAI 兼容接口的极简客户端（/v1/models + /v1/chat/completions）。
 * 纯 Java（java.net），无第三方依赖，可在桌面 JVM 实测。
 * 注意：apiKey 仅用于发往用户自己配置的 baseUrl，绝不打日志。
 */
public class LlmClient {

    public static class Result {
        public boolean ok;
        /** 成功时为模型回复/连接信息，失败时为错误描述 */
        public String text;
        /** v3.51 步骤5：usage 画像（响应体有 usage 才填，否则 -1） */
        public long tokIn = -1;
        public long tokOut = -1;
    }

    /** v3.23 原生工具调用返回：纯文本回复 或 tool_calls 数组 */
    public static class ToolReply {
        public boolean ok;
        public boolean httpToolsRejected; // 400/404 且提到 tools → 模型端不支持，调用方可回退旧流程
        public String text;               // assistant 文本（可为空）
        /** 每个元素 {id, name, argsJson}，用 org.json 存 */
        public java.util.List<org.json.JSONObject> calls = new java.util.ArrayList<>();
        public String error;
        /** v3.51 步骤5：usage 画像（响应体有 usage 才填，否则 -1） */
        public long tokIn = -1;
        public long tokOut = -1;
    }

    // ---------------- v3.51 步骤5：模型调用画像打点 ----------------

    /**
     * 模型调用画像回调（AgentLoop 安装，打点 model_call 事件；不记 prompt 原文）。
     * timeout：错误文本含超时特征；retries：本次调用实际尝试次数 - 1。
     */
    public interface ModelCallSink {
        void onCall(String model, long latencyMs, long tokIn, long tokOut,
                    boolean timeout, int retries);
    }

    private static volatile ModelCallSink telemetrySink = null;

    public static void setTelemetrySink(ModelCallSink s) { telemetrySink = s; }

    private void reportCall(long t0, long tokIn, long tokOut, String errText, int retries) {
        ModelCallSink s = telemetrySink;
        if (s == null) return;
        boolean timeout = errText != null
                && (errText.contains("超时")
                    || errText.toLowerCase().contains("timeout"));
        try {
            s.onCall(model, System.currentTimeMillis() - t0,
                    tokIn, tokOut, timeout, retries);
        } catch (Exception ignored) {
        }
    }

    /** 从 /chat/completions 响应体解析 usage（未知时 {-1,-1}） */
    private static long[] parseUsage(String body) {
        try {
            org.json.JSONObject root = new org.json.JSONObject(body);
            org.json.JSONObject u = root.optJSONObject("usage");
            if (u == null) return new long[]{-1, -1};
            return new long[]{u.optLong("prompt_tokens", -1),
                    u.optLong("completion_tokens", -1)};
        } catch (Exception e) {
            return new long[]{-1, -1};
        }
    }

    /**
     * v3.23 原生工具调用。messages 元素格式：
     * {"role":"system"/"user"/"assistant"/"tool","content":"...","tool_call_id":"..."(tool时),"tool_calls":[...](assistant时),"image_b64":"..."(user附图时)}
     * tools 为 OpenAI function schemas JSONArray。
     */
    /**
     * v3.51 步骤5：chatTools 打点包装——模型画像（延迟/token/超时/重试）经 sink 上报，
     * 不记 prompt 原文。原实现下沉为 doChatTools，调用方签名不变。
     */
    public ToolReply chatTools(org.json.JSONArray messages, org.json.JSONArray tools) {
        long t0 = System.currentTimeMillis();
        ToolReply tr = doChatTools(messages, tools);
        reportCall(t0, tr.tokIn, tr.tokOut, tr.ok ? null : tr.error, 0);
        return tr;
    }

    private ToolReply doChatTools(org.json.JSONArray messages, org.json.JSONArray tools) {
        ToolReply tr = new ToolReply();
        try {
            // 组装 payload
            org.json.JSONObject payload = new org.json.JSONObject();
            payload.put("model", model);
            org.json.JSONArray msgs = new org.json.JSONArray();
            for (int i = 0; i < messages.length(); i++) {
                org.json.JSONObject m = messages.getJSONObject(i);
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("role", m.optString("role", "user"));
                String img = m.optString("image_b64", "");
                if (!img.isEmpty()) {
                    org.json.JSONArray content = new org.json.JSONArray();
                    org.json.JSONObject t = new org.json.JSONObject();
                    t.put("type", "text");
                    t.put("text", m.optString("content", ""));
                    content.put(t);
                    org.json.JSONObject iu = new org.json.JSONObject();
                    iu.put("type", "image_url");
                    org.json.JSONObject url = new org.json.JSONObject();
                    url.put("url", "data:image/jpeg;base64," + img);
                    iu.put("image_url", url);
                    content.put(iu);
                    o.put("content", content);
                } else {
                    o.put("content", m.optString("content", ""));
                }
                if (m.has("tool_calls")) o.put("tool_calls", m.getJSONArray("tool_calls"));
                if (m.has("tool_call_id")) o.put("tool_call_id", m.getString("tool_call_id"));
                if (m.has("name")) o.put("name", m.getString("name"));
                msgs.put(o);
            }
            payload.put("messages", msgs);
            if (tools != null && tools.length() > 0) {
                payload.put("tools", tools);
                payload.put("tool_choice", "auto");
            }
            HttpURLConnection c = open("/chat/completions", "POST");
            byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) { os.write(bytes); }
            int code = c.getResponseCode();
            String body = readBody(c, code);
            if (code != 200) {
                tr.ok = false;
                tr.error = "HTTP " + code + "：" + brief(body);
                String low = body.toLowerCase();
                if ((code == 400 || code == 404) && (low.contains("tool") || low.contains("function"))) {
                    tr.httpToolsRejected = true;
                }
                return tr;
            }
            org.json.JSONObject root = new org.json.JSONObject(body);
            org.json.JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                tr.ok = false; tr.error = "无 choices"; return tr;
            }
            org.json.JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
            if (msg == null) { tr.ok = false; tr.error = "无 message"; return tr; }
            tr.text = msg.optString("content", "");
            if (tr.text != null && tr.text.equals("null")) tr.text = "";
            org.json.JSONArray tcs = msg.optJSONArray("tool_calls");
            if (tcs != null) {
                for (int i = 0; i < tcs.length(); i++) {
                    org.json.JSONObject tc = tcs.getJSONObject(i);
                    org.json.JSONObject fn = tc.optJSONObject("function");
                    if (fn == null) continue;
                    org.json.JSONObject one = new org.json.JSONObject();
                    one.put("id", tc.optString("id", "call_" + i));
                    one.put("name", fn.optString("name", ""));
                    one.put("argsJson", fn.optString("arguments", "{}"));
                    tr.calls.add(one);
                }
            }
            tr.ok = true;
            // v3.51 步骤5：usage 画像（响应体有 usage 才填）
            long[] uu = parseUsage(body);
            tr.tokIn = uu[0];
            tr.tokOut = uu[1];
            return tr;
        } catch (Exception e) {
            tr.ok = false;
            tr.error = "请求失败：" + e.getMessage();
            return tr;
        }
    }

    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public LlmClient(String baseUrl, String apiKey, String model) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
    }

    /**
     * v3.22-hotfix：baseUrl 自愈。用户常把版本拼重复（/v4/v4）或
     * 与代码硬编码的 /v1 冲突（智谱 baseUrl 自带 /v4，拼成 /v4/v1 必 404）。
     * 规则：去末尾斜杠 → 折叠末尾重复路径段 → 已带 /vN 后缀直接用，
     * 否则补 /v1（兼容 "https://api.deepseek.com" 这类老配置）。
     */
    public static String normalizeBaseUrl(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        // 折叠末尾重复路径段：/v4/v4 → /v4（循环处理 /v4/v4/v4）
        boolean changed = true;
        while (changed) {
            changed = false;
            int l = b.lastIndexOf('/');
            if (l > 8) { // 8 = "https://" 长度，别动协议部分
                String seg = b.substring(l); // 如 /v4
                String rest = b.substring(0, l);
                if (!seg.isEmpty() && rest.endsWith(seg)) {
                    b = rest;
                    changed = true;
                }
            }
        }
        // 已带版本后缀（/v1、/v4…）直接用；否则补 /v1
        if (!b.matches(".*/v\\d+$")) {
            b = b + "/v1";
        }
        return b;
    }

    /** 测试连接：GET /v1/models */
    public Result testConnection() {
        Result r = new Result();
        try {
            HttpURLConnection c = open("/models", "GET");
            int code = c.getResponseCode();
            String body = readBody(c, code);
            if (code == 200) {
                int n = countOccurrences(body, "\"id\"");
                r.ok = true;
                r.text = "连接成功，可用模型约 " + n + " 个";
            } else {
                r.ok = false;
                r.text = "HTTP " + code + "：" + brief(body);
            }
        } catch (Exception e) {
            r.ok = false;
            r.text = "连接失败：" + e.getMessage();
        }
        return r;
    }

    /** 一问一答：POST /v1/chat/completions */
    /**
     * v2.51 借鉴 ApkClaw：失败指数退避重试（1s→2s→4s），401/403 不重试。
     */
    public Result chat(String prompt) {
        // v3.51 步骤5：打点包装——模型画像经 sink 上报，不记 prompt 原文
        long t0 = System.currentTimeMillis();
        int[] att = new int[1];
        Result r = withRetry(() -> doChat(prompt), att);
        reportCall(t0, r.tokIn, r.tokOut, r.ok ? null : r.text, att[0]);
        return r;
    }

    /** 流式回调：收到 token 片段时调用 */
    public interface StreamCallback {
        void onToken(String token);
    }

    /**
     * v3.02：真流式输出（SSE）。
     * 每个 token 到达时调 onToken，全收完返回完整 Result。
     */
    public Result chatStream(String prompt, StreamCallback cb) {
        // v3.51 步骤5：打点包装（SSE 无 usage，tok=-1）
        long t0 = System.currentTimeMillis();
        Result r = doChatStream(prompt, cb);
        reportCall(t0, r.tokIn, r.tokOut, r.ok ? null : r.text, 0);
        return r;
    }

    private Result doChatStream(String prompt, StreamCallback cb) {
        Result r = new Result();
        StringBuilder full = new StringBuilder();
        try {
            HttpURLConnection c = open("/chat/completions", "POST");
            String payload = "{\"model\":\"" + esc(model)
                    + "\",\"stream\":true"
                    + ",\"messages\":[{\"role\":\"user\",\"content\":\""
                    + esc(prompt) + "\"}]}";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "text/event-stream");
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }
            int code = c.getResponseCode();
            if (code != 200) {
                r.ok = false;
                r.text = "HTTP " + code + "：" + brief(readBody(c, code));
                return r;
            }
            // 逐行读 SSE
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream(),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) break;
                    try {
                        org.json.JSONObject o = new org.json.JSONObject(data);
                        org.json.JSONArray choices = o.optJSONArray("choices");
                        if (choices == null || choices.length() == 0) continue;
                        org.json.JSONObject delta = choices.optJSONObject(0)
                                .optJSONObject("delta");
                        if (delta == null || delta.isNull("content")) continue;
                        // v3.11 Issue 14：走集中 sanitize（v3.04 的 null 过滤收拢到一处）
                        String token = sanitizeStreamToken(delta);
                        if (token == null) continue;
                        full.append(token);
                        if (cb != null) {
                            try { cb.onToken(token); } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {
                        // 单行解析失败跳过
                    }
                }
            }
            r.ok = true;
            r.text = full.toString();
        } catch (Exception e) {
            r.ok = false;
            r.text = "请求失败：" + e.getMessage();
        }
        return r;
    }

    private Result withRetry(java.util.function.Supplier<Result> call, int[] attemptsOut) {
        Result r = null;
        long[] waits = {1000, 2000, 4000};
        for (int i = 0; i <= waits.length; i++) {
            r = call.get();
            if (attemptsOut != null) attemptsOut[0] = i; // v3.51 步骤5：记录实际尝试轮次
            if (r.ok) return r;
            // key 错了重试没用，直接返回
            if (r.text != null && (r.text.contains("HTTP 401") || r.text.contains("HTTP 403"))) {
                return r;
            }
            if (i < waits.length) {
                try { Thread.sleep(waits[i]); } catch (InterruptedException ignored) {}
            }
        }
        return r;
    }

    private Result doChat(String prompt) {
        Result r = new Result();
        try {
            HttpURLConnection c = open("/chat/completions", "POST");
            String payload = "{\"model\":\"" + esc(model)
                    + "\",\"messages\":[{\"role\":\"user\",\"content\":\""
                    + esc(prompt) + "\"}]}";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }
            int code = c.getResponseCode();
            String body = readBody(c, code);
            if (code == 200) {
                String content = extractContent(body);
                if (content != null) {
                    r.ok = true;
                    r.text = content;
                    // v3.51 步骤5：usage 画像
                    long[] uu = parseUsage(body);
                    r.tokIn = uu[0];
                    r.tokOut = uu[1];
                } else {
                    r.ok = false;
                    r.text = "解析回复失败";
                }
            } else {
                r.ok = false;
                r.text = "HTTP " + code + "：" + brief(body);
            }
        } catch (Exception e) {
            r.ok = false;
            r.text = "请求失败：" + e.getMessage();
        }
        return r;
    }

    /**
     * 视觉问答：把截图发给视觉模型。
     * image 传 Bitmap（会自动压缩到 720p JPEG），visionModel 为空则用主 model。
     */
    public Result chatVision(String prompt, android.graphics.Bitmap image,
                             String visionModel) {
        // v3.51 步骤5：打点包装
        long t0 = System.currentTimeMillis();
        int[] att = new int[1];
        Result r = withRetry(() -> doChatVision(prompt, image, visionModel), att);
        reportCall(t0, r.tokIn, r.tokOut, r.ok ? null : r.text, att[0]);
        return r;
    }

    private Result doChatVision(String prompt, android.graphics.Bitmap image,
                             String visionModel) {
        Result r = new Result();
        try {
            if (image == null) {
                r.ok = false;
                r.text = "截图为空";
                return r;
            }
            // 压缩到 720p 以内，省 token
            android.graphics.Bitmap small = scaleDown(image, 720);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, bos);
            if (small != image) small.recycle();
            String b64 = android.util.Base64.encodeToString(bos.toByteArray(),
                    android.util.Base64.NO_WRAP);
            String vm = visionModel == null || visionModel.isEmpty()
                    ? model : visionModel;
            HttpURLConnection c = open("/chat/completions", "POST");
            // OpenAI vision 格式：content 为数组
            String payload = "{\"model\":\"" + esc(vm) + "\",\"messages\":"
                    + "[{\"role\":\"user\",\"content\":"
                    + "[{\"type\":\"text\",\"text\":\"" + esc(prompt) + "\"},"
                    + "{\"type\":\"image_url\",\"image_url\":"
                    + "{\"url\":\"data:image/jpeg;base64," + b64 + "\"}}]}]}";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }
            int code = c.getResponseCode();
            String body = readBody(c, code);
            if (code == 200) {
                String content = extractContent(body);
                if (content != null) {
                    r.ok = true;
                    r.text = content;
                    // v3.51 步骤5：usage 画像
                    long[] uu = parseUsage(body);
                    r.tokIn = uu[0];
                    r.tokOut = uu[1];
                } else {
                    r.ok = false;
                    r.text = "解析回复失败";
                }
            } else {
                r.ok = false;
                r.text = "HTTP " + code + "：" + brief(body);
            }
        } catch (Exception e) {
            r.ok = false;
            r.text = "请求失败：" + e.getMessage();
        }
        return r;
    }

    /** 等比缩放到 maxDim 以内 */
    private static android.graphics.Bitmap scaleDown(
            android.graphics.Bitmap src, int maxDim) {
        int w = src.getWidth(), h = src.getHeight();
        int m = Math.max(w, h);
        if (m <= maxDim) return src;
        float s = (float) maxDim / m;
        return android.graphics.Bitmap.createScaledBitmap(src,
                Math.round(w * s), Math.round(h * s), true);
    }

    private HttpURLConnection open(String path, String method) throws Exception {
        URL url = new URL(baseUrl + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Authorization", "Bearer " + apiKey);
        c.setRequestProperty("Accept", "application/json");
        return c;
    }

    private String readBody(HttpURLConnection c, int code) throws Exception {
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 错误 body 截断，避免超长刷屏 */
    private String brief(String body) {
        if (body == null) return "";
        String t = body.replaceAll("\\s+", " ").trim();
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    private int countOccurrences(String s, String sub) {
        int count = 0, i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            count++;
            i += sub.length();
        }
        return count;
    }

    private String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * 从 chat/completions 响应中提取第一条 message.content。
     * 先定位 "choices" 数组，再在其后找 "content"，避免误命中。
     */
    static String extractContent(String body) {
        int ci = body.indexOf("\"choices\"");
        if (ci < 0) return null;
        return extractJsonString(body, "content", ci);
    }

    /** 从 fromIndex 起找 key 对应的 JSON 字符串值（含转义还原） */
    static String extractJsonString(String json, String key, int fromIndex) {
        String k = "\"" + key + "\"";
        int i = json.indexOf(k, fromIndex);
        while (i >= 0) {
            int j = i + k.length();
            while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
            if (j < json.length() && json.charAt(j) == ':') {
                j++;
                while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
                if (j < json.length() && json.charAt(j) == '"') {
                    StringBuilder sb = new StringBuilder();
                    j++;
                    while (j < json.length()) {
                        char ch = json.charAt(j);
                        if (ch == '\\' && j + 1 < json.length()) {
                            char e2 = json.charAt(j + 1);
                            switch (e2) {
                                case 'n': sb.append('\n'); break;
                                case 't': sb.append('\t'); break;
                                case 'r': sb.append('\r'); break;
                                case '"': sb.append('"'); break;
                                case '\\': sb.append('\\'); break;
                                case '/': sb.append('/'); break;
                                case 'u':
                                    if (j + 5 < json.length()) {
                                        try {
                                            sb.append((char) Integer.parseInt(
                                                    json.substring(j + 2, j + 6), 16));
                                        } catch (NumberFormatException ignored) {
                                        }
                                        j += 4;
                                    }
                                    break;
                                default: sb.append(e2); break;
                            }
                            j += 2;
                        } else if (ch == '"') {
                            return sb.toString();
                        } else {
                            sb.append(ch);
                            j++;
                        }
                    }
                    return null; // 字符串未闭合
                }
            }
            i = json.indexOf(k, i + 1);
        }
        return null;
    }
    /**
     * v3.11 Issue 14：流式 token 集中清洗。
     * 返回 null 表示该 token 应丢弃（空/JSON null/字面"null"/纯空白）。
     */
    public static String sanitizeStreamToken(org.json.JSONObject delta) {
        try {
            if (delta == null || delta.isNull("content")) return null;
            String t = delta.optString("content", "");
            if (t == null) return null;
            t = t.trim();
            if (t.isEmpty() || "null".equals(t) || "undefined".equals(t)) return null;
            return delta.optString("content", "");
        } catch (Exception e) {
            return null;
        }
    }

}
