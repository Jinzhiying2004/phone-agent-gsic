package com.gsic.app;

import android.content.Context;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * v3.67: cloudflared 内置隧道（快速隧道，临时测试用）。
 * 原理：手机主动出站连 Cloudflare 边缘，拿到一个随机的 trycloudflare.com 地址，
 * 该地址只转发到本机回环的远控端口；AgentHttpServer 本来就只放行回环，
 * 所以隧道进来的请求在服务端看来就是 127.0.0.1，仍要过 X-Token 鉴权。
 * 隧道本身不是鉴权：拿到地址的人没有 token 仍然进不去。
 * 快速隧道地址每次启动就换，本版先走手动同步：用户在 App 里看到地址后
 * 发给云端 Muse，云端再用该地址探 /status。固定域名是后续阶段。
 * 二进制：构建时从 Cloudflare 官方 GitHub releases 拉取 linux-arm64 静态版
 * 放进 assets（不进 git，build.sh 缺失时自动拉取，与 vdinput.dex 同款做法）。
 * 注意：Android 上直接 exec 静态 Go 二进制是否成功，须真机实测，
 * 失败时 lastError 会原样记录，UI 直接展示，不谎报已通。
 */
public class CloudflareTunnel {

    private static final Pattern URL_PAT =
            Pattern.compile("https://(?!api\\.)[A-Za-z0-9-]+\\.trycloudflare\\.com");

    private static volatile Process proc = null;
    private static volatile boolean running = false;
    private static volatile String url = "";
    private static volatile String lastError = "";
    private static volatile long startedAt = 0;
    private static volatile boolean useProxy = false;

    public static boolean isRunning() {
        return running && proc != null && proc.isAlive();
    }

    public static String getUrl() {
        // v3.72：未运行时不返回旧地址，/status 与复制按钮都不会拿到过期/误报地址
        return isRunning() ? url : "";
    }

    public static String getError() {
        return lastError;
    }

    public static long getStartedAt() {
        return startedAt;
    }

    public static boolean isProxyMode(Context ctx) {
        try {
            return ctx.getSharedPreferences("cf", Context.MODE_PRIVATE)
                    .getBoolean("proxy", false);
        } catch (Exception e) {
            return false;
        }
    }

    public static void setProxyMode(Context ctx, boolean on) {
        try {
            ctx.getSharedPreferences("cf", Context.MODE_PRIVATE)
                    .edit().putBoolean("proxy", on).apply();
        } catch (Exception ignored) {
        }
    }

    public static boolean isEnabled(Context ctx) {
        try {
            return ctx.getSharedPreferences("cf", Context.MODE_PRIVATE)
                    .getBoolean("enabled", false);
        } catch (Exception e) {
            return false;
        }
    }

    public static void setEnabled(Context ctx, boolean on) {
        try {
            ctx.getSharedPreferences("cf", Context.MODE_PRIVATE)
                    .edit().putBoolean("enabled", on).apply();
        } catch (Exception ignored) {
        }
    }

    public static String statusText(Context ctx) {
        if (isRunning()) {
            String u = url.isEmpty() ? "启动中，等待 Cloudflare 分配地址…" : url;
            return "Cloudflare 隧道运行中（" + (useProxy ? "走代理 7890 + http2" : "直连") + "）\n" + u;
        }
        if (!lastError.isEmpty()) {
            return "Cloudflare 隧道未运行\n上次错误：" + lastError;
        }
        return "Cloudflare 隧道未运行（未开启）";
    }

    /** 把 assets 里的 cloudflared 拷到私有目录并加执行权限；缺失时抛错。 */
    public static File ensureBinary(Context ctx) throws Exception {
        File bin = new File(ctx.getFilesDir(), "cloudflared");
        if (bin.exists() && bin.length() > 1000000 && bin.canExecute()) {
            return bin;
        }
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = ctx.getAssets().open("cloudflared");
            out = new FileOutputStream(bin);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            if (out != null) {
                try { out.close(); } catch (Exception ignored) {}
            }
            if (in != null) {
                try { in.close(); } catch (Exception ignored) {}
            }
        }
        bin.setExecutable(true, false);
        if (!bin.exists() || bin.length() < 1000000) {
            throw new Exception("cloudflared 二进制缺失或不完整");
        }
        return bin;
    }

    /** 直连启动（保持 3.67 行为）。 */
    public static synchronized void start(final Context ctx, final int port) {
        startWithProxy(ctx, port, false);
    }

    /** 走代理启动：HTTPS_PROXY 指到本机 clash 7890，并强制 http2 走 TCP，
     * 避免默认 QUIC（UDP）被透明代理/防火墙拦住；本机回环走 NO_PROXY。 */
    public static synchronized void startWithProxy(final Context ctx, final int port, final boolean proxy) {
        if (isRunning()) {
            return;
        }
        setProxyMode(ctx, proxy);
        startInternal(ctx, port, proxy);
    }

    /** 在后台线程启动快速隧道；URL 从进程输出里解析，解析到之前 url 为空。 */
    public static synchronized void startInternal(final Context ctx, final int port, final boolean proxy) {
        if (isRunning()) {
            return;
        }
        setEnabled(ctx, true);
        useProxy = proxy;
        lastError = "";
        url = "";
        final Context appCtx = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File bin = ensureBinary(appCtx);
                    java.util.List<String> args = new java.util.ArrayList<String>();
                    args.add(bin.getAbsolutePath());
                    args.add("tunnel");
                    if (proxy) {
                        args.add("--protocol");
                        args.add("http2");
                    }
                    args.add("--url");
                    args.add("http://127.0.0.1:" + port);
                    args.add("--no-autoupdate");
                    ProcessBuilder pb = new ProcessBuilder(args);
                    if (proxy) {
                        pb.environment().put("HTTPS_PROXY", "http://127.0.0.1:7890");
                        pb.environment().put("HTTP_PROXY", "http://127.0.0.1:7890");
                        pb.environment().put("NO_PROXY", "127.0.0.1,localhost");
                    }
                    pb.redirectErrorStream(true);
                    proc = pb.start();
                    running = true;
                    startedAt = System.currentTimeMillis();
                    BufferedReader br = new BufferedReader(
                            new InputStreamReader(proc.getInputStream(), "UTF-8"));
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line == null) {
                            break;
                        }
                        String low = line.toLowerCase();
                        // v3.72：报错行不解析地址（2026-10-29 实测：
                        // failed 行里的 https://api.trycloudflare.com/tunnel
                        // 曾被旧正则当成隧道地址，/status 误报）
                        if (low.contains("error") || low.contains("failed")
                                || low.contains("err ")) {
                            lastError = line.length() > 200
                                    ? line.substring(0, 200) : line;
                            continue;
                        }
                        Matcher m = URL_PAT.matcher(line);
                        if (m.find()) {
                            url = m.group();
                        }
                    }
                    try {
                        proc.waitFor();
                    } catch (Exception ignored) {
                    }
                } catch (Exception e) {
                    String msg = e.getMessage();
                    lastError = msg == null ? "启动失败" : msg;
                } finally {
                    running = false;
                    proc = null;
                    // v3.72：进程退出后地址即失效，清掉，免得 /status 还报旧地址
                    url = "";
                }
            }
        }, "cf-tunnel").start();
    }

    public static synchronized void stop(Context ctx) {
        setEnabled(ctx, false);
        stopProcessOnly();
    }

    /** 只杀进程、不清开关：服务销毁时用，下次服务启动按开关恢复。 */
    public static synchronized void stopProcessOnly() {
        try {
            if (proc != null) {
                proc.destroy();
            }
        } catch (Exception ignored) {
        }
        running = false;
        proc = null;
        url = "";
    }

    /** 服务启动时按开关恢复隧道（AgentService 调用）。 */
    public static void startIfEnabled(Context ctx, int port) {
        if (isEnabled(ctx) && !isRunning()) {
            startInternal(ctx, port, isProxyMode(ctx));
        }
    }
}
