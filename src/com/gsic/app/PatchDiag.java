package com.gsic.app;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 热更新诊断（E1 决定性实验，v3.54 内置，见 hidden_files/hotpatch-diagnosis.md）。
 *
 * 唯一从未验证过的环节：DexInjector.injectFirst 把合并后的 dexElements
 * Field.set 写回 base pathList 之后，运行中 app ClassLoader 的元素构成到底
 * 是什么、补丁类的 CodeSource 指向哪里。本类在两个时刻 dump：
 * - "attach"：PatchApplication.attachBaseContext 在 tryLoadPatch 之后同步调用，
 *   记录注入时刻的构成；
 * - "service"：AgentService.onCreate 异步调用，记录运行中的构成 + 补丁类
 *   CodeSource，供与 attach 时刻对比（E3）。
 *
 * 结果追加写 files/patch_diag.txt（超 128KB 轮转），供远控 shell base64 读回。
 * 判读：
 * - 元素里没有 patch.dex → 注入写回失效（查 final/ART 行为）；
 * - 元素在但补丁类 CodeSource=基线 APK → 解析层绕过（MIUI 定制/预校验）；
 * - CodeSource=patch.dex → 机制其实是通的，p2–p6 另有其因（转 E2 进程核查）。
 *
 * 纯只读诊断，不改任何状态；失败只记日志，不影响启动。
 */
public final class PatchDiag {

    private static final String TAG = "PatchDiag";
    private static final String FILE = "patch_diag.txt";
    private static final long MAX_SIZE = 128 * 1024;

    private PatchDiag() {
    }

    /** 同步 dump（attach 时刻调用，保持轻量） */
    public static void dump(Context ctx, String phase) {
        try {
            JSONObject r = new JSONObject();
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                    Locale.US);
            r.put("time", f.format(new Date()));
            r.put("phase", phase);
            r.put("versionCode", UpdateManager.currentCode(ctx));
            r.put("versionName", AppInfo.VERSION);
            r.put("process", processName());
            r.put("pid", android.os.Process.myPid());
            r.put("loaded_tag", PatchManager.loadedTag(ctx));
            r.put("patch", patchInfo(ctx));
            r.put("dexElements", dexElements(ctx));
            appendReport(ctx, r.toString(1));
            Log.i(TAG, "诊断已落盘 phase=" + phase
                    + " 进程=" + r.optString("process"));
        } catch (Exception e) {
            Log.w(TAG, "诊断失败: " + e);
        }
    }

    /** 异步 dump（服务启动时调用，不阻塞启动） */
    public static void dumpAsync(final Context ctx, final String phase) {
        try {
            final Context app = ctx.getApplicationContext();
            new Thread(() -> {
                try {
                    dump(app, phase);
                } catch (Exception e) {
                    Log.w(TAG, "异步诊断失败: " + e);
                }
            }, "PatchDiag").start();
        } catch (Exception e) {
            Log.w(TAG, "启动诊断线程失败: " + e);
        }
    }

    /** 补丁落盘状态：存在性/sha256/meta(tag/appCode/classes 前 5 个) */
    private static JSONObject patchInfo(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            File dir = new File(ctx.getFilesDir(), "patches");
            File dex = new File(dir, "patch.dex");
            File meta = new File(dir, "applied.json");
            o.put("dex_exists", dex.exists());
            o.put("meta_exists", meta.exists());
            if (dex.exists()) {
                try {
                    o.put("dex_sha256", PatchManager.sha256(dex));
                } catch (Exception e) {
                    o.put("dex_sha256", "读失败:" + e);
                }
            }
            if (meta.exists()) {
                try {
                    String s = readFile(meta);
                    JSONObject m = new JSONObject(s);
                    o.put("tag", m.optString("tag", ""));
                    o.put("appCode", m.optInt("appCode", -1));
                    JSONArray cls = m.optJSONArray("classes");
                    JSONArray head = new JSONArray();
                    if (cls != null) {
                        for (int i = 0; i < cls.length() && i < 5; i++) {
                            head.put(cls.optString(i));
                        }
                    }
                    o.put("classes_head", head);
                    // E1 ②：补丁类 CodeSource（initialize=false，不触发 <clinit>）
                    JSONObject cs = new JSONObject();
                    if (cls != null) {
                        for (int i = 0; i < cls.length() && i < 5; i++) {
                            String name = cls.optString(i);
                            cs.put(name, codeSourceOf(ctx, name));
                        }
                    }
                    o.put("codeSource", cs);
                } catch (Exception e) {
                    o.put("meta_error", String.valueOf(e));
                }
            }
        } catch (Exception e) {
            try {
                o.put("error", String.valueOf(e));
            } catch (Exception ignored) {
            }
        }
        return o;
    }

    private static String codeSourceOf(Context ctx, String className) {
        try {
            Class<?> c = Class.forName(className, false, ctx.getClassLoader());
            java.security.ProtectionDomain pd = c.getProtectionDomain();
            if (pd == null) return "ProtectionDomain=null";
            java.security.CodeSource src = pd.getCodeSource();
            if (src == null) return "CodeSource=null";
            java.net.URL loc = src.getLocation();
            return loc == null ? "location=null" : loc.toString();
        } catch (ClassNotFoundException e) {
            return "类未加载（两边都找不到）";
        } catch (Exception e) {
            return "查询失败:" + e;
        }
    }

    /** E1 ①：当前进程 app ClassLoader 的 dexElements 数量与每个元素的 dex 路径 */
    private static JSONObject dexElements(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            ClassLoader loader = ctx.getClassLoader();
            o.put("loader", String.valueOf(loader.getClass().getName()));
            Object pathList = getField(loader, "pathList");
            Object elems = getField(pathList, "dexElements");
            int n = Array.getLength(elems);
            o.put("count", n);
            JSONArray arr = new JSONArray();
            for (int i = 0; i < n; i++) {
                Object el = Array.get(elems, i);
                arr.put(elementPath(el));
            }
            o.put("paths", arr);
        } catch (Exception e) {
            try {
                o.put("error", String.valueOf(e));
            } catch (Exception ignored) {
            }
        }
        return o;
    }

    /** Element.dexFile 公开字段 + DexFile.getName() 公开方法，跨 API 安全 */
    private static String elementPath(Object element) {
        try {
            Object dexFile = getField(element, "dexFile");
            if (dexFile == null) return "<dexFile=null>";
            Object name = dexFile.getClass().getMethod("getName")
                    .invoke(dexFile);
            return String.valueOf(name);
        } catch (Exception e) {
            return "<不可读:" + e.getClass().getSimpleName() + ">";
        }
    }

    private static String processName() {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                String p = android.app.Application.getProcessName();
                if (p != null && !p.isEmpty()) return p;
            }
        } catch (Exception ignored) {
        }
        try {
            byte[] b = readBytes(new File("/proc/self/cmdline"));
            String s = new String(b, "UTF-8");
            int z = s.indexOf('\0');
            return z > 0 ? s.substring(0, z) : s;
        } catch (Exception e) {
            return "未知:" + e;
        }
    }

    private static Object getField(Object o, String name) throws Exception {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void appendReport(Context ctx, String text) throws Exception {
        File f = new File(ctx.getFilesDir(), FILE);
        if (f.exists() && f.length() > MAX_SIZE) {
            // 轮转：只保留最近一次，避免无限增长
            // noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        String block = "===== patch_diag "
                + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                        .format(new Date()) + " =====\n" + text + "\n";
        try (FileOutputStream os = new FileOutputStream(f, true)) {
            os.write(block.getBytes("UTF-8"));
        }
    }

    private static String readFile(File f) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos =
                    new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            return bos.toString("UTF-8");
        }
    }

    private static byte[] readBytes(File f) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos =
                    new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            return bos.toByteArray();
        }
    }
}
