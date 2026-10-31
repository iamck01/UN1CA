package io.mesalabs.rezoss.nudge;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.IOException;

/** Calls the signed Rezoss AI provider without exposing the saved API key to AIOS. */
final class ApiProviderClient {
    static final String PACKAGE = "io.mesalabs.rezoss.qwen";
    private static final Uri URI = Uri.parse("content://io.mesalabs.rezoss.qwen.api");
    private final Context context;

    ApiProviderClient(Context context) { this.context = context.getApplicationContext(); }

    static boolean trusted(Context context, String pkg) {
        PackageManager pm = context.getPackageManager();
        if (pm.checkSignatures(context.getPackageName(), pkg) == PackageManager.SIGNATURE_MATCH) return true;
        byte[] expected = new byte[32];
        String hash = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8";
        for (int i = 0; i < expected.length; i++)
            expected[i] = (byte) Integer.parseInt(hash.substring(i * 2, i * 2 + 2), 16);
        return pm.hasSigningCertificate(pkg, expected, PackageManager.CERT_INPUT_SHA256);
    }

    boolean available() {
        try {
            PackageManager pm = context.getPackageManager();
            if (!pm.getApplicationInfo(PACKAGE, 0).enabled || !trusted(context, PACKAGE)) return false;
            ProviderInfo provider = pm.resolveContentProvider(URI.getAuthority(), 0);
            return provider != null && PACKAGE.equals(provider.packageName);
        } catch (Exception unavailable) {
            return false;
        }
    }

    boolean configured() {
        if (!available()) return false;
        try {
            Bundle result = context.getContentResolver().call(URI, "api_ready", null, null);
            return result != null && result.getBoolean("configured", false);
        } catch (Exception unavailable) {
            return false;
        }
    }

    String generate(String prompt, int maxTokens, long deadline, String requestId) throws Exception {
        if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("deadline");
        Bundle extras = new Bundle();
        extras.putString("prompt", prompt);
        extras.putString("request_id", requestId);
        extras.putLong("deadline", deadline);
        extras.putInt("max_tokens", maxTokens);
        Bundle result = context.getContentResolver().call(URI, "generate", null, extras);
        if (result == null) throw new IOException("api_unavailable");
        String status = result.getString("status", "api_unavailable");
        String content = result.getString("content");
        if (!"generated".equals(status) || content == null) throw new IOException(status);
        return content;
    }

    void cancel(String requestId) {
        if (requestId == null || requestId.isEmpty()) return;
        Bundle extras = new Bundle();
        extras.putString("request_id", requestId);
        try { context.getContentResolver().call(URI, "cancel", null, extras); }
        catch (Exception ignored) { }
    }
}
