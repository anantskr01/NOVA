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

/** Android Keystore-backed store for NOVA secrets such as AI and PC companion credentials. */
public final class NovaSecureStore {
    private static final String PREFS = "nova_secure_store";
    private static final String KEY_ALIAS = "nova_api_key";
    private static final String PC_KEY_ALIAS = "nova_pc_secret";
    private static final String VALUE = "encrypted_api_key";
    private static final String PC_VALUE = "encrypted_pc_secret";

    private final SharedPreferences prefs;

    public NovaSecureStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void putApiKey(String value) { putSecret(VALUE, KEY_ALIAS, value); }
    public String getApiKey() { return getSecret(VALUE, KEY_ALIAS); }
    public void putPcSecret(String value) { putSecret(PC_VALUE, PC_KEY_ALIAS, value); }
    public String getPcSecret() { return getSecret(PC_VALUE, PC_KEY_ALIAS); }

    private void putSecret(String prefName, String alias, String value) {
        try {
            if (value == null || value.isEmpty()) {
                prefs.edit().remove(prefName).remove(prefName + "_iv").apply();
                return;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            prefs.edit()
                    .putString(prefName, android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP))
                    .putString(prefName + "_iv", android.util.Base64.encodeToString(cipher.getIV(), android.util.Base64.NO_WRAP))
                    .apply();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to protect NOVA secret", e);
        }
    }

    private String getSecret(String prefName, String alias) {
        try {
            String encrypted = prefs.getString(prefName, "");
            String ivText = prefs.getString(prefName + "_iv", "");
            if (encrypted.isEmpty() || ivText.isEmpty()) return "";
            byte[] data = android.util.Base64.decode(encrypted, android.util.Base64.NO_WRAP);
            byte[] iv = android.util.Base64.decode(ivText, android.util.Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(alias), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private SecretKey getOrCreateKey(String alias) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(alias)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(alias, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
