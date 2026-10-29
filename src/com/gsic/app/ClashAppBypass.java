package com.gsic.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * v3.70: clash 按 App 直连开关。
 * 机制（用户亲定按 App 选，不是改 mihomo PROCESS-NAME）：
 * clash.iptables 的 CLASH_OUT 链本来就对 root（uid 0）与 Tailscale
 * UID 做 RETURN 豁免，之后才 MARK 进 TPROXY。按 App 直连就是
 * 给选中 App 的 UID 加同一条 RETURN，流量走正常路由直连，
 * DNS 仍走 clash 1053（DNS 不断），只对本机 App 生效，
 * 热点下设备不受影响（热点走 PREROUTING，无本机 UID）。
 * 持久化三件套，缺一不可，否则 clash 重启就丢：
 * 1）选单存 GSIC SharedPreferences clash_app_bypass（包名逗号串）；
 * 2）UID 写 /data/clash/app_bypass_uids.txt（一行一个 UID，root 写）；
 * 3）clash.iptables 只打一次钩子：TSUID 豁免行后加
 *    sh /data/clash/scripts/clash_app_bypass.sh，脚本读 UID 文件
 *    逐条加 RETURN；钩子打过就不再重复打，脚本首次备份到
 *    clash.iptables.bak-gsic。
 * 应用时 clash 在运行就即时插 iptables 生效，不重启 clash、
 * 不断网；clash 没运行就只存选单与钩子，下次启动自动生效。
 * GSIC 自身被选中时 cloudflared 也直连（子进程与 GSIC 同 UID）。
 * 调用方必须在后台线程调用 apply()，再把结果贴回 UI 线程。
 */
public class ClashAppBypass {

    private static final String PREFS = "gsic_clash_app";
    private static final String KEY = "bypass_pkgs";
    public static final String UID_FILE = "/data/clash/app_bypass_uids.txt";
    public static final String HOOK_SCRIPT = "/data/clash/scripts/clash_app_bypass.sh";
    public static final String IPTABLES = "/data/clash/scripts/clash.iptables";

    public static class AppItem {
        public String label = "";
        public String pkg = "";
        public int uid = -1;
    }

    public static class Result {
        public boolean ok = false;
        public String message = "";
    }

    public static Set<String> loadSelection(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY, "");
        Set<String> out = new HashSet<>();
        if (raw != null) {
            for (String s : raw.split(",")) {
                s = s.trim();
                if (!s.isEmpty()) out.add(s);
            }
        }
        return out;
    }

    public static void saveSelection(Context ctx, Set<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p);
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, sb.toString()).apply();
    }

    /** 非系统已装 App + GSIC 自身，按应用名排序；后台线程调用。 */
    public static List<AppItem> listApps(Context ctx) {
        List<AppItem> out = new ArrayList<>();
        try {
            PackageManager pm = ctx.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : apps) {
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean updatedSystem = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                if (system && !updatedSystem && !ai.packageName.equals(ctx.getPackageName())) {
                    continue;
                }
                AppItem it = new AppItem();
                it.pkg = ai.packageName;
                it.uid = ai.uid;
                try {
                    CharSequence label = pm.getApplicationLabel(ai);
                    it.label = label != null ? label.toString() : ai.packageName;
                } catch (Exception e) {
                    it.label = ai.packageName;
                }
                out.add(it);
            }
            Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String[] exec(String cmd, int timeoutSec) {
        try {
            return RootShell.get().exec(cmd, timeoutSec);
        } catch (Exception e) {
            return new String[]{"-1", String.valueOf(e.getMessage())};
        }
    }

    private static boolean ok(String[] r) {
        return r != null && r.length >= 2 && "0".equals(r[0]);
    }

    /** 应用当前选单：写 UID 文件、打钩子、clash 在运行就即时生效。 */
    public static Result apply(Context ctx, Set<String> pkgs) {
        Result res = new Result();
        try {
            saveSelection(ctx, pkgs);
            // UID：优先用 PackageManager 的 uid，拿不到的包跳过并记名
            List<String> uids = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            PackageManager pm = ctx.getPackageManager();
            for (String pkg : pkgs) {
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    uids.add(String.valueOf(ai.uid));
                } catch (Exception e) {
                    skipped.add(pkg);
                }
            }
            // 写 UID 文件（空选单也写空文件，等于清掉旧豁免）
            StringBuilder uidCmd = new StringBuilder("printf '' > " + UID_FILE);
            for (String u : uids) {
                uidCmd.append(" && printf '%s\\n' ").append(u).append(" >> ").append(UID_FILE);
            }
            String[] w = exec(uidCmd.toString(), 15);
            if (!ok(w)) {
                res.message = "写 UID 文件失败（需 root），选单已存但未生效";
                return res;
            }
            // 写钩子脚本（每次重写，内容固定，幂等）
            String script = "printf '%s\\n' "
                    + "'#!/system/bin/sh' "
                    + "'if [ -f " + UID_FILE + " ]; then' "
                    + "'  while read -r buid; do' "
                    + "'    [ -n \"$buid\" ] || continue' "
                    + "'    iptables -t mangle -A CLASH_OUT -m owner --uid-owner \"$buid\" -j RETURN' "
                    + "'  done < " + UID_FILE + "' "
                    + "'fi' "
                    + "> " + HOOK_SCRIPT + " && chmod +x " + HOOK_SCRIPT;
            String[] s = exec(script, 15);
            if (!ok(s)) {
                res.message = "写钩子脚本失败，选单已存但未生效";
                return res;
            }
            // 打钩子：已含钩子就跳过，否则先备份再插一行
            String[] has = exec("grep -q clash_app_bypass.sh " + IPTABLES + " && echo HAS_HOOK || echo NO_HOOK", 15);
            boolean hasHook = has != null && has.length >= 2 && has[1] != null && has[1].contains("HAS_HOOK");
            if (!hasHook) {
                String[] bak = exec("cp " + IPTABLES + " " + IPTABLES + ".bak-gsic 2>/dev/null; echo bak_done", 15);
                if (!ok(bak)) {
                    res.message = "备份 clash.iptables 失败，未打钩子";
                    return res;
                }
                String awk = "awk '/TSUID.*CLASH_OUT/{print; print \"  sh " + HOOK_SCRIPT + " 2>/dev/null || true\"; next}1' "
                        + IPTABLES + " > " + IPTABLES + ".gsic-tmp && mv " + IPTABLES + ".gsic-tmp " + IPTABLES;
                String[] patch = exec(awk, 15);
                if (!ok(patch)) {
                    res.message = "打钩子失败，未生效";
                    return res;
                }
                String[] verify = exec("grep -q clash_app_bypass.sh " + IPTABLES + " && echo HOOK_OK || echo HOOK_MISS", 15);
                if (verify == null || verify.length < 2 || !verify[1].contains("HOOK_OK")) {
                    exec("cp " + IPTABLES + ".bak-gsic " + IPTABLES, 15);
                    res.message = "钩子校验未命中，已还原 clash.iptables";
                    return res;
                }
            }
            // 即时生效：clash 在运行才有 CLASH_OUT 链
            String[] status = exec("/data/clash/scripts/clash.service status", 15);
            boolean running = status != null && status.length >= 2 && status[1] != null && status[1].contains("running");
            if (!running) {
                res.ok = true;
                res.message = "选单已存（" + pkgs.size() + " 个），钩子已打好，但 clash 当前没运行，"
                        + "下次 clash 启动时自动生效；要立即生效先启动 clash 再点一次应用。";
                return res;
            }
            // 先清掉旧的本文件豁免（按 UID 逐条删，删不到不算错），再按新选单插
            // 注意：只删 UID 文件里当前这批与上次选单的交集无法追溯，
            // 所以这里直接重建 CLASH_OUT 的豁免段过于危险，改用：
            // 对每个新 UID 先查后插（已存在不重复），对已取消的 App
            // 用户需重启 clash 一次才能彻底清掉——把这点写明。
            StringBuilder live = new StringBuilder("echo live_start");
            for (String u : uids) {
                live.append(" && (iptables -t mangle -C CLASH_OUT -m owner --uid-owner ").append(u)
                        .append(" -j RETURN 2>/dev/null || iptables -t mangle -I CLASH_OUT 1 -m owner --uid-owner ")
                        .append(u).append(" -j RETURN)");
            }
            String[] liveRes = exec(live.toString(), 30);
            if (!ok(liveRes)) {
                res.message = "即时插 iptables 失败，但选单与钩子已存，重启 clash 后自动生效";
                return res;
            }
            res.ok = true;
            res.message = "已应用：直连 " + pkgs.size() + " 个 App（即时生效，不用重启 clash）。"
                    + "注意：取消勾选的 App 要重启 clash 一次才能彻底清掉旧豁免，"
                    + "新勾选的立即生效。"
                    + (skipped.isEmpty() ? "" : " 未找到 UID 已跳过：" + skipped);
            return res;
        } catch (Exception e) {
            res.message = "应用失败：" + String.valueOf(e.getMessage());
            return res;
        }
    }

    /** v3.71: clash 重启开关（用户亲定）：调 clash.service restart，重启后查 status。 */
    public static Result restartClash() {
        Result res = new Result();
        try {
            String[] r = exec("/data/clash/scripts/clash.service restart", 60);
            String[] status = exec("/data/clash/scripts/clash.service status", 15);
            boolean running = status != null && status.length >= 2 && status[1] != null && status[1].contains("running");
            if (running) {
                res.ok = true;
                res.message = "clash 已重启并运行（" + status[1].trim() + "）。"
                        + "按 App 直连的豁免由 clash.iptables 钩子在启动时自动从 UID 文件恢复，"
                        + "不用再点应用；如果某个 App 还是走代理，重启后点一次应用再试。";
            } else {
                res.message = "clash 重启后未运行（restart 输出：" + (r != null && r.length >= 2 ? r[1].trim() : "")
                        + "；status：" + (status != null && status.length >= 2 ? status[1].trim() : "")
                        + "）。注意：clash 停了不等于断网，流量会走直连；再点一次重启，或用 root shell 手动查 /data/clash/run/kernel.log。";
            }
            return res;
        } catch (Exception e) {
            res.message = "clash 重启异常：" + String.valueOf(e.getMessage());
            return res;
        }
    }
}
