#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Build the user-configured API app used by the ROM inference bridge.
set -euo pipefail
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
: "${SRC_DIR:?Source buildenv.sh <target> first}"
OUTPUT=${1:-$SRC_DIR/out/archived/QWEN_LLM/QWEN_LLM_rezoss.apk}
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}; TOOLS=$SDK/build-tools/37.0.0
WORK=$(mktemp -d /tmp/rezoss-api-app.XXXXXX)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/classes" "$WORK/dex"
javac -source 8 -target 8 -Xlint:-options -classpath "$SDK/platforms/android-37.0/android.jar" \
    -d "$WORK/classes" "$ROOT"/java/io/mesalabs/rezoss/qwen/*.java
jar cf "$WORK/classes.jar" -C "$WORK/classes" .
"$TOOLS/d8" --min-api 30 --lib "$SDK/platforms/android-37.0/android.jar" --output "$WORK/dex" "$WORK/classes.jar"
"$TOOLS/aapt2" compile --dir "$ROOT/res" -o "$WORK/res.zip"
"$TOOLS/aapt2" link --manifest "$ROOT/AndroidManifest.xml" -I "$SDK/platforms/android-37.0/android.jar" \
    -R "$WORK/res.zip" -o "$WORK/base.apk"
cp "$WORK/dex/classes.dex" "$WORK/"
# On-device backend: the same llama.cpp server the ROM's summary service ships.
mkdir -p "$WORK/lib/arm64-v8a"
cp "$ROOT/../../local-summary-service/system/bin/unica-summary-llama-server" "$WORK/lib/arm64-v8a/librezoss_llama.so"
(
    cd "$WORK"
    zip -q -0 base.apk classes.dex lib/arm64-v8a/librezoss_llama.so
)
"$TOOLS/zipalign" -P 16 -f 4 "$WORK/base.apk" "$WORK/aligned.apk"
CERT=aosp
if ${ROM_IS_OFFICIAL:-false}; then CERT=unica; fi
"$TOOLS/apksigner" sign --key "$SRC_DIR/security/${CERT}_platform.pk8" --cert "$SRC_DIR/security/${CERT}_platform.x509.pem" \
    --out "$WORK/signed.apk" "$WORK/aligned.apk"
"$TOOLS/apksigner" verify --verbose "$WORK/signed.apk"
"$TOOLS/zipalign" -c -P 16 4 "$WORK/signed.apk"
unzip -tq "$WORK/signed.apk"
mkdir -p "$(dirname -- "$OUTPUT")"
if [[ -e "$OUTPUT" ]]; then
    BACKUP="$OUTPUT.bak.$(date +%Y%m%d_%H%M%S)"
    [[ ! -e "$BACKUP" ]] || { echo "Backup exists: $BACKUP" >&2; exit 1; }
    cp -a "$OUTPUT" "$BACKUP"
    [[ ! -e "$OUTPUT.version" ]] || cp -a "$OUTPUT.version" "$BACKUP.version"
fi
mv "$WORK/signed.apk" "$OUTPUT"
APK_SHA256_LINE=$(sha256sum "$OUTPUT")
APK_SHA256=${APK_SHA256_LINE%% *}
APK_SIZE=$(stat -c %s "$OUTPUT")
GIT_COMMIT=$(git -C "$SRC_DIR" rev-parse --short=12 HEAD 2>/dev/null || true)
VERSION_FILE="$OUTPUT.version"
{
    printf "package=io.mesalabs.rezoss.qwen\n"
    printf "versionCode=8\n"
    printf "versionName=3.1-api-local\n"
    printf "apk=%s\n" "$OUTPUT"
    printf "sizeBytes=%s\n" "$APK_SIZE"
    printf "sha256=%s\n" "$APK_SHA256"
    printf "backend=openai-compatible-chat-completions,local-llama-cpp\n"
    printf "credentialStorage=android-keystore-aes-gcm\n"
    printf "builtAtUtc=%s\n" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    [[ -n "$GIT_COMMIT" ]] && printf "sourceCommit=%s\n" "$GIT_COMMIT"
} > "$VERSION_FILE"
sha256sum "$OUTPUT"
echo "version metadata: $VERSION_FILE"
