package com.aircontrol;

import org.json.JSONObject;

/** Structured result returned by a NOVA tool execution boundary. */
public final class NovaToolResult {
    public final boolean success;
    public final String toolType;
    public final String message;
    public final String errorCode;
    public final boolean retryable;
    public final boolean verified;

    private NovaToolResult(boolean ok, String type, String msg, String error, boolean retry, boolean verifiedResult) {
        success = ok;
        toolType = type == null ? "" : type;
        message = msg == null ? "" : msg;
        errorCode = error == null ? "" : error;
        retryable = retry;
        verified = verifiedResult;
    }

    public static NovaToolResult success(String toolType, String message, boolean verified) {
        return new NovaToolResult(true, toolType, message, "", false, verified);
    }

    public static NovaToolResult failure(String toolType, String errorCode, String message, boolean retryable) {
        return new NovaToolResult(false, toolType, message, errorCode, retryable, false);
    }

    public JSONObject toJson() {
        return new JSONObject()
                .put("ok", success)
                .put("tool", toolType)
                .put("message", message)
                .put("error", errorCode)
                .put("retryable", retryable)
                .put("verified", verified);
    }

    @Override
    public String toString() {
        return toJson().toString();
    }
}
