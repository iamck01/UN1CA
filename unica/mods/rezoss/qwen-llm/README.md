# Rezoss AI API app

This module builds `QWEN_LLM_rezoss.apk`, a small settings app and trusted API bridge for the existing ROM text-generation flow.

## Setup

1. Install the ROM's `QWEN_LLM_rezoss.apk` and open **Rezoss AI**.
2. Choose OpenAI, Gemini, OpenRouter, or a custom endpoint; enter the provider's model name and API key.
3. Save the settings. The API key is encrypted with Android Keystore and is not included in the ROM build.

The app sends non-streaming OpenAI Chat Completions requests. The built-in presets use these endpoints:

- OpenAI: `https://api.openai.com/v1/chat/completions`
- Gemini's OpenAI-compatible endpoint: `https://generativelanguage.googleapis.com/v1beta/openai/chat/completions`
- OpenRouter: `https://openrouter.ai/api/v1/chat/completions`

Custom endpoints must accept a Bearer API key and OpenAI-style `chat/completions` requests, then return text in `choices[0].message.content`. This covers other providers that implement that protocol. There is no single protocol for every vendor: APIs that only expose a different native format need a matching adapter.

## On-device mode

Turn on **Run on this device** in Rezoss AI to generate replies and summaries locally with Qwen2.5-1.5B-Instruct (Q4_K_M) instead of an API. Nothing is sent to a provider in this mode.

1. Install `QWEN_LLM_rezoss_model.apk` (about 1.1 GB, built by `model/build.sh` and signed with the same certificate as the app).
2. Open Rezoss AI and turn on **Run on this device**. The app copies the model into its private storage and verifies its SHA-256 (about 1.1 GB of free space is needed).
3. Once it shows "On-device model ready", the model pack can be uninstalled.

The app starts the bundled llama.cpp server on loopback with a per-launch random key, stops it after two idle minutes or when the device gets hot, and trims the oldest input to fit a 4096-token context. Summaries can take longer than with an API; the ROM allows up to 75 seconds for them.

## Data flow

The ROM keeps its existing screen-text extraction, prompt adaptation, and reply/summary formats. The inference backend sends the resulting text to the configured endpoint and validates the response before returning it to the existing system caller. The API key stays inside the Rezoss AI app process; the AIOS bridge can request a completion but cannot read the saved key. Prompts, responses, and API keys are not written to logs.

Because generation uses a remote API, extracted screen text is sent to the endpoint selected by the user. The app requires HTTPS and does not follow redirects.

## Build

Run `build.sh` after sourcing the UN1CA `buildenv.sh`. The app is signed with the ROM platform certificate and written to the existing archived APK output path. It does not embed model weights; `model/build.sh` builds the optional on-device model pack separately.
