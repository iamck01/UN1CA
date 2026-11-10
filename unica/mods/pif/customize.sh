APPLY_PATCH "system" "system/framework/framework.jar" \
    "$MODPATH/framework.jar/0001-Introduce-PlayIntegrityHooks.patch"

if [ -f "$APKTOOL_DIR/system/framework/framework.jar/smali_classes6/io/mesalabs/unica/PlayIntegrityHooks.smali" ]; then
    SMALI_PATCH "system" "system/framework/framework.jar" \
        "smali/android/app/Instrumentation.smali" "replace" \
        'newApplication(Ljava/lang/Class;Landroid/content/Context;)Landroid/app/Application;' \
        'return-object p0' \
        '    invoke-static {p1}, Lio/mesalabs/unica/PlayIntegrityHooks;->setProps(Landroid/content/Context;)V\n\n    return-object p0' \
        > /dev/null
    SMALI_PATCH "system" "system/framework/framework.jar" \
        "smali/android/app/Instrumentation.smali" "replace" \
        'newApplication(Ljava/lang/ClassLoader;Ljava/lang/String;Landroid/content/Context;)Landroid/app/Application;' \
        'return-object p0' \
        '    invoke-static {p3}, Lio/mesalabs/unica/PlayIntegrityHooks;->setProps(Landroid/content/Context;)V\n\n    return-object p0' \
        > /dev/null
    APPLY_PATCH "system" "system/framework/services.jar" \
        "$MODPATH/services.jar/0001-Introduce-PlayIntegrityHooks.patch"

    if [ ! -f "$APKTOOL_DIR/system/framework/framework.jar/smali_classes6/io/mesalabs/unica/KeyboxImitationHooks.smali" ]; then
        SMALI_PATCH "system" "system/framework/framework.jar" \
            "smali_classes6/io/mesalabs/unica/PlayIntegrityHooks.smali" "return" \
            'shouldBlockKeyAttestation()Z' 'true' || true
    fi
else
    LOGW "PlayIntegrityHooks not present in framework.jar, skipping PIF instrumentation hooks"
fi
