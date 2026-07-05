package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Terse mutation acknowledgement for MCP tool results.
 *
 * <p>Serializes as a flat JSON object, e.g. {@code {"ok":true,"part":"body","verts":8,"tris":12}}.
 * Used instead of echoing full entity views after mutations; callers that want
 * the full refreshed state pass {@code verbose:true} to the tool.
 */
public final class McpAck {

    private final LinkedHashMap<String, Object> fields = new LinkedHashMap<>();

    private McpAck() {
        fields.put("ok", true);
    }

    public static McpAck ok() {
        return new McpAck();
    }

    /** Add a field; null values are dropped. */
    public McpAck with(String key, Object value) {
        if (value != null) fields.put(key, value);
        return this;
    }

    @JsonValue
    public Map<String, Object> fields() {
        return fields;
    }
}
