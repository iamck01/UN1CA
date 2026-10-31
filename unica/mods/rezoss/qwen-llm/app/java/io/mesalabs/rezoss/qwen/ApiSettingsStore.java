package io.mesalabs.rezoss.qwen;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.net.URI;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores provider settings privately and encrypts the API key with Android Keystore. */
final class ApiSettingsStore {
    private static final String PREFS = "api_settings";
    private static final String KEY_ALIAS = "rezoss_qwen_api_key_v1";
    private static final String KEY_AAD = "io.mesalabs.rezoss.qwen.api_key.v1";

    static final class Settings {
        final String endpoint;
        final String model;
        final String apiKey;
        Settings(String endpoint, String model, String apiKey) {
            this.endpoint = endpoint;
            this.model = model;
            this.apiKey = apiKey;
        }
        boolean ready() { return !endpoint.isEmpty() && !model.isEmpty() && !apiKey.isEmpty(); }
    }

    static Settings read(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String endpoint = prefs.getString("endpoint", "https://api.openai.com/v1/chat/completions");
        String model = prefs.getString("model", "");
        String encrypted = prefs.getString("api_key", "");
        String apiKey = encrypted.isEmpty() ? "" : decrypt(encrypted);
        return new Settings(endpoint, model, apiKey);
    }

    static Settings display(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new Settings(prefs.getString("endpoint", "https://api.openai.com/v1/chat/completions"),
                prefs.getString("model", ""), "");
    }

    /** True when generation should run on the device instead of the configured API. */
    static boolean local(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("local", false);
    }

    static void setLocal(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("local", enabled).apply();
        if (enabled) LocalEngine.get(context).prepareAsync();
        else LocalEngine.get(context).stop();
    }

    static boolean configured(Context context) {
        if (local(context)) return LocalEngine.get(context).usable();
        try {
            Settings settings = read(context);
            validateEndpoint(settings.endpoint);
            return !settings.model.trim().isEmpty() && settings.model.length() <= 128 && settings.ready();
        } catch (Exception invalid) {
            return false;
        }
    }

    static boolean hasSavedKey(Context context) {
        return !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString("api_key", "").isEmpty();
    }

    static void save(Context context, String endpoint, String model, String apiKey) throws Exception {
        String cleanEndpoint = endpoint == null ? "" : endpoint.trim();
        String cleanModel = model == null ? "" : model.trim();
        String cleanKey = apiKey == null ? "" : apiKey.trim();
        validateEndpoint(cleanEndpoint);
        if (cleanModel.isEmpty() || cleanModel.length() > 128)
            throw new IllegalArgumentException("Enter a model name (up to 128 characters).");
        if (cleanKey.length() > 4096)
            throw new IllegalArgumentException("The API key is too long.");

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String encrypted = prefs.getString("api_key", "");
        if (!cleanKey.isEmpty()) encrypted = encrypt(cleanKey);
        else if (!encrypted.isEmpty()) decrypt(encrypted);
        if (encrypted.isEmpty()) throw new IllegalArgumentException("Enter an API key first.");
        prefs.edit().putString("endpoint", cleanEndpoint).putString("model", cleanModel)
                .putString("api_key", encrypted).apply();
    }

    static void clearKey(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("api_key").apply();
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) { }
    }

    static void validateEndpoint(String endpoint) {
        if (endpoint == null || endpoint.length() > 2048)
            throw new IllegalArgumentException("Enter a valid HTTPS chat-completions URL.");
        try {
            URI uri = URI.create(endpoint);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException("Use an HTTPS URL without a username, query, or fragment.");
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Enter a valid HTTPS chat-completions URL.");
        }
    }

    private static SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return (SecretKey) store.getKey(KEY_ALIAS, null);
        if (!create) throw new IllegalStateException("API key encryption key is unavailable.");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    private static String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        cipher.updateAAD(KEY_AAD.getBytes("UTF-8"));
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(value.getBytes("UTF-8"));
        byte[] packed = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, packed, 0, iv.length);
        System.arraycopy(ciphertext, 0, packed, iv.length, ciphertext.length);
        return Base64.encodeToString(packed, Base64.NO_WRAP);
    }

    private static String decrypt(String encoded) throws Exception {
        byte[] packed = Base64.decode(encoded, Base64.NO_WRAP);
        if (packed.length < 29) throw new IllegalStateException("Saved API key is invalid.");
        byte[] iv = new byte[12];
        byte[] ciphertext = new byte[packed.length - iv.length];
        System.arraycopy(packed, 0, iv, 0, iv.length);
        System.arraycopy(packed, iv.length, ciphertext, 0, ciphertext.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, iv));
        cipher.updateAAD(KEY_AAD.getBytes("UTF-8"));
        return new String(cipher.doFinal(ciphertext), "UTF-8");
    }
}
