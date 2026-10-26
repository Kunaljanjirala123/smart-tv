#!/usr/bin/env bash
# Builds app/build/glass-home.apk with the plain Android SDK tools: no Gradle, no libraries.
# Needs JDK 17 and an Android SDK with platforms;android-30 and build-tools;34.0.0.
# GitHub Actions runs this on every push (.github/workflows/build.yml).
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

VERSION_NAME=${VERSION_NAME:-1.0}
VERSION_CODE=${VERSION_CODE:-1}
SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
JAR=$SDK/platforms/android-30/android.jar
BT=$SDK/build-tools/34.0.0
[[ -f $JAR ]] || { echo "error: $JAR missing: sdkmanager 'platforms;android-30'" >&2; exit 1; }
[[ -x $BT/aapt2 ]] || { echo "error: $BT missing: sdkmanager 'build-tools;34.0.0'" >&2; exit 1; }

KEYSTORE=${KEYSTORE:-$PWD/../signing/glass-home.keystore}
KEYPASS=${KEYPASS:-glasshome}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/gen" "$WORK/classes" build

"$BT/aapt2" compile --dir res -o "$WORK/res.zip"
"$BT/aapt2" link -I "$JAR" --manifest AndroidManifest.xml "$WORK/res.zip" \
  --min-sdk-version 28 --target-sdk-version 28 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$WORK/gen" -o "$WORK/app.apk"

find src "$WORK/gen" -name '*.java' > "$WORK/sources.txt"
javac --release 8 -Xlint:-options -encoding UTF-8 -classpath "$JAR" -d "$WORK/classes" @"$WORK/sources.txt"

find "$WORK/classes" -name '*.class' > "$WORK/classes.txt"
"$BT/d8" --release --min-api 28 --lib "$JAR" --output "$WORK" @"$WORK/classes.txt"
(cd "$WORK" && zip -q -X app.apk classes.dex)

"$BT/zipalign" -f -p 4 "$WORK/app.apk" "$WORK/aligned.apk"
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYPASS" --key-pass "pass:$KEYPASS" \
  --out build/glass-home.apk "$WORK/aligned.apk"
"$BT/apksigner" verify build/glass-home.apk
"$BT/aapt2" dump permissions build/glass-home.apk
sha256sum build/glass-home.apk | awk '{print $1}' > build/glass-home.apk.sha256
printf 'built build/glass-home.apk  %s bytes  sha256 %s\n' "$(wc -c < build/glass-home.apk)" "$(cat build/glass-home.apk.sha256)"
