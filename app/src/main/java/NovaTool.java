package com.aircontrol;

/** Metadata for a capability NOVA can expose to its planner. */
public interface NovaTool {
    String type();
    String description();
    boolean reversible();
}
