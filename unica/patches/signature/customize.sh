CERT_PREFIX="aosp"
$ROM_IS_OFFICIAL && CERT_PREFIX="unica"

if [ ! -f "$SRC_DIR/security/${CERT_PREFIX}_platform.x509.pem" ]; then
    ABORT "File not found: security/${CERT_PREFIX}_platform.x509.pem"
fi

APPLY_PATCH "system" "system/framework/framework.jar" "$MODPATH/framework.jar/0001-Allow-CorePatch-style-SigningDetails-bypass.patch" || true
APPLY_PATCH "system" "system/framework/framework.jar" "$MODPATH/framework.jar/0002-Allow-CorePatch-style-APK-digest-bypass.patch" || true
APPLY_PATCH "system" "system/framework/services.jar" "$MODPATH/services.jar/0001-Allow-custom-platform-signature.patch" || true
APPLY_PATCH "system" "system/framework/services.jar" "$MODPATH/services.jar/0002-Allow-custom-OfflineLanguageModel-update.patch" || true
APPLY_PATCH "system" "system/framework/services.jar" "$MODPATH/services.jar/0003-Allow-disabling-signature-verification.patch" || true

CERT_SIGNATURE="$(sed "/CERTIFICATE/d" "$SRC_DIR/security/${CERT_PREFIX}_platform.x509.pem" | tr -d "\n" | base64 -d | xxd -p -c 0)"
if [ -f "$APKTOOL_DIR/system/framework/services.jar/smali_classes2/com/android/server/pm/InstallPackageHelper.smali" ] && \
        grep -q "CONFIG_CUSTOM_PLATFORM_SIGNATURE" "$APKTOOL_DIR/system/framework/services.jar/smali_classes2/com/android/server/pm/InstallPackageHelper.smali"; then
    SMALI_PATCH "system" "system/framework/services.jar" \
        "smali_classes2/com/android/server/pm/InstallPackageHelper.smali" "replace" \
        '<init>(Lcom/android/server/pm/PackageManagerService;Lcom/android/server/pm/AppDataHelper;Lcom/android/server/pm/RemovePackageHelper;Lcom/android/server/pm/DeletePackageHelper;Lcom/android/server/pm/BroadcastHelper;)V' \
        'CONFIG_CUSTOM_PLATFORM_SIGNATURE' \
        "$CERT_SIGNATURE" \
        > /dev/null || LOGW "Custom platform signature string replace skipped"
fi

unset CERT_PREFIX CERT_SIGNATURE
