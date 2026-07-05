package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void basicPropertiesAndRequired() {
        JsonNode s = McpSchema.of(MAPPER)
                .str("name", "a name")
                .num("t", "a number")
                .intg("count", "an int")
                .bool("flag", "a flag")
                .required("name", "t")
                .build();

        assertEquals("object", s.get("type").asText());
        JsonNode props = s.get("properties");
        assertEquals("string", props.get("name").get("type").asText());
        assertEquals("a name", props.get("name").get("description").asText());
        assertEquals("number", props.get("t").get("type").asText());
        assertEquals("integer", props.get("count").get("type").asText());
        assertEquals("boolean", props.get("flag").get("type").asText());
        assertEquals(2, s.get("required").size());
        assertEquals("name", s.get("required").get(0).asText());
    }

    @Test
    void noRequiredNodeWhenNoneDeclared() {
        JsonNode s = McpSchema.of(MAPPER).str("a", "x").build();
        assertFalse(s.has("required"));
    }

    @Test
    void arrayShapes() {
        JsonNode s = McpSchema.of(MAPPER)
                .arr("nums", "number", "numbers")
                .intArr("ids", "ids")
                .strArray("names", "names")
                .vec3("pos", "position")
                .rgba("color", "color")
                .build();

        JsonNode props = s.get("properties");
        assertEquals("number", props.get("nums").get("items").get("type").asText());
        assertEquals("integer", props.get("ids").get("items").get("type").asText());
        assertEquals("string", props.get("names").get("items").get("type").asText());

        assertEquals(3, props.get("pos").get("minItems").asInt());
        assertEquals(3, props.get("pos").get("maxItems").asInt());
        assertEquals("number", props.get("pos").get("items").get("type").asText());

        assertEquals(4, props.get("color").get("minItems").asInt());
        assertEquals(4, props.get("color").get("maxItems").asInt());
        assertEquals("integer", props.get("color").get("items").get("type").asText());
    }

    @Test
    void enumStrEmitsEnumValues() {
        JsonNode s = McpSchema.of(MAPPER)
                .enumStr("action", "what to do", "play", "pause", "stop")
                .build();
        JsonNode def = s.get("properties").get("action");
        assertEquals("string", def.get("type").asText());
        assertTrue(def.has("enum"));
        assertEquals(3, def.get("enum").size());
        assertEquals("play", def.get("enum").get(0).asText());
    }
}
