#!/system/bin/sh
# shellcheck disable=SC2015 # fail exits, "A && B || fail" is intended
# Copyright (c) 2026 Rezoss
# SPDX-License-Identifier: GPL-3.0-or-later
# Explicit post-install activation for the NEXT boot. Never mounts/restarts.
set -eu
MODDIR=${0%/*}
fail() { echo "REFUSED: $*"; exit 1; }
[ "$(id -u)" = 0 ] || fail "Root required"
[ "$(getprop ro.product.device)" = "$(cat "$MODDIR/device")" ] || fail "Built for $(cat "$MODDIR/device") only"
[ -f "$MODDIR/skip_mount" ] || fail "Already armed or missing guard"
[ -d "$MODDIR/candidate/vendor" ] || fail "Missing staged payload"
[ ! -e "$MODDIR/system" ] || fail "Unexpected existing system directory"
META=$(readlink -f /data/adb/metamodule)
[ "$META" = /data/adb/modules/mountify ] || fail "Validated packaging requires Mountify"
[ ! -e "$META/disable" ] && [ ! -e "$META/remove" ] || fail "Mountify disabled/removing"
[ "$(sha256sum /vendor/lib64/hw/audio.primary.kalama.so | cut -d ' ' -f 1)" = "$(cat "$MODDIR/hal.sha256")" ] || fail "Unexpected live HAL"
[ "$(sha256sum /vendor/lib64/libar-pal.so | cut -d ' ' -f 1)" = "$(cat "$MODDIR/pal.sha256")" ] || fail "Unexpected live PAL"
(cd "$MODDIR" && sha256sum -c payload.sha256) || fail "Payload hashes differ"
# shellcheck disable=SC2043 # Keep the list form for more competing modules
for id in rezoss_callscreen_compat_tx_test; do
    D=/data/adb/modules/$id
    [ ! -d "$D" ] || [ -f "$D/disable" ] || [ -f "$D/remove" ] || fail "Competing module $id"
done
# Failures before final guard removal cannot activate this module.
mkdir "$MODDIR/system"
cp -a "$MODDIR/candidate/vendor" "$MODDIR/system/vendor"
# Preserve directory-specific labels. In particular hw is vendor_hal_file;
# vendor_file denies SurfaceFlinger/audioserver directory enumeration.
for rel in vendor vendor/lib64 vendor/lib64/hw; do
    /data/adb/ksu/bin/busybox chcon --reference="/$rel" "$MODDIR/system/$rel"
done
/data/adb/ksu/bin/busybox chcon --reference=/vendor/lib64/hw/audio.primary.kalama.so "$MODDIR/system/vendor/lib64/hw/audio.primary.kalama.so"
/data/adb/ksu/bin/busybox chcon --reference=/vendor/lib64/libar-pal.so "$MODDIR/system/vendor/lib64/libcs-pal.so"
for p in "$MODDIR/system/vendor" "$MODDIR/system/vendor/lib64" "$MODDIR/system/vendor/lib64/hw" \
    "$MODDIR/system/vendor/lib64/hw/audio.primary.kalama.so" "$MODDIR/system/vendor/lib64/libcs-pal.so"; do
    EXPECTED=vendor_file
    [ "$p" != "$MODDIR/system/vendor/lib64/hw" ] || EXPECTED=vendor_hal_file
    case "$(ls -Zd "$p")" in *u:object_r:$EXPECTED:s0*) ;; *) fail "Wrong label: $p";; esac
done
cmp "$MODDIR/candidate/vendor/lib64/libcs-pal.so" "$MODDIR/system/vendor/lib64/libcs-pal.so" || fail "Companion changed"
cmp "$MODDIR/candidate/vendor/lib64/hw/audio.primary.kalama.so" "$MODDIR/system/vendor/lib64/hw/audio.primary.kalama.so" || fail "HAL changed"
# Install no later than this explicit step; retain a named guard for rollback.
mv "$MODDIR/skip_mount" "$MODDIR/armed_skip_mount_backup"
sync
echo "ARMED for next boot through Mountify. No live mounts or service restart performed."
echo "Rollback: create $MODDIR/disable and reboot."
