package com.aircontrol;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Local setup screen for pairing the Android NOVA client with the PC companion. */
public final class NovaPcSetupActivity extends Activity {
    private static final String PREFS = "nova_pc_settings";
    private final ExecutorService tester = Executors.newSingleThreadExecutor();
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private EditText endpoint;
    private EditText token;
    private Button save;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        TextView title = new TextView(this); title.setText("NOVA PC AGENT"); title.setTextSize(24); root.addView(title);
        TextView hint = new TextView(this); hint.setText("Enter the PC companion address and its secret token. The token is stored in Android Keystore-backed storage."); root.addView(hint);
        endpoint = new EditText(this); endpoint.setHint("http://192.168.x.x:18765/"); endpoint.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString("endpoint", "")); root.addView(endpoint);
        token = new EditText(this); token.setHint("PC token (32+ characters)"); token.setInputType(0x00000081); root.addView(token);
        save = new Button(this); save.setText("SAVE & TEST"); root.addView(save);
        save.setOnClickListener(v -> saveAndTest());
        setContentView(root);
    }

    private void saveAndTest() {
        String url = endpoint.getText().toString().trim();
        String secret = token.getText().toString().trim();
        if (url.isEmpty() || secret.length() < 32) {
            Toast.makeText(this, "Enter a valid PC URL and a 32+ character token.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!url.endsWith("/")) url += "/";
        final String finalUrl = url;
        final String finalSecret = secret;
        save.setEnabled(false);
        Toast.makeText(this, "Testing PC companion and token…", Toast.LENGTH_SHORT).show();
        NovaPcHttpClient client;
        try {
            client = new NovaPcHttpClient(finalUrl, finalSecret, 5_000);
        } catch (Exception e) {
            save.setEnabled(true);
            Toast.makeText(this, "Invalid PC configuration: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        NovaPcHttpClient finalClient = client;
        tester.execute(() -> {
            try {
                finalClient.healthCheck();
                finalClient.authenticatedHealthCheck();
                if (destroyed.get()) return;
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("endpoint", finalUrl).apply();
                new NovaSecureStore(this).putPcToken(finalSecret);
                NovaPcRuntime.configure(finalClient);
                runOnUiThread(() -> {
                    if (destroyed.get()) return;
                    save.setEnabled(true);
                    Toast.makeText(this, "PC companion connected and authenticated. NOVA PC tools are ready.", Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (destroyed.get()) return;
                    save.setEnabled(true);
                    Toast.makeText(this, "PC companion not reachable: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    @Override protected void onDestroy() {
        destroyed.set(true);
        tester.shutdownNow();
        super.onDestroy();
    }
}
