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

/** Android Keystore-backed credentials for the authenticated PC companion. */
public final class NovaPcCredentials {
    private static final String PREFS = "nova_pc_credentials";
    private static final String KEY_ALIAS = "nova_pc_secret";
    private static final String VALUE = "encrypted_secret";
    private static final String IV = "secret_iv";
    private final SharedPreferences prefs;

    public NovaPcCredentials(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void setSecret(String secret) {
        if (secret == null || secret.length() < 32) throw new IllegalArgumentException("PC secret must be at least 32 characters");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            prefs.edit()
                    .putString(VALUE, android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP))
                    .putString(IV, android.util.Base64.encodeToString(cipher.getIV(), android.util.Base64.NO_WRAP))
                    .apply();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to protect PC credentials", e);
        }
    }

    public String getSecret() {
        try {
            String encrypted = prefs.getString(VALUE, "");
            String ivText = prefs.getString(IV, "");
            if (encrypted.isEmpty() || ivText.isEmpty()) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, android.util.Base64.decode(ivText, android.util.Base64.NO_WRAP)));
            return new String(cipher.doFinal(android.util.Base64.decode(encrypted, android.util.Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public void clear() {
        prefs.edit().clear().apply();
    }

    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
