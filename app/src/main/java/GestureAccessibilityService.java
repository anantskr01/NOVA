package com.aircontrol;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class GestureAccessibilityService extends AccessibilityService {

    private static final String TAG = "NovaAccessibility";

    private static GestureAccessibilityService instance;

    // Short touch strokes make the control loop responsive.
    private static final long SWIPE_DURATION = 45;

    private static final float HORIZONTAL_DISTANCE = 0.16f;
    private static final float VERTICAL_DISTANCE = 0.16f;

    // Finger movement is queued because Android can reject a new
    // accessibility gesture while the previous one is still running.
    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private boolean fingerGestureRunning = false;
    private float pendingFingerX = 0f;
    private float pendingFingerY = 0f;

    private static final float MAX_PENDING_X = 0.055f;
    private static final float MAX_PENDING_Y = 0.055f;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "NOVA ACCESSIBILITY SERVICE CONNECTED");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // NOVA uses direct gestures and does not require window events.
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "NOVA ACCESSIBILITY INTERRUPTED");
    }

    public static GestureAccessibilityService getInstance() {
        return instance;
    }

    /** Public bridge used by NOVA command tools for Android global actions. */
    /** Returns a short, safe summary of visible accessibility text. */
    public String getVisibleTextSummary() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "I cannot read the current screen. Accessibility window content may be unavailable.";
        StringBuilder out = new StringBuilder();
        collectText(root, out, 0);
        if (out.length() == 0) return "I cannot find readable text on the current screen.";
        return out.length() > 1800 ? out.substring(0, 1800) : out.toString();
    }

    /**
     * Structured accessibility snapshot used by NOVA's planner. It exposes only
     * UI metadata already available through AccessibilityService.
     */
    public String getUiSnapshot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "No accessibility window is available.";
        StringBuilder out = new StringBuilder();
        collectUiSnapshot(root, out, 0);
        if (out.length() == 0) return "No readable UI elements found.";
        return out.length() > 6000 ? out.substring(0, 6000) : out.toString();
    }

    private void collectUiSnapshot(AccessibilityNodeInfo node, StringBuilder out, int depth) {
        if (node == null || depth > 18 || out.length() > 6000) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String label = text != null && text.length() > 0 ? text.toString().trim()
                : (desc != null ? desc.toString().trim() : "");
        if (!label.isEmpty() || node.isClickable()) {
            android.graphics.Rect bounds = new android.graphics.Rect();
            node.getBoundsInScreen(bounds);
            out.append("• ")
                    .append(label.isEmpty() ? "[unlabeled]" : label)
                    .append(" | class=").append(node.getClassName())
                    .append(" | clickable=").append(node.isClickable())
                    .append(" | bounds=").append(bounds.left).append(',').append(bounds.top)
                    .append('-').append(bounds.right).append(',').append(bounds.bottom)
                    .append('\n');
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectUiSnapshot(node.getChild(i), out, depth + 1);
        }
    }

    /** Clicks the Nth clickable/text-bearing element in the current UI tree. */
    public boolean clickVisibleIndex(int requestedIndex) {
        if (requestedIndex < 1) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        java.util.ArrayList<AccessibilityNodeInfo> nodes = new java.util.ArrayList<>();
        collectClickableNodes(root, nodes, 0);
        if (requestedIndex > nodes.size()) return false;
        AccessibilityNodeInfo target = nodes.get(requestedIndex - 1);
        try {
            if (target.isClickable() && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            AccessibilityNodeInfo parent = target.getParent();
            while (parent != null) {
                if (parent.isClickable() && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                parent = parent.getParent();
            }
        } catch (Exception e) {
            Log.e(TAG, "CLICK INDEX ERROR", e);
        }
        return false;
    }

    private void collectClickableNodes(AccessibilityNodeInfo node, java.util.ArrayList<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 18 || out.size() >= 50) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if (node.isClickable() && ((text != null && text.length() > 0) || (desc != null && desc.length() > 0))) {
            out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) collectClickableNodes(node.getChild(i), out, depth + 1);
    }

    /** Finds a visible node by text/content-description and clicks it when possible. */
    public boolean clickText(String requested) {
        if (requested == null || requested.trim().isEmpty()) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo target = findTextNode(root, requested.trim().toLowerCase());
        if (target == null) return false;
        try {
            if (target.isClickable() && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            AccessibilityNodeInfo parent = target.getParent();
            while (parent != null) {
                if (parent.isClickable() && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                parent = parent.getParent();
            }
        } catch (Exception e) {
            Log.e(TAG, "CLICK TEXT ERROR", e);
        }
        return false;
    }

    private AccessibilityNodeInfo findTextNode(AccessibilityNodeInfo node, String requested) {
        if (node == null) return null;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String value = text != null ? text.toString().trim().toLowerCase() : "";
        String description = desc != null ? desc.toString().trim().toLowerCase() : "";
        if ((!value.isEmpty() && (value.equals(requested) || value.contains(requested))) ||
                (!description.isEmpty() && (description.equals(requested) || description.contains(requested)))) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findTextNode(node.getChild(i), requested);
            if (result != null) return result;
        }
        return null;
    }

    private void collectText(AccessibilityNodeInfo node, StringBuilder out, int depth) {
        if (node == null || depth > 25 || out.length() > 1800) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if (text != null && text.length() > 0) {
            String value = text.toString().trim();
            if (!value.isEmpty()) out.append(value).append('\n');
        } else if (desc != null && desc.length() > 0) {
            String value = desc.toString().trim();
            if (!value.isEmpty()) out.append(value).append('\n');
        }
        for (int i = 0; i < node.getChildCount(); i++) collectText(node.getChild(i), out, depth + 1);
    }

    public boolean performGlobalActionPublic(int action) {
        try {
            return performGlobalAction(action);
        } catch (Exception e) {
            Log.e(TAG, "GLOBAL ACTION ERROR", e);
            return false;
        }
    }

    private Point getScreenSize() {
        Point size = new Point();

        try {
            size.x = getResources().getDisplayMetrics().widthPixels;
            size.y = getResources().getDisplayMetrics().heightPixels;
        } catch (Exception e) {
            Log.e(TAG, "SCREEN SIZE ERROR", e);
        }

        if (size.x <= 0) size.x = 1080;
        if (size.y <= 0) size.y = 1920;

        return size;
    }

    private boolean performSwipe(
            float startX,
            float startY,
            float endX,
            float endY,
            GestureResultCallback callback
    ) {
        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(
                        path,
                        0,
                        SWIPE_DURATION
                );

        GestureDescription gesture =
                new GestureDescription.Builder()
                        .addStroke(stroke)
                        .build();

        boolean accepted =
                dispatchGesture(
                        gesture,
                        callback,
                        null
                );

        Log.d(TAG, "dispatchGesture accepted = " + accepted);
        return accepted;
    }

    private void performSwipe(
            float startX,
            float startY,
            float endX,
            float endY
    ) {
        performSwipe(
                startX,
                startY,
                endX,
                endY,
                new GestureResultCallback() {
                    @Override
                    public void onCompleted(
                            GestureDescription gestureDescription
                    ) {
                        Log.d(TAG, "GESTURE COMPLETED");
                    }

                    @Override
                    public void onCancelled(
                            GestureDescription gestureDescription
                    ) {
                        Log.d(TAG, "GESTURE CANCELLED");
                    }
                }
        );
    }

    public void swipeRight() {
        Point size = getScreenSize();
        float y = size.y * 0.50f;
        float centerX = size.x * 0.50f;
        float distance = size.x * HORIZONTAL_DISTANCE;
        performSwipe(centerX - distance, y, centerX, y);
    }

    public void swipeLeft() {
        Point size = getScreenSize();
        float y = size.y * 0.50f;
        float centerX = size.x * 0.50f;
        float distance = size.x * HORIZONTAL_DISTANCE;
        performSwipe(centerX + distance, y, centerX, y);
    }

    public void swipeUp() {
        Point size = getScreenSize();
        float x = size.x * 0.50f;
        float centerY = size.y * 0.50f;
        float distance = size.y * VERTICAL_DISTANCE;
        performSwipe(x, centerY + distance, x, centerY);
    }

    public void swipeDown() {
        Point size = getScreenSize();
        float x = size.x * 0.50f;
        float centerY = size.y * 0.50f;
        float distance = size.y * VERTICAL_DISTANCE;
        performSwipe(x, centerY - distance, x, centerY);
    }

    // =========================================================
    // LOW-LATENCY FINGER CONTROL
    // =========================================================

    public void moveFinger(float deltaX, float deltaY) {
        if (Math.abs(deltaX) < 0.0005f &&
                Math.abs(deltaY) < 0.0005f) {
            return;
        }

        mainHandler.post(() -> {
            pendingFingerX = clamp(
                    pendingFingerX + deltaX,
                    -MAX_PENDING_X,
                    MAX_PENDING_X
            );

            pendingFingerY = clamp(
                    pendingFingerY + deltaY,
                    -MAX_PENDING_Y,
                    MAX_PENDING_Y
            );

            dispatchPendingFingerMovement();
        });
    }

    private void dispatchPendingFingerMovement() {
        if (fingerGestureRunning) {
            return;
        }

        if (Math.abs(pendingFingerX) < 0.002f &&
                Math.abs(pendingFingerY) < 0.002f) {
            return;
        }

        float deltaX = pendingFingerX;
        float deltaY = pendingFingerY;

        pendingFingerX = 0f;
        pendingFingerY = 0f;

        Point size = getScreenSize();

        float centerX = size.x * 0.50f;
        float centerY = size.y * 0.50f;

        float screenDeltaX = deltaX * size.x * 1.8f;
        float screenDeltaY = deltaY * size.y * 1.8f;

        screenDeltaX = clamp(
                screenDeltaX,
                -size.x * 0.06f,
                size.x * 0.06f
        );

        screenDeltaY = clamp(
                screenDeltaY,
                -size.y * 0.06f,
                size.y * 0.06f
        );

        float endX = clamp(
                centerX + screenDeltaX,
                5f,
                size.x - 5f
        );

        float endY = clamp(
                centerY + screenDeltaY,
                5f,
                size.y - 5f
        );

        fingerGestureRunning = true;

        boolean accepted = performSwipe(
                centerX,
                centerY,
                endX,
                endY,
                new GestureResultCallback() {
                    @Override
                    public void onCompleted(
                            GestureDescription gestureDescription
                    ) {
                        fingerGestureRunning = false;
                        dispatchPendingFingerMovement();
                    }

                    @Override
                    public void onCancelled(
                            GestureDescription gestureDescription
                    ) {
                        fingerGestureRunning = false;
                        dispatchPendingFingerMovement();
                    }
                }
        );

        if (!accepted) {
            fingerGestureRunning = false;
            pendingFingerX = clamp(
                    pendingFingerX + deltaX,
                    -MAX_PENDING_X,
                    MAX_PENDING_X
            );
            pendingFingerY = clamp(
                    pendingFingerY + deltaY,
                    -MAX_PENDING_Y,
                    MAX_PENDING_Y
            );
        }
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        pendingFingerX = 0f;
        pendingFingerY = 0f;
        fingerGestureRunning = false;

        if (instance == this) {
            instance = null;
        }

        Log.d(TAG, "NOVA ACCESSIBILITY SERVICE DESTROYED");
        super.onDestroy();
    }

}
