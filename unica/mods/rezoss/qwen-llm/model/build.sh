#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Build the optional on-device model pack (Qwen2.5-1.5B-Instruct Q4_K_M) for Rezoss AI.
# The app copies and verifies the model once; the pack can be uninstalled afterwards.
set -euo pipefail
: "${SRC_DIR:?Source buildenv.sh <target> first}"
ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
OUTPUT=${1:-$SRC_DIR/out/archived/QWEN_LLM/QWEN_LLM_rezoss_model.apk}
MODEL_DIR=${QWEN_MODEL_DIR:-$SRC_DIR/out/archived/QWEN_LLM/model}
MODEL=qwen2.5-1.5b-instruct-q4_k_m.gguf
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}; TOOLS=$SDK/build-tools/37.0.0
WORK=$(mktemp -d /tmp/rezoss-model-pack.XXXXXX)
trap 'rm -rf "$WORK"' EXIT
bash "$ROOT/fetch-model.sh" "$MODEL_DIR"
"$TOOLS/aapt2" link --manifest "$ROOT/AndroidManifest.xml" -I "$SDK/platforms/android-37.0/android.jar" -o "$WORK/base.apk"
mkdir -p "$WORK/assets"
ln "$MODEL_DIR/$MODEL" "$WORK/assets/$MODEL" 2>/dev/null || cp "$MODEL_DIR/$MODEL" "$WORK/assets/$MODEL"
cp "$MODEL_DIR/QWEN-LICENSE" "$WORK/assets/"
(
    cd "$WORK"
    # Stored, not deflated: the app streams the asset straight into its private copy.
    zip -q -0 base.apk "assets/$MODEL" assets/QWEN-LICENSE
)
"$TOOLS/zipalign" -P 16 -f 4 "$WORK/base.apk" "$WORK/aligned.apk"
CERT=aosp
if ${ROM_IS_OFFICIAL:-false}; then CERT=unica; fi
# Must match the Rezoss AI app certificate; the app refuses a pack with another signer.
"$TOOLS/apksigner" sign --key "$SRC_DIR/security/${CERT}_platform.pk8" --cert "$SRC_DIR/security/${CERT}_platform.x509.pem" \
    --out "$WORK/signed.apk" "$WORK/aligned.apk"
"$TOOLS/apksigner" verify --verbose "$WORK/signed.apk"
"$TOOLS/zipalign" -c -P 16 4 "$WORK/signed.apk"
mkdir -p "$(dirname -- "$OUTPUT")"
mv "$WORK/signed.apk" "$OUTPUT"
sha256sum "$OUTPUT"
