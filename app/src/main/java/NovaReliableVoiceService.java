package com.aircontrol;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Stable hands-free voice engine.
 *
 * Uses Android's normal SpeechRecognizer in short, automatically restarted
 * sessions. This deliberately avoids the Android-13+ AudioRecord/pipe/
 * segmented-session path, which is provider/device dependent and can return
 * repeated speech errors even when microphone permission is valid.
 */
public final class NovaReliableVoiceService extends Service {
    private static final String TAG = "NovaReliableVoice";
    private static final String CHANNEL_ID = "nova_voice";
    private static final int NOTIFICATION_ID = 2002;

    private static final long WAKE_WINDOW_MS = 15000L;
    private static final long RESTART_DELAY_MS = 450L;
    private static final long DUPLICATE_WINDOW_MS = 1400L;

    private static final long CLAP_PAIR_WINDOW_MS = 1100L;
    private static final long CLAP_DEBOUNCE_MS = 180L;
    private static final float CLAP_MIN_RMS_DB = 5.0f;
    private static final float CLAP_RISE_DB = 10.0f;
    private static final float CLAP_BASELINE_ALPHA = 0.08f;

    public static final String ACTION_STOP = "com.aircontrol.STOP_NOVA_VOICE";

    private Handler handler;
    private NovaAssistant assistant;
    private SpeechRecognizer recognizer;
    private boolean running;
    private boolean recognizerStarting;
    private boolean awake;
    private long awakeUntil;

    private String lastPhrase = "";
    private long lastPhraseTime;

    private int clapCount;
    private long lastClapTime;
    private float clapBaselineDb = -40f;

    private WindowManager windowManager;
    private NovaWakeOverlay wakeOverlay;
    private ToneGenerator toneGenerator;

    @Override public void onCreate() {
        super.onCreate();
        handler = new Handler(getMainLooper());
        assistant = new NovaAssistant(this, this::updateNotification);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createChannel();
        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70);
        } catch (Exception e) {
            Log.w(TAG, "ToneGenerator unavailable", e);
        }
        Log.d(TAG, "RELIABLE VOICE SERVICE CREATED");
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission is missing");
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            Notification notification = notification("NOVA Hands-Free microphone");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not start microphone foreground service", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!running) {
            running = true;
            updateNotification("NOVA • starting hands-free voice");
            handler.postDelayed(this::startRecognitionSession, 350L);
        }
        return START_STICKY;
    }

    private void startRecognitionSession() {
        if (!running || recognizerStarting) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("NOVA • speech recognition unavailable");
            handler.postDelayed(this::startRecognitionSession, 2000L);
            return;
        }

        recognizerStarting = true;
        destroyRecognizer();

        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    recognizerStarting = false;
                    updateNotification(awake ? "NOVA • listening for command" : "NOVA • Hands-Free listening");
                }

                @Override public void onBeginningOfSpeech() {
                    updateNotification(awake ? "NOVA • hearing command" : "NOVA • hearing");
                }

                @Override public void onRmsChanged(float rmsdB) {
                    detectDoubleClap(rmsdB);
                }

                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { }

                @Override public void onError(int error) {
                    recognizerStarting = false;
                    if (!running) return;
                    Log.w(TAG, "SpeechRecognizer error=" + error + "; restarting session");
                    updateNotification("NOVA • speech reconnecting");
                    scheduleRecognitionRestart();
                }

                @Override public void onResults(Bundle results) {
                    recognizerStarting = false;
                    if (running) processResults(results);
                    scheduleRecognitionRestart();
                }

                @Override public void onPartialResults(Bundle partialResults) {
                    ArrayList<String> values = partialResults == null ? null :
                            partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (values == null || values.isEmpty()) return;
                    String phrase = values.get(0);
                    if (containsWake(phrase) && !awake) {
                        wake();
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
            input.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());

            recognizer.startListening(input);
            recognizerStarting = false;
            updateNotification(awake ? "NOVA • listening for command" : "NOVA • Hands-Free listening");
            Log.d(TAG, "RELIABLE SPEECH SESSION STARTED");
        } catch (Exception e) {
            recognizerStarting = false;
            Log.e(TAG, "Failed to start speech session", e);
            destroyRecognizer();
            scheduleRecognitionRestart();
        }
    }

    private void processResults(Bundle results) {
        if (results == null) return;
        ArrayList<String> values = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (values == null || values.isEmpty()) return;

        String heard = values.get(0);
        if (heard == null) return;
        heard = heard.trim();
        if (heard.isEmpty()) return;

        long now = System.currentTimeMillis();
        if (heard.equalsIgnoreCase(lastPhrase) && now - lastPhraseTime < DUPLICATE_WINDOW_MS) return;
        lastPhrase = heard;
        lastPhraseTime = now;

        if (containsWake(heard)) {
            boolean wasAwake = awake;
            wake();
            if (assistant != null) {
                // A wake phrase can also carry the first command, e.g. "Hey Nova open Chrome".
                String command = removeWakePhrase(heard);
                if (!command.isEmpty()) assistant.handle(command);
                else if (wasAwake) assistant.handleVoice(heard);
            }
            return;
        }

        if (awake && now < awakeUntil) {
            if (assistant != null) assistant.handle(heard);
            awakeUntil = now + WAKE_WINDOW_MS;
            updateNotification("NOVA • listening for command");
        } else {
            awake = false;
        }
    }

    private void wake() {
        awake = true;
        awakeUntil = System.currentTimeMillis() + WAKE_WINDOW_MS;
        clapCount = 0;
        updateNotification("NOVA ONLINE • listening");
        playWakeTone();
        showWakeOverlay();
    }

    private String removeWakePhrase(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        int index = lower.indexOf("hey nova");
        int length = 8;
        if (index < 0) {
            index = lower.indexOf("nova");
            length = 4;
        }
        if (index < 0) return "";
        return text.substring(index + length).trim();
    }

    private boolean containsWake(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT).trim();
        return lower.contains("hey nova") || lower.equals("nova") || lower.startsWith("nova ");
    }

    private void detectDoubleClap(float rmsdB) {
        if (!running || awake || Float.isNaN(rmsdB) || Float.isInfinite(rmsdB)) return;

        clapBaselineDb = clapBaselineDb * (1f - CLAP_BASELINE_ALPHA) + rmsdB * CLAP_BASELINE_ALPHA;
        long now = System.currentTimeMillis();
        boolean spike = rmsdB >= CLAP_MIN_RMS_DB && rmsdB >= clapBaselineDb + CLAP_RISE_DB;
        if (!spike || now - lastClapTime < CLAP_DEBOUNCE_MS) return;

        if (clapCount == 0 || now - lastClapTime > CLAP_PAIR_WINDOW_MS) clapCount = 1;
        else clapCount++;
        lastClapTime = now;

        if (clapCount >= 2) {
            clapCount = 0;
            wake();
        }
    }

    private void scheduleRecognitionRestart() {
        handler.removeCallbacksAndMessages(null);
        if (running) handler.postDelayed(this::startRecognitionSession, RESTART_DELAY_MS);
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) { }
            try { recognizer.destroy(); } catch (Exception ignored) { }
            recognizer = null;
        }
        recognizerStarting = false;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "NOVA Voice", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("NOVA hands-free voice status");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification notification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setContentTitle("NOVA")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        if (handler == null) return;
        handler.post(() -> {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null && running) manager.notify(NOTIFICATION_ID, notification(text));
        });
    }

    private void playWakeTone() {
        try {
            if (toneGenerator != null) toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 90);
        } catch (Exception ignored) { }
    }

    private void showWakeOverlay() {
        if (windowManager == null || !Settings.canDrawOverlays(this)) return;
        handler.post(() -> {
            removeWakeOverlay();
            try {
                wakeOverlay = new NovaWakeOverlay(this);
                int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE;
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        android.graphics.PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                windowManager.addView(wakeOverlay, params);
                handler.postDelayed(this::removeWakeOverlay, 1200L);
            } catch (Exception e) {
                Log.w(TAG, "Wake overlay unavailable", e);
            }
        });
    }

    private void removeWakeOverlay() {
        if (wakeOverlay == null || windowManager == null) return;
        try { windowManager.removeView(wakeOverlay); } catch (Exception ignored) { }
        wakeOverlay = null;
    }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        destroyRecognizer();
        removeWakeOverlay();
        if (toneGenerator != null) {
            try { toneGenerator.release(); } catch (Exception ignored) { }
            toneGenerator = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
