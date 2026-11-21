package com.stonebreak.openmason.assets.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.api.mcp.McpToolContribution;
import com.openmason.main.systems.mcp.McpSurface;
import com.openmason.main.systems.mcp.McpTool;
import com.openmason.main.systems.mcp.McpToolRegistry;
import com.openmason.main.systems.mcp.McpToolRegistryFactory;
import com.openmason.main.systems.mcp.ToolCapabilities;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the Stonebreak plugin's MCP tool surface (#431: the groups moved out of the tool with their
 * golden lists, {@code /mcp/tools/<group>.txt}; regenerate with {@code -Dmcp.snapshot.write=true}).
 */
@Tag("regression")
class StonebreakMcpToolsSnapshotTest {

    private static final Path GOLDEN_DIR = Path.of("src/test/resources/mcp/tools");

    private static List<String> namesOf(McpToolContribution group) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setDefaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL));
        McpToolRegistry registry = new McpToolRegistry();
        group.contributor().contribute(McpToolRegistryFactory.context(null, mapper, ToolCapabilities.none(),
                registry, McpSurface.builtin()));
        return registry.all().stream().map(McpTool::name).sorted().toList();
    }

    @Test
    void eachGroupMatchesItsGoldenList() throws IOException {
        boolean write = Boolean.getBoolean("mcp.snapshot.write");
        for (McpToolContribution group : StonebreakMcpTools.all()) {
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
    void theAssetsGuideTopicReadsFromThePluginModule() {
        String text = StonebreakMcpTools.assetsTopic().markdown().get();
        assertTrue(text != null && !text.isBlank());
        assertTrue(text.startsWith("# Asset lens") && text.contains("asset_list"), text);
    }

    private static List<String> readGolden(String group) throws IOException {
        try (InputStream in = StonebreakMcpToolsSnapshotTest.class.getResourceAsStream("/mcp/tools/" + group + ".txt")) {
            assertNotNull(in, "missing test resource /mcp/tools/" + group + ".txt");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim).filter(s -> !s.isEmpty()).sorted().toList();
        }
    }
}
