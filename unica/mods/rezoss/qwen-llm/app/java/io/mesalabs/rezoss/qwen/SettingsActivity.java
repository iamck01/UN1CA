package io.mesalabs.rezoss.qwen;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small user-facing settings screen for an OpenAI-compatible API. */
public final class SettingsActivity extends Activity {
    private static final String OPENAI = "https://api.openai.com/v1/chat/completions";
    private static final String GEMINI = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions";
    private static final String OPENROUTER = "https://openrouter.ai/api/v1/chat/completions";
    private static final String[] PROVIDERS = {"OpenAI", "Gemini", "OpenRouter", "Custom endpoint"};
    private EditText endpoint;
    private EditText model;
    private EditText apiKey;
    private TextView keyState;
    private Spinner provider;
    private Switch local;
    private TextView localState;
    private final Runnable refreshLocal = new Runnable() {
        @Override public void run() {
            if (isFinishing() || localState == null) return;
            localState.setText(localStatus());
            main.postDelayed(this, 1000);
        }
    };
    private final ExecutorService storage = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Rezoss AI");

        ScrollView scroll = new ScrollView(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        page.setPadding(pad, pad, pad, pad);
        scroll.addView(page);

        TextView title = new TextView(this);
        title.setText("API settings");
        title.setTextSize(24);
        page.addView(title, matchWrap());

        TextView description = new TextView(this);
        description.setText("Keep the ROM's current screen-text extraction and reply/summary flow while choosing an API provider. Extracted text is sent to that provider when it needs a response.");
        description.setTextSize(14);
        description.setPadding(0, dp(12), 0, dp(18));
        page.addView(description, matchWrap());

        local = new Switch(this);
        local.setText("Run on this device (Qwen2.5-1.5B, no API)");
        local.setTextSize(16);
        local.setPadding(0, dp(4), 0, dp(4));
        page.addView(local, matchWrap());
        localState = new TextView(this);
        localState.setTextSize(12);
        localState.setPadding(0, 0, 0, dp(12));
        page.addView(localState, matchWrap());
        local.setChecked(ApiSettingsStore.local(this));
        local.setOnCheckedChangeListener((button, enabled) -> {
            storage.execute(() -> ApiSettingsStore.setLocal(this, enabled));
            localState.setText(localStatus());
        });

        addLabel(page, "Provider");
        provider = new Spinner(this);
        ArrayAdapter<String> providers = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, PROVIDERS);
        providers.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        provider.setAdapter(providers);
        page.addView(provider, matchWrap());

        addLabel(page, "Chat-completions URL (HTTPS)");
        endpoint = field(OPENAI,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        endpoint.setText(OPENAI);
        page.addView(endpoint, matchWrap());

        addLabel(page, "Model name");
        model = field("Provider's model ID", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        page.addView(model, matchWrap());

        addLabel(page, "API key");
        apiKey = field("Paste your API key", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        page.addView(apiKey, matchWrap());
        keyState = new TextView(this);
        keyState.setTextSize(12);
        keyState.setPadding(0, dp(4), 0, dp(12));
        page.addView(keyState, matchWrap());

        Button save = new Button(this);
        save.setText("Save settings");
        save.setOnClickListener(view -> saveSettings());
        page.addView(save, matchWrap());

        Button clear = new Button(this);
        clear.setText("Remove saved API key");
        clear.setOnClickListener(view -> confirmClearKey());
        page.addView(clear, matchWrap());

        TextView privacy = new TextView(this);
        privacy.setText("OpenAI, Gemini, OpenRouter, and custom OpenAI-compatible endpoints are supported. The API key is encrypted with Android Keystore. Extracted text is sent over HTTPS to the provider you choose.");
        privacy.setTextSize(12);
        privacy.setPadding(0, dp(18), 0, 0);
        page.addView(privacy, matchWrap());

        setContentView(scroll);
        loadSettings();
        provider.setSelection(providerFor(endpoint.getText().toString()));
        provider.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                String preset = endpointFor(position);
                if (preset != null) endpoint.setText(preset);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    private String localStatus() {
        LocalEngine engine = LocalEngine.get(this);
        String status = engine.status();
        String text;
        if (status.equals("ready") || status.equals("running")) text = "On-device model ready.";
        else if (status.startsWith("preparing")) text = "Preparing the on-device model (" + status.substring(10) + "). Keep the app open.";
        else if (status.equals("not_prepared")) text = "Model pack installed. Turn this on to prepare it (about 1.1 GB).";
        else if (status.equals("model_missing")) text = "Install QWEN_LLM_rezoss_model.apk to use the on-device model. After it is prepared you can uninstall the pack.";
        else text = "On-device model unavailable (" + status + ").";
        boolean on = local != null && local.isChecked();
        return (on ? "Replies and summaries run on this device; nothing is sent to an API. "
                : "Off: the API below is used. ") + text;
    }

    @Override protected void onResume() {
        super.onResume();
        main.post(refreshLocal);
    }

    @Override protected void onPause() {
        main.removeCallbacks(refreshLocal);
        super.onPause();
    }

    private static String endpointFor(int position) {
        switch (position) {
            case 0: return OPENAI;
            case 1: return GEMINI;
            case 2: return OPENROUTER;
            default: return null;
        }
    }

    private static int providerFor(String url) {
        if (OPENAI.equalsIgnoreCase(url)) return 0;
        if (GEMINI.equalsIgnoreCase(url)) return 1;
        if (OPENROUTER.equalsIgnoreCase(url)) return 2;
        return 3;
    }

    private void loadSettings() {
        try {
            ApiSettingsStore.Settings settings = ApiSettingsStore.display(this);
            endpoint.setText(settings.endpoint);
            model.setText(settings.model);
            apiKey.setText("");
            keyState.setText(!ApiSettingsStore.hasSavedKey(this)
                    ? "No API key saved."
                    : "API key saved. Leave this field empty to keep it.");
        } catch (Exception failure) {
            keyState.setText("Saved API key could not be read. Enter a new key to replace it.");
        }
    }

    private void saveSettings() {
        String endpointValue = endpoint.getText().toString();
        String modelValue = model.getText().toString();
        String keyValue = apiKey.getText().toString();
        storage.execute(() -> {
            String error = null;
            try { ApiSettingsStore.save(this, endpointValue, modelValue, keyValue); }
            catch (Exception failure) {
                error = failure instanceof IllegalArgumentException
                        ? failure.getMessage() : "Could not save settings.";
            }
            String message = error;
            main.post(() -> {
                if (isFinishing()) return;
                if (message != null) {
                    new AlertDialog.Builder(this).setMessage(message).setPositiveButton("OK", null).show();
                    return;
                }
                apiKey.setText("");
                keyState.setText("API key saved securely. Leave the field empty to keep it.");
                Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void confirmClearKey() {
        new AlertDialog.Builder(this)
                .setMessage("Remove the saved API key? Reply and summary generation will stop until you add another key.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (dialog, which) -> {
                    ApiSettingsStore.clearKey(this);
                    keyState.setText("No API key saved.");
                    Toast.makeText(this, "API key removed", Toast.LENGTH_SHORT).show();
                }).show();
    }

    private EditText field(String label, int inputType) {
        EditText value = new EditText(this);
        value.setSingleLine(true);
        value.setInputType(inputType);
        value.setHint(label);
        value.setContentDescription(label);
        value.setPadding(dp(12), dp(10), dp(12), dp(10));
        return value;
    }

    private void addLabel(LinearLayout page, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(14);
        label.setPadding(0, dp(8), 0, 0);
        page.addView(label, matchWrap());
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onDestroy() {
        storage.shutdown();
        super.onDestroy();
    }
}
