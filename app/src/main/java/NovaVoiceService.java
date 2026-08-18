package com.aircontrol;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;

public final class NovaVoiceService extends Service {

    private static final String TAG = "NovaVoice";

    private static final String CHANNEL = "nova_voice";
    private static final int NOTIFICATION_ID = 2002;

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_COUNT = 1;
    private static final int BYTES_PER_SAMPLE = 2;

    private static final long ACTIVE_WINDOW_MS = 15000L;

    private static final long START_GUARD_MS = 500L;
    private static final long RESTART_MS = 800L;

    private static final long CLAP_PAIR_WINDOW_MS = 1100L;
    private static final long CLAP_DEBOUNCE_MS = 180L;
    private static final float CLAP_MIN_RMS_DB = 5.0f;
    private static final float CLAP_RISE_DB = 10.0f;
    private static final float CLAP_BASELINE_ALPHA = 0.08f;

    public static final String ACTION_STOP =
            "com.aircontrol.STOP_NOVA_VOICE";

    private Handler handler;
    private NovaAssistant assistant;

    private SpeechRecognizer recognizer;

    private AudioRecord audioRecord;
    private Thread audioThread;

    private ParcelFileDescriptor audioReadPipe;
    private ParcelFileDescriptor audioWritePipe;

    private OutputStream audioOutput;

    private volatile boolean running = false;
    private volatile boolean audioRunning = false;
    private volatile boolean recognizerRunning = false;

    private boolean awake = false;
    private long awakeUntil = 0L;

    private String lastPhrase = "";
    private long lastPhraseTime = 0L;

    private int clapCount = 0;
    private long lastClapTime = 0L;
    private float clapBaselineDb = -40f;

    private WindowManager windowManager;
    private NovaWakeOverlay wakeOverlay;
    private ToneGenerator toneGenerator;

    // ------------------------------------------------------------
    // SERVICE CREATE
    // ------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();

        handler = new Handler(getMainLooper());

        assistant = new NovaAssistant(
                this,
                this::updateNotification
        );

        createChannel();

        windowManager =
                (WindowManager) getSystemService(WINDOW_SERVICE);

        try {
            toneGenerator = new ToneGenerator(
                    AudioManager.STREAM_NOTIFICATION,
                    70
            );
        } catch (Exception e) {
            Log.w(TAG, "ToneGenerator unavailable", e);
        }

        Log.d(TAG, "NOVA VOICE SERVICE CREATED");
    }

    // ------------------------------------------------------------
    // START COMMAND
    // ------------------------------------------------------------

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {

        if (intent != null &&
                ACTION_STOP.equals(intent.getAction())) {

            Log.d(TAG, "STOP COMMAND RECEIVED");

            stopSelf();

            return START_NOT_STICKY;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(
                        Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED) {

            Log.e(
                    TAG,
                    "RECORD_AUDIO PERMISSION NOT GRANTED"
            );

            stopSelf();

            return START_NOT_STICKY;
        }

        // --------------------------------------------------------
        // FOREGROUND MICROPHONE SERVICE
        // --------------------------------------------------------

        try {

            Notification n =
                    notification(
                            "NOVA Hands-Free microphone"
                    );

            if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.Q) {

                startForeground(
                        NOTIFICATION_ID,
                        n,
                        ServiceInfo
                                .FOREGROUND_SERVICE_TYPE_MICROPHONE
                );

            } else {

                startForeground(
                        NOTIFICATION_ID,
                        n
                );
            }

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "FAILED TO START MICROPHONE FOREGROUND SERVICE",
                    e
            );

            stopSelf();

            return START_NOT_STICKY;
        }

        if (!running) {

            running = true;

            updateNotification(
                    "NOVA • starting continuous microphone"
            );

            handler.postDelayed(
                    this::startContinuousVoice,
                    START_GUARD_MS
            );
        }

        return START_STICKY;
    }

    // ------------------------------------------------------------
    // CONTINUOUS VOICE
    // ------------------------------------------------------------

    private void startContinuousVoice() {

        if (!running) {
            return;
        }

        /*
         * Android 13+:
         *
         * Use AudioRecord as the permanently-open microphone.
         * SpeechRecognizer consumes that stream using
         * EXTRA_AUDIO_SOURCE + segmented session mode.
         */
        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.TIRAMISU) {

            startContinuousAudioRecognition();

        } else {

            /*
             * Older Android versions don't have the segmented audio
             * source API. Fall back to the normal recognizer.
             */
            startLegacyRecognition();
        }
    }

    // ------------------------------------------------------------
    // CONTINUOUS AUDIO + SEGMENTED SPEECH RECOGNIZER
    // ------------------------------------------------------------

    private void startContinuousAudioRecognition() {

        Log.d(
                TAG,
                "STARTING CONTINUOUS AUDIO RECOGNITION"
        );

        stopAudioCapture();
        destroyRecognizer();

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {

            Log.e(
                    TAG,
                    "Speech recognition unavailable"
            );

            updateNotification(
                    "NOVA • speech recognition unavailable"
            );

            scheduleRestart();

            return;
        }

        int minBuffer =
                AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                );

        if (minBuffer <= 0) {

            Log.e(
                    TAG,
                    "Invalid AudioRecord buffer size: "
                            + minBuffer
            );

            scheduleRestart();

            return;
        }

        int bufferSize =
                Math.max(
                        minBuffer * 2,
                        4096
                );

        try {

            audioRecord =
                    new AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize
                    );

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "AudioRecord creation failed",
                    e
            );

            audioRecord = null;

            scheduleRestart();

            return;
        }

        if (audioRecord.getState() !=
                AudioRecord.STATE_INITIALIZED) {

            Log.e(
                    TAG,
                    "AudioRecord not initialized"
            );

            stopAudioCapture();

            scheduleRestart();

            return;
        }

        try {

            ParcelFileDescriptor[] pipe =
                    ParcelFileDescriptor.createPipe();

            audioReadPipe = pipe[0];
            audioWritePipe = pipe[1];

            audioOutput =
                    new ParcelFileDescriptor
                            .AutoCloseOutputStream(
                                    audioWritePipe
                            );

        } catch (IOException e) {

            Log.e(
                    TAG,
                    "Could not create audio pipe",
                    e
            );

            stopAudioCapture();

            scheduleRestart();

            return;
        }

        try {

            recognizer =
                    SpeechRecognizer
                            .createSpeechRecognizer(this);

            recognizer.setRecognitionListener(
                    createSegmentedRecognitionListener()
            );

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "SpeechRecognizer creation failed",
                    e
            );

            destroyRecognizer();
            stopAudioCapture();

            scheduleRestart();

            return;
        }

        Intent input =
                new Intent(
                        RecognizerIntent
                                .ACTION_RECOGNIZE_SPEECH
                );

        input.putExtra(
                RecognizerIntent
                        .EXTRA_LANGUAGE_MODEL,
                RecognizerIntent
                        .LANGUAGE_MODEL_FREE_FORM
        );

        input.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                Locale.getDefault()
        );

        input.putExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                true
        );

        input.putExtra(
                RecognizerIntent.EXTRA_MAX_RESULTS,
                3
        );

        /*
         * Tell Android the speech recognizer is receiving audio
         * from an already-open source.
         */
        input.putExtra(
                RecognizerIntent.EXTRA_AUDIO_SOURCE,
                audioReadPipe
        );

        input.putExtra(
                RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,
                CHANNEL_COUNT
        );

        input.putExtra(
                RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING,
                AudioFormat.ENCODING_PCM_16BIT
        );

        input.putExtra(
                RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE,
                SAMPLE_RATE
        );

        /*
         * This is the critical part.
         *
         * The recognizer remains in one segmented session while
         * the audio source stays open.
         */
        input.putExtra(
                RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                RecognizerIntent.EXTRA_AUDIO_SOURCE
        );

        try {

            recognizer.startListening(input);

            recognizerRunning = true;

            updateNotification(
                    "NOVA • Hands-Free listening"
            );

            Log.d(
                    TAG,
                    "SEGMENTED SPEECH RECOGNIZER STARTED"
            );

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "Segmented recognizer start failed",
                    e
            );

            recognizerRunning = false;

            destroyRecognizer();
            stopAudioCapture();

            scheduleRestart();

            return;
        }

        startAudioCapture(
                bufferSize
        );
    }

    // ------------------------------------------------------------
    // AUDIO CAPTURE
    // ------------------------------------------------------------

    private void startAudioCapture(
            int bufferSize
    ) {

        if (audioRecord == null ||
                audioOutput == null ||
                !running) {

            return;
        }

        if (audioRunning) {
            return;
        }

        audioRunning = true;

        audioThread =
                new Thread(
                        () -> {

                            byte[] buffer =
                                    new byte[
                                            bufferSize
                                    ];

                            try {

                                audioRecord.startRecording();

                                Log.d(
                                        TAG,
                                        "AUDIORECORD STARTED"
                                );

                                while (
                                        running &&
                                        audioRunning &&
                                        audioRecord != null &&
                                        audioRecord.getRecordingState()
                                                == AudioRecord
                                                .RECORDSTATE_RECORDING
                                ) {

                                    int read =
                                            audioRecord.read(
                                                    buffer,
                                                    0,
                                                    buffer.length
                                            );

                                    if (read <= 0) {

                                        Log.w(
                                                TAG,
                                                "AudioRecord read = "
                                                        + read
                                        );

                                        continue;
                                    }

                                    /*
                                     * Feed PCM directly into the
                                     * SpeechRecognizer pipe.
                                     */
                                    audioOutput.write(
                                            buffer,
                                            0,
                                            read
                                    );

                                    detectClapFromPcm(
                                            buffer,
                                            read
                                    );
                                }

                            } catch (Exception e) {

                                if (running) {

                                    Log.e(
                                            TAG,
                                            "CONTINUOUS AUDIO LOOP FAILED",
                                            e
                                    );
                                }

                            } finally {

                                audioRunning = false;

                                closeAudioOutput();
                            }

                        },
                        "NOVA-AudioCapture"
                );

        audioThread.start();
    }

    // ------------------------------------------------------------
    // CLAP DETECTION FROM RAW AUDIO
    // ------------------------------------------------------------

    private void detectClapFromPcm(
            byte[] data,
            int length
    ) {

        if (!running ||
                awake ||
                length < 2) {

            return;
        }

        long sumSquares = 0;

        int sampleCount =
                length / 2;

        for (
                int i = 0;
                i < length - 1;
                i += 2
        ) {

            int low =
                    data[i] & 0xff;

            int high =
                    data[i + 1];

            short sample =
                    (short)
                            ((high << 8) | low);

            sumSquares +=
                    (long) sample * sample;
        }

        if (sampleCount <= 0) {
            return;
        }

        double rms =
                Math.sqrt(
                        sumSquares /
                                (double) sampleCount
                );

        if (rms <= 0.0) {
            return;
        }

        float rmsDb =
                (float)
                        (20.0 *
                                Math.log10(
                                        rms / 32768.0
                                ) +
                                90.0);

        if (Float.isNaN(rmsDb) ||
                Float.isInfinite(rmsDb)) {

            return;
        }

        clapBaselineDb =
                clapBaselineDb *
                        (1f - CLAP_BASELINE_ALPHA)
                        +
                        rmsDb *
                                CLAP_BASELINE_ALPHA;

        long now =
                System.currentTimeMillis();

        boolean spike =
                rmsDb >= CLAP_MIN_RMS_DB
                        &&
                rmsDb >=
                        clapBaselineDb +
                                CLAP_RISE_DB;

        if (!spike ||
                now - lastClapTime <
                        CLAP_DEBOUNCE_MS) {

            return;
        }

        if (clapCount == 0 ||
                now - lastClapTime >
                        CLAP_PAIR_WINDOW_MS) {

            clapCount = 1;

        } else {

            clapCount++;
        }

        lastClapTime = now;

        if (clapCount >= 2) {

            clapCount = 0;

            awake = true;

            awakeUntil =
                    now + ACTIVE_WINDOW_MS;

            triggerWakeExperience();
        }
    }

    // ------------------------------------------------------------
    // SEGMENTED LISTENER
    // ------------------------------------------------------------

    private RecognitionListener
    createSegmentedRecognitionListener() {

        return new RecognitionListener() {

            @Override
            public void onReadyForSpeech(
                    Bundle params
            ) {

                updateNotification(
                        "NOVA • Hands-Free listening"
                );

                Log.d(
                        TAG,
                        "SEGMENTED RECOGNIZER READY"
                );
            }

            @Override
            public void onBeginningOfSpeech() {

                updateNotification(
                        awake
                                ? "NOVA • hearing command"
                                : "NOVA • hearing"
                );
            }

            @Override
            public void onRmsChanged(
                    float rmsdB
            ) {
                // Raw AudioRecord handles clap detection.
            }

            @Override
            public void onBufferReceived(
                    byte[] buffer
            ) {
                // Audio is supplied through AudioRecord.
            }

            @Override
            public void onEndOfSpeech() {

                /*
                 * IMPORTANT:
                 * Do not restart or destroy anything here.
                 *
                 * In segmented-session mode, this can represent
                 * the end of an individual speech segment.
                 */
                Log.d(
                        TAG,
                        "SEGMENT END"
                );
            }

            @Override
            public void onError(
                    int error
            ) {

                Log.e(
                        TAG,
                        "SEGMENTED SPEECH ERROR = "
                                + error
                );

                /*
                 * The most important part:
                 *
                 * Do NOT immediately tear down the microphone.
                 *
                 * Keep AudioRecord alive and only recreate the
                 * recognizer if the recognition service itself
                 * has actually failed.
                 */
                if (!running) {
                    return;
                }

                updateNotification(
                        "NOVA • speech reconnecting"
                );

                if (error ==
                        SpeechRecognizer.ERROR_NO_MATCH) {

                    /*
                     * No spoken result is not a reason to
                     * shut down the microphone.
                     */
                    return;
                }

                /*
                 * For a real recognizer failure we restart the
                 * recognizer using the still-available microphone
                 * stream.
                 */
                handler.postDelayed(
                        () -> {

                            if (running) {

                                restartRecognizerOnly();
                            }

                        },
                        RESTART_MS
                );
            }

            @Override
            public void onResults(
                    Bundle results
            ) {

                processResults(
                        results
                );
            }

            /*
             * Android 13+ segmented-session callback.
             */
            @Override
            public void onSegmentResults(
                    Bundle results
            ) {

                processResults(
                        results
                );
            }

            /*
             * The continuous segmented session should normally
             * remain open while AudioRecord is open.
             */
            @Override
            public void onEndOfSegmentedSession() {

                Log.w(
                        TAG,
                        "END OF SEGMENTED SESSION"
                );

                if (running) {

                    restartRecognizerOnly();
                }
            }

            @Override
            public void onPartialResults(
                    Bundle partialResults
            ) {

                ArrayList<String> values =
                        partialResults
                                .getStringArrayList(
                                        SpeechRecognizer
                                                .RESULTS_RECOGNITION
                                );

                if (values == null ||
                        values.isEmpty()) {

                    return;
                }

                String phrase =
                        values.get(0);

                if (containsWake(phrase) &&
                        !awake) {

                    long now =
                            System.currentTimeMillis();

                    awake = true;

                    awakeUntil =
                            now + ACTIVE_WINDOW_MS;

                    triggerWakeExperience();
                }
            }

            @Override
            public void onEvent(
                    int eventType,
                    Bundle params
            ) {
                // No-op.
            }
        };
    }

    // ------------------------------------------------------------
    // PROCESS RESULTS
    // ------------------------------------------------------------

    private void processResults(
            Bundle results
    ) {

        if (results == null) {
            return;
        }

        ArrayList<String> values =
                results.getStringArrayList(
                        SpeechRecognizer
                                .RESULTS_RECOGNITION
                );

        if (values == null ||
                values.isEmpty()) {

            return;
        }

        String heard =
                values.get(0);

        if (heard == null ||
                heard.trim().isEmpty()) {

            return;
        }

        process(
                heard
        );
    }

    // ------------------------------------------------------------
    // RESTART RECOGNIZER ONLY
    // ------------------------------------------------------------

    private void restartRecognizerOnly() {

        if (!running) {
            return;
        }

        Log.d(
                TAG,
                "RESTARTING SPEECH RECOGNIZER ONLY"
        );

        destroyRecognizer();

        /*
         * Do not close AudioRecord here.
         * The microphone stream remains active.
         */
        handler.postDelayed(
                () -> {

                    if (!running) {
                        return;
                    }

                    startRecognizerFromExistingAudio();

                },
                250L
        );
    }

    private void startRecognizerFromExistingAudio() {

        if (!running ||
                audioReadPipe == null) {

            return;
        }

        try {

            recognizer =
                    SpeechRecognizer
                            .createSpeechRecognizer(this);

            recognizer.setRecognitionListener(
                    createSegmentedRecognitionListener()
            );

            Intent input =
                    new Intent(
                            RecognizerIntent
                                    .ACTION_RECOGNIZE_SPEECH
                    );

            input.putExtra(
                    RecognizerIntent
                            .EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent
                            .LANGUAGE_MODEL_FREE_FORM
            );

            input.putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    Locale.getDefault()
            );

            input.putExtra(
                    RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                    true
            );

            input.putExtra(
                    RecognizerIntent.EXTRA_MAX_RESULTS,
                    3
            );

            input.putExtra(
                    RecognizerIntent.EXTRA_AUDIO_SOURCE,
                    audioReadPipe
            );

            input.putExtra(
                    RecognizerIntent
                            .EXTRA_AUDIO_SOURCE_CHANNEL_COUNT,
                    CHANNEL_COUNT
            );

            input.putExtra(
                    RecognizerIntent
                            .EXTRA_AUDIO_SOURCE_ENCODING,
                    AudioFormat.ENCODING_PCM_16BIT
            );

            input.putExtra(
                    RecognizerIntent
                            .EXTRA_AUDIO_SOURCE_SAMPLING_RATE,
                    SAMPLE_RATE
            );

            input.putExtra(
                    RecognizerIntent
                            .EXTRA_SEGMENTED_SESSION,
                    RecognizerIntent.EXTRA_AUDIO_SOURCE
            );

            recognizer.startListening(
                    input
            );

            recognizerRunning = true;

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "FAILED TO RESTART RECOGNIZER",
                    e
            );

            recognizerRunning = false;

            destroyRecognizer();

            if (running) {
                scheduleRestart();
            }
        }
    }

    // ------------------------------------------------------------
    // LEGACY ANDROID FALLBACK
    // ------------------------------------------------------------

    private void startLegacyRecognition() {

        Log.d(
                TAG,
                "ANDROID < 33: USING LEGACY SPEECH RECOGNITION"
        );

        destroyRecognizer();

        try {

            recognizer =
                    SpeechRecognizer
                            .createSpeechRecognizer(this);

            recognizer.setRecognitionListener(
                    createLegacyListener()
            );

            startLegacySession();

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "LEGACY RECOGNIZER FAILED",
                    e
            );

            destroyRecognizer();

            scheduleRestart();
        }
    }

    private RecognitionListener
    createLegacyListener() {

        return new RecognitionListener() {

            @Override
            public void onReadyForSpeech(
                    Bundle params
            ) {

                updateNotification(
                        "NOVA • Hands-Free listening"
                );
            }

            @Override
            public void onBeginningOfSpeech() {

                updateNotification(
                        "NOVA • hearing"
                );
            }

            @Override
            public void onRmsChanged(
                    float rmsdB
            ) {

                detectDoubleClapLegacy(
                        rmsdB
                );
            }

            @Override
            public void onBufferReceived(
                    byte[] buffer
            ) {
            }

            @Override
            public void onEndOfSpeech() {
            }

            @Override
            public void onError(
                    int error
            ) {

                if (!running) {
                    return;
                }

                handler.postDelayed(
                        () -> {

                            if (running) {
                                startLegacySession();
                            }

                        },
                        600L
                );
            }

            @Override
            public void onResults(
                    Bundle results
            ) {

                processResults(
                        results
                );

                if (running) {
                    startLegacySession();
                }
            }

            @Override
            public void onPartialResults(
                    Bundle partialResults
            ) {

                ArrayList<String> values =
                        partialResults
                                .getStringArrayList(
                                        SpeechRecognizer
                                                .RESULTS_RECOGNITION
                                );

                if (values != null &&
                        !values.isEmpty()) {

                    String phrase =
                            values.get(0);

                    if (containsWake(phrase) &&
                            !awake) {

                        long now =
                                System.currentTimeMillis();

                        awake = true;

                        awakeUntil =
                                now + ACTIVE_WINDOW_MS;

                        triggerWakeExperience();
                    }
                }
            }

            @Override
            public void onEvent(
                    int eventType,
                    Bundle params
            ) {
            }
        };
    }

    private void startLegacySession() {

        if (!running ||
                recognizer == null) {

            return;
        }

        try {

            Intent input =
                    new Intent(
                            RecognizerIntent
                                    .ACTION_RECOGNIZE_SPEECH
                    );

            input.putExtra(
                    RecognizerIntent
                            .EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent
                            .LANGUAGE_MODEL_FREE_FORM
            );

            input.putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    Locale.getDefault()
            );

            input.putExtra(
                    RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                    true
            );

            input.putExtra(
                    RecognizerIntent.EXTRA_MAX_RESULTS,
                    3
            );

            recognizer.startListening(
                    input
            );

        } catch (Exception e) {

            Log.e(
                    TAG,
                    "LEGACY START FAILED",
                    e
            );

            if (running) {
                scheduleRestart();
            }
        }
    }

    private void detectDoubleClapLegacy(
            float rmsdB
    ) {

        if (!running ||
                awake ||
                Float.isNaN(rmsdB) ||
                Float.isInfinite(rmsdB)) {

            return;
        }

        clapBaselineDb =
                clapBaselineDb *
                        (1f - CLAP_BASELINE_ALPHA)
                        +
                        rmsdB *
                                CLAP_BASELINE_ALPHA;

        long now =
                System.currentTimeMillis();

        boolean spike =
                rmsdB >= CLAP_MIN_RMS_DB
                        &&
                rmsdB >=
                        clapBaselineDb +
                                CLAP_RISE_DB;

        if (!spike ||
                now - lastClapTime <
                        CLAP_DEBOUNCE_MS) {

            return;
        }

        if (clapCount == 0 ||
                now - lastClapTime >
                        CLAP_PAIR_WINDOW_MS) {

            clapCount = 1;

        } else {

            clapCount++;
        }

        lastClapTime = now;

        if (clapCount >= 2) {

            clapCount = 0;

            awake = true;

            awakeUntil =
                    now + ACTIVE_WINDOW_MS;

            triggerWakeExperience();
        }
    }

    // ------------------------------------------------------------
    // PROCESS VOICE COMMAND
    // ------------------------------------------------------------

    private void process(
            String heard
    ) {

        if (heard == null ||
                heard.trim().isEmpty()) {

            return;
        }

        String text =
                heard.trim();

        long now =
                System.currentTimeMillis();

        if (text.equalsIgnoreCase(
                lastPhrase
        ) &&
                now - lastPhraseTime < 1400L) {

            return;
        }

        lastPhrase = text;
        lastPhraseTime = now;

        if (containsWake(text)) {

            awake = true;

            awakeUntil =
                    now + ACTIVE_WINDOW_MS;

            triggerWakeExperience();

            if (assistant != null) {

                assistant.handleVoice(
                        text
                );
            }

            return;
        }

        if (awake &&
                now < awakeUntil) {

            if (assistant != null) {

                assistant.handle(
                        text
                );
            }

            awakeUntil =
                    now + ACTIVE_WINDOW_MS;

        } else {

            awake = false;
        }

        if (!awake) {
            clapCount = 0;
        }
    }

    // ------------------------------------------------------------
    // WAKE PHRASE
    // ------------------------------------------------------------

    private boolean containsWake(
            String text
    ) {

        if (text == null) {
            return false;
        }

        String lower =
                text.toLowerCase(
                        Locale.ROOT
                ).trim();

        return lower.contains("hey nova")
                ||
                lower.equals("nova")
                ||
                lower.startsWith("nova ");
    }

    // ------------------------------------------------------------
    // WAKE EXPERIENCE
    // ------------------------------------------------------------

    private void triggerWakeExperience() {

        updateNotification(
                "NOVA ONLINE • listening"
        );

        playWakeTone();

        showWakeOverlay();
    }

    private void playWakeTone() {

        try {

            if (toneGenerator != null) {

                toneGenerator.startTone(
                        ToneGenerator
                                .TONE_PROP_BEEP2,
                        90
                );
            }

        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------
    // OVERLAY
    // ------------------------------------------------------------

    private void showWakeOverlay() {

        if (windowManager == null ||
                !android.provider.Settings
                        .canDrawOverlays(this)) {

            return;
        }

        handler.post(() -> {

            removeWakeOverlay();

            wakeOverlay =
                    new NovaWakeOverlay(this);

            int type =
                    Build.VERSION.SDK_INT >=
                            Build.VERSION_CODES.O
                            ?
                            WindowManager
                                    .LayoutParams
                                    .TYPE_APPLICATION_OVERLAY
                            :
                            WindowManager
                                    .LayoutParams
                                    .TYPE_PHONE;

            WindowManager.LayoutParams params =
                    new WindowManager.LayoutParams(
                            WindowManager
                                    .LayoutParams
                                    .MATCH_PARENT,

                            WindowManager
                                    .LayoutParams
                                    .MATCH_PARENT,

                            type,

                            WindowManager
                                    .LayoutParams
                                    .FLAG_NOT_FOCUSABLE
                                    |
                            WindowManager
                                    .LayoutParams
                                    .FLAG_NOT_TOUCHABLE
                                    |
                            WindowManager
                                    .LayoutParams
                                    .FLAG_LAYOUT_NO_LIMITS,

                            PixelFormat.TRANSLUCENT
                    );

            params.gravity =
                    Gravity.TOP |
                    Gravity.START;

            try {

                windowManager.addView(
                        wakeOverlay,
                        params
                );

                wakeOverlay.showFor(
                        1450L,
                        this::removeWakeOverlay
                );

            } catch (Exception e) {

                Log.d(
                        TAG,
                        "Wake overlay unavailable: "
                                + e.getMessage()
                );

                wakeOverlay = null;
            }
        });
    }

    private void removeWakeOverlay() {

        if (windowManager == null ||
                wakeOverlay == null) {

            return;
        }

        try {

            windowManager.removeView(
                    wakeOverlay
            );

        } catch (Exception ignored) {
        }

        wakeOverlay = null;
    }

    // ------------------------------------------------------------
    // RESTART
    // ------------------------------------------------------------

    private void scheduleRestart() {

        if (!running) {
            return;
        }

        handler.postDelayed(
                () -> {

                    if (running) {
                        startContinuousVoice();
                    }

                },
                RESTART_MS
        );
    }

    // ------------------------------------------------------------
    // STOP AUDIO
    // ------------------------------------------------------------

    private void stopAudioCapture() {

        audioRunning = false;

        if (audioRecord != null) {

            try {

                if (audioRecord.getRecordingState() ==
                        AudioRecord.RECORDSTATE_RECORDING) {

                    audioRecord.stop();
                }

            } catch (Exception ignored) {
            }
        }

        closeAudioOutput();

        if (audioRecord != null) {

            try {

                audioRecord.release();

            } catch (Exception ignored) {
            }

            audioRecord = null;
        }

        if (audioThread != null) {

            try {

                audioThread.interrupt();

            } catch (Exception ignored) {
            }

            audioThread = null;
        }
    }

    private void closeAudioOutput() {

        if (audioOutput != null) {

            try {

                audioOutput.close();

            } catch (Exception ignored) {
            }

            audioOutput = null;
        }

        if (audioReadPipe != null) {

            try {

                audioReadPipe.close();

            } catch (Exception ignored) {
            }

            audioReadPipe = null;
        }

        audioWritePipe = null;
    }

    // ------------------------------------------------------------
    // DESTROY RECOGNIZER
    // ------------------------------------------------------------

    private void destroyRecognizer() {

        recognizerRunning = false;

        if (recognizer != null) {

            try {
                recognizer.cancel();
            } catch (Exception ignored) {
            }

            try {
                recognizer.destroy();
            } catch (Exception ignored) {
            }

            recognizer = null;
        }
    }

    // ------------------------------------------------------------
    // NOTIFICATION CHANNEL
    // ------------------------------------------------------------

    private void createChannel() {

        if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL,
                            "NOVA Voice",
                            NotificationManager
                                    .IMPORTANCE_LOW
                    );

            NotificationManager manager =
                    (NotificationManager)
                            getSystemService(
                                    NOTIFICATION_SERVICE
                            );

            if (manager != null) {

                manager.createNotificationChannel(
                        channel
                );
            }
        }
    }

    // ------------------------------------------------------------
    // NOTIFICATION
    // ------------------------------------------------------------

    private Notification notification(
            String text
    ) {

        Notification.Builder builder =
                Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.O
                        ?
                        new Notification.Builder(
                                this,
                                CHANNEL
                        )
                        :
                        new Notification.Builder(
                                this
                        );

        return builder
                .setContentTitle(
                        "NOVA Voice"
                )
                .setContentText(
                        text
                )
                .setSmallIcon(
                        android.R.drawable
                                .ic_btn_speak_now
                )
                .setOngoing(true)
                .setCategory(
                        Notification.CATEGORY_SERVICE
                )
                .build();
    }

    private void updateNotification(
            String text
    ) {

        NotificationManager manager =
                (NotificationManager)
                        getSystemService(
                                NOTIFICATION_SERVICE
                        );

        if (manager != null &&
                running) {

            manager.notify(
                    NOTIFICATION_ID,
                    notification(text)
            );
        }
    }

    // ------------------------------------------------------------
    // SERVICE DESTROY
    // ------------------------------------------------------------

    @Override
    public void onDestroy() {

        Log.d(
                TAG,
                "NOVA VOICE SERVICE DESTROYING"
        );

        running = false;

        recognizerRunning = false;

        destroyRecognizer();

        stopAudioCapture();

        removeWakeOverlay();

        if (toneGenerator != null) {

            try {
                toneGenerator.release();
            } catch (Exception ignored) {
            }

            toneGenerator = null;
        }

        if (assistant != null) {

            assistant.destroy();

            assistant = null;
        }

        if (handler != null) {

            handler.removeCallbacksAndMessages(
                    null
            );
        }

        super.onDestroy();
    }

    // ------------------------------------------------------------
    // BIND
    // ------------------------------------------------------------

    @Override
    public IBinder onBind(
            Intent intent
    ) {
        return null;
    }
}