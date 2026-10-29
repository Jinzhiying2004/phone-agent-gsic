#!/bin/bash
# 手工编译 APK（aapt2 + javac + d8 + apksigner），无需 Gradle
set -e
cd "$(dirname "$0")"

export JAVA_HOME=/home/hatch/build-tools/jdk17
export PATH=$JAVA_HOME/bin:$PATH
SDK=/home/hatch/android-sdk
BT=$SDK/build-tools/34.0.0
PLAT=$SDK/platforms/android-34/android.jar
OUT=out

# 启动器图标以 base64 内嵌在 tools/launcher_icons.b64.json（文本推送管线无法传输二进制 PNG）；
# 构建前若 PNG 缺失则自动还原，不影响正常构建。
python3 - <<'EOF'
import json, base64, os
icons = json.load(open('tools/launcher_icons.b64.json'))
for path, b64 in icons.items():
    if not os.path.exists(path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        open(path, 'wb').write(base64.b64decode(b64))
        print('已还原图标', path)
EOF

# vdinput.dex：虚拟屏输入注入（root 下经 app_process 调用），二进制不进推送管线；
# 缺失或源码更新时自动从 tools/VdInput.java + VdTap.java 重编。
if [ ! -f assets/vdinput.dex ] || [ tools/VdInput.java -nt assets/vdinput.dex ] || [ tools/VdTap.java -nt assets/vdinput.dex ]; then
  echo "== vdinput.dex 重编 =="
  mkdir -p $OUT/vdinput_classes $OUT/vdinput_dex
  javac -encoding UTF-8 -source 8 -target 8 -nowarn -classpath $PLAT -d $OUT/vdinput_classes \
    tools/VdInput.java tools/VdTap.java
  $BT/d8 --min-api 26 --lib $PLAT --output $OUT/vdinput_dex \
    $(find $OUT/vdinput_classes -name "*.class")
  cp $OUT/vdinput_dex/classes.dex assets/vdinput.dex
  echo "vdinput.dex 已生成"
fi

# cloudflared：内置隧道二进制（linux-arm64 静态版，官方 GitHub releases）。
# 二进制不进 git 推送管线；构建时缺失则自动拉取，存在则直接用。
if [ ! -f assets/cloudflared ] || [ ! -s assets/cloudflared ]; then
  echo "== cloudflared 拉取（构建时，官方 releases） =="
  curl -L --max-time 180 -o assets/cloudflared \
    https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64
  chmod +x assets/cloudflared
fi
ls -lh assets/cloudflared

rm -rf $OUT && mkdir -p $OUT/compiled $OUT/gen $OUT/classes $OUT/dex

echo "== 1/6 aapt2 compile =="
$BT/aapt2 compile --dir res -o $OUT/compiled/

echo "== 2/6 aapt2 link =="
# targetSdk 刻意定 28（Android 9）：Android 10+ 对 targetSdk>=29 的应用
# 用 SELinux 禁止执行私有目录里的二进制文件（W^X 限制），官方 Termux
# 也是靠 targetSdk=28 绕开的。targetSdk 34 会导致 Termux bash 报 Permission denied。
$BT/aapt2 link -o $OUT/base.apk -I $PLAT \
  --manifest AndroidManifest.xml -A assets \
  --java $OUT/gen $OUT/compiled/*.flat \
  --min-sdk-version 26 --target-sdk-version 28

echo "== 3/6 javac =="
javac -encoding UTF-8 -source 8 -target 8 -nowarn -classpath $PLAT -d $OUT/classes \
  $(find src -name "*.java") $(find $OUT/gen -name "*.java")

echo "== 4/6 d8 =="
$BT/d8 --min-api 26 --lib $PLAT --output $OUT/dex \
  $(find $OUT/classes -name "*.class")

echo "== 5/6 打包 dex =="
cp $OUT/base.apk $OUT/app.apk
python3 -c "
import zipfile
z = zipfile.ZipFile('$OUT/app.apk', 'a', zipfile.ZIP_STORED)
z.write('$OUT/dex/classes.dex', 'classes.dex')
z.close()
print('dex 已加入')
"

echo "== 6/6 对齐 + 签名 =="
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US" 2>/dev/null
fi
$BT/zipalign -f 4 $OUT/app.apk $OUT/app-aligned.apk
VN=$(grep -o 'versionName="[0-9.]*"' AndroidManifest.xml | grep -o '[0-9.]*')
APK=../gsic-$VN.apk
$BT/apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out $APK $OUT/app-aligned.apk

echo "== 验签 =="
$BT/apksigner verify --print-certs $APK | head -4
ls -la $APK

echo "== 生成 version.json（应用内更新用，发布时填写 apkUrl） =="
python3 -c "
import json, re
m = open('AndroidManifest.xml').read()
vc = re.search(r'versionCode=\"(\d+)\"', m).group(1)
vn = re.search(r'versionName=\"([\d.]+)\"', m).group(1)
apk_url = ('https://github.com/Jinzhiying2004/phone-agent-gsic'
           '/releases/download/v%s/gsic-%s.apk' % (vn, vn))
json.dump({'version': vn, 'versionCode': int(vc), 'apkUrl': apk_url,
           'notes': ''}, open('../version.json', 'w'), ensure_ascii=False, indent=2)
print(open('../version.json').read())
"
echo "提示：version.json 已自动填 Release 直链；发版时把 version.json 提交进仓库根目录、APK 传到对应 Release，软件内自更新即通"

echo "== 7/7 归档热补丁基线 =="
VC=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
rm -rf baselines/$VC && mkdir -p baselines/$VC
cp -r $OUT/classes baselines/$VC/classes
cp -r $OUT/gen baselines/$VC/gen
python3 -c "
import hashlib, json, os
def sha(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for c in iter(lambda: f.read(65536), b''): h.update(c)
    return h.hexdigest()
snap = {}
for top in ('res', 'assets'):
    if os.path.isdir(top):
        for dp, _, fns in os.walk(top):
            for fn in sorted(fns):
                p = os.path.join(dp, fn)
                snap[os.path.relpath(p)] = sha(p)
snap['AndroidManifest.xml'] = sha('AndroidManifest.xml')
json.dump(snap, open('baselines/$VC/snap.json', 'w'), indent=1)
print('基线已归档：baselines/$VC（含 classes/gen/资源快照）')
"
