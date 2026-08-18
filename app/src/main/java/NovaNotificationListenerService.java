package com.aircontrol;

import android.app.Notification;
import android.content.pm.PackageManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.Deque;

/** Optional notification bridge. Android requires the user to enable notification access. */
public final class NovaNotificationListenerService extends NotificationListenerService {
    private static final String TAG = "NovaNotifications";
    private static final int MAX = 30;
    private static final Deque<String> recent = new ArrayDeque<>();

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null || sbn.getNotification().extras == null) return;
        CharSequence title = sbn.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = sbn.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT);
        if (title == null && text == null) return;

        String app = sbn.getPackageName();
        try {
            CharSequence label = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(app, 0));
            app = String.valueOf(label);
        } catch (Exception ignored) { }

        StringBuilder item = new StringBuilder();
        item.append(app).append(" — ");
        if (title != null) item.append(title);
        if (text != null && text.length() > 0) item.append(": ").append(text);

        synchronized (recent) {
            recent.addFirst(item.toString());
            while (recent.size() > MAX) recent.removeLast();
        }
        Log.d(TAG, "Notification captured: " + item);
    }

    @Override public void onNotificationRemoved(StatusBarNotification sbn) {
        // Intentionally kept quiet; NOVA only retains a short local summary.
    }

    public static String snapshot() {
        synchronized (recent) {
            if (recent.isEmpty()) return "No recent notifications captured.";
            StringBuilder out = new StringBuilder();
            int count = 0;
            for (String item : recent) {
                if (count++ > 0) out.append('\n');
                out.append("• ").append(item);
                if (count >= 12) break;
            }
            return out.toString();
        }
    }

    public static void clearLocalSnapshot() {
        synchronized (recent) { recent.clear(); }
    }
}
