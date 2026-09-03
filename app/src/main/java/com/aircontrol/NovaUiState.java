package com.aircontrol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Structured representation of the currently observable accessibility UI. */
public final class NovaUiState {
    public final String packageName;
    public final String screenTitle;
    public final String focusedElement;
    public final List<Element> elements;

    public NovaUiState(String packageName, String screenTitle, String focusedElement, List<Element> elements) {
        this.packageName = packageName == null ? "" : packageName;
        this.screenTitle = screenTitle == null ? "" : screenTitle;
        this.focusedElement = focusedElement == null ? "" : focusedElement;
        this.elements = Collections.unmodifiableList(new ArrayList<>(elements));
    }

    public String toPromptString() {
        StringBuilder out = new StringBuilder();
        out.append("package=").append(packageName).append('\n');
        if (!screenTitle.isEmpty()) out.append("screen_hint=").append(screenTitle).append('\n');
        if (!focusedElement.isEmpty()) out.append("focused=").append(focusedElement).append('\n');
        out.append("elements:\n");
        for (int i = 0; i < elements.size(); i++) {
            out.append(i + 1).append(". ").append(elements.get(i).toPromptString()).append('\n');
        }
        return out.toString();
    }

    public static final class Element {
        public final String label;
        public final String role;
        public final String className;
        public final boolean clickable;
        public final boolean editable;
        public final boolean focusable;
        public final boolean focused;
        public final boolean enabled;
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public Element(String label, String role, String className, boolean clickable,
                       boolean editable, boolean focusable, boolean focused, boolean enabled,
                       int left, int top, int right, int bottom) {
            this.label = label == null ? "" : label;
            this.role = role == null ? "UNKNOWN" : role;
            this.className = className == null ? "" : className;
            this.clickable = clickable;
            this.editable = editable;
            this.focusable = focusable;
            this.focused = focused;
            this.enabled = enabled;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public String toPromptString() {
            return "label=\"" + label + "\" role=" + role
                    + " clickable=" + clickable
                    + " editable=" + editable
                    + " focused=" + focused
                    + " enabled=" + enabled
                    + " bounds=" + left + "," + top + "-" + right + "," + bottom;
        }
    }
}
