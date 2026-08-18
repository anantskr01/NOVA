package com.aircontrol;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Best-effort always-listening NOVA voice loop.
 * Android's SpeechRecognizer is session based, so NOVA restarts the same
 * recognizer after each completed/failed session instead of pretending that
 * Android provides a permanent microphone stream.
 */
public final class NovaVoiceService extends Service {
    private static final String TAG = "NovaVoice";
    private static final String CHANNEL = "nova_voice";
    private static final int NOTIFICATION_ID = 2002;

    private static final long ACTIVE_WINDOW_MS = 15000L;
    private static final long NORMAL_RESTART_MS = 350L;
    private static final long ERROR_RESTART_MS = 1200L;
    private static final long START_GUARD_MS = 350L;
    private static final long NO_SPEECH_RESTART_MS = 650L;
    private static final long MIN_SESSION_GAP_MS = 450L;

    // Double-clap wake detection. This uses SpeechRecognizer RMS callbacks,
    // so NOVA does not open a second microphone stream while recognizing speech.
    private static final long CLAP_PAIR_WINDOW_MS = 1100L;
    private static final long CLAP_DEBOUNCE_MS = 180L;
    private static final float CLAP_MIN_RMS_DB = 5.0f;
    private static final float CLAP_RISE_DB = 10.0f;
    private static final float CLAP_BASELINE_ALPHA = 0.08f;
    private float clapBaselineDb = -40f;
    private long lastClapTime = 0L;
    private int clapCount = 0;

    public static final String ACTION_STOP = "com.aircontrol.STOP_NOVA_VOICE";

    private SpeechRecognizer recognizer;
    private Handler handler;
    private NovaAssistant assistant;

    private boolean running;
    private boolean starting;
    private boolean awake;
    private long awakeUntil;

    private String lastPhrase = "";
    private long lastPhraseTime;
    private long lastStartTime;
    private boolean restartScheduled;
    private boolean sessionFinished;

    private WindowManager windowManager;
    private NovaWakeOverlay wakeOverlay;
    private ToneGenerator toneGenerator;

    @Override public void onCreate() {
        super.onCreate();
        handler = new Handler(getMainLooper());
        assistant = new NovaAssistant(this, this::updateNotification);
        createChannel();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        try { toneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70); } catch (Exception ignored) { }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            Notification n = notification("Always listening for Hey NOVA");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Exception e) {
            Log.e(TAG, "Voice foreground service failed", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!running) {
            running = true;
            restartScheduled = false;
            awake = false;
            sessionFinished = false;
            updateNotification("Voice service started • preparing microphone");
            scheduleListening(START_GUARD_MS);
        }
        return START_STICKY;
    }

    private void scheduleListening(long delay) {
        if (handler == null || !running || restartScheduled) return;
        restartScheduled = true;
        handler.postDelayed(() -> {
            restartScheduled = false;
            if (!running) return;
            startListeningSession();
        }, Math.max(0L, delay));
    }

    private void startListeningSession() {
        if (!running || starting) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateNotification("Microphone permission required");
            Log.e(TAG, "RECORD_AUDIO permission is not granted");
            return;
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("Speech recognition unavailable");
            Log.e(TAG, "SpeechRecognizer.isRecognitionAvailable() = false");
            scheduleListening(ERROR_RESTART_MS);
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastStartTime < MIN_SESSION_GAP_MS) {
            scheduleListening(MIN_SESSION_GAP_MS);
            return;
        }
        lastStartTime = now;
        starting = true;

        destroyRecognizer();

        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            sessionFinished = false;
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    starting = false;
                    updateNotification(awake ? "NOVA awake • listening" : "Listening for Hey NOVA");
                }

                @Override public void onBeginningOfSpeech() {
                    updateNotification(awake ? "Hearing command" : "Hearing voice input");
                }

                @Override public void onRmsChanged(float rmsdB) {
                    detectDoubleClap(rmsdB);
                }
                @Override public void onBufferReceived(byte[] buffer) { }

                @Override public void onEndOfSpeech() {
                    // Some recognition providers do not deliver a result promptly.
                    // Give them a short grace period, then restart the session.
                    handler.postDelayed(() -> {
                        if (!running || sessionFinished) return;
                        sessionFinished = true;
                        starting = false;
                        destroyRecognizer();
                        scheduleListening(NO_SPEECH_RESTART_MS);
                    }, 900L);
                }

                @Override public void onError(int error) {
                    if (sessionFinished) return;
                    sessionFinished = true;
                    starting = false;
                    if (!running) return;
                    Log.d(TAG, "SpeechRecognizer error=" + error);
                    updateNotification("Voice retrying (" + error + ")");
                    destroyRecognizer();
                    scheduleListening(ERROR_RESTART_MS);
                }

                @Override public void onResults(Bundle results) {
                    if (sessionFinished) return;
                    sessionFinished = true;
                    starting = false;
                    ArrayList<String> values =
                            results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (values != null && !values.isEmpty()) {
                        process(values.get(0));
                    }
                    destroyRecognizer();
                    if (running) scheduleListening(NORMAL_RESTART_MS);
                }

                @Override public void onPartialResults(Bundle partialResults) {
                    ArrayList<String> values =
                            partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (values == null || values.isEmpty()) return;
                    String phrase = values.get(0);
                    if (containsWake(phrase) && !awake) {
                        long now = System.currentTimeMillis();
                        awake = true;
                        awakeUntil = now + ACTIVE_WINDOW_MS;
                        triggerWakeExperience();
                    }
                }

                @Override public void onEvent(int eventType, Bundle params) { }
            });

            Intent input = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            input.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            input.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            input.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            input.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            input.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900);
            input.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700);
            input.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 300);

            recognizer.startListening(input);
        } catch (Exception e) {
            starting = false;
            Log.e(TAG, "START LISTENING FAILED", e);
            destroyRecognizer();
            if (running) scheduleListening(ERROR_RESTART_MS);
        }
    }

    /**
     * Detects two sharp microphone-level peaks inside the active recognition
     * session. Because this reuses SpeechRecognizer's RMS callback, it does
     * not create a competing AudioRecord microphone stream.
     */
    private void detectDoubleClap(float rmsdB) {
        if (!running || awake || Float.isNaN(rmsdB) || Float.isInfinite(rmsdB)) return;

        // Establish a slowly moving noise floor. A clap should rise sharply
        // above that floor rather than merely being loud for a long time.
        clapBaselineDb = clapBaselineDb * (1f - CLAP_BASELINE_ALPHA)
                + rmsdB * CLAP_BASELINE_ALPHA;

        long now = System.currentTimeMillis();
        boolean spike = rmsdB >= CLAP_MIN_RMS_DB
                && rmsdB >= clapBaselineDb + CLAP_RISE_DB;

        if (!spike || now - lastClapTime < CLAP_DEBOUNCE_MS) return;

        if (clapCount == 0 || now - lastClapTime > CLAP_PAIR_WINDOW_MS) {
            clapCount = 1;
        } else {
            clapCount++;
        }
        lastClapTime = now;

        if (clapCount >= 2) {
            clapCount = 0;
            awake = true;
            awakeUntil = now + ACTIVE_WINDOW_MS;
            triggerWakeExperience();
        }
    }

    private void process(String heard) {
        if (heard == null || heard.trim().isEmpty()) return;

        String text = heard.trim();
        long now = System.currentTimeMillis();

        if (text.equalsIgnoreCase(lastPhrase) && now - lastPhraseTime < 1400L) return;
        lastPhrase = text;
        lastPhraseTime = now;

        boolean wake = containsWake(text);
        if (wake) {
            awake = true;
            awakeUntil = now + ACTIVE_WINDOW_MS;
            triggerWakeExperience();
            assistant.handleVoice(text);
            return;
        }

        if (awake && now < awakeUntil) {
            assistant.handle(text);
            awakeUntil = now + ACTIVE_WINDOW_MS;
        } else {
            awake = false;
        }
        // A completed speech session is a natural boundary for clap detection.
        if (!awake) clapCount = 0;
    }

    private boolean containsWake(String text) {
        String lower = text.toLowerCase(Locale.ROOT).trim();
        return lower.contains("hey nova") || lower.equals("nova") ||
                lower.startsWith("nova ");
    }

    private void triggerWakeExperience() {
        updateNotification("NOVA ONLINE • listening");
        playWakeTone();
        showWakeOverlay();
    }

    private void playWakeTone() {
        try {
            if (toneGenerator != null) {
                toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 90);
            }
        } catch (Exception ignored) { }
    }

    private void showWakeOverlay() {
        if (windowManager == null || android.provider.Settings.canDrawOverlays(this) == false) return;
        handler.post(() -> {
            removeWakeOverlay();
            wakeOverlay = new NovaWakeOverlay(this);
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            try {
                windowManager.addView(wakeOverlay, params);
                wakeOverlay.showFor(1450L, this::removeWakeOverlay);
            } catch (Exception e) {
                Log.d(TAG, "Wake overlay unavailable: " + e.getMessage());
                wakeOverlay = null;
            }
        });
    }

    private void removeWakeOverlay() {
        if (windowManager == null || wakeOverlay == null) return;
        try { windowManager.removeView(wakeOverlay); } catch (Exception ignored) { }
        wakeOverlay = null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "NOVA Voice", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification notification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return builder.setContentTitle("NOVA Voice")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null && running) manager.notify(NOTIFICATION_ID, notification(text));
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
    }

    @Override public void onDestroy() {
        running = false;
        starting = false;
        restartScheduled = false;
        awake = false;
        clapCount = 0;
        lastClapTime = 0L;
        sessionFinished = true;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        destroyRecognizer();
        removeWakeOverlay();
        if (toneGenerator != null) { try { toneGenerator.release(); } catch (Exception ignored) { } toneGenerator = null; }
        if (assistant != null) {
            assistant.destroy();
            assistant = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
