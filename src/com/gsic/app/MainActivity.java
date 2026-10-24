package com.gsic.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 三栏主界面：主页（状态/技能/定时/记录） / 远控 / 我的（关于）。
 * Material 风格：顶栏 + 卡片。三栏导航沿用 1.12。
 */
public class MainActivity extends Activity {

    private static final int ACCENT = 0xFF1E88E5;
    private static final int ACCENT_DARK = 0xFF1565C0;
    private static final int TAB_BG = 0xFFF0F0F0;
    private static final int TEXT_DARK = 0xFF333333;
    private static final int TEXT_GRAY = 0xFF777777;
    private static final int GREEN = 0xFF2E7D32;
    private static final int RED = 0xFFC62828;

    private static final int REQ_IMPORT = 1001;

    private LinearLayout pageHome, pageRemote, pageAgent, pageTerm, pageMy;
    private TabIconView tabHome, tabRemote, tabAgent, tabTerm, tabMy;
    // v2.64：tabBar 和 content 改字段，供焦点监听控制显隐
    private LinearLayout tabBarView;
    private LinearLayout contentView;

    private TextView statusView, svcStatusView;
    private TextView adbStatusView, shizukuStatusView, wifiAdbStatusView;
    private LinearLayout skillsBox, schedBox, histBox, skillCardsBox;
    private LinearLayout llmProfilesBox;
    private boolean llmNewMode = false;
    private TextView svcInfo;

    // 终端页
    private ShellSession shell;
    private TextView termStatus, termOut, termPrompt;
    private ScrollView termScroll;
    private EditText termInput;
    // 内置 Termux 环境
    private BootstrapManager bootstrapManager;
    private TextView termuxEnvStatus;
    private android.widget.ProgressBar termuxProgress;
    private Button termuxDownloadBtn;
    private LinearLayout termBody;
    private TextView termGateHint;
    // 终端专注模式：shell 运行时自动隐藏多余卡片，输出区固定填满
    private LinearLayout termEnvCard, termTxCard, termInputRow;
    private ScrollView termPageScroll;
    private boolean termFollow = true;
    // 云端大模型
    private LlmStore llmStore;
    private String llmProvider;
    private EditText llmBaseUrl, llmApiKey, llmModel, llmVisionModel,
            llmAsk, llmNameInput;
    private LinearLayout auxModelsBox;
    private AuxModelStore auxModelStore;
    private android.widget.Spinner askModelSpinner;
    private String askModelOverride = "";
    private android.widget.Spinner agentModelSpinner;
    private String agentModelOverride = "";
    private BehaviorLog behaviorLog;
    private TextView llmStatus, llmReply;
    // AI 智能执行（Agent 循环）
    private EditText agentGoal;
    private LinearLayout agentInputBar;
    private android.graphics.drawable.Drawable agentInputBg;
    private FlowLightDrawable agentInputFlow;
    private LinearLayout agentMsgBox;
    private Button agentSendBtn;
    private android.widget.CheckBox agentAdvBox;
    private android.widget.CheckBox agentSupervisorBox;
    // v2.62：监管者独立输出区
    private LinearLayout supervisorMsgBox;
    private ScrollView supervisorScrollView;
    private ActionRisk.Policy riskPolicy;
    private android.widget.TextView riskSummaryView;
    private ChatStore chatStore;
    private String lastGoal = "";
    private String lastHistory = "";
    private final java.util.concurrent.ConcurrentLinkedQueue<String> feedbackQueue =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private boolean loadingHistory = false;
    private Runnable agentFloatHintRefresher;
    // v3.52 地基·流光写死：软件内嵌彩条——不走悬浮窗，直接长在界面根布局上，
    // 500ms 对表任务状态（FloatWindow.isTaskActive，三端共用同一状态源）：
    // 任何通道的任务在跑，打开软件必见彩条，与悬浮窗互为双保险
    private android.view.View inAppFlowStripe;
    private FlowLightDrawable inAppFlowDrawable;
    private final android.os.Handler inAppFlowHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable inAppFlowTick = new Runnable() {
        @Override
        public void run() {
            try {
                // v3.53：改认 isActive（任务登记 OR 远控操控心跳），与悬浮流光同判据
                boolean on = FloatWindow.isActive();
                if (inAppFlowStripe != null) {
                    if (on && inAppFlowStripe.getVisibility() != android.view.View.VISIBLE) {
                        inAppFlowStripe.setVisibility(android.view.View.VISIBLE);
                        if (inAppFlowDrawable != null) inAppFlowDrawable.start();
                    } else if (!on && inAppFlowStripe.getVisibility() == android.view.View.VISIBLE) {
                        inAppFlowStripe.setVisibility(android.view.View.GONE);
                        if (inAppFlowDrawable != null) inAppFlowDrawable.stop();
                    }
                }
            } catch (Exception ignored) {
            }
            inAppFlowHandler.postDelayed(this, 500);
        }
    };
    private android.app.AlertDialog chatHistoryDialog;
    private LinearLayout userProfileBox;
    private UserProfile userProfile;
    private CaptureStore captureStore;
    private android.widget.ScrollView agentScrollView;
    private volatile boolean agentStop = true;
    private Thread agentThread;
    // 云端操控
    private TextView museTsStatus, museHelp;
    private Button museTsAppBtn;
    // 远控端口（菜单副标题 / API 卡片标题随端口动态刷新）
    private TextView remoteSvcSub, apiCardTitle;
    // 应用内更新
    private TextView updStatus;
    private android.widget.ProgressBar updProgress;
    // Telegram 机器人
    private TgStore tgStore;
    // v3.05：TgBot 已搬到 AgentService，MainActivity 不再持有
    private EditText tgToken;
    private TextView tgStatus, tgOwner, tgLogView;
    private android.widget.ScrollView tgScrollView;

    private SkillStore skillStore;
    private HistoryStore historyStore;
    private ScheduleStore scheduleStore;
    private TaskRunner runner;
    private Thread runThread;

    // ---------- 基础组件 ----------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /** 白色圆角卡片 */
    /** Material 3 Expressive 圆角背景 */
    private android.graphics.drawable.GradientDrawable rbg(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setCornerRadius(dp(radiusDp));
        d.setColor(color);
        return d;
    }

    /** 胶囊按钮：动态主色 + 涟漪（紧凑体型） */
    private void stylePillButton(Button b) {
        int p = DyColor.primary(this);
        android.graphics.drawable.GradientDrawable bg = rbg(p, 20);
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        b.setMinHeight(dp(36));
        b.setMinimumHeight(dp(36));
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            android.content.res.ColorStateList ripple =
                    android.content.res.ColorStateList.valueOf(0x40FFFFFF);
            b.setBackground(new android.graphics.drawable.RippleDrawable(
                    ripple, bg, null));
        } else {
            b.setBackground(bg);
        }
        b.setTextColor(DyColor.onPrimary(this));
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable cbg = rbg(DyColor.surface(this), 24);
        cbg.setStroke(dp(1), DyColor.outline(this));
        c.setBackground(cbg);
        int p = dp(16);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(16), dp(10), dp(16), dp(10));
        c.setLayoutParams(lp);
        return c;
    }

    private TextView cardTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(18);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setTextColor(DyColor.onSurface(this));
        t.setPadding(0, 0, 0, dp(8));
        return t;
    }

    private TextView bodyText(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(14);
        t.setTextColor(TEXT_DARK);
        t.setLineSpacing(dp(4), 1f);
        return t;
    }

    private TextView grayText(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(TEXT_GRAY);
        return t;
    }

    private Button btn(String s) {
        Button b = new Button(this);
        b.setText(s);
        stylePillButton(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    /** 次级按钮：tonal 柔和风（浅底深字） */
    private void styleTonalButton(Button b) {
        android.graphics.drawable.GradientDrawable d =
                rbg(DyColor.primaryContainer(this), 20);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            android.content.res.ColorStateList ripple =
                    android.content.res.ColorStateList.valueOf(0x20000000);
            b.setBackground(new android.graphics.drawable.RippleDrawable(
                    ripple, d, null));
        } else {
            b.setBackground(d);
        }
        b.setTextColor(DyColor.onSurface(this));
        b.setPadding(dp(14), dp(6), dp(14), dp(6));
        b.setMinHeight(dp(36));
        b.setMinimumHeight(dp(36));
    }

    private Button smallBtn(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setTextSize(13);
        styleTonalButton(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(6), dp(4), dp(6), dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    /** 横排子 View 均分宽度（窄屏防挤） */
    private void distributeRow(LinearLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            android.view.View c = row.getChildAt(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            c.setLayoutParams(lp);
            if (c instanceof Button) {
                Button b = (Button) c;
                b.setTextSize(12);
                b.setSingleLine(true);
            }
        }
    }

    /** 卡片标题行：标题 + 右侧按钮 */
    private LinearLayout headerRow(String title, Button action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        t.setTextColor(TEXT_DARK);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(t, lp);
        if (action != null) row.addView(action);
        row.setPadding(0, 0, 0, dp(6));
        return row;
    }

    private void log(String s) {
        // 线程安全：后台线程调用时切到 UI 线程，否则 Toast 直接闪退
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
        } else {
            runOnUiThread(() ->
                    Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
        }
    }

    /** Telegram Bot 日志（线程安全，自动滚到底） */
    private void tgLog(String s) {
        runOnUiThread(() -> {
            if (tgLogView != null) {
                tgLogView.append(s + "\n");
                if (tgScrollView != null) {
                    tgScrollView.post(() -> tgScrollView.fullScroll(
                            android.view.View.FOCUS_DOWN));
                }
            }
        });
    }

    private void refreshTg() {
        if (tgStatus == null) return;
        // v3.05：TG 在 Service 里跑，查 Service 状态
        boolean run = AgentService.isTgRunning();
        tgStatus.setText(tgStore.hasToken()
                ? (run ? "运行中 ✅" : "已配置，未启动")
                : "未配置 Bot Token");
        tgStatus.setTextColor(tgStore.hasToken() && run ? GREEN : TEXT_GRAY);
        tgOwner.setText(tgStore.hasOwner()
                ? "主人已绑定 ✅" : "主人未绑定（给机器人发 /start）");
        if (tgStore.hasToken()) {
            tgToken.setHint("Bot Token（" + tgStore.getMaskedToken() + "，留空保留）");
        }
    }

    // v3.08：任务完成后的日志入口按钮
    private void showTaskLogButton(String logPath) {
        try {
            java.io.File f = new java.io.File(logPath);
            String name = f.getName();
            // 转成可读时间
            String timeStr = name;
            try {
                // task_20261019_143022_ab12.log -> 2026-10-19 14:30:22
                String dt = name.substring(5, 20);
                timeStr = dt.substring(0, 4) + "-" + dt.substring(4, 6) + "-"
                        + dt.substring(6, 8) + " " + dt.substring(9, 11) + ":"
                        + dt.substring(11, 13) + ":" + dt.substring(13, 15);
            } catch (Exception ignored) {
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.START);
            row.setPadding(0, dp(4), 0, dp(4));
            Button btn = new Button(this);
            btn.setText("📝 任务日志 " + timeStr);
            btn.setTextSize(12);
            final java.io.File logFile = new java.io.File(logPath);
            btn.setOnClickListener(v -> showTaskLogContent(logFile));
            row.addView(btn);
            agentMsgBox.addView(row);
            if (agentScrollView != null) {
                agentScrollView.post(() -> agentScrollView.fullScroll(
                        android.view.View.FOCUS_DOWN));
            }
        } catch (Exception ignored) {
        }
    }

    /** AI 智能执行日志 → 聊天气泡（线程安全，自动滚到底） */
    private void agentLog(String s) {
        runOnUiThread(() -> {
            if (agentMsgBox == null) return;
            String time = new java.text.SimpleDateFormat("HH:mm",
                    java.util.Locale.getDefault()).format(new java.util.Date());
            if (s.startsWith("\uD83C\uDFAF")) { // 🎯 用户目标
                addChatBubble(s.replaceFirst("^[^ ]+ ", ""), true, time);
            } else if (s.startsWith("✅") || s.startsWith("\uD83C\uDF89")
                    || s.startsWith("⏹") || s.startsWith("\uD83D\uDED1")) {
                addChatSystem(s, time);
            } else {
                addChatBubble(s, false, time);
            }
            if (agentScrollView != null) {
                agentScrollView.post(() -> agentScrollView.fullScroll(
                        android.view.View.FOCUS_DOWN));
            }
        });
    }

    // v2.62：监管者独立输出（与智能体分开）
    private void supervisorLog(String s) {
        runOnUiThread(() -> {
            if (supervisorMsgBox == null) return;
            TextView tv = new TextView(this);
            tv.setText(s);
            tv.setTextSize(11);
            tv.setTextColor(DyColor.onSurfaceVariant(this));
            tv.setPadding(dp(8), dp(4), dp(8), dp(4));
            tv.setTextIsSelectable(true);
            supervisorMsgBox.addView(tv);
            if (supervisorScrollView != null) {
                supervisorScrollView.post(() -> supervisorScrollView.fullScroll(
                        android.view.View.FOCUS_DOWN));
            }
        });
    }

    private android.graphics.drawable.GradientDrawable bubbleBg(int color, float r) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setCornerRadius(dp((int) r));
        d.setColor(color);
        return d;
    }

    /** 聊天气泡：isUser 右蓝左灰 */
    // v3.02：流式气泡，返回 TextView 供追加 token
    private TextView startStreamBubble() {
        final TextView[] holder = new TextView[1];
        runOnUiThread(() -> {
            int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.78);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.START);
            row.setPadding(0, dp(6), 0, dp(6));

            LinearLayout bubble = new LinearLayout(this);
            bubble.setOrientation(LinearLayout.VERTICAL);
            bubble.setBackground(bubbleBg(DyColor.surfaceVariant(this), 18));
            bubble.setPadding(dp(12), dp(8), dp(12), dp(6));

            TextView msg = new TextView(this);
            msg.setText("🤖 ");
            msg.setTextSize(14);
            msg.setTextColor(DyColor.onSurface(this));
            msg.setTextIsSelectable(true);
            msg.setMaxWidth(maxW);
            msg.setLineSpacing(dp(2), 1f);
            bubble.addView(msg);

            row.addView(bubble);
            agentMsgBox.addView(row);
            if (agentScrollView != null) {
                agentScrollView.post(() -> agentScrollView.fullScroll(
                        android.view.View.FOCUS_DOWN));
            }
            holder[0] = msg;
        });
        // 等待 UI 线程创建完成（最多 1 秒）
        long start = System.currentTimeMillis();
        while (holder[0] == null
                && System.currentTimeMillis() - start < 1000) {
            try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        }
        return holder[0];
    }

    private void addChatBubble(String text, boolean isUser, String time) {
        int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.78);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(isUser ? Gravity.END : Gravity.START);
        row.setPadding(0, dp(6), 0, dp(6));

        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setBackground(bubbleBg(isUser ? DyColor.primary(this)
                : DyColor.surfaceVariant(this), 18));
        bubble.setPadding(dp(12), dp(8), dp(12), dp(6));

        TextView msg = new TextView(this);
        msg.setText(text);
        msg.setTextSize(14);
        msg.setTextColor(isUser ? DyColor.onPrimary(this) : DyColor.onSurface(this));
        msg.setTextIsSelectable(true);
        msg.setMaxWidth(maxW);
        msg.setLineSpacing(dp(2), 1f);
        bubble.addView(msg);

        TextView ts = new TextView(this);
        ts.setText(time);
        ts.setTextSize(10);
        ts.setTextColor(isUser ? DyColor.onPrimary(this) : DyColor.onSurfaceVariant(this));
        ts.setGravity(Gravity.END);
        bubble.addView(ts);

        row.addView(bubble);
        agentMsgBox.addView(row);
        if (chatStore != null && !loadingHistory) {
            chatStore.append(isUser ? "user" : "agent", text, time);
        }
    }

    /** 系统居中提示 */
    private void addChatSystem(String text, String time) {
        TextView t = new TextView(this);
        t.setText(text + "  " + time);
        t.setTextSize(12);
        t.setTextColor(TEXT_GRAY);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(6), 0, dp(6));
        agentMsgBox.addView(t);
        if (chatStore != null && !loadingHistory) {
            chatStore.append("system", text, time);
        }
    }

    /** 清空气泡（开始新任务时） */
    private void clearChat() {
        if (agentMsgBox != null) agentMsgBox.removeAllViews();
    }

    /** 历史对话菜单 */
    private void showChatHistory() {
        if (chatStore == null) return;
        if (chatHistoryDialog != null && chatHistoryDialog.isShowing()) {
            chatHistoryDialog.dismiss();
        }
        java.util.List<ChatStore.Session> ss = chatStore.listSessions();
        if (ss.isEmpty()) {
            agentLog("暂无历史对话");
            return;
        }
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (ChatStore.Session se : ss) {
            final long sid = se.id;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(10), dp(8), dp(10));
            TextView t = new TextView(this);
            t.setText(ChatStore.sessionTitle(se));
            t.setTextSize(14);
            t.setTextColor(DyColor.onSurface(this));
            t.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(t, lp);
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> {
                loadingHistory = true;
                try {
                    clearChat();
                    ChatStore.Session target = chatStore.getSession(sid);
                    if (target != null) {
                        for (ChatStore.Msg m : target.msgs) {
                            if ("user".equals(m.role)) addChatBubble(m.text, true, m.time);
                            else if ("agent".equals(m.role)) addChatBubble(m.text, false, m.time);
                            else addChatSystem(m.text, m.time);
                        }
                    }
                } finally {
                    loadingHistory = false;
                }
            });
            Button delBtn = smallBtn("删除");
            final long delId = se.id;
            delBtn.setOnClickListener(v -> {
                chatStore.deleteSession(delId);
                // 刷新对话框：关掉重开
                showChatHistory();
            });
            row.addView(delBtn);
            list.addView(row);
            if (se != ss.get(ss.size() - 1)) {
                android.view.View div = new android.view.View(this);
                div.setBackgroundColor(DyColor.outline(this));
                list.addView(div, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
            }
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(list);
        chatHistoryDialog = new android.app.AlertDialog.Builder(this)
                .setTitle("历史对话")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
        android.app.AlertDialog dlg = chatHistoryDialog;
        android.widget.Button pos = dlg.getButton(
                android.app.AlertDialog.BUTTON_POSITIVE);
        if (pos != null) pos.setTextColor(DyColor.primary(this));
    }

    /** 确认完成后，后台自主决策是否生成技能卡 */
    private void scheduleSkillCard(String goal, String history) {
        final String hg = goal;
        final String hh = history;
        new Thread(() -> generateSkillCard(hg, hh,
                SkillCardStore.SRC_AGENT, null)).start();
    }

    /**
     * 技能卡生成：成功任务后调用。
     * source=user（用户要求）或 agent（自主决策）。
     */
    private void generateSkillCard(String goal, String history, String source,
                                   String preferName) {
        try {
            String prompt = "你是手机自动化助手。用户刚才完成了任务：\n目标："
                    + goal + "\n过程：\n" + history
                    + "\n\n请判断这个任务是否值得存为可复用的技能卡"
                    + "（高频、步骤固定、有明确触发场景才值得）。"
                    + "如果值得，输出纯 JSON：\n"
                    + "{\"worth\":true,\"name\":\"简短名\","
                    + "\"type\":\"script\"或\"skill\","
                    + "\"trigger\":\"触发关键词，逗号分隔\","
                    + "\"content\":\"script 为完整 shell 脚本；skill 为步骤 JSON\","
                    + "\"summary\":\"一句话总结\"}\n"
                    + "如果不值得，输出 {\"worth\":false}。只输出 JSON。";
            LlmClient.Result r = llmStore.client().chat(prompt);
            if (!r.ok) return;
            org.json.JSONObject o = AgentLoop.extractJson(r.text);
            if (o == null || !o.optBoolean("worth", false)) {
                if (SkillCardStore.SRC_AGENT.equals(source)) {
                    agentLog("📇 该任务不值得存为技能卡，已跳过");
                }
                return;
            }
            String name = (preferName != null && !preferName.isEmpty())
                    ? preferName : o.optString("name", goal);
            String type = o.optString("type", SkillCardStore.TYPE_SKILL);
            if (!SkillCardStore.TYPE_SCRIPT.equals(type)) type = SkillCardStore.TYPE_SKILL;
            new SkillCardStore(this).add(name, type, source,
                    o.optString("trigger", ""), o.optString("content", ""),
                    o.optString("summary", ""));
            agentLog("📇 已生成技能卡：" + name
                    + "（" + (SkillCardStore.TYPE_SCRIPT.equals(type) ? "脚本" : "技能")
                    + "·" + (SkillCardStore.SRC_USER.equals(source) ? "用户" : "自主") + "）");
        } catch (Exception e) {
            agentLog("技能卡生成失败：" + e.getMessage());
        }
    }

    /** 快捷指令：优先级最高，不经过大脑直接执行 */
    private boolean handleSlashCmd(String cmd) {
        switch (cmd) {
            case "/stop":
                agentStop = true;
                agentLog("🛑 已强行停止");
                FloatWindow.forceEnd();
                stopInputFlow();
                return true;
            case "/clear":
                if (chatStore != null) chatStore.newSession();
                SharedMemory.clear(this);
                clearChat();
                agentLog("👋 对话已清空，在下方输入目标。");
                return true;
            case "/retry":
                if (lastGoal.isEmpty()) {
                    agentLog("没有可重试的任务");
                } else {
                    agentGoal.setText(lastGoal);
                    agentLog("↻ 重试上次任务：" + lastGoal);
                    startAgent();
                }
                return true;
            case "/save": {
                if (lastGoal.isEmpty()) {
                    agentLog("没有可保存的任务");
                } else {
                    agentLog("📇 正在从上次任务生成技能卡…");
                    final String fn = cmd.length() > 5 ? cmd.substring(5).trim() : "";
                    final String fg = lastGoal;
                    final String fh = lastHistory;
                    new Thread(() -> generateSkillCard(fg, fh,
                            SkillCardStore.SRC_USER, fn)).start();
                }
                return true;
            }
            case "/help":
                agentLog("📖 快捷指令：\n/stop 强行停止\n/clear 清空对话\n"
                        + "/retry 重试上次任务\n/save [名称] 存为技能卡\n/help 显示本帮助");
                return true;
            default:
                return false;
        }
    }

    // v2.88：发送后输入框保持焦点，不用重新点
    private void refocusInput() {
        if (agentGoal == null) return;
        agentGoal.post(() -> {
            agentGoal.requestFocus();
            try {
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)
                                getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.showSoftInput(agentGoal,
                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            } catch (Exception ignored) {
            }
        });
    }

    // v2.89：从服务商拉取模型列表（OpenAI 兼容 /v1/models）
    private void fetchModels() {
        String baseUrl = llmBaseUrl.getText().toString().trim();
        String apiKey = llmApiKey.getText().toString().trim();
        if (apiKey.isEmpty()) apiKey = llmStore.getApiKeyRaw();
        if (baseUrl.isEmpty() || apiKey.isEmpty()) {
            log("先填 base_url 和 api_key");
            return;
        }
        // base_url 可能带 /v1 后缀，拼 /models 时处理
        String url = baseUrl.endsWith("/") ? baseUrl + "models"
                : baseUrl + "/models";
        final String fUrl = url;
        final String fKey = apiKey;
        log("正在拉取模型列表...");
        new Thread(() -> {
            try {
                java.net.URL u = new java.net.URL(fUrl);
                java.net.HttpURLConnection c =
                        (java.net.HttpURLConnection) u.openConnection();
                c.setRequestMethod("GET");
                c.setRequestProperty("Authorization", "Bearer " + fKey);
                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
                int code = c.getResponseCode();
                if (code != 200) {
                    String msg = "HTTP " + code;
                    try (java.io.InputStream es = c.getErrorStream()) {
                        if (es != null) {
                            byte[] buf = new byte[512];
                            int n = es.read(buf);
                            if (n > 0) msg += "：" + new String(buf, 0, n, "UTF-8");
                        }
                    } catch (Exception ignored) {}
                    final String fMsg = msg;
                    runOnUiThread(() -> log("拉取失败：" + fMsg));
                    return;
                }
                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                org.json.JSONObject jo = new org.json.JSONObject(sb.toString());
                org.json.JSONArray data = jo.optJSONArray("data");
                if (data == null || data.length() == 0) {
                    runOnUiThread(() -> log("模型列表为空"));
                    return;
                }
                java.util.List<String> ids = new java.util.ArrayList<>();
                for (int i = 0; i < data.length(); i++) {
                    String id = data.optJSONObject(i).optString("id", "");
                    if (!id.isEmpty()) ids.add(id);
                }
                java.util.Collections.sort(ids);
                final String[] items = ids.toArray(new String[0]);
                runOnUiThread(() -> {
                    new AlertDialog.Builder(this)
                            .setTitle("选择模型（" + items.length + " 个）")
                            .setItems(items, (d, which) -> {
                                llmModel.setText(items[which]);
                                log("已选模型：" + items[which]);
                            })
                            .setNegativeButton("取消", null)
                            .show();
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                runOnUiThread(() -> log("拉取失败：" + msg));
            }
        }).start();
    }

    // v3.19：辅助模型拉取模型 id 列表（OpenAI 兼容 /models）
    private void auxFetchModels(EditText etBase, EditText etKey,
                                EditText etModel) {
        String baseUrl = etBase.getText().toString().trim();
        String apiKey = etKey.getText().toString().trim();
        if (baseUrl.isEmpty() || apiKey.isEmpty()) {
            log("先填 base_url 和 api_key");
            return;
        }
        String url = baseUrl.endsWith("/") ? baseUrl + "models"
                : baseUrl + "/models";
        final String fUrl = url;
        final String fKey = apiKey;
        log("正在拉取模型列表...");
        new Thread(() -> {
            try {
                java.net.URL u = new java.net.URL(fUrl);
                java.net.HttpURLConnection c =
                        (java.net.HttpURLConnection) u.openConnection();
                c.setRequestMethod("GET");
                c.setRequestProperty("Authorization", "Bearer " + fKey);
                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
                int code = c.getResponseCode();
                if (code != 200) {
                    String msg = "HTTP " + code;
                    try (java.io.InputStream es = c.getErrorStream()) {
                        if (es != null) {
                            byte[] buf = new byte[512];
                            int n = es.read(buf);
                            if (n > 0) msg += "：" + new String(buf, 0, n, "UTF-8");
                        }
                    } catch (Exception ignored) {}
                    final String fMsg = msg;
                    runOnUiThread(() -> log("拉取失败：" + fMsg));
                    return;
                }
                StringBuilder sb = new StringBuilder();
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(c.getInputStream(), "UTF-8"))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                org.json.JSONObject jo = new org.json.JSONObject(sb.toString());
                org.json.JSONArray data = jo.optJSONArray("data");
                if (data == null || data.length() == 0) {
                    runOnUiThread(() -> log("模型列表为空"));
                    return;
                }
                java.util.List<String> ids = new java.util.ArrayList<>();
                for (int i = 0; i < data.length(); i++) {
                    String id = data.optJSONObject(i).optString("id", "");
                    if (!id.isEmpty()) ids.add(id);
                }
                java.util.Collections.sort(ids);
                final String[] items = ids.toArray(new String[0]);
                runOnUiThread(() -> {
                    new AlertDialog.Builder(this)
                            .setTitle("选择模型（" + items.length + " 个）")
                            .setItems(items, (d, which) -> {
                                etModel.setText(items[which]);
                                log("已选模型：" + items[which]);
                            })
                            .setNegativeButton("取消", null)
                            .show();
                });
            } catch (Exception e) {
                final String msg = e.getMessage();
                runOnUiThread(() -> log("拉取失败：" + msg));
            }
        }).start();
    }

    // v2.93：查看任务日志列表
    // v3.11 Issue 8：记忆列表（可逐条删/一键清空）
    private void showMemoryList() {
        java.util.Map<String, String> mems = UserMemory.listAll(this);
        if (mems.isEmpty()) {
            log("暂无记忆");
            return;
        }
        String[] keys = mems.keySet().toArray(new String[0]);
        String[] items = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            String v = mems.get(keys[i]);
            items[i] = keys[i] + "："
                    + (v.length() > 30 ? v.substring(0, 30) + "…" : v);
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("🧠 记忆（" + keys.length + " 条）")
                .setItems(items, (d, which) -> {
                    String k = keys[which];
                    new android.app.AlertDialog.Builder(this)
                            .setTitle(k)
                            .setMessage(mems.get(k))
                            .setPositiveButton("删除", (d2, w2) -> {
                                UserMemory.remove(this, k);
                                log("已删除记忆：" + k);
                                showMemoryList();
                            })
                            .setNegativeButton("关闭", null)
                            .show();
                })
                .setNeutralButton("清空全部", (d, w) -> {
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("清空全部记忆？")
                            .setPositiveButton("清空", (d2, w2) -> {
                                UserMemory.clearAll(this);
                                log("已清空全部记忆");
                            })
                            .setNegativeButton("取消", null)
                            .show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showTaskLogs() {
        java.io.File[] logs = TaskRunLogger.listLogs(this);
        if (logs.length == 0) {
            log("暂无任务日志");
            return;
        }
        String[] names = new String[logs.length];
        for (int i = 0; i < logs.length; i++) {
            // task_20261019_141530_ab12.log → 2026-10-19 14:15:30
            String n = logs[i].getName().replace("task_", "").replace(".log", "");
            names[i] = n.length() >= 15
                    ? n.substring(0, 4) + "-" + n.substring(4, 6) + "-" + n.substring(6, 8)
                    + " " + n.substring(9, 11) + ":" + n.substring(11, 13) + ":" + n.substring(13, 15)
                    : n;
        }
        new AlertDialog.Builder(this)
                .setTitle("任务日志（" + logs.length + "）")
                .setItems(names, (d, which) -> showTaskLogContent(logs[which]))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showTaskLogContent(java.io.File f) {
        try {
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.FileReader(f))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append("\n");
            }
            TextView tv = new TextView(this);
            tv.setText(sb.toString());
            tv.setTextSize(12);
            tv.setPadding(dp(12), dp(12), dp(12), dp(12));
            ScrollView sv = new ScrollView(this);
            sv.addView(tv);
            new AlertDialog.Builder(this)
                    .setTitle(f.getName())
                    .setView(sv)
                    .setPositiveButton("关闭", null)
                    .show();
        } catch (Exception e) {
            log("读取失败：" + e.getMessage());
        }
    }

    private void startAgent() {
        String goal = agentGoal.getText().toString().trim();
        if (goal.isEmpty()) {
            log("先输入目标");
            return;
        }
        // 快捷指令优先级最高
        if (goal.startsWith("/")) {
            agentGoal.setText("");
            String cmd = goal.split("\\s+")[0].toLowerCase();
            if (!handleSlashCmd(cmd)) {
                agentLog("未知指令：" + cmd + "，输入 /help 查看");
            }
            return;
        }
        // v3.29：三端统一原则——执行中反馈、零token直达都不需要 Key/无障碍，先行处理；
        // 无障碍不再做硬门：root 优先，无 root 又无无障碍时只警告、仍放行（与 TG 一致）
        if (agentThread != null && agentThread.isAlive()) {
            // 执行中发消息 = 指出问题，注入为实时反馈
            feedbackQueue.offer(goal);
            agentGoal.setText("");
            refocusInput();
            agentLog("💬 已收到你的补充说明，智能体下一步会参考");
            return;
        }
        agentGoal.setText("");
        refocusInput();
        // 高频意图本地路由：零 token 直达，不烧大模型，不查 Key/权限
        LocalIntentRouter.RouteResult localRr = null;
        try {
            localRr = LocalIntentRouter.tryRoute(this, goal);
        } catch (Exception ignored) {
        }
        if (localRr != null) {
            agentLog((localRr.handled ? "✅ " : "⚠️ ") + localRr.message);
            if (localRr.handled) {
                // v3.51 步骤5：本地路由决策点打点（零 token 直达）
                Telemetry.decisionPoint(this, "app", "local_route", 1.0,
                        goal, "local_route", 1.0, null);
                historyStore.append("⚡本地：" + goal, true);
                runOnUiThread(this::refreshHistory);
                return;
            }
        } else {
            try {
                org.json.JSONObject rd = new org.json.JSONObject();
                rd.put("goal", goal);
                Telemetry.event(this, "app", "router_miss", rd);
            } catch (Exception ignored) {
            }
        }
        if (!llmStore.hasKey()) {
            log("先在「云端大模型」配置 API Key");
            return;
        }
        if (!AgentAccessibilityService.isRunning() && Root.findSu() == null) {
            agentLog("⚠️ 无障碍未开启且无 root，屏幕操控不可用；涉及看屏/点击的任务会受限");
        }
        // v2.82：纯聊天不走任务流，直接对话回复
        // v3.02：真流式输出
        if (LocalIntentRouter.isChat(goal)) {
            try {
                org.json.JSONObject cd = new org.json.JSONObject();
                cd.put("text", goal);
                Telemetry.event(this, "app", "chat", cd);
            } catch (Exception ignored) {
            }
            agentGoal.setText("");
            refocusInput();
            final String chatGoal = goal;
            agentLog("💬 " + chatGoal);
            new Thread(() -> {
                LlmClient llm = new LlmClient(llmStore.getBaseUrl(),
                        llmStore.getApiKeyRaw(), llmStore.getModel());
                String prompt = "你是GSIC，一个友好、简洁的手机助手。用户说：" + chatGoal
                        + "\n用一句话友好回应，如果用户没说具体要做什么，问他想让我干什么。不要输出JSON。"
                        + "【重要】你现在是纯聊天模式、没有任何操作手机的工具，绝不能声称已经帮用户"
                        + "打开/关闭/设置/发送/删除了任何东西；如果用户是在让你操作手机，"
                        + "直接告诉他：把这条单独发给我，我按任务执行。"; prompt = prompt + TermsGate.CORE_TEXT;
                final TextView tv = startStreamBubble();
                final StringBuilder full = new StringBuilder();
                LlmClient.Result r = llm.chatStream(prompt, token -> {
                    full.append(token);
                    if (tv != null) {
                        final String t = token;
                        runOnUiThread(() -> {
                            tv.append(t);
                            if (agentScrollView != null) {
                                agentScrollView.post(() -> agentScrollView.fullScroll(
                                        android.view.View.FOCUS_DOWN));
                            }
                        });
                    }
                });
                if (!r.ok) {
                    final String err = "出错了：" + r.text;
                    runOnUiThread(() -> {
                        if (tv != null) tv.setText("🤖 " + err);
                        else agentLog("🤖 " + err);
                    });
                } else {
                    // 存入历史
                    final String reply = full.toString();
                    runOnUiThread(() -> {
                        if (chatStore != null) {
                            String time = new java.text.SimpleDateFormat("HH:mm",
                                    java.util.Locale.getDefault())
                                    .format(new java.util.Date());
                            chatStore.append("agent", reply, time);
                        }
                    });
                }
            }).start();
            return;
        }
        // 不再自动新开对话：保留上下文，历史消息即上下文
        // 用户点"新对话"按钮才开新会话
        clearChat();
        final long taskStartMs = System.currentTimeMillis();
        lastGoal = goal;
        agentStop = false;
        final String taskGoal = goal;
        // 运行记录：创建进行中条目
        final long histId = historyStore.startTask(taskGoal);
        runOnUiThread(this::refreshHistory);
        agentThread = new Thread(() -> {
            boolean confirmedDone = false;
            boolean stopped = false;
            String supFeedback = "";
            try {
                // v3.51：App 通道任务登记（此前 App 任务不登记，流光无自愈、
                // 丢了全程黑）——与线程 finally 的 endTask 配对，监管多轮
                // 期间 AgentLoop 的登记嵌套在内，计数不归零不熄灯
                FloatWindow.beginTask(this, "🎯 " + taskGoal);
                // 悬浮窗权限：没有就先提示一次
                if (!FloatWindow.hasPermission(this)) {
                    runOnUiThread(() -> android.widget.Toast.makeText(this,
                            "开启悬浮窗权限可在后台看进度", android.widget.Toast.LENGTH_LONG).show());
                }
                final int MAX_SUP_ROUNDS = 2; // 监管者最多要求继续 2 轮
                final AgentLoop[] loopRef = new AgentLoop[1];
                String lastMissing = "";
                int stepBudget = AgentLoop.NO_LIMIT;

                // ===== 对话上下文（最近 6 条，裁剪） =====
                String convCtx = "";
                try {
                    java.util.List<ChatStore.Msg> allMsgs = chatStore.currentMsgs();
                    StringBuilder cb = new StringBuilder();
                    int start = Math.max(0, allMsgs.size() - 6);
                    for (int ci = start; ci < allMsgs.size(); ci++) {
                        ChatStore.Msg cm = allMsgs.get(ci);
                        String role = "user".equals(cm.role) ? "用户"
                                : "agent".equals(cm.role) ? "助手" : "系统";
                        String txt = cm.text.length() > 80
                                ? cm.text.substring(0, 80) + "…" : cm.text;
                        cb.append(role).append("：").append(txt).append("\n");
                    }
                    convCtx = cb.toString().trim();
                } catch (Exception ignored) {
                }
                final String fCtx = convCtx;

                // ===== 思考者规划阶段 =====
                String thinkerPlanText = "";
                Thinker.Plan tPlan = null;
                agentLog("🧠 思考者规划中…");
                try {
                    java.util.List<AgentAccessibilityService.NodeInfo> pNodes =
                            AgentAccessibilityService.dumpScreen();
                    String pScreen = pNodes.isEmpty()
                            ? "(读屏为空)" : AgentLoop.serialize(pNodes);
                    tPlan = Thinker.plan(agentClient(), taskGoal, pScreen);
                    if (tPlan.ok) {
                        thinkerPlanText = tPlan.toText();
                        agentLog("🧠 思考者规划：\n" + thinkerPlanText);
                    } else {
                        agentLog("🧠 思考者规划失败，直接执行");
                    }
                } catch (Exception e) {
                    agentLog("🧠 思考者异常：" + e.getMessage());
                }
                final String fPlan = thinkerPlanText;
                final java.util.List<String> fSteps =
                        (tPlan != null && tPlan.steps != null)
                        ? new java.util.ArrayList<>(tPlan.steps)
                        : new java.util.ArrayList<String>();

                int totalSteps = 0;
                int lastProgressPct = -1;
                int completedRounds = 0;
                // 审计：跨监管轮次累加每步动作明细
                final org.json.JSONArray auditAll = new org.json.JSONArray();
                for (int supRound = 0; ; supRound++) {
                    // ===== 执行阶段（含失败后台重试） =====
                    boolean finalDone = false;
                    int maxRetry = 2;
                    for (int attempt = 0; attempt <= maxRetry; attempt++) {
                        if (agentStop) break;
                        if (attempt > 0) {
                            agentLog("↻ 后台重试第 " + attempt + " 次…");
                        }
                        final boolean isContinue = supRound > 0;
                        // v3.51：不再每轮 hide+重加（闪烁且一次失败就丢窗），
                        // 改断言补窗——窗在就不动，缺了才补
                        runOnUiThread(FloatWindow::assertShown);
                        runOnUiThread(this::startInputFlow);
                        behaviorLog.user("启动智能体任务", taskGoal);
                        behaviorLog.app("智能体开始执行",
                                "模型=" + (agentModelOverride.isEmpty()
                                ? llmStore.getModel() : agentModelOverride));
                        AgentLoop loop = new AgentLoop(this, agentClient(),
                                new AgentLoop.Listener() {
                                    @Override
                                    public void onLog(String msg) {
                                        // v2.62：监管者消息分流到独立输出区
                                        if (msg.startsWith("🔍")
                                                || msg.contains("监管者")) {
                                            supervisorLog(msg);
                                        } else {
                                            agentLog(msg);
                                        }
                                        // 悬浮窗流式显示思考与执行过程
                                        String line = null;
                                        if (msg.startsWith("🤔")) {
                                            line = msg.length() > 22
                                                    ? msg.substring(0, 22) + "…"
                                                    : msg;
                                        } else if (msg.startsWith("💭")) {
                                            String body = msg.substring(2).trim();
                                            line = "💭 "
                                                    + (body.length() > 20
                                                            ? body.substring(0, 20) + "…"
                                                            : body);
                                        } else if (msg.startsWith("🖐")) {
                                            String body = msg.substring(2).trim();
                                            line = "🖐 "
                                                    + (body.length() > 20
                                                            ? body.substring(0, 20) + "…"
                                                            : body);
                                        } else if (msg.startsWith("🔍")
                                                || msg.startsWith("🔁")
                                                || msg.startsWith("🧠")) {
                                            line = msg.length() > 22
                                                    ? msg.substring(0, 22) + "…"
                                                    : msg;
                                        }
                                        if (line != null) FloatWindow.pushLine(line);
                                    }

                                    @Override
                                    public boolean isStopped() {
                                        return agentStop;
                                    }

                                    @Override
                                    public boolean askContinue(String reason) {
                                        return askUserContinue(reason);
                                    }
                                });
                        loop.setAllowAdvanced(agentAdvBox != null && agentAdvBox.isChecked());
                        loop.setSupervisorEnabled(agentSupervisorBox != null
                                && agentSupervisorBox.isChecked());
                        loop.setSupervisorClient(llmStore.supervisorClient());
                        // 风险审批：编译期分级 + 用户策略，本地弹窗确认
                        loop.setRiskPolicy(riskPolicy);
                        loop.setApprover(MainActivity.this::askUserApprove);
                        loop.setRemote(false);
                        loop.setChannel("app");
                        if (!fCtx.isEmpty()) loop.setConversationContext(fCtx);
                        String profTxt = userProfile.describeForPrompt();
                        if (!profTxt.isEmpty()) loop.setConversationContext(
                                fCtx.isEmpty() ? profTxt : fCtx + "\n" + profTxt);
                        // v3.44：一份共享记忆——叠加三端共用轮次
                        String sharedCtx = SharedMemory.recentText(this, 3);
                        if (!sharedCtx.isEmpty()) {
                            String base = fCtx.isEmpty() ? "" : fCtx + "\n";
                            String prof = profTxt.isEmpty()
                                    ? "" : "\n" + profTxt;
                            loop.setConversationContext(
                                    base + sharedCtx + prof);
                        }
                        if (!fPlan.isEmpty()) loop.setPlan(fPlan);
                        if (!fSteps.isEmpty()) loop.setTodoSteps(fSteps);
                        if (!supFeedback.isEmpty()) loop.setSupervisorFeedback(supFeedback);
                        loop.setFeedbackSupplier(() -> feedbackQueue.poll());
                        loopRef[0] = loop;
                        // v3.50 第 3 步：低置信拦截——loop.run() 被拦截时弹选项对话框，
                        // 用户点选后把选项拼回 goal 重跑（最多澄清 2 轮；取消则停任务）
                        String execGoal = taskGoal;
                        int clarifyRounds = 0;
                        finalDone = false;
                        while (!agentStop) {
                            final String g = execGoal;
                            finalDone = loop.run(g, stepBudget);
                            if (!loop.needsClarify()) break;
                            if (clarifyRounds >= 2) {
                                agentLog("❓ 已澄清 2 轮仍低置信，停止（请换个说法再试）");
                                break;
                            }
                            clarifyRounds++;
                            String clarified = askClarifyGoal(g, loop);
                            if (clarified == null) {
                                agentLog("❓ 用户取消澄清，任务停止");
                                break;
                            }
                            execGoal = clarified;
                            agentLog("❓ 澄清后重跑：" + execGoal);
                        }
                        if (finalDone || agentStop) break;
                        if (attempt < maxRetry) {
                            try {
                                Thread.sleep(3000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                    // 审计：收拢本轮每步明细（停止时也要留档）
                    try {
                        org.json.JSONArray part = new org.json.JSONArray(
                                loopRef[0] != null
                                        ? loopRef[0].getAuditJson() : "[]");
                        for (int pi = 0; pi < part.length(); pi++) {
                            org.json.JSONObject po = part.getJSONObject(pi);
                            po.put("round", supRound + 1);
                            auditAll.put(po);
                        }
                    } catch (Exception ignored) {
                    }
                    if (agentStop) {
                        stopped = true;
                        break;
                    }
                    lastHistory = loopRef[0] != null
                            ? loopRef[0].getLastHistory() : "";
                    // 累加步数
                    if (lastHistory != null) {
                        for (String hl : lastHistory.split("\n")) {
                            if (hl.startsWith("第") && hl.contains("步：")) {
                                totalSteps++;
                            }
                        }
                    }
                    completedRounds = supRound + 1;

                    // ===== 监管者复核阶段 =====
                    boolean supOn = agentSupervisorBox != null
                            && agentSupervisorBox.isChecked();
                    if (!supOn) {
                        // 未开启监管者：以执行者为准
                        confirmedDone = finalDone;
                        if (finalDone) scheduleSkillCard(taskGoal, lastHistory);
                        break;
                    }
                    supervisorLog("🔍 监管者复核中…");
                    Supervisor.Verdict verdict = new Supervisor.Verdict();
                    try {
                        java.util.List<AgentAccessibilityService.NodeInfo> fNodes =
                                AgentAccessibilityService.dumpScreen();
                        String fScreen = fNodes.isEmpty()
                                ? "(读屏为空)" : AgentLoop.serialize(fNodes);
                        // v3.50 意图层：终审判据绑定——把任务书传给监管者，只按任务书完成判据裁决
                        verdict = Supervisor.review(llmStore.supervisorClient(), taskGoal,
                                finalDone, lastHistory, fScreen, null,
                                loopRef[0] != null ? loopRef[0].getTaskBook() : null);
                    } catch (Exception e) {
                        supervisorLog("🔍 监管者复核异常：" + e.getMessage());
                    }
                    supervisorLog(Supervisor.conclude(finalDone, verdict));
                    if (verdict.ok) lastProgressPct = verdict.progressPct;

                    if (verdict.ok && verdict.completed) {
                        confirmedDone = true;
                        scheduleSkillCard(taskGoal, lastHistory);
                        break;
                    }
                    if (!verdict.ok) {
                        confirmedDone = finalDone; // 复核失败，信任执行者
                        if (finalDone) scheduleSkillCard(taskGoal, lastHistory);
                        break;
                    }
                    // 未完成：先看是不是卡住了（同一问题连续两轮）
                    if (supRound > 0 && Supervisor.isStuck(lastMissing, verdict.missing)) {
                        supervisorLog("🔍 监管者：连续两轮卡在同一问题（"
                                + verdict.missing + "），停手以免浪费 token，"
                                + "建议人工查看");
                        confirmedDone = false;
                        break;
                    }
                    lastMissing = verdict.missing;
                    if (supRound >= MAX_SUP_ROUNDS) {
                        supervisorLog("🔍 监管者：已达最大复核轮数，不再继续");
                        confirmedDone = false;
                        break;
                    }
                    // v3.34：步数不设限，监管者只给反馈、不再砍步数
                                        supFeedback = Supervisor.buildFeedback(verdict);
                    agentLog("🔁 监管者：完成度约 " + verdict.progressPct + "%，需要继续执行（第 " + (supRound + 2) + " 轮）…");
                }

                stopped = agentStop;
                long usedSec = (System.currentTimeMillis() - taskStartMs) / 1000;
                String resultDetail = "用时" + usedSec + "秒";
                // 运行记录：丰富执行情况（步数/监管轮数/完成度）
                String richDetail = resultDetail
                        + " · " + totalSteps + "步"
                        + " · 监管" + completedRounds + "轮"
                        + (lastProgressPct >= 0
                                ? " · 完成度" + lastProgressPct + "%" : "");
                // v3.44：一份共享记忆——App 这一轮也写进三端共用轮次
                // v3.45：结果尾巴三端对齐——带上用时/步数/监管/完成度明细
                SharedMemory.addRound(this, "app", taskGoal,
                        (confirmedDone ? "任务完成"
                                : (stopped ? "被手动停止" : "任务未完成"))
                                + "，" + richDetail);
                if (confirmedDone) {
                    agentLog("🎉 任务完成（监管者已确认）");
                    // v3.08：任务完成给日志入口（用户在玩时点开看，不在时只存文件）
                    final String logPath = loopRef[0] != null
                            ? loopRef[0].getTaskLogPath() : null;
                    if (logPath != null) {
                        runOnUiThread(() -> showTaskLogButton(logPath));
                    }
                    runOnUiThread(() -> android.widget.Toast.makeText(this,
                            "✅ 任务已完成", android.widget.Toast.LENGTH_LONG).show());
                    historyStore.finishTask(histId, taskGoal, true,
                            richDetail + " · 已确认");
                } else if (stopped) {
                    agentLog("⏹ 已停止");
                    historyStore.finishTask(histId, taskGoal, false,
                            richDetail + " · 手动停止");
                } else {
                    agentLog("❌ 任务未完成");
                    runOnUiThread(() -> android.widget.Toast.makeText(this,
                            "任务未完成，监管者已复核", android.widget.Toast.LENGTH_LONG).show());
                    historyStore.finishTask(histId, taskGoal, false,
                            richDetail + " · 未完成");
                }
                runOnUiThread(this::refreshHistory);
                // 审计明细入库
                if (auditAll.length() > 0) {
                    historyStore.setSteps(histId, auditAll.toString());
                }
                // 不定时自动总结用户画像：每 3 个任务一次，后台执行
                try {
                    int done = getSharedPreferences("profile", MODE_PRIVATE)
                            .getInt("task_count", 0) + 1;
                    getSharedPreferences("profile", MODE_PRIVATE).edit()
                            .putInt("task_count", done).apply();
                    if (done % 3 == 0) {
                        new Thread(() -> {
                            summarizeUserProfile();
                            runOnUiThread(this::renderUserProfile);
                        }).start();
                    }
                } catch (Exception ignored) {
                }
            } catch (Exception e) {
                agentLog("异常：" + e.getMessage());
            } finally {
                TaskLock.release("app"); // v3.32：三通道互斥释放
                runOnUiThread(() -> {
                    FloatWindow.endTask("");
                    stopInputFlow();
                    if (agentSendBtn != null) agentSendBtn.setText("发送");
                });
            }        }, "agent-loop");
        // v3.32：三通道真互斥——TG/远控占用时不并发抢屏
        if (!TaskLock.tryAcquire("app")) {
            agentLog("⏳ 任务忙（" + TaskLock.current() + " 通道正在执行），请稍后再试");
            return;
        }
        try {
            agentThread.start();
        } catch (Exception e) {
            TaskLock.release("app");
            throw e;
        }
        runOnUiThread(() -> {
            if (agentSendBtn != null) agentSendBtn.setText("停止");
        });
        agentLog("▶ 开始执行（可点停止）");
    }

    private String localIps() {
        StringBuilder sb = new StringBuilder();
        try {
            for (java.net.NetworkInterface ni :
                    java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                for (java.net.InetAddress a :
                        java.util.Collections.list(ni.getInetAddresses())) {
                    if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address) {
                        if (sb.length() > 0) sb.append("，");
                        sb.append(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return sb.length() == 0 ? "未知" : sb.toString();
    }

    // ---------- 主页 ----------

    // ---------- 主页（一级菜单 → 二级详情） ----------

    private android.widget.ScrollView homeMenuScroll;
    private LinearLayout homeDetailWrap;
    private java.util.Map<String, LinearLayout> homeSections;

    private LinearLayout buildHomePage() {
        homeSections = new java.util.HashMap<>();
        homeSections.put("status", buildStatusCard());
        homeSections.put("llm", buildLlmCard());
        homeSections.put("ask", buildAskCard());
        homeSections.put("skill", buildSkillCard());
        homeSections.put("sched", buildSchedCard());
        homeSections.put("hist", buildHistCard());

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(0, dp(8), 0, dp(100)); // v3.20：底部避让悬浮胶囊tab
        LinearLayout homeList = menuCard();
        addMenuRow(homeList, "📊", "状态", "无障碍权限与服务状态",
                v -> showHomeSection("status"));
        addMenuRow(homeList, "🧠", "大模型配置", "服务商、Key 与连接测试",
                v -> showHomeSection("llm"));
        addMenuRow(homeList, "💬", "问答测试", "快速验证大模型连通与回答",
                v -> showHomeSection("ask"));
        addMenuRow(homeList, "🛠️", "技能", "本地技能中心",
                v -> showHomeSection("skill"));
        addMenuRow(homeList, "⏰", "定时任务", "本地定时调度",
                v -> showHomeSection("sched"));
        addMenuRow(homeList, "📝", "运行记录", "任务执行历史",
                v -> showHomeSection("hist"));
        addMenuRow(homeList, "🧠", "智能沉淀", "运行数据汇总报告，分享给 Muse 分析更新",
                v -> showSediment());
        menu.addView(homeList);

        homeMenuScroll = new ScrollView(this);
        homeMenuScroll.addView(menu);
        homeDetailWrap = new LinearLayout(this);
        homeDetailWrap.setOrientation(LinearLayout.VERTICAL);
        homeDetailWrap.setVisibility(View.GONE);

        outer.addView(homeMenuScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        outer.addView(homeDetailWrap, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return outer;
    }

    /** 分组菜单容器（一张卡片装多行） */
    private LinearLayout menuCard() {
        LinearLayout c = card();
        c.setPadding(dp(6), dp(6), dp(6), dp(6));
        return c;
    }

    /** Material 列表行：图标 + 标题 + 副标题 + 箭头 */
    private TextView addMenuRow(LinearLayout list, String icon, String title,
                            String sub, android.view.View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setClickable(true);
        row.setFocusable(true);
        // 涟漪
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            android.graphics.drawable.GradientDrawable mask = rbg(0xFFFFFFFF, 16);
            android.content.res.ColorStateList ripple =
                    android.content.res.ColorStateList.valueOf(0x20000000);
            row.setBackground(new android.graphics.drawable.RippleDrawable(
                    ripple, null, mask));
        }
        // 图标圆
        TextView ic = new TextView(this);
        ic.setText(icon);
        ic.setTextSize(20);
        ic.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable circle =
                new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(DyColor.primaryContainer(this));
        ic.setBackground(circle);
        int sz = dp(44);
        LinearLayout.LayoutParams icLp = new LinearLayout.LayoutParams(sz, sz);
        icLp.setMargins(0, 0, dp(12), 0);
        row.addView(ic, icLp);
        // 文字
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(this);
        t1.setText(title);
        t1.setTextSize(16);
        t1.setTextColor(DyColor.onSurface(this));
        txt.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText(sub);
        t2.setTextSize(13);
        t2.setTextColor(DyColor.onSurfaceVariant(this));
        t2.setPadding(0, dp(2), 0, 0);
        txt.addView(t2);
        LinearLayout.LayoutParams txtLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(txt, txtLp);
        // 箭头
        TextView chev = new TextView(this);
        chev.setText("›");
        chev.setTextSize(22);
        chev.setTextColor(DyColor.onSurfaceVariant(this));
        row.addView(chev);
        row.setOnClickListener(onClick);
        // 分隔线
        if (list.getChildCount() > 0) {
            android.view.View div = new android.view.View(this);
            div.setBackgroundColor(DyColor.outline(this));
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            divLp.setMargins(dp(68), 0, dp(12), 0);
            list.addView(div, divLp);
        }
        list.addView(row);
        return t2;
    }

    /** v3.31 智能沉淀：报告列表 + 分享给 Muse */
    private void showSediment() {
        java.util.List<java.io.File> files = SedimentReport.all(this);
        java.util.List<String> names = new java.util.ArrayList<>();
        if (files.isEmpty()) {
            names.add("（暂无报告，有运行数据后在 1/7/30 天节点自动生成）");
        } else {
            for (java.io.File f : files) names.add(f.getName());
        }
        names.add("➕ 立即生成近 7 天报告");
        new android.app.AlertDialog.Builder(this)
                .setTitle("🧠 智能沉淀报告")
                .setItems(names.toArray(new String[0]), (d, w) -> {
                    if (w == names.size() - 1) {
                        new Thread(() -> {
                            java.io.File f = SedimentReport.generate(this, 7);
                            runOnUiThread(() -> {
                                if (f != null) shareSediment(f);
                                else android.widget.Toast.makeText(this, "近 7 天还没有运行数据", android.widget.Toast.LENGTH_SHORT).show();
                            });
                        }).start();
                    } else if (!files.isEmpty() && w < files.size()) {
                        shareSediment(files.get(w));
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void shareSediment(java.io.File f) {
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.FileReader(f));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
            r.close();
            android.content.Intent it = new android.content.Intent(
                    android.content.Intent.ACTION_SEND);
            it.setType("text/plain");
            it.putExtra(android.content.Intent.EXTRA_TEXT, sb.toString());
            startActivity(android.content.Intent.createChooser(it, "分享沉淀报告给 Muse"));
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "读取报告失败：" + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void showHomeSection(String key) {
        LinearLayout sec = homeSections.get(key);
        if (sec == null) return;
        if (sec.getParent() != null) {
            ((android.view.ViewGroup) sec.getParent()).removeView(sec);
        }
        homeDetailWrap.removeAllViews();
        Button back = smallBtn("← 返回");
        back.setOnClickListener(v -> showHomeMenu());
        homeDetailWrap.addView(back);
        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        sv.setPadding(0, 0, 0, dp(100)); // v3.20：底部避让悬浮胶囊tab
        sv.addView(sec);
        homeDetailWrap.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        homeMenuScroll.setVisibility(View.GONE);
        homeDetailWrap.setVisibility(View.VISIBLE);
    }

    private void showHomeMenu() {
        homeDetailWrap.setVisibility(View.GONE);
        homeMenuScroll.setVisibility(View.VISIBLE);
    }

    private LinearLayout buildStatusCard() {
        LinearLayout statusCard = card();
        statusCard.addView(cardTitle("状态"));
        statusView = new TextView(this);
        statusView.setTextSize(14);
        statusCard.addView(statusView);
        svcStatusView = new TextView(this);
        svcStatusView.setTextSize(14);
        statusCard.addView(svcStatusView);
        adbStatusView = new TextView(this);
        adbStatusView.setTextSize(14);
        statusCard.addView(adbStatusView);
        shizukuStatusView = new TextView(this);
        shizukuStatusView.setTextSize(14);
        statusCard.addView(shizukuStatusView);
        wifiAdbStatusView = new TextView(this);
        wifiAdbStatusView.setTextSize(14);
        statusCard.addView(wifiAdbStatusView);
        Button bShizuku = smallBtn("打开 Shizuku");
        bShizuku.setOnClickListener(v -> {
            try {
                startActivity(getPackageManager()
                        .getLaunchIntentForPackage("moe.shizuku.privileged.api"));
            } catch (Exception e) {
                log("未安装 Shizuku");
            }
        });
        statusCard.addView(bShizuku);
        Button bAccess = btn("开启无障碍权限");
        bAccess.setOnClickListener(v -> showAccessRationale());
        statusCard.addView(bAccess);
        Button bDump = btn("📋 抓取界面元素（写技能用）");
        bDump.setOnClickListener(v -> new Thread(() -> {
            java.util.List<ElementDumper.El> els = ElementDumper.capture();
            String pkg = "";
            try {
                AgentAccessibilityService sv = AgentAccessibilityService.get();
                if (sv != null && sv.getRootInActiveWindow() != null)
                    pkg = sv.getRootInActiveWindow().getPackageName().toString();
            } catch (Exception ignored) {
            }
            final String cid = captureStore.saveCapture(els, pkg);
            runOnUiThread(() -> {
                if (cid != null) showCaptureRecord(cid);
                else log("抓取失败：无障碍未开启或界面为空");
            });
        }).start());
        statusCard.addView(bDump);
        // 抓取记录入口
        Button bCapList = btn("📸 抓取记录");
        bCapList.setOnClickListener(v -> showCaptureList());
        statusCard.addView(bCapList);
        final Button bCapFloat = btn(CaptureFloat.isShowing()
                ? "🎯 关闭悬浮抓取" : "🎯 悬浮抓取（去其他 App 点一下即抓）");
        bCapFloat.setOnClickListener(v -> {
            if (CaptureFloat.isShowing()) {
                CaptureFloat.hide();
                bCapFloat.setText("🎯 悬浮抓取（去其他 App 点一下即抓）");
                log("已关闭悬浮抓取");
            } else {
                CaptureFloat.show(this);
                if (CaptureFloat.isShowing()) {
                    bCapFloat.setText("🎯 关闭悬浮抓取");
                    log("悬浮按钮已显示，切到目标 App 点 🎯 抓取");
                }
            }
        });
        statusCard.addView(bCapFloat);
        return statusCard;
    }

    private LinearLayout buildLlmCard() {
        LinearLayout llmCard = card();
        llmCard.addView(cardTitle("大模型配置（多密钥/多提供商）"));
        llmProfilesBox = new LinearLayout(this);
        llmProfilesBox.setOrientation(LinearLayout.VERTICAL);
        llmCard.addView(llmProfilesBox);
        Button bLlmNew = smallBtn("＋ 新增配置");
        bLlmNew.setOnClickListener(v -> {
            llmNewMode = true;
            llmProvider = "自定义";
            llmBaseUrl.setText("");
            llmApiKey.setText("");
            llmModel.setText("");
            llmVisionModel.setText("");
            llmNameInput.setText("");
            log("填写后点保存，即新增一套密钥配置");
        });
        llmCard.addView(bLlmNew);
        LinearLayout provRow = new LinearLayout(this);
        provRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView provLabel = grayText("服务商：");
        provRow.addView(provLabel);
        for (String[] p : LlmStore.PRESETS) {
            final String[] preset = p;
            Button b = smallBtn(preset[0]);
            b.setOnClickListener(v -> {
                llmProvider = preset[0];
                if (!preset[1].isEmpty()) llmBaseUrl.setText(preset[1]);
                if (!preset[2].isEmpty()) llmModel.setText(preset[2]);
                // 智谱默认配视觉模型
                if ("智谱".equals(preset[0]) && llmVisionModel != null
                        && llmVisionModel.getText().toString().isEmpty()) {
                    llmVisionModel.setText("glm-4v-flash");
                }
                log("已选 " + preset[0]);
            });
            provRow.addView(b);
        }
        llmCard.addView(provRow);
        llmNameInput = new EditText(this);
        llmNameInput.setHint("配置名称，如：主力/备用");
        llmNameInput.setTextSize(13);
        llmNameInput.setSingleLine(true);
        llmCard.addView(llmNameInput);
        llmBaseUrl = new EditText(this);
        llmBaseUrl.setHint("base_url，如 https://api.deepseek.com/v1");
        llmBaseUrl.setTextSize(13);
        llmBaseUrl.setSingleLine(true);
        llmCard.addView(llmBaseUrl);
        llmApiKey = new EditText(this);
        llmApiKey.setHint("api_key（只存本机，只发往上面的地址）");
        llmApiKey.setTextSize(13);
        llmApiKey.setSingleLine(true);
        llmApiKey.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        llmCard.addView(llmApiKey);
        llmModel = new EditText(this);
        llmModel.setHint("model，如 deepseek-chat");
        llmModel.setTextSize(13);
        llmModel.setSingleLine(true);
        // v2.89：模型行加拉取按钮
        LinearLayout modelRow = new LinearLayout(this);
        modelRow.setOrientation(LinearLayout.HORIZONTAL);
        modelRow.addView(llmModel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button bFetchModels = smallBtn("拉取");
        bFetchModels.setOnClickListener(v -> fetchModels());
        modelRow.addView(bFetchModels);
        llmCard.addView(modelRow);
        llmVisionModel = new EditText(this);
        llmVisionModel.setHint("视觉模型（可空，如 glm-4v-flash，智能体看屏幕用）");
        llmVisionModel.setTextSize(13);
        llmVisionModel.setSingleLine(true);
        llmCard.addView(llmVisionModel);
        LinearLayout llmBtns = new LinearLayout(this);
        llmBtns.setOrientation(LinearLayout.HORIZONTAL);
        Button bLlmSave = smallBtn("保存配置");
        bLlmSave.setOnClickListener(v -> {
            String key = llmApiKey.getText().toString().trim();
            String nm = llmNameInput.getText().toString().trim();
            if (llmNewMode) {
                String nid = llmStore.add(nm, llmProvider,
                        llmBaseUrl.getText().toString(),
                        key, llmModel.getText().toString());
                llmStore.update(nid, null, null, null, null, null,
                        llmVisionModel.getText().toString());
                llmNewMode = false;
                log("已新增配置：" + llmStore.getActiveName());
            } else {
                if (key.isEmpty()) key = llmStore.getApiKeyRaw(); // 为空则保留原 key
                LlmStore.Profile ap = llmStore.getActive();
                if (ap != null) llmStore.update(ap.id, nm, llmProvider,
                        llmBaseUrl.getText().toString(), key,
                        llmModel.getText().toString(),
                        llmVisionModel.getText().toString());
                log("已保存：" + llmStore.getActiveName());
            }
            refreshLlm();
            refreshLlmProfiles();
            refreshAskModels();
            refreshAgentModels();
        });
        Button bLlmDel = smallBtn("删除");
        bLlmDel.setOnClickListener(v -> {
            LlmStore.Profile ap = llmStore.getActive();
            if (ap == null) return;
            if (llmStore.delete(ap.id)) {
                log("已删除：" + ap.name);
                refreshLlm();
                refreshLlmProfiles();
            } else {
                log("至少保留一套配置");
            }
        });
        Button bLlmTest = smallBtn("测试连接");
        bLlmTest.setOnClickListener(v -> {
            llmStatus.setText("连接中…");
            llmStatus.setTextColor(TEXT_GRAY);
            new Thread(() -> {
                LlmClient.Result r = llmStore.client().testConnection();
                runOnUiThread(() -> {
                    llmStatus.setText(r.text);
                    llmStatus.setTextColor(r.ok ? GREEN : RED);
                });
            }).start();
        });
        llmBtns.addView(bLlmSave);
        llmBtns.addView(bLlmDel);
        llmBtns.addView(bLlmTest);
        llmCard.addView(llmBtns);
        llmStatus = new TextView(this);
        llmStatus.setTextSize(13);
        llmStatus.setTextColor(TEXT_GRAY);
        llmCard.addView(llmStatus);

        // ---- 辅助模型（智能体按需调用，如智谱视觉） ----
        LinearLayout auxCard = card();
        LinearLayout auxHeader = new LinearLayout(this);
        auxHeader.setOrientation(LinearLayout.HORIZONTAL);
        auxHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView auxTitle = new TextView(this);
        auxTitle.setText("辅助模型");
        auxTitle.setTextSize(16);
        auxTitle.setTypeface(auxTitle.getTypeface(),
                android.graphics.Typeface.BOLD);
        auxTitle.setTextColor(DyColor.onSurface(this));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        auxHeader.addView(auxTitle, alp);
        Button bAuxAdd = smallBtn("＋ 添加");
        bAuxAdd.setOnClickListener(v -> auxModelDialog(null));
        auxHeader.addView(bAuxAdd);
        auxCard.addView(auxHeader);
        auxCard.addView(grayText("独立于主力模型，智能体按需调用（如看屏幕用智谱视觉）"));
        auxModelsBox = new LinearLayout(this);
        auxModelsBox.setOrientation(LinearLayout.VERTICAL);
        auxCard.addView(auxModelsBox);
        llmCard.addView(auxCard);
        refreshAuxModels();
        return llmCard;
    }

    /** 刷新辅助模型列表 */
    private void refreshAuxModels() {
        if (auxModelsBox == null || auxModelStore == null) return;
        auxModelsBox.removeAllViews();
        java.util.List<AuxModelStore.AuxModel> list = auxModelStore.list();
        if (list.isEmpty()) {
            auxModelsBox.addView(grayText("暂无辅助模型，点右上添加（如智谱视觉）"));
            return;
        }
        for (AuxModelStore.AuxModel m : list) {
            final AuxModelStore.AuxModel am = m;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            TextView t = new TextView(this);
            String kind = AuxModelStore.kindName(am.kind);
            t.setText(kind + " · " + am.name + "\n" + am.model
                    + "（" + AuxModelStore.maskKey(am.apiKey) + "）");
            t.setTextSize(13);
            t.setTextColor(DyColor.onSurface(this));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(t, lp);
            Button bEdit = smallBtn("编辑");
            bEdit.setOnClickListener(v -> auxModelDialog(am));
            row.addView(bEdit);
            Button bDel = smallBtn("删除");
            bDel.setOnClickListener(v -> {
                auxModelStore.delete(am.id);
                refreshAuxModels();
                log("已删除辅助模型");
            });
            row.addView(bDel);
            auxModelsBox.addView(row);
        }
    }

    /** 辅助模型添加/编辑对话框 */
    private void auxModelDialog(AuxModelStore.AuxModel exist) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), dp(8), dp(8), dp(8));
        EditText etName = new EditText(this);
        etName.setHint("名称，如：智谱视觉");
        etName.setTextSize(13);
        if (exist != null) etName.setText(exist.name);
        form.addView(etName);
        EditText etBase = new EditText(this);
        etBase.setHint("base_url，如 https://open.bigmodel.cn/api/paas/v4");
        etBase.setTextSize(13);
        if (exist != null) etBase.setText(exist.baseUrl);
        else etBase.setText("https://open.bigmodel.cn/api/paas/v4");
        form.addView(etBase);
        EditText etKey = new EditText(this);
        etKey.setHint("api_key（只存本机）");
        etKey.setTextSize(13);
        etKey.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(etKey);
        EditText etModel = new EditText(this);
        etModel.setHint("model，如 glm-4v-flash");
        etModel.setTextSize(13);
        if (exist != null) etModel.setText(exist.model);
        else etModel.setText("glm-4v-flash");
        // v3.19：model 行加拉取按钮（从 base_url 拉取模型 id 列表）
        LinearLayout modelRow = new LinearLayout(this);
        modelRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        modelRow.addView(etModel, mp);
        Button bFetchAux = smallBtn("拉取");
        bFetchAux.setOnClickListener(v -> auxFetchModels(etBase, etKey, etModel));
        modelRow.addView(bFetchAux);
        form.addView(modelRow);
        // 用途选择：视觉/语音/图像/对话
        final String[] kinds = {AuxModelStore.KIND_VISION,
                AuxModelStore.KIND_VOICE, AuxModelStore.KIND_IMAGE,
                AuxModelStore.KIND_CHAT};
        final int[] selKind = {0};
        if (exist != null) {
            for (int i = 0; i < kinds.length; i++) {
                if (kinds[i].equals(exist.kind)) {
                    selKind[0] = i;
                    break;
                }
            }
        }
        android.widget.RadioGroup rg = new android.widget.RadioGroup(this);
        rg.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < kinds.length; i++) {
            android.widget.RadioButton rb =
                    new android.widget.RadioButton(this);
            rb.setText(AuxModelStore.kindName(kinds[i]) + "模型");
            rb.setTextSize(13);
            rb.setId(i);
            rg.addView(rb);
        }
        rg.check(selKind[0]);
        rg.setOnCheckedChangeListener((g, id) -> selKind[0] = id);
        form.addView(rg);

        new android.app.AlertDialog.Builder(this)
                .setTitle(exist == null ? "添加辅助模型" : "编辑辅助模型")
                .setView(form)
                .setPositiveButton("保存", (d, w) -> {
                    String nm = etName.getText().toString().trim();
                    String bu = etBase.getText().toString().trim();
                    String ky = etKey.getText().toString().trim();
                    String md = etModel.getText().toString().trim();
                    if (bu.isEmpty() || md.isEmpty()) {
                        log("地址和模型不能为空");
                        return;
                    }
                    if (exist == null) {
                        if (ky.isEmpty()) {
                            log("请填写 api_key");
                            return;
                        }
                        auxModelStore.add(nm, bu, ky, md, kinds[selKind[0]]);
                        log("已添加辅助模型");
                    } else {
                        auxModelStore.update(exist.id, nm, bu, ky, md,
                                kinds[selKind[0]]);
                        log("已保存");
                    }
                    refreshAuxModels();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private LinearLayout buildAgentPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12), dp(8), dp(12), 0);
        page.setBackgroundColor(DyColor.surface(this));

        // 顶栏：标题 + 历史记录按钮
        LinearLayout agentHeader = new LinearLayout(this);
        agentHeader.setOrientation(LinearLayout.HORIZONTAL);
        agentHeader.setGravity(Gravity.CENTER_VERTICAL);
        agentHeader.setPadding(0, 0, 0, dp(4));
        TextView agentTitle = new TextView(this);
        agentTitle.setText("思考执行员");
        agentTitle.setTextSize(18);
        agentTitle.setTypeface(agentTitle.getTypeface(),
                android.graphics.Typeface.BOLD);
        agentTitle.setTextColor(DyColor.onSurface(this));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        agentHeader.addView(agentTitle, titleLp);
        Button histBtn = smallBtn("🕘 历史");
        histBtn.setOnClickListener(v -> showChatHistory());
        agentHeader.addView(histBtn);
        Button newChatBtn = smallBtn("✏️ 新对话");
        newChatBtn.setOnClickListener(v -> {
            if (chatStore != null) chatStore.newSession();
                SharedMemory.clear(this);
            clearChat();
            agentLog("👋 新对话开始，在下方输入目标。");
        });
        agentHeader.addView(newChatBtn);
        page.addView(agentHeader);

        // 模型选择行：聊天框内切换主模型配置中的其他模型
        LinearLayout agentModelRow = new LinearLayout(this);
        agentModelRow.setOrientation(LinearLayout.HORIZONTAL);
        agentModelRow.setGravity(Gravity.CENTER_VERTICAL);
        agentModelRow.setPadding(0, 0, 0, dp(4));
        TextView amLabel = grayText("模型：");
        amLabel.setTextSize(12);
        agentModelRow.addView(amLabel);
        agentModelSpinner = new android.widget.Spinner(this);
        LinearLayout.LayoutParams amLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        agentModelRow.addView(agentModelSpinner, amLp);
        page.addView(agentModelRow);
        refreshAgentModels();

        // 消息区在上，占满剩余空间
        agentScrollView = new ScrollView(this);
        agentMsgBox = new LinearLayout(this);
        agentMsgBox.setOrientation(LinearLayout.VERTICAL);
        agentMsgBox.setPadding(0, 0, 0, dp(8));
        agentScrollView.addView(agentMsgBox, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        page.addView(agentScrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));






        // 动作审批策略入口
        final TextView riskRow = new TextView(this);
        riskRow.setTextSize(12);
        riskRow.setPadding(dp(4), dp(4), dp(4), dp(8));
        riskRow.setClickable(true);
        riskRow.setFocusable(true);
        riskSummaryView = riskRow;
        Runnable refreshRiskRow = this::refreshRiskRow;
        riskRow.setOnClickListener(v -> showRiskDialog(refreshRiskRow));
        // v2.95：审批行+悬浮窗行装进可收纳容器
        final LinearLayout riskFloatBox = new LinearLayout(this);
        riskFloatBox.setOrientation(LinearLayout.VERTICAL);
        riskFloatBox.addView(riskRow);
        page.addView(riskFloatBox);
        refreshRiskRow.run();

        // 悬浮窗权限入口
        final TextView floatPermHint = new TextView(this);
        floatPermHint.setTextSize(12);
        floatPermHint.setPadding(dp(4), 0, dp(4), dp(8));
        floatPermHint.setClickable(true);
        floatPermHint.setFocusable(true);
        Runnable refreshFloatHint = () -> {
            boolean ok = FloatWindow.hasPermission(this);
            floatPermHint.setText(ok ? "🪟 悬浮窗进度：已开启"
                    : "🪟 悬浮窗进度：未开启（点我去开）");
            floatPermHint.setTextColor(ok ? DyColor.onSurfaceVariant(this)
                    : DyColor.primary(this));
        };
        refreshFloatHint.run();
        floatPermHint.setOnClickListener(v -> {
            if (FloatWindow.hasPermission(this)) return;
            showPermRationale("为什么需要悬浮窗权限",
                    "悬浮窗只用来在后台显示任务进度（思考💭/执行🖐实时流式），\n"
                            + "切回 App 内会自动隐藏，不展示其他内容。\n\n"
                            + "可随时在系统设置中关闭。",
                    "去开启", () -> FloatWindow.requestPermission(this));
        });
        riskFloatBox.addView(floatPermHint);
        // v2.97：流式输出开关
        final TextView streamRow = new TextView(this);
        streamRow.setTextSize(12);
        streamRow.setPadding(dp(4), 0, dp(4), dp(8));
        streamRow.setClickable(true);
        streamRow.setFocusable(true);
        final boolean[] streamOn = {getSharedPreferences("agent", MODE_PRIVATE)
                .getBoolean("stream_output", true)};
        Runnable refreshStream = () -> {
            streamRow.setText(streamOn[0] ? "🌊 流式输出：已开启（点我关闭）"
                    : "🌊 流式输出：已关闭（点我开启）");
            streamRow.setTextColor(streamOn[0] ? DyColor.onSurfaceVariant(this)
                    : DyColor.primary(this));
        };
        refreshStream.run();
        streamRow.setOnClickListener(v -> {
            streamOn[0] = !streamOn[0];
            getSharedPreferences("agent", MODE_PRIVATE).edit()
                    .putBoolean("stream_output", streamOn[0]).apply();
            refreshStream.run();
            log(streamOn[0] ? "🌊 流式输出已开启" : "🌊 流式输出已关闭");
        });
        riskFloatBox.addView(streamRow);
        // v2.96：收纳按钮放进输入栏（下面加），和发送对称
        // v2.97：改图标（功能多，不叫审批了）
        final Button riskToggleBtn = new Button(this);
        riskToggleBtn.setText("⚙️");
        riskToggleBtn.setTextSize(16);
        riskToggleBtn.setTextColor(android.graphics.Color.WHITE);
        riskToggleBtn.setBackground(bubbleBg(DyColor.primary(this), 20));
        riskToggleBtn.setPadding(dp(12), dp(10), dp(12), dp(10));
        final boolean[] riskExpanded = {true};
        riskToggleBtn.setOnClickListener(v -> {
            riskExpanded[0] = !riskExpanded[0];
            riskFloatBox.setVisibility(riskExpanded[0] ? View.VISIBLE : View.GONE);
        });
        // 每次进智能体页刷新状态（onResume 里调）
        agentFloatHintRefresher = refreshFloatHint;

        // 输入栏在下（Material 圆角）
        LinearLayout inputBar = new LinearLayout(this);
        inputBar.setOrientation(LinearLayout.HORIZONTAL);
        inputBar.setGravity(Gravity.CENTER_VERTICAL);
        inputBar.setBackground(bubbleBg(DyColor.surfaceVariant(this), 24));
        agentInputBar = inputBar;
        agentInputBg = inputBar.getBackground();
        inputBar.setPadding(dp(6), dp(6), dp(6), dp(6));
        agentGoal = new EditText(this);
        agentGoal.setHint("输入目标，AI 照做");
        agentGoal.setTextSize(14);
        agentGoal.setSingleLine(true);
        agentGoal.setBackground(null);
        agentGoal.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        agentGoal.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                startAgent();
                return true;
            }
            return false;
        });
        // v2.83：删掉焦点监听，只认键盘显隐（防打架）
        LinearLayout.LayoutParams goalLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        goalLp.setMargins(dp(8), 0, dp(4), 0);
        // v2.96：收纳按钮放输入栏最左，和发送对称
        inputBar.addView(riskToggleBtn, 0);
        inputBar.addView(agentGoal, goalLp);
        agentSendBtn = new Button(this);
        agentSendBtn.setText("发送");
        agentSendBtn.setTextSize(14);
        agentSendBtn.setTextColor(DyColor.onPrimary(this));
        agentSendBtn.setBackground(bubbleBg(DyColor.primary(this), 20));
        agentSendBtn.setPadding(dp(20), dp(10), dp(20), dp(10));
        agentSendBtn.setOnClickListener(v -> {
            // M3E 触感反馈：主操作给一记轻触感
            v.performHapticFeedback(
                    android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            if (agentThread != null && agentThread.isAlive()) {
                agentStop = true;
                agentLog("🛑 已请求停止");
            } else {
                startAgent();
            }
        });
        inputBar.addView(agentSendBtn);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        // v2.94：输入框底部留空，避开悬浮 tab（消息可滑到 tab 下，输入框不行）
        barLp.setMargins(0, dp(8), 0, dp(84));
        page.addView(inputBar, barLp);

        // 恢复历史对话
        loadingHistory = true;
        try {
            java.util.List<ChatStore.Msg> msgs = chatStore.currentMsgs();
            if (msgs.isEmpty()) {
                agentLog("👋 你好，我是思考执行员。输入目标后，我会先拆解规划再执行。");
            } else {
                for (ChatStore.Msg m : msgs) {
                    if ("user".equals(m.role)) addChatBubble(m.text, true, m.time);
                    else if ("agent".equals(m.role)) addChatBubble(m.text, false, m.time);
                    else addChatSystem(m.text, m.time);
                }
                // v2.86：恢复历史后滚到底
                if (agentScrollView != null) {
                    agentScrollView.post(() -> agentScrollView.fullScroll(
                            android.view.View.FOCUS_DOWN));
                }
            }
        } finally {
            loadingHistory = false;
        }
        return page;
    }

    /** 无障碍权限：先讲清用途再跳系统设置 */
    private void showAccessRationale() {
        showPermRationale("为什么需要无障碍权限",
                "思考执行员需要它来：\n"
                        + "· 读取屏幕上的按钮和文字（眼睛）\n"
                        + "· 帮你点击、滑动、输入（手）\n"
                        + "· 免 root 截图（Android 11+ 系统截图接口）\n\n"
                        + "只在手机本地使用，不收集、不上传任何内容；\n"
                        + "可随时在系统设置中关闭。",
                "去开启",
                () -> startActivity(new Intent(
                        Settings.ACTION_ACCESSIBILITY_SETTINGS)));
    }

    /** 通用权限说明对话框：讲清原因，按需申请，可撤销 */
    private void showPermRationale(String title, String msg,
                                   String okLabel, Runnable go) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(okLabel, (d, w) -> go.run())
                .setNegativeButton("取消", null)
                .show();
    }

    private LinearLayout buildAskCard() {
        LinearLayout askCard = card();
        askCard.addView(cardTitle("问答测试"));
        askCard.addView(grayText("走上面配置的大模型，验证连通与回答："));
        // 模型选择：当前服务商的可选模型
        LinearLayout modelRow = new LinearLayout(this);
        modelRow.setOrientation(LinearLayout.HORIZONTAL);
        modelRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView modelLabel = grayText("模型：");
        modelRow.addView(modelLabel);
        askModelSpinner = new android.widget.Spinner(this);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        modelRow.addView(askModelSpinner, spLp);
        askCard.addView(modelRow);
        refreshAskModels();
        LinearLayout askRow = new LinearLayout(this);
        askRow.setOrientation(LinearLayout.HORIZONTAL);
        askRow.setGravity(Gravity.CENTER_VERTICAL);
        llmAsk = new EditText(this);
        llmAsk.setHint("输入一个问题");
        llmAsk.setTextSize(13);
        llmAsk.setSingleLine(true);
        LinearLayout.LayoutParams askLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        askRow.addView(llmAsk, askLp);
        llmReply = new TextView(this);
        llmReply.setTextSize(13);
        llmReply.setTextColor(TEXT_DARK);
        llmReply.setLineSpacing(dp(3), 1f);
        llmReply.setTextIsSelectable(true);
        llmReply.setHint("回答会显示在这里，长按可复制");
        ScrollView replyScroll = new ScrollView(this);
        replyScroll.addView(llmReply, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        Button bAsk = smallBtn("问");
        bAsk.setOnClickListener(v -> {
            String q = llmAsk.getText().toString().trim();
            if (q.isEmpty()) {
                log("先输入问题");
                return;
            }
            llmAsk.setText("");
            llmReply.setText("思考中…");
            final String useModel = askModelOverride.isEmpty()
                    ? llmStore.getModel() : askModelOverride;
            new Thread(() -> {
                LlmClient c = llmStore.client();
                // 用选中的模型覆盖
                LlmClient.Result r = new LlmClient(
                        llmStore.getBaseUrl(), llmStore.getApiKeyRaw(),
                        useModel).chat(q);
                runOnUiThread(() -> {
                    llmReply.setText(r.ok ? r.text : "失败：" + r.text);
                    replyScroll.post(() ->
                            replyScroll.fullScroll(android.view.View.FOCUS_UP));
                });
            }).start();
        });
        askRow.addView(bAsk);
        askCard.addView(askRow);
        LinearLayout.LayoutParams replyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(170));
        replyLp.setMargins(0, dp(4), 0, 0);
        askCard.addView(replyScroll, replyLp);
        return askCard;
    }

    /** 刷新智能体聊天框的模型下拉 */
    private void refreshAgentModels() {
        if (agentModelSpinner == null) return;
        String[] models = llmStore.getProviderModels();
        String cur = llmStore.getModel();
        String provider = llmStore.getProvider();
        java.util.List<String> items = new java.util.ArrayList<>();
        if (cur != null && !cur.isEmpty())
            items.add(cur + "（当前·" + provider + "）");
        for (String m : models) {
            if (!m.equals(cur)) items.add(m);
        }
        if (items.isEmpty()) items.add("（无可选模型）");
        android.widget.ArrayAdapter<String> ad =
                new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        agentModelSpinner.setAdapter(ad);
        agentModelSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                    android.widget.AdapterView<?> parent,
                    android.view.View view, int pos, long id) {
                String sel = items.get(pos);
                if (sel.endsWith("）") || sel.startsWith("（")) {
                    agentModelOverride = "";
                } else {
                    agentModelOverride = sel;
                }
                behaviorLog.user("智能体切换模型", sel);
            }
            @Override
            public void onNothingSelected(
                    android.widget.AdapterView<?> parent) {
            }
        });
    }

    /** 智能体用的 LLM 客户端（含聊天框选中的模型覆盖） */
    private LlmClient agentClient() {
        if (agentModelOverride == null || agentModelOverride.isEmpty()) {
            return llmStore.client();
        }
        return new LlmClient(llmStore.getBaseUrl(),
                llmStore.getApiKeyRaw(), agentModelOverride);
    }

    /** 刷新问答测试的模型下拉：当前服务商的可选模型 */
    private void refreshAskModels() {
        if (askModelSpinner == null) return;
        String[] models = llmStore.getProviderModels();
        String cur = llmStore.getModel();
        java.util.List<String> items = new java.util.ArrayList<>();
        // 当前配置的模型放第一位
        if (cur != null && !cur.isEmpty()) items.add(cur + "（当前）");
        for (String m : models) {
            if (!m.equals(cur)) items.add(m);
        }
        if (items.isEmpty()) items.add("（服务商无预设模型，用配置里的）");
        android.widget.ArrayAdapter<String> ad =
                new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        askModelSpinner.setAdapter(ad);
        askModelSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                    android.widget.AdapterView<?> parent,
                    android.view.View view, int pos, long id) {
                String sel = items.get(pos);
                // 去掉"（当前）"后缀
                if (sel.endsWith("（当前）")) {
                    askModelOverride = "";
                } else if (sel.startsWith("（")) {
                    askModelOverride = "";
                } else {
                    askModelOverride = sel;
                }
            }
            @Override
            public void onNothingSelected(
                    android.widget.AdapterView<?> parent) {
            }
        });
    }

    private LinearLayout buildSkillCard() {
        LinearLayout skillCard = card();
        LinearLayout importRow = new LinearLayout(this);
        importRow.setOrientation(LinearLayout.HORIZONTAL);
        importRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        Button bPaste = smallBtn("📥 粘贴导入");
        bPaste.setOnClickListener(v -> pasteImportSkillCard());
        importRow.addView(bPaste);
        Button bImport = smallBtn("导入");
        bImport.setOnClickListener(v -> {
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            it.setType("*/*");
            it.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(it, REQ_IMPORT);
        });
        importRow.addView(bImport);
        LinearLayout skillHeader = new LinearLayout(this);
        skillHeader.setOrientation(LinearLayout.HORIZONTAL);
        skillHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView skillTitle = new TextView(this);
        skillTitle.setText("技能");
        skillTitle.setTextSize(18);
        skillTitle.setTypeface(skillTitle.getTypeface(),
                android.graphics.Typeface.BOLD);
        skillTitle.setTextColor(DyColor.onSurface(this));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        skillHeader.addView(skillTitle, hlp);
        skillHeader.addView(importRow);
        skillCard.addView(skillHeader);
        skillsBox = new LinearLayout(this);
        skillsBox.setOrientation(LinearLayout.VERTICAL);
        skillCard.addView(skillsBox);
        // 技能卡区域
        LinearLayout cardSec = card();
        cardSec.addView(cardTitle("📇 技能卡"));
        cardSec.addView(grayText("任务完成后自动生成总结，下次直接调用，免重复思考。用户创建的优先。"));
        skillCardsBox = new LinearLayout(this);
        skillCardsBox.setOrientation(LinearLayout.VERTICAL);
        cardSec.addView(skillCardsBox);
        // 把技能卡区域挂到 skill 页面（buildSkillCard 返回的是 skillCard，加到外面）
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(skillCard);
        wrap.addView(cardSec);
        return wrap;
    }

    /** 从剪贴板粘贴 AI 生成的技能卡 JSON 并导入 */
    private void pasteImportSkillCard() {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
            if (cm.getPrimaryClip() == null
                    || cm.getPrimaryClip().getItemCount() == 0) {
                log("剪贴板是空的，先复制 AI 生成的 JSON");
                return;
            }
            String txt = cm.getPrimaryClip().getItemAt(0)
                    .coerceToText(this).toString().trim();
            importSkillCardJson(txt);
        } catch (Exception e) {
            log("导入失败：" + e.getMessage());
        }
    }

    /** v3.12 Issue 12：文件选择回调 */
    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            try {
                java.io.InputStream is = getContentResolver()
                        .openInputStream(data.getData());
                byte[] buf = new byte[65536];
                java.io.ByteArrayOutputStream bos =
                        new java.io.ByteArrayOutputStream();
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                is.close();
                String txt = new String(bos.toByteArray(),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                importSkillCardJson(txt);
            } catch (Exception e) {
                log("读取文件失败：" + e.getMessage());
            }
        }
    }

    /** v3.12 Issue 12：导入技能卡 JSON（剪贴板/文件共用）：校验+风险提示 */
    private void importSkillCardJson(String txt) {
        try {
            org.json.JSONObject o = AgentLoop.extractJson(txt);
            if (o == null) {
                log("剪贴板里没找到合法 JSON");
                return;
            }
            // v3.12 Issue 12：结构校验 + 风险扫描
            String err = SkillCardStore.validateImport(o);
            if (err != null) {
                log("导入校验失败：" + err);
                return;
            }
            String type = o.optString("type", "skill");
            String content = o.optString("content", "");
            // 兼容老格式：顶层 steps 数组
            if (content.isEmpty() && o.optJSONArray("steps") != null) {
                content = new org.json.JSONObject()
                        .put("steps", o.optJSONArray("steps")).toString();
                o.put("content", content);
            }
            String riskWarn = SkillCardStore.scanRisk(type, content);
            final org.json.JSONObject fo = o;
            Runnable doImport = () -> {
                String id = new SkillCardStore(this).importValidated(fo);
                refreshSkillCards();
                log("已导入技能卡：" + fo.optString("name", "")
                        + (id != null ? "" : "（失败）"));
            };
            if (!riskWarn.isEmpty()) {
                new android.app.AlertDialog.Builder(this)
                        .setTitle("⚠️ 风险提示")
                        .setMessage("该技能卡" + riskWarn + "\n确定导入吗？")
                        .setPositiveButton("导入", (d, w) -> doImport.run())
                        .setNegativeButton("取消", null)
                        .show();
            } else {
                doImport.run();
            }
        } catch (Exception e) {
            log("导入失败：" + e.getMessage());
        }
    }

    private LinearLayout buildSchedCard() {
        LinearLayout schedCard = card();
        Button bAdd = smallBtn("添加");
        bAdd.setOnClickListener(v -> addScheduleDialog());
        schedCard.addView(headerRow("定时任务", bAdd));
        TextView schedHint = grayText("需远程控制服务保持运行");
        schedCard.addView(schedHint);
        schedBox = new LinearLayout(this);
        schedBox.setOrientation(LinearLayout.VERTICAL);
        schedCard.addView(schedBox);
        return schedCard;
    }

    private LinearLayout buildHistCard() {
        LinearLayout histCard = card();
        LinearLayout histHeader = new LinearLayout(this);
        histHeader.setOrientation(LinearLayout.HORIZONTAL);
        histHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView ht = cardTitle("运行记录");
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        histHeader.addView(ht, hlp);
        Button bExport = smallBtn("📤 导出");
        bExport.setOnClickListener(v -> exportLogs());
        histHeader.addView(bExport);
        histCard.addView(histHeader);
        histBox = new LinearLayout(this);
        histBox.setOrientation(LinearLayout.VERTICAL);
        histCard.addView(histBox);
        return histCard;
    }

    /** 导出运行日志：历史记录导出为 txt，可分享 */
    private void exportLogs() {
        new Thread(() -> {
            try {
                java.util.List<HistoryStore.Entry> list = historyStore.list();
                StringBuilder sb = new StringBuilder();
                sb.append("PhoneAgent 运行日志\n");
                sb.append("导出时间: ").append(new java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        java.util.Locale.getDefault()).format(
                        new java.util.Date())).append("\n");
                sb.append("共 ").append(list.size()).append(" 条记录\n");
                sb.append("================================\n\n");
                for (HistoryStore.Entry e : list) {
                    sb.append("【").append(e.type).append("】")
                            .append(e.title).append("\n");
                    if (e.timeStr != null && !e.timeStr.isEmpty()) {
                        sb.append("时间: ").append(e.timeStr).append("\n");
                    }
                    sb.append("结果: ").append(e.ok ? "成功" : "失败")
                            .append("\n");
                    if (e.detail != null && !e.detail.isEmpty()) {
                        sb.append(e.detail).append("\n");
                    }
                    sb.append("--------------------------------\n");
                }
                String fname = "gsic_log_"
                        + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                        java.util.Locale.getDefault()).format(
                        new java.util.Date()) + ".txt";
                java.io.File out = new java.io.File(
                        getExternalFilesDir(null), fname);
                java.nio.file.Files.write(out.toPath(),
                        sb.toString().getBytes("UTF-8"));
                runOnUiThread(() -> {
                    android.content.Intent share =
                            new android.content.Intent(
                            android.content.Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(android.content.Intent.EXTRA_STREAM,
                            CaptureProvider.uriFor(out));
                    share.addFlags(
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(android.content.Intent.createChooser(
                            share, "导出运行日志"));
                    log("日志已导出: " + fname);
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                        log("导出失败: " + e.getMessage()));
            }
        }).start();
    }

    private void refreshSkillCards() {
        if (skillCardsBox == null) return;
        skillCardsBox.removeAllViews();
        java.util.List<SkillCardStore.Card> list =
                new SkillCardStore(this).listByPriority();
        if (list.isEmpty()) {
            skillCardsBox.addView(grayText("暂无技能卡，完成任务后会自动生成"));
            return;
        }
        // 两类卡分区展示（用户 2026-10-22 亲定分开存储）：步骤型
        // （确定性回放）在前，说明型（智能体读说明执行）在后
        boolean hasSteps = false, hasGuide = false;
        for (SkillCardStore.Card c : list) {
            if (c.isProse()) hasGuide = true; else hasSteps = true;
        }
        if (hasSteps) {
            skillCardsBox.addView(grayText("— 步骤型 · 确定性回放 —"));
            for (SkillCardStore.Card c : list) {
                if (!c.isProse()) addSkillCardRow(c);
            }
        }
        if (hasGuide) {
            skillCardsBox.addView(grayText("— 说明型 · 智能体读说明执行 —"));
            for (SkillCardStore.Card c : list) {
                if (c.isProse()) addSkillCardRow(c);
            }
        }
    }

    private void addSkillCardRow(SkillCardStore.Card c) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            TextView t = new TextView(this);
            t.setText("📇 " + c.name + "（" + c.typeLabel() + "·"
                    + c.sourceLabel() + "·用过" + c.useCount + "次）"
                    + (c.isProse() ? "·说明型，点运行由智能体执行" : ""));
            t.setTextSize(14);
            t.setTextColor(DyColor.onSurface(this));
            row.addView(t);
            if (!c.summary.isEmpty()) {
                TextView ds = new TextView(this);
                ds.setText(c.summary);
                ds.setTextSize(12);
                ds.setTextColor(DyColor.onSurfaceVariant(this));
                row.addView(ds);
            }
            if (!c.trigger.isEmpty()) {
                TextView tg = new TextView(this);
                tg.setText("触发：" + c.trigger);
                tg.setTextSize(12);
                tg.setTextColor(DyColor.onSurfaceVariant(this));
                row.addView(tg);
            }
            LinearLayout btnRow = new LinearLayout(this);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            Button bRun = smallBtn("运行");
            final String cid = c.id;
            bRun.setOnClickListener(v -> runSkillCard(cid));
            Button bDel = smallBtn("删除");
            bDel.setOnClickListener(v -> {
                new SkillCardStore(this).delete(cid);
                refreshSkillCards();
            });
            Button bExp = smallBtn("导出");
            bExp.setOnClickListener(v -> {
                String json = new SkillCardStore(this).exportJson(cid);
                if (json == null) {
                    log("导出失败");
                    return;
                }
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager)
                                getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText(
                        "技能卡", json));
                log("技能卡 JSON 已复制到剪贴板，可分享给他人导入");
            });
            Button bRb = smallBtn("\u56de\u6eda");
            bRb.setOnClickListener(v -> {
                SkillCardStore rbStore = new SkillCardStore(this);
                java.util.List<SkillCardStore.VersionSnap> hist = rbStore.versions(cid);
                if (hist.isEmpty()) {
                    log("该技能卡暂无版本快照可回滚");
                    return;
                }
                SkillCardStore.Card cur = rbStore.get(cid);
                final String[] items = new String[hist.size()];
                for (int i = 0; i < hist.size(); i++) {
                    SkillCardStore.VersionSnap sn = hist.get(i);
                    items[i] = "v" + sn.version + " · "
                            + (sn.note == null ? "" : sn.note);
                }
                new android.app.AlertDialog.Builder(this)
                        .setTitle("回滚技能卡（当前 v"
                                + (cur == null ? "?" : cur.version)
                                + "，回滚前自动快照当前版）")
                        .setItems(items, (d, which) -> {
                            SkillCardStore.VersionSnap sn = hist.get(which);
                            new android.app.AlertDialog.Builder(this)
                                    .setTitle("确认回滚到 v" + sn.version + "？")
                                    .setMessage("回滚后该卡打回草稿，需复验成功一次才转正。")
                                    .setPositiveButton("回滚", (d2, w2) -> {
                                        boolean ok = new SkillCardStore(this)
                                                .rollback(cid, sn.version);
                                        log(ok ? "📇 已回滚到 v" + sn.version
                                                        + "，打回草稿待复验"
                                                : "回滚失败");
                                        refreshSkillCards();
                                    })
                                    .setNegativeButton("取消", null)
                                    .show();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
            btnRow.addView(bRun);
            btnRow.addView(bRb);
            btnRow.addView(bExp);
            btnRow.addView(bDel);
            row.addView(btnRow);
            skillCardsBox.addView(row);
    }

    /** 手动运行技能卡 */
    private void runSkillCard(String cardId) {
        SkillCardStore.Card c = new SkillCardStore(this).get(cardId);
        if (c == null) return;
        // 说明型卡：TaskRunner 的 use_card 只把说明回给调用者、零动作，
        // 手动运行走它就是「没执行却报 ✓」的假成功（2026-10-22 用户亲测）。
        // 改转智能体任务：模型读卡说明后用自己的工具逐步执行（与 TG
        // 通道同一条路），技能中心切到智能体页让用户看得见过程。
        if (c.isProse()) {
            log("📇 说明型技能卡，转交智能体执行：" + c.name);
            final String g = "执行技能卡《" + c.name + "》（id=" + c.id
                    + "）：先用 use_card 读取本卡说明，再按说明用你的工具逐步完成";
            runOnUiThread(() -> {
                showPage(2);
                agentGoal.setText(g);
                startAgent();
            });
            return;
        }
        log("📇 手动运行技能卡：" + c.name);
        new Thread(() -> {
            TaskRunner runner = new TaskRunner(this, this::log);
            runner.setRiskPolicy(riskPolicy);
            runner.setApprover(MainActivity.this::askUserApprove);
            try {
                org.json.JSONObject step = new org.json.JSONObject();
                step.put("do", "use_card");
                step.put("id", cardId);
                boolean ok = runner.execOneStep(step.toString());
                log(ok ? "技能卡执行完成 ✓" : "技能卡执行失败 ✗");
            } catch (Exception e) {
                log("执行异常：" + e.getMessage());
            }
            runOnUiThread(this::refreshSkillCards);
        }).start();
    }

    private void refreshSkills() {
        skillsBox.removeAllViews();
        List<SkillStore.Skill> skills = skillStore.list();
        if (skills.isEmpty()) {
            skillsBox.addView(grayText("暂无技能，点右上「导入」添加"));
            return;
        }
        for (SkillStore.Skill s : skills) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            TextView name = new TextView(this);
            name.setText(s.name);
            name.setTextSize(15);
            name.setTextColor(TEXT_DARK);
            row.addView(name);
            if (!s.desc.isEmpty()) {
                TextView desc = grayText(s.desc);
                row.addView(desc);
            }

            LinearLayout ops = new LinearLayout(this);
            ops.setOrientation(LinearLayout.HORIZONTAL);
            ops.setGravity(Gravity.CENTER_VERTICAL);

            Switch sw = new Switch(this);
            sw.setText("启用");
            sw.setTextSize(13);
            sw.setChecked(s.enabled);
            sw.setOnCheckedChangeListener((v, on) -> skillStore.setEnabled(s, on));
            ops.addView(sw);

            Button bRun = smallBtn("运行");
            bRun.setOnClickListener(v -> runSkill(s));
            ops.addView(bRun);

            Button bDel = smallBtn("删除");
            bDel.setOnClickListener(v -> new AlertDialog.Builder(this)
                    .setMessage("删除技能「" + s.name + "」？")
                    .setPositiveButton("删除", (d, w) -> {
                        skillStore.delete(s);
                        refreshSkills();
                    })
                    .setNegativeButton("取消", null)
                    .show());
            ops.addView(bDel);

            row.addView(ops);
            skillsBox.addView(row);

            View div = new View(this);
            div.setBackgroundColor(0xFFEEEEEE);
            skillsBox.addView(div, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        }
    }

    private void runSkill(SkillStore.Skill s) {
        if (runThread != null && runThread.isAlive()) {
            log("有任务在运行中…");
            return;
        }
        if (!AgentAccessibilityService.isRunning() && Root.findSu() == null) {
            log("⚠️ 无障碍未开启且无 root，技能里需要看屏/点击的步骤可能失败");
        }
        runner = new TaskRunner(this, msg -> {
        });
        runner.setRiskPolicy(riskPolicy);
        runner.setApprover(MainActivity.this::askUserApprove);
        runThread = new Thread(() -> {
            boolean ok = runner.run(s.json);
            historyStore.append(s.name, ok);
            runOnUiThread(() -> {
                log(ok ? "完成 ✓" : "失败 ✗");
                refreshHistory();
            });
        });
        runThread.start();
        log("开始：" + s.name);
    }

    private void refreshHistory() {
        if (histBox == null) return;
        histBox.removeAllViews();
        List<HistoryStore.Entry> list = historyStore.list();
        if (list.isEmpty()) {
            histBox.addView(grayText("暂无运行记录"));
            return;
        }
        for (HistoryStore.Entry e : list) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            TextView t = new TextView(this);
            String icon = "task".equals(e.type) ? "🤖"
                    : "card".equals(e.type) ? "📇" : "⚙️";
            String title = e.title.isEmpty() ? "(空)" : e.title;
            if (title.length() > 24) title = title.substring(0, 24) + "…";
            boolean running = "running".equals(e.status);
            String mark = running ? "🟢 " : (e.ok ? "✓ " : "✗ ");
            t.setText(icon + " " + mark + title);
            t.setTextSize(14);
            t.setTextColor(running ? DyColor.primary(this)
                    : e.ok ? DyColor.onSurface(this) : RED);
            row.addView(t);
            TextView d = new TextView(this);
            StringBuilder dsb = new StringBuilder(e.timeStr);
            if (!e.detail.isEmpty()) dsb.append(" · ").append(e.detail);
            d.setText(dsb.toString());
            d.setTextSize(12);
            d.setTextColor(DyColor.onSurfaceVariant(this));
            row.addView(d);
            // 有审计明细的行可点开展示每步动作
            if (e.stepsJson != null && e.stepsJson.length() > 2) {
                final HistoryStore.Entry fe = e;
                row.setClickable(true);
                row.setFocusable(true);
                TextView more = new TextView(this);
                more.setText("📋 查看每步明细");
                more.setTextSize(12);
                more.setTextColor(DyColor.primary(this));
                row.addView(more);
                row.setOnClickListener(v -> showAuditDialog(fe));
            }
            histBox.addView(row);
        }
    }

    /** 审计明细对话框：展示某次任务的每步动作 */
    private void showAuditDialog(HistoryStore.Entry e) {
        StringBuilder sb = new StringBuilder();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(e.stepsJson);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                sb.append("第").append(o.optInt("round", 1)).append("轮·步骤")
                        .append(o.optInt("n", 0)).append("：")
                        .append(o.optString("do", "?")).append(" ")
                        .append(o.optBoolean("ok", false) ? "✓" : "✗")
                        .append(" ").append(o.optString("risk", "")).append("\n");
                String out = o.optString("out", "");
                if (!out.isEmpty()) sb.append("   ↳ ").append(out).append("\n");
            }
        } catch (Exception ex) {
            sb.append("明细解析失败");
        }
        if (sb.length() == 0) sb.append("（无明细）");
        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(12);
        tv.setTextColor(DyColor.onSurface(this));
        tv.setPadding(dp(8), dp(8), dp(8), dp(8));
        tv.setTextIsSelectable(true);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        new android.app.AlertDialog.Builder(this)
                .setTitle("📋 每步动作明细")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void refreshSchedules() {
        schedBox.removeAllViews();
        List<ScheduleStore.Schedule> list = scheduleStore.list();
        if (list.isEmpty()) {
            schedBox.addView(grayText("暂无定时任务"));
            return;
        }
        for (ScheduleStore.Schedule sc : list) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            TextView t = new TextView(this);
            t.setText(scheduleStore.describe(sc));
            t.setTextSize(14);
            t.setTextColor(TEXT_DARK);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(t, lp);
            Switch sw = new Switch(this);
            sw.setChecked(sc.enabled);
            sw.setOnCheckedChangeListener((v, on) -> scheduleStore.setEnabled(sc.id, on));
            row.addView(sw);
            Button bDel = smallBtn("删");
            bDel.setOnClickListener(v -> {
                scheduleStore.remove(sc.id);
                refreshSchedules();
            });
            row.addView(bDel);
            schedBox.addView(row);
        }
    }

    private void addScheduleDialog() {
        List<SkillStore.Skill> skills = skillStore.list();
        if (skills.isEmpty()) {
            log("先导入技能");
            return;
        }
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(8), dp(8), dp(8), dp(8));

        List<String> names = new ArrayList<>();
        for (SkillStore.Skill s : skills) names.add(s.name);
        Spinner spSkill = new Spinner(this);
        ArrayAdapter<String> ad1 = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, names);
        ad1.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spSkill.setAdapter(ad1);
        form.addView(spSkill);

        Spinner spType = new Spinner(this);
        ArrayAdapter<String> ad2 = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"每天", "每间隔", "每周几", "一次性"});
        ad2.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spType.setAdapter(ad2);
        form.addView(spType);

        EditText et = new EditText(this);
        et.setHint("每天填 08:30；间隔填分钟数如 60");
        form.addView(et);

        // v3.12 Issue 13：周几多选（仅"每周几"类型用）
        LinearLayout weekRow = new LinearLayout(this);
        weekRow.setOrientation(LinearLayout.HORIZONTAL);
        String[] weekNames = {"日", "一", "二", "三", "四", "五", "六"};
        android.widget.CheckBox[] weekBoxes = new android.widget.CheckBox[7];
        for (int i = 0; i < 7; i++) {
            android.widget.CheckBox cb = new android.widget.CheckBox(this);
            cb.setText(weekNames[i]);
            cb.setTextSize(12);
            // 默认勾选工作日
            int dow = i + 1; // 1=周日
            if (dow >= 2 && dow <= 6) cb.setChecked(true);
            weekBoxes[i] = cb;
            weekRow.addView(cb);
        }
        weekRow.setVisibility(android.view.View.GONE);
        form.addView(weekRow);
        spType.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> a,
                            android.view.View v, int pos, long id) {
                        weekRow.setVisibility(pos == 2
                                ? android.view.View.VISIBLE
                                : android.view.View.GONE);
                        if (pos == 0) et.setHint("填 08:30");
                        else if (pos == 1) et.setHint("填分钟数如 60");
                        else if (pos == 2) et.setHint("填 08:30（勾选周几）");
                        else et.setHint("填 MM-dd HH:mm，如 10-20 08:30");
                    }
                    @Override
                    public void onNothingSelected(
                            android.widget.AdapterView<?> a) {}
                });

        new AlertDialog.Builder(this)
                .setTitle("添加定时任务")
                .setView(form)
                .setPositiveButton("确定", (d, w) -> {
                    try {
                        ScheduleStore.Schedule sc = new ScheduleStore.Schedule();
                        sc.skillId = skills.get(spSkill.getSelectedItemPosition()).id;
                        sc.skillName = skills.get(spSkill.getSelectedItemPosition()).name;
                        String input = et.getText().toString().trim();
                        int typePos = spType.getSelectedItemPosition();
                        if (typePos == 0) {
                            String[] hm = input.split(":");
                            sc.type = "daily";
                            sc.hour = Integer.parseInt(hm[0].trim());
                            sc.minute = Integer.parseInt(hm[1].trim());
                            if (sc.hour < 0 || sc.hour > 23 || sc.minute < 0 || sc.minute > 59)
                                throw new IllegalArgumentException();
                        } else if (typePos == 1) {
                            sc.type = "interval";
                            sc.everyMin = Integer.parseInt(input);
                            if (sc.everyMin <= 0) throw new IllegalArgumentException();
                        } else if (typePos == 2) {
                            // v3.12 Issue 13：每周几
                            String[] hm = input.split(":");
                            sc.type = "weekly";
                            sc.hour = Integer.parseInt(hm[0].trim());
                            sc.minute = Integer.parseInt(hm[1].trim());
                            if (sc.hour < 0 || sc.hour > 23 || sc.minute < 0 || sc.minute > 59)
                                throw new IllegalArgumentException();
                            StringBuilder wb = new StringBuilder();
                            for (int i = 0; i < 7; i++) {
                                if (weekBoxes[i].isChecked()) {
                                    if (wb.length() > 0) wb.append(",");
                                    wb.append(i + 1);
                                }
                            }
                            if (wb.length() == 0) throw new IllegalArgumentException();
                            sc.weekdays = wb.toString();
                        } else {
                            // v3.12 Issue 13：一次性 MM-dd HH:mm
                            java.text.SimpleDateFormat f =
                                    new java.text.SimpleDateFormat("MM-dd HH:mm",
                                            java.util.Locale.getDefault());
                            java.util.Date dd = f.parse(input);
                            java.util.Calendar c =
                                    java.util.Calendar.getInstance();
                            c.setTime(dd);
                            c.set(java.util.Calendar.YEAR,
                                    java.util.Calendar.getInstance()
                                            .get(java.util.Calendar.YEAR));
                            // 若时间已过，顺延到明年
                            if (c.getTimeInMillis() <= System.currentTimeMillis()) {
                                c.add(java.util.Calendar.YEAR, 1);
                            }
                            sc.type = "once";
                            sc.onceAt = c.getTimeInMillis();
                        }
                        scheduleStore.add(sc);
                        refreshSchedules();
                        log("已添加");
                    } catch (Exception e) {
                        log("格式不对，重填");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
    // ---------- 远控页（一级菜单 → 二级详情） ----------

    private android.widget.ScrollView remoteMenuScroll;
    private LinearLayout remoteDetailWrap;
    private java.util.Map<String, LinearLayout> remoteSections;

    private LinearLayout buildRemotePage() {
        remoteSections = new java.util.HashMap<>();
        remoteSections.put("svc", buildSvcCard());
        remoteSections.put("muse", buildMuseCard());
        remoteSections.put("tg", buildTgCard());
        remoteSections.put("api", buildApiCard());
        remoteSections.put("task", buildRemoteTaskCard());

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(0, dp(8), 0, dp(100)); // v3.20：底部避让悬浮胶囊tab
        LinearLayout remoteList = menuCard();
        remoteSvcSub = addMenuRow(remoteList, "🌐", "远程控制服务",
                "HTTP 服务开关（端口 " + AgentService.getPort(this) + "）",
                v -> showRemoteSection("svc"));
        addMenuRow(remoteList, "☁️", "云端操控", "Tailscale 内网被控",
                v -> showRemoteSection("muse"));
        addMenuRow(remoteList, "✈️", "Telegram 机器人", "Bot 公网控制",
                v -> showRemoteSection("tg"));
        addMenuRow(remoteList, "🔌", "API 接口", "接口文档",
                v -> showRemoteSection("api"));
        addMenuRow(remoteList, "🎯", "远控任务", "目标式任务实时进度",
                v -> showRemoteSection("task"));
        menu.addView(remoteList);

        remoteMenuScroll = new ScrollView(this);
        remoteMenuScroll.addView(menu);
        remoteDetailWrap = new LinearLayout(this);
        remoteDetailWrap.setOrientation(LinearLayout.VERTICAL);
        remoteDetailWrap.setVisibility(View.GONE);

        outer.addView(remoteMenuScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        outer.addView(remoteDetailWrap, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return outer;
    }

    private void showRemoteSection(String key) {
        LinearLayout sec = remoteSections.get(key);
        if (sec == null) return;
        if (sec.getParent() != null) {
            ((android.view.ViewGroup) sec.getParent()).removeView(sec);
        }
        remoteDetailWrap.removeAllViews();
        Button back = smallBtn("← 返回");
        back.setOnClickListener(v -> showRemoteMenu());
        remoteDetailWrap.addView(back);
        ScrollView sv = new ScrollView(this);
        sv.setClipToPadding(false);
        sv.setPadding(0, 0, 0, dp(100)); // v3.20：底部避让悬浮胶囊tab
        sv.addView(sec);
        remoteDetailWrap.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        remoteMenuScroll.setVisibility(View.GONE);
        remoteDetailWrap.setVisibility(View.VISIBLE);
        if ("task".equals(key)) startRemoteTaskRefresh();
        else stopRemoteTaskRefresh();
    }

    private void showRemoteMenu() {
        stopRemoteTaskRefresh();
        remoteDetailWrap.setVisibility(View.GONE);
        remoteMenuScroll.setVisibility(View.VISIBLE);
    }

    // ---------- 远控任务实时卡：目标式任务的思考过程 + 执行过程边跑边看 ----------
    private TextView remoteTaskGoal, remoteTaskStatus, remoteTaskLog;
    private final android.os.Handler remoteTaskHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable remoteTaskRefresher;
    private boolean remoteTaskRefreshing = false;

    private LinearLayout buildRemoteTaskCard() {
        LinearLayout c = card();
        c.addView(cardTitle("远控任务（实时）"));
        remoteTaskGoal = new TextView(this);
        remoteTaskGoal.setTextSize(14);
        remoteTaskGoal.setTextColor(TEXT_DARK);
        c.addView(remoteTaskGoal);
        remoteTaskStatus = new TextView(this);
        remoteTaskStatus.setTextSize(14);
        remoteTaskStatus.setTextColor(TEXT_DARK);
        remoteTaskStatus.setPadding(0, dp(6), 0, dp(6));
        c.addView(remoteTaskStatus);
        remoteTaskLog = new TextView(this);
        remoteTaskLog.setTextSize(12);
        remoteTaskLog.setTypeface(android.graphics.Typeface.MONOSPACE);
        remoteTaskLog.setTextColor(TEXT_DARK);
        c.addView(remoteTaskLog);
        return c;
    }

    private void startRemoteTaskRefresh() {
        stopRemoteTaskRefresh();
        remoteTaskRefreshing = true;
        refreshRemoteTaskCard();
        remoteTaskRefresher = new Runnable() {
            @Override
            public void run() {
                if (!remoteTaskRefreshing) return;
                refreshRemoteTaskCard();
                remoteTaskHandler.postDelayed(this, 1500);
            }
        };
        remoteTaskHandler.postDelayed(remoteTaskRefresher, 1500);
    }

    private void stopRemoteTaskRefresh() {
        remoteTaskRefreshing = false;
        if (remoteTaskRefresher != null) {
            remoteTaskHandler.removeCallbacks(remoteTaskRefresher);
        }
    }

    private void refreshRemoteTaskCard() {
        if (remoteTaskGoal == null) return;
        RemoteTask rt = ApiHandler.getLatestRemoteTask();
        if (rt == null) {
            remoteTaskGoal.setText("暂无远控任务");
            remoteTaskStatus.setText("用 POST /task {\"goal\":\"...\"} 下发目标式任务后，这里实时显示思考与执行过程");
            remoteTaskLog.setText("");
            return;
        }
        remoteTaskGoal.setText("🎯 " + rt.goal);
        StringBuilder st = new StringBuilder();
        if (rt.done) {
            st.append(rt.ok ? "✅ 已完成" : "⏹ 已结束");
            if (!rt.error.isEmpty()) st.append("：").append(rt.error);
        } else {
            st.append("▶ 进行中 · 第 ").append(rt.step())
                    .append(" 步 / ").append(rt.maxSteps)
                    .append("（已用 ").append(rt.elapsedSec()).append("s）");
        }
        if (!rt.think().isEmpty()) st.append("\n💭 ").append(rt.think());
        remoteTaskStatus.setText(st.toString());
        StringBuilder sb = new StringBuilder();
        for (String l : rt.tail(12)) sb.append(l).append("\n");
        remoteTaskLog.setText(sb.toString().trim());
    }

    private LinearLayout buildSvcCard() {
        LinearLayout svcCard = card();
        svcCard.addView(cardTitle("远程控制服务"));
        svcInfo = new TextView(this);
        svcInfo.setTextSize(14);
        svcInfo.setTextColor(TEXT_DARK);
        svcCard.addView(svcInfo);
        Button bStart = btn("启动服务");
        bStart.setOnClickListener(v -> {
            // v3.42：手动启动 → 清除停止标记，自启守护恢复工作
            BootReceiver.setUserStopped(this, false);
            Intent it = new Intent(this, AgentService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(it);
            } else {
                startService(it);
            }
            svcInfo.postDelayed(this::refreshSvc, 800);
        });
        svcCard.addView(bStart);
        Button bStop = btn("停止服务");
        bStop.setOnClickListener(v -> {
            // v3.42：手动停止 → 立标记，开机/更新/看门狗都不再自动拉起
            BootReceiver.setUserStopped(this, true);
            stopService(new Intent(this, AgentService.class));
            svcInfo.postDelayed(this::refreshSvc, 500);
        });
        svcCard.addView(bStop);
        Button bPort = btn("修改端口");
        bPort.setOnClickListener(v -> showPortDialog());
        svcCard.addView(bPort);
        // v3.11 Issue 6：厂商 ROM 保活引导
        Button bKeep = btn("🔋 保活设置引导");
        bKeep.setOnClickListener(v -> showKeepAliveGuide());
        svcCard.addView(bKeep);
        return svcCard;
    }

    /** v3.11 Issue 6：MIUI/ColorOS 等保活引导 */
    private void showKeepAliveGuide() {
        String manu = android.os.Build.MANUFACTURER.toLowerCase();
        StringBuilder sb = new StringBuilder();
        sb.append("本 App 需长期后台运行（远控/TG/定时任务），");
        sb.append("国产 ROM 会激进杀后台，请按下面设置：\n\n");
        if (manu.contains("xiaomi") || manu.contains("redmi")) {
            sb.append("【MIUI】\n")
              .append("1. 设置 → 应用设置 → 授权管理 → 自启动管理 → 允许本 App 自启动\n")
              .append("2. 设置 → 电池与性能 → 应用省电 → 本 App → 无限制\n")
              .append("3. 最近任务划卡时点 🔒 锁定本 App\n");
        } else if (manu.contains("oppo") || manu.contains("realme")
                || manu.contains("oneplus")) {
            sb.append("【ColorOS】\n")
              .append("1. 设置 → 电池 → 应用耗电管理 → 本 App → 允许后台活动\n")
              .append("2. 设置 → 应用管理 → 自启动管理 → 允许\n");
        } else if (manu.contains("vivo") || manu.contains("iqoo")) {
            sb.append("【OriginOS】\n")
              .append("1. 设置 → 电池 → 后台耗电管理 → 本 App → 允许\n")
              .append("2. i 管家 → 应用管理 → 自启动管理 → 允许\n");
        } else if (manu.contains("huawei") || manu.contains("honor")) {
            sb.append("【EMUI/HarmonyOS】\n")
              .append("1. 设置 → 电池 → 应用启动管理 → 本 App → 手动管理（全开）\n");
        } else {
            sb.append("1. 系统设置 → 电池/省电 → 找到本 App → 设为无限制\n")
              .append("2. 允许自启动\n");
        }
        sb.append("\n提示：本 App targetSdk 28 是为了兼容 Termux，");
        sb.append("系统提示专为旧版 Android 打造属正常现象。");
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(dp(16), dp(8), dp(16), dp(8));
        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(13);
        tv.setTextColor(TEXT_DARK);
        tv.setLineSpacing(dp(3), 1f);
        ll.addView(tv);
        Button bBatt = smallBtn("打开电池优化设置");
        bBatt.setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent(
                        android.provider.Settings
                                .ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e) {
                log("无法打开设置");
            }
        });
        ll.addView(bBatt);
        new android.app.AlertDialog.Builder(this)
                .setTitle("🔋 后台保活设置")
                .setView(ll)
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 修改远控端口：1024–65535；服务运行时自动重启生效 */
    private void showPortDialog() {
        final EditText et = new EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(AgentService.getPort(this)));
        et.setSelection(et.getText().length());
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(20), dp(12), dp(20), dp(4));
        wrap.addView(et, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("修改远程控制端口")
                .setMessage("默认 " + AgentService.DEFAULT_PORT
                        + "，范围 1024–65535。保存后服务会自动重启生效。")
                .setView(wrap)
                .setPositiveButton("保存", (d, w) -> {
                    int p;
                    try {
                        p = Integer.parseInt(et.getText().toString().trim());
                    } catch (Exception e) {
                        p = -1;
                    }
                    if (p < 1024 || p > 65535) {
                        Toast.makeText(this, "端口范围 1024–65535",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    boolean wasRunning = AgentService.isRunning();
                    AgentService.setPort(this, p);
                    if (wasRunning) {
                        // 重启服务让新端口生效
                        stopService(new Intent(this, AgentService.class));
                        Intent it = new Intent(this, AgentService.class);
                        if (android.os.Build.VERSION.SDK_INT >= 26) {
                            startForegroundService(it);
                        } else {
                            startService(it);
                        }
                    }
                    svcInfo.postDelayed(this::refreshSvc, 800);
                    log("远控端口已改为 " + p
                            + (wasRunning ? "，服务重启中…" : "，下次启动服务生效"));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private LinearLayout buildMuseCard() {
        LinearLayout museCard = card();
        museCard.addView(cardTitle("云端操控（被 Muse 控制）"));
        museTsStatus = new TextView(this);
        museTsStatus.setTextSize(13);
        museTsStatus.setTextColor(TEXT_GRAY);
        museCard.addView(museTsStatus);
        final TextView helpView = new TextView(this);
        helpView.setTextSize(13);
        helpView.setTextColor(TEXT_DARK);
        helpView.setLineSpacing(dp(3), 1f);
        helpView.setText("未检测到 Tailscale，按步骤排查：\n"
                + "1. 安装 Tailscale App（下方按钮）\n"
                + "2. 打开 Tailscale 并登录，确认显示 Connected（开关已打开）\n"
                + "3. 回到本页点「重新检测」\n"
                + "注意：需与云端 Muse 登录同一账号（同一 tailnet）。");
        museCard.addView(helpView);
        museHelp = helpView;
        LinearLayout museBtns = new LinearLayout(this);
        museBtns.setOrientation(LinearLayout.HORIZONTAL);
        Button bTsDetect = smallBtn("重新检测");
        bTsDetect.setOnClickListener(v -> {
            refreshSvc();
            log("已重新检测");
        });
        final Button appBtn = smallBtn("安装 Tailscale");
        appBtn.setOnClickListener(v -> {
            try {
                if (isTailscaleAppInstalled()) {
                    startActivity(getPackageManager()
                            .getLaunchIntentForPackage("com.tailscale.ipn"));
                } else {
                    startActivity(new android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(
                                    "https://play.google.com/store/apps/details?id=com.tailscale.ipn")));
                }
            } catch (Exception e) {
                log("无法打开");
            }
        });
        museTsAppBtn = appBtn;
        museBtns.addView(bTsDetect);
        museBtns.addView(appBtn);
        museCard.addView(museBtns);
        TextView museSteps = new TextView(this);
        museSteps.setTextSize(13);
        museSteps.setTextColor(TEXT_DARK);
        museSteps.setLineSpacing(dp(3), 1f);
        museSteps.setText("1. 手机安装 Tailscale 并登录你的账号\n"
                + "2. 点下方「复制连接信息」，发给云端 Muse\n"
                + "3. Muse 加入你的 tailnet 后，即可调用接口操控手机");
        museCard.addView(museSteps);
        Button bCopy = btn("复制连接信息");
        bCopy.setOnClickListener(v -> {
            String info = buildMuseConnectInfo();
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    "GSIC连接信息", info));
            log("已复制，发给云端 Muse 即可");
        });
        museCard.addView(bCopy);
        // v3.11 Issue 1：Token 管理
        TextView tokenTitle = new TextView(this);
        tokenTitle.setText("🔑 Token 管理");
        tokenTitle.setTextSize(14);
        tokenTitle.setTextColor(TEXT_DARK);
        tokenTitle.setPadding(0, dp(12), 0, dp(4));
        museCard.addView(tokenTitle);
        final TokenManager tm = new TokenManager(this);
        TextView tokenInfo = new TextView(this);
        tokenInfo.setTextSize(12);
        tokenInfo.setTextColor(TEXT_GRAY);
        Runnable refreshTokenInfo = () -> {
            String masked = TokenManager.mask(tm.getToken());
            long remainMs = tm.getRemainingMs();
            String remainStr = remainMs < 0 ? "永久有效"
                    : "剩余 " + (remainMs / 86400000) + " 天";
            tokenInfo.setText("当前 Token：" + masked + "\n" + remainStr);
        };
        refreshTokenInfo.run();
        museCard.addView(tokenInfo);
        // 有效期选择
        LinearLayout expiryRow = new LinearLayout(this);
        expiryRow.setOrientation(LinearLayout.HORIZONTAL);
        expiryRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView expiryLabel = new TextView(this);
        expiryLabel.setText("有效期：");
        expiryLabel.setTextSize(13);
        expiryRow.addView(expiryLabel);
        android.widget.Spinner expirySpinner = new android.widget.Spinner(this);
        String[] expOptions = {"永久", "7 天", "30 天"};
        int[] expValues = {0, 7, 30};
        android.widget.ArrayAdapter<String> expAdapter =
                new android.widget.ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_item, expOptions);
        expAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        expirySpinner.setAdapter(expAdapter);
        int curExp = tm.getExpiryDays();
        for (int i = 0; i < expValues.length; i++) {
            if (expValues[i] == curExp) {
                expirySpinner.setSelection(i);
                break;
            }
        }
        expirySpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> p, android.view.View v,
                            int pos, long id) {
                        tm.setExpiryDays(expValues[pos]);
                        refreshTokenInfo.run();
                        log("Token 有效期 → " + expOptions[pos]);
                    }
                    @Override
                    public void onNothingSelected(
                            android.widget.AdapterView<?> p) {}
                });
        expiryRow.addView(expirySpinner);
        museCard.addView(expiryRow);
        // 轮换/吊销按钮
        LinearLayout tokenBtns = new LinearLayout(this);
        tokenBtns.setOrientation(LinearLayout.HORIZONTAL);
        Button bRotate = smallBtn("🔄 轮换");
        bRotate.setOnClickListener(v -> {
            String newToken = tm.rotate();
            refreshTokenInfo.run();
            log("Token 已轮换：" + TokenManager.mask(newToken)
                    + "，旧 token 立即失效");
        });
        Button bRevoke = smallBtn("🚫 吊销");
        bRevoke.setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("吊销 Token？")
                    .setMessage("吊销后当前 token 立即失效，需重新复制连接信息给 Muse。")
                    .setPositiveButton("吊销", (d, w) -> {
                        tm.revoke();
                        refreshTokenInfo.run();
                        log("Token 已吊销");
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        tokenBtns.addView(bRotate);
        tokenBtns.addView(bRevoke);
        museCard.addView(tokenBtns);
        return museCard;
    }

    private LinearLayout buildTgCard() {
        LinearLayout tgCard = card();
        tgCard.addView(cardTitle("Telegram 机器人"));
        tgCard.addView(grayText(
                "去 @BotFather 建机器人拿 token，手机即 Bot：\n"
                        + "Telegram 里发话，手机照做。不依赖 Tailscale。"));
        tgToken = new EditText(this);
        tgToken.setHint("Bot Token（只存本机）");
        tgToken.setTextSize(13);
        tgToken.setSingleLine(true);
        tgToken.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tgCard.addView(tgToken);
        // v2.99：TG 自启开关
        LinearLayout tgAutoRow = new LinearLayout(this);
        tgAutoRow.setOrientation(LinearLayout.HORIZONTAL);
        tgAutoRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView tgAutoLabel = new TextView(this);
        tgAutoLabel.setText("软件启动时自动开启机器人");
        tgAutoLabel.setTextSize(13);
        LinearLayout.LayoutParams tgAutoLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tgAutoRow.addView(tgAutoLabel, tgAutoLp);
        android.widget.Switch tgAutoSw = new android.widget.Switch(this);
        boolean tgAuto = getSharedPreferences("agent", MODE_PRIVATE)
                .getBoolean("tg_autostart", true);
        tgAutoSw.setChecked(tgAuto);
        tgAutoSw.setOnCheckedChangeListener((v, on) -> {
            getSharedPreferences("agent", MODE_PRIVATE).edit()
                    .putBoolean("tg_autostart", on).apply();
            log(on ? "已开启 TG 自启" : "已关闭 TG 自启");
        });
        tgAutoRow.addView(tgAutoSw);
        tgCard.addView(tgAutoRow);
        LinearLayout tgBtns = new LinearLayout(this);
        tgBtns.setOrientation(LinearLayout.HORIZONTAL);
        Button bTgSave = smallBtn("保存");
        bTgSave.setOnClickListener(v -> {
            String t = tgToken.getText().toString().trim();
            if (!t.isEmpty()) tgStore.saveToken(t);
            tgToken.setText("");
            refreshTg();
            log("已保存");
        });
        Button bTgStart = smallBtn("启动");
        bTgStart.setOnClickListener(v -> {
            // v3.05：TG 在 Service 里跑
            AgentService.startTgStatic(this);
            refreshTg();
            log("🤖 TG 机器人已启动（后台运行）");
        });
        Button bTgStop = smallBtn("停止");
        bTgStop.setOnClickListener(v -> {
            AgentService.stopTgStatic();
            refreshTg();
            log("🤖 TG 机器人已停止");
        });
        tgBtns.addView(bTgSave);
        tgBtns.addView(bTgStart);
        tgBtns.addView(bTgStop);
        tgCard.addView(tgBtns);
        tgStatus = new TextView(this);
        tgStatus.setTextSize(13);
        tgStatus.setTextColor(TEXT_GRAY);
        tgCard.addView(tgStatus);
        tgOwner = new TextView(this);
        tgOwner.setTextSize(13);
        tgOwner.setTextColor(TEXT_GRAY);
        tgCard.addView(tgOwner);
        Button bTgUnbind = smallBtn("解绑主人");
        bTgUnbind.setOnClickListener(v -> {
            // v3.07：解绑加确认，防止误触
            if (!tgStore.hasOwner()) {
                log("本来就没绑定主人");
                return;
            }
            new android.app.AlertDialog.Builder(this)
                    .setTitle("解绑主人？")
                    .setMessage("解绑后机器人不再响应任何人，直到有人发 /start 重新绑定。")
                    .setPositiveButton("解绑", (d, w) -> {
                        long oldOwner = tgStore.getOwner();
                        tgStore.unbindOwner();
                        refreshTg();
                        log("已解绑主人（" + oldOwner + "），下次 /start 重新绑定");
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        tgCard.addView(bTgUnbind);
        tgLogView = new TextView(this);
        tgLogView.setTextSize(12);
        tgLogView.setTextColor(TEXT_DARK);
        tgScrollView = new ScrollView(this);
        tgScrollView.addView(tgLogView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams tgLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(120));
        tgLp.setMargins(0, dp(4), 0, 0);
        tgCard.addView(tgScrollView, tgLp);
        return tgCard;
    }

    private LinearLayout buildApiCard() {
        LinearLayout apiCard = card();
        apiCardTitle = cardTitle(
                "API 接口（端口 " + AgentService.getPort(this) + "，token 鉴权）");
        apiCard.addView(apiCardTitle);
        TextView api = new TextView(this);
        api.setText("GET /status —— 服务状态\n"
                + "GET /screen —— 读屏（结构化文本）\n"
                + "GET /screenshot —— 真机截图（base64 PNG）\n"
                + "POST /action —— 单步执行\n"
                + "POST /task —— 执行完整任务\n"
                + "  · goal 模式 {\"goal\":\"...\"} 默认异步：立即返回 taskId，"
                + "用 GET /task/status?taskId= 轮询看思考与执行过程；"
                + "加 \"wait\":true 则同步等结果\n\n"
                + "/task 步骤新增：\n"
                + "· termux：内置 Termux 里跑命令（⭐优先用）\n"
                + "  {\"action\":\"termux\",\"cmd\":\"python3 a.py\"} \n"
                + "· search：联网搜索，结果可存变量\n"
                + "  {\"action\":\"search\",\"query\":\"xx\",\"save_as\":\"r\"} 后续用 {{r}} 引用\n\n"
                + "鉴权：?token=xxx 或 Header X-Token\n"
                + "云端 Agent 经 Tailscale 内网调用，请勿暴露到公网。\n"
                + "v2.42 起仅接受本机与 Tailscale 网段（100.64.0.0/10）的连接，"
                + "局域网其他设备会被直接拒绝。");
        api.setTextSize(13);
        api.setTextColor(TEXT_GRAY);
        apiCard.addView(api);
        return apiCard;
    }

    // ---------- 终端页（内置 shell + 内置 Termux 环境 + 插件生态） ----------

    private LinearLayout buildTermPage() {
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(0, dp(8), 0, dp(100)); // v3.20：底部避让悬浮胶囊tab

        // 内置 Termux 环境卡片
        LinearLayout envCard = card();
        termEnvCard = envCard;
        envCard.addView(cardTitle("内置 Termux 环境"));
        termuxEnvStatus = new TextView(this);
        termuxEnvStatus.setTextSize(13);
        termuxEnvStatus.setTextColor(TEXT_GRAY);
        envCard.addView(termuxEnvStatus);
        termuxProgress = new android.widget.ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        termuxProgress.setMax(100);
        termuxProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pgLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pgLp.setMargins(0, dp(6), 0, dp(6));
        envCard.addView(termuxProgress, pgLp);
        TextView envHint = grayText("官方 Termux 用户空间，首次使用下载约 30MB（仅此刻联网），"
                + "之后 pkg / python 均可直接用。");
        envCard.addView(envHint);
        termuxDownloadBtn = smallBtn("下载并安装");
        termuxDownloadBtn.setOnClickListener(v -> downloadBootstrap());
        Button remountBtn = smallBtn("重新挂载");
        remountBtn.setOnClickListener(v -> {
            log("正在挂载…");
            new Thread(() -> {
                String err = bootstrapManager.ensureMounted();
                runOnUiThread(() -> {
                    log(err == null ? "挂载成功 ✓" : err);
                    refreshTermuxEnv();
                });
            }).start();
        });
        final Button delBtn = smallBtn("删除环境");
        delBtn.setOnClickListener(v -> {
            if (!"确认删除？".equals(delBtn.getText().toString())) {
                delBtn.setText("确认删除？");
                return;
            }
            delBtn.setText("删除环境");
            new Thread(() -> {
                bootstrapManager.remove();
                runOnUiThread(() -> {
                    log("已删除");
                    refreshTermuxEnv();
                });
            }).start();
        });
        Button diagBtn = smallBtn("🔍 诊断");
        diagBtn.setOnClickListener(v -> {
            new Thread(() -> {
                final String result = bootstrapManager.diagnose();
                runOnUiThread(() -> {
                    appendTerm(result + "\n");
                    log("诊断完成");
                    behaviorLog.app("Termux 诊断", result.replace("\n", " | "));
                });
            }).start();
        });
        Button fixBtn = smallBtn("🔧 修复链接");
        fixBtn.setOnClickListener(v -> {
            new Thread(() -> {
                final String result = bootstrapManager.fixSymlinks()
                        + "\n" + bootstrapManager.fixShebangs();
                runOnUiThread(() -> {
                    log(result);
                    appendTerm(result + "\n");
                });
            }).start();
        });
        // v2.68：按钮分两行，文字不再截断
        LinearLayout envBtns1 = new LinearLayout(this);
        envBtns1.setOrientation(LinearLayout.HORIZONTAL);
        envBtns1.addView(termuxDownloadBtn);
        envBtns1.addView(remountBtn);
        envBtns1.addView(delBtn);
        distributeRow(envBtns1);
        LinearLayout envBtns2 = new LinearLayout(this);
        envBtns2.setOrientation(LinearLayout.HORIZONTAL);
        envBtns2.addView(diagBtn);
        envBtns2.addView(fixBtn);
        distributeRow(envBtns2);
        envCard.addView(envBtns1);
        envCard.addView(envBtns2);
        inner.addView(envCard);

        // Termux 终端卡片：环境就绪后才显示输入区
        LinearLayout termCard = card();
        termCard.addView(cardTitle("Termux 终端"));
        termGateHint = new TextView(this);
        termGateHint.setTextSize(13);
        termGateHint.setTextColor(TEXT_GRAY);
        termGateHint.setLineSpacing(dp(3), 1f);
        termGateHint.setText("Termux 环境未就绪。\n请先在上方「内置 Termux 环境」卡片完成下载安装，"
                + "终端输入将在环境就绪后显示。");
        termCard.addView(termGateHint);
        termBody = new LinearLayout(this);
        termBody.setOrientation(LinearLayout.VERTICAL);
        termBody.setVisibility(View.GONE);
        termCard.addView(termBody);
        termStatus = new TextView(this);
        termStatus.setTextSize(13);
        termStatus.setTextColor(TEXT_GRAY);
        termStatus.setText("状态：未启动");
        termBody.addView(termStatus);

        termOut = new TextView(this);
        termOut.setTextSize(12);
        termOut.setTypeface(android.graphics.Typeface.MONOSPACE);
        termOut.setTextColor(0xFF00FF88);
        termOut.setBackgroundColor(0xFF1A1A1A);
        termOut.setPadding(dp(8), dp(8), dp(8), dp(8));
        termScroll = new ScrollView(this);
        termScroll.addView(termOut, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams outLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(260));
        outLp.setMargins(0, dp(6), 0, dp(6));
        termBody.addView(termScroll, outLp);
        // 智能跟随：用户手指拖动看历史时不抢滚，滑到底部自动恢复跟随
        termScroll.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                termFollow = false;
            }
            return false;
        });
        termScroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
            View child = termScroll.getChildAt(0);
            if (child != null) {
                int dist = child.getBottom()
                        - (termScroll.getHeight() + termScroll.getScrollY());
                if (dist <= dp(8)) termFollow = true;
            }
        });

        LinearLayout inputRow = new LinearLayout(this);
        termInputRow = inputRow;
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        termPrompt = new TextView(this);
        termPrompt.setText("$ ");
        termPrompt.setTextSize(14);
        termPrompt.setTypeface(android.graphics.Typeface.MONOSPACE);
        termPrompt.setTextColor(TEXT_DARK);
        inputRow.addView(termPrompt);
        termInput = new EditText(this);
        termInput.setHint("输入命令，回车发送");
        termInput.setTextSize(13);
        termInput.setSingleLine(true);
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        inLp.setMargins(0, 0, dp(8), 0);
        inputRow.addView(termInput, inLp);
        Button sendBtn = smallBtn("发送");
        sendBtn.setOnClickListener(v -> sendTermCmd());
        inputRow.addView(sendBtn);
        termBody.addView(inputRow);
        termInput.setOnEditorActionListener((v, actionId, event) -> {
            if (event != null && event.getAction() == android.view.KeyEvent.ACTION_DOWN
                    && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER) {
                sendTermCmd();
                return true;
            }
            return false;
        });

        LinearLayout shellBtns = new LinearLayout(this);
        shellBtns.setOrientation(LinearLayout.HORIZONTAL);
        Button shBtn = smallBtn("系统 sh");
        shBtn.setOnClickListener(v -> startShell(false));
        Button suBtn = smallBtn("root shell");
        suBtn.setOnClickListener(v -> startShell(true));
        Button txShellBtn = smallBtn("Termux");
        txShellBtn.setOnClickListener(v -> startTermuxShell());
        shellBtns.addView(shBtn);
        shellBtns.addView(suBtn);
        shellBtns.addView(txShellBtn);
        distributeRow(shellBtns);
        termBody.addView(shellBtns);

        LinearLayout shellBtns2 = new LinearLayout(this);
        shellBtns2.setOrientation(LinearLayout.HORIZONTAL);
        Button clearBtn = smallBtn("清屏");
        clearBtn.setOnClickListener(v -> termOut.setText(""));
        Button killBtn = smallBtn("断开");
        killBtn.setOnClickListener(v -> {
            if (shell != null) shell.stop();
            termStatus.setText("状态：未启动");
            termStatus.setTextColor(TEXT_GRAY);
            termPrompt.setText("$ ");
            setTermFocusMode(false);
        });
        shellBtns2.addView(clearBtn);
        shellBtns2.addView(killBtn);
        distributeRow(shellBtns2);
        termBody.addView(shellBtns2);
        inner.addView(termCard);

        // Termux 生态卡片（一个按钮的插件枢纽，取代旧的外部 Termux 联动）
        LinearLayout txCard = card();
        termTxCard = txCard;
        txCard.addView(cardTitle("Termux 生态"));
        txCard.addView(grayText("环境已内置；搭配官方插件可扩展能力："
                + "API 调安卓功能、GUI 写界面、Boot 开机自启等。"));
        Button ecoBtn = btn("Termux 插件生态");
        ecoBtn.setOnClickListener(v -> showPluginDialog());
        txCard.addView(ecoBtn);
        inner.addView(txCard);

        ScrollView wrap = new ScrollView(this);
        termPageScroll = wrap;
        wrap.addView(inner);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.addView(wrap, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return outer;
    }

    private void sendTermCmd() {
        String c = termInput.getText().toString();
        if (c.trim().isEmpty()) return;
        if (shell == null || !shell.isRunning()) {
            log("先启动 shell");
            return;
        }
        appendTerm(termPrompt.getText() + c + "\n");
        shell.exec(c);
        termInput.setText("");
        termPrompt.setText("[" + shell.getCwd() + "]$ ");
    }

    private void startShell(boolean asRoot) {
        startShellWith(new String[]{asRoot ? "su" : "sh"},
                new java.util.HashMap<String, String>(),
                asRoot ? "root" : "sh");
    }

    /** 启动内置 Termux 环境的登录 shell（免 root，官方 Termux 也不需要 root） */
    private void startTermuxShell() {
        if (!bootstrapManager.isInstalled()) {
            log("请先下载并安装 Termux 环境");
            return;
        }
        appendTerm("[正在启动 Termux…]\n");
        new Thread(() -> {
            // 免 root 优先：直接用 staging 目录，不依赖挂载
            // （官方 Termux 本来就不需要 root，挂载只是可选优化）
            java.util.Map<String, String> env = bootstrapManager.termuxEnv();
            String prefix = env.get("PREFIX");
            java.io.File bashFile =
                    new java.io.File(prefix + "/bin/bash");
            if (!bashFile.exists()) {
                runOnUiThread(() -> {
                    termStatus.setText("状态：启动失败");
                    termStatus.setTextColor(RED);
                    appendTerm("[启动失败] 找不到 bash: "
                            + bashFile.getAbsolutePath() + "\n");
                    behaviorLog.app("Termux 启动失败", "找不到 bash");
                });
                return;
            }
            // 显式 chmod 0755（File.setExecutable 在部分 ROM 上不可靠）
            try {
                android.system.Os.chmod(bashFile.getAbsolutePath(), 0755);
            } catch (Exception e) {
                bashFile.setExecutable(true, false);
            }
            final boolean canExec = bashFile.canExecute();
            runOnUiThread(() -> {
                if (!canExec) {
                    termStatus.setText("状态：启动失败");
                    termStatus.setTextColor(RED);
                    appendTerm("[启动失败] bash 无执行权限，"
                            + "可能是系统限制\n");
                    return;
                }
                startShellWith(
                        new String[]{prefix + "/bin/bash", "--login"},
                        env, "Termux");
                behaviorLog.app("Termux 启动", "免 root 模式");
                refreshTermuxEnv();
            });
        }).start();
    }

    private void startShellWith(String[] cmd, java.util.Map<String, String> env,
                                String label) {
        if (shell != null) shell.stop();
        termOut.setText("");
        appendTerm("[正在启动 " + label + "…]\n");
        shell = new ShellSession(new ShellSession.Listener() {
            @Override
            public void onOutput(String s) {
                appendTerm(s);
            }

            @Override
            public void onExit(int code) {
                runOnUiThread(() -> {
                    termStatus.setText("状态：已断开（exit=" + code + "）");
                    termStatus.setTextColor(RED);
                    appendTerm("\n[shell 已退出，exit=" + code + "]\n");
                    setTermFocusMode(false);
                });
            }
        });
        boolean ok = shell.start(cmd, env);
        if (ok) {
            termStatus.setText("状态：运行中（" + label + "）✓");
            termStatus.setTextColor(GREEN);
            setTermFocusMode(true);
        } else {
            termStatus.setText("状态：启动失败" + ("root".equals(label) ? "（无 root 权限？）" : ""));
            termStatus.setTextColor(RED);
        }
    }

    // ---------- 内置 Termux 环境：下载 / 安装 / 状态 ----------

    private void downloadBootstrap() {
        termuxDownloadBtn.setEnabled(false);
        termuxProgress.setVisibility(View.VISIBLE);
        termuxProgress.setProgress(0);
        termuxEnvStatus.setText("正在下载官方环境（约 30MB）…");
        bootstrapManager.download(new BootstrapManager.ProgressListener() {
            @Override
            public void onProgress(int p) {
                runOnUiThread(() -> termuxProgress.setProgress(p));
            }

            @Override
            public void onDone(boolean ok, String msg) {
                runOnUiThread(() -> {
                    if (!ok) {
                        termuxProgress.setVisibility(View.GONE);
                        termuxDownloadBtn.setEnabled(true);
                        termuxEnvStatus.setText("下载失败：" + msg);
                        log(msg);
                        return;
                    }
                    termuxEnvStatus.setText("正在解压安装…");
                    bootstrapManager.install(new BootstrapManager.ProgressListener() {
                        @Override
                        public void onProgress(int p) {
                        }

                        @Override
                        public void onDone(boolean ok2, String msg2) {
                            runOnUiThread(() -> {
                                termuxProgress.setVisibility(View.GONE);
                                termuxDownloadBtn.setEnabled(true);
                                if (!ok2) {
                                    termuxEnvStatus.setText("安装失败：" + msg2);
                                    log(msg2);
                                    refreshTermuxEnv();
                                    return;
                                }
                                termuxEnvStatus.setText("正在挂载…");
                                new Thread(() -> {
                                    String err = bootstrapManager.ensureMounted();
                                    runOnUiThread(() -> {
                                        log(err == null
                                                ? "Termux 环境就绪 ✓，点「Termux」开始用"
                                                : err);
                                        refreshTermuxEnv();
                                    });
                                }).start();
                            });
                        }
                    });
                });
            }
        });
    }

    private void refreshTermuxEnv() {
        if (termuxEnvStatus == null) return;
        termuxEnvStatus.setText("状态：" + bootstrapManager.statusText());
        boolean ready = bootstrapManager.isReady();
        boolean installed = bootstrapManager.isInstalled();
        termuxEnvStatus.setTextColor(ready ? GREEN : (installed ? TEXT_GRAY : RED));
        termuxDownloadBtn.setEnabled(!installed);
        termuxDownloadBtn.setText(installed ? "已安装" : "下载并安装");
        // 终端输入区门控：环境就绪后才显示
        if (termBody != null) {
            termBody.setVisibility(ready ? View.VISIBLE : View.GONE);
            termGateHint.setVisibility(ready ? View.GONE : View.VISIBLE);
        }
    }

    private void appendTerm(String s) {
        runOnUiThread(() -> {
            termOut.append(s);
            // 只有跟随模式才自动滚到底；用户手指拖动看历史时不打扰
            termScroll.post(() -> {
                if (termFollow) termScroll.fullScroll(View.FOCUS_DOWN);
            });
        });
    }

    /** 终端专注模式：shell 运行时隐藏多余卡片，输出区固定填满剩余空间 */
    private void setTermFocusMode(boolean focus) {
        if (termEnvCard != null)
            termEnvCard.setVisibility(focus ? View.GONE : View.VISIBLE);
        if (termTxCard != null)
            termTxCard.setVisibility(focus ? View.GONE : View.VISIBLE);
        if (termScroll != null) {
            LinearLayout.LayoutParams lp =
                    (LinearLayout.LayoutParams) termScroll.getLayoutParams();
            if (focus) {
                lp.height = 0;
                lp.weight = 1f;
            } else {
                lp.height = dp(260);
                lp.weight = 0;
            }
            termScroll.setLayoutParams(lp);
        }
        termFollow = true;
    }

    private boolean isPkgInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Termux 官方插件：{显示名, 包名, 一句话介绍} */
    private static final String[][] TERMUX_PLUGINS = {
            {"Termux:API", "com.termux.api",
                    "电池、通知、TTS、相机、定位"},
            {"Termux:GUI", "com.termux.gui", "用脚本构建原生安卓界面"},
            {"Termux:Boot", "com.termux.boot", "开机自动运行脚本"},
            {"Termux:Widget", "com.termux.widget", "桌面小部件一键跑脚本"},
            {"Termux:Tasker", "com.termux.tasker", "Tasker 任务联动"},
            {"Termux:Float", "com.termux.float", "悬浮窗终端"},
            {"Termux:Styling", "com.termux.styling", "终端配色与字体"},
    };

    /** 一个按钮的插件枢纽：已装→打开，未装→去 F-Droid 安装 */
    private void showPluginDialog() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (String[] p : TERMUX_PLUGINS) {
            final String name = p[0];
            final String pkg = p[1];
            final boolean installed = isPkgInstalled(pkg);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            LinearLayout txt = new LinearLayout(this);
            txt.setOrientation(LinearLayout.VERTICAL);
            TextView tTitle = new TextView(this);
            tTitle.setText(name + (installed ? " ✓" : ""));
            tTitle.setTextSize(15);
            tTitle.setTextColor(installed ? GREEN : TEXT_DARK);
            txt.addView(tTitle);
            TextView tDesc = new TextView(this);
            tDesc.setText(p[2]);
            tDesc.setTextSize(12);
            tDesc.setTextColor(TEXT_GRAY);
            tDesc.setPadding(0, dp(2), 0, 0);
            txt.addView(tDesc);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(0, 0, dp(8), 0);
            row.addView(txt, lp);
            Button b = smallBtn(installed ? "打开" : "安装");
            b.setPadding(dp(12), dp(4), dp(12), dp(4));
            b.setMinHeight(dp(32));
            b.setMinimumHeight(dp(32));
            b.setOnClickListener(v -> {
                try {
                    if (installed) {
                        startActivity(getPackageManager()
                                .getLaunchIntentForPackage(pkg));
                    } else {
                        startActivity(new android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(
                                        "https://f-droid.org/packages/" + pkg + "/")));
                    }
                } catch (Exception e) {
                    log("无法打开");
                }
            });
            row.addView(b);
            list.addView(row);
        }
        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        android.app.AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Termux 插件生态")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
        Button pos = dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE);
        if (pos != null) pos.setTextColor(DyColor.primary(this));
    }

    // ---------- 我的页（关于） ----------

    /** 软件自检卡：记录用户操作与软件反馈，可查看/导出/清空 */
    private LinearLayout buildSelfCheckCard() {
        LinearLayout scCard = card();
        LinearLayout scHeader = new LinearLayout(this);
        scHeader.setOrientation(LinearLayout.HORIZONTAL);
        scHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView scTitle = cardTitle("软件自检");
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        scHeader.addView(scTitle, scLp);
        scCard.addView(scHeader);
        scCard.addView(grayText(
                "开启后记录你在软件内的操作和软件的反馈，用于自查问题"));
        final TextView scStatus = new TextView(this);
        scStatus.setTextSize(13);
        scStatus.setTextColor(TEXT_GRAY);
        scCard.addView(scStatus);
        // v3.52：开机自启回执（地基自验：有证据的自启状态）
        final TextView scBoot = new TextView(this);
        scBoot.setTextSize(13);
        scBoot.setTextColor(TEXT_GRAY);
        scCard.addView(scBoot);
        // 地基·自更新回执（与自启回执并列展示）
        final TextView scUpd = new TextView(this);
        scUpd.setTextSize(13);
        scUpd.setTextColor(TEXT_GRAY);
        scCard.addView(scUpd);
        final Button bToggle = smallBtn("▶ 开始记录");
        final Runnable refreshSc = new Runnable() {
            @Override
            public void run() {
                boolean rec = behaviorLog.isRecording();
                bToggle.setText(rec ? "⏹ 停止记录" : "▶ 开始记录");
                scStatus.setText(rec ? "● 正在记录…" : "未记录");
                scStatus.setTextColor(rec ? Color.RED : TEXT_GRAY);
                scBoot.setText(BootReceiver.receiptText(MainActivity.this));
                scUpd.setText(UpdateManager.receiptText(MainActivity.this));
            }
        };
        bToggle.setOnClickListener(v -> {
            if (behaviorLog.isRecording()) {
                behaviorLog.stop();
                log("自检记录已停止");
            } else {
                behaviorLog.start();
                log("自检记录开始，你的操作将被记录");
            }
            refreshSc.run();
        });
        LinearLayout scBtns = new LinearLayout(this);
        scBtns.setOrientation(LinearLayout.HORIZONTAL);
        scBtns.addView(bToggle);
        Button bView = smallBtn("👁 查看");
        bView.setOnClickListener(v -> showBehaviorLog());
        scBtns.addView(bView);
        Button bExp = smallBtn("📤 导出");
        bExp.setOnClickListener(v -> exportBehaviorLog());
        scBtns.addView(bExp);
        Button bClr = smallBtn("🗑 清空");
        bClr.setOnClickListener(v -> {
            behaviorLog.clear();
            log("自检记录已清空");
        });
        scBtns.addView(bClr);
        scCard.addView(scBtns);
        refreshSc.run();
        return scCard;
    }

    /** 查看自检记录对话框 */
    private void showBehaviorLog() {
        java.util.List<String> list = behaviorLog.list();
        StringBuilder sb = new StringBuilder();
        if (list.isEmpty()) {
            sb.append("暂无记录，先点「开始记录」");
        } else {
            for (String l : list) sb.append(l).append("\n");
        }
        TextView tv = new TextView(this);
        tv.setText(sb.toString());
        tv.setTextSize(12);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(8), dp(8), dp(8), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(400)));
        new android.app.AlertDialog.Builder(this)
                .setTitle("自检记录（最新在前）")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
        behaviorLog.user("查看自检记录");
    }

    /** 导出自检记录 */
    private void exportBehaviorLog() {
        new Thread(() -> {
            try {
                final java.io.File out =
                        behaviorLog.exportFile(MainActivity.this);
                runOnUiThread(() -> {
                    android.content.Intent share =
                            new android.content.Intent(
                            android.content.Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(
                            android.content.Intent.EXTRA_STREAM,
                            CaptureProvider.uriFor(out));
                    share.addFlags(android.content.Intent
                            .FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(android.content.Intent.createChooser(
                            share, "导出自检记录"));
                    log("自检记录已导出");
                });
                behaviorLog.user("导出自检记录");
            } catch (Exception e) {
                runOnUiThread(() ->
                        log("导出失败: " + e.getMessage()));
            }
        }).start();
    }

    // v3.47: usage guide text, shared by About-Software and My-page bottom card (single source, no divergence)
    private static String usageGuideText() {
        return "【先做准备】\n"
                + "1. 权限：开无障碍（读屏/点击/截图，只在本机用）、悬浮窗（任务进度与流光显示）；MIUI 还要放行后台弹出界面并加电池白名单，否则可能点不动或后台被杀。有 root 时自动走 root 操作通道，更快更稳；无 root 也能用（无障碍通道）。\n"
                + "2. 模型：主页「云端大模型」填主模型（地址/Key/模型名，点「拉取」可从服务商获取模型列表直接选）。没配主模型 Key 时智能执行用不了，但本地直达指令照常。纯图标按钮靠视觉定位，建议在「辅助模型」里配一个视觉类模型（如智谱 glm-4v-flash），主模型会兜底。\n\n"
                + "【智能体页：下达任务】\n"
                + "底部中间「智能体」页输入一句目标即可，例如：打开汽水音乐领免费听歌时长 / 开启个人热点 / 打开微信给某人发消息。它会自己看屏、规划、动手、复核，走一步看一步，错了换方法；没有步数和总时长限制，觉得办完才停。\n"
                + "运行中：全屏流光边框表示正在干活（触摸穿透，不挡你操作）；你可以中途在对话里插话纠正，它下一步就参考；发送按钮变停止可随时打断。虚拟屏在右上角有悬浮预览（仅 App 通道，见下）。\n"
                + "快捷指令：/stop 强停、/clear 清空、/retry 重试、/help 帮助、/new 新开对话（清三端共享上下文）。\n\n"
                + "【零 token 直达与闲聊】\n"
                + "这几类不进模型、本地直接办：设闹钟、倒计时、打开/开启某个应用、打开相册、回桌面/回主页、静音/震动/响铃。打招呼、提问类闲聊走轻量对话回复，不烧任务 token；短指令默认按任务处理，不会被误判成闲聊。\n\n"
                + "【监管者：防假完成】\n"
                + "本地看门狗盯着重复动作、来回交替、连续失败、反复找不存在的文字、界面停滞；触发后监管者看最新截图裁决（给指引继续/办不成中止/误报放行）。任务结束还要过终审：监管者看最终截图核对目标，没办到会打回补做，办不到如实报未完成——自己说完成不算数。开关与监管模型在「我的」页：监管者默认用最强主模型，长按某个模型档案可指定它专做监管。\n\n"
                + "【技能卡】\n"
                + "两种卡：步骤卡（JSON 固定步骤重放）和说明型卡（自然语言流程，交给智能体照着做）。来源：你手写的优先，其次是任务成功后自动沉淀的。技能中心可运行/删除/导出/导入；步骤可标 optional，失败只跳过不中断整卡；智能体也会按关键词自己调用合适的卡。常用任务存成卡，下次一句话直接办，省 token。\n\n"
                + "【定时任务】\n"
                + "四种类型：每天、每周几、每隔 N 分钟、一次性。到点自动执行目标或技能卡，执行结果进运行记录。\n\n"
                + "【远控页：服务与云端操控】\n"
                + "1. 远程控制服务：启停服务、改端口（默认 28789）、看/复制 token。服务只放行本机回环和 Tailscale 网段，同 WiFi 的其他设备连不上；token 别给别人，拿到就能操作你的手机。\n"
                + "2. 云端操控：手机装 Tailscale 并与云端 Muse 同一账号组网，在本页复制连接信息（IP/端口/token）发给 Muse，它就能用 /status 看状态、/screen 读屏、/screenshot 截屏、/action 单步、/task 下达步骤或目标、/task/status 看实时进度。远控任务在主屏执行，结束后自动回桌面。\n"
                + "3. API 接口卡有每个接口的说明与示例。\n\n"
                + "【Telegram 机器人】\n"
                + "远控页的 TG 卡填 Bot token 后启用，软件启动会自动拉起。先给 Bot 发 /start 绑定主人，只认主人的消息。直接发文字就是下达目标；指令：/status 状态、/shot 截屏、/stop 打断、/new 新对话、/task {json} 跑步骤、/agent 目标 智能执行。执行进度会在一条消息里原地更新，结束发简短总结。\n\n"
                + "【虚拟屏】\n"
                + "只有 App 通道发的任务默认在虚拟屏里跑（需要 root）：不占用主屏，右上角悬浮预览能实时看到它在做什么，结束自动销毁。Telegram 和远控的任务在主屏执行。注意：系统相机在虚拟屏里不画图（MIUI 限制），涉及相机预览的任务请在主屏做。\n\n"
                + "【终端页】\n"
                + "内置 Termux：首次一键下载官方环境（约 30MB，校验后使用），免 root 用 staging 模式也能跑 bash 和 pkg；终端页可直接交互，智能体也能用 termux 工具在本地查信息、算数、处理文件。环境异常先点「诊断」，链接坏了点「修复链接」。无 root 设备不显示终端页。\n\n"
                + "【我的页】\n"
                + "关于更新：看版本、检查更新、设更新源；关于软件：本使用方法 + 全部更新日志。还有：监管者输出（独立折叠区）、动作审批（见下）、长期记忆管理（查看/删单条/清空）、智能沉淀（1/7/30 天报告，可分享给 Muse 分析）、任务日志与运行记录（可导出）、软件自检。\n\n"
                + "【安全与动作审批】\n"
                + "动作按风险分四级：只读/设备控制/外部效应/破坏性，每级可设自动允许、每次确认或拒绝。远控和 Telegram 进来的任务没有审批人在场，「每次确认」档会被直接拦截。API Key、TG token、远控 token 和用户记忆都经系统 Keystore 加密保存，不明文存。\n\n"
                + "【限制与排障】\n"
                + "办不成会如实报未完成和卡点，常见原因：没配模型 Key、视觉模型不可用、目标 App 改版、强弹窗广告或验证码、应用根本没装。任务长时间没进展可 /stop 打断后换个说法重试，或看「我的」页的任务日志定位卡在哪一步。\n\n"
                + "【返回键】非主页按返回先回主页，主页双击退出。";
    }

    // v2.65：关于软件卡（含使用方法+更新日志，二级折叠）
    private LinearLayout buildAboutSoftwareCard() {
        LinearLayout swCard = card();
        // 一级：关于软件
        LinearLayout swHeader = new LinearLayout(this);
        swHeader.setOrientation(LinearLayout.HORIZONTAL);
        swHeader.setGravity(Gravity.CENTER_VERTICAL);
        swHeader.setClickable(true);
        swHeader.setFocusable(true);
        swHeader.setPadding(dp(4), dp(8), dp(4), dp(8));
        TextView swTitle = new TextView(this);
        swTitle.setText("关于软件");
        swTitle.setTextSize(18);
        swTitle.setTypeface(swTitle.getTypeface(),
                android.graphics.Typeface.BOLD);
        swTitle.setTextColor(DyColor.onSurface(this));
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        swHeader.addView(swTitle, swLp);
        final TextView swArrow = new TextView(this);
        swArrow.setTextSize(16);
        swArrow.setTextColor(DyColor.onSurfaceVariant(this));
        swHeader.addView(swArrow);
        swCard.addView(swHeader);
        LinearLayout swBody = new LinearLayout(this);
        swBody.setOrientation(LinearLayout.VERTICAL);
        swCard.addView(swBody);
        final boolean[] swOpen = {false};
        Runnable paintSw = () -> swArrow.setText(swOpen[0] ? "▾" : "▸");
        swHeader.setOnClickListener(v -> {
            swOpen[0] = !swOpen[0];
            swBody.setVisibility(swOpen[0] ? View.VISIBLE : View.GONE);
            paintSw.run();
        });
        swBody.setVisibility(View.GONE);
        paintSw.run();

        // 二级：使用方法（可折叠）
        LinearLayout useHeader = new LinearLayout(this);
        useHeader.setOrientation(LinearLayout.HORIZONTAL);
        useHeader.setGravity(Gravity.CENTER_VERTICAL);
        useHeader.setClickable(true);
        useHeader.setFocusable(true);
        useHeader.setPadding(dp(4), dp(6), dp(4), dp(6));
        TextView useTitle = new TextView(this);
        useTitle.setText("使用方法");
        useTitle.setTextSize(15);
        useTitle.setTypeface(useTitle.getTypeface(),
                android.graphics.Typeface.BOLD);
        useTitle.setTextColor(DyColor.onSurface(this));
        LinearLayout.LayoutParams useLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        useHeader.addView(useTitle, useLp);
        final TextView useArrow = new TextView(this);
        useArrow.setTextSize(14);
        useArrow.setTextColor(DyColor.onSurfaceVariant(this));
        useHeader.addView(useArrow);
        swBody.addView(useHeader);
        TextView useBody = new TextView(this);
        useBody.setText(usageGuideText());
        useBody.setTextSize(13);
        useBody.setTextColor(TEXT_DARK);
        useBody.setLineSpacing(dp(4), 1f);
        useBody.setPadding(dp(8), dp(4), dp(8), dp(8));
        useBody.setTextIsSelectable(true);
        swBody.addView(useBody);
        final boolean[] useOpen = {false};
        Runnable paintUse = () -> useArrow.setText(useOpen[0] ? "▾" : "▸");
        useHeader.setOnClickListener(v -> {
            useOpen[0] = !useOpen[0];
            useBody.setVisibility(useOpen[0] ? View.VISIBLE : View.GONE);
            paintUse.run();
        });
        useBody.setVisibility(View.GONE);
        paintUse.run();

        // 二级：更新日志（可折叠）
        LinearLayout logHeader = new LinearLayout(this);
        logHeader.setOrientation(LinearLayout.HORIZONTAL);
        logHeader.setGravity(Gravity.CENTER_VERTICAL);
        logHeader.setClickable(true);
        logHeader.setFocusable(true);
        logHeader.setPadding(dp(4), dp(6), dp(4), dp(6));
        TextView logTitle2 = new TextView(this);
        logTitle2.setText("更新日志");
        logTitle2.setTextSize(15);
        logTitle2.setTypeface(logTitle2.getTypeface(),
                android.graphics.Typeface.BOLD);
        logTitle2.setTextColor(DyColor.onSurface(this));
        LinearLayout.LayoutParams logLp2 = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        logHeader.addView(logTitle2, logLp2);
        final TextView logArrow2 = new TextView(this);
        logArrow2.setTextSize(14);
        logArrow2.setTextColor(DyColor.onSurfaceVariant(this));
        logHeader.addView(logArrow2);
        swBody.addView(logHeader);
        LinearLayout logBody2 = new LinearLayout(this);
        logBody2.setOrientation(LinearLayout.VERTICAL);
        TextView hint2 = grayText("点击版本号展开详情");
        logBody2.addView(hint2);
        LinearLayout logList2 = new LinearLayout(this);
        logList2.setOrientation(LinearLayout.VERTICAL);
        logBody2.addView(logList2);
        swBody.addView(logBody2);
        final boolean[] logOpen2 = {false};
        Runnable paintLog2 = () -> logArrow2.setText(logOpen2[0] ? "▾" : "▸");
        logHeader.setOnClickListener(v -> {
            logOpen2[0] = !logOpen2[0];
            logBody2.setVisibility(logOpen2[0] ? View.VISIBLE : View.GONE);
            paintLog2.run();
        });
        logBody2.setVisibility(View.GONE);
        paintLog2.run();
        // 填充更新日志
        for (int i = 0; i < AppInfo.LOG_VERSIONS.length; i++) {
            final int idx = i;
            final TextView detail = new TextView(this);
            detail.setText(AppInfo.LOG_DETAILS[i]);
            detail.setTextSize(13);
            detail.setTextColor(TEXT_DARK);
            detail.setLineSpacing(dp(3), 1f);
            detail.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            detail.setPadding(dp(8), dp(2), dp(8), dp(8));
            final Button header = btn("");
            header.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            final Runnable paint = () -> {
                boolean open = detail.getVisibility() == View.VISIBLE;
                header.setText(AppInfo.LOG_VERSIONS[idx] + " · "
                        + AppInfo.LOG_SUMMARY[idx] + (open ? "  ▾" : "  ▸"));
            };
            header.setOnClickListener(v -> {
                detail.setVisibility(detail.getVisibility() == View.VISIBLE
                        ? View.GONE : View.VISIBLE);
                paint.run();
            });
            paint.run();
            logList2.addView(header);
            logList2.addView(detail);
        }

        return swCard;
    }

    private LinearLayout buildMyPage() {
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(0, dp(8), 0, dp(100)); // v3.20：底部避让悬浮胶囊tab

        // ---- 软件自检卡 ----
        inner.addView(buildSelfCheckCard());

        LinearLayout aboutCard = card();
        // 可折叠的关于更新
        LinearLayout aboutHeader = new LinearLayout(this);
        aboutHeader.setOrientation(LinearLayout.HORIZONTAL);
        aboutHeader.setGravity(Gravity.CENTER_VERTICAL);
        aboutHeader.setClickable(true);
        aboutHeader.setFocusable(true);
        TextView aboutTitle = new TextView(this);
        aboutTitle.setText("关于更新");
        aboutTitle.setTextSize(18);
        aboutTitle.setTypeface(aboutTitle.getTypeface(),
                android.graphics.Typeface.BOLD);
        aboutTitle.setTextColor(DyColor.onSurface(this));
        LinearLayout.LayoutParams aboutLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        aboutHeader.addView(aboutTitle, aboutLp);
        final TextView aboutArrow = new TextView(this);
        aboutArrow.setTextSize(16);
        aboutArrow.setTextColor(DyColor.onSurfaceVariant(this));
        aboutHeader.addView(aboutArrow);
        aboutCard.addView(aboutHeader);
        final LinearLayout aboutBody = new LinearLayout(this);
        aboutBody.setOrientation(LinearLayout.VERTICAL);
        aboutCard.addView(aboutBody);
        final boolean[] aboutOpen = {true};
        Runnable paintAbout = () -> aboutArrow.setText(aboutOpen[0] ? "▾" : "▸");
        aboutHeader.setOnClickListener(v -> {
            aboutOpen[0] = !aboutOpen[0];
            aboutBody.setVisibility(aboutOpen[0] ? View.VISIBLE : View.GONE);
            paintAbout.run();
        });
        paintAbout.run();
        TextView title = new TextView(this);
        title.setText("GSIC");
        title.setTextSize(22);
        title.setTextColor(TEXT_DARK);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setClickable(true);
        title.setFocusable(true);
        final long[] eggLast = {0};
        final int[] eggCount = {0};
        title.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (now - eggLast[0] > 2000) eggCount[0] = 0;
            eggLast[0] = now;
            eggCount[0]++;
            if (eggCount[0] >= 5) {
                eggCount[0] = 0;
                new AlertDialog.Builder(this)
                        .setTitle("GSIC")
                        .setMessage("全球大学生创新挑战\n创新是 GSIC 存在的意义")
                        .setPositiveButton("知道了", null)
                        .show();
            }
        });
        aboutBody.addView(title);
        TextView ver = new TextView(this);
        String patchTag = PatchManager.loadedTag(this);
        ver.setText("版本 " + AppInfo.VERSION
                + (patchTag.isEmpty() ? "" : " · 补丁 " + patchTag));
        ver.setTextSize(14);
        ver.setTextColor(TEXT_GRAY);
        ver.setGravity(Gravity.CENTER_HORIZONTAL);
        ver.setPadding(0, 0, 0, dp(8));
        aboutBody.addView(ver);
        // 应用内更新：检查更新 / 设置更新源
        TextView updTitle = new TextView(this);
        updTitle.setText("应用内更新");
        updTitle.setTextSize(16);
        updTitle.setTypeface(updTitle.getTypeface(),
                android.graphics.Typeface.BOLD);
        updTitle.setTextColor(DyColor.onSurface(this));
        updTitle.setPadding(0, dp(4), 0, dp(4));
        aboutBody.addView(updTitle);
        updStatus = new TextView(this);
        updStatus.setTextSize(13);
        updStatus.setTextColor(TEXT_GRAY);
        updStatus.setText("当前版本 " + AppInfo.VERSION
                + "，点“检查更新”从更新源获取新版。");
        aboutBody.addView(updStatus);
        updProgress = new android.widget.ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        updProgress.setMax(100);
        updProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams updPgLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        updPgLp.setMargins(0, dp(6), 0, dp(6));
        aboutBody.addView(updProgress, updPgLp);
        LinearLayout updBtns = new LinearLayout(this);
        updBtns.setOrientation(LinearLayout.HORIZONTAL);
        Button checkUpdBtn = smallBtn("检查更新");
        checkUpdBtn.setOnClickListener(v -> doCheckUpdate());
        Button updSrcBtn = smallBtn("设置更新源");
        updSrcBtn.setOnClickListener(v -> showUpdateSourceDialog());
        updBtns.addView(checkUpdBtn);
        updBtns.addView(updSrcBtn);
        distributeRow(updBtns);
        aboutBody.addView(updBtns);
        inner.addView(aboutCard);

        // v2.65：关于软件（含使用方法+更新日志，二级折叠）
        inner.addView(buildAboutSoftwareCard());

        // v2.62：监管者独立输出区（可折叠，与智能体输出分开）
        LinearLayout supCard = card();
        LinearLayout supHeader = new LinearLayout(this);
        supHeader.setOrientation(LinearLayout.HORIZONTAL);
        supHeader.setGravity(Gravity.CENTER_VERTICAL);
        supHeader.setClickable(true);
        supHeader.setFocusable(true);
        supHeader.setPadding(dp(4), dp(4), dp(4), dp(4));
        TextView supTitle = new TextView(this);
        supTitle.setText("🔍 监管者输出");
        supTitle.setTextSize(13);
        supTitle.setTypeface(supTitle.getTypeface(),
                android.graphics.Typeface.BOLD);
        supTitle.setTextColor(DyColor.onSurface(this));
        LinearLayout.LayoutParams supLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        supHeader.addView(supTitle, supLp);
        final TextView supArrow = new TextView(this);
        supArrow.setTextSize(14);
        supArrow.setTextColor(DyColor.onSurfaceVariant(this));
        supHeader.addView(supArrow);
        supCard.addView(supHeader);
        supervisorScrollView = new ScrollView(this);
        supervisorMsgBox = new LinearLayout(this);
        supervisorMsgBox.setOrientation(LinearLayout.VERTICAL);
        supervisorScrollView.addView(supervisorMsgBox,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        // 限高 120dp，避免占太多空间
        supervisorScrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(120)));
        supCard.addView(supervisorScrollView);
        final boolean[] supOpen = {false};
        Runnable paintSup = () -> supArrow.setText(supOpen[0] ? "▾" : "▸");
        supHeader.setOnClickListener(v -> {
            supOpen[0] = !supOpen[0];
            supervisorScrollView.setVisibility(
                    supOpen[0] ? View.VISIBLE : View.GONE);
            paintSup.run();
        });
        supervisorScrollView.setVisibility(View.GONE);
        paintSup.run();
        inner.addView(supCard);

        // v2.93：任务日志查看
        LinearLayout logCard = card();
        LinearLayout logHeader = new LinearLayout(this);
        logHeader.setOrientation(LinearLayout.HORIZONTAL);
        logHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView logTitle = new TextView(this);
        logTitle.setText("📝 任务日志");
        logTitle.setTextSize(16);
        logTitle.setTypeface(logTitle.getTypeface(),
                android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        logHeader.addView(logTitle, logLp);
        Button bViewLogs = smallBtn("查看");
        bViewLogs.setOnClickListener(v -> showTaskLogs());
        logHeader.addView(bViewLogs);
        logCard.addView(logHeader);
        // v3.11 Issue 7：日志占用 + 保留天数 + 一键清理
        TextView logSizeTv = new TextView(this);
        logSizeTv.setTextSize(12);
        logSizeTv.setTextColor(DyColor.onSurfaceVariant(this));
        logSizeTv.setPadding(dp(4), dp(2), dp(4), 0);
        Runnable refreshLogSize = () -> {
            long bytes = TaskRunLogger.getLogDirSize(this);
            String s2 = bytes < 1024 ? bytes + "B"
                    : bytes < 1048576
                    ? String.format("%.1fKB", bytes / 1024.0)
                    : String.format("%.1fMB", bytes / 1048576.0);
            logSizeTv.setText("占用 " + s2 + "（自动保留 30 天内/最多 200 个）");
        };
        refreshLogSize.run();
        logCard.addView(logSizeTv);
        LinearLayout logOptRow = new LinearLayout(this);
        logOptRow.setOrientation(LinearLayout.HORIZONTAL);
        logOptRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView keepLabel = new TextView(this);
        keepLabel.setText("保留天数：");
        keepLabel.setTextSize(12);
        logOptRow.addView(keepLabel);
        EditText keepEt = new EditText(this);
        keepEt.setTextSize(12);
        keepEt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        keepEt.setText(String.valueOf(getSharedPreferences("agent",
                MODE_PRIVATE).getInt("log_keep_days", 30)));
        keepEt.setEms(4);
        keepEt.setOnFocusChangeListener((v, has) -> {
            if (!has) {
                try {
                    int d = Integer.parseInt(keepEt.getText().toString());
                    if (d < 1) d = 1;
                    if (d > 365) d = 365;
                    getSharedPreferences("agent", MODE_PRIVATE).edit()
                            .putInt("log_keep_days", d).apply();
                    TaskRunLogger.cleanupOldLogs(this);
                    refreshLogSize.run();
                    log("日志保留天数 → " + d);
                } catch (Exception ignored) {
                }
            }
        });
        logOptRow.addView(keepEt);
        Button bClean = smallBtn("🧹 一键清理过期");
        bClean.setOnClickListener(v -> {
            TaskRunLogger.cleanupOldLogs(this);
            refreshLogSize.run();
            showTaskLogs();
            log("已清理过期日志");
        });
        logOptRow.addView(bClean);
        logCard.addView(logOptRow);
        inner.addView(logCard);

        // v3.13 Issue 3：热补丁管理
        LinearLayout patchCard = card();
        LinearLayout patchHeader = new LinearLayout(this);
        patchHeader.setOrientation(LinearLayout.HORIZONTAL);
        patchHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView patchTitle = new TextView(this);
        patchTitle.setText("🩹 热补丁");
        patchTitle.setTextSize(16);
        patchTitle.setTypeface(patchTitle.getTypeface(),
                android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams patchLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        patchHeader.addView(patchTitle, patchLp);
        patchCard.addView(patchHeader);
        TextView patchStatus = new TextView(this);
        patchStatus.setTextSize(12);
        patchStatus.setTextColor(DyColor.onSurfaceVariant(this));
        Runnable refreshPatch = () -> {
            String tag = PatchManager.loadedTag(this);
            patchStatus.setText("当前补丁：" + (tag.isEmpty() ? "无" : tag)
                    + (PatchManager.hasBackup(this) ? "（有备份可回滚）" : ""));
        };
        refreshPatch.run();
        patchCard.addView(patchStatus);
        android.widget.CheckBox patchEnableBox =
                new android.widget.CheckBox(this);
        patchEnableBox.setText("启用热补丁（关闭后重启生效）");
        patchEnableBox.setTextSize(12);
        patchEnableBox.setChecked(getSharedPreferences("agent", MODE_PRIVATE)
                .getBoolean("patch_enabled", true));
        patchEnableBox.setOnCheckedChangeListener((v, on) -> {
            getSharedPreferences("agent", MODE_PRIVATE).edit()
                    .putBoolean("patch_enabled", on).apply();
            log("热补丁 " + (on ? "已启用" : "已禁用") + "（重启 App 生效）");
        });
        patchCard.addView(patchEnableBox);
        Button bRollback = smallBtn("↩️ 回滚上一个补丁");
        bRollback.setOnClickListener(v -> {
            if (!PatchManager.hasBackup(this)) {
                log("没有可回滚的备份");
                return;
            }
            new android.app.AlertDialog.Builder(this)
                    .setTitle("回滚热补丁？")
                    .setMessage("将恢复到上一个补丁，重启 App 后生效。")
                    .setPositiveButton("回滚", (d, w) -> {
                        String err = PatchManager.rollbackPatch(this);
                        if (err == null) {
                            refreshPatch.run();
                            log("已回滚，重启 App 生效");
                        } else {
                            log(err);
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        patchCard.addView(bRollback);
        inner.addView(patchCard);

        // v3.11 Issue 8：记忆管理
        LinearLayout memCard = card();
        LinearLayout memHeader = new LinearLayout(this);
        memHeader.setOrientation(LinearLayout.HORIZONTAL);
        memHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView memTitle = new TextView(this);
        memTitle.setText("🧠 记忆管理");
        memTitle.setTextSize(16);
        memTitle.setTypeface(memTitle.getTypeface(),
                android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams memLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        memHeader.addView(memTitle, memLp);
        Button bViewMem = smallBtn("查看");
        bViewMem.setOnClickListener(v -> showMemoryList());
        memHeader.addView(bViewMem);
        memCard.addView(memHeader);
        memCard.addView(grayText("本地存储，不上传。App 记住的偏好和事实都在这里。"));
        inner.addView(memCard);


        // v2.62：更多设置（可折叠）——监管者开关放这里，缩小
        LinearLayout moreCard = card();
        LinearLayout moreHeader = new LinearLayout(this);
        moreHeader.setOrientation(LinearLayout.HORIZONTAL);
        moreHeader.setGravity(Gravity.CENTER_VERTICAL);
        moreHeader.setClickable(true);
        moreHeader.setFocusable(true);
        moreHeader.setPadding(dp(4), dp(4), dp(4), dp(4));
        TextView moreTitle = new TextView(this);
        moreTitle.setText("更多设置");
        moreTitle.setTextSize(13);
        moreTitle.setTypeface(moreTitle.getTypeface(),
                android.graphics.Typeface.BOLD);
        moreTitle.setTextColor(DyColor.onSurface(this));
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        moreHeader.addView(moreTitle, moreLp);
        final TextView moreArrow = new TextView(this);
        moreArrow.setTextSize(14);
        moreArrow.setTextColor(DyColor.onSurfaceVariant(this));
        moreHeader.addView(moreArrow);
        moreCard.addView(moreHeader);
        LinearLayout moreBody = new LinearLayout(this);
        moreBody.setOrientation(LinearLayout.VERTICAL);
        moreCard.addView(moreBody);
        final boolean[] moreOpen = {false};
        Runnable paintMore = () -> moreArrow.setText(moreOpen[0] ? "▾" : "▸");
        moreHeader.setOnClickListener(v -> {
            moreOpen[0] = !moreOpen[0];
            moreBody.setVisibility(moreOpen[0] ? View.VISIBLE : View.GONE);
            paintMore.run();
        });
        moreBody.setVisibility(View.GONE);
        paintMore.run();
        // v2.67：高级动作收纳到更多设置（缩小）
        agentAdvBox = new android.widget.CheckBox(this);
        agentAdvBox.setText("🔧 高级动作（shell / 文件读写 / 抓网页）");
        agentAdvBox.setTextSize(10);
        agentAdvBox.setTextColor(DyColor.onSurfaceVariant(this));
        agentAdvBox.setChecked(
                getSharedPreferences("agent", MODE_PRIVATE).getBoolean("adv", true));
        agentAdvBox.setOnCheckedChangeListener((v, on) ->
                getSharedPreferences("agent", MODE_PRIVATE).edit()
                        .putBoolean("adv", on).apply());
        agentAdvBox.setPadding(dp(4), 0, dp(4), dp(4));
        moreBody.addView(agentAdvBox);
        // v3.10 Issue 4：远程高危动作策略
        TextView remoteRiskLabel = new TextView(this);
        remoteRiskLabel.setText("🛡️ 远程高危动作策略（HTTP/TG）");
        remoteRiskLabel.setTextSize(10);
        remoteRiskLabel.setTextColor(DyColor.onSurfaceVariant(this));
        remoteRiskLabel.setPadding(dp(4), dp(4), dp(4), 0);
        moreBody.addView(remoteRiskLabel);
        android.widget.Spinner remoteRiskSpinner = new android.widget.Spinner(this);
        String[] riskOptions = {"一律拒绝（默认）", "允许但记录"};
        String[] riskValues = {"deny", "allow_log"};
        android.widget.ArrayAdapter<String> riskAdapter =
                new android.widget.ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_item, riskOptions);
        riskAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        remoteRiskSpinner.setAdapter(riskAdapter);
        String curRisk = getSharedPreferences("agent", MODE_PRIVATE)
                .getString("remote_risk_policy", "deny");
        for (int i = 0; i < riskValues.length; i++) {
            if (riskValues[i].equals(curRisk)) {
                remoteRiskSpinner.setSelection(i);
                break;
            }
        }
        remoteRiskSpinner.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            android.widget.AdapterView<?> p, android.view.View v,
                            int pos, long id) {
                        getSharedPreferences("agent", MODE_PRIVATE).edit()
                                .putString("remote_risk_policy", riskValues[pos])
                                .apply();
                        log("远程高危策略 → " + riskOptions[pos]);
                    }
                    @Override
                    public void onNothingSelected(
                            android.widget.AdapterView<?> p) {}
                });
        moreBody.addView(remoteRiskSpinner);
        // v3.14：审批全局悬浮窗开关
        android.widget.CheckBox approveOverlayBox =
                new android.widget.CheckBox(this);
        approveOverlayBox.setText("🛡 审批用全局悬浮窗（虚拟屏任务时主屏弹窗）");
        approveOverlayBox.setTextSize(10);
        approveOverlayBox.setTextColor(DyColor.onSurfaceVariant(this));
        approveOverlayBox.setChecked(
                getSharedPreferences("agent", MODE_PRIVATE)
                        .getBoolean("approve_overlay", true));
        approveOverlayBox.setOnCheckedChangeListener((v, on) -> {
            getSharedPreferences("agent", MODE_PRIVATE).edit()
                    .putBoolean("approve_overlay", on).apply();
            if (on && !ApproveOverlay.canShow(this)) {
                new android.app.AlertDialog.Builder(this)
                        .setTitle("需要悬浮窗权限")
                        .setMessage("全局审批弹窗需要“悬浮窗”权限，去开启吗？")
                        .setPositiveButton("去开启",
                                (d, w) -> ApproveOverlay.openPermissionSettings(this))
                        .setNegativeButton("稍后", null)
                        .show();
            }
            log("全局审批悬浮窗 " + (on ? "已开启" : "已关闭"));
        });
        approveOverlayBox.setPadding(dp(4), dp(4), dp(4), dp(4));
        moreBody.addView(approveOverlayBox);
        // v3.14：任务默认虚拟屏开关
        android.widget.CheckBox vdDefaultBox =
                new android.widget.CheckBox(this);
        vdDefaultBox.setText("🖥 任务默认在虚拟屏运行（需 root，主屏不受打扰）");
        vdDefaultBox.setTextSize(10);
        vdDefaultBox.setTextColor(DyColor.onSurfaceVariant(this));
        vdDefaultBox.setChecked(
                getSharedPreferences("agent", MODE_PRIVATE)
                        .getBoolean("task_vd_default", true));
        vdDefaultBox.setOnCheckedChangeListener((v, on) ->
                getSharedPreferences("agent", MODE_PRIVATE).edit()
                        .putBoolean("task_vd_default", on).apply());
        vdDefaultBox.setPadding(dp(4), 0, dp(4), dp(4));
        moreBody.addView(vdDefaultBox);
        // v3.48：待机光效「M 八爪鱼」开关
        android.widget.CheckBox petBox =
                new android.widget.CheckBox(this);
        petBox.setText("🐙 待机光效「M八爪鱼」（空闲时在桌面沿光轨活动）");
        petBox.setTextSize(10);
        petBox.setTextColor(DyColor.onSurfaceVariant(this));
        petBox.setChecked(
                getSharedPreferences("agent", MODE_PRIVATE)
                        .getBoolean("pet_enabled", true));
        petBox.setOnCheckedChangeListener((v, on) -> {
            getSharedPreferences("agent", MODE_PRIVATE).edit()
                    .putBoolean("pet_enabled", on).apply();
            if (on && !FloatWindow.hasPermission(this)) {
                FloatWindow.requestPermission(this);
            }
        });
        petBox.setPadding(dp(4), 0, dp(4), dp(4));
        moreBody.addView(petBox);
        android.widget.CheckBox petDeskBox =
                new android.widget.CheckBox(this);
        petDeskBox.setText("🐙 八爪鱼仅在桌面显示（关掉则任何界面空闲时都可能出现）");
        petDeskBox.setTextSize(10);
        petDeskBox.setTextColor(DyColor.onSurfaceVariant(this));
        petDeskBox.setChecked(
                getSharedPreferences("agent", MODE_PRIVATE)
                        .getBoolean("pet_desktop_only", true));
        petDeskBox.setOnCheckedChangeListener((v, on) ->
                getSharedPreferences("agent", MODE_PRIVATE).edit()
                        .putBoolean("pet_desktop_only", on).apply());
        petDeskBox.setPadding(dp(4), 0, dp(4), dp(4));
        moreBody.addView(petDeskBox);
        // 监管者开关：默认开启，可关闭，设置持久化
        agentSupervisorBox = new android.widget.CheckBox(this);
        agentSupervisorBox.setText("🔍 监管者复核（任务结束后独立判定完成度）");
        agentSupervisorBox.setTextSize(10);
        agentSupervisorBox.setTextColor(DyColor.onSurfaceVariant(this));
        agentSupervisorBox.setChecked(
                getSharedPreferences("agent", MODE_PRIVATE).getBoolean(
                        "supervisor", true));
        agentSupervisorBox.setOnCheckedChangeListener((v, on) ->
                getSharedPreferences("agent", MODE_PRIVATE).edit()
                        .putBoolean("supervisor", on).apply());
        agentSupervisorBox.setPadding(dp(4), 0, dp(4), dp(4));
        moreBody.addView(agentSupervisorBox);
        // v2.79：启动自动开启远控服务开关
        android.widget.CheckBox autoRemoteBox =
                new android.widget.CheckBox(this);
        autoRemoteBox.setText("🚀 启动自动开启远控服务");
        autoRemoteBox.setTextSize(10);
        autoRemoteBox.setTextColor(DyColor.onSurfaceVariant(this));
        autoRemoteBox.setChecked(
                getSharedPreferences("agent", MODE_PRIVATE).getBoolean(
                        "auto_remote", true));
        autoRemoteBox.setOnCheckedChangeListener((v, on) ->
                getSharedPreferences("agent", MODE_PRIVATE).edit()
                        .putBoolean("auto_remote", on).apply());
        autoRemoteBox.setPadding(dp(4), 0, dp(4), dp(4));
        moreBody.addView(autoRemoteBox);
        inner.addView(moreCard);

        LinearLayout profileCard = card();
        LinearLayout profileHeader = new LinearLayout(this);
        profileHeader.setOrientation(LinearLayout.HORIZONTAL);
        profileHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView profileTitle = new TextView(this);
        profileTitle.setText("用户画像");
        profileTitle.setTextSize(18);
        profileTitle.setTypeface(profileTitle.getTypeface(),
                android.graphics.Typeface.BOLD);
        profileTitle.setTextColor(DyColor.onSurface(this));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        profileHeader.addView(profileTitle, plp);
        Button bRefreshProfile = smallBtn("🔄 刷新");
        bRefreshProfile.setOnClickListener(v -> refreshUserProfile(true));
        profileHeader.addView(bRefreshProfile);
        profileCard.addView(profileHeader);
        profileCard.addView(grayText("智能体从对话和任务中自动总结，不定时更新"));
        userProfileBox = new LinearLayout(this);
        userProfileBox.setOrientation(LinearLayout.VERTICAL);
        profileCard.addView(userProfileBox);
        inner.addView(profileCard);

        LinearLayout useCard = card();
        useCard.addView(cardTitle("使用方法"));
        TextView use = new TextView(this);
        use.setTextSize(14);
        use.setTextColor(TEXT_DARK);
        use.setLineSpacing(dp(4), 1f);
        use.setText(usageGuideText());
        
        useCard.addView(use);
        inner.addView(useCard);

        ScrollView wrap = new ScrollView(this);
        wrap.addView(inner);
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.addView(wrap, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return outer;
    }

    // ---------- 应用内更新 ----------

    private void doCheckUpdate() {
        updStatus.setText("正在检查更新…");
        UpdateManager.check(this,
                (hasUpdate, version, code, apkUrl, notes, err) ->
                        runOnUiThread(() -> {
                            if (err != null) {
                                updStatus.setText(err);
                                return;
                            }
                            if (!hasUpdate) {
                                updStatus.setText("已是最新（" + AppInfo.VERSION + "）");
                                return;
                            }
                            updStatus.setText("发现新版 " + version);
                            new AlertDialog.Builder(this)
                                    .setTitle("发现新版 " + version)
                                    .setMessage((notes == null || notes.isEmpty())
                                            ? "是否下载安装？"
                                            : notes + "\n\n是否下载安装？")
                                    .setPositiveButton("下载",
                                            (d, w) -> downloadUpdate(apkUrl, version))
                                    .setNegativeButton("稍后", null)
                                    .show();
                        }));
    }

    private void downloadUpdate(String apkUrl, String version) {
        updProgress.setVisibility(View.VISIBLE);
        updProgress.setProgress(0);
        updStatus.setText("正在下载 " + version + "…");
        UpdateManager.download(this, apkUrl, new UpdateManager.DownloadListener() {
            @Override
            public void onProgress(int p) {
                runOnUiThread(() -> updProgress.setProgress(p));
            }

            @Override
            public void onDone(java.io.File apk, String err) {
                runOnUiThread(() -> {
                    updProgress.setVisibility(View.GONE);
                    if (err != null) {
                        updStatus.setText(err);
                        return;
                    }
                    updStatus.setText("下载完成，准备安装…");
                    String r = UpdateManager.installApk(MainActivity.this, apk);
                    if ("silent_ok".equals(r)) {
                        updStatus.setText("已静默安装，服务重启后即是新版"
                                + "（自检卡可查更新回执）");
                    } else if ("need_permission".equals(r)) {
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("需要安装权限")
                                .setMessage("请在系统设置中允许本应用“安装未知应用”，"
                                        + "然后重新检查更新。")
                                .setPositiveButton("去设置", (d, w) ->
                                        UpdateManager.openUnknownSourcesSetting(
                                                MainActivity.this))
                                .setNegativeButton("取消", null)
                                .show();
                        updStatus.setText("需要“安装未知应用”权限");
                    } else if (r != null) {
                        updStatus.setText("调起安装失败：" + r);
                    } else {
                        updStatus.setText("已调起系统安装");
                    }
                });
            }
        });
    }

    private void showUpdateSourceDialog() {
        final EditText et = new EditText(this);
        et.setText(UpdateManager.getSource(this));
        et.setHint("https://…/version.json");
        et.setTextSize(13);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(20), dp(12), dp(20), dp(4));
        wrap.addView(et, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("更新源")
                .setMessage("version.json 地址（由发布者提供）：\n"
                        + "{\"version\":\"2.43\",\"versionCode\":68,"
                        + "\"apkUrl\":\"https://…apk\",\"notes\":\"更新说明\"}")
                .setView(wrap)
                .setPositiveButton("保存", (d, w) -> {
                    UpdateManager.setSource(this, et.getText().toString());
                    updStatus.setText("更新源已保存，点“检查更新”试试。");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------- Tab 切换 ----------

    // v2.64：tabBar 显隐（输入时自动隐藏）
    // v2.83：加动画取消+状态防抖，防输入框和 tab 打架
    private boolean tabBarHidden = false;

    private void hideTabBar() {
        if (tabBarHidden) return;
        tabBarHidden = true;
        if (tabBarView != null && tabBarView.getVisibility() == View.VISIBLE) {
            tabBarView.animate().cancel();
            tabBarView.animate().translationY(dp(80)).alpha(0f)
                    .setDuration(200)
                    .withEndAction(() -> tabBarView.setVisibility(View.GONE));
            if (contentView != null) {
                contentView.setPadding(0, 0, 0, 0);
                contentView.requestLayout();
            }
        }
    }

    private void showTabBar() {
        if (!tabBarHidden) return;
        tabBarHidden = false;
        if (tabBarView != null && tabBarView.getVisibility() != View.VISIBLE) {
            tabBarView.animate().cancel();
            tabBarView.setVisibility(View.VISIBLE);
            tabBarView.animate().translationY(0).alpha(1f).setDuration(200);
            // v2.92：内容可滑到 tab 下方，不留白
            if (contentView != null) {
                contentView.setPadding(0, 0, 0, 0);
                contentView.requestLayout();
            }
        }
    }

    // v3.01：tab 显示防抖（键盘检测抖动导致莫名出现）
    private Runnable pendingShowTab = null;

    private void showTabBarDebounced() {
        // 取消之前的待显示
        if (pendingShowTab != null && tabBarView != null) {
            tabBarView.removeCallbacks(pendingShowTab);
        }
        pendingShowTab = () -> {
            // 延迟 300ms 后再次确认键盘真的收起了
            View decor = getWindow().getDecorView();
            android.graphics.Rect r = new android.graphics.Rect();
            decor.getWindowVisibleDisplayFrame(r);
            int screenH = decor.getRootView().getHeight();
            int kbH = screenH - r.bottom;
            if (kbH <= screenH * 0.15) {
                showTabBar();
            }
        };
        if (tabBarView != null) {
            tabBarView.postDelayed(pendingShowTab, 300);
        }
    }

    // v2.61：返回键层级——非主页先回主页，主页双击退出
    private boolean backPressedOnce = false;

    @Override
    public void onBackPressed() {
        int cur = getSharedPreferences("ui", MODE_PRIVATE).getInt("tab", 0);
        if (cur != 0) {
            showPage(0);
            return;
        }
        if (backPressedOnce) {
            super.onBackPressed();
            return;
        }
        backPressedOnce = true;
        android.widget.Toast.makeText(this, "再按一次退出",
                android.widget.Toast.LENGTH_SHORT).show();
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(() -> backPressedOnce = false, 2000);
    }

    private void showPage(int idx) {
        getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("tab", idx).apply();
        String[] tabNames = {"主页", "远控", "智能体", "终端", "我的"};
        if (behaviorLog != null && idx >= 0 && idx < tabNames.length) {
            behaviorLog.user("切换标签页", tabNames[idx]);
        }
        pageHome.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        pageRemote.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        pageAgent.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        pageTerm.setVisibility(idx == 3 ? View.VISIBLE : View.GONE);
        pageMy.setVisibility(idx == 4 ? View.VISIBLE : View.GONE);
        tabHome.setActive(idx == 0);
        tabRemote.setActive(idx == 1);
        tabAgent.setActive(idx == 2);
        tabTerm.setActive(idx == 3);
        tabMy.setActive(idx == 4);
        // v2.86：切到智能体页时滚到底（别停在顶部看旧消息）
        if (idx == 2 && agentScrollView != null) {
            agentScrollView.post(() -> agentScrollView.fullScroll(
                    android.view.View.FOCUS_DOWN));
        }
    }

    // v3.58：tab 图标改单色细线条矢量绘制（TabIconView），选中态胶囊底由图标视图自绘
    private TabIconView makeTab(int kind, int idx) {
        TabIconView b = new TabIconView(this, kind);
        // 无障碍：读屏时报文字名
        String[] names = {"主页", "远控", "思考执行员", "终端", "我的"};
        if (idx >= 0 && idx < names.length) b.setContentDescription(names[idx]);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, dp(46), 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> showPage(idx));
        return b;
    }

    private void refreshStatus() {
        boolean ok = AgentAccessibilityService.isRunning();
        statusView.setText("无障碍服务：" + (ok ? "已开启 ✓" : "未开启 ✗"));
        statusView.setTextColor(ok ? GREEN : RED);
        boolean svc = AgentService.isRunning();
        svcStatusView.setText("远程服务：" + (svc ? "运行中 ✓（定时任务可用）" : "未启动"));
        svcStatusView.setTextColor(svc ? GREEN : TEXT_GRAY);
        // USB 调试 (ADB)
        boolean adbOn = android.provider.Settings.Global.getInt(
                getContentResolver(), "adb_enabled", 0) == 1;
        adbStatusView.setText("USB 调试 (ADB)：" + (adbOn ? "已开启 ✓" : "未开启 ✗"));
        adbStatusView.setTextColor(adbOn ? GREEN : TEXT_GRAY);
        // Shizuku
        boolean shizuku = isPackageInstalled("moe.shizuku.privileged.api");
        shizukuStatusView.setText("Shizuku：" + (shizuku ? "已安装 ✓" : "未安装 ✗"));
        shizukuStatusView.setTextColor(shizuku ? GREEN : TEXT_GRAY);
        // 无线调试 (Android 11+)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            boolean wifiAdb = android.provider.Settings.Global.getInt(
                    getContentResolver(), "adb_wifi_enabled", 0) == 1;
            wifiAdbStatusView.setText("无线调试：" + (wifiAdb ? "已开启 ✓" : "未开启 ✗"));
            wifiAdbStatusView.setTextColor(wifiAdb ? GREEN : TEXT_GRAY);
        } else {
            wifiAdbStatusView.setText("无线调试：需 Android 11+");
            wifiAdbStatusView.setTextColor(TEXT_GRAY);
        }
    }

    private boolean isPackageInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshSvc() {
        String token = new ApiHandler(this).getToken();
        int port = AgentService.getPort(this);
        svcInfo.setText("服务：" + (AgentService.isRunning() ? "运行中 ✓" : "未启动")
                + "\n端口：" + port
                + "\nToken：" + token
                + "\n本机 IP：" + localIps());
        if (remoteSvcSub != null) {
            remoteSvcSub.setText("HTTP 服务开关（端口 " + port + "）");
        }
        if (apiCardTitle != null) {
            apiCardTitle.setText("API 接口（端口 " + port + "，token 鉴权）");
        }
        refreshMuseCard(token);
    }

    /** Tailscale IP（云端可达），未安装/未连接返回 null */
    private String tailscaleIp() {
        try {
            for (java.net.NetworkInterface ni :
                    java.util.Collections.list(
                            java.net.NetworkInterface.getNetworkInterfaces())) {
                String n = ni.getName().toLowerCase();
                for (java.net.InetAddress a :
                        java.util.Collections.list(ni.getInetAddresses())) {
                    if (a.isLoopbackAddress()
                            || !(a instanceof java.net.Inet4Address)) continue;
                    String ip = a.getHostAddress();
                    // 安卓上 Tailscale 走系统 VPN（网卡多为 tun0），
                    // 但其 IP 恒为 100.64.0.0/10（CGNAT 段），以此判定最可靠
                    if (n.contains("tailscale") || isCgnat(ip)) return ip;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 是否为 100.64.0.0/10（Tailscale 专用网段） */
    static boolean isCgnat(String ip) {
        try {
            String[] p = ip.split("\\.");
            if (p.length != 4 || !p[0].equals("100")) return false;
            int b = Integer.parseInt(p[1]);
            return b >= 64 && b <= 127;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isTailscaleAppInstalled() {
        try {
            getPackageManager().getPackageInfo("com.tailscale.ipn", 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshMuseCard(String token) {
        if (museTsStatus == null) return;
        String tsIp = tailscaleIp();
        boolean appInstalled = isTailscaleAppInstalled();
        if (tsIp != null) {
            museTsStatus.setText("Tailscale：已连接 ✓（" + tsIp + "，云端可达）");
            museTsStatus.setTextColor(GREEN);
            if (museHelp != null) museHelp.setVisibility(View.GONE);
        } else {
            museTsStatus.setText("Tailscale：未检测到（云端暂不可达）");
            museTsStatus.setTextColor(RED);
            if (museHelp != null) museHelp.setVisibility(View.VISIBLE);
        }
        if (museTsAppBtn != null) {
            museTsAppBtn.setText(appInstalled ? "打开 Tailscale" : "安装 Tailscale");
        }
    }

    /** 生成发给云端 Muse 的连接信息文本（含 token，用户主动复制发出） */
    private String buildMuseConnectInfo() {
        String token = new ApiHandler(this).getToken();
        String tsIp = tailscaleIp();
        StringBuilder sb = new StringBuilder();
        sb.append("GSIC · 云端操控连接信息\n");
        int port = AgentService.getPort(this);
        if (tsIp != null) {
            sb.append("服务地址：http://").append(tsIp).append(":").append(port).append("\n");
        } else {
            sb.append("服务地址：http://").append(firstLocalIp())
                    .append(":").append(port).append("（局域网，需先装 Tailscale）\n");
        }
        sb.append("Token：").append(token).append("\n");
        sb.append("接口：GET /status ｜ GET /screen ｜ GET /screenshot ｜ POST /action ｜ POST /task\n");
        sb.append("鉴权：URL 参数 ?token= 或请求头 X-Token");
        return sb.toString();
    }

    private String firstLocalIp() {
        String ips = localIps();
        int i = ips.indexOf('，');
        return i > 0 ? ips.substring(0, i) : ips;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!TermsGate.isAccepted(this)) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("GSIC使用协议")
                    .setMessage(TermsGate.AGREEMENT_TEXT)
                    .setCancelable(false)
                    .setPositiveButton("同意", (d, w) -> { TermsGate.accept(this); d.dismiss(); })
                    .setNegativeButton("不同意", (d, w) -> { finish(); })
                    .show();
        }

        skillStore = new SkillStore(this);
        historyStore = new HistoryStore(this);
        scheduleStore = new ScheduleStore(this);
        bootstrapManager = new BootstrapManager(this);
        llmStore = new LlmStore(this);
        llmProvider = llmStore.getProvider();
        tgStore = new TgStore(this);
        riskPolicy = new ActionRisk.Policy(this);

        // v2.79：启动默认开启远控服务（可在更多设置关闭）
        boolean autoRemote = getSharedPreferences("agent", MODE_PRIVATE)
                .getBoolean("auto_remote", true);
        if (autoRemote && !AgentService.isRunning()) {
            Intent svcIt = new Intent(this, AgentService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(svcIt);
            } else {
                startService(svcIt);
            }
        }

        // v2.61：根布局改 FrameLayout，底部 tab 改悬浮胶囊样式
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(0xFFF7F7F7);

        LinearLayout content = new LinearLayout(this);
        contentView = content;
        content.setOrientation(LinearLayout.VERTICAL);
        // v2.92：tab 悬浮，内容可滑到 tab 下方（tab 半透明）
        content.setPadding(0, 0, 0, 0);

        LinearLayout appbar = new LinearLayout(this);
        appbar.setBackgroundColor(ACCENT);
        TextView abTitle = new TextView(this);
        abTitle.setText("GSIC");
        abTitle.setTextColor(Color.WHITE);
        abTitle.setTextSize(20);
        abTitle.setPadding(dp(16), dp(12), dp(16), dp(12));
        appbar.addView(abTitle);
        content.addView(appbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        chatStore = new ChatStore(this);
        userProfile = new UserProfile(this);
        captureStore = new CaptureStore(this);
        auxModelStore = new AuxModelStore(this);
        behaviorLog = new BehaviorLog(this);
        pageHome = buildHomePage();
        pageRemote = buildRemotePage();
        pageAgent = buildAgentPage();
        pageTerm = buildTermPage();
        pageMy = buildMyPage();
        content.addView(pageHome, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        content.addView(pageRemote, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        content.addView(pageAgent, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        content.addView(pageTerm, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        content.addView(pageMy, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // v2.61：悬浮胶囊 tabBar
        root.addView(content, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout tabBar = new LinearLayout(this);
        tabBarView = tabBar;
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setPadding(dp(6), dp(6), dp(6), dp(6));
        // 胶囊背景：圆角 + 阴影 + 半透明毛玻璃（v2.87）
        android.graphics.drawable.GradientDrawable capsuleBg =
                new android.graphics.drawable.GradientDrawable();
        capsuleBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        capsuleBg.setCornerRadius(dp(28));
        capsuleBg.setColor(0xD9FFFFFF); // 85% 白，半透明
        tabBar.setBackground(capsuleBg);
        tabBar.setElevation(dp(8));
        tabHome = makeTab(TabIconView.HOME, 0);
        tabRemote = makeTab(TabIconView.REMOTE, 1);
        tabAgent = makeTab(TabIconView.AGENT, 2);
        tabTerm = makeTab(TabIconView.TERM, 3);
        tabMy = makeTab(TabIconView.MY, 4);
        tabBar.addView(tabHome);
        tabBar.addView(tabRemote);
        tabBar.addView(tabAgent);
        tabBar.addView(tabTerm);
        tabBar.addView(tabMy);
        android.widget.FrameLayout.LayoutParams tabLp =
                new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        tabLp.setMargins(dp(16), 0, dp(16), dp(16));
        root.addView(tabBar, tabLp);

        setContentView(root);
        // v3.52：内嵌彩条挂到根布局最上层（不消费触摸，纯显示）
        try {
            inAppFlowDrawable = new FlowLightDrawable(
                    android.graphics.Color.TRANSPARENT, 0xFF40C4FF, 0,
                    getResources().getDisplayMetrics().density);
            inAppFlowStripe = new android.view.View(this);
            inAppFlowStripe.setBackground(inAppFlowDrawable);
            inAppFlowStripe.setVisibility(android.view.View.GONE);
            root.addView(inAppFlowStripe,
                    new android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        } catch (Exception ignored) {
        }
        int lastTab = getSharedPreferences("ui", MODE_PRIVATE).getInt("tab", 0);
        if (lastTab < 0 || lastTab > 4) lastTab = 0;
        showPage(lastTab);
        refreshAll();

        // v3.05：TG 自启已移到 AgentService.onCreate，这里只刷新状态
        refreshTg();

        // v2.50：无 root 不显示终端 tab（Termux 挂载需要 root；后台检测不卡 UI）
        new Thread(() -> {
            boolean hasRoot = Root.findSu() != null;
            if (!hasRoot) {
                runOnUiThread(() -> {
                    tabTerm.setVisibility(View.GONE);
                    int cur = getSharedPreferences("ui", MODE_PRIVATE).getInt("tab", 0);
                    if (cur == 3) showPage(0);
                });
            }
        }).start();

        // 输入法弹起时：终端页可见且输入行被键盘遮挡，自动上滚让输入框露出来
        getWindow().getDecorView().getViewTreeObserver()
                .addOnGlobalLayoutListener(() -> {
                    Rect r = new Rect();
                    View decor = getWindow().getDecorView();
                    decor.getWindowVisibleDisplayFrame(r);
                    int screenH = decor.getRootView().getHeight();
                    int kbH = screenH - r.bottom;
                    boolean kbVisible = kbH > screenH * 0.15;
                    // v2.83：只认键盘显隐（删掉焦点双保险，防打架）
                    // v3.01：显示加防抖，防莫名出现
                    if (kbVisible) {
                        // 键盘弹起：取消待显示，立即隐藏
                        if (pendingShowTab != null && tabBarView != null) {
                            tabBarView.removeCallbacks(pendingShowTab);
                        }
                        hideTabBar();
                    } else {
                        showTabBarDebounced();
                    }
                    if (kbVisible
                            && pageTerm != null
                            && pageTerm.getVisibility() == View.VISIBLE
                            && termInputRow != null
                            && termInputRow.getVisibility() == View.VISIBLE
                            && termPageScroll != null) {
                        termPageScroll.post(() -> {
                            int[] loc = new int[2];
                            termInputRow.getLocationOnScreen(loc);
                            int rowBottom = loc[1] + termInputRow.getHeight();
                            if (rowBottom > r.bottom) {
                                termPageScroll.smoothScrollBy(0,
                                        rowBottom - r.bottom + dp(12));
                            }
                        });
                    }
                });
    }

    private void refreshAll() {
        refreshStatus();
        refreshSvc();
        refreshSkills();
        refreshSkillCards();
        refreshSchedules();
        refreshHistory();
        refreshTermuxEnv();
        refreshLlm();
        refreshLlmProfiles();
        renderUserProfile();
        refreshTg();
    }

    private void refreshLlm() {
        if (llmBaseUrl == null) return;
        // 用户正在输入的框不覆盖，避免 onResume 时冲掉未保存的输入
        if (!llmBaseUrl.hasFocus()) llmBaseUrl.setText(llmStore.getBaseUrl());
        if (!llmModel.hasFocus()) llmModel.setText(llmStore.getModel());
        if (llmVisionModel != null && !llmVisionModel.hasFocus())
            llmVisionModel.setText(llmStore.getVisionModel());
        if (!llmApiKey.hasFocus()) {
            llmApiKey.setText("");
            llmApiKey.setHint("api_key（" + llmStore.getMaskedKey() + "，留空保留）");
        }
        llmStatus.setText(llmStore.hasKey()
                ? "当前：" + llmStore.getActiveName() + "（" + llmProvider + "）" : "未配置");
        llmStatus.setTextColor(llmStore.hasKey() ? GREEN : TEXT_GRAY);
        refreshAskModels();
        if (llmNameInput != null && !llmNameInput.hasFocus()) {
            llmNameInput.setText(llmStore.getActiveName());
        }
    }

    /** 刷新用户画像展示 */
    private void refreshUserProfile(boolean manual) {
        if (userProfileBox == null) return;
        if (manual) {
            // 手动刷新：后台重新总结
            log("正在重新总结用户画像…");
            new Thread(() -> {
                summarizeUserProfile();
                runOnUiThread(() -> {
                    renderUserProfile();
                    log("用户画像已更新");
                });
            }).start();
            return;
        }
        renderUserProfile();
    }

    private void renderUserProfile() {
        if (userProfileBox == null || userProfile == null) return;
        userProfileBox.removeAllViews();
        if (userProfile.isEmpty()) {
            userProfileBox.addView(grayText("暂无画像，完成几个任务后自动生成"));
            return;
        }
        if (!userProfile.summary.isEmpty()) {
            TextView t = new TextView(this);
            t.setText(userProfile.summary);
            t.setTextSize(14);
            t.setTextColor(DyColor.onSurface(this));
            t.setLineSpacing(dp(2), 1f);
            t.setPadding(0, dp(4), 0, dp(4));
            userProfileBox.addView(t);
        }
        addProfileSection("🎯 常用任务", userProfile.frequentTasks);
        addProfileSection("⭐ 偏好", userProfile.preferences);
        addProfileSection("💡 兴趣", userProfile.interests);
        addProfileSection("🔁 习惯", userProfile.habits);
        TextView ts = new TextView(this);
        ts.setText("更新于 " + userProfile.updatedStr());
        ts.setTextSize(11);
        ts.setTextColor(DyColor.onSurfaceVariant(this));
        ts.setPadding(0, dp(6), 0, 0);
        userProfileBox.addView(ts);
    }

    private void addProfileSection(String title, java.util.List<String> items) {
        if (items == null || items.isEmpty()) return;
        TextView t = new TextView(this);
        t.setText(title + "：" + String.join("、", items));
        t.setTextSize(13);
        t.setTextColor(DyColor.onSurfaceVariant(this));
        t.setPadding(0, dp(2), 0, dp(2));
        userProfileBox.addView(t);
    }

    /** 后台总结用户画像（LLM） */
    private void summarizeUserProfile() {
        try {
            if (!llmStore.hasKey()) return;
            // 收集：最近对话 + 最近任务 + 现有画像
            StringBuilder ctx = new StringBuilder();
            java.util.List<ChatStore.Msg> msgs = chatStore.currentMsgs();
            int start = Math.max(0, msgs.size() - 15);
            ctx.append("最近对话：\n");
            for (int i = start; i < msgs.size(); i++) {
                ChatStore.Msg m = msgs.get(i);
                String txt = m.text.length() > 60
                        ? m.text.substring(0, 60) + "…" : m.text;
                ctx.append("- ").append(m.role).append("：").append(txt).append("\n");
            }
            ctx.append("\n最近任务：\n");
            java.util.List<HistoryStore.Entry> hist = historyStore.list();
            for (int i = 0; i < Math.min(10, hist.size()); i++) {
                HistoryStore.Entry e = hist.get(i);
                ctx.append("- ").append(e.title)
                        .append(e.ok ? "（成功）" : "（未成功）").append("\n");
            }
            ctx.append("\n现有画像：\n").append(userProfile.describeForPrompt());
            String prompt = "你是用户画像分析师。根据以下智能体的记忆"
                    + "（对话、任务记录、现有画像），总结/更新用户画像。\n\n"
                    + ctx.toString()
                    + "\n\n只输出纯 JSON：\n"
                    + "{\"summary\":\"一句话总结这个用户\","
                    + "\"interests\":[\"兴趣1\"],"
                    + "\"habits\":[\"习惯1\"],"
                    + "\"preferences\":[\"偏好1\"],"
                    + "\"frequentTasks\":[\"常用任务1\"]}";
            LlmClient.Result r = llmStore.client().chat(prompt);
            if (r.ok) userProfile.updateFromJson(r.text);
        } catch (Exception ignored) {
        }
    }

    /** 刷新配置档案列表 */
    private void refreshLlmProfiles() {
        if (llmProfilesBox == null) return;
        llmProfilesBox.removeAllViews();
        java.util.List<LlmStore.Profile> list = llmStore.list();
        for (LlmStore.Profile pf : list) {
            final String pid = pf.id;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(8), dp(4), dp(8));
            row.setClickable(true);
            row.setFocusable(true);
            boolean active = pf.id.equals(llmStore.getActive().id);
            LlmStore.Profile supPf = llmStore.supervisorProfile();
            boolean isSup = supPf != null && pf.id.equals(supPf.id);
            TextView t = new TextView(this);
            t.setText((active ? "✓ " : "○ ") + pf.name
                    + "（" + pf.provider + "/" + pf.model + "）"
                    + (isSup ? " · 🛡监管" : ""));
            t.setTextSize(14);
            t.setTextColor(active ? DyColor.primary(this) : DyColor.onSurface(this));
            if (active) t.setTypeface(t.getTypeface(),
                    android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(t, lp);
            row.setOnClickListener(v -> {
                llmStore.setActive(pid);
                llmNewMode = false;
                LlmStore.Profile np = llmStore.getActive();
                llmProvider = np.provider;
                refreshLlm();
                refreshLlmProfiles();
                log("已切换：" + np.name);
            });
            row.setOnLongClickListener(v -> {
                llmStore.setSupervisorId(pid);
                refreshLlmProfiles();
                log("监管者模型已设为：" + pf.name + "（长按其他档案可改，默认主力）");
                return true;
            });
            llmProfilesBox.addView(row);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        inAppFlowHandler.removeCallbacks(inAppFlowTick);
        FloatWindow.setInApp(false);
        // 离开软件且任务在跑：补弹悬浮窗显示进度
        try {
            if (agentThread != null && agentThread.isAlive()
                    && !agentStop) {
                FloatWindow.assertShown();
            }
        } catch (Exception ignored) {
        }
    }

    protected void onResume() {
        super.onResume();
        // v3.52：内嵌彩条对表开始（首跳立即执行，回软件即见当前任务状态）
        inAppFlowHandler.removeCallbacks(inAppFlowTick);
        inAppFlowHandler.post(inAppFlowTick);
        new Thread(() -> SedimentReport.checkSchedule(this)).start();
        try {
            if (getIntent() != null && getIntent().getBooleanExtra("open_sediment", false)) {
                getIntent().removeExtra("open_sediment");
                showSediment();
            }
        } catch (Exception ignored) {
        }
        // 回到软件内：主动关闭任务悬浮窗（进度直接看界面）
        FloatWindow.setInApp(true);
        if (agentFloatHintRefresher != null) agentFloatHintRefresher.run();
        if (statusView != null) refreshAll();
        // 悬浮抓取返回：展示记录详情
        if (CaptureFloat.pendingCaptureId != null) {
            final String cid = CaptureFloat.pendingCaptureId;
            CaptureFloat.pendingCaptureId = null;
            if (cid != null) showCaptureRecord(cid);
            else log("抓取失败：无障碍未开启或界面为空");
        }
    }

    /** 输入栏流光：有流光=任务运行中 */
    private void startInputFlow() {
        if (agentInputBar == null) return;
        stopInputFlow();
        float density = getResources().getDisplayMetrics().density;
        agentInputFlow = new FlowLightDrawable(
                DyColor.surfaceVariant(this), DyColor.primary(this),
                24, density);
        agentInputBar.setBackground(agentInputFlow);
        agentInputFlow.start();
    }

    private void stopInputFlow() {
        try {
            if (agentInputFlow != null) agentInputFlow.stop();
        } catch (Exception ignored) {
        }
        agentInputFlow = null;
        if (agentInputBar != null && agentInputBg != null) {
            agentInputBar.setBackground(agentInputBg);
        }
    }

    /**
     * 超时/卡住时主动询问用户是否继续，并阐明原因。
     * 在后台线程调用，会阻塞等待用户选择。返回 true=继续。
     */
    /**
     * v3.50 第 3 步：低置信澄清对话框（agent 线程调用，UI 线程弹窗阻塞等待）。
     * 有选项时点选；无选项时让用户自由补充。返回拼好"（用户明确：…）"的重跑目标，
     * 用户取消返回 null（并置 agentStop）。
     */
    private String askClarifyGoal(String goal, AgentLoop loop) {
        if (agentStop) return null;
        final String question = loop.clarifyQuestion();
        final java.util.List<String> opts = loop.clarifyOptions();
        final String[] result = {null};
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        runOnUiThread(() -> {
            android.app.AlertDialog.Builder b =
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("❓ 需要确认一下")
                            .setMessage(question)
                            .setCancelable(false)
                            .setNegativeButton("取消任务", (d, w) -> {
                                result[0] = null;
                                agentStop = true;
                                latch.countDown();
                            });
            if (opts != null && !opts.isEmpty()) {
                final String[] items = opts.toArray(new String[0]);
                b.setItems(items, (d, which) -> {
                    result[0] = goal + "（用户明确：" + items[which] + "）";
                    latch.countDown();
                });
            } else {
                final android.widget.EditText et =
                        new android.widget.EditText(this);
                et.setHint("请补充说明…");
                et.setMinLines(2);
                b.setView(et);
                b.setPositiveButton("确定", (d, w) -> {
                    String t = et.getText().toString().trim();
                    if (t.isEmpty()) {
                        result[0] = null;
                        agentStop = true;
                    } else {
                        result[0] = goal + "（用户明确：" + t + "）";
                    }
                    latch.countDown();
                });
            }
            b.show();
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return result[0];
    }

    private boolean askUserContinue(String reason) {
        // v3.14：优先全局悬浮窗（虚拟屏任务时主屏也能决策）
        boolean useOverlay = getSharedPreferences("agent", MODE_PRIVATE)
                .getBoolean("approve_overlay", true);
        if (useOverlay && ApproveOverlay.canShow(this)) {
            int r = ApproveOverlay.askCustom(this, "⏳ 任务等待过长（全局）",
                    reason + "\n是否继续执行？",
                    new String[]{"继续执行", "停止任务"}, 1);
            if (r == 1) agentStop = true;
            boolean go = r == 0;
            log(go ? "用户选择继续执行" : "用户选择停止任务（全局悬浮窗）");
            return go;
        }
        final boolean[] result = {false};
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        runOnUiThread(() -> {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("⏳ 任务等待过长")
                    .setMessage(reason + "\n是否继续执行？")
                    .setPositiveButton("继续执行", (d, w) -> {
                        result[0] = true;
                        latch.countDown();
                    })
                    .setNegativeButton("停止任务", (d, w) -> {
                        result[0] = false;
                        agentStop = true;
                        latch.countDown();
                    })
                    .setCancelable(false)
                    .show();
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return result[0];
    }

    /** 刷新「动作审批」入口行的策略摘要 */
    private void refreshRiskRow() {
        if (riskSummaryView == null || riskPolicy == null) return;
        StringBuilder sb = new StringBuilder("🛡 动作审批：");
        for (ActionRisk.Level lv : ActionRisk.Level.values()) {
            sb.append(lv.label).append(ActionRisk.Policy.policyLabel(
                    riskPolicy.get(lv))).append(" · ");
        }
        String s = sb.toString();
        if (s.endsWith(" · ")) s = s.substring(0, s.length() - 3);
        riskSummaryView.setText(s + "（点我修改）");
        riskSummaryView.setTextColor(DyColor.primary(this));
    }

    /** 动作审批策略对话框：四级 × 三档（自动允许/每次确认/拒绝） */
    private void showRiskDialog(Runnable onDone) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        TextView hint = new TextView(this);
        hint.setText("风险等级由代码固定（防 prompt 注入降级），"
                + "这里只调每级的处理策略。远程（HTTP/Telegram）进来的任务"
                + "无审批人，「每次确认」档会自动拦截。");
        hint.setTextSize(12);
        hint.setTextColor(DyColor.onSurfaceVariant(this));
        hint.setPadding(0, 0, 0, dp(8));
        box.addView(hint);
        final String[] labels = {"自动允许", "每次确认", "拒绝"};
        final int[] vals = {ActionRisk.ALLOW, ActionRisk.ASK, ActionRisk.DENY};
        for (ActionRisk.Level lv : ActionRisk.Level.values()) {
            final ActionRisk.Level level = lv;
            TextView title = new TextView(this);
            title.setText(lv.label + riskExamples(lv));
            title.setTextSize(13);
            title.setTextColor(DyColor.onSurface(this));
            title.setPadding(0, dp(6), 0, dp(2));
            box.addView(title);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            int cur = riskPolicy.get(lv);
            for (int i = 0; i < labels.length; i++) {
                final int v = vals[i];
                android.widget.Button b = smallBtn(labels[i]);
                if (v == cur) {
                    b.setBackgroundColor(DyColor.primary(this));
                    b.setTextColor(Color.WHITE);
                }
                b.setOnClickListener(x -> {
                    riskPolicy.set(level, v);
                    refreshRiskRow();
                    onDone.run();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(dp(2), 0, dp(2), 0);
                row.addView(b, lp);
            }
            box.addView(row);
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("🛡 动作审批策略")
                .setView(box)
                .setPositiveButton("完成", null)
                .show();
    }

    /** 每级包含的动作举例（帮助用户理解） */
    private static String riskExamples(ActionRisk.Level lv) {
        switch (lv) {
            case LOW:
                return "（读屏/等待/截屏）";
            case MEDIUM:
                return "（点击/滑动/输入/启动应用）";
            case HIGH:
                return "（termux/搜索/抓网页/写文件）";
            case CRITICAL:
                return "（root shell）";
            default:
                return "";
        }
    }

    /**
     * 高风险动作执行前向用户确认（agent 线程调用，UI 线程弹窗阻塞等待）。
     * 用户停止任务后自动拒绝，避免弹窗卡死后台。
     */
    private boolean askUserApprove(String action, String detail) {
        if (agentStop) return false;
        // v3.14：优先全局悬浮窗（虚拟屏任务时主屏也能决策）
        boolean useOverlay = getSharedPreferences("agent", MODE_PRIVATE)
                .getBoolean("approve_overlay", true);
        if (useOverlay && ApproveOverlay.canShow(this)) {
            int r = ApproveOverlay.ask(this, action, detail);
            if (r == ApproveOverlay.STOP_TASK) agentStop = true;
            if (r == ApproveOverlay.ALLOW_ONCE) {
                log("✅ 已批准（全局悬浮窗）：" + action);
                return true;
            }
            log("⛔ 已拒绝（全局悬浮窗）：" + action);
            return false;
        }
        final boolean[] result = {false};
        final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        runOnUiThread(() -> {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("🛡 高风险动作确认")
                    .setMessage("思考执行员请求执行：\n" + action
                            + "\n\n参数：" + detail
                            + "\n\n允许执行一次吗？")
                    .setPositiveButton("允许一次", (d, w) -> {
                        result[0] = true;
                        latch.countDown();
                    })
                    .setNegativeButton("拒绝", (d, w) -> {
                        result[0] = false;
                        latch.countDown();
                    })
                    .setNeutralButton("停止任务", (d, w) -> {
                        result[0] = false;
                        agentStop = true;
                        latch.countDown();
                    })
                    .setCancelable(false)
                    .show();
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return result[0];
    }

    /** 抓取记录列表 */
    private void showCaptureList() {
        java.util.List<CaptureStore.Record> recs = captureStore.list();
        if (recs.isEmpty()) {
            log("暂无抓取记录，先点 📋 抓取或 🎯 悬浮抓取");
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        for (CaptureStore.Record r : recs) {
            final CaptureStore.Record rec = r;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            TextView t = new TextView(this);
            String label = rec.timeStr()
                    + (rec.pkg.isEmpty() ? "" : " · " + rec.pkg)
                    + "\n" + rec.total + " 元素 / " + rec.clickables + " 可点";
            t.setText(label);
            t.setTextSize(13);
            t.setTextColor(DyColor.onSurface(this));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(t, lp);
            Button bDel = smallBtn("删除");
            bDel.setOnClickListener(v -> {
                captureStore.delete(rec.id);
                showCaptureList();
            });
            row.addView(bDel);
            row.setClickable(true);
            row.setOnClickListener(v -> showCaptureRecord(rec.id));
            // 删除按钮消费点击，避免触发行点击
            bDel.setFocusable(true);
            box.addView(row);
            View div = new View(this);
            div.setBackgroundColor(0x14000000);
            box.addView(div, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(420)));
        new android.app.AlertDialog.Builder(this)
                .setTitle("📸 抓取记录（点一条查看）")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 记录详情：标注截图 + 元素文本 + 一键发 AI */
    private void showCaptureRecord(String id) {
        final CaptureStore.Record r = captureStore.get(id);
        if (r == null) {
            log("记录已删除");
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        // 标注截图
        if (!r.imagePath.isEmpty()) {
            try {
                android.graphics.Bitmap bmp =
                        android.graphics.BitmapFactory.decodeFile(r.imagePath);
                if (bmp != null) {
                    android.widget.ImageView iv =
                            new android.widget.ImageView(this);
                    iv.setImageBitmap(bmp);
                    iv.setAdjustViewBounds(true);
                    iv.setScaleType(
                            android.widget.ImageView.ScaleType.FIT_CENTER);
                    box.addView(iv, new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(300)));
                }
            } catch (Exception ignored) {
            }
        }
        TextView tv = new TextView(this);
        tv.setText(r.dump);
        tv.setTextSize(12);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(4), dp(8), dp(4), dp(8));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220)));
        box.addView(sv);

        new android.app.AlertDialog.Builder(this)
                .setTitle("📸 " + r.timeStr()
                        + (r.pkg.isEmpty() ? "" : " · " + r.pkg))
                .setView(box)
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制文本", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager)
                                    getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData
                            .newPlainText("capture", r.dump));
                    log("已复制");
                })
                .setNegativeButton("🤖 发给 AI 生成技能卡", (d, w) ->
                        shareToAi(r))
                .show();
    }

    /** 一键发送给任意 AI：prompt 文本 + 标注截图，走系统分享 */
    private void shareToAi(CaptureStore.Record r) {
        try {
            String prompt = CaptureStore.buildAiPrompt(r);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, prompt);
            share.putExtra(Intent.EXTRA_SUBJECT, "生成GSIC技能卡");
            if (!r.imagePath.isEmpty()) {
                java.io.File img = new java.io.File(r.imagePath);
                if (img.exists()) {
                    android.net.Uri uri = CaptureProvider.uriFor(img);
                    share.putExtra(Intent.EXTRA_STREAM, uri);
                    share.setType("image/png");
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    // 图文一起发
                    share.putExtra(Intent.EXTRA_TEXT, prompt);
                }
            }
            startActivity(Intent.createChooser(share, "发送给 AI 生成技能卡"));
        } catch (Exception e) {
            // FileProvider 未配置时退回纯文本分享
            try {
                String prompt = CaptureStore.buildAiPrompt(r);
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("text/plain");
                share.putExtra(Intent.EXTRA_TEXT, prompt);
                startActivity(Intent.createChooser(share, "发送给 AI（文本）"));
            } catch (Exception ignored) {
            }
        }
    }

    /** 展示界面元素抓取结果 */
    private void showElementDump(String dump) {
        TextView tv = new TextView(this);
        tv.setText(dump);
        tv.setTextSize(12);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(8), dp(8), dp(8), dp(8));
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        // 限制高度
        sv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(420)));
        new android.app.AlertDialog.Builder(this)
                .setTitle("📱 界面元素")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager)
                                    getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData
                            .newPlainText("elements", dump));
                    log("已复制");
                })
                .show();
    }
}
