package com.aircontrol;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Converts the raw accessibility tree into a compact semantic state for NOVA. */
public final class NovaUiStateBuilder {
    private static final int MAX_DEPTH = 18;
    private static final int MAX_ELEMENTS = 80;

    private NovaUiStateBuilder() {}

    public static NovaUiState build(AccessibilityNodeInfo root, String packageName) {
        List<NovaUiState.Element> elements = new ArrayList<>();
        if (root != null) collect(root, elements, 0);

        String focused = "";
        String title = "";
        for (NovaUiState.Element e : elements) {
            if (e.focused && !e.label.isEmpty()) focused = e.label;
            if (title.isEmpty() && isLikelyTitle(e)) title = e.label;
        }
        return new NovaUiState(packageName, title, focused, elements);
    }

    private static void collect(AccessibilityNodeInfo node, List<NovaUiState.Element> out, int depth) {
        if (node == null || depth > MAX_DEPTH || out.size() >= MAX_ELEMENTS) return;

        String label = labelOf(node);
        if (!label.isEmpty() || node.isClickable() || node.isEditable() || node.isFocusable()) {
            Rect r = new Rect();
            node.getBoundsInScreen(r);
            out.add(new NovaUiState.Element(
                    label,
                    roleOf(node, label),
                    String.valueOf(node.getClassName()),
                    node.isClickable(),
                    node.isEditable(),
                    node.isFocusable(),
                    node.isFocused(),
                    node.isEnabled(),
                    r.left, r.top, r.right, r.bottom
            ));
        }
        for (int i = 0; i < node.getChildCount(); i++) collect(node.getChild(i), out, depth + 1);
    }

    private static String labelOf(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        if (text != null && !text.toString().trim().isEmpty()) return text.toString().trim();
        CharSequence desc = node.getContentDescription();
        return desc == null ? "" : desc.toString().trim();
    }

    private static String roleOf(AccessibilityNodeInfo node, String label) {
        String cls = String.valueOf(node.getClassName()).toLowerCase(Locale.ROOT);
        String lower = label.toLowerCase(Locale.ROOT);
        if (node.isEditable()) return "EDITABLE";
        if (lower.equals("search") || lower.contains("search")) return "SEARCH_CONTROL";
        if (lower.equals("back") || lower.equals("home") || lower.equals("settings")) return "NAVIGATION";
        if (cls.contains("button") || node.isClickable()) return "BUTTON";
        if (cls.contains("image")) return "IMAGE";
        if (cls.contains("text")) return "TEXT";
        return "UNKNOWN";
    }

    private static boolean isLikelyTitle(NovaUiState.Element e) {
        return !e.label.isEmpty() && !e.clickable && !e.editable && e.top < 350;
    }
}
