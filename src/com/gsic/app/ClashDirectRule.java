package com.gsic.app;

/**
 * v3.69: GSIC 自动给 clash 加 trycloudflare 直连规则。
 * 背景：3.67 直连与 3.68 走代理 7890 申请快速隧道都超时，
 * 停 clash 又会断 DNS/断网，所以不再停 clash，改为让这个端点
 * 绕过机场节点直连，其他流量与 DNS 仍走 clash。
 * 依据 clash.service（2026-10-23 重建版）：启动时 cat template +
 * config.yaml 合并成 run/config.yaml，所以规则要加进含 rules:
 * 的那一份源文件里，插在 rules: 紧后一行（mihomo 规则先匹配先生效）。
 * 安全：先备份两份源文件到 /data/clash/backup-gsic-<时间戳>/，
 * 已有规则时不重复插；重启后 status 不是 running 就立即用备份
 * 还原并再重启一次，绝不把用户留在没网的状态。
 * 本类只跑 root shell 命令，不直接读写 /data/clash（GSIC 进程
 * 本身无权读写 root 目录），调用方必须在后台线程调用 apply()，
 * 再把结果贴回 UI 线程。
 */
public class ClashDirectRule {

    public static final String RULE = "DOMAIN-SUFFIX,trycloudflare.com,DIRECT";

    public static class Result {
        public boolean ok = false;
        public String message = "";
        public String backupDir = "";
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

    public static Result apply() {
        Result res = new Result();
        try {
            // 1. 源文件必须都在，否则不动
            String[] ls = exec("ls /data/clash/template /data/clash/config.yaml /data/clash/scripts/clash.service", 15);
            if (!ok(ls)) {
                res.message = "clash 配置文件不全，未改动：" + (ls.length >= 2 ? ls[1] : "");
                return res;
            }
            // 2. 备份
            String ts = String.valueOf(System.currentTimeMillis() / 1000L);
            res.backupDir = "/data/clash/backup-gsic-" + ts;
            String[] bak = exec("mkdir -p " + res.backupDir
                    + " && cp /data/clash/template " + res.backupDir + "/template"
                    + " && cp /data/clash/config.yaml " + res.backupDir + "/config.yaml"
                    + " && cp /data/clash/run/config.yaml " + res.backupDir + "/run-config.yaml 2>/dev/null; echo backup_done", 15);
            if (!ok(bak)) {
                res.message = "备份失败，未改动";
                return res;
            }
            // 3. 已有规则就不重复插，直接重启
            String[] has = exec("grep -q trycloudflare.com /data/clash/template /data/clash/config.yaml 2>/dev/null && echo HAS_RULE || echo NO_RULE", 15);
            boolean already = has != null && has.length >= 2 && has[1] != null && has[1].contains("HAS_RULE");
            String target = "";
            if (!already) {
                // 4. 找含 rules: 的源文件，插在 rules: 紧后一行
                String[] find = exec("grep -l '^rules:' /data/clash/template /data/clash/config.yaml 2>/dev/null | head -1", 15);
                target = find != null && find.length >= 2 ? find[1].trim() : "";
                if (target.isEmpty()) {
                    res.message = "未找到含 rules: 的 clash 源文件，未改动（备份在 " + res.backupDir + "）";
                    return res;
                }
                String awk = "awk '/^rules:/{print; print \"  - " + RULE + "\"; next}1' " + target + " > " + target + ".gsic-tmp && mv " + target + ".gsic-tmp " + target;
                String[] ins = exec(awk, 15);
                if (!ok(ins)) {
                    res.message = "插规则失败，未重启（备份在 " + res.backupDir + "）";
                    return res;
                }
                String[] verify = exec("grep -n trycloudflare.com " + target, 15);
                if (verify == null || verify.length < 2 || !verify[1].contains("trycloudflare.com")) {
                    rollback(res);
                    res.message = "插规则后校验未命中，已回滚还原并重启";
                    return res;
                }
            }
            // 5. 重启 clash（合并配置 + -t 校验 + iptables）
            String[] restart = exec("/data/clash/scripts/clash.service restart", 60);
            String[] status = exec("/data/clash/scripts/clash.service status", 15);
            boolean running = status != null && status.length >= 2 && status[1] != null && status[1].contains("running");
            if (!running) {
                rollback(res);
                res.message = "重启后 clash 未运行，已用备份还原并重启；隧道先别测，等 clash 恢复后再说";
                return res;
            }
            res.ok = true;
            res.message = "直连规则已加" + (already ? "（本来就有，未重复插）" : "（插进 " + target + "）")
                    + "，clash 已重启并运行，备份在 " + res.backupDir
                    + "。接下来按直连启动隧道测一次。";
            return res;
        } catch (Exception e) {
            try {
                rollback(res);
            } catch (Exception ignored) {
            }
            res.ok = false;
            res.message = "加规则异常，已尝试回滚：" + String.valueOf(e.getMessage());
            return res;
        }
    }

    private static void rollback(Result res) {
        if (res.backupDir == null || res.backupDir.isEmpty()) {
            return;
        }
        exec("cp " + res.backupDir + "/template /data/clash/template"
                + " && cp " + res.backupDir + "/config.yaml /data/clash/config.yaml"
                + " && /data/clash/scripts/clash.service restart", 60);
    }
}
