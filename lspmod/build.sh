#!/usr/bin/env bash
# 手工构建 XgjRotateFix.apk（无 Gradle）
# aapt2 compile -> aapt2 link -> javac -> d8 -> aapt add classes.dex -> zipalign -> apksigner
set -e

export MSYS2_ARG_CONV_EXCL='*'
export MSYS_NO_PATHCONV=1

ROOT='C:/xgj_236/AllToolBox/rotate_fix/lspmod'
SDK='C:/Android/Sdk'
BT="$SDK/build-tools/35.0.0"
ANDROID="$SDK/platforms/android-34/android.jar"
XPOSED="$ROOT/api-82.jar"
PFX='C:/xgj_236/AllToolBox/ca/certs/codesign.pfx'
PFX_PWD="$(tr -d '\r\n' < 'C:/xgj_236/AllToolBox/ca/certs/password.txt')"

SRC="$ROOT"
BUILD="$ROOT/build"
FLAT="$BUILD/flat"
CLASSES="$BUILD/classes"
GEN="$BUILD/gen"
DEX="$BUILD/dex"
UNSIGNED="$BUILD/XgjRotateFix-unsigned.apk"
ALIGNED="$BUILD/XgjRotateFix-aligned.apk"
SIGNED="$ROOT/XgjRotateFix.apk"

rm -rf "$BUILD"
mkdir -p "$FLAT" "$CLASSES" "$GEN" "$DEX"

echo '[1/6] aapt2 compile'
"$BT/aapt2.exe" compile --dir "$SRC/res" -o "$FLAT"

echo '[2/6] aapt2 link'
LINK_ARGS=(link -I "$ANDROID" --manifest "$SRC/AndroidManifest.xml" -A "$SRC/assets" --java "$GEN" -o "$UNSIGNED")
for f in "$FLAT"/*.flat; do LINK_ARGS+=("$f"); done
"$BT/aapt2.exe" "${LINK_ARGS[@]}"

echo '[3/6] javac'
JAVA_SRC=()
while IFS= read -r f; do JAVA_SRC+=("$f"); done < <(find "$SRC/java" "$GEN" -name '*.java')
javac -source 1.8 -target 1.8 -encoding UTF-8 -nowarn \
      -bootclasspath "$ANDROID" \
      -classpath "$ANDROID;$XPOSED" \
      -d "$CLASSES" \
      "${JAVA_SRC[@]}"

echo '[4/6] d8'
CLASS_FILES=()
while IFS= read -r f; do CLASS_FILES+=("$f"); done < <(find "$CLASSES" -name '*.class')
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
     --min-api 21 --lib "$ANDROID" --classpath "$XPOSED" \
     --output "$DEX" "${CLASS_FILES[@]}"

echo '[5/6] aapt add classes.dex'
( cd "$DEX" && "$BT/aapt.exe" add "$UNSIGNED" classes.dex )

echo '[6/6] zipalign + apksigner'
"$BT/zipalign.exe" -f 4 "$UNSIGNED" "$ALIGNED"
"$BT/apksigner.bat" sign \
    --ks "$PFX" --ks-type PKCS12 \
    --ks-pass "pass:$PFX_PWD" \
    --min-sdk-version 21 \
    --out "$SIGNED" "$ALIGNED"

echo
echo "SUCCESS: $SIGNED"
ls -l "$SIGNED"
