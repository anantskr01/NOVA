package com.aircontrol;

/** Metadata for a capability NOVA can expose to its planner. */
public interface NovaTool {
    String type();
    String description();
    boolean reversible();

    /** Stable metadata used for planning/policy decisions without phrase matching. */
    default String category() { return NovaActionSchema.isInformational(type()) ? "information" : "android"; }
    default String inputSchema() { return "{\"type\":\"string\"}"; }
    default boolean readOnly() { return NovaActionSchema.isInformational(type()) || "none".equals(type()); }
    default boolean confirmationRequired() { return false; }
    default String capabilityRequirement() { return ""; }
    default long timeoutMillis() { return NovaAgentPolicy.MAX_TASK_MILLIS; }
}
