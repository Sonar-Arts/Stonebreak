package com.openmason.main.systems.assistant.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.main.systems.mcp.McpTool;
import com.openmason.main.systems.mcp.McpToolRegistry;
import com.openmason.main.systems.mcp.McpToolRegistryFactory;
import com.openmason.main.systems.mcp.ToolCapabilities;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolBridgeTest {

    private static ObjectMapper mapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
    }

    @Test
    void everyRegistryToolBecomesAFunctionSpec() {
        ObjectMapper mapper = mapper();
        McpToolRegistry registry =
                McpToolRegistryFactory.build(null, mapper, ToolCapabilities.none());
        ToolBridge bridge = new ToolBridge(registry, mapper);
        List<ObjectNode> specs = bridge.openAiToolSpecs();
        assertEquals(registry.all().size(), specs.size());
        for (ObjectNode spec : specs) {
            assertEquals("function", spec.path("type").asText());
            assertFalse(spec.path("function").path("name").asText().isEmpty());
            assertTrue(spec.path("function").path("parameters").isObject());
        }
        // Spot check: schema passes through verbatim.
        McpTool knowledge = registry.get("knowledge");
        ObjectNode knowledgeSpec = specs.stream()
                .filter(s -> s.path("function").path("name").asText().equals("knowledge"))
                .findFirst().orElseThrow();
        assertEquals(knowledge.inputSchema(), knowledgeSpec.path("function").path("parameters"));
    }

    @Test
    void dispatchStringTool() {
        ObjectMapper mapper = mapper();
        McpToolRegistry registry =
                McpToolRegistryFactory.build(null, mapper, ToolCapabilities.none());
        ToolBridge bridge = new ToolBridge(registry, mapper);
        ToolBridge.DispatchResult result = bridge.dispatch("knowledge", "{}");
        assertFalse(result.error(), () -> "knowledge dispatch failed: " + result.text());
        assertTrue(result.text().contains("modeling_fundamentals"));
    }

    @Test
    void unknownToolAndBadArgsBecomeTeachingErrors() {
        ObjectMapper mapper = mapper();
        McpToolRegistry registry =
                McpToolRegistryFactory.build(null, mapper, ToolCapabilities.none());
        ToolBridge bridge = new ToolBridge(registry, mapper);
        ToolBridge.DispatchResult unknown = bridge.dispatch("nope_tool", "{}");
        assertTrue(unknown.error());
        assertTrue(unknown.text().contains("unknown tool"));
        ToolBridge.DispatchResult badArgs = bridge.dispatch("knowledge", "{broken");
        assertTrue(badArgs.error());
        assertTrue(badArgs.text().contains("not valid JSON"));
    }
}
