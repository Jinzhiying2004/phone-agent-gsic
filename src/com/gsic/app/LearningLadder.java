package com.gsic.app;

/**
 * 学习梯子（SPEC 一：接单自检发现不会 -> 按梯子先学再做）。
 * 爬梯顺序：1 查技能卡与认知记忆 -> 2 联网搜（上限两轮，无果不硬耗）
 * -> 3 问用户一句（选项式）。问过仍无解就收尾，不无限转圈。
 * 纯逻辑、无 Android 依赖，可在 JVM 单测；三端共享（同一身体三种人格）。
 */
public class LearningLadder {

    /** 梯子第 2 级：联网搜索上限轮数（SPEC 亲定：上限两轮，无果不硬耗） */
    public static final int MAX_SEARCH_ROUNDS = 2;
    /** 梯子第 3 级：问用户时选项上限（悬浮窗一行放不下太多按钮） */
    public static final int MAX_ASK_OPTIONS = 4;

    public enum Rung { CARD, SEARCH, ASK, STUCK }

    private String topic = "";
    private int searchRounds = 0;
    private boolean answerFound = false;
    private boolean asked = false;

    /**
     * 开始爬梯：先走第 1 级（查卡/认知），这是模型侧的检索动作，
     * 控制器只记账（搜了几轮、问没问过），不替模型做判断。
     */
    public Rung begin(String topic) {
        this.topic = topic == null ? "" : topic;
        this.searchRounds = 0;
        this.answerFound = false;
        this.asked = false;
        return Rung.CARD;
    }

    /** 记录一次搜索轮次；found=true 表示搜到可用答案，梯子就此结束。 */
    public void recordSearch(boolean found) {
        searchRounds++;
        if (found) answerFound = true;
    }

    /** 剩余搜索轮数（TaskRunner 的 search 工具用它做硬上限）。 */
    public int searchBudgetLeft() {
        return Math.max(0, MAX_SEARCH_ROUNDS - searchRounds);
    }

    public boolean isResolved() { return answerFound; }
    public int getSearchRounds() { return searchRounds; }
    public String getTopic() { return topic; }

    /**
     * 下一级去哪：预算内 -> 搜；两轮搜完无果 -> 问用户；
     * 已问过 -> 停手（把已知信息交代收尾，不许无限转圈试错）。
     */
    public Rung next() {
        if (answerFound) return Rung.CARD;
        if (searchBudgetLeft() > 0) return Rung.SEARCH;
        if (!asked) return Rung.ASK;
        return Rung.STUCK;
    }

    public void markAsked() { asked = true; }

    /** 选项裁剪到上限，去空去重。 */
    public static String[] capOptions(String[] options) {
        if (options == null) return new String[0];
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (String o : options) {
            if (o == null) continue;
            String t = o.trim();
            if (t.isEmpty() || out.contains(t)) continue;
            out.add(t);
        }
        if (out.size() > MAX_ASK_OPTIONS) out = out.subList(0, MAX_ASK_OPTIONS);
        return out.toArray(new String[0]);
    }

    /** 选项式提问的统一文本格式（App 悬浮窗 / TG / 远控结论共用）。 */
    public static String formatAsk(String question, String[] options) {
        StringBuilder sb = new StringBuilder();
        sb.append("? ").append(question == null ? "" : question.trim()).append("\n");
        String[] opts = capOptions(options);
        if (opts.length == 0) {
            sb.append("(开放式提问，请直接回复)");
        } else {
            for (int i = 0; i < opts.length; i++) {
                sb.append("\n").append(i + 1).append(". ").append(opts[i]);
            }
        }
        return sb.toString();
    }

    /**
     * 注入 sessionSystemPrompt 的梯子规程（模型侧纪律：先查卡 -> 搜两轮 -> 问）。
     * R21（提问时机纪律）：只问下一步动作真需要的空位，不提前问后续步骤；
     * 多个空位从上到下一次问一个。R24（问前先查设备上下文）：先过一遍设备已有信息，
     * 能自己定高置信的直接定不问，剩下的才问。R22（提问三态分流）：ask_user 只问信息缺失类问题；
     * 敏感动作的"行不行"不拿 ask_user 问，直接调对应工具走风险确认通道裁决。
     */
    public static String ladderPrompt() {
        return "学习梯子(不会就先学，不硬跑)：1 先查技能卡与认知记忆(use_card/remember/界面地图)，"
                + "会就直接做；2 还不会就联网搜，[每任务最多 2 轮]，搜到可用做法就做，"
                + "两轮无果不许再搜；3 还不懂就用 ask_user 问用户一句(选项式，给出候选选项)："
                + "问之前先过一遍设备已有信息(日历/联系人/通知/词典/界面地图/当前屏面)，"
                + "能自己定高置信的直接定、不问，剩下的才问；"
                + "只问下一步动作真需要的空位，不提前问后续步骤，多个空位从上到下一次问一个；"
                + "问完按用户选择做；问过一次仍无解就收尾报告已知信息，不许无限转圈试错。"
                + " 分流：ask_user 只问信息缺失类问题；敏感动作(支付/发消息/删数据/改系统权限/卸载)不要拿它问"
                + "\"行不行\"——直接调对应工具，让风险通道按用户策略裁决(App 会弹确认，远控/TG 无审批人时自动拦截并记日志)，"
                + "被拦截就在结论里如实说明卡口；明确做不到的直接收尾报告，不问用户。";
    }
}
