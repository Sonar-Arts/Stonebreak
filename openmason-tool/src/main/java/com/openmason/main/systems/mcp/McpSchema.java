package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Shared fluent JSON Schema (draft-07) builder for MCP tool input schemas.
 *
 * <p>Union of the builders formerly duplicated across the per-domain
 * {@code *ToolDefinitions} classes. All property methods return {@code this}
 * for chaining; call {@link #build()} last.
 */
public final class McpSchema {

    private final ObjectMapper mapper;
    private final ObjectNode root;
    private final ObjectNode properties;
    private final ArrayNode required;

    private McpSchema(ObjectMapper mapper) {
        this.mapper = mapper;
        this.root = mapper.createObjectNode();
        this.properties = mapper.createObjectNode();
        this.required = mapper.createArrayNode();
        root.put("type", "object");
        root.set("properties", properties);
    }

    public static McpSchema of(ObjectMapper mapper) {
        return new McpSchema(mapper);
    }

    public McpSchema str(String name, String description) { return prop(name, "string", description); }

    public McpSchema num(String name, String description) { return prop(name, "number", description); }

    public McpSchema intg(String name, String description) { return prop(name, "integer", description); }

    public McpSchema bool(String name, String description) { return prop(name, "boolean", description); }

    /** String property constrained to an enum of allowed values. */
    public McpSchema enumStr(String name, String description, String... values) {
        ObjectNode def = mapper.createObjectNode();
        def.put("type", "string");
        def.put("description", description);
        ArrayNode allowed = mapper.createArrayNode();
        for (String v : values) allowed.add(v);
        def.set("enum", allowed);
        properties.set(name, def);
        return this;
    }

    /** Variable-length array with a given item type ("number", "integer", "string"). */
    public McpSchema arr(String name, String itemType, String description) {
        properties.set(name, arrNode(itemType, description));
        return this;
    }

    /** Variable-length integer array. */
    public McpSchema intArr(String name, String description) {
        return arr(name, "integer", description);
    }

    /** Variable-length string array. */
    public McpSchema strArray(String name, String description) {
        return arr(name, "string", description);
    }

    /** Fixed-length [x,y,z] number array. */
    public McpSchema vec3(String name, String description) {
        ObjectNode def = arrNode("number", description);
        def.put("minItems", 3);
        def.put("maxItems", 3);
        properties.set(name, def);
        return this;
    }

    /** Fixed-length [r,g,b,a] integer array. */
    public McpSchema rgba(String name, String description) {
        ObjectNode def = arrNode("integer", description);
        def.put("minItems", 4);
        def.put("maxItems", 4);
        properties.set(name, def);
        return this;
    }

    public McpSchema prop(String name, String type, String description) {
        ObjectNode def = mapper.createObjectNode();
        def.put("type", type);
        def.put("description", description);
        properties.set(name, def);
        return this;
    }

    public McpSchema required(String... names) {
        for (String n : names) required.add(n);
        return this;
    }

    public JsonNode build() {
        if (!required.isEmpty()) root.set("required", required);
        return root;
    }

    private ObjectNode arrNode(String itemType, String description) {
        ObjectNode def = mapper.createObjectNode();
        def.put("type", "array");
        def.put("description", description);
        ObjectNode items = mapper.createObjectNode();
        items.put("type", itemType);
        def.set("items", items);
        return def;
    }
}
