package com.aircontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * Restores NOVA's user-enabled hands-free mode after a device reboot.
 * Android may still refuse microphone foreground-service startup from boot;
 * that platform restriction is caught rather than crashing the receiver.
 */
public final class NovaBootReceiver extends BroadcastReceiver {
    private static final String TAG = "NovaBoot";
    private static final String PREFS = "nova_voice_prefs";
    private static final String PREF_ALWAYS_LISTENING = "always_listening";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_ALWAYS_LISTENING, false)) return;

        Intent service = new Intent(context, NovaVoiceService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
            Log.d(TAG, "Requested NOVA voice service after boot");
        } catch (Exception e) {
            Log.w(TAG, "Android blocked microphone service at boot", e);
        }
    }
}
