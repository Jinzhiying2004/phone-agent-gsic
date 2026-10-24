package com.gsic.app;

import android.content.Context;
import android.content.SharedPreferences;

public class TermsGate {
    public static final String CORE_TEXT = "GSIC终极目标：作为智能体OS运行在硬件设备上（掌上设备与家庭中枢）；现阶段为寄生在手机系统中的软件，后续升级仍在寄生阶段；创新是GSIC存在的意义；使用须共享算力并每周提交一次使用体验沉淀总结到仓库反馈。";
    public static final String AGREEMENT_TEXT = "GSIC使用协议：使用本软件须共享算力，并每周提交一次使用体验沉淀总结到仓库反馈（GitHub Issues），由你授权的AI办理，提醒仅首次一次。不同意无法使用。";
    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences("gsic_terms", Context.MODE_PRIVATE);
    }
    public static boolean isAccepted(Context ctx) {
        try { return prefs(ctx).getBoolean("accepted", false); } catch (Exception e) { return false; }
    }
    public static void accept(Context ctx) {
        try {
            SharedPreferences.Editor ed = prefs(ctx).edit();
            ed.putBoolean("accepted", true);
            ed.putBoolean("ai_authorized", true);
            ed.putLong("accepted_at", System.currentTimeMillis());
            ed.apply();
        } catch (Exception ignored) {}
    }
    public static boolean weeklyDue(Context ctx) {
        try {
            long last = prefs(ctx).getLong("last_weekly_ack", 0);
            if (last == 0) last = prefs(ctx).getLong("accepted_at", 0);
            if (last == 0) return false;
            return System.currentTimeMillis() - last >= 7L * 24 * 60 * 60 * 1000;
        } catch (Exception e) { return false; }
    }
    public static boolean weeklyRemindedForDue(Context ctx) {
        try { return prefs(ctx).getBoolean("weekly_reminded", false); } catch (Exception e) { return false; }
    }
    public static void markWeeklyReminded(Context ctx) {
        try { prefs(ctx).edit().putBoolean("weekly_reminded", true).apply(); } catch (Exception ignored) {}
    }
    public static void markWeeklyAck(Context ctx, String issueUrl) {
        try {
            SharedPreferences.Editor ed = prefs(ctx).edit();
            ed.putLong("last_weekly_ack", System.currentTimeMillis());
            ed.putBoolean("weekly_reminded", false);
            if (issueUrl != null) ed.putString("last_weekly_issue", issueUrl);
            ed.apply();
        } catch (Exception ignored) {}
    }
    public static boolean isFirstRemoteTold(Context ctx) {
        try { return prefs(ctx).getBoolean("first_remote_told", false); } catch (Exception e) { return false; }
    }
    public static void markFirstRemoteTold(Context ctx) {
        try { prefs(ctx).edit().putBoolean("first_remote_told", true).apply(); } catch (Exception ignored) {}
    }
    public static String weeklySummary(Context ctx) {
        try {
            java.io.File f = SedimentReport.generate(ctx, 7);
            StringBuilder sb = new StringBuilder();
            sb.append("GSIC每周沉淀总结（脱敏聚合，非原始记录）"); sb.append(NL);
            if (f == null) {
                sb.append("本周无可汇总内容。"); sb.append(NL);
                return sb.toString();
            }
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
            String ln;
            boolean skip = false;
            int n = 0;
            while ((ln = r.readLine()) != null) {
                String s = ln.trim();
                if (s.startsWith("## ") || s.startsWith("---")) skip = false;
                if (s.contains("失败任务样本") || s.contains("本地路由未命中") || s.startsWith("## 闲聊")) { skip = true; continue; }
                if (skip) continue;
                if (s.isEmpty()) continue;
                if (s.length() > 200) s = s.substring(0, 200);
                sb.append(s); sb.append(NL);
                n++;
                if (n >= 60) break;
            }
            r.close();
            sb.append("说明：本总结仅含聚合经验与失败病因，不含原始任务目标与私人内容。"); sb.append(NL);
            return sb.toString();
        } catch (Exception e) { return "GSIC每周沉淀总结：本周无可汇总内容。"; }
    }
    private static final String NL = String.valueOf((char)10);
}
