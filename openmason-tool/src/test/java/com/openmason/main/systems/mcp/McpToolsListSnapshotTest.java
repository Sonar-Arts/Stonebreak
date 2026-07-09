package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the MCP tool surface: any tool add/remove/rename must show up as an
 * explicit diff against {@code /mcp/expected-tool-names.txt}, and the
 * serialized {@code tools/list} payload must stay under a byte budget so
 * surface growth is a conscious decision (small local LLMs read this payload
 * on every session).
 *
 * <p>Registration is exercised exactly as {@link McpServerBootstrap} does it,
 * but with a null {@code MainImGuiInterface} — service constructors only store
 * the reference; no tool handler is invoked here.
 */
class McpToolsListSnapshotTest {

    /**
     * Upper bound on the serialized tools/list JSON (name + description +
     * inputSchema for every tool). Set for the curated 71-tool surface —
     * growing past it must be a conscious decision, not drift.
     */
    private static final int TOOLS_LIST_BYTE_BUDGET = 60_000;

    private static McpToolRegistry buildRealRegistry() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        McpToolRegistry registry = new McpToolRegistry();
        ModelEditingService model = new ModelEditingService(null);
        TextureEditingService texture = new TextureEditingService(null);
        FaceTextureEditingService faceTexture = new FaceTextureEditingService(null);
        BoneEditingService bones = new BoneEditingService(null);
        AttachmentEditingService attachments = new AttachmentEditingService(null);
        AnimationEditingService animation = new AnimationEditingService(null);
        new OpenMasonToolDefinitions(model, mapper).registerAll(registry);
        new TextureToolDefinitions(texture, new CanvasCaptureService(null), mapper).registerAll(registry);
        new FaceTextureToolDefinitions(faceTexture, mapper).registerAll(registry);
        new BoneToolDefinitions(bones, mapper).registerAll(registry);
        new AttachmentToolDefinitions(attachments, mapper).registerAll(registry);
        new AnimationToolDefinitions(animation, mapper).registerAll(registry);
        new ViewportToolDefinitions(new ViewportCaptureService(null), mapper).registerAll(registry);
        new com.openmason.main.systems.scripting.mcp.ScriptingToolDefinitions(
                new com.openmason.main.systems.scripting.mcp.ScriptingService(null, mapper), mapper)
                .registerAll(registry);
        new MetaToolDefinitions(new ModelSummaryService(null),
                model, texture, bones, attachments, animation, mapper).registerAll(registry);
        return registry;
    }

    @Test
    void toolNamesMatchGoldenList() throws IOException {
        List<String> actual = buildRealRegistry().all().stream()
                .map(McpTool::name)
                .sorted()
                .toList();

        List<String> expected = readGolden();
        assertEquals(expected, actual,
                "MCP tool surface changed. If intentional, update src/test/resources/mcp/expected-tool-names.txt");
    }

    @Test
    void everyToolHasDescriptionAndObjectSchema() {
        for (McpTool tool : buildRealRegistry().all()) {
            assertNotNull(tool.description(), tool.name() + " has no description");
            assertFalse(tool.description().isBlank(), tool.name() + " has a blank description");
            JsonNode schema = tool.inputSchema();
            assertNotNull(schema, tool.name() + " has no input schema");
            assertEquals("object", schema.path("type").asText(), tool.name() + " schema is not an object");
            assertTrue(schema.has("properties"), tool.name() + " schema has no properties node");
        }
    }

    @Test
    void toolsListPayloadWithinByteBudget() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        List<Object> entries = new ArrayList<>();
        for (McpTool tool : buildRealRegistry().all()) {
            entries.add(new ToolEntry(tool.name(), tool.description(), tool.inputSchema()));
        }
        int bytes = mapper.writeValueAsBytes(entries).length;
        assertTrue(bytes <= TOOLS_LIST_BYTE_BUDGET,
                "tools/list payload is " + bytes + " bytes, over the " + TOOLS_LIST_BYTE_BUDGET
                        + " budget — the tool surface grew; shrink descriptions/schemas or consciously raise the budget");
    }

    private record ToolEntry(String name, String description, JsonNode inputSchema) {
    }

    private static List<String> readGolden() throws IOException {
        try (InputStream in = McpToolsListSnapshotTest.class.getResourceAsStream("/mcp/expected-tool-names.txt")) {
            assertNotNull(in, "missing test resource /mcp/expected-tool-names.txt");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .sorted()
                    .toList();
        }
    }
}
