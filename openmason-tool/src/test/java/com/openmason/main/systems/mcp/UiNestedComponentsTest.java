package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.format.sbui.SbuiReader;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.openmason.main.systems.io.AssetWriteService;
import com.openmason.main.systems.io.WritePolicy;
import com.openmason.main.systems.io.WriteRoots;
import com.openmason.main.systems.io.WriteSandbox;
import com.openmason.main.systems.threading.MainThreadExecutor;
import com.openmason.main.systems.uiEditor.automation.UiAutomation;
import com.openmason.main.systems.uiEditor.service.UiDocumentService;
import com.openmason.main.systems.uiEditor.service.UiProjectContext;
import com.openmason.main.systems.uiEditor.service.UiRecoveryService;
import com.openmason.main.systems.uiEditor.service.UiSnapshot;
import com.openmason.main.systems.uiEditor.view.UiEditorContext;
import com.stonebreak.ui.runtime.GameUiDocuments;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Components inside components (#324): a screen places {@code card}, whose source places
 * {@code btn}. Keys reach through both ({@code card/btn/label}), overrides land on the screen's
 * instance, typos at any depth are refused, the placing document lists the whole component
 * closure (so the export is not blocked), and the result survives export and the game's load.
 * Embedded nesting is covered by {@code UiOpBatchTest}.
 */
@Tag("integration")
class UiNestedComponentsTest {

    private final ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @TempDir
    Path tmp;

    private Path project;
    private UiEditorContext ctx;
    private McpToolRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        MainThreadExecutor.bindToCurrentThread();
        project = Files.createDirectories(tmp.resolve("proj"));
        ctx = new UiEditorContext(new UiDocumentService(new UiProjectContext(() -> project),
            new UiRecoveryService(tmp.resolve("rec"))));
        UiAutomation automation = new UiAutomation(ctx, null);
        AssetWriteService writes = new AssetWriteService(null, new WriteSandbox(WriteRoots.of(project,
            Files.createDirectories(tmp.resolve("game")), Files.createDirectories(tmp.resolve("exports")))),
            () -> WritePolicy.ASK_RISKY);
        registry = new McpToolRegistry();
        new UiToolDefinitions(new UiEditingService(() -> automation, writes, mapper, null), mapper).registerAll(registry);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
    }

    private JsonNode call(String tool, String argsJson) throws Exception {
        return mapper.valueToTree(registry.get(tool).handler().call(mapper.readTree(argsJson)));
    }

    private String refused(String opsJson) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> call("ui_ops", opsJson));
        assertTrue(e.getMessage().contains("unchanged"), e.getMessage());
        return e.getMessage();
    }

    @Test
    void keysReachThroughNestedInstances() throws Exception {
        call("ui_new", "{\"template\":\"button_component\",\"id\":\"test:ui/components/btn\"}");
        call("ui_save", "{}");
        call("ui_new", "{\"template\":\"blank_component\",\"id\":\"test:ui/components/card\"}");
        call("ui_ops", "{\"ops\":[{\"op\":\"add_instance\",\"component\":\"test:ui/components/btn\"}]}");
        call("ui_save", "{}");
        call("ui_new", "{\"id\":\"test:ui/screens/deck\"}");
        JsonNode ops = call("ui_ops", """
            {"ops":[{"op":"add_instance","component":"test:ui/components/card","as":"c"},
                    {"op":"set_style","keys":"$c/btn/button","style":{"width":222}},
                    {"op":"set_prop","keys":"$c/btn/label","prop":"text","value":"Deep"}]}""");
        String card = ops.get("bound").get("c").get(0).asText();
        assertEquals("card", card);
        var deps = ctx.service.active().archive().dependencies();
        assertNotNull(deps.find("test:ui/components/btn"), "placing card lists what card places");
        assertTrue(deps.find("test:ui/components/card").requires().contains("test:ui/components/btn"),
            "card's row requires its closure");

        // typos at each depth, and a path through something that is not an instance
        assertTrue(refused("{\"ops\":[{\"op\":\"set_prop\",\"keys\":\"card/btm/label\",\"prop\":\"text\",\"value\":1}]}")
            .contains("test:ui/components/card has no element 'btm'"));
        assertTrue(refused("{\"ops\":[{\"op\":\"set_prop\",\"keys\":\"card/btn/lable\",\"prop\":\"text\",\"value\":1}]}")
            .contains("test:ui/components/btn has no element 'lable'"));
        assertTrue(refused("{\"ops\":[{\"op\":\"set_style\",\"keys\":\"card/root/label\",\"style\":{\"width\":1}}]}")
            .contains("not a nested component instance"));

        // reads agree with the ops
        assertTrue(call("ui_tree", "{\"internals\":true}").toString().contains("\"card/btn/label\""));
        JsonNode label = call("ui_get", "{\"key\":\"card/btn/label\"}");
        assertEquals("Deep", label.get("override").get("props").get("text").asText());
        assertEquals("test:ui/components/card", label.get("component").asText());
        assertEquals(222, call("ui_get", "{\"key\":\"card/btn/button\"}").get("rect").get("w").asDouble(), 0.5);

        // export (everything collected) and load like the game: the deep override travelled
        call("ui_save", "{}");
        Path sbui = Path.of(call("ui_export", "{\"mode\":\"collect_all\"}").get("path").asText());
        SbuiArchive loaded = SbuiReader.read(sbui, SbuiReader.Options.RUNTIME).archive();
        try (UiDocumentView view = GameUiDocuments.open(loaded, Map.of(), ctx::typeface, Map.of())) {
            UiSnapshot.render(view, ctx.typeface(), 1280, 720, 1f);
            UiElement button = view.instance().find("card/btn/button");
            assertNotNull(button, "nested instance built from the collected copies");
            assertEquals(222, button.rect().width(), 0.5f);
        }

        // an editable copy of the export (its component rows stay shared and resolve through the project)
        call("ui_close", "{}");
        call("ui_open", "{\"path\":\"" + sbui + "\"}");
        assertTrue(refused("{\"ops\":[{\"op\":\"set_prop\",\"keys\":\"card/btn/lable\",\"prop\":\"text\",\"value\":1}]}")
            .contains("has no element 'lable'"));
        JsonNode ok = call("ui_ops", "{\"ops\":[{\"op\":\"set_style\",\"keys\":\"card/btn/button\",\"style\":{\"width\":111}}]}");
        assertTrue(ok.get("changed").asBoolean());
    }
}
