package com.gsic.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * v3.50 主人词典（种子版）：用户亲定的个人语义映射。
 *
 * 与 UserMemory（key-value 偏好）不同，词典存的是"词→含义"（如 clash 指
 * Clash_For_Magisk 模块而非 App），任务书生成与每轮钉住时常驻注入。
 *
 * 纯 Java：文件 IO 走 java.io.File（调用方传 ctx.getFilesDir()），
 * 无 Android 依赖，可在 JVM 直接单测。文件格式与 SharedMemory 同套路：
 * master_dict.json = {"items":[{"k":"..","v":"..","src":"user|agent"}]}。
 *
 * v3.50 不做编辑 UI 与自动沉淀（留给 v3.51 认知库）；put 接口先留好。
 */
public class MasterDict {

    public static final String FILE_NAME = "master_dict.json";
    public static final int MAX_ITEMS = 100;

    public static class Item {
        public String k = "";
        public String v = "";
        /** 来源：user=用户亲定，agent=沉淀 */
        public String src = "";

        public Item() {
        }

        public Item(String k, String v, String src) {
            this.k = k == null ? "" : k;
            this.v = v == null ? "" : v;
            this.src = src == null ? "" : src;
        }
    }

    private final List<Item> items = new ArrayList<>();

    /** 四条种子（全部来自用户亲定，见 MEMORY/设计稿第八节） */
    public static MasterDict seeds() {
        MasterDict d = new MasterDict();
        d.put("clash",
                "指 Clash_For_Magisk Magisk 模块（mihomo 内核，给热点下 PC 共享代理），不是 App；启停/升级走模块路径",
                "user");
        d.put("领时长",
                "汽水音乐走既定技能卡路径（看广告领）、酷狗概念版（com.kugou.android.lite）免广告；领完停止播放并清理后台",
                "user");
        d.put("回桌面",
                "用户已废止自动回桌面（2026-10-22）：任务完成停在结束界面即可，不要主动回桌面，除非用户明说要回",
                "user");
        d.put("音乐收尾",
                "停止播放音乐并清理后台",
                "user");
        return d;
    }

    /**
     * 从目录加载；文件不存在/解析失败回种子版（v3.50 种子即全部内容）。
     * 调用方传 ctx.getFilesDir()。
     */
    public static MasterDict load(File dir) {
        MasterDict d = new MasterDict();
        try {
            if (dir == null) return seeds();
            File f = new File(dir, FILE_NAME);
            if (!f.exists()) return seeds();
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int off = 0, r;
            while (off < buf.length && (r = in.read(buf, off, buf.length - off)) > 0) {
                off += r;
            }
            in.close();
            if (off == 0) return d;
            JSONObject root = new JSONObject(new String(buf, 0, off, "UTF-8"));
            JSONArray arr = root.optJSONArray("items");
            if (arr == null) return seeds();
            for (int i = 0; i < arr.length() && d.items.size() < MAX_ITEMS; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String k = o.optString("k", "").trim();
                String v = o.optString("v", "").trim();
                if (k.isEmpty() || v.isEmpty()) continue;
                d.items.add(new Item(k, v, o.optString("src", "").trim()));
            }
        } catch (Exception e) {
            return seeds();
        }
        // 用户亲定种子条目以随包种子为准：规矩改版（如废止自动回桌面）
        // 必须能覆盖手机上旧文件里的旧条目，学来的条目不受影响
        MasterDict s = seeds();
        for (Item si : s.items) {
            if ("user".equals(si.src)) d.put(si.k, si.v, si.src);
        }
        return d;
    }

    /** 持久化到目录 */
    public synchronized void save(File dir) {
        try {
            if (dir == null) return;
            if (!dir.exists()) dir.mkdirs();
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("k", it.k);
                o.put("v", it.v);
                o.put("src", it.src);
                arr.put(o);
            }
            root.put("items", arr);
            File f = new File(dir, FILE_NAME);
            FileOutputStream out = new FileOutputStream(f);
            out.write(root.toString().getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
        }
    }

    /** 新增或覆盖（同 k 保留原位置）；超上限时丢弃最旧的 */
    public synchronized void put(String k, String v, String src) {
        if (k == null || k.trim().isEmpty() || v == null || v.trim().isEmpty()) return;
        String kk = k.trim();
        for (Item it : items) {
            if (it.k.equals(kk)) {
                it.v = v.trim();
                if (src != null && !src.trim().isEmpty()) it.src = src.trim();
                return;
            }
        }
        items.add(new Item(kk, v.trim(), src == null ? "" : src.trim()));
        while (items.size() > MAX_ITEMS) items.remove(0);
    }

    /** 查词义；无返回空串 */
    public synchronized String lookup(String k) {
        if (k == null) return "";
        for (Item it : items) {
            if (it.k.equals(k.trim())) return it.v;
        }
        return "";
    }

    public synchronized int size() {
        return items.size();
    }

    public synchronized List<Item> all() {
        return new ArrayList<>(items);
    }

    /** 任务书生成 prompt 用全文（让模型用词典解指代） */
    public synchronized String describeForPrompt() {
        if (items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            sb.append("- ").append(it.k).append("：").append(it.v).append("\n");
        }
        return sb.toString().trim();
    }

    /** 钉住块末尾紧凑行：k=短义；… */
    public synchronized String describeShort() {
        if (items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (i > 0) sb.append("；");
            sb.append(it.k).append("=").append(trim(it.v, 30));
        }
        return sb.toString();
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
