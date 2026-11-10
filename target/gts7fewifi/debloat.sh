# Copyright (c) 2026 iamck01
# SPDX-License-Identifier: GPL-3.0-or-later

# Debloat list for Galaxy Tab S7 FE Wi-Fi (gts7fewifi / SM-T733)
# To fit One UI 8.5 within target super partition size (8,321,499,136 bytes)

# Korean carrier bloat and carrier/hidden directories from S23 donor
for d in carrier hidden; do
    [ -d "$WORK_DIR/system/system/$d" ] && SYSTEM_DEBLOAT+="
system/$d
"
    [ -d "$WORK_DIR/system/$d" ] && SYSTEM_DEBLOAT+="
$d
"
done

# Dynamic match for heavy/non-essential apps
if [ -d "$WORK_DIR/system" ]; then
    SYSTEM_DEBLOAT+="
$(find "$WORK_DIR/system" -maxdepth 4 -type d \( \
    -name "*SKT*" -o \
    -name "*KTC*" -o \
    -name "*LGT*" -o \
    -name "*LGU*" -o \
    -name "*OneStore*" -o \
    -name "*Tstore*" -o \
    -name "*SecCarrier*" -o \
    -name "KidsHome" -o \
    -name "KidsInstaller" -o \
    -name "GlobalGoals" -o \
    -name "SamsungMembers" -o \
    -name "ARZone" -o \
    -name "AREmoji" -o \
    -name "AREmojiEditor" -o \
    -name "AvatarEmojiSticker" -o \
    -name "LiveDrawing" -o \
    -name "DecoPic" -o \
    -name "QuickMeasure" -o \
    -name "DexOnPC" -o \
    -name "SamsungHealth" -o \
    -name "DigitalKey" -o \
    -name "PaymentFramework" -o \
    -name "SamsungCarKeyFw" -o \
    -name "OneDrive*" -o \
    -name "Microsoft365" -o \
    -name "Outlook" -o \
    -name "LinkedIn*" \
\) 2>/dev/null | sed "s|$WORK_DIR/system/||g")
"
fi

if [ -d "$WORK_DIR/product" ]; then
    PRODUCT_DEBLOAT+="
$(find "$WORK_DIR/product" -maxdepth 4 -type d \( \
    -name "GoogleTTS" -o \
    -name "Videos" -o \
    -name "Drive" -o \
    -name "Photos" \
\) 2>/dev/null | sed "s|$WORK_DIR/product/||g")
"
fi
