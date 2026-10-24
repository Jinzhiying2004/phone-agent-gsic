package com.gsic.app;

import java.util.concurrent.atomic.AtomicReference;

/**
 * v3.11 Issue 14：三通道任务互斥锁。
 * App / TG / 远控同时只能跑一个 AgentLoop 任务，避免状态冲突。
 */
public class TaskLock {

    /** 通道名：app / tg / remote */
    private static final AtomicReference<String> holder = new AtomicReference<>(null);

    /** 尝试获取锁，成功返回 true */
    public static boolean tryAcquire(String channel) {
        return holder.compareAndSet(null, channel);
    }

    /** 释放锁（只有持有者能释放） */
    public static void release(String channel) {
        holder.compareAndSet(channel, null);
    }

    /** 当前持有通道，null=空闲 */
    public static String current() {
        return holder.get();
    }

    public static boolean isBusy() {
        return holder.get() != null;
    }
}
