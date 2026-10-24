package com.gsic.app;

/**
 * 学习梯子步骤2：TG 同步提问的挂起/投递门。
 * 纯 Java、无 Android 依赖，可 JVM 单测。
 * 任务线程 register 后阻塞在 latch 上；TgBot 轮询线程收到主人
 * 下一条消息时 deliver（投递回答）/ cancel（新指令打断）唤醒它。
 * chatId 粒度，一次只挂一个提问；等待线程收尾时 unregister 注销。
 */
public class AskGate {

    public static class Pending {
        public final java.util.List<String> options;
        public final java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(1);
        public volatile String rawAnswer = null;
        public volatile boolean cancelled = false;

        Pending(java.util.List<String> options) {
            this.options = options != null ? options
                    : new java.util.ArrayList<String>();
        }
    }

    private static final java.util.Map<Long, Pending> PENDING =
            new java.util.concurrent.ConcurrentHashMap<Long, Pending>();

    /** 任务线程挂起：登记一个等待提问，返回 Pending（含 latch）。 */
    public static Pending register(long chatId,
            java.util.List<String> options) {
        Pending p = new Pending(options);
        PENDING.put(chatId, p);
        return p;
    }

    public static boolean hasPending(long chatId) {
        return PENDING.containsKey(chatId);
    }

    /** 轮询线程投递用户回复：有挂起提问才返回 true。 */
    public static boolean deliver(long chatId, String text) {
        Pending p = PENDING.get(chatId);
        if (p == null) return false;
        p.rawAnswer = text;
        p.latch.countDown();
        return true;
    }

    /** 轮询线程打断：用户发了新指令（/stop 等），唤醒等待线程并标记取消。 */
    public static boolean cancel(long chatId) {
        Pending p = PENDING.get(chatId);
        if (p == null) return false;
        p.cancelled = true;
        p.latch.countDown();
        return true;
    }

    /**
     * 等待线程收尾时注销。只移除自己注册的那个实例，
     * 防止新一轮提问刚 register 就被旧收尾误删。
     */
    public static void unregister(long chatId, Pending p) {
        PENDING.remove(chatId, p);
    }

    /**
     * 回答解析（原 TgBot.parseClarifyAnswer 上移共享）：
     * 1 序号（1-4 / ①②③④）→ 取对应选项；2 选项文本命中 → 取该选项；
     * 3 否则视为用户自由补充的说明，原样返回。
     */
    public static String parseAnswer(String text,
            java.util.List<String> options) {
        String t = text == null ? "" : text.trim();
        int idx = -1;
        if (t.matches("[1-4]")) {
            idx = t.charAt(0) - '1';
        } else if (!t.isEmpty()) {
            int p = "①②③④".indexOf(t.charAt(0));
            if (p >= 0) idx = p;
        }
        if (idx >= 0 && options != null && idx < options.size()) {
            return options.get(idx);
        }
        if (options != null) {
            for (String o : options) {
                if (o == null || o.isEmpty()) continue;
                if (o.equals(t)) return o;
                if (t.length() >= 2 && (t.contains(o) || o.contains(t))) {
                    return o;
                }
            }
        }
        return t;
    }
}
