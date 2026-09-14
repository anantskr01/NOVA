package com.aircontrol;

/** Explicit approval boundary for tools that can mutate or execute on the PC. */
public interface NovaPermissionGate {
    /** Returns true only when the user has explicitly approved this exact tool invocation. */
    boolean approve(String toolType, String arguments);

    /** Safe default: confirmation-required operations are denied until a real UI approval exists. */
    NovaPermissionGate DENY_BY_DEFAULT = (toolType, arguments) -> false;
}
