package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpArgsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void reqStringRejectsMissingBlankAndNonText() {
        JsonNode args = json("{\"a\":\"x\",\"b\":\"\",\"c\":5}");
        assertEquals("x", McpArgs.reqString(args, "a"));
        assertThrows(IllegalArgumentException.class, () -> McpArgs.reqString(args, "b"));
        assertThrows(IllegalArgumentException.class, () -> McpArgs.reqString(args, "c"));
        assertThrows(IllegalArgumentException.class, () -> McpArgs.reqString(args, "missing"));
    }

    @Test
    void optStringReturnsNullForAbsentBlankOrNonText() {
        JsonNode args = json("{\"a\":\"x\",\"b\":\"\",\"c\":5}");
        assertEquals("x", McpArgs.optString(args, "a"));
        assertNull(McpArgs.optString(args, "b"));
        assertNull(McpArgs.optString(args, "c"));
        assertNull(McpArgs.optString(args, "missing"));
    }

    @Test
    void numericParsers() {
        JsonNode args = json("{\"f\":1.5,\"i\":7,\"s\":\"nope\"}");
        assertEquals(1.5f, McpArgs.reqFloat(args, "f"));
        assertEquals(7, McpArgs.reqInt(args, "i"));
        assertEquals(1.5f, McpArgs.optFloatBoxed(args, "f"));
        assertNull(McpArgs.optFloatBoxed(args, "missing"));
        assertEquals(9, McpArgs.optInt(args, "missing", 9));
        assertEquals(7, McpArgs.optInt(args, "i", 9));
        assertEquals(2.5, McpArgs.optDouble(args, "missing", 2.5));
        assertThrows(IllegalArgumentException.class, () -> McpArgs.reqFloat(args, "s"));
        assertThrows(IllegalArgumentException.class, () -> McpArgs.reqInt(args, "missing"));
    }

    @Test
    void boolParser() {
        JsonNode args = json("{\"t\":true,\"n\":1}");
        assertTrue(McpArgs.optBool(args, "t", false));
        assertTrue(McpArgs.optBool(args, "n", true)); // non-boolean falls back
        assertTrue(McpArgs.optBool(args, "missing", true));
    }

    @Test
    void arrayParsers() {
        JsonNode args = json("{\"f\":[1,2.5,3],\"i\":[4,5],\"bad\":[1,\"x\"],\"empty\":[]}");
        assertArrayEquals(new float[]{1f, 2.5f, 3f}, McpArgs.optFloatArray(args, "f"));
        assertArrayEquals(new int[]{4, 5}, McpArgs.optIntArray(args, "i"));
        assertNull(McpArgs.optFloatArray(args, "missing"));
        assertArrayEquals(new int[]{4, 5}, McpArgs.reqIntArray(args, "i"));
        assertThrows(IllegalArgumentException.class, () -> McpArgs.reqIntArray(args, "empty"));
        assertThrows(IllegalArgumentException.class, () -> McpArgs.reqIntArray(args, "missing"));
        assertThrows(IllegalArgumentException.class, () -> McpArgs.optIntArray(args, "bad"));
    }

    @Test
    void vec3Parsers() {
        JsonNode args = json("{\"v\":[1,2,3],\"short\":[1,2],\"bad\":[1,2,\"x\"]}");
        assertEquals(new Vector3f(1, 2, 3), McpArgs.optVec3(args, "v"));
        assertNull(McpArgs.optVec3(args, "missing"));
        assertThrows(IllegalArgumentException.class, () -> McpArgs.optVec3(args, "short"));
        assertThrows(IllegalArgumentException.class, () -> McpArgs.optVec3(args, "bad"));
        assertEquals(new Vector3f(1, 2, 3), McpArgs.reqVec3(args, "v"));
        assertThrows(IllegalArgumentException.class, () -> McpArgs.reqVec3(args, "missing"));
    }

    @Test
    void stringListDropsBlankEntries() {
        JsonNode args = json("{\"l\":[\"a\",\"\",\"b\"],\"notArr\":\"x\"}");
        assertEquals(List.of("a", "b"), McpArgs.optStringList(args, "l"));
        assertEquals(List.of(), McpArgs.optStringList(args, "missing"));
        assertThrows(IllegalArgumentException.class, () -> McpArgs.optStringList(args, "notArr"));
    }

    @Test
    void rgbaAndIntAt() {
        JsonNode args = json("{\"color\":[255,128,0,255],\"short\":[1,2,3]}");
        assertArrayEquals(new int[]{255, 128, 0, 255}, McpArgs.reqRgba(args, "color"));
        assertThrows(IllegalArgumentException.class, () -> McpArgs.reqRgba(args, "short"));
        assertThrows(IllegalArgumentException.class, () -> McpArgs.reqRgba(args, "missing"));

        JsonNode arr = json("[1,\"x\"]");
        assertEquals(1, McpArgs.intAt(arr, 0, "pixels"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> McpArgs.intAt(arr, 1, "pixels"));
        assertTrue(ex.getMessage().contains("pixels[1]"));
    }
}
