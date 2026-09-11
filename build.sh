#!/bin/bash
set -euo pipefail
PROJECT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}}}"
BT="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
JDK="${BUTTONS_JDK:-${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}}"
XPOSED="${XPOSED_API_JAR:-$PROJECT/.deps/xposed-api-82.jar}"
export PATH="$JDK/bin:$PATH"
OUT="$PROJECT/build"
for dependency in "$ANDROID_JAR" "$XPOSED" "$BT/aapt2" "$JDK/bin/javac"; do
    if [ ! -f "$dependency" ]; then
        echo "Missing build dependency: $dependency" >&2
        exit 1
    fi
done
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/rescomp"
"$BT/aapt2" compile --dir "$PROJECT/res" -o "$OUT/rescomp/res.zip"
"$BT/aapt2" link -I "$ANDROID_JAR" --manifest "$PROJECT/AndroidManifest.xml" \
    -A "$PROJECT/assets" --min-sdk-version 28 --target-sdk-version 32 \
    -o "$OUT/base.apk" "$OUT/rescomp/res.zip"
"$JDK/bin/javac" --release 8 -cp "$ANDROID_JAR:$XPOSED" \
    -d "$OUT/classes" "$PROJECT"/src/com/efran/buttons/*.java
classes=()
while IFS= read -r -d '' file; do classes+=("$file"); done < <(find "$OUT/classes" -name '*.class' -print0)
"$BT/d8" --min-api 28 --lib "$ANDROID_JAR" --classpath "$XPOSED" --output "$OUT/dex" "${classes[@]}"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ ! -f "$PROJECT/signing.keystore" ]; then
    "$JDK/bin/keytool" -genkeypair -keystore "$PROJECT/signing.keystore" -alias buttons \
        -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 \
        -dname 'CN=Headunit Buttons, O=local, C=ID' >/dev/null 2>&1
    chmod 600 "$PROJECT/signing.keystore"
fi
"$BT/apksigner" sign --ks "$PROJECT/signing.keystore" --ks-pass pass:android \
    --ks-key-alias buttons --out "$PROJECT/headunit-buttons.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$PROJECT/headunit-buttons.apk"
echo "Built $PROJECT/headunit-buttons.apk"
