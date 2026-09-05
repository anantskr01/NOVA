package com.aircontrol;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class NovaActionSchemaTest {
    @Test public void rejectsUnknownAction() throws Exception {
        assertTrue(NovaActionSchema.validate(new JSONObject().put("type", "delete_everything")).startsWith("unknown_action"));
    }

    @Test public void rejectsOversizedValue() throws Exception {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < NovaActionSchema.MAX_ACTION_VALUE_CHARS + 1; i++) value.append('x');
        assertEquals("value_too_long:open_app", NovaActionSchema.validate(new JSONObject().put("type", "open_app").put("value", value.toString())));
    }

    @Test public void memoryWriteIsMutationAndNotParallelSafe() {
        assertTrue(NovaActionSchema.isMutation("remember"));
        assertFalse(NovaActionSchema.isInformational("remember"));
        assertFalse(NovaActionSchema.canRunInParallel("remember"));
    }

    @Test public void rejectsParallelMutation() throws Exception {
        JSONArray steps = new JSONArray().put(new JSONObject().put("type", "home"));
        String error = NovaActionSchema.validate(new JSONObject().put("type", "parallel").put("value", steps.toString()));
        assertTrue(error.startsWith("parallel_invalid_step:0:parallel_mutation_forbidden"));
    }

    @Test public void validatesRememberPayload() throws Exception {
        assertEquals("remember_invalid_json", NovaActionSchema.validate(new JSONObject().put("type", "remember").put("value", "not-json")));
        assertEquals("", NovaActionSchema.validate(new JSONObject().put("type", "remember").put("value", "{\"key\":\"preference\",\"value\":\"dark mode\"}")));
    }
}
