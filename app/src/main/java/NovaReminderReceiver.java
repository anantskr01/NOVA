package com.aircontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Delivers local NOVA reminders created by the assistant. */
public class NovaReminderReceiver extends BroadcastReceiver {
    private static final String CHANNEL = "nova_reminders";
    private static final String TITLE = "title";

    @Override public void onReceive(Context context, Intent intent) {
        String title = intent == null ? "NOVA reminder" : intent.getStringExtra(TITLE);
        if (title == null || title.trim().isEmpty()) title = "NOVA reminder";
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "NOVA Reminders", NotificationManager.IMPORTANCE_HIGH);
            manager.createNotificationChannel(channel);
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL)
                : new Notification.Builder(context);
        builder.setContentTitle("NOVA")
                .setContentText(title)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER);
        manager.notify((int) (System.currentTimeMillis() & 0x7fffffff), builder.build());
    }
}
