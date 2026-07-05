package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpRequestRouterTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private McpRequestRouter router;

    @BeforeEach
    void setUp() {
        McpToolRegistry registry = new McpToolRegistry();
        registry.register(new McpTool("echo_json", "returns a map",
                McpSchema.of(mapper).build(),
                args -> Map.of("ok", true, "value", 42)));
        registry.register(new McpTool("echo_markdown", "returns raw text",
                McpSchema.of(mapper).build(),
                args -> "# Heading\nplain **markdown** text"));
        registry.register(new McpTool("echo_image", "returns an image",
                McpSchema.of(mapper).build(),
                args -> new McpImageContent("QUJD", "image/png")));
        registry.register(new McpTool("throws_iae", "teaching error",
                McpSchema.of(mapper).build(),
                args -> {
                    throw new IllegalArgumentException("No part 'x' (call list_parts)");
                }));
        router = new McpRequestRouter(registry, mapper);
    }

    private McpJsonRpc.Response call(String toolName) {
        JsonNode params = mapper.createObjectNode().put("name", toolName);
        return router.handle(new McpJsonRpc.Request("2.0", 1, "tools/call", params));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> content(McpJsonRpc.Response response) {
        Map<String, Object> result = (Map<String, Object>) response.result();
        return (Map<String, Object>) ((List<?>) result.get("content")).get(0);
    }

    @SuppressWarnings("unchecked")
    private static boolean isError(McpJsonRpc.Response response) {
        return Boolean.TRUE.equals(((Map<String, Object>) response.result()).get("isError"));
    }

    @Test
    void initializeCarriesInstructions() {
        McpJsonRpc.Response response = router.handle(
                new McpJsonRpc.Request("2.0", 1, "initialize", null));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.result();
        String instructions = (String) result.get("instructions");
        assertNotNull(instructions);
        assertTrue(instructions.contains("model_summary"));
        assertTrue(instructions.contains("run_python_script"));
    }

    @Test
    void objectResultsAreJsonEncodedInTextBlock() {
        McpJsonRpc.Response response = call("echo_json");
        Map<String, Object> content = content(response);
        assertEquals("text", content.get("type"));
        assertTrue(((String) content.get("text")).contains("\"value\":42"));
        assertTrue(!isError(response));
    }

    @Test
    void stringResultsPassThroughUnquoted() {
        Map<String, Object> content = content(call("echo_markdown"));
        assertEquals("# Heading\nplain **markdown** text", content.get("text"),
                "raw strings must not be JSON-escaped");
    }

    @Test
    void imageResultsBecomeImageContent() {
        Map<String, Object> content = content(call("echo_image"));
        assertEquals("image", content.get("type"));
        assertEquals("QUJD", content.get("data"));
        assertEquals("image/png", content.get("mimeType"));
    }

    @Test
    void toolIllegalArgumentBecomesIsErrorTextNotProtocolError() {
        McpJsonRpc.Response response = call("throws_iae");
        assertTrue(isError(response));
        assertTrue(((String) content(response).get("text")).contains("list_parts"));
    }

    @Test
    void unknownToolIsAProtocolError() {
        McpJsonRpc.Response response = call("no_such_tool");
        assertNotNull(response.error());
        assertTrue(response.error().message().contains("no_such_tool"));
    }
}
