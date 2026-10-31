package io.mesalabs.rezoss.qwen;

import android.content.Context;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** OpenAI-compatible, non-streaming chat completion transport. Prompts and keys are never logged. */
final class ApiClient {
    private static final int MAX_RESPONSE_BYTES = 262144;
    private static final int MAX_OUTPUT_CHARS = 8192;
    private static final Map<String, HttpURLConnection> ACTIVE = new HashMap<>();
    private static final Set<String> CANCELLED = new LinkedHashSet<>();

    static final class ApiException extends IOException {
        final String status;
        ApiException(String status) { super(status); this.status = status; }
    }

    static String complete(Context context, String systemPrompt, String userPrompt,
            int maxTokens, long deadline, String requestId) throws Exception {
        return complete(context, systemPrompt, userPrompt, maxTokens, false, deadline, requestId);
    }

    /** jsonReplies constrains the on-device model to the reply JSON object; APIs ignore it. */
    static String complete(Context context, String systemPrompt, String userPrompt,
            int maxTokens, boolean jsonReplies, long deadline, String requestId) throws Exception {
        if (userPrompt == null || userPrompt.isEmpty() || userPrompt.length() > 16000
                || maxTokens < 1 || maxTokens > 2048 || requestId == null || requestId.isEmpty())
            throw new ApiException("invalid_request");
        if (Thread.currentThread().isInterrupted()) throw new ApiException("cancelled");
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining < 1) throw new ApiException("deadline");
        if (ApiSettingsStore.local(context))
            return LocalEngine.get(context).complete(systemPrompt, userPrompt, maxTokens, jsonReplies, deadline, requestId);

        ApiSettingsStore.Settings settings = ApiSettingsStore.read(context);
        if (!settings.ready()) throw new ApiException("api_unconfigured");
        ApiSettingsStore.validateEndpoint(settings.endpoint);

        JSONArray messages = new JSONArray();
        if (systemPrompt != null && !systemPrompt.isEmpty())
            messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        messages.put(new JSONObject().put("role", "user").put("content", userPrompt));
        JSONObject payload = new JSONObject().put("model", settings.model)
                .put("messages", messages).put("max_tokens", maxTokens).put("stream", false);

        HttpURLConnection connection = (HttpURLConnection) new URL(settings.endpoint).openConnection();
        synchronized (ACTIVE) {
            if (CANCELLED.remove(requestId)) {
                connection.disconnect();
                throw new ApiException("cancelled");
            }
            if (ACTIVE.containsKey(requestId)) throw new ApiException("duplicate_request");
            ACTIVE.put(requestId, connection);
        }
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout((int) Math.max(1, Math.min(10000, remaining)));
            connection.setReadTimeout((int) Math.max(1, Math.min(45000, remaining)));
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + settings.apiKey);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setDoOutput(true);
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            if (Thread.currentThread().isInterrupted()) throw new ApiException("cancelled");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new ApiException("api_http_" + status);
            byte[] response;
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] block = new byte[4096];
                int count;
                while ((count = input.read(block)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new ApiException("cancelled");
                    if (SystemClock.elapsedRealtime() >= deadline) throw new ApiException("deadline");
                    if (output.size() + count > MAX_RESPONSE_BYTES) throw new ApiException("response_too_large");
                    output.write(block, 0, count);
                }
                response = output.toByteArray();
            }
            JSONObject result = new JSONObject(new String(response, StandardCharsets.UTF_8));
            JSONArray choices = result.optJSONArray("choices");
            JSONObject choice = choices == null || choices.length() == 0 ? null : choices.optJSONObject(0);
            JSONObject message = choice == null ? null : choice.optJSONObject("message");
            String content = message == null ? null : message.optString("content", null);
            if (content == null) throw new ApiException("invalid_response");
            content = content.trim();
            if (content.isEmpty() || content.length() > MAX_OUTPUT_CHARS)
                throw new ApiException("invalid_output");
            return content;
        } catch (ApiException failure) {
            throw failure;
        } catch (IOException failure) {
            if (SystemClock.elapsedRealtime() >= deadline) throw new ApiException("deadline");
            if (Thread.currentThread().isInterrupted()) throw new ApiException("cancelled");
            throw new ApiException("network_error");
        } catch (Exception failure) {
            throw new ApiException("invalid_response");
        } finally {
            synchronized (ACTIVE) { if (ACTIVE.get(requestId) == connection) ACTIVE.remove(requestId); }
            connection.disconnect();
        }
    }

    static void cancel(Context context, String requestId) {
        LocalEngine.get(context).cancel(requestId);
        cancel(requestId);
    }

    static void cancelAll(Context context) {
        LocalEngine.get(context).cancelAll();
        cancelAll();
    }

    static void cancel(String requestId) {
        if (requestId == null || requestId.isEmpty()) return;
        HttpURLConnection connection;
        synchronized (ACTIVE) {
            connection = ACTIVE.remove(requestId);
            if (connection == null) {
                CANCELLED.add(requestId);
                while (CANCELLED.size() > 128) CANCELLED.remove(CANCELLED.iterator().next());
            }
        }
        if (connection != null) connection.disconnect();
    }

    static void cancelAll() {
        HttpURLConnection[] connections;
        synchronized (ACTIVE) {
            connections = ACTIVE.values().toArray(new HttpURLConnection[0]);
            ACTIVE.clear();
            CANCELLED.clear();
        }
        for (HttpURLConnection connection : connections) connection.disconnect();
    }
}
