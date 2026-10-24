package com.gsic.app;

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 用户行为 + 软件反馈记录器。
 * 用于"软件自检"：用户开启后，记录用户在软件内的操作
 * 和软件做出的反馈，可查看、导出、清空。
 * 存 files/behavior.log（追加，自动轮转）。
 */
public class BehaviorLog {

    /** 用户操作 */
    public static final String USER = "用户";
    /** 软件反馈 */
    public static final String APP = "软件";
    /** 系统事件 */
    public static final String SYS = "系统";

    private static final long MAX_SIZE = 512 * 1024; // 512KB 轮转

    private final File file;
    private volatile boolean recording = false;
    private final SimpleDateFormat fmt =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());

    public BehaviorLog(Context ctx) {
        file = new File(ctx.getApplicationContext().getFilesDir(),
                "behavior.log");
    }

    /** 开始自检记录 */
    public synchronized void start() {
        recording = true;
        log(SYS, "开始自检记录", "");
    }

    /** 停止自检记录 */
    public synchronized void stop() {
        log(SYS, "停止自检记录", "");
        recording = false;
    }

    public boolean isRecording() {
        return recording;
    }

    /** 记录一条（仅在记录中时写入文件） */
    public void log(String kind, String action, String detail) {
        if (!recording) return;
        try {
            rotateIfNeeded();
            String line = fmt.format(new Date())
                    + " [" + kind + "] " + action
                    + (detail == null || detail.isEmpty()
                    ? "" : " —— " + detail)
                    + "\n";
            Files.write(file.toPath(),
                    line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    public void user(String action) {
        log(USER, action, "");
    }

    public void user(String action, String detail) {
        log(USER, action, detail);
    }

    public void app(String action) {
        log(APP, action, "");
    }

    public void app(String action, String detail) {
        log(APP, action, detail);
    }

    /** 读取全部记录（最新在前，最多 500 条） */
    public List<String> list() {
        List<String> out = new ArrayList<>();
        try {
            if (!file.exists()) return out;
            List<String> lines = Files.readAllLines(file.toPath(),
                    StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0
                    && out.size() < 500; i--) {
                out.add(lines.get(i));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 导出文件 */
    public File exportFile(Context ctx) throws Exception {
        String name = "behavior_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss",
                Locale.getDefault()).format(new Date()) + ".txt";
        File out = new File(ctx.getExternalFilesDir(null), name);
        StringBuilder sb = new StringBuilder();
        sb.append("PhoneAgent 自检记录\n");
        sb.append("导出时间: ").append(
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                        Locale.getDefault()).format(new Date()))
                .append("\n================================\n\n");
        if (file.exists()) {
            List<String> lines = Files.readAllLines(file.toPath(),
                    StandardCharsets.UTF_8);
            for (String l : lines) sb.append(l).append("\n");
        } else {
            sb.append("（暂无记录）\n");
        }
        Files.write(out.toPath(),
                sb.toString().getBytes(StandardCharsets.UTF_8));
        return out;
    }

    public void clear() {
        try {
            if (file.exists()) file.delete();
        } catch (Exception ignored) {
        }
    }

    private void rotateIfNeeded() {
        try {
            if (file.exists() && file.length() > MAX_SIZE) {
                File bak = new File(file.getParent(), "behavior.old.log");
                if (bak.exists()) bak.delete();
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(bak);
            }
        } catch (Exception ignored) {
        }
    }
}
