package com.aircontrol;

import android.app.Activity;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit approval boundary for tools that can mutate or execute on the PC. */
public interface NovaPermissionGate {
    /** Returns true only when the user has explicitly approved this exact tool invocation. */
    boolean approve(String toolType, String arguments);

    NovaPermissionGate FAIL_CLOSED = (toolType, arguments) -> false;
    AtomicReference<NovaPermissionGate> ACTIVE_GATE = new AtomicReference<>(FAIL_CLOSED);

    /** Install the UI-backed gate for the current NOVA activity; null restores deny-by-default. */
    static void install(NovaPermissionGate gate) {
        ACTIVE_GATE.set(gate == null ? FAIL_CLOSED : gate);
    }

    /** Delegating default used by Brain/PC executor; safe until an Activity installs a real gate. */
    NovaPermissionGate DENY_BY_DEFAULT = (toolType, arguments) -> ACTIVE_GATE.get().approve(toolType, arguments);

    /** Convenience helper for Activity-backed approval. */
    static NovaPermissionGate forActivity(Activity activity) {
        return activity == null ? FAIL_CLOSED : new NovaConfirmationGate(activity);
    }
}
