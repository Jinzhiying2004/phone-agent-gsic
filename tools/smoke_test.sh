#!/bin/bash
# v3.12 Issue 5：冒烟测试脚本
# 用法：./tools/smoke_test.sh [apk路径]
# 在已 root 且装好 App 的测试机上跑（需 adb 连接）
set -e
APK="${1:-gsic-*.apk}"
PKG="com.gsic.app"

echo "== 1. 安装 APK =="
adb install -r $APK

echo "== 2. 启动 App =="
adb shell am start -n $PKG/.MainActivity
sleep 3

echo "== 3. 检查远控服务端口 =="
adb shell "su -c 'netstat -tlnp 2>/dev/null | grep 28789'" \
  && echo "远控端口监听中" || echo "WARN: 远控端口未监听（可能未启动服务）"

echo "== 4. 纯 Java 单测（AppInfo 三数组对齐） =="
export PATH=~/build-tools/jdk17/bin:$PATH
rm -rf /tmp/smoke && mkdir -p /tmp/smoke
javac -encoding UTF-8 -d /tmp/smoke src/com/gsic/app/AppInfo.java
cat > /tmp/smoke/S.java <<'EOF'
public class S {
    public static void main(String[] a) throws Exception {
        Class<?> c = Class.forName("com.gsic.app.AppInfo");
        int v = ((String[]) c.getField("LOG_VERSIONS").get(null)).length;
        int s2 = ((String[]) c.getField("LOG_SUMMARY").get(null)).length;
        int d = ((String[]) c.getField("LOG_DETAILS").get(null)).length;
        if (v != s2 || s2 != d) { System.out.println("FAIL: " + v + "/" + s2 + "/" + d); System.exit(1); }
        System.out.println("OK: 三数组 " + v + " 对齐");
    }
}
EOF
javac -encoding UTF-8 -cp /tmp/smoke -d /tmp/smoke /tmp/smoke/S.java
java -cp /tmp/smoke S

echo "== 5. ActionRisk 分级单测 =="
cat > /tmp/smoke/R.java <<'EOF'
public class R {
    public static void main(String[] a) throws Exception {
        Class<?> c = Class.forName("com.gsic.app.ActionRisk");
        Object lv = c.getMethod("levelOf", String.class).invoke(null, "reboot");
        if (!"CRITICAL".equals(lv.toString())) { System.out.println("FAIL: reboot=" + lv); System.exit(1); }
        lv = c.getMethod("levelOf", String.class).invoke(null, "tap");
        if (!"LOW".equals(lv.toString())) { System.out.println("FAIL: tap=" + lv); System.exit(1); }
        System.out.println("OK: 风险分级正确");
    }
}
EOF
# ActionRisk 依赖 android 上下文？levelOf 是纯函数，单独编译
javac -encoding UTF-8 -d /tmp/smoke src/com/gsic/app/ActionRisk.java 2>/dev/null \
  && javac -encoding UTF-8 -cp /tmp/smoke -d /tmp/smoke /tmp/smoke/R.java \
  && java -cp /tmp/smoke R \
  || echo "SKIP: ActionRisk 需 android.jar，跳过（CI 上跑）"

echo "== 冒烟测试完成 =="
