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

    @Test public void rejectsMissingAndNonStringType() throws Exception {
        assertEquals("type_missing", NovaActionSchema.validate(new JSONObject()));
        assertEquals("type_not_string", NovaActionSchema.validate(new JSONObject().put("type", 123)));
    }

    @Test public void rejectsNonStringValue() throws Exception {
        assertEquals("value_not_string",
                NovaActionSchema.validate(new JSONObject().put("type", "web_search").put("value", 123)));
    }

    @Test public void rejectsUnexpectedFields() throws Exception {
        assertEquals("unexpected_field:reason",
                NovaActionSchema.validate(new JSONObject().put("type", "home").put("reason", "test")));
    }

    @Test public void acceptsCanonicalActions() throws Exception {
        assertEquals("", NovaActionSchema.validate(new JSONObject().put("type", "home").put("value", "")));
        assertEquals("", NovaActionSchema.validate(new JSONObject().put("type", "web_search").put("value", "android accessibility")));
        assertEquals("", NovaActionSchema.validate(new JSONObject().put("type", "click_index").put("value", "1")));
    }

    @Test public void strictShapeAppliesInsideParallel() throws Exception {
        JSONArray steps = new JSONArray()
                .put(new JSONObject().put("type", "web_search").put("value", "test").put("extra", "blocked"));
        String result = NovaActionSchema.validate(new JSONObject()
                .put("type", "parallel").put("value", steps.toString()));
        assertEquals("parallel_invalid_step:0:unexpected_field:extra", result);
    }
}
