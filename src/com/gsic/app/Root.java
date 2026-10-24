package com.gsic.app;

/**
 * root 二进制路径探测。
 * 部分设备上 su 不在应用进程的 PATH 中，逐个尝试常见绝对路径并缓存命中结果。
 */
public class Root {

    private static String suPath;

    private static final String[] CANDIDATES = {
            "su",
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/magisk/.core/bin/su",
            "/data/adb/magisk/busybox/su",
    };

    /** 返回可用的 su 路径，未找到返回 null */
    public static synchronized String findSu() {
        if (suPath != null) return suPath;
        for (String c : CANDIDATES) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{c, "-c", "exit 0"});
                if (p.waitFor() == 0) {
                    suPath = c;
                    return suPath;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** 用 su 执行命令；su 不可用时抛异常 */
    public static Process exec(String cmd) throws Exception {
        String su = findSu();
        if (su == null) throw new Exception("su not found");
        return Runtime.getRuntime().exec(new String[]{su, "-c", cmd});
    }
}
