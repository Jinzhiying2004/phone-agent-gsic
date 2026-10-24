package com.gsic.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.provider.AlarmClock;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 高频意图本地路由：这类请求意图明确、无需大模型思考，
 * 直接走本地系统能力，零 token、更快更稳（对标 Hermes 的确定性本地指令路由）。
 *
 * <p>当前覆盖（全部无需危险权限）：
 * <ul>
 * <li>设闹钟：「明天早上7点设个闹钟」「定个7:30的闹钟」→ 系统闹钟</li>
 * <li>计时器：「倒计时10分钟」「计时5分钟」→ 系统计时器</li>
 * <li>打开应用：「打开微信」「启动相机」→ 按应用名匹配启动</li>
 * <li>响铃模式：「静音」「震动」「响铃/正常模式」→ AudioManager</li>
 * </ul>
 */
public class LocalIntentRouter {

    public static class RouteResult {
        public final boolean handled;
        public final String message;

        public RouteResult(boolean handled, String message) {
            this.handled = handled;
            this.message = message;
        }
    }

    /** 尝试本地路由；不匹配返回 null（调用方继续走 agent 流程）。 */
    public static RouteResult tryRoute(Context ctx, String goal) {
        if (goal == null) return null;
        String t = goal.trim();
        RouteResult r;
        r = tryHome(t);
        if (r != null) return r;
        r = tryAlbum(ctx, t);
        if (r != null) return r;
        r = tryAlarm(ctx, t);
        if (r != null) return r;
        r = tryTimer(ctx, t);
        if (r != null) return r;
        r = tryOpenApp(ctx, t);
        if (r != null) return r;
        r = tryRinger(ctx, t);
        if (r != null) return r;
        r = tryVolume(ctx, t);
        if (r != null) return r;
        r = tryBattery(ctx, t);
        if (r != null) return r;
        return null;
    }

    // ---------------- 音量（v3.22-hotfix：直达层扩展） ----------------

    private static RouteResult tryVolume(Context ctx, String t) {
        if (t.length() > 10) return null;
        int key = -1;
        String label = null;
        if (t.matches(".*(音量|声音).*(调大|调高|大一点|大声|增大|提高).*")
                || t.equals("音量调大") || t.equals("声音调大")) {
            key = 24; label = "音量调大"; // KEYCODE_VOLUME_UP
        } else if (t.matches(".*(音量|声音).*(调小|调低|小一点|小声|减小|降低).*")
                || t.equals("音量调小") || t.equals("声音调小")) {
            key = 25; label = "音量调小"; // KEYCODE_VOLUME_DOWN
        }
        if (key < 0) return null;
        try {
            // 按 3 次，每次一步
            String cmd = "input keyevent " + key + "; input keyevent " + key + "; input keyevent " + key;
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            boolean ok = p.waitFor() == 0;
            return new RouteResult(ok, ok ? "🔊 已" + label + "（本地直达，零 token）" : "🔊 调节失败");
        } catch (Exception e) {
            return new RouteResult(false, "🔊 调节失败：" + e.getMessage());
        }
    }

    // ---------------- 电量（v3.22-hotfix：直达层扩展） ----------------

    private static RouteResult tryBattery(Context ctx, String t) {
        if (t.length() > 8) return null;
        if (!(t.contains("电量") || t.contains("电池"))) return null;
        try {
            android.content.IntentFilter ifilter =
                    new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
            android.content.Intent batteryStatus = ctx.registerReceiver(null, ifilter);
            if (batteryStatus == null) return new RouteResult(false, "🔋 读不到电池状态");
            int level = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
            int status = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
            int pct = (level >= 0 && scale > 0) ? (level * 100 / scale) : -1;
            boolean charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING
                    || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
            return new RouteResult(true, "🔋 当前电量 " + pct + "%" + (charging ? "（充电中）" : ""));
        } catch (Exception e) {
            return new RouteResult(false, "🔋 查询失败：" + e.getMessage());
        }
    }

    // ---------------- 回桌面（主屏，零 token 直达） ----------------

    private static RouteResult tryHome(String t) {
        if (t.length() > 8) return null;
        String s = t.replace(" ", "").replace("。", "").replace("，", "");
        if (!s.equals("回桌面") && !s.equals("回主页") && !s.equals("回到桌面")
                && !s.equals("回到主页") && !s.equals("回主屏") && !s.equals("去桌面")
                && !s.equals("返回桌面")) return null;
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"su", "-c", "input keyevent 3"});
            boolean ok = p.waitFor() == 0;
            return new RouteResult(ok,
                    ok ? "🏠 已回桌面（本地直达，零 token）" : "🏠 回桌面失败");
        } catch (Exception e) {
            return new RouteResult(false, "🏠 回桌面失败：" + e.getMessage());
        }
    }

    // ---------------- 相册（图库，零 token 直达；2026-10-20 沉淀首报：
    // TG 说"开相册"未命中路由、agent 64 秒没打开相册任务失败，故加直达） ----------------

    private static RouteResult tryAlbum(Context ctx, String t) {
        String s = t.replace(" ", "").replace("。", "").replace("，", "");
        if (!s.equals("相册") && !s.equals("开相册") && !s.equals("打开相册")
                && !s.equals("看相册") && !s.equals("看看相册")
                && !s.equals("图库") && !s.equals("开图库") && !s.equals("打开图库"))
            return null;
        // MIUI 图库 label 多为"图库"，部分 ROM 叫"相册"；按候选名依次找
        String pkg = null;
        String hitName = null;
        for (String cand : new String[]{"相册", "图库"}) {
            pkg = findLaunchPackage(ctx, cand);
            if (pkg != null) { hitName = cand; break; }
        }
        if (pkg == null) {
            return new RouteResult(false, "🖼 没找到相册应用，换 agent 试试");
        }
        try {
            Intent it = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (it == null) {
                return new RouteResult(false, "🖼 相册无法启动");
            }
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            return new RouteResult(true,
                    "🖼 已打开" + hitName + "（本地直达，零 token）");
        } catch (Exception e) {
            return new RouteResult(false, "🖼 打开失败：" + e.getMessage());
        }
    }

    // ---------------- 闹钟 ----------------

    private static final Pattern P_ALARM_KW =
            Pattern.compile("(设|定).{0,4}闹钟|闹钟|叫我起床|喊我起床");

    private static RouteResult tryAlarm(Context ctx, String t) {
        if (!P_ALARM_KW.matcher(t).find()) return null;
        int[] hm = parseAlarmTime(t);
        if (hm == null) return null; // 没解析出时间，交给 agent
        try {
            Intent it = new Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hm[0])
                    .putExtra(AlarmClock.EXTRA_MINUTES, hm[1])
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "gsic 闹钟")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            return new RouteResult(true, "⏰ 已设置闹钟 "
                    + String.format("%02d:%02d", hm[0], hm[1])
                    + "（本地直达，零 token）");
        } catch (Exception e) {
            return new RouteResult(false, "⏰ 闹钟设置失败：" + e.getMessage());
        }
    }

    /**
     * 解析中文时间表达，返回 [hour, minute]；解析不出返回 null。
     * 纯函数，可独立测试。
     * 支持：「7点」「7点半」「7:30」「07:30」「早上7点」「下午3点」
     * 「晚上8点半」「晚上12点(→0点)」「凌晨12点(→0点)」
     */
    static int[] parseAlarmTime(String t) {
        // 上午/下午等前缀
        String period = null;
        Matcher pm = Pattern.compile("(凌晨|早上|上午|中午|下午|晚上|夜间)")
                .matcher(t);
        if (pm.find()) period = pm.group(1);

        Matcher m = Pattern.compile("(\\d{1,2})\\s*[点:：]\\s*(\\d{1,2})?\\s*分?")
                .matcher(t);
        if (!m.find()) return null;
        int hour, min = 0;
        try {
            hour = Integer.parseInt(m.group(1));
        } catch (Exception e) {
            return null;
        }
        String minStr = m.group(2);
        boolean half = false;
        if (minStr != null && !minStr.isEmpty()) {
            try {
                min = Integer.parseInt(minStr);
            } catch (Exception e) {
                return null;
            }
        } else {
            // 「7点半」
            int end = m.end();
            if (end < t.length() && t.charAt(end) == '半') {
                half = true;
                min = 30;
            }
        }
        if (hour < 0 || hour > 23 || min < 0 || min > 59) return null;
        // 12 小时制转换
        if (hour <= 12 && period != null) {
            boolean isPm = period.contains("下午") || period.contains("晚上")
                    || period.contains("夜间") || "中午".equals(period);
            if (isPm) {
                if (hour < 12) hour += 12;
                // 「晚上12点」= 午夜 0 点
                else if (hour == 12 && (period.contains("晚上")
                        || period.contains("凌晨") || period.contains("夜间"))) hour = 0;
            } else if (hour == 12 && period.contains("凌晨")) {
                hour = 0; // 「凌晨12点」= 午夜 0 点
            }
        }
        // 「13点」这种 24 小时制直接用
        return new int[]{hour, min};
    }

    // ---------------- 计时器 ----------------

    private static RouteResult tryTimer(Context ctx, String t) {
        Matcher m = Pattern.compile("(倒计时|计时|定时)\\s*(\\d+)\\s*分(钟)?")
                .matcher(t);
        if (!m.find()) return null;
        int minutes;
        try {
            minutes = Integer.parseInt(m.group(2));
        } catch (Exception e) {
            return null;
        }
        if (minutes <= 0 || minutes > 600) return null;
        try {
            Intent it = new Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "gsic 计时")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            return new RouteResult(true, "⏱ 已开始 " + minutes
                    + " 分钟倒计时（本地直达，零 token）");
        } catch (Exception e) {
            return new RouteResult(false, "⏱ 计时器启动失败：" + e.getMessage());
        }
    }

    // ---------------- 打开应用 ----------------

    private static final Pattern P_OPEN =
            Pattern.compile("^(打开|开启|启动|开一下)\\s*(.+?)\\s*$");

    private static RouteResult tryOpenApp(Context ctx, String t) {
        Matcher m = P_OPEN.matcher(t);
        if (!m.find()) return null;
        String name = m.group(2).trim();
        if (name.isEmpty() || name.length() > 20) return null;
        // v3.43.1：「X的设置」类短语不是应用名（实测「开启应用抽屉的设置」
        // 被反向包含匹配误命中系统「设置」应用，只开了设置首页冒充完成）
        if (name.contains("的")) return null;
        String pkg = findLaunchPackage(ctx, name);
        if (pkg == null) {
            // v3.43：系统功能名（热点/蓝牙/抽屉等）不是应用——静默未命中，交给 agent
            if (name.matches(".*(热点|蓝牙|无线|手电|飞行|定位|省电|静音|震动|录音|深色|护眼|抽屉|通知|投屏|移动数据|流量).*")) {
                return null;
            }
            return new RouteResult(false,
                    "📱 没找到叫「" + name + "」的应用，换 agent 试试");
        }
        try {
            Intent it = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
            if (it == null) {
                return new RouteResult(false, "📱 「" + name + "」无法启动");
            }
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(it);
            return new RouteResult(true,
                    "📱 已打开「" + name + "」（本地直达，零 token）");
        } catch (Exception e) {
            return new RouteResult(false, "📱 打开失败：" + e.getMessage());
        }
    }

    /** 按应用名（label 或包名片段）找可启动包；找不到返回 null。 */
    static String findLaunchPackage(Context ctx, String name) {
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(main, 0);
            String contains = null;
            String n = name.replaceAll("\\s+", "").toLowerCase();
            for (ResolveInfo ri : list) {
                String label = String.valueOf(
                        ri.loadLabel(pm)).replaceAll("\\s+", "").toLowerCase();
                if (label.equals(n)) return ri.activityInfo.packageName;
                if (contains == null && (label.contains(n)
                        || (n.contains(label) && n.length() - label.length() <= 3))) {
                    contains = ri.activityInfo.packageName;
                }
                // v3.43：包名兜底（clash → com.github.metacubex.clash.meta 这类）
                if (contains == null && ri.activityInfo.packageName
                        .toLowerCase().contains(n)) {
                    contains = ri.activityInfo.packageName;
                }
            }
            return contains;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------- 响铃模式 ----------------

    /** v2.82：判断是否为纯聊天（非任务）。打招呼/感谢/闲聊等不走任务流。 */
    private static final Pattern P_CHAT =
            Pattern.compile("^(你好|您好|hi|hello|嗨|早安|早上好|晚安|谢谢|感谢|多谢|再见|拜拜|"
                    + "你是谁|你叫什么|你好呀|hello|hey)[！!。.,，]*$",
                    Pattern.CASE_INSENSITIVE);

    /** 动作动词：出现这些词大概率是任务，不是闲聊 */
    private static final Pattern P_TASK_VERB =
            Pattern.compile("打开|开启|关闭|关掉|启动|播放|搜索|查找|设置|调节|安装|卸载|拍照|截图|截屏|"
                    + "回|去|返回|滑动|下滑|上滑|点击|点开|输入|登录|登陆|领取|切换|连接|断开|"
                    + "停止|暂停|刷新|滚动|保存|新建|添加|拨打|挂断|复制|粘贴|重启|解锁|签到|导航|打不开|开不了|"
                    + "发|查|订|买|定|下载|上传|删除|清理|备份|同步|分享|翻译");

    // v3.43：求知提问（问事实/概念，不含操作动词时才算闲聊）
    private static final Pattern P_QUESTION =
            Pattern.compile("什么是|为什么|怎么回事|是什么意思|怎么理解|讲个笑话|说个笑话|"
                    + "你会什么|你能做什么|你都会什么|几点了|现在几点|今天星期几|天气怎么样|天气如何");

    // v3.43：纯应答/语气句（任务后的收尾语，不是新指令）
    private static final Pattern P_ACK =
            Pattern.compile("^(嗯+|哦+|噢+|好+|好的|行|可以|知道了|明白|收到|哈哈+|呵呵|哇|不错|厉害|辛苦了|ok|OK|嗯嗯)[！!。.,，~～]*$");

    public static boolean isChat(String goal) {
        if (goal == null) return false;
        String t = goal.trim();
        if (t.isEmpty()) return false;
        // 明确的打招呼/闲聊
        if (P_CHAT.matcher(t).matches()) return true;
        // 纯应答/语气句
        if (P_ACK.matcher(t).matches()) return true;
        // v3.43 分流反转：旧规则「≤8 字且不含动词表词 → 闲聊」把「开启个人热点」
        // 「下滑」这类短指令误判进无工具的闲聊路径，模型只能口头声称完成
        // （2026-10-20 遥测实证，幻觉主源头）。改为：闲聊只认正向信号——
        // 求知提问且不含操作动词才算闲聊，其余默认走 agent 执行。
        if (P_QUESTION.matcher(t).find()
                && !P_TASK_VERB.matcher(t).find()) return true;
        return false;
    }

    private static RouteResult tryRinger(Context ctx, String t) {
        int mode = -1;
        String label = null;
        if (Pattern.compile("^(.*)(静音|调成静音|开启静音)(.*)$").matcher(t).matches()
                && t.length() <= 12) {
            mode = AudioManager.RINGER_MODE_SILENT;
            label = "静音";
        } else if (t.matches(".*(震动模式|调成震动|开启震动).*")
                && t.length() <= 12) {
            mode = AudioManager.RINGER_MODE_VIBRATE;
            label = "震动";
        } else if (t.matches(".*(响铃模式|正常模式|取消静音|调成响铃).*")
                && t.length() <= 12) {
            mode = AudioManager.RINGER_MODE_NORMAL;
            label = "响铃";
        }
        if (mode < 0) return null;
        try {
            AudioManager am = (AudioManager)
                    ctx.getSystemService(Context.AUDIO_SERVICE);
            am.setRingerMode(mode);
            return new RouteResult(true,
                    "🔔 已切换为" + label + "模式（本地直达，零 token）");
        } catch (Exception e) {
            return new RouteResult(false, "🔔 切换失败：" + e.getMessage());
        }
    }
}
