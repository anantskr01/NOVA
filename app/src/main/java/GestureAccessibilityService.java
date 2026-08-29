package com.aircontrol;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Point;
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

    @Override protected void onServiceConnected() { super.onServiceConnected(); instance = this; Log.d(TAG, "NOVA ACCESSIBILITY SERVICE CONNECTED"); }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { Log.d(TAG, "NOVA ACCESSIBILITY INTERRUPTED"); }
    public static GestureAccessibilityService getInstance() { return instance; }

    public String getVisibleTextSummary() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "I cannot read the current screen. Accessibility window content may be unavailable.";
        StringBuilder out = new StringBuilder(); collectText(root, out, 0);
        if (out.length() == 0) return "I cannot find readable text on the current screen.";
        return out.length() > 1800 ? out.substring(0, 1800) : out.toString();
    }

    public String getUiSnapshot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "No accessibility window is available.";
        StringBuilder out = new StringBuilder(); collectUiSnapshot(root, out, 0);
        if (out.length() == 0) return "No readable UI elements found.";
        return out.length() > 10000 ? out.substring(0, 10000) : out.toString();
    }

    private void collectUiSnapshot(AccessibilityNodeInfo node, StringBuilder out, int depth) {
        if (node == null || depth > 20 || out.length() > 10000) return;
        CharSequence text = node.getText(); CharSequence desc = node.getContentDescription(); CharSequence hint = node.getHintText();
        String label = text != null && text.length() > 0 ? text.toString().trim() : (desc != null ? desc.toString().trim() : "");
        String hintText = hint == null ? "" : hint.toString().trim();
        if (!label.isEmpty() || !hintText.isEmpty() || node.isClickable() || node.isEditable()) {
            android.graphics.Rect bounds = new android.graphics.Rect(); node.getBoundsInScreen(bounds);
            out.append("• ").append(label.isEmpty() ? "[unlabeled]" : label)
                    .append(hintText.isEmpty() ? "" : " | hint=" + hintText)
                    .append(" | class=").append(node.getClassName())
                    .append(" | clickable=").append(node.isClickable())
                    .append(" | editable=").append(node.isEditable())
                    .append(" | enabled=").append(node.isEnabled())
                    .append(" | bounds=").append(bounds.left).append(',').append(bounds.top).append('-').append(bounds.right).append(',').append(bounds.bottom).append('\n');
        }
        for (int i = 0; i < node.getChildCount(); i++) collectUiSnapshot(node.getChild(i), out, depth + 1);
    }

    public boolean clickVisibleIndex(int requestedIndex) {
        if (requestedIndex < 1) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow(); if (root == null) return false;
        java.util.ArrayList<AccessibilityNodeInfo> nodes = new java.util.ArrayList<>(); collectClickableNodes(root, nodes, 0);
        if (requestedIndex > nodes.size()) return false;
        return clickNodeOrParent(nodes.get(requestedIndex - 1));
    }

    private void collectClickableNodes(AccessibilityNodeInfo node, java.util.ArrayList<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 20 || out.size() >= 80) return;
        CharSequence text = node.getText(); CharSequence desc = node.getContentDescription();
        if (node.isClickable() && ((text != null && text.length() > 0) || (desc != null && desc.length() > 0))) out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) collectClickableNodes(node.getChild(i), out, depth + 1);
    }

    public boolean clickText(String requested) {
        if (requested == null || requested.trim().isEmpty()) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow(); if (root == null) return false;
        AccessibilityNodeInfo target = findTextNode(root, requested.trim().toLowerCase());
        return target != null && clickNodeOrParent(target);
    }

    private AccessibilityNodeInfo findTextNode(AccessibilityNodeInfo node, String requested) {
        if (node == null) return null;
        CharSequence text = node.getText(); CharSequence desc = node.getContentDescription(); CharSequence hint = node.getHintText();
        String value = text != null ? text.toString().trim().toLowerCase() : "";
        String description = desc != null ? desc.toString().trim().toLowerCase() : "";
        String hintText = hint != null ? hint.toString().trim().toLowerCase() : "";
        if ((!value.isEmpty() && (value.equals(requested) || value.contains(requested))) ||
                (!description.isEmpty() && (description.equals(requested) || description.contains(requested))) ||
                (!hintText.isEmpty() && (hintText.equals(requested) || hintText.contains(requested)))) return node;
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo result = findTextNode(node.getChild(i), requested); if (result != null) return result; }
        return null;
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo target) {
        try {
            if (target.isEnabled() && target.isClickable() && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            AccessibilityNodeInfo parent = target.getParent();
            while (parent != null) { if (parent.isEnabled() && parent.isClickable() && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true; parent = parent.getParent(); }
        } catch (Exception e) { Log.e(TAG, "CLICK ERROR", e); }
        return false;
    }

    /** Sets text on the best matching editable accessibility node. */
    public boolean typeText(String requested, String text) {
        if (text == null) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow(); if (root == null) return false;
        AccessibilityNodeInfo target = requested == null || requested.trim().isEmpty() ? findFirstEditable(root, 0) : findTextNode(root, requested.trim().toLowerCase());
        if (target != null && !target.isEditable()) target = findEditableAncestor(target);
        if (target == null) target = findFirstEditable(root, 0);
        if (target == null || !target.isEnabled()) return false;
        try {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            Bundle args = new Bundle(); args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        } catch (Exception e) { Log.e(TAG, "TYPE TEXT ERROR", e); return false; }
    }

    private AccessibilityNodeInfo findFirstEditable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 20) return null;
        if (node.isEditable() && node.isEnabled()) return node;
        for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo result = findFirstEditable(node.getChild(i), depth + 1); if (result != null) return result; }
        return null;
    }

    private AccessibilityNodeInfo findEditableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        while (current != null) { if (current.isEditable()) return current; current = current.getParent(); }
        return null;
    }

    private void collectText(AccessibilityNodeInfo node, StringBuilder out, int depth) {
        if (node == null || depth > 25 || out.length() > 1800) return;
        CharSequence text = node.getText(); CharSequence desc = node.getContentDescription();
        if (text != null && text.length() > 0) { String value = text.toString().trim(); if (!value.isEmpty()) out.append(value).append('\n'); }
        else if (desc != null && desc.length() > 0) { String value = desc.toString().trim(); if (!value.isEmpty()) out.append(value).append('\n'); }
        for (int i = 0; i < node.getChildCount(); i++) collectText(node.getChild(i), out, depth + 1);
    }

    public boolean performGlobalActionPublic(int action) { try { return performGlobalAction(action); } catch (Exception e) { Log.e(TAG, "GLOBAL ACTION ERROR", e); return false; } }
    private Point getScreenSize() { Point size = new Point(); try { size.x = getResources().getDisplayMetrics().widthPixels; size.y = getResources().getDisplayMetrics().heightPixels; } catch (Exception e) { Log.e(TAG, "SCREEN SIZE ERROR", e); } if (size.x <= 0) size.x = 1080; if (size.y <= 0) size.y = 1920; return size; }

    private boolean performSwipe(float startX, float startY, float endX, float endY, GestureResultCallback callback) {
        Path path = new Path(); path.moveTo(startX, startY); path.lineTo(endX, endY);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, SWIPE_DURATION);
        return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), callback, null);
    }
    private void performSwipe(float startX, float startY, float endX, float endY) { performSwipe(startX, startY, endX, endY, new GestureResultCallback() {}); }
    public void swipeRight() { Point size = getScreenSize(); float y=size.y*.5f,c=size.x*.5f,d=size.x*HORIZONTAL_DISTANCE; performSwipe(c-d,y,c,y); }
    public void swipeLeft() { Point size = getScreenSize(); float y=size.y*.5f,c=size.x*.5f,d=size.x*HORIZONTAL_DISTANCE; performSwipe(c+d,y,c,y); }
    public void swipeUp() { Point size = getScreenSize(); float x=size.x*.5f,c=size.y*.5f,d=size.y*VERTICAL_DISTANCE; performSwipe(x,c+d,x,c); }
    public void swipeDown() { Point size = getScreenSize(); float x=size.x*.5f,c=size.y*.5f,d=size.y*VERTICAL_DISTANCE; performSwipe(x,c-d,x,c); }

    public void moveFinger(float deltaX, float deltaY) {
        if (Math.abs(deltaX) < 0.0005f && Math.abs(deltaY) < 0.0005f) return;
        mainHandler.post(() -> { pendingFingerX=clamp(pendingFingerX+deltaX,-MAX_PENDING_X,MAX_PENDING_X); pendingFingerY=clamp(pendingFingerY+deltaY,-MAX_PENDING_Y,MAX_PENDING_Y); dispatchPendingFingerMovement(); });
    }
    private void dispatchPendingFingerMovement() {
        if (fingerGestureRunning || (Math.abs(pendingFingerX)<0.002f && Math.abs(pendingFingerY)<0.002f)) return;
        float dx=pendingFingerX,dy=pendingFingerY; pendingFingerX=0; pendingFingerY=0; Point size=getScreenSize(); float cx=size.x*.5f,cy=size.y*.5f;
        float sx=clamp(dx*size.x*1.8f,-size.x*.06f,size.x*.06f), sy=clamp(dy*size.y*1.8f,-size.y*.06f,size.y*.06f);
        fingerGestureRunning=true; boolean accepted=performSwipe(cx,cy,clamp(cx+sx,5,size.x-5),clamp(cy+sy,5,size.y-5),new GestureResultCallback(){
            @Override public void onCompleted(GestureDescription g){fingerGestureRunning=false;dispatchPendingFingerMovement();}
            @Override public void onCancelled(GestureDescription g){fingerGestureRunning=false;dispatchPendingFingerMovement();}
        });
        if(!accepted){fingerGestureRunning=false;pendingFingerX=clamp(pendingFingerX+dx,-MAX_PENDING_X,MAX_PENDING_X);pendingFingerY=clamp(pendingFingerY+dy,-MAX_PENDING_Y,MAX_PENDING_Y);}
    }
    private float clamp(float value,float min,float max){return Math.max(min,Math.min(max,value));}
    @Override public void onDestroy(){mainHandler.removeCallbacksAndMessages(null);pendingFingerX=0;pendingFingerY=0;fingerGestureRunning=false;if(instance==this)instance=null;super.onDestroy();}
}
