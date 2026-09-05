package com.aircontrol;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.JSONObject;
import java.util.Locale;

/** Central, permission-aware action layer used by voice, text and AI plans. */
public final class NovaActionEngine {
    public interface Callback { void status(String text); void reply(String text); }
    private final Context context;
    private final Callback callback;
    private final NovaAppCatalog apps;

    public NovaActionEngine(Context context, Callback callback) {
        this.context = context.getApplicationContext(); this.callback = callback; this.apps = new NovaAppCatalog(this.context);
    }

    public boolean execute(String type, String value) {
        String action = type == null ? "none" : type.trim().toLowerCase(Locale.ROOT);
        NovaDiagnostics.event("action_requested", action);
        try {
            JSONObject request = new JSONObject().put("type", action).put("value", value == null ? "" : value);
            String validation = NovaActionSchema.validate(request);
            if (!validation.isEmpty()) {
                NovaDiagnostics.event("action_validated", action + " blocked=" + validation);
                if (callback != null) callback.status("ACTION BLOCKED • " + validation);
                return false;
            }
            NovaDiagnostics.event("action_validated", action + " ok");
            boolean result;
            switch (action) {
                case "home": result = global(AccessibilityService.GLOBAL_ACTION_HOME); break;
                case "back": result = global(AccessibilityService.GLOBAL_ACTION_BACK); break;
                case "recents": result = global(AccessibilityService.GLOBAL_ACTION_RECENTS); break;
                case "notifications": result = global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS); break;
                case "quick_settings": result = global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS); break;
                case "scroll_up": result = swipe("up"); break;
                case "scroll_down": result = swipe("down"); break;
                case "swipe_left": result = swipe("left"); break;
                case "swipe_right": result = swipe("right"); break;
                case "wait":
                    long delay; try { delay = Long.parseLong(value == null ? "500" : value.trim()); } catch (NumberFormatException ignored) { delay = 500L; }
                    SystemClock.sleep(Math.max(100L, Math.min(delay, 2500L))); result = true; break;
                case "type_text": result = typeText(value); break;
                case "press_enter": result = pressEnter(); break;
                case "search": result = openWebUrl("https://www.google.com/search?q=" + Uri.encode(value.trim())); break;
                case "open_url": result = openWebUrl(value); break;
                case "open_package":
                    Intent pkg = context.getPackageManager().getLaunchIntentForPackage(value.trim());
                    if (pkg == null) result = false; else { launch(pkg); result = true; }
                    break;
                case "open_app":
                    android.content.pm.ResolveInfo info = apps.resolve(value); Intent appIntent = apps.launchIntent(info);
                    if (appIntent == null) result = false; else { launch(appIntent); result = true; }
                    break;
                case "settings": launch(new Intent(Settings.ACTION_SETTINGS)); result = true; break;
                case "none": result = true; break;
                default:
                    if (callback != null) callback.status("ACTION BLOCKED • unknown_action:" + action);
                    result = false;
                    break;
            }
            NovaDiagnostics.event("action_executed", action + " result=" + result);
            return result;
        } catch (Exception e) {
            NovaDiagnostics.event("action_failed", action + " class=" + NovaAiProviderManager.classifyFailure(e.getMessage()));
            if (callback != null) callback.status("ACTION FAILED • " + action);
            return false;
        }
    }

    private boolean typeText(String value) {
        GestureAccessibilityService service = GestureAccessibilityService.getInstance();
        if (service == null) { NovaDiagnostics.event("accessibility_unavailable", "type_text"); if (callback != null) callback.status("ACCESSIBILITY NOT CONNECTED"); return false; }
        return service.typeText(value == null ? "" : value);
    }

    private boolean pressEnter() {
        GestureAccessibilityService service = GestureAccessibilityService.getInstance();
        if (service == null) { NovaDiagnostics.event("accessibility_unavailable", "press_enter"); if (callback != null) callback.status("ACCESSIBILITY NOT CONNECTED"); return false; }
        return service.pressEnter();
    }

    public boolean global(int action) {
        GestureAccessibilityService service = GestureAccessibilityService.getInstance();
        if (service == null) { NovaDiagnostics.event("accessibility_unavailable", "global_action"); if (callback != null) callback.status("ACCESSIBILITY NOT CONNECTED"); return false; }
        return service.performGlobalActionPublic(action);
    }

    private boolean swipe(String direction) {
        GestureAccessibilityService service = GestureAccessibilityService.getInstance();
        if (service == null) { NovaDiagnostics.event("accessibility_unavailable", direction); if (callback != null) callback.status("ACCESSIBILITY NOT CONNECTED"); return false; }
        if ("up".equals(direction)) service.swipeUp(); else if ("down".equals(direction)) service.swipeDown(); else if ("left".equals(direction)) service.swipeLeft(); else service.swipeRight();
        return true;
    }

    private boolean openWebUrl(String raw) {
        if (raw == null || raw.trim().isEmpty()) return false;
        String value = raw.trim(); Uri uri = Uri.parse(value).normalizeScheme(); String scheme = uri.getScheme();
        if (!("http".equals(scheme) || "https".equals(scheme))) { NovaDiagnostics.event("action_blocked", "url_scheme"); if (callback != null) callback.status("ACTION BLOCKED • URL SCHEME"); return false; }
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);
        if (intent.resolveActivity(context.getPackageManager()) == null) { NovaDiagnostics.event("action_failed", "no_browser"); if (callback != null) callback.status("ACTION FAILED • NO BROWSER"); return false; }
        launch(intent); return true;
    }

    private void launch(Intent intent) { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(intent); }
}
