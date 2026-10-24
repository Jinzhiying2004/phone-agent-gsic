package com.gsic.app;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

/**
 * Material You 动态取色（Android 12/API 31+ 读系统壁纸色）。
 * API 31 以下用静态回退色，保证 Android 11 照常显示。
 * 颜色名对应系统 tonal palette：system_accent1_* / system_neutral1_*。
 */
public class DyColor {

    public static boolean supported() {
        return Build.VERSION.SDK_INT >= 31;
    }

    public static boolean isDark(Context ctx) {
        int night = ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int sys(Context ctx, String name, int fallback) {
        if (!supported()) return fallback;
        try {
            int id = ctx.getResources().getIdentifier(name, "color", "android");
            if (id == 0) return fallback;
            return ctx.getColor(id);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 主色：按钮、选中态、用户气泡 */
    public static int primary(Context ctx) {
        return sys(ctx, "system_accent1_600", 0xFF1E88E5);
    }

    /** 主色上的文字 */
    public static int onPrimary(Context ctx) {
        return sys(ctx, "system_accent1_0", 0xFFFFFFFF);
    }

    /** 主色浅底：输入栏、次级气泡 */
    public static int primaryContainer(Context ctx) {
        boolean dark = isDark(ctx);
        return sys(ctx, dark ? "system_accent1_800" : "system_accent1_100",
                dark ? 0xFF0D47A1 : 0xFFD2E7FA);
    }

    /** 卡片底 */
    public static int surface(Context ctx) {
        boolean dark = isDark(ctx);
        return sys(ctx, dark ? "system_neutral1_900" : "system_neutral1_50",
                dark ? 0xFF1C1B1F : 0xFFFDFCFF);
    }

    /** 输入框、灰泡底 */
    public static int surfaceVariant(Context ctx) {
        boolean dark = isDark(ctx);
        return sys(ctx, dark ? "system_neutral2_800" : "system_neutral1_100",
                dark ? 0xFF2D2C31 : 0xFFF1F3F4);
    }

    /** 分割线/描边 */
    public static int outline(Context ctx) {
        boolean dark = isDark(ctx);
        return sys(ctx, dark ? "system_neutral2_700" : "system_neutral1_200",
                dark ? 0xFF44464A : 0xFFE0E0E0);
    }

    /** 主文字 */
    public static int onSurface(Context ctx) {
        boolean dark = isDark(ctx);
        return sys(ctx, dark ? "system_neutral1_100" : "system_neutral1_900",
                dark ? 0xFFE6E1E5 : 0xFF1C1B1F);
    }

    /** 次级文字 */
    public static int onSurfaceVariant(Context ctx) {
        return sys(ctx, "system_neutral2_500", 0xFF777777);
    }
}
