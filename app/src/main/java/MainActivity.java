package com.aircontrol;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.content.SharedPreferences;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

public final class MainActivity extends Activity implements NovaAssistant.Listener {
    private static final int CAMERA_PERMISSION_CODE = 100;
    private static final int AUDIO_PERMISSION_CODE = 101;
    private static final int NOTIFICATION_PERMISSION_CODE = 102;
    private static final String PREFS = "nova_voice_prefs";
    private static final String PREF_ALWAYS_LISTENING = "always_listening";

    private TextView statusText;
    private TextView handStatusText;
    private TextView assistantStatusText;
    private EditText commandInput;
    private Button micButton;
    private Button handsFreeButton;
    private NovaHudView hudView;

    private EditText aiEndpointInput;
    private EditText aiModelInput;
    private EditText aiKeyInput;

    private NovaAssistant nova;
    private SpeechRecognizer speechRecognizer;
    private boolean listening = false;
    private boolean handsFree = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        handStatusText = findViewById(R.id.handStatusText);
        assistantStatusText = findViewById(R.id.assistantStatusText);
        commandInput = findViewById(R.id.commandInput);
        micButton = findViewById(R.id.micButton);
        handsFreeButton = findViewById(R.id.handsFreeButton);
        hudView = findViewById(R.id.novaHud);
        aiEndpointInput = findViewById(R.id.aiEndpointInput);
        aiModelInput = findViewById(R.id.aiModelInput);
        aiKeyInput = findViewById(R.id.aiKeyInput);

        nova = new NovaAssistant(this, this);
        handsFree = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(PREF_ALWAYS_LISTENING, false);
        aiEndpointInput.setText(nova.getEndpoint());
        aiModelInput.setText(nova.getModel());

        findViewById(R.id.executeButton).setOnClickListener(v -> executeTypedCommand());
        micButton.setOnClickListener(v -> toggleListening());
        findViewById(R.id.readScreenButton).setOnClickListener(v -> nova.handle("read screen"));
        handsFreeButton.setOnClickListener(v -> toggleHandsFree());
        findViewById(R.id.saveAiButton).setOnClickListener(v -> {
            nova.saveAiSettings(
                    aiEndpointInput.getText().toString(),
                    aiKeyInput.getText().toString(),
                    aiModelInput.getText().toString());
            Toast.makeText(this, "NOVA AI settings saved securely", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.accessibilityButton).setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.notificationButton).setOnClickListener(v -> startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")));

        setupSpeechRecognizer();
        if (handsFree) {
            handsFreeButton.setText("STOP HANDS-FREE");
            assistantStatusText.setText("VOICE • ALWAYS LISTENING");
        }
        requestNotificationPermission();
        checkCameraPermission();
    }

    private void executeTypedCommand() {
        String command = commandInput.getText().toString().trim();
        if (command.isEmpty()) return;
        nova.handle(command);
        commandInput.setText("");
    }

    private void checkCameraPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            statusText.setText("NOVA • CAMERA PERMISSION REQUIRED");
            handStatusText.setText("VISION • WAITING FOR PERMISSION");
            hudView.setState("WAITING");
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_CODE);
            return;
        }
        requestAudioThenStart();
    }

    private void requestAudioThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_CODE);
            return;
        }
        startCameraGestureService();
    }

    private void startCameraGestureService() {
        Intent intent = new Intent(this, CameraGestureService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
            else startService(intent);
            statusText.setText("NOVA • CORE ONLINE");
            handStatusText.setText("VISION • FINGER CONTROL ACTIVE");
            hudView.setState("ONLINE");
        } catch (Exception e) {
            statusText.setText("NOVA • SERVICE ERROR");
            handStatusText.setText("VISION • UNAVAILABLE");
            hudView.setState("ERROR");
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_CODE);
        }
    }

    private void toggleHandsFree() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_CODE);
            return;
        }

        if (handsFree) {
            stopService(new Intent(this, NovaReliableVoiceService.class));
            handsFree = false;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(PREF_ALWAYS_LISTENING, false).apply();
            handsFreeButton.setText("START HANDS-FREE • HEY NOVA");
            assistantStatusText.setText("VOICE • STANDBY");
            hudView.setState("ONLINE");
        } else {
            Intent start = new Intent(this, NovaReliableVoiceService.class);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(start);
                else startService(start);
                handsFree = true;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(PREF_ALWAYS_LISTENING, true).apply();
                handsFreeButton.setText("STOP HANDS-FREE");
                assistantStatusText.setText("VOICE • ALWAYS LISTENING");
                hudView.setState("LISTENING");
                Toast.makeText(this, "NOVA will keep listening in the background", Toast.LENGTH_SHORT).show();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                    try {
                        Intent overlay = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(overlay);
                    } catch (Exception ignored) { }
                }
            } catch (Exception e) {
                handsFree = false;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(PREF_ALWAYS_LISTENING, false).apply();
                assistantStatusText.setText("VOICE • START BLOCKED BY ANDROID");
                Toast.makeText(this, "Android blocked background microphone", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            assistantStatusText.setText("VOICE • UNAVAILABLE ON THIS DEVICE");
            micButton.setEnabled(false);
            return;
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                listening = true;
                micButton.setText("LISTENING…");
                assistantStatusText.setText("VOICE • LISTENING");
                hudView.setState("LISTENING");
            }
            @Override public void onBeginningOfSpeech() { hudView.setState("HEARING"); }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { listening = false; micButton.setText("MIC"); }
            @Override public void onError(int error) {
                listening = false;
                micButton.setText("MIC");
                assistantStatusText.setText("VOICE • READY");
                hudView.setState("ONLINE");
            }
            @Override public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    commandInput.setText(matches.get(0));
                    nova.handleVoice(matches.get(0));
                }
                assistantStatusText.setText("VOICE • READY");
                hudView.setState("ONLINE");
            }
            @Override public void onPartialResults(Bundle partialResults) { }
            @Override public void onEvent(int eventType, Bundle params) { }
        });
    }

    private void toggleListening() {
        if (speechRecognizer == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_CODE);
            return;
        }
        if (listening) {
            speechRecognizer.stopListening();
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        speechRecognizer.startListening(intent);
    }

    @Override public void onStatus(String text) {
        runOnUiThread(() -> {
            String value = text == null ? "READY" : text;
            assistantStatusText.setText("NOVA • " + value);
            hudView.setState(stateFrom(value));
        });
    }

    private String stateFrom(String value) {
        String v = value.toUpperCase(Locale.ROOT);
        if (v.contains("THINK") || v.contains("PLAN")) return "THINKING";
        if (v.contains("LISTEN") || v.contains("HEAR")) return "LISTENING";
        if (v.contains("ERROR") || v.contains("FAILED")) return "ERROR";
        if (v.contains("ACTION") || v.contains("COMPLETED")) return "ACTIVE";
        return "ONLINE";
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) requestAudioThenStart();
            else {
                statusText.setText("NOVA • CAMERA DENIED");
                handStatusText.setText("VISION • DISABLED");
                hudView.setState("LOCKED");
            }
        } else if (requestCode == NOTIFICATION_PERMISSION_CODE) {
            assistantStatusText.setText("ASSISTANT • NOTIFICATION PERMISSION UPDATED");
        } else if (requestCode == AUDIO_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                assistantStatusText.setText("VOICE • READY");
            } else {
                assistantStatusText.setText("VOICE • TEXT COMMANDS STILL AVAILABLE");
            }
            startCameraGestureService();
        }
    }

    @Override protected void onDestroy() {
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        if (nova != null) {
            nova.destroy();
            nova = null;
        }
        super.onDestroy();
    }
}