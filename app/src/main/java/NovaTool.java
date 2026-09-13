package com.aircontrol;

/** Generic contract for a capability NOVA can expose to its planner and executors. */
public interface NovaTool {
    /** Stable machine-readable tool identity. */
    String type();

    /** Human-readable capability description for planning. */
    String description();

    /** JSON object describing accepted arguments. */
    default String parameterSchema() {
        return "{\"type\":\"object\",\"properties\":{\"value\":{\"type\":\"string\"}}}";
    }

    /** Permission/risk classification used before execution. */
    default NovaPermissionPolicy.Risk risk() {
        return NovaPermissionPolicy.classify(type());
    }

    /** Whether the operation can normally be reversed without external side effects. */
    boolean reversible();

    /** Whether the tool can safely participate in an informational parallel batch. */
    default boolean supportsParallel() {
        return NovaActionSchema.canRunInParallel(type());
    }

    /** Generic execution boundary. Platform-specific executors may override this. */
    default NovaToolResult execute(NovaToolInput input) {
        return NovaToolResult.failure(type(), "executor_not_bound", "Tool executor is not bound", false);
    }
}
