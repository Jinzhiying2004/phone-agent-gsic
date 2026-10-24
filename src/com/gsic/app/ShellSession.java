package com.gsic.app;

import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

/**
 * 常驻交互式 shell 会话（普通 sh 或 su root shell）。
 * 纯 Java（仅 java.io/java.lang），可在桌面 JVM 独立实测。
 */
public class ShellSession {

    public interface Listener {
        void onOutput(String s);

        void onExit(int code);
    }

    private final Listener listener;
    private Process process;
    private BufferedWriter writer;
    private volatile String cwd = "/";
    private volatile boolean running;

    public ShellSession(Listener listener) {
        this.listener = listener;
    }

    public synchronized boolean start(boolean asRoot) {
        if (asRoot) {
            String su = Root.findSu();
            if (su == null) {
                listener.onOutput("[启动失败] 未找到 su\n");
                return false;
            }
            return start(new String[]{su}, new java.util.HashMap<String, String>());
        }
        return start(new String[]{"sh"},
                new java.util.HashMap<String, String>());
    }

    /**
     * 启动自定义命令的交互式会话，并注入环境变量。
     * 例如：在 Termux 环境中跑 bash --login。
     */
    public synchronized boolean start(String[] command,
                                      java.util.Map<String, String> env) {
        stop();
        try {
            ProcessBuilder pb = new ProcessBuilder(command)
                    .redirectErrorStream(true);
            if (env != null && !env.isEmpty()) {
                pb.environment().putAll(env);
            }
            process = pb.start();
            writer = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), "UTF-8"));
            running = true;
            Thread reader = new Thread(this::pump, "shell-reader");
            reader.setDaemon(true);
            reader.start();
            Thread watcher = new Thread(() -> {
                try {
                    int code = process.waitFor();
                    running = false;
                    listener.onExit(code);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "shell-watcher");
            watcher.setDaemon(true);
            watcher.start();
            return true;
        } catch (Exception e) {
            listener.onOutput("[启动失败] " + e.getMessage() + "\n");
            return false;
        }
    }

    private void pump() {
        try {
            InputStreamReader r =
                    new InputStreamReader(process.getInputStream(), "UTF-8");
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) {
                listener.onOutput(new String(buf, 0, n));
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized void exec(String cmd) {
        if (!running || writer == null) return;
        try {
            trackCd(cmd.trim());
            writer.write(cmd);
            writer.newLine();
            writer.flush();
        } catch (Exception ignored) {
        }
    }

    /** 跟踪 cd 以显示当前目录（尽力而为） */
    private void trackCd(String t) {
        if (t.equals("cd") || t.startsWith("cd ")) {
            String arg = t.length() > 2 ? t.substring(2).trim() : "";
            // 去掉引号
            if (arg.length() >= 2 && arg.startsWith("\"") && arg.endsWith("\"")) {
                arg = arg.substring(1, arg.length() - 1);
            }
            if (arg.isEmpty() || arg.equals("~") || arg.equals("$HOME")) {
                cwd = "/";
            } else if (arg.startsWith("/")) {
                cwd = arg;
            } else if (arg.equals("..")) {
                int i = cwd.lastIndexOf('/');
                cwd = i <= 0 ? "/" : cwd.substring(0, i);
            } else if (!arg.equals(".")) {
                cwd = cwd.endsWith("/") ? cwd + arg : cwd + "/" + arg;
            }
        }
    }

    public String getCwd() {
        return cwd;
    }

    public boolean isRunning() {
        return running;
    }

    public synchronized void stop() {
        running = false;
        try {
            if (writer != null) writer.close();
        } catch (Exception ignored) {
        }
        try {
            if (process != null) process.destroy();
        } catch (Exception ignored) {
        }
        writer = null;
        process = null;
    }
}
