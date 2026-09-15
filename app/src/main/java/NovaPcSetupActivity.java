package com.aircontrol;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Foreground pairing UI for the real NOVA PC companion. */
public final class NovaPcSetupActivity extends Activity {
    private static final String PREFS = "nova_pc_settings";
    private static final String ENDPOINT = "endpoint";
    private EditText endpointInput;
    private EditText tokenInput;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 40, 32, 32);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("NOVA PC COMPANION");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView help = new TextView(this);
        help.setText("Enter the PC companion address and the same NOVA_PC_TOKEN used by the companion. The token is stored in Android Keystore.");
        help.setPadding(0, 20, 0, 20);
        root.addView(help, new LinearLayout.LayoutParams(-1, -2));

        endpointInput = new EditText(this);
        endpointInput.setHint("http://PC-LAN-IP:18765");
        endpointInput.setSingleLine(true);
        endpointInput.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(ENDPOINT, ""));
        root.addView(endpointInput, new LinearLayout.LayoutParams(-1, -2));

        tokenInput = new EditText(this);
        tokenInput.setHint("NOVA_PC_TOKEN (32+ characters)");
        tokenInput.setSingleLine(true);
        tokenInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(tokenInput, new LinearLayout.LayoutParams(-1, -2));

        Button connect = new Button(this);
        connect.setText("PAIR AND TEST CONNECTION");
        connect.setOnClickListener(v -> pair());
        root.addView(connect, new LinearLayout.LayoutParams(-1, -2));

        Button clear = new Button(this);
        clear.setText("CLEAR PC CREDENTIALS");
        clear.setOnClickListener(v -> {
            new NovaPcCredentials(this).clear();
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply();
            tokenInput.setText("");
            status.setText("PC pairing cleared.");
        });
        root.addView(clear, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setPadding(0, 20, 0, 20);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        Button openNova = new Button(this);
        openNova.setText("OPEN NOVA");
        openNova.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
        });
        root.addView(openNova, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    private void pair() {
        String endpoint = endpointInput.getText().toString().trim();
        String token = tokenInput.getText().toString().trim();
        if (!(endpoint.startsWith("http://") || endpoint.startsWith("https://"))) {
            status.setText("Invalid endpoint. Use http:// or https://.");
            return;
        }
        if (token.length() < 32) {
            status.setText("Token must contain at least 32 characters.");
            return;
        }
        status.setText("Testing PC companion…");
        new Thread(() -> {
            boolean connected = new NovaPcHttpClient(endpoint, token).isConnected();
            runOnUiThread(() -> {
                if (!connected) {
                    status.setText("PC companion not reachable/authenticated. Check PC IP, port, firewall, and token.");
                    return;
                }
                try {
                    new NovaPcCredentials(this).setSecret(token);
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(ENDPOINT, endpoint).apply();
                    status.setText("PC COMPANION CONNECTED • protocol 1 verified");
                } catch (Exception e) {
                    status.setText("PC is reachable, but NOVA could not store the credentials safely.");
                }
            });
        }, "nova-pc-pair-test").start();
    }
}
