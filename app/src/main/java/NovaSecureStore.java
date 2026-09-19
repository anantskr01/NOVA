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

/** Android Keystore-backed storage for NOVA secrets. */
public final class NovaSecureStore {
    private static final String PREFS = "nova_secure_store";
    private static final String API_ALIAS = "nova_api_key";
    private static final String PC_ALIAS = "nova_pc_token";
    private final SharedPreferences prefs;
    public NovaSecureStore(Context context) { prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    public void putApiKey(String value) { putSecret(API_ALIAS, "encrypted_api_key", value); }
    public String getApiKey() { return getSecret(API_ALIAS, "encrypted_api_key"); }
    public void putPcToken(String value) { putSecret(PC_ALIAS, "encrypted_pc_token", value); }
    public String getPcToken() { return getSecret(PC_ALIAS, "encrypted_pc_token"); }
    private void putSecret(String alias, String key, String value) {
        try {
            if (value == null || value.isEmpty()) { prefs.edit().remove(key).remove(key + "_iv").apply(); return; }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias));
            prefs.edit().putString(key, android.util.Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), android.util.Base64.NO_WRAP))
                    .putString(key + "_iv", android.util.Base64.encodeToString(cipher.getIV(), android.util.Base64.NO_WRAP)).apply();
        } catch (Exception e) { throw new IllegalStateException("Unable to protect NOVA secret", e); }
    }
    private String getSecret(String alias, String key) {
        try {
            String encrypted = prefs.getString(key, ""), ivText = prefs.getString(key + "_iv", ""); if (encrypted.isEmpty() || ivText.isEmpty()) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(alias), new GCMParameterSpec(128, android.util.Base64.decode(ivText, android.util.Base64.NO_WRAP)));
            return new String(cipher.doFinal(android.util.Base64.decode(encrypted, android.util.Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }
    private SecretKey getOrCreateKey(String alias) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore"); keyStore.load(null);
        if (keyStore.containsAlias(alias)) return ((KeyStore.SecretKeyEntry) keyStore.getEntry(alias, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
