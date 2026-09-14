package com.aircontrol;

/** Generic contract for a capability NOVA can expose to its planner and executors. */
public interface NovaTool {
    String type();
    String description();

    default String parameterSchema() {
        return "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}}}";
    }

    default NovaPermissionPolicy.Risk risk() {
        return NovaPermissionPolicy.classify(type());
    }

    boolean reversible();

    default boolean supportsParallel() {
        return NovaActionSchema.canRunInParallel(type());
    }

    default NovaToolResult execute(NovaToolInput input) {
        return NovaToolResult.failure(type(), "executor_not_bound", "Tool executor is not bound", false);
    }
}
