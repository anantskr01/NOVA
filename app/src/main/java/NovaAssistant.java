package com.aircontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

/** NOVA voice/UI front-end. */
public final class NovaAssistant {

    public interface Listener {
        void onStatus(String text);
    }

    private static final String TAG = "NovaAssistant";
    private static final String PREFS = "nova_ai_settings";
    private static final String ENDPOINT = "endpoint";
    private static final String MODEL = "model";
    private static final String WAKE_PHRASE = "hey nova";

    private static final String LOCAL_ENDPOINT = "http://192.168.29.210:11434/v1/chat/completions";
    private static final String LOCAL_MODEL = "qwen2.5:1.5b";

    private final Context context;
    private final Listener listener;
    private final SharedPreferences prefs;
    private final NovaSecureStore secureStore;
    private final NovaBrain brain;
    private TextToSpeech tts;

    public NovaAssistant(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        secureStore = new NovaSecureStore(this.context);

        String savedEndpoint = prefs.getString(ENDPOINT, "").trim();
        if (savedEndpoint.isEmpty() || savedEndpoint.contains("api.openai.com")) {
            prefs.edit()
                    .putString(ENDPOINT, LOCAL_ENDPOINT)
                    .putString(MODEL, LOCAL_MODEL)
                    .apply();
        }

        brain = new NovaBrain(this.context, new NovaBrain.Listener() {
            @Override public void status(String text) { NovaAssistant.this.status(text); }
            @Override public void reply(String text) { NovaAssistant.this.say(text); }
        });

        tts = new TextToSpeech(this.context, result -> {
            if (result == TextToSpeech.SUCCESS) {
                try { tts.setLanguage(Locale.getDefault()); } catch (Exception ignored) { }
            }
        });
    }

    public void saveAiSettings(String endpoint, String apiKey, String model) {
        prefs.edit()
                .putString(ENDPOINT, endpoint == null ? "" : endpoint.trim())
                .putString(MODEL, model == null || model.trim().isEmpty() ? LOCAL_MODEL : model.trim())
                .apply();
        secureStore.putApiKey(apiKey == null ? "" : apiKey.trim());
        status("AI CORE CONFIGURED • KEY PROTECTED");
    }

    public String getEndpoint() { return prefs.getString(ENDPOINT, LOCAL_ENDPOINT); }
    public String getModel() { return prefs.getString(MODEL, LOCAL_MODEL); }
    public boolean hasAiCore() { return !getEndpoint().trim().isEmpty(); }

    public void handleVoice(String raw) {
        if (raw == null) return;
        String text = raw.trim();
        if (text.isEmpty()) return;

        // SpeechRecognizer commonly returns punctuation variants such as
        // "Hey, NOVA". Normalize only for wake-word matching.
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ")
                .trim();
        int wake = normalized.indexOf(WAKE_PHRASE);

        if (wake >= 0) {
            // Map the normalized wake phrase back to the original text by finding
            // the first occurrence of "hey" and removing the following wake words.
            String lowerOriginal = text.toLowerCase(Locale.ROOT);
            int hey = lowerOriginal.indexOf("hey");
            if (hey >= 0) {
                String afterHey = text.substring(hey + 3).trim();
                afterHey = afterHey.replaceFirst("^[,;:!?\\-]+\\s*", "");
                if (afterHey.toLowerCase(Locale.ROOT).startsWith("nova")) {
                    text = afterHey.substring(4).trim();
                } else {
                    text = normalized.substring(WAKE_PHRASE.length()).trim();
                }
            } else {
                text = normalized.substring(WAKE_PHRASE.length()).trim();
            }
            if (text.isEmpty()) {
                say("Yes. I am listening.");
                return;
            }
        } else if (normalized.equals("nova")) {
            say("Yes. I am listening.");
            return;
        }

        handle(text);
    }

    public void handle(String command) {
        if (command == null) return;
        command = command.trim();
        if (command.isEmpty()) return;
        status("NOVA • " + command);
        brain.handle(command, getEndpoint(), secureStore.getApiKey(), getModel());
    }

    private void status(String text) {
        if (text == null || text.trim().isEmpty()) return;
        if (listener != null) listener.onStatus(text);
        Log.d(TAG, "STATUS: " + text);
    }

    private void say(String text) {
        if (text == null || text.trim().isEmpty()) return;
        status(text);
        if (tts != null) {
            try { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "NOVA"); }
            catch (Exception e) { Log.e(TAG, "TTS ERROR", e); }
        }
    }

    public void destroy() {
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
                tts = null;
            }
        } catch (Exception e) { Log.e(TAG, "TTS SHUTDOWN ERROR", e); }
        try { brain.destroy(); }
        catch (Exception e) { Log.e(TAG, "BRAIN SHUTDOWN ERROR", e); }
    }
}
