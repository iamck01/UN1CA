package io.mesalabs.rezoss.qwen;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Log;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import org.json.*;

/**
 * Optional on-device backend: Qwen2.5-1.5B-Instruct Q4_K_M on the bundled llama.cpp server.
 * The model ships in a separate, same-signer APK and is copied once into private storage after
 * a SHA-256 check. The server only listens on loopback and requires a per-launch random key.
 */
final class LocalEngine {
    static final String MODEL_PACKAGE = "io.mesalabs.rezoss.qwen.model";
    static final String MODEL = "qwen2.5-1.5b-instruct-q4_k_m.gguf";
    private static final String SHA = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e";
    private static final long MODEL_SIZE = 1117320736L;
    private static final int CONTEXT = 4096;
    private static final long IDLE_STOP_MS = 120000;
    private static final String TAG = "RezossLLM";
    private static LocalEngine instance;

    private final Context context;
    private final ReentrantLock runner = new ReentrantLock();
    private final ExecutorService preparer = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, HttpURLConnection> active = new HashMap<>();
    private final Set<String> cancelled = new LinkedHashSet<>();
    private volatile Process process;
    private volatile String key;
    private volatile int port;
    private volatile long lastUse;
    private volatile boolean preparing;
    private volatile int progress;
    private volatile String failure;

    private LocalEngine(Context context) {
        this.context = context.getApplicationContext();
        janitor.scheduleAtFixedRate(() -> {
            if (process != null && !runner.isLocked() && SystemClock.elapsedRealtime() - lastUse > IDLE_STOP_MS) stop();
        }, 30, 30, TimeUnit.SECONDS);
    }

    static synchronized LocalEngine get(Context context) {
        if (instance == null) instance = new LocalEngine(context);
        return instance;
    }

    private File modelFile() { return new File(context.getFilesDir(), MODEL); }
    private File stampFile() { return new File(context.getFilesDir(), SHA + ".verified"); }

    /** The model pack is installed and signed with the same certificate as this app. */
    boolean packInstalled() {
        try {
            PackageManager pm = context.getPackageManager();
            pm.getPackageInfo(MODEL_PACKAGE, 0);
            return pm.checkSignatures(context.getPackageName(), MODEL_PACKAGE) == PackageManager.SIGNATURE_MATCH;
        } catch (PackageManager.NameNotFoundException missing) {
            return false;
        }
    }

    boolean modelReady() { return modelFile().length() == MODEL_SIZE && stampFile().exists(); }

    boolean usable() { return modelReady() || packInstalled(); }

    /** Short status for the settings screen. */
    String status() {
        if (modelReady()) return process != null && process.isAlive() ? "running" : "ready";
        if (preparing) return "preparing " + progress + "%";
        if (failure != null) return "failed: " + failure;
        return packInstalled() ? "not_prepared" : "model_missing";
    }

    /** Copies and verifies the model in the background; safe to call repeatedly. */
    synchronized void prepareAsync() {
        if (preparing || modelReady() || !packInstalled()) return;
        preparing = true;
        failure = null;
        progress = 0;
        preparer.execute(() -> {
            try { prepare(); }
            catch (Exception error) {
                failure = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                Log.w(TAG, "local model prepare failed: " + failure);
            } finally { preparing = false; }
        });
    }

    private void prepare() throws Exception {
        File target = modelFile();
        File part = new File(context.getFilesDir(), MODEL + ".part");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        Context pack = context.createPackageContext(MODEL_PACKAGE, 0);
        try (InputStream source = pack.getAssets().open(MODEL);
             FileOutputStream sink = new FileOutputStream(part)) {
            byte[] block = new byte[1024 * 1024];
            int count;
            while ((count = source.read(block)) != -1) {
                total += count;
                if (total > MODEL_SIZE) throw new IOException("model size");
                digest.update(block, 0, count);
                sink.write(block, 0, count);
                progress = (int) (total * 100 / MODEL_SIZE);
            }
            sink.getFD().sync();
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        if (total != MODEL_SIZE || !SHA.contentEquals(hex)) {
            part.delete();
            throw new IOException("model integrity");
        }
        if (!part.renameTo(target)) throw new IOException("model rename");
        if (!stampFile().createNewFile() && !stampFile().exists()) throw new IOException("model stamp");
        Log.i(TAG, "local model prepared");
    }

    /** OpenAI-style chat completion served by the on-device model. */
    String complete(String system, String user, int maxTokens, boolean jsonReplies,
            long deadline, String requestId) throws Exception {
        if (!modelReady()) {
            prepareAsync();
            throw new ApiClient.ApiException(packInstalled() ? "local_model_preparing" : "local_model_missing");
        }
        String hot = ThermalGuard.blocked(context);
        if (hot != null) { stop(); throw new ApiClient.ApiException(hot); }
        long wait = deadline - SystemClock.elapsedRealtime();
        if (wait < 1 || !runner.tryLock(wait, TimeUnit.MILLISECONDS)) throw new ApiClient.ApiException("busy");
        try {
            synchronized (active) { if (cancelled.remove(requestId)) throw new ApiClient.ApiException("cancelled"); }
            long started = SystemClock.elapsedRealtime();
            start(deadline, requestId);
            String body = fit(system, user, maxTokens, deadline, requestId);
            JSONArray messages = new JSONArray();
            if (system != null && !system.isEmpty())
                messages.put(new JSONObject().put("role", "system").put("content", system));
            messages.put(new JSONObject().put("role", "user").put("content", body));
            JSONObject request = new JSONObject().put("messages", messages).put("max_tokens", maxTokens)
                    .put("temperature", 0.3).put("stream", false).put("cache_prompt", true);
            if (jsonReplies) {
                JSONObject properties = new JSONObject();
                for (String tone : new String[]{"negative", "neutral", "positive"})
                    properties.put(tone, new JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 96));
                JSONObject schema = new JSONObject().put("type", "object").put("properties", properties)
                        .put("required", new JSONArray().put("negative").put("neutral").put("positive"))
                        .put("additionalProperties", false);
                request.put("response_format", new JSONObject().put("type", "json_schema")
                        .put("json_schema", new JSONObject().put("name", "replies").put("schema", schema)));
            }
            JSONObject response = post("/v1/chat/completions", request, deadline, requestId);
            JSONArray choices = response.optJSONArray("choices");
            JSONObject message = choices == null || choices.length() == 0 ? null
                    : choices.getJSONObject(0).optJSONObject("message");
            Object content = message == null ? null : message.opt("content");
            if (!(content instanceof String) || ((String) content).trim().isEmpty())
                throw new ApiClient.ApiException("invalid_response");
            Log.i(TAG, "local inference elapsedMs=" + (SystemClock.elapsedRealtime() - started));
            return (String) content;
        } finally {
            lastUse = SystemClock.elapsedRealtime();
            runner.unlock();
        }
    }

    /** Keeps the prompt inside the context window by dropping the oldest input first. */
    private String fit(String system, String user, int maxTokens, long deadline, String requestId) throws Exception {
        int budget = CONTEXT - maxTokens - 64;
        String body = user;
        for (int tries = 0; tries < 8; tries++) {
            String all = (system == null ? "" : system) + "\n" + body;
            int tokens = post("/tokenize", new JSONObject().put("content", all), deadline, requestId)
                    .getJSONArray("tokens").length();
            if (tokens <= budget) return body;
            JSONArray conversation = null;
            try { conversation = new JSONArray(body); } catch (JSONException plainText) { }
            if (conversation != null) {
                if (conversation.length() <= 1) break;
                conversation.remove(0);
                body = conversation.toString();
            } else {
                int cut = Math.max(1, (int) (body.length() * (1 - (double) budget / tokens)) + body.length() / 10);
                int newline = body.indexOf('\n', cut);
                body = body.substring(newline > 0 && newline - cut < 200 ? newline + 1 : Math.min(cut, body.length() - 1));
            }
        }
        throw new ApiClient.ApiException("context_limit");
    }

    private void start(long deadline, String requestId) throws Exception {
        if (process != null && process.isAlive()) return;
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        key = android.util.Base64.encodeToString(secret, android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE);
        File keyFile = new File(context.getFilesDir(), "server.key");
        try (FileOutputStream stream = new FileOutputStream(keyFile)) {
            stream.write((key + "\n").getBytes(StandardCharsets.UTF_8));
        }
        try (ServerSocket available = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = available.getLocalPort();
        }
        File binary = new File(context.getApplicationInfo().nativeLibraryDir, "librezoss_llama.so");
        ProcessBuilder builder = new ProcessBuilder(binary.getAbsolutePath(), "-m", modelFile().getAbsolutePath(),
                "--host", "127.0.0.1", "--port", String.valueOf(port), "-c", String.valueOf(CONTEXT),
                "-t", "4", "-tb", "4", "-np", "1", "--no-webui", "--jinja",
                "--api-key-file", keyFile.getAbsolutePath(), "--log-disable");
        builder.redirectOutput(new File("/dev/null"));
        builder.redirectError(new File("/dev/null"));
        process = builder.start();
        while (SystemClock.elapsedRealtime() + 300 < deadline) {
            if (!process.isAlive()) { process = null; throw new ApiClient.ApiException("local_server_exit"); }
            try {
                post("/tokenize", new JSONObject().put("content", "ready"),
                        Math.min(deadline, SystemClock.elapsedRealtime() + 1000), requestId);
                Log.i(TAG, "local server ready");
                return;
            } catch (ApiClient.ApiException probe) {
                // A probe timing out only means the model is still loading.
                if ("cancelled".equals(probe.status)) { stop(); throw probe; }
                Thread.sleep(150);
            } catch (IOException waiting) {
                Thread.sleep(150);
            }
        }
        throw new ApiClient.ApiException("deadline");
    }

    private JSONObject post(String path, JSONObject body, long deadline, String requestId) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new ApiClient.ApiException("cancelled");
        long left = deadline - SystemClock.elapsedRealtime();
        if (left <= 0) throw new ApiClient.ApiException("deadline");
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        synchronized (active) {
            if (cancelled.remove(requestId)) throw new ApiClient.ApiException("cancelled");
            active.put(requestId, connection);
        }
        try {
            connection.setConnectTimeout((int) Math.min(left, 1000));
            connection.setReadTimeout((int) Math.max(1, left));
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            int code = connection.getResponseCode();
            if (code == 400 && path.startsWith("/v1/")) throw new ApiClient.ApiException("context_limit");
            if (code != 200) throw new IOException("HTTP " + code);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] block = new byte[4096];
                int count;
                while ((count = in.read(block)) != -1) {
                    if (out.size() + count > 262144) throw new ApiClient.ApiException("invalid_response");
                    out.write(block, 0, count);
                }
            }
            return new JSONObject(out.toString("UTF-8"));
        } catch (SocketTimeoutException timeout) {
            throw new ApiClient.ApiException("deadline");
        } finally {
            synchronized (active) { if (active.get(requestId) == connection) active.remove(requestId); }
            connection.disconnect();
        }
    }

    void cancel(String requestId) {
        if (requestId == null || requestId.isEmpty()) return;
        HttpURLConnection connection;
        synchronized (active) {
            connection = active.remove(requestId);
            if (connection == null) {
                cancelled.add(requestId);
                while (cancelled.size() > 128) cancelled.remove(cancelled.iterator().next());
            }
        }
        if (connection != null) connection.disconnect();
    }

    void cancelAll() {
        HttpURLConnection[] connections;
        synchronized (active) {
            connections = active.values().toArray(new HttpURLConnection[0]);
            active.clear();
            cancelled.clear();
        }
        for (HttpURLConnection connection : connections) connection.disconnect();
    }

    /** Stops the server (idle timeout, thermal block or switching back to API mode). */
    void stop() {
        Process running = process;
        process = null;
        if (running != null) running.destroyForcibly();
    }
}
