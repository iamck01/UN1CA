# Fix camera lock for devices with a rear SLSI sensor
HEX_PATCH "$WORK_DIR/vendor/lib/hw/camera.qcom.so" \
    "726f2e626f6f742e666c6173682e6c6f636b656400" \
    "726f2e63616d6572612e6e6f746966795f6e666300" || true
HEX_PATCH "$WORK_DIR/vendor/lib/hw/com.qti.chi.override.so" \
    "726f2e626f6f742e666c6173682e6c6f636b656400" \
    "726f2e63616d6572612e6e6f746966795f6e666300" || true
HEX_PATCH "$WORK_DIR/vendor/lib64/hw/camera.qcom.so" \
    "726f2e626f6f742e666c6173682e6c6f636b656400" \
    "726f2e63616d6572612e6e6f746966795f6e666300" || true
HEX_PATCH "$WORK_DIR/vendor/lib64/hw/com.qti.chi.override.so" \
    "726f2e626f6f742e666c6173682e6c6f636b656400" \
    "726f2e63616d6572612e6e6f746966795f6e666300" || true

if ! grep -q "Camera End" "$WORK_DIR/vendor/ueventd.rc"; then
    {
        echo ""
        cat "$SRC_DIR/target/gts7fewifi/patches/camera/ueventd"
    } >> "$WORK_DIR/vendor/ueventd.rc"
fi
