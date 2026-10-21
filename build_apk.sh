#!/usr/bin/env bash
# بناء ملف APK بدون Gradle ولا Android Studio، بنفس الطريقة التي بُني بها الإصدار المرفق.
# الأدوات المطلوبة (مرة واحدة)، وتوضع في مجلد TOOLS:
#   aapt2      : من حزمة npm "aaptjs3" (bin/x64/linux/aapt2)
#   D8         : من إصدار jadx على GitHub (lib/jadx-*-all.jar يحتوي com.android.tools.r8.D8)
#   apksigner  : من حزمة npm "@postar/apktool-node" (lib/apksigner.jar)
#   android.jar: من مستودع GitHub "Sable/android-platforms" (android-34/android.jar)
# الاستخدام:  TOOLS=/path/to/tools ./build_apk.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/app/src/main"
TOOLS="${TOOLS:?ضع مسار مجلد الأدوات في المتغير TOOLS}"
AAPT="$TOOLS/aapt/package/bin/x64/linux/aapt2"
D8_JAR="$(ls "$TOOLS"/jadx/lib/jadx-*-all.jar | head -1)"
SIGNER="$TOOLS/postar/package/lib/apksigner.jar"
AJ="$TOOLS/plat/android-34/android.jar"
OUT="$HERE/build-out"

rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

# ١) الموارد: Gradle يضيف اسم الحزمة للـmanifest تلقائيًا، هنا نضيفه في نسخة مؤقتة
sed 's#<manifest xmlns:android="http://schemas.android.com/apk/res/android">#<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.santafe.launcher">#' \
  "$SRC/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$AAPT" compile --dir "$SRC/res" -o "$OUT/res.zip"
"$AAPT" link -o "$OUT/base.apk" -I "$AJ" --manifest "$OUT/AndroidManifest.xml" --java "$OUT/gen" -A "$SRC/assets" \
  --min-sdk-version 24 --target-sdk-version 34 --version-code 10 --version-name 1.9 -R "$OUT/res.zip" --auto-add-overlay

# ٢) الكود
javac -encoding UTF-8 --release 11 -classpath "$AJ" -d "$OUT/classes" \
  "$SRC"/java/com/santafe/launcher/*.java "$OUT"/gen/com/santafe/launcher/R.java
java -cp "$D8_JAR" com.android.tools.r8.D8 --release --min-api 24 --lib "$AJ" --output "$OUT/dex" \
  $(find "$OUT/classes" -name '*.class')

# ٣) التجميع والمحاذاة
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q ../unsigned.apk classes.dex)
python3 - "$OUT/unsigned.apk" "$OUT/aligned.apk" <<'EOF'
import sys, zipfile
src, dst = sys.argv[1], sys.argv[2]
zin = zipfile.ZipFile(src)
with open(dst, 'wb') as f:
    zout = zipfile.ZipFile(f, 'w')
    for info in zin.infolist():
        data = zin.read(info.filename)
        stored = info.filename == 'resources.arsc' or info.compress_type == zipfile.ZIP_STORED
        zi = zipfile.ZipInfo(info.filename, date_time=(2026, 1, 1, 0, 0, 0))
        zi.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
        if stored:  # المحاذاة على ٤ بايت كما يفعل zipalign
            zi.extra = b'\0' * ((4 - (f.tell() + 30 + len(info.filename.encode())) % 4) % 4)
        zout.writestr(zi, data)
    zout.close()
EOF

# ٤) التوقيع
#   كلمات المرور في keystore/keystore.properties (ملف خاص لا يُشارك ولا يُرفع).
#   المفتاح الجديد (RSA 3072) يوقّع توقيع v3، ومعه «إثبات تدوير» من المفتاح القديم،
#   والقديم يوقّع v2؛ فيُثبَّت الإصدار فوق النسخ السابقة كتحديث عادي.
KP="$HERE/keystore/keystore.properties"
[ -f "$KP" ] || { echo "ملف keystore/keystore.properties غير موجود"; exit 1; }
prop() { grep "^$1=" "$KP" | cut -d= -f2-; }
K="$HERE/keystore"
OLD_PASS="$(prop OLD_PASS)" NEW_PASS="$(prop PASS)" java -jar "$SIGNER" sign \
  --ks "$K/$(prop OLD_KS)" --ks-key-alias "$(prop OLD_ALIAS)" --ks-pass env:OLD_PASS --key-pass env:OLD_PASS \
  --next-signer --ks "$K/$(prop KS)" --ks-key-alias "$(prop ALIAS)" --ks-pass env:NEW_PASS --key-pass env:NEW_PASS \
  --lineage "$K/lineage.bin" --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
  --rotation-min-sdk-version 28 --min-sdk-version 24 --out "$HERE/SantaFeLauncher.apk" "$OUT/aligned.apk"
java -jar "$SIGNER" verify --print-certs "$HERE/SantaFeLauncher.apk" | grep -E "Signer|SHA-256" && echo "تم: $HERE/SantaFeLauncher.apk"
