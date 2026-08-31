package com.aircontrol;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Point;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class GestureAccessibilityService extends AccessibilityService {

    private static final String TAG = "NovaAccessibility";
    private static GestureAccessibilityService instance;

    private static final long SWIPE_DURATION = 45;
    private static final float HORIZONTAL_DISTANCE = 0.16f;
    private static final float VERTICAL_DISTANCE = 0.16f;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean fingerGestureRunning = false;
    private float pendingFingerX = 0f;
    private float pendingFingerY = 0f;
    private static final float MAX_PENDING_X = 0.055f;
    private static final float MAX_PENDING_Y = 0.055f;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "NOVA ACCESSIBILITY SERVICE CONNECTED");
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override public void onInterrupt() { Log.d(TAG, "NOVA ACCESSIBILITY INTERRUPTED"); }

    public static GestureAccessibilityService getInstance() { return instance; }

    public String getVisibleTextSummary() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "I cannot read the current screen. Accessibility window content may be unavailable.";
        StringBuilder out = new StringBuilder();
        collectText(root, out, 0);
        if (out.length() == 0) return "I cannot find readable text on the current screen.";
        return out.length() > 1800 ? out.substring(0, 1800) : out.toString();
    }

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
                    .append(" | enabled=").append(node.isEnabled())
                    .append(" | editable=").append(node.isEditable())
                    .append(" | bounds=").append(bounds.left).append(',').append(bounds.top)
                    .append('-').append(bounds.right).append(',').append(bounds.bottom)
                    .append('\n');
        }
        for (int i = 0; i < node.getChildCount(); i++) collectUiSnapshot(node.getChild(i), out, depth + 1);
    }

    public boolean clickVisibleIndex(int requestedIndex) {
        if (requestedIndex < 1) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        java.util.ArrayList<AccessibilityNodeInfo> nodes = new java.util.ArrayList<>();
        collectClickableNodes(root, nodes, 0);
        if (requestedIndex > nodes.size()) return false;
        return clickNodeOrParent(nodes.get(requestedIndex - 1));
    }

    private void collectClickableNodes(AccessibilityNodeInfo node, java.util.ArrayList<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 18 || out.size() >= 50) return;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        if (node.isClickable() && node.isEnabled() && ((text != null && text.length() > 0) || (desc != null && desc.length() > 0))) {
            out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) collectClickableNodes(node.getChild(i), out, depth + 1);
    }

    public boolean clickText(String requested) {
        if (requested == null || requested.trim().isEmpty()) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo target = findBestTextNode(root, requested.trim().toLowerCase(), 0);
        return target != null && clickNodeOrParent(target);
    }

    private AccessibilityNodeInfo findBestTextNode(AccessibilityNodeInfo node, String requested, int depth) {
        if (node == null || depth > 18) return null;
        AccessibilityNodeInfo best = null;
        int bestScore = -1;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo candidate = findBestTextNode(node.getChild(i), requested, depth + 1);
            int score = scoreNode(candidate, requested);
            if (score > bestScore) { bestScore = score; best = candidate; }
        }
        int selfScore = scoreNode(node, requested);
        if (selfScore > bestScore) return node;
        return best;
    }

    private int scoreNode(AccessibilityNodeInfo node, String requested) {
        if (node == null || !node.isVisibleToUser()) return -1;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String value = text == null ? "" : text.toString().trim().toLowerCase();
        String description = desc == null ? "" : desc.toString().trim().toLowerCase();
        if (value.isEmpty() && description.isEmpty()) return -1;
        int score = 0;
        if (value.equals(requested)) score += 100;
        else if (value.contains(requested)) score += 70;
        if (description.equals(requested)) score += 95;
        else if (description.contains(requested)) score += 65;
        if (node.isClickable()) score += 35;
        if (node.isEnabled()) score += 10;
        if (node.isFocusable()) score += 5;
        if (node.isEditable()) score += 4;
        return score;
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo target) {
        if (target == null || !target.isVisibleToUser()) return false;
        try {
            if (target.isEnabled() && target.isClickable() && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            AccessibilityNodeInfo parent = target.getParent();
            int depth = 0;
            while (parent != null && depth++ < 6) {
                if (parent.isEnabled() && parent.isClickable() && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                parent = parent.getParent();
            }
        } catch (Exception e) {
            Log.e(TAG, "CLICK NODE ERROR", e);
        }
        return false;
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
        try { return performGlobalAction(action); }
        catch (Exception e) { Log.e(TAG, "GLOBAL ACTION ERROR", e); return false; }
    }

    private Point getScreenSize() {
        Point size = new Point();
        try {
            size.x = getResources().getDisplayMetrics().widthPixels;
            size.y = getResources().getDisplayMetrics().heightPixels;
        } catch (Exception e) { Log.e(TAG, "SCREEN SIZE ERROR", e); }
        if (size.x <= 0) size.x = 1080;
        if (size.y <= 0) size.y = 1920;
        return size;
    }

    private boolean performSwipe(float startX, float startY, float endX, float endY, GestureResultCallback callback) {
        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, SWIPE_DURATION);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        boolean accepted = dispatchGesture(gesture, callback, null);
        Log.d(TAG, "dispatchGesture accepted = " + accepted);
        return accepted;
    }

    private void performSwipe(float startX, float startY, float endX, float endY) {
        performSwipe(startX, startY, endX, endY, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) { Log.d(TAG, "GESTURE COMPLETED"); }
            @Override public void onCancelled(GestureDescription gestureDescription) { Log.d(TAG, "GESTURE CANCELLED"); }
        });
    }

    /** Dispatch a one-shot tap in screen pixels for the remote device agent. */
    public boolean tapCoordinates(float x, float y) {
        if (Float.isNaN(x) || Float.isNaN(y)) return false;
        Point size = getScreenSize();
        if (x < 0 || y < 0 || x >= size.x || y >= size.y) return false;
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 1))
                .build();
        return dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) { }
            @Override public void onCancelled(GestureDescription gestureDescription) { }
        }, null);
    }

    /** Dispatch a bounded arbitrary swipe in screen pixels. */
    public boolean swipeCoordinates(float x1, float y1, float x2, float y2, long durationMs) {
        if (Float.isNaN(x1) || Float.isNaN(y1) || Float.isNaN(x2) || Float.isNaN(y2)) return false;
        Point size = getScreenSize();
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0 || x1 >= size.x || x2 >= size.x || y1 >= size.y || y2 >= size.y) return false;
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        long duration = Math.max(50L, Math.min(3000L, durationMs));
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, duration))
                .build();
        return dispatchGesture(gesture, null, null);
    }

    /** Replace the currently focused editable field's contents. */
    public boolean typeText(String text) {
        if (text == null) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo target = findFocusedEditable(root, 0);
        if (target == null) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private AccessibilityNodeInfo findFocusedEditable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 20) return null;
        if (node.isFocused() && node.isEditable() && node.isEnabled()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findFocusedEditable(node.getChild(i), depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    /** Submit the focused editable field on Android versions exposing IME_ENTER. */
    public boolean pressEnter() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo target = findFocusedEditable(root, 0);
        return target != null && target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
    }

    public void swipeRight() {
        Point size = getScreenSize();
        float y = size.y * 0.50f, centerX = size.x * 0.50f, distance = size.x * HORIZONTAL_DISTANCE;
        performSwipe(centerX - distance, y, centerX, y);
    }

    public void swipeLeft() {
        Point size = getScreenSize();
        float y = size.y * 0.50f, centerX = size.x * 0.50f, distance = size.x * HORIZONTAL_DISTANCE;
        performSwipe(centerX + distance, y, centerX, y);
    }

    public void swipeUp() {
        Point size = getScreenSize();
        float x = size.x * 0.50f, centerY = size.y * 0.50f, distance = size.y * VERTICAL_DISTANCE;
        performSwipe(x, centerY + distance, x, centerY);
    }

    public void swipeDown() {
        Point size = getScreenSize();
        float x = size.x * 0.50f, centerY = size.y * 0.50f, distance = size.y * VERTICAL_DISTANCE;
        performSwipe(x, centerY - distance, x, centerY);
    }

    public void moveFinger(float deltaX, float deltaY) {
        if (Math.abs(deltaX) < 0.0005f && Math.abs(deltaY) < 0.0005f) return;
        mainHandler.post(() -> {
            pendingFingerX = clamp(pendingFingerX + deltaX, -MAX_PENDING_X, MAX_PENDING_X);
            pendingFingerY = clamp(pendingFingerY + deltaY, -MAX_PENDING_Y, MAX_PENDING_Y);
            dispatchPendingFingerMovement();
        });
    }

    private void dispatchPendingFingerMovement() {
        if (fingerGestureRunning) return;
        if (Math.abs(pendingFingerX) < 0.002f && Math.abs(pendingFingerY) < 0.002f) return;

        float deltaX = pendingFingerX, deltaY = pendingFingerY;
        pendingFingerX = 0f;
        pendingFingerY = 0f;

        Point size = getScreenSize();
        float centerX = size.x * 0.50f, centerY = size.y * 0.50f;
        float screenDeltaX = clamp(deltaX * size.x * 1.8f, -size.x * 0.06f, size.x * 0.06f);
        float screenDeltaY = clamp(deltaY * size.y * 1.8f, -size.y * 0.06f, size.y * 0.06f);
        float endX = clamp(centerX + screenDeltaX, 5f, size.x - 5f);
        float endY = clamp(centerY + screenDeltaY, 5f, size.y - 5f);

        fingerGestureRunning = true;
        boolean accepted = performSwipe(centerX, centerY, endX, endY, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                fingerGestureRunning = false;
                dispatchPendingFingerMovement();
            }
            @Override public void onCancelled(GestureDescription gestureDescription) {
                fingerGestureRunning = false;
                dispatchPendingFingerMovement();
            }
        });
        if (!accepted) {
            fingerGestureRunning = false;
            dispatchPendingFingerMovement();
        }
    }

    private float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
