package com.gsic.app;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * v2.93：任务运行详细日志。
 * 每次任务运行生成一个文件，按 年月日_时分秒 命名。
 * 层级结构：任务 → 步骤 → 思考/动作/结果/监管。
 */
public class TaskRunLogger {

    private final File logFile;
    private final StringBuilder sb = new StringBuilder();
    private final SimpleDateFormat tsFmt =
            new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    public TaskRunLogger(Context ctx, String goal) {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date());
        // 文件名加随机后缀防同秒冲突
        String name = "task_" + ts + "_" 
                + Integer.toHexString((int) (Math.random() * 0xFFFF)) + ".log";
        File dir = new File(ctx.getFilesDir(), "task_logs");
        // noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        // v3.11 Issue 7：每次建日志时顺手清理过期文件
        cleanupOldLogs(ctx);
        logFile = new File(dir, name);
        String startTs = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()).format(new Date());
        sb.append("════════ 任务开始 ").append(startTs).append(" ════════\n");
        sb.append("目标：").append(goal).append("\n\n");
        flush();
    }

    private String ts() {
        return tsFmt.format(new Date());
    }

    /** 步骤开始 */
    public synchronized void stepStart(int step) {
        sb.append("── 第 ").append(step).append(" 步 [").append(ts()).append("] ──\n");
    }

    /** 思考 */
    public synchronized void think(String think) {
        if (think == null || think.isEmpty()) return;
        sb.append("  💭 思考：").append(think).append("\n");
    }

    /** 进度 */
    public synchronized void state(String state) {
        if (state == null || state.isEmpty()) return;
        sb.append("  📍 进度：").append(state).append("\n");
    }

    /** 动作 */
    public synchronized void action(String desc) {
        sb.append("  ▶ 动作：").append(desc).append("\n");
    }

    /** 动作结果 */
    public synchronized void result(String res) {
        if (res == null || res.isEmpty()) return;
        String r = res.length() > 300 ? res.substring(0, 300) + "..." : res;
        sb.append("    └ 结果：").append(r).append("\n");
    }

    /** 监管者干预（含理由） */
    public synchronized void supervisor(String msg) {
        sb.append("  🔍 监管：").append(msg.replace("\n", "\n    ")).append("\n");
    }

    /** 截图标记 */
    public synchronized void screenshot() {
        sb.append("  👁 已截图\n");
    }

    /** 任务结束 */
    public synchronized void finish(boolean ok, String answer) {
        String endTs = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()).format(new Date());
        sb.append("\n════════ 任务").append(ok ? "完成" : "未完成")
                .append(" ").append(endTs).append(" ════════\n");
        if (answer != null && !answer.isEmpty()) {
            sb.append("最终答复：").append(answer).append("\n");
        }
        flush();
    }

    /** 更正结尾：终审改判等场景追加更正段，避免日志结尾与最终结果（遥测/远控）口径不一致 */
    public synchronized void correctFinish(boolean ok, String note) {
        String endTs = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                Locale.getDefault()).format(new Date());
        sb.append("\n════════ 更正 ").append(endTs).append(" ════════\n");
        sb.append("最终结果更正为：任务").append(ok ? "完成" : "未完成").append("\n");
        if (note != null && !note.isEmpty()) {
            sb.append(note).append("\n");
        }
        flush();
    }

    private void flush() {
        try (FileWriter w = new FileWriter(logFile, false)) {
            w.write(sb.toString());
        } catch (Exception ignored) {
        }
    }

    public String getPath() {
        return logFile.getAbsolutePath();
    }

    /** 列出所有任务日志（按时间倒序） */
    public static File[] listLogs(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "task_logs");
        File[] files = dir.listFiles((d, name) -> name.endsWith(".log"));
        if (files == null) return new File[0];
        java.util.Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
        return files;
    }

    /**
     * v3.11 Issue 7：清理过期日志。
     * 保留策略：默认保留最近 30 天，可在"agent"配置里改 log_keep_days。
     * 同时限制总数 200，超了删最老的。
     */
    public static void cleanupOldLogs(Context ctx) {
        try {
            int keepDays = ctx.getSharedPreferences("agent",
                    Context.MODE_PRIVATE).getInt("log_keep_days", 30);
            if (keepDays <= 0) return;
            File dir = new File(ctx.getFilesDir(), "task_logs");
            File[] files = dir.listFiles();
            if (files == null) return;
            long cutoff = System.currentTimeMillis()
                    - (long) keepDays * 24 * 3600 * 1000;
            java.util.Arrays.sort(files,
                    (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
            int alive = files.length;
            for (File f : files) {
                if (f.lastModified() < cutoff || alive > 200) {
                    // noinspection ResultOfMethodCallIgnored
                    if (f.delete()) alive--;
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** v3.11 Issue 7：日志目录总大小（字节） */
    public static long getLogDirSize(Context ctx) {
        long size = 0;
        try {
            File dir = new File(ctx.getFilesDir(), "task_logs");
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) size += f.length();
            }
        } catch (Exception ignored) {
        }
        return size;
    }
}
