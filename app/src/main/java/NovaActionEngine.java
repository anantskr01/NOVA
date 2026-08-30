package com.aircontrol;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.Locale;

/** Central, permission-aware action layer used by voice, text and AI plans. */
public final class NovaActionEngine {
    public interface Callback {
        void status(String text);
        void reply(String text);
    }

    private final Context context;
    private final Callback callback;
    private final NovaAppCatalog apps;

    public NovaActionEngine(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        this.apps = new NovaAppCatalog(this.context);
    }

    public boolean execute(String type, String value) {
        String action = type == null ? "none" : type.trim().toLowerCase(Locale.ROOT);
        String argument = value == null ? "" : value.trim();
        try {
            switch (action) {
                case "home": return global(AccessibilityService.GLOBAL_ACTION_HOME);
                case "back": return global(AccessibilityService.GLOBAL_ACTION_BACK);
                case "recents": return global(AccessibilityService.GLOBAL_ACTION_RECENTS);
                case "notifications": return global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
                case "quick_settings": return global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
                case "scroll_up": return swipe("up");
                case "scroll_down": return swipe("down");
                case "swipe_left": return swipe("left");
                case "swipe_right": return swipe("right");
                case "wait":
                    long delay;
                    try { delay = Long.parseLong(argument.isEmpty() ? "500" : argument); }
                    catch (NumberFormatException ignored) { return false; }
                    SystemClock.sleep(Math.max(100L, Math.min(delay, 2500L)));
                    return true;
                case "search":
                    if (argument.isEmpty()) return false;
                    launch(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://www.google.com/search?q=" + Uri.encode(argument))));
                    return true;
                case "open_url":
                    return openUrl(argument);
                case "open_package":
                    return openPackage(argument);
                case "open_app":
                    return openApp(argument);
                case "settings":
                    launch(new Intent(Settings.ACTION_SETTINGS));
                    return true;
                case "none":
                default:
                    return false;
            }
        } catch (Exception e) {
            callback.status("ACTION FAILED • " + action);
            return false;
        }
    }

    private boolean openUrl(String value) {
        if (value.isEmpty()) return false;
        Uri uri = Uri.parse(value);
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            callback.status("ACTION BLOCKED • UNSAFE URL SCHEME");
            return false;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        if (intent.resolveActivity(context.getPackageManager()) == null) return false;
        launch(intent);
        return true;
    }

    private boolean openPackage(String value) {
        if (value.isEmpty() || value.indexOf(' ') >= 0) return false;
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(value);
        if (intent == null) return false;
        launch(intent);
        return true;
    }

    private boolean openApp(String value) {
        if (value.isEmpty()) return false;
        android.content.pm.ResolveInfo info = apps.resolve(value);
        Intent intent = apps.launchIntent(info);
        if (intent == null) return false;
        launch(intent);
        return true;
    }

    public boolean global(int action) {
        GestureAccessibilityService service = GestureAccessibilityService.getInstance();
        if (service == null) {
            callback.status("ACCESSIBILITY NOT CONNECTED");
            return false;
        }
        boolean accepted = service.performGlobalActionPublic(action);
        if (!accepted) callback.status("ACTION BLOCKED • ACCESSIBILITY REJECTED");
        return accepted;
    }

    private boolean swipe(String direction) {
        GestureAccessibilityService service = GestureAccessibilityService.getInstance();
        if (service == null) {
            callback.status("ACCESSIBILITY NOT CONNECTED");
            return false;
        }
        if ("up".equals(direction)) service.swipeUp();
        else if ("down".equals(direction)) service.swipeDown();
        else if ("left".equals(direction)) service.swipeLeft();
        else if ("right".equals(direction)) service.swipeRight();
        else return false;
        return true;
    }

    private void launch(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
