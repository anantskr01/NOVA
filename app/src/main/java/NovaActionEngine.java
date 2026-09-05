package com.aircontrol;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.JSONObject;

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
        String action = type == null ? "none" : type.trim().toLowerCase();
        try {
            JSONObject request = new JSONObject()
                    .put("type", action)
                    .put("value", value == null ? "" : value);
            String validation = NovaActionSchema.validate(request);
            if (!validation.isEmpty()) {
                if (callback != null) callback.status("ACTION BLOCKED • " + validation);
                return false;
            }

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
                    try { delay = Long.parseLong(value == null ? "500" : value.trim()); }
                    catch (NumberFormatException ignored) { delay = 500L; }
                    SystemClock.sleep(Math.max(100L, Math.min(delay, 2500L)));
                    return true;
                case "type_text":
                    return typeText(value);
                case "press_enter":
                    return pressEnter();
                case "search":
                    return openWebUrl("https://www.google.com/search?q=" + Uri.encode(value.trim()));
                case "open_url":
                    return openWebUrl(value);
                case "open_package":
                    Intent pkg = context.getPackageManager().getLaunchIntentForPackage(value.trim());
                    if (pkg == null) return false;
                    launch(pkg);
                    return true;
                case "open_app":
                    android.content.pm.ResolveInfo info = apps.resolve(value);
                    Intent appIntent = apps.launchIntent(info);
                    if (appIntent == null) return false;
                    launch(appIntent);
                    return true;
                case "settings":
                    launch(new Intent(Settings.ACTION_SETTINGS));
                    return true;
                case "none":
                    return true;
                default:
                    if (callback != null) callback.status("ACTION BLOCKED • unknown_action:" + action);
                    return false;
            }
        } catch (Exception e) {
            if (callback != null) callback.status("ACTION FAILED • " + action);
            return false;
        }
    }

    private boolean typeText(String value) {
        GestureAccessibilityService service = GestureAccessibilityService.getInstance();
        if (service == null) {
            if (callback != null) callback.status("ACCESSIBILITY NOT CONNECTED");
            return false;
        }
        return service.typeText(value == null ? "" : value);
    }

    private boolean pressEnter() {
        GestureAccessibilityService service = GestureAccessibilityService.getInstance();
        if (service == null) {
            if (callback != null) callback.status("ACCESSIBILITY NOT CONNECTED");
            return false;
        }
        return service.pressEnter();
    }

    public boolean global(int action) {
        GestureAccessibilityService service = GestureAccessibilityService.getInstance();
        if (service == null) {
            if (callback != null) callback.status("ACCESSIBILITY NOT CONNECTED");
            return false;
        }
        return service.performGlobalActionPublic(action);
    }

    private boolean swipe(String direction) {
        GestureAccessibilityService service = GestureAccessibilityService.getInstance();
        if (service == null) {
            if (callback != null) callback.status("ACCESSIBILITY NOT CONNECTED");
            return false;
        }
        if ("up".equals(direction)) service.swipeUp();
        else if ("down".equals(direction)) service.swipeDown();
        else if ("left".equals(direction)) service.swipeLeft();
        else service.swipeRight();
        return true;
    }

    private boolean openWebUrl(String raw) {
        if (raw == null || raw.trim().isEmpty()) return false;
        String value = raw.trim();
        Uri uri = Uri.parse(value).normalizeScheme();
        String scheme = uri.getScheme();
        if (!("http".equals(scheme) || "https".equals(scheme))) {
            if (callback != null) callback.status("ACTION BLOCKED • URL SCHEME");
            return false;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        if (intent.resolveActivity(context.getPackageManager()) == null) {
            if (callback != null) callback.status("ACTION FAILED • NO BROWSER");
            return false;
        }
        launch(intent);
        return true;
    }

    private void launch(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}
