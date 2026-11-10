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

# Offline models / translation weights if present
for m in etc/models system/etc/models system/system/etc/models; do
    [ -d "$WORK_DIR/system/$m" ] && SYSTEM_DEBLOAT+="
${m#system/}
"
done

# Dynamic match for heavy/non-essential apps in system
if [ -d "$WORK_DIR/system" ]; then
    SYSTEM_DEBLOAT+="
$(find "$WORK_DIR/system" -maxdepth 5 -type d \( \
    -iname "*SKT*" -o \
    -iname "*KTC*" -o \
    -iname "*LGT*" -o \
    -iname "*LGU*" -o \
    -iname "*OneStore*" -o \
    -iname "*Tstore*" -o \
    -iname "*SecCarrier*" -o \
    -iname "*Kids*" -o \
    -iname "*GlobalGoals*" -o \
    -iname "*SamsungMembers*" -o \
    -iname "*ARZone*" -o \
    -iname "*AREmoji*" -o \
    -iname "*AvatarEmoji*" -o \
    -iname "*LiveDrawing*" -o \
    -iname "*DecoPic*" -o \
    -iname "*QuickMeasure*" -o \
    -iname "*DexOnPC*" -o \
    -iname "*SamsungHealth*" -o \
    -iname "*DigitalKey*" -o \
    -iname "*PaymentFramework*" -o \
    -iname "*SamsungCarKey*" -o \
    -iname "*OneDrive*" -o \
    -iname "*Microsoft365*" -o \
    -iname "*Outlook*" -o \
    -iname "*LinkedIn*" -o \
    -iname "*Bixby*" -o \
    -iname "*VoiceWakeUp*" -o \
    -iname "*SmartSwitch*" -o \
    -iname "*SamsungTTS*" -o \
    -iname "*SMT_*" -o \
    -iname "*Tips*" -o \
    -iname "*VoiceNote*" \
\) 2>/dev/null | sed "s|$WORK_DIR/system/||g")
"
fi

# Dynamic match for heavy store-installable apps in product
if [ -d "$WORK_DIR/product" ]; then
    PRODUCT_DEBLOAT+="
$(find "$WORK_DIR/product" -maxdepth 5 -type d \( \
    -iname "*Velvet*" -o \
    -iname "*GoogleTTS*" -o \
    -iname "*SpeechServices*" -o \
    -iname "*GoogleRestore*" -o \
    -iname "*Videos*" -o \
    -iname "*Drive*" -o \
    -iname "*Photos*" \
\) 2>/dev/null | sed "s|$WORK_DIR/product/||g")
"
fi
