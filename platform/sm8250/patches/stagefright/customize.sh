# ACodec::reconfigEncoder4OtherApps() reads up to 512 bytes into a 255-byte
# buffer, FORTIFY aborts the process as soon as it is called. One UI 9
# sources use Codec2 and never reach it, but the Android 11 sm8250 vendor
# still exposes OMX video encoders, so the screen recorder crashes in
# setupAVCEncoderParameters(). Read at most 254 bytes instead.

# [mov w1, #0x1; mov w2, #0x200; mov x3, x21; mov w4, #0xff] -> [... mov w2, #0xfe ...]
if [ -f "$WORK_DIR/system/system/lib64/libstagefright.so" ]; then
    HEX_PATCH "$WORK_DIR/system/system/lib64/libstagefright.so" \
        "2100805202408052e30315aae41f8052" "21008052c21f8052e30315aae41f8052" \
        || LOGW "lib64/libstagefright.so: reconfigEncoder4OtherApps() does not match, skipping"
fi

# [movs r1, #0x1; mov.w r2, #0x200; mov r0, r7; mov r3, r6] -> [... mov.w r2, #0xfe ...]
if [ -f "$WORK_DIR/system/system/lib/libstagefright.so" ]; then
    HEX_PATCH "$WORK_DIR/system/system/lib/libstagefright.so" \
        "01214ff4007238463346" "01214ff0fe0238463346" \
        || LOGW "lib/libstagefright.so: reconfigEncoder4OtherApps() does not match, skipping"
fi
