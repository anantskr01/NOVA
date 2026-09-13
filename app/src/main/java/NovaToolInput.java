package com.aircontrol;

import org.json.JSONObject;

/** Immutable input envelope passed across NOVA's generic tool boundary. */
public final class NovaToolInput {
    public final String toolType;
    public final String value;
    public final JSONObject arguments;

    public NovaToolInput(String type, String rawValue, JSONObject args) {
        toolType = type == null ? "" : type.trim().toLowerCase();
        value = rawValue == null ? "" : rawValue;
        arguments = args == null ? new JSONObject() : args;
    }
}
