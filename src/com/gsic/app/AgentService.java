package com.gsic.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/** 前台服务：保活 HTTP 控制服务。端口可在 App 内修改（默认 28789），token 鉴权。 */
public class AgentService extends Service {

    /** 默认端口（v2.41 起由 18789 改为 28789） */
    public static final int DEFAULT_PORT = 28789;

    /** 当前生效端口（用户可在远控页修改） */
    public static int getPort(Context ctx) {
        return ctx.getSharedPreferences("svc", Context.MODE_PRIVATE)
                .getInt("port", DEFAULT_PORT);
    }

    public static void setPort(Context ctx, int port) {
        ctx.getSharedPreferences("svc", Context.MODE_PRIVATE)
                .edit().putInt("port", port).apply();
    }

    private static volatile boolean running = false;

    // v3.05：TgBot 搬到 Service 里，后台真运行
    private TgBot tgBot;
    private static volatile boolean tgRunning = false;

    public static boolean isTgRunning() {
        return tgRunning;
    }

    // v3.05：静态控制 TG（供 MainActivity 调用）
    private static AgentService instance = null;

    public static void startTgStatic(Context ctx) {
        if (instance != null) {
            instance.startTgBot();
        } else {
            // Service 未运行，先拉起服务（onCreate 会自动启 TG）
            Intent i = new Intent(ctx, AgentService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        }
    }

    public static void stopTgStatic() {
        if (instance != null) instance.stopTgBot();
        tgRunning = false;
    }

    public static boolean isRunning() {
        return running;
    }

    private AgentHttpServer http;
    private PetController pet;
    private android.os.Handler schedHandler;

    private final Runnable schedTick = new Runnable() {
        @Override
        public void run() {
            try {
                runDueSchedules();
            } catch (Exception ignored) {
            }
            if (schedHandler != null) schedHandler.postDelayed(this, 60_000);
        }
    };

    /** 本地定时任务：每天 / 每间隔执行技能，零 token */
    private void runDueSchedules() {
        ScheduleStore store = new ScheduleStore(this);
        HistoryStore hist = new HistoryStore(this);
        SkillStore skills = new SkillStore(this);
        long now = System.currentTimeMillis();
        for (ScheduleStore.Schedule sc : store.due(now)) {
            String json = null;
            String name = sc.skillName;
            for (SkillStore.Skill sk : skills.list()) {
                if (sk.id.equals(sc.skillId) && sk.enabled) {
                    json = sk.json;
                    name = sk.name;
                    break;
                }
            }
            boolean ok = false;
            if (json != null) {
                TaskRunner r = new TaskRunner(this, msg -> {
                });
                ok = r.run(json);
            }
            hist.append(name + "（定时）", ok);
            store.touch(sc.id, now);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // v3.52：开机自启回执确认（有待确认的开机广播则落 boot_ok_ts）
        BootReceiver.noteServiceUp(this);
        // 地基·自更新：装后自验对账 + 启动静默检查（24h 节流、只记不扰）
        UpdateManager.confirmIfUpdated(this);
        UpdateManager.silentCheck(this);
        // v3.54 热更新诊断 E1：运行中 dump ClassLoader 元素构成+补丁类 CodeSource
        // （异步，不阻塞启动；与 attach 时刻 dump 对比即 E3）
        PatchDiag.dumpAsync(this, "service");
        String chId = "agent_svc";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    chId, "远程控制服务", NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(ch);
        }
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, chId)
                : new Notification.Builder(this);
        int port = getPort(this);
        // v3.05：通知栏显示 TG 状态
        String tgState = tgRunning ? " · TG机器人运行中" : "";
        Notification n = nb.setContentTitle("GSIC")
                .setContentText("远程控制服务运行中（端口 " + port + "）" + tgState)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();
        startForeground(1, n);
        try {
            http = new AgentHttpServer(port, new ApiHandler(this));
            http.start();
            running = true;
            // 定时任务调度循环（每分钟检查一次）
            schedHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            schedHandler.postDelayed(schedTick, 30_000);
            // v3.42 常驻三层：闹钟看门狗 + Magisk root 守护（开机自启在 BootReceiver）
            scheduleWatchdog();
            installRootWatchdog();
            // v3.05：TG 机器人随服务自启（后台真运行）
            tryStartTgBot();
            // v3.67：内置 Cloudflare 隧道按开关恢复（快速隧道，手动同步地址）
            try {
                CloudflareTunnel.startIfEnabled(this, port);
            } catch (Exception ignored) {
            }
            // v3.48：待机光效「M 八爪鱼」
            try {
                pet = new PetController(this);
                pet.start();
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            stopSelf();
        }
    }

    /** v3.42：闹钟看门狗——每 10 分钟叫醒 BootReceiver 检查服务是否还活着 */
    private void scheduleWatchdog() {
        try {
            android.app.AlarmManager am = (android.app.AlarmManager)
                    getSystemService(ALARM_SERVICE);
            Intent i = new Intent(this, BootReceiver.class);
            i.setAction("com.gsic.app.WATCHDOG");
            android.app.PendingIntent pi = android.app.PendingIntent.getBroadcast(
                    this, 421, i,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT);
            long interval = 10 * 60_000L;
            am.setRepeating(android.app.AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + interval, interval, pi);
        } catch (Exception ignored) {
        }
    }

    // v3.42 root 守护脚本（Magisk service.d 开机执行，不依赖 MIUI 自启动放行）
    private static final String WATCHDOG_SH =
            "#!/system/bin/sh\n"
            + "# gsic 常驻守护：服务进程不在就拉起；"
            + "用户手动停止（svc_off 标记）时不拉起\n"
            + "sleep 40\n"
            + "while true; do\n"
            + "  if [ ! -f /data/data/com.gsic.app/files/svc_off ]; then\n"
            + "    pidof com.gsic.app >/dev/null 2>&1 || "
            + "am startservice -n com.gsic.app/com.gsic.app.AgentService "
            + ">/dev/null 2>&1\n"
            + "  fi\n"
            + "  sleep 60\n"
            + "done\n";

    /** v3.42：安装 Magisk root 守护（每版本装一次；无 root/无 Magisk 静默跳过） */
    private void installRootWatchdog() {
        try {
            android.content.SharedPreferences sp =
                    getSharedPreferences("agent", MODE_PRIVATE);
            if (sp.getInt("wd_installed_ver", 0) >= 42) return;
            sp.edit().putInt("wd_installed_ver", 42).apply();
            new Thread(() -> {
                try {
                    if (Root.findSu() == null) return;
                    java.io.File tmp = new java.io.File(
                            getFilesDir(), "gsic_watchdog.sh");
                    java.io.FileOutputStream fos =
                            new java.io.FileOutputStream(tmp);
                    fos.write(WATCHDOG_SH.getBytes("UTF-8"));
                    fos.close();
                    String cmd = "mkdir -p /data/adb/service.d"
                            + " && cp " + tmp.getAbsolutePath()
                            + " /data/adb/service.d/gsic_watchdog.sh"
                            + " && chmod 755 /data/adb/service.d/gsic_watchdog.sh"
                            + "; dumpsys deviceidle whitelist +com.gsic.app"
                            + "; pgrep -f gsic_watchdog >/dev/null"
                            + " || (setsid sh /data/adb/service.d/gsic_watchdog.sh"
                            + " >/dev/null 2>&1 &)";
                    RootShell.get().exec(cmd, 30);
                } catch (Exception ignored) {
                }
            }, "wd-install").start();
        } catch (Exception ignored) {
        }
    }

    /** v3.05：尝试启动 TG 机器人（有 token 且开启自启） */
    private void tryStartTgBot() {
        try {
            boolean auto = getSharedPreferences("agent", MODE_PRIVATE)
                    .getBoolean("tg_autostart", true);
            TgStore tgStore = new TgStore(this);
            if (auto && tgStore.hasToken()) {
                startTgBot();
            }
        } catch (Exception ignored) {
        }
    }

    /** v3.05：启动 TG（可被 MainActivity 调用） */
    public void startTgBot() {
        try {
            if (tgBot == null) {
                TgStore tgStore = new TgStore(this);
                LlmStore llmStore = new LlmStore(this);
                tgBot = new TgBot(this, tgStore, llmStore,
                        msg -> android.util.Log.i("TgBot", msg));
            }
            tgBot.start();
            tgRunning = true;
        } catch (Exception e) {
            android.util.Log.e("AgentService", "TG 启动失败: " + e.getMessage());
        }
    }

    /** v3.05：停止 TG（可被 MainActivity 调用） */
    public void stopTgBot() {
        try {
            if (tgBot != null) tgBot.stop();
            tgRunning = false;
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    /**
     * v3.22-hotfix：用户划掉任务卡片时自重启。
     * MIUI 下从最近任务划掉会杀进程，靠 START_STICKY 常拉不起来，
     * 这里主动再拉一次。
     */
    @Override
    public void onTaskRemoved(android.content.Intent rootIntent) {
        try {
            android.content.Intent i = new android.content.Intent(
                    getApplicationContext(), AgentService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                getApplicationContext().startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception ignored) {
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = false;
        // v3.67：服务销毁时停隧道进程，开关保留，下次服务启动按开关恢复
        try {
            CloudflareTunnel.stopProcessOnly();
        } catch (Exception ignored) {
        }
        if (pet != null) pet.stop();
        // v3.05：服务销毁时停 TG
        stopTgBot();
        instance = null;
        if (schedHandler != null) schedHandler.removeCallbacks(schedTick);
        if (http != null) http.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
