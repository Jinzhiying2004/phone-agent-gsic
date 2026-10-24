package com.gsic.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.system.Os;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 内置 Termux 环境管理器。
 * <p>
 * 方案：首次使用时下载官方 Termux bootstrap（aarch64，约 30MB），
 * 解压到 App 私有目录，再用 root 挂载（mount --bind）到 Termux 标准路径
 * /data/data/com.termux/files/usr。因为 apt/dpkg/pkg 及后续安装的包都把
 * 该路径硬编码进了二进制与脚本，只有标准路径能 100% 兼容。
 * <p>
 * 安全防护：若检测到官方 Termux App 已安装，拒绝挂载，避免覆盖用户数据。
 */
public class BootstrapManager {

    /** 官方 bootstrap 版本（与 termux-app 官方构建一致） */
    public static final String BOOTSTRAP_VERSION = "2026.02.12-r1+apt.android-7";
    public static final String BOOTSTRAP_URL =
            "https://github.com/termux/termux-packages/releases/download/"
                    + "bootstrap-2026.02.12-r1%2Bapt.android-7/bootstrap-aarch64.zip";
    /** 官方公布的 SHA256（来自 termux-app 构建脚本） */
    public static final String BOOTSTRAP_SHA256 =
            "ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3";

    /** Termux 标准 prefix 路径（挂载目标） */
    public static final String TERMUX_PREFIX = "/data/data/com.termux/files/usr";

    public interface ProgressListener {
        void onProgress(int percent);

        void onDone(boolean ok, String msg);
    }

    private final Context ctx;
    private final SharedPreferences pref;

    public BootstrapManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.pref = ctx.getSharedPreferences("bootstrap", Context.MODE_PRIVATE);
    }

    private File filesDir() {
        return ctx.getFilesDir();
    }

    /** 解压 staging 目录（私有） */
    public File stagingDir() {
        return new File(filesDir(), "termux");
    }

    public File zipFile() {
        return new File(filesDir(), "bootstrap-aarch64.zip");
    }

    public File homeDir() {
        return new File(filesDir(), "termux-home");
    }

    public boolean isInstalled() {
        return pref.getBoolean("installed", false)
                && new File(stagingDir(), "bin/bash").exists();
    }

    public boolean isMounted() {
        // 读 /proc/mounts 确认挂载点
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream("/proc/mounts")))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains(" " + TERMUX_PREFIX + " ")) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public boolean isTermuxAppInstalled() {
        try {
            ctx.getPackageManager().getPackageInfo("com.termux", 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 环境是否就绪（已安装且已挂载） */
    public boolean isReady() {
        return isInstalled() && isMounted();
    }

    // ================= v3.57 本地编程能力步骤4：Termux 首启自动装 python+git =================

    /** 运行时（python3+git）是否已就绪 */
    public boolean isRuntimeReady() {
        return pref.getBoolean("runtime_ready", false);
    }

    private volatile boolean runtimeInstalling = false;

    /**
     * 确保 Termux 内 python3+git 可用（首启自动装）。
     * - runtime_ready=true 直接返回；
     * - 正在安装中则不重复起线程；
     * - 否则后台线程：pkg update && pkg install -y python git（超时 10 分钟）
     *   → python3 -V && git --version 验执行链 → 成功写 runtime_ready=true；
     *   无网/失败写 runtime_pending=true（下次调用重试）。
     * 不抛异常、不卡任务：结果只走 listener。
     */
    public void ensureRuntime(final ProgressListener listener) {
        final ProgressListener l = listener == null
                ? new ProgressListener() {
                    public void onProgress(int p) {}
                    public void onDone(boolean ok, String msg) {}
                } : listener;
        if (!isInstalled()) {
            l.onDone(false, "Termux 环境未安装");
            return;
        }
        if (isRuntimeReady()) {
            l.onDone(true, "python3/git 已就绪");
            return;
        }
        if (runtimeInstalling) {
            l.onDone(false, "python3/git 正在安装中");
            return;
        }
        runtimeInstalling = true;
        new Thread(() -> {
            try {
                l.onProgress(5);
                int code = execInTermux(
                        "pkg update -y && pkg install -y python git",
                        10 * 60 * 1000);
                if (code != 0) {
                    pref.edit().putBoolean("runtime_pending", true).apply();
                    l.onDone(false, "安装 python/git 失败（exit=" + code
                            + "，可能无网；稍后自动重试）");
                    return;
                }
                l.onProgress(80);
                int v = execInTermux("python3 -V && git --version", 60 * 1000);
                if (v == 0) {
                    pref.edit().putBoolean("runtime_ready", true)
                            .putBoolean("runtime_pending", false).apply();
                    l.onDone(true, "python3/git 安装完成并通过执行链验证");
                } else {
                    pref.edit().putBoolean("runtime_pending", true).apply();
                    l.onDone(false,
                            "执行链验证失败（python3/git 不可用），稍后重试");
                }
            } catch (Exception e) {
                pref.edit().putBoolean("runtime_pending", true).apply();
                l.onDone(false, "安装异常：" + e.getMessage() + "，稍后重试");
            } finally {
                runtimeInstalling = false;
            }
        }).start();
    }

    /**
     * 在 Termux 环境内执行命令（staging 目录直跑，免 root）。
     * 返回退出码；超时返回 124；异常返回 127。不抛异常。
     */
    private int execInTermux(String cmd, long timeoutMs) {
        try {
            String prefix = stagingDir().getAbsolutePath();
            java.io.File bashFile = new java.io.File(prefix + "/bin/bash");
            if (!bashFile.exists()) return 127;
            if (!bashFile.canExecute()) bashFile.setExecutable(true);
            ProcessBuilder pb = new ProcessBuilder(
                    bashFile.getAbsolutePath(), "-c", cmd);
            pb.environment().clear();
            pb.environment().putAll(termuxEnv());
            pb.redirectErrorStream(true);
            Process pr = pb.start();
            // 消费输出防管道阻塞（安装日志量大）
            Thread drain = new Thread(() -> {
                try {
                    byte[] buf = new byte[8192];
                    java.io.InputStream in = pr.getInputStream();
                    while (in.read(buf) >= 0) { /* 丢弃 */ }
                } catch (Exception ignored) {}
            });
            drain.setDaemon(true);
            drain.start();
            boolean done = pr.waitFor(timeoutMs,
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!done) {
                pr.destroyForcibly();
                return 124;
            }
            return pr.exitValue();
        } catch (Exception e) {
            return 127;
        }
    }

    /** 后台下载 bootstrap，带进度与 SHA256 校验 */
    public void download(final ProgressListener listener) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(BOOTSTRAP_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("User-Agent", "gsic");
                conn.connect();
                int total = conn.getContentLength();
                MessageDigest sha = MessageDigest.getInstance("SHA-256");
                File out = zipFile();
                try (InputStream in = new BufferedInputStream(conn.getInputStream());
                     OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[65536];
                    int n;
                    long done = 0;
                    int lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        sha.update(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                listener.onProgress(pct);
                            }
                        }
                    }
                }
                StringBuilder hex = new StringBuilder();
                for (byte b : sha.digest()) hex.append(String.format("%02x", b));
                if (!hex.toString().equalsIgnoreCase(BOOTSTRAP_SHA256)) {
                    out.delete();
                    listener.onDone(false, "校验失败：SHA256 不匹配，已删除");
                    return;
                }
                listener.onProgress(100);
                listener.onDone(true, "下载完成，校验通过");
            } catch (Exception e) {
                listener.onDone(false, "下载失败：" + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    /** 解压并初始化（后台线程） */
    public void install(final ProgressListener listener) {
        new Thread(() -> {
            try {
                File stage = new File(filesDir(), "termux-staging");
                deleteRecursive(stage);
                stage.mkdirs();
                List<String[]> symlinks = new ArrayList<>();
                try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(
                        new FileInputStream(zipFile())))) {
                    ZipEntry e;
                    byte[] buf = new byte[65536];
                    while ((e = zin.getNextEntry()) != null) {
                        String name = e.getName();
                        if (name.equals("SYMLINKS.txt")) {
                            BufferedReader r = new BufferedReader(
                                    new InputStreamReader(zin, "UTF-8"));
                            String line;
                            while ((line = r.readLine()) != null) {
                                String[] parts = line.split("←");
                                if (parts.length != 2) {
                                    throw new RuntimeException(
                                            "SYMLINKS.txt 格式错误: " + line);
                                }
                                symlinks.add(parts);
                            }
                            continue;
                        }
                        File target = new File(stage, name);
                        if (e.isDirectory()) {
                            target.mkdirs();
                        } else {
                            File parent = target.getParentFile();
                            if (parent != null) parent.mkdirs();
                            try (OutputStream os = new FileOutputStream(target)) {
                                int n;
                                while ((n = zin.read(buf)) > 0) os.write(buf, 0, n);
                            }
                            if (name.startsWith("bin/")
                                    || name.startsWith("libexec")
                                    || name.startsWith("lib/apt/apt-helper")
                                    || name.startsWith("lib/apt/methods")) {
                                target.setExecutable(true, false);
                            }
                        }
                        zin.closeEntry();
                    }
                }
                // 创建软链接：目标改写为 staging 相对路径，免 root 也能用
                // （原来是官方绝对路径 /data/data/com.termux/...，只在挂载后有效）
                String stagePath = stage.getAbsolutePath();
                for (String[] p : symlinks) {
                    File link = new File(stage, p[1].replaceFirst("^\\./", ""));
                    File parent = link.getParentFile();
                    if (parent != null) parent.mkdirs();
                    if (link.exists()) link.delete();
                    String target = p[0];
                    // 把官方绝对路径前缀换成 staging 路径
                    if (target.startsWith("/data/data/com.termux/files/usr")) {
                        target = stagePath + target.substring(
                                "/data/data/com.termux/files/usr".length());
                    } else if (target.startsWith("./")) {
                        target = stagePath + "/" + target.substring(2);
                    }
                    Os.symlink(target, link.getAbsolutePath());
                }
                // 改写脚本 shebang：官方路径换成 staging 路径，免 root 也能直接跑脚本
                fixShebangsIn(stage, stagePath);
                // 原子替换
                File dest = stagingDir();
                deleteRecursive(dest);
                if (!stage.renameTo(dest)) {
                    listener.onDone(false, "移动目录失败");
                    return;
                }
                homeDir().mkdirs();
                pref.edit().putBoolean("installed", true)
                        .putString("version", BOOTSTRAP_VERSION).apply();
                listener.onDone(true, "环境安装完成");
                // v3.57：安装完成后自动触发 runtime 安装（python3+git），后台不阻塞
                ensureRuntime(null);
            } catch (Exception e) {
                listener.onDone(false, "安装失败：" + e.getMessage());
            }
        }).start();
    }

    /**
     * 确保挂载到标准路径。需要 root。
     * 若检测到官方 Termux 已安装则拒绝，避免覆盖用户数据。
     */
    public String ensureMounted() {
        if (!isInstalled()) return "环境尚未安装";
        if (isTermuxAppInstalled()) {
            return "检测到官方 Termux 已安装，为避免覆盖其数据，已停止挂载";
        }
        if (isMounted()) return null; // 已就绪
        String src = stagingDir().getAbsolutePath();
        String cmd = "mkdir -p " + TERMUX_PREFIX
                + " && mount --bind " + src + " " + TERMUX_PREFIX;
        int code = runSu(cmd);
        if (code != 0) return "挂载失败（exit=" + code + "），请确认已 root";
        return isMounted() ? null : "挂载命令已执行但未生效";
    }

    /** 卸载挂载并删除环境 */
    public String remove() {
        runSu("umount " + TERMUX_PREFIX + " 2>/dev/null; rmdir -p "
                + TERMUX_PREFIX + " 2>/dev/null; true");
        deleteRecursive(stagingDir());
        deleteRecursive(homeDir());
        zipFile().delete();
        pref.edit().clear().apply();
        return null;
    }

    private Boolean mmSupported = null;

    /** 以 root 执行命令；优先 -mm（Magisk/KernelSU 的全局挂载命名空间） */
    private int runSu(String cmd) {
        if (mmSupported == null) {
            mmSupported = execSu(new String[]{"su", "-mm", "-c", "true"}) == 0;
        }
        if (mmSupported) {
            return execSu(new String[]{"su", "-mm", "-c", cmd});
        }
        return execSu(new String[]{"su", "-c", cmd});
    }

    private int execSu(String[] argv) {
        try {
            Process p = Runtime.getRuntime().exec(argv);
            return p.waitFor();
        } catch (Exception e) {
            return 127;
        }
    }

    private void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    /**
     * 挂载是否真实可用：/proc/mounts 有记录且挂载路径下 bash 真实存在。
     * （有残留挂载记录但文件已不存在时，不能算挂载）
     */
    public boolean mountedUsable() {
        return isMounted()
                && new java.io.File(TERMUX_PREFIX + "/bin/bash").exists();
    }

    /** 运行环境内命令所需的环境变量 */
    public Map<String, String> termuxEnv() {
        Map<String, String> env = new HashMap<>();
        // 挂载路径必须真实可用（bash 存在）才用，否则一律用 staging
        boolean mounted = mountedUsable();
        String prefix = mounted ? TERMUX_PREFIX : stagingDir().getAbsolutePath();
        env.put("HOME", homeDir().getAbsolutePath());
        env.put("PREFIX", prefix);
        env.put("PATH", prefix + "/bin");
        env.put("TERM", "xterm-256color");
        env.put("LANG", "en_US.UTF-8");
        env.put("TMPDIR", prefix + "/tmp");
        if (!mounted) {
            // 未挂载回退模式：靠 LD_LIBRARY_PATH 找 so
            env.put("LD_LIBRARY_PATH", prefix + "/lib");
        }
        return env;
    }

    public String statusText() {
        if (!isInstalled()) return "未安装";
        if (isTermuxAppInstalled()) return "已安装（官方 Termux 存在，未挂载）";
        if (mountedUsable()) return "已安装并挂载 ✓";
        return "已安装（免 root 模式）";
    }

    /** 修复已安装环境中的坏软链接（免 root 模式） */
    public String fixSymlinks() {
        if (!isInstalled()) return "环境未安装";
        File prefix = stagingDir();
        String prefixPath = prefix.getAbsolutePath();
        int fixed = 0, skipped = 0;
        try {
            java.util.Queue<File> q = new java.util.LinkedList<>();
            q.add(prefix);
            while (!q.isEmpty()) {
                File dir = q.poll();
                File[] files = dir.listFiles();
                if (files == null) continue;
                for (File f : files) {
                    if (f.isDirectory() && !isSymlink(f)) {
                        q.add(f);
                    } else if (isSymlink(f)) {
                        String target = readSymlink(f);
                        if (target != null && target.startsWith(
                                "/data/data/com.termux/files/usr")) {
                            String newTarget = prefixPath + target.substring(
                                "/data/data/com.termux/files/usr".length());
                            f.delete();
                            Os.symlink(newTarget, f.getAbsolutePath());
                            fixed++;
                        } else {
                            skipped++;
                        }
                    }
                }
            }
        } catch (Exception e) {
            return "修复失败: " + e.getMessage();
        }
        return "修复完成：改写 " + fixed + " 个链接，跳过 " + skipped + " 个";
    }

    /** 修复已安装环境中脚本的 shebang（免 root 模式，官方路径改写为 staging 路径） */
    public String fixShebangs() {
        if (!isInstalled()) return "环境未安装";
        int fixed = fixShebangsIn(stagingDir(),
                stagingDir().getAbsolutePath());
        return "shebang 修复完成：改写 " + fixed + " 个脚本";
    }

    // 遍历目录，把脚本首行 shebang 里的官方前缀改写成给定前缀；返回改写数
    private static int fixShebangsIn(File root, String prefixPath) {
        int fixed = 0;
        java.util.Queue<File> q = new java.util.LinkedList<>();
        q.add(root);
        while (!q.isEmpty()) {
            File dir = q.poll();
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (f.isDirectory() && !isSymlink(f)) {
                    q.add(f);
                } else if (f.isFile() && !isSymlink(f)
                        && f.length() > 2 && f.length() < 4194304) {
                    try {
                        byte[] all = new byte[(int) f.length()];
                        boolean full = false;
                        try (java.io.FileInputStream in =
                                new java.io.FileInputStream(f)) {
                            int off = 0;
                            while (off < all.length) {
                                int n = in.read(all, off, all.length - off);
                                if (n < 0) break;
                                off += n;
                            }
                            full = off >= all.length;
                        }
                        if (!full) continue;
                        if (all[0] != '#' || all[1] != '!') continue;
                        String text = new String(all, "UTF-8");
                        int nl = text.indexOf('\n');
                        String first = nl >= 0 ? text.substring(0, nl) : text;
                        String want = "#!" + TERMUX_PREFIX;
                        if (!first.startsWith(want)) continue;
                        String out = "#!" + prefixPath
                                + first.substring(want.length())
                                + (nl >= 0 ? text.substring(nl) : "");
                        boolean exec = f.canExecute();
                        try (java.io.FileOutputStream os =
                                new java.io.FileOutputStream(f)) {
                            os.write(out.getBytes("UTF-8"));
                        }
                        if (exec) f.setExecutable(true, false);
                        fixed++;
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return fixed;
    }


    private static boolean isSymlink(File f) {
        try {
            return !f.getCanonicalPath().equals(f.getAbsolutePath());
        } catch (Exception e) {
            return false;
        }
    }

    private static String readSymlink(File f) {
        try {
            return Os.readlink(f.getAbsolutePath());
        } catch (Exception e) {
            return null;
        }
    }

    /** 诊断 Termux 环境：返回每步检查结果 */
    public String diagnose() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Termux 诊断 ===\n");
        boolean mounted = isMounted();
        String prefix = mounted ? TERMUX_PREFIX
                : stagingDir().getAbsolutePath();
        sb.append("模式: ").append(mounted ? "挂载" : "免 root 回退").append("\n");
        sb.append("PREFIX: ").append(prefix).append("\n");
        java.io.File bash = new java.io.File(prefix + "/bin/bash");
        sb.append("bash 存在: ").append(bash.exists()).append("\n");
        if (bash.exists()) {
            sb.append("bash 可执行: ").append(bash.canExecute()).append("\n");
            sb.append("bash 大小: ").append(bash.length()).append(" 字节\n");
            // 试运行 bash --version
            try {
                java.util.Map<String, String> env = termuxEnv();
                ProcessBuilder pb = new ProcessBuilder(
                        bash.getAbsolutePath(), "--version");
                pb.environment().clear();
                pb.environment().putAll(env);
                pb.redirectErrorStream(true);
                Process pr = pb.start();
                boolean done = pr.waitFor(10,
                        java.util.concurrent.TimeUnit.SECONDS);
                if (done) {
                    java.io.BufferedReader br =
                            new java.io.BufferedReader(
                            new java.io.InputStreamReader(
                                    pr.getInputStream(), "UTF-8"));
                    String line = br.readLine();
                    br.close();
                    sb.append("试运行: exit=").append(pr.exitValue());
                    if (line != null) {
                        sb.append(", 输出=").append(line.length() > 80
                                ? line.substring(0, 80) : line);
                    }
                    sb.append("\n");
                } else {
                    pr.destroyForcibly();
                    sb.append("试运行: 超时\n");
                }
            } catch (Exception e) {
                sb.append("试运行失败: ").append(e.getMessage())
                        .append("\n");
            }
        }
        // 检查关键目录
        String[] dirs = {"/bin", "/lib", "/etc", "/tmp"};
        for (String d : dirs) {
            java.io.File f = new java.io.File(prefix + d);
            sb.append(d).append(": ")
                    .append(f.exists() ? "存在" : "缺失").append("\n");
        }
        // lib 里的 so 数量
        java.io.File libDir = new java.io.File(prefix + "/lib");
        if (libDir.exists()) {
            String[] sos = libDir.list((dir, name) ->
                    name.endsWith(".so"));
            sb.append("lib/*.so 数量: ")
                    .append(sos == null ? 0 : sos.length).append("\n");
        }
        // staging 路径详细检查（免 root 实际用的路径）
        java.io.File stBash =
                new java.io.File(stagingDir(), "bin/bash");
        sb.append("[staging] bash 存在: ").append(stBash.exists())
                .append("\n");
        if (stBash.exists()) {
            sb.append("[staging] 可执行: ").append(stBash.canExecute())
                    .append("\n");
            sb.append("[staging] 可读: ").append(stBash.canRead())
                    .append("\n");
            // 尝试设置执行权限看是否成功
            boolean setOk = stBash.setExecutable(true, false);
            sb.append("[staging] setExecutable 结果: ").append(setOk)
                    .append(", 之后可执行: ").append(stBash.canExecute())
                    .append("\n");
            // 检查是不是软链接断链
            try {
                String linkTarget = Os.readlink(
                        stBash.getAbsolutePath());
                sb.append("[staging] 是软链接→").append(linkTarget)
                        .append("\n");
            } catch (Exception e) {
                sb.append("[staging] 是实体文件\n");
            }
        }
        sb.append("=== 诊断结束 ===");
        return sb.toString();
    }
}
