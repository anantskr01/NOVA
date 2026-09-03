package com.aircontrol;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "NOVA ACCESSIBILITY SERVICE CONNECTED");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "NOVA ACCESSIBILITY INTERRUPTED");
    }

    public static GestureAccessibilityService getInstance() {
        return instance;
    }

    /** Best-effort package identity of the current accessibility window. */
    public String getActivePackageName() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return "";
        return root.getPackageName().toString();
    }

    public String getVisibleTextSummary() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return "I cannot read the current screen. Accessibility window content may be unavailable.";
        }
        StringBuilder out = new StringBuilder();
        collectText(root, out, 0);
        if (out.length() == 0) return "I cannot find readable text on the current screen.";
        return out.length() > 1800 ? out.substring(0, 1800) : out.toString();
    }

    public String getUiSnapshot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "No accessibility window is available.";
        StringBuilder out = new StringBuilder();
        out.append("package=").append(getActivePackageName()).append('\n');
        collectUiSnapshot(root, out, 0);
        if (out.length() == 0) return "No readable UI elements found.";
        return out.length() > 6000 ? out.substring(0, 6000) : out.toString();
    }

    private void collectUiSnapshot(AccessibilityNodeInfo node, StringBuilder out, int depth) {
        if (node == null || depth > 18 || out.length() > 6000) return;

        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String label = text != null && text.length() > 0
                ? text.toString().trim()
                : (desc != null ? desc.toString().trim() : "");

        if (!label.isEmpty() || node.isClickable() || node.isEditable() || node.isFocusable()) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            out.append("• ")
                    .append(label.isEmpty() ? "[unlabeled]" : label)
                    .append(" | class=").append(node.getClassName())
                    .append(" | clickable=").append(node.isClickable())
                    .append(" | enabled=").append(node.isEnabled())
                    .append(" | editable=").append(node.isEditable())
                    .append(" | focused=").append(node.isFocused())
                    .append(" | focusable=").append(node.isFocusable())
                    .append(" | bounds=")
                    .append(bounds.left).append(',').append(bounds.top)
                    .append('-').append(bounds.right).append(',').append(bounds.bottom)
                    .append('\n');
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            collectUiSnapshot(node.getChild(i), out, depth + 1);
        }
    }

    public boolean clickVisibleIndex(int requestedIndex) {
        if (requestedIndex < 1) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        ArrayList<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collectClickableNodes(root, nodes, 0);
        if (requestedIndex > nodes.size()) return false;

        AccessibilityNodeInfo target = nodes.get(requestedIndex - 1);
        Log.d(TAG, "CLICK INDEX TARGET: " + describeNode(target));
        return clickNodeSafely(target);
    }

    private void collectClickableNodes(AccessibilityNodeInfo node,
                                        ArrayList<AccessibilityNodeInfo> out,
                                        int depth) {
        if (node == null || depth > 18 || out.size() >= 50) return;

        if (isUsableClickableNode(node)) out.add(node);

        for (int i = 0; i < node.getChildCount(); i++) {
            collectClickableNodes(node.getChild(i), out, depth + 1);
        }
    }

    /** Semantic click: exact text/description first, then bounded partial matching. */
    public boolean clickText(String requested) {
        if (requested == null || requested.trim().isEmpty()) return false;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Log.w(TAG, "CLICK TEXT: no accessibility root");
            return false;
        }

        String query = normalize(requested);
        List<NodeCandidate> candidates = new ArrayList<>();
        collectMatchingNodes(root, query, candidates, 0);

        if (candidates.isEmpty()) {
            Log.w(TAG, "CLICK TEXT: no matching node for [" + requested + "]");
            return false;
        }

        NodeCandidate best = null;
        for (NodeCandidate candidate : candidates) {
            if (best == null || candidate.score > best.score) best = candidate;
        }

        if (best == null || best.node == null) return false;

        Log.d(TAG, "CLICK SELECTED: " + describeNode(best.node) + " | score=" + best.score);
        return clickNodeSafely(best.node);
    }

    private void collectMatchingNodes(AccessibilityNodeInfo node,
                                      String requested,
                                      List<NodeCandidate> out,
                                      int depth) {
        if (node == null || depth > 18) return;

        if (node.isVisibleToUser() && node.isEnabled()) {
            int score = scoreNode(node, requested);
            if (score > 0) out.add(new NodeCandidate(node, score));
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            collectMatchingNodes(node.getChild(i), requested, out, depth + 1);
        }
    }

    private int scoreNode(AccessibilityNodeInfo node, String requested) {
        if (node == null || !node.isVisibleToUser() || !node.isEnabled()) return -1;

        CharSequence textValue = node.getText();
        CharSequence descValue = node.getContentDescription();
        String text = normalize(textValue == null ? "" : textValue.toString());
        String desc = normalize(descValue == null ? "" : descValue.toString());

        if (text.isEmpty() && desc.isEmpty()) return -1;

        int score = 0;
        if (text.equals(requested)) score += 1000;
        if (desc.equals(requested)) score += 950;
        if (!text.isEmpty() && text.equalsIgnoreCase(requested)) score += 100;
        if (!desc.isEmpty() && desc.equalsIgnoreCase(requested)) score += 90;
        if (text.contains(requested)) score += 300;
        if (desc.contains(requested)) score += 280;
        if (node.isClickable()) score += 250;
        if (node.isFocusable()) score += 20;
        if (node.isEditable()) score += 10;

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        long area = (long) Math.max(0, bounds.width()) * Math.max(0, bounds.height());
        if (area > 1_500_000L) score -= 180;
        else if (area > 900_000L) score -= 100;

        return Math.max(score, -1);
    }

    private boolean clickNodeSafely(AccessibilityNodeInfo target) {
        if (target == null || !target.isVisibleToUser() || !target.isEnabled()) return false;

        try {
            if (target.isClickable()
                    && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d(TAG, "CLICK SUCCESS: ACTION_CLICK");
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "ACTION_CLICK ERROR", e);
        }

        // Only use a nearby logical parent. Never blindly click a giant ancestor.
        AccessibilityNodeInfo parent = target.getParent();
        int depth = 0;
        while (parent != null && depth++ < 4) {
            if (isSafeParentClickTarget(target, parent)) {
                try {
                    if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.d(TAG, "CLICK SUCCESS: PARENT ACTION_CLICK");
                        return true;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "PARENT CLICK ERROR", e);
                }
            }
            parent = parent.getParent();
        }

        // Android recommends dispatchGesture as a fallback when ACTION_CLICK is unsupported.
        return tapNodeBounds(target);
    }

    private boolean isSafeParentClickTarget(AccessibilityNodeInfo child,
                                            AccessibilityNodeInfo parent) {
        if (parent == null || !parent.isVisibleToUser()
                || !parent.isEnabled() || !parent.isClickable()) return false;

        Rect childBounds = new Rect();
        Rect parentBounds = new Rect();
        child.getBoundsInScreen(childBounds);
        parent.getBoundsInScreen(parentBounds);
        if (childBounds.isEmpty() || parentBounds.isEmpty()) return false;

        long parentArea = (long) parentBounds.width() * Math.max(0, parentBounds.height());
        long childArea = (long) childBounds.width() * Math.max(0, childBounds.height());
        if (parentArea <= 0 || childArea <= 0) return false;

        return parentArea <= childArea * 8L;
    }

    private boolean tapNodeBounds(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.isEmpty()) return false;

        Point screen = getScreenSize();
        float x = clamp(bounds.centerX(), 2f, screen.x - 2f);
        float y = clamp(bounds.centerY(), 2f, screen.y - 2f);

        Log.d(TAG, "TAP FALLBACK: x=" + x + " y=" + y + " bounds=" + bounds);

        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(
                        path, 0, Math.max(40L, ViewConfiguration.getTapTimeout()));
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                Log.d(TAG, "TAP FALLBACK COMPLETED");
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                Log.d(TAG, "TAP FALLBACK CANCELLED");
            }
        }, null);

        Log.d(TAG, "TAP FALLBACK ACCEPTED = " + accepted);
        return accepted;
    }

    public boolean typeText(String text) {
        if (text == null) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        AccessibilityNodeInfo target = findBestEditableNode(root, 0, true);
        if (target == null) target = findBestEditableNode(root, 0, false);
        if (target == null || !target.isEnabled()) return false;

        try {
            if (!target.isFocused() && target.isFocusable()) {
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            }

            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            boolean changed = target.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            if (!changed) return false;

            CharSequence actual = target.getText();
            Log.d(TAG, "TEXT INPUT APPLIED • chars=" + text.length()
                    + " • verified=" + (actual != null && actual.toString().equals(text)));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "TEXT INPUT ERROR", e);
            return false;
        }
    }

    private AccessibilityNodeInfo findBestEditableNode(AccessibilityNodeInfo node,
                                                         int depth,
                                                         boolean focusedOnly) {
        if (node == null || depth > 18) return null;

        AccessibilityNodeInfo best = null;
        int bestScore = -1;
        if (node.isVisibleToUser() && node.isEnabled() && node.isEditable()
                && (!focusedOnly || node.isFocused())) {
            best = node;
            bestScore = (node.isFocused() ? 1000 : 0) + (node.isFocusable() ? 20 : 0);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo candidate = findBestEditableNode(
                    node.getChild(i), depth + 1, focusedOnly);
            if (candidate == null) continue;
            int score = (candidate.isFocused() ? 1000 : 0)
                    + (candidate.isFocusable() ? 20 : 0);
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    /** Submits the focused editable field through the platform IME action when available. */
    public boolean pressEnter() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        AccessibilityNodeInfo focused = findFocusedEditable(root, 0);
        if (focused != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (focused.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())) {
                    Log.d(TAG, "IME ENTER DISPATCHED");
                    return true;
                }
            } catch (Exception e) {
                Log.e(TAG, "IME ENTER ERROR", e);
            }
        }

        String[] submitLabels = {"search", "go", "enter", "done"};
        for (String label : submitLabels) {
            AccessibilityNodeInfo target = findBestTextNode(root, label, 0);
            if (target != null && clickNodeSafely(target)) {
                Log.d(TAG, "SUBMIT FALLBACK CLICK • " + label);
                return true;
            }
        }
        return false;
    }

    private AccessibilityNodeInfo findFocusedEditable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 18) return null;
        if (node.isVisibleToUser() && node.isEnabled() && node.isEditable() && node.isFocused()) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findFocusedEditable(node.getChild(i), depth + 1);
            if (result != null) return result;
        }
        return null;
    }

    private AccessibilityNodeInfo findBestTextNode(AccessibilityNodeInfo node,
                                                    String requested,
                                                    int depth) {
        if (node == null || depth > 18) return null;
        String query = normalize(requested);
        AccessibilityNodeInfo best = null;
        int bestScore = -1;

        int selfScore = scoreNode(node, query);
        if (selfScore > bestScore) {
            bestScore = selfScore;
            best = node;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo candidate = findBestTextNode(
                    node.getChild(i), requested, depth + 1);
            int score = scoreNode(candidate, query);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
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
        for (int i = 0; i < node.getChildCount(); i++) {
            collectText(node.getChild(i), out, depth + 1);
        }
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

    private boolean performSwipe(float startX, float startY, float endX, float endY,
                                 GestureResultCallback callback) {
        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, SWIPE_DURATION);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        boolean accepted = dispatchGesture(gesture, callback, null);
        Log.d(TAG, "dispatchGesture accepted = " + accepted);
        return accepted;
    }

    private void performSwipe(float startX, float startY, float endX, float endY) {
        performSwipe(startX, startY, endX, endY, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                Log.d(TAG, "GESTURE COMPLETED");
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                Log.d(TAG, "GESTURE CANCELLED");
            }
        });
    }

    public void swipeRight() {
        Point size = getScreenSize();
        float y = size.y * 0.50f, centerX = size.x * 0.50f;
        float distance = size.x * HORIZONTAL_DISTANCE;
        performSwipe(centerX - distance, y, centerX, y);
    }

    public void swipeLeft() {
        Point size = getScreenSize();
        float y = size.y * 0.50f, centerX = size.x * 0.50f;
        float distance = size.x * HORIZONTAL_DISTANCE;
        performSwipe(centerX + distance, y, centerX, y);
    }

    public void swipeUp() {
        Point size = getScreenSize();
        float x = size.x * 0.50f, centerY = size.y * 0.50f;
        float distance = size.y * VERTICAL_DISTANCE;
        performSwipe(x, centerY + distance, x, centerY);
    }

    public void swipeDown() {
        Point size = getScreenSize();
        float x = size.x * 0.50f, centerY = size.y * 0.50f;
        float distance = size.y * VERTICAL_DISTANCE;
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

        float deltaX = pendingFingerX;
        float deltaY = pendingFingerY;
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
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                fingerGestureRunning = false;
                dispatchPendingFingerMovement();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                fingerGestureRunning = false;
                dispatchPendingFingerMovement();
            }
        });

        if (!accepted) {
            fingerGestureRunning = false;
            pendingFingerX = clamp(pendingFingerX + deltaX, -MAX_PENDING_X, MAX_PENDING_X);
            pendingFingerY = clamp(pendingFingerY + deltaY, -MAX_PENDING_Y, MAX_PENDING_Y);
            mainHandler.postDelayed(this::dispatchPendingFingerMovement, 40L);
        }
    }

    private boolean isUsableClickableNode(AccessibilityNodeInfo node) {
        if (node == null || !node.isVisibleToUser() || !node.isEnabled() || !node.isClickable()) return false;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        return (text != null && text.length() > 0) || (desc != null && desc.length() > 0);
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private String describeNode(AccessibilityNodeInfo node) {
        if (node == null) return "[null]";
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        return "text=\"" + (text == null ? "" : text)
                + "\" desc=\"" + (desc == null ? "" : desc)
                + "\" class=" + node.getClassName()
                + " clickable=" + node.isClickable()
                + " enabled=" + node.isEnabled()
                + " bounds=" + bounds;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static class NodeCandidate {
        final AccessibilityNodeInfo node;
        final int score;

        NodeCandidate(AccessibilityNodeInfo node, int score) {
            this.node = node;
            this.score = score;
        }
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        pendingFingerX = 0f;
        pendingFingerY = 0f;
        fingerGestureRunning = false;
        if (instance == this) instance = null;
        Log.d(TAG, "NOVA ACCESSIBILITY SERVICE DESTROYED");
        super.onDestroy();
    }
}
