package com.aircontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * User approval boundary for PC mutations/execution.
 * Each request is approved or denied explicitly; approval is not persisted.
 */
public final class NovaConfirmationGate implements NovaPermissionGate {
    private static final long TIMEOUT_SECONDS = 60L;
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());

    public NovaConfirmationGate(Activity activity) {
        this.activity = activity;
    }

    @Override public boolean approve(String toolType, String arguments) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean approved = new AtomicBoolean(false);
        main.post(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) {
                latch.countDown();
                return;
            }
            String details = arguments == null || arguments.trim().isEmpty()
                    ? "No parameters"
                    : arguments.trim();
            new AlertDialog.Builder(activity)
                    .setTitle("NOVA permission required")
                    .setMessage("Allow this PC action?\n\nTool: " + toolType + "\n\n" + details)
                    .setNegativeButton("Deny", (dialog, which) -> latch.countDown())
                    .setPositiveButton("Allow once", (dialog, which) -> {
                        approved.set(true);
                        latch.countDown();
                    })
                    .setOnCancelListener(dialog -> latch.countDown())
                    .show();
        });

        try {
            return latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) && approved.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
