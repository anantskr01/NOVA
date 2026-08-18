package com.aircontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Small Android Keystore-backed store for secrets such as an AI API key. */
public final class NovaSecureStore {
    private static final String PREFS = "nova_secure_store";
    private static final String KEY_ALIAS = "nova_api_key";
    private static final String VALUE = "encrypted_api_key";

    private final SharedPreferences prefs;

    public NovaSecureStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void putApiKey(String value) {
        try {
            if (value == null || value.isEmpty()) {
                prefs.edit().remove(VALUE).apply();
                return;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            prefs.edit()
                    .putString(VALUE, android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP))
                    .putString(VALUE + "_iv", android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP))
                    .apply();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to protect the API key", e);
        }
    }

    public String getApiKey() {
        try {
            String encrypted = prefs.getString(VALUE, "");
            String ivText = prefs.getString(VALUE + "_iv", "");
            if (encrypted.isEmpty() || ivText.isEmpty()) return "";
            byte[] data = android.util.Base64.decode(encrypted, android.util.Base64.NO_WRAP);
            byte[] iv = android.util.Base64.decode(ivText, android.util.Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
