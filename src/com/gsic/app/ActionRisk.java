package com.gsic.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * 动作风险分级（编译期固化，对标 FoneClaw 的风险等级思想）。
 *
 * <p>风险等级写在代码里，不在 prompt 里：被操纵的 prompt 改不了代码允许什么。
 * 每个 TaskRunner 动作在 {@link #levelFor(String)} 中有唯一确定的等级，
 * 用户只能调每级的审批策略，不能把高风险动作降级。
 *
 * <p>四级：
 * <ul>
 * <li>LOW —— 只读/无害：读屏、等待、断言、休眠、截屏</li>
 * <li>MEDIUM —— 设备控制/本地读：点击、滑动、输入、启动应用、读文件、技能调用</li>
 * <li>HIGH —— 外部效应/网络/写文件：termux 命令、联网搜索、抓网页、写文件</li>
 * <li>CRITICAL —— 破坏性：root shell 任意命令</li>
 * </ul>
 *
 * <p>每级三档策略：ALLOW 自动允许 / ASK 每次确认 / DENY 拒绝。
 * 默认：LOW=ALLOW，MEDIUM=ALLOW，HIGH=ASK，CRITICAL=ASK。
 */
public class ActionRisk {

    public enum Level {
        LOW("🟢 只读"),
        MEDIUM("🟡 设备控制"),
        HIGH("🟠 外部效应"),
        CRITICAL("🔴 破坏性");

        public final String label;

        Level(String label) {
            this.label = label;
        }
    }

    /** 策略：0=自动允许，1=每次确认，2=拒绝 */
    public static final int ALLOW = 0;
    public static final int ASK = 1;
    public static final int DENY = 2;

    private static final Map<String, Level> LEVELS = new HashMap<>();

    static {
        // LOW：只读/无害
        put(Level.LOW, "wait_text", "assert_text", "sleep", "screenshot",
                "app_list", "sys_get", "getprop", "ps", "remember");
        // MEDIUM：设备控制 / 本地读
        put(Level.MEDIUM, "tap", "tap_text", "tap_id", "swipe", "back", "home",
                "input", "launch", "read_file", "use_card", "skill",
                "screen", "brightness", "volume", "vibrate", "torch",
                "wifi", "bluetooth", "app_kill");
        // HIGH：外部效应 / 网络 / 写文件；v3.57 读别家私有数据（read_file_root）升级审批
        put(Level.HIGH, "termux", "search", "fetch_url", "write_file",
                "app_install", "sys_put", "airplane", "read_file_root");
        // CRITICAL：破坏性（root 任意命令 / 删应用 / 重启）；v3.57 root 写域外（write_file_root）与片段编辑（edit_file）同级
        put(Level.CRITICAL, "shell", "app_uninstall", "app_clear",
                "reboot", "kill", "write_file_root", "edit_file");
    }

    private static void put(Level lv, String... actions) {
        for (String a : actions) LEVELS.put(a, lv);
    }

    /** 查动作的风险等级；未知动作按最高级处理（fail-closed）。纯函数，可独立测试。 */
    public static Level levelFor(String action) {
        if (action == null) return Level.CRITICAL;
        Level lv = LEVELS.get(action);
        return lv == null ? Level.CRITICAL : lv;
    }

    /** 用户可配的三档策略，存 SharedPreferences。 */
    public static class Policy {
        private static final String PREF = "risk_policy";
        private final SharedPreferences sp;

        public Policy(Context ctx) {
            sp = ctx.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE);
        }

        public int get(Level lv) {
            return sp.getInt("p_" + lv.name(), defaultFor(lv));
        }

        public void set(Level lv, int policy) {
            sp.edit().putInt("p_" + lv.name(), policy).apply();
        }

        public static int defaultFor(Level lv) {
            switch (lv) {
                case LOW:
                case MEDIUM:
                    return ALLOW;
                case HIGH:
                case CRITICAL:
                default:
                    return ASK;
            }
        }

        public static String policyLabel(int p) {
            switch (p) {
                case ALLOW:
                    return "自动允许";
                case DENY:
                    return "拒绝";
                case ASK:
                default:
                    return "每次确认";
            }
        }
    }
}
