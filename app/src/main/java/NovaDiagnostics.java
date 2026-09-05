package com.aircontrol;

import android.util.Log;
import org.json.JSONObject;

/** Bounded structured observability for agent lifecycle and recovery diagnostics. */
public final class NovaDiagnostics {
    private static final String TAG = "NovaDiagnostics";
    private static final int MAX_TEXT = 320;
    private NovaDiagnostics() { }

    public static void event(String name, String detail) {
        try {
            JSONObject e = new JSONObject()
                    .put("event", name == null ? "unknown" : compact(name))
                    .put("detail", compact(detail));
            Log.i(TAG, e.toString());
        } catch (Exception ignored) { }
    }

    /** Never emit likely credentials/tokens even if a caller accidentally supplies them as detail. */
    public static String compact(String value) {
        if (value == null) return "";
        String s = value.replace('\r', ' ').replace('\n', ' ').trim();
        s = s.replaceAll("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^,; ]+", "$1[REDACTED]");
        s = s.replaceAll("(?i)(api[_ -]?key\\s*[:=]\\s*)[^,; ]+", "$1[REDACTED]");
        s = s.replaceAll("(?i)(password|passwd|secret|token)\\s*[:=]\\s*[^,; ]+", "$1=[REDACTED]");
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) + "…" : s;
    }
}
