package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.api.mcp.McpToolContribution;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the MCP tool surface: any tool add/remove/rename must show up as an
 * explicit diff against the golden list of the tool group (plugin contribution,
 * #379) it belongs to, {@code /mcp/tools/<contribution id>.txt}, and the
 * serialized {@code tools/list} payload must stay under a byte budget so
 * surface growth is a conscious decision (small local LLMs read this payload
 * on every session). Regenerate the lists with {@code -Dmcp.snapshot.write=true}.
 *
 * <p>Registration is exercised exactly as {@link McpServerBootstrap} does it,
 * but with no editor services — service constructors only store the reference;
 * no tool handler is invoked here.
 */
@Tag("regression")
class McpToolsListSnapshotTest {

    /**
     * Upper bound on the serialized tools/list JSON (name + description +
     * inputSchema for every tool). Raised 60k -> 72k for the 112-tool surface
     * (2026-08-23: asset lens, model inspection, script library, knowledge) —
     * measured 61.3k at the raise. Raised 72k -> 80k for the 142-tool surface
     * (2026-09-16: save sandbox / Save Sheet, model_save/open/new, sbo/sbe
     * export + editor tools) — measured ~74k at the raise. Raised 80k -> 88k
     * for the 162-tool surface (2026-10-06, #324: 20 ui_* UI Editor tools,
     * 10.4k of the payload; the edits are one compact ui_ops batch tool rather
     * than per-op tools) — measured 85.2k at the raise; growing past the
     * budget must stay a conscious decision, not drift.
     */
    private static final int TOOLS_LIST_BYTE_BUDGET = 88_000;

    private static McpToolRegistry buildRealRegistry() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        // Same construction path as production (McpServerBootstrap + the
        // assistant harness); no editor services — constructors only
        // store the reference. Capabilities.none() keeps the surface
        // deterministic on machines without optional integrations.
        return McpToolRegistryFactory.build(null, mapper, ToolCapabilities.none());
    }

    /** Where the per-group golden lists live in the source tree (surefire runs in the module dir). */
    private static final Path GOLDEN_DIR = Path.of("src/test/resources/mcp/tools");

    @Test
    void eachToolGroupMatchesItsGoldenList() throws IOException {
        boolean write = Boolean.getBoolean("mcp.snapshot.write");
        for (McpToolContribution group : BuiltinMcpTools.all()) {
            List<String> actual = namesOf(group);
            if (write) {
                Files.createDirectories(GOLDEN_DIR);
                Files.writeString(GOLDEN_DIR.resolve(group.id() + ".txt"), String.join("\n", actual) + "\n");
                continue;
            }
            assertEquals(readGolden(group.id()), actual, "MCP tool surface of group '" + group.id()
                    + "' changed. If intentional, regenerate with -Dmcp.snapshot.write=true");
        }
    }

    @Test
    void everyGoldenListBelongsToAToolGroup() throws IOException {
        Set<String> groups = BuiltinMcpTools.all().stream().map(McpToolContribution::id)
                .collect(Collectors.toCollection(TreeSet::new));
        try (Stream<Path> files = Files.list(GOLDEN_DIR)) {
            Set<String> golden = files.map(f -> f.getFileName().toString())
                    .filter(n -> n.endsWith(".txt")).map(n -> n.substring(0, n.length() - 4))
                    .collect(Collectors.toCollection(TreeSet::new));
            assertEquals(groups, golden, "golden lists without a tool group (or groups without a list) in "
                    + GOLDEN_DIR);
        }
    }

    @Test
    void theWholeSurfaceIsTheUnionOfItsGroups() {
        // Names are unique across groups (the registry refuses duplicates while building).
        List<String> whole = buildRealRegistry().all().stream().map(McpTool::name).sorted().toList();
        List<String> union = BuiltinMcpTools.all().stream().flatMap(g -> namesOf(g).stream()).sorted().toList();
        assertEquals(union, whole);
    }

    /** The sorted tool names one group registers on its own. */
    private static List<String> namesOf(McpToolContribution group) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        McpToolRegistry registry = new McpToolRegistry();
        group.contributor().contribute(McpToolRegistryFactory.context(null, mapper, ToolCapabilities.none(),
                registry, McpSurface.builtin()));
        return registry.all().stream().map(McpTool::name).sorted().toList();
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

    private static List<String> readGolden(String group) throws IOException {
        try (InputStream in = McpToolsListSnapshotTest.class.getResourceAsStream("/mcp/tools/" + group + ".txt")) {
            assertNotNull(in, "missing test resource /mcp/tools/" + group + ".txt");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .sorted()
                    .toList();
        }
    }
}
