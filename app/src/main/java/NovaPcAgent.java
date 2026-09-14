package com.aircontrol;

/**
 * Platform-neutral boundary for NOVA's PC execution agent.
 *
 * The Android app owns the contract and policy; a future trusted PC companion
 * supplies the actual Windows/Linux/macOS implementation. Keeping this boundary
 * explicit prevents the Android agent from pretending that PC actions are available.
 */
public interface NovaPcAgent {
    /** Returns true only when a trusted PC companion is currently connected. */
    boolean isConnected();

    /** Executes one already-authorized PC operation. */
    NovaToolResult execute(NovaToolInput input);

    /** Returns a bounded observation of the current PC state. */
    String observe();

    /** Stops/invalidates the current PC session. */
    default void disconnect() { }
}
