#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
set -euo pipefail
DEST="${1:?Usage: fetch-model.sh OUTPUT_DIRECTORY}"
REV=91cad51170dc346986eccefdc2dd33a9da36ead9
NAME=qwen2.5-1.5b-instruct-q4_k_m.gguf
SHA=6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e
mkdir -p "$DEST"
if [[ ! -f "$DEST/$NAME" ]]; then
    curl --fail --location --retry 3 --continue-at - \
        "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/$REV/$NAME" \
        -o "$DEST/$NAME.part"
    printf '%s  %s\n' "$SHA" "$DEST/$NAME.part" | sha256sum -c -
    mv "$DEST/$NAME.part" "$DEST/$NAME"
fi
printf '%s  %s\n' "$SHA" "$DEST/$NAME" | sha256sum -c -
[[ $(stat -c %s "$DEST/$NAME") -le 3000000000 ]]
curl --fail --location --retry 3 \
    "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/$REV/LICENSE" \
    -o "$DEST/QWEN-LICENSE"
