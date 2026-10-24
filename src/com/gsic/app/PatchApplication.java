package com.gsic.app;

import android.app.Application;
import android.content.Context;

/**
 * 应用入口 Application：attachBaseContext 阶段先尝试加载已落盘的热补丁，
 * 保证补丁类在任何业务类初始化之前完成替换。加载失败只记日志，不阻断启动。
 */
public class PatchApplication extends Application {

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        PatchManager.tryLoadPatch(this);
        // v3.54 热更新诊断 E1：注入时刻 dump ClassLoader 元素构成（同步、轻量）
        PatchDiag.dump(this, "attach");
    }
}
