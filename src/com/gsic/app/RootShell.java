package com.gsic.app;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

/**
 * 常驻 root shell 桥（对标 DSHA-FR 的 root 直通桥，轻量版）。
 * <p>
 * 旧方案每个 shell 步骤都 spawn 一次 {@code su -c}，慢且在部分设备上
 * 因 su 不在 PATH 而失败。新方案维护一个常驻 su 会话，命令通过 stdin
 * 写入、用唯一 marker 回读 exit code，避免重复拉起进程。
 * <p>
 * 纯 Java（仅 java.io/java.lang），可在桌面 JVM 独立实测
 * （测试时用 sh 代替 su）。
 */
public class RootShell {

    private final String[] shellCmd;
    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;

    /** 生产用：su 路径经 Root.findSu() 探测 */
    public RootShell() {
        this(null);
    }

    /** 测试用：可注入任意 shell（如 sh） */
    RootShell(String[] shellCmd) {
        this.shellCmd = shellCmd;
    }

    private synchronized boolean ensure() {
        try {
            if (process != null && process.isAlive()) return true;
            String[] cmd = shellCmd;
            if (cmd == null) {
                String su = Root.findSu();
                if (su == null) return false;
                cmd = new String[]{su};
            }
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            writer = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), "UTF-8"));
            reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), "UTF-8"));
            return true;
        } catch (Exception e) {
            process = null;
            return false;
        }
    }

    /**
     * 同步执行命令。
     *
     * @return String[2]{exitCode, output}；su 不可用时 exitCode 为 -1
     */
    public synchronized String[] exec(String cmd, int timeoutSec) {
        if (!ensure()) return new String[]{"-1", "su not available"};
        try {
            String marker = "__RC" + System.nanoTime() + "__";
            writer.write(cmd);
            writer.newLine();
            writer.write("echo " + marker + "$?");
            writer.newLine();
            writer.flush();
            // v2.45-p1 修复：裸 reader.readLine() 会无限阻塞，
            // 超时检查永远执行不到，hung 命令会永久占住 synchronized 锁。
            // 改为 ready() 轮询 + 非阻塞读，超时则杀掉会话下次重建。
            StringBuilder out = new StringBuilder();
            StringBuilder pending = new StringBuilder();
            long end = System.currentTimeMillis() + timeoutSec * 1000L;
            int code = -1;
            boolean done = false;
            boolean timedOut = false;
            char[] buf = new char[8192];
            while (!done) {
                long remain = end - System.currentTimeMillis();
                if (remain <= 0) {
                    timedOut = true;
                    break;
                }
                if (reader.ready()) {
                    int n = reader.read(buf, 0, buf.length);
                    if (n < 0) break; // EOF
                    pending.append(buf, 0, n);
                    int idx;
                    while ((idx = pending.indexOf("\n")) >= 0) {
                        String line = pending.substring(0, idx);
                        pending.delete(0, idx + 1);
                        if (line.endsWith("\r")) {
                            line = line.substring(0, line.length() - 1);
                        }
                        // p157-p4：marker 改行内包含匹配。
                        // 根因（2026-10-21 实测定位）：上一个命令的输出若无
                        // 尾换行，下一个命令的 echo 回显 marker 会与输出粘成
                        // 同一行，行首匹配永远命中不了，必 30 秒超时
                        // （典型：cat 读 llm_profiles.json 等无尾换行文件）。
                        int mIdx = line.indexOf(marker);
                        if (mIdx >= 0) {
                            String before = line.substring(0, mIdx);
                            if (!before.isEmpty()) {
                                out.append(before).append('\n');
                            }
                            try {
                                code = Integer.parseInt(
                                        line.substring(mIdx + marker.length())
                                                .trim());
                            } catch (NumberFormatException ignored) {
                            }
                            done = true;
                            break;
                        }
                        out.append(line).append('\n');
                    }
                } else {
                    Thread.sleep(Math.min(50, remain));
                }
            }
            if (timedOut) {
                close(); // hung 命令占住会话：杀掉，下次 ensure() 重建
                return new String[]{"-1", "__TIMEOUT__"};
            }
            return new String[]{String.valueOf(code), out.toString()};
        } catch (Exception e) {
            close();
            return new String[]{"-1", String.valueOf(e.getMessage())};
        }
    }

    public synchronized void close() {
        try {
            if (process != null) process.destroy();
        } catch (Exception ignored) {
        }
        process = null;
        writer = null;
        reader = null;
    }

    // ---- 单例，供 TaskRunner 使用 ----

    private static volatile RootShell instance;

    public static RootShell get() {
        RootShell r = instance;
        if (r == null) {
            synchronized (RootShell.class) {
                r = instance;
                if (r == null) {
                    r = new RootShell();
                    instance = r;
                }
            }
        }
        return r;
    }
}
