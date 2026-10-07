package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.format.sbui.SbuiReader;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.openmason.main.systems.io.AssetWriteService;
import com.openmason.main.systems.io.WritePolicy;
import com.openmason.main.systems.io.WriteRoots;
import com.openmason.main.systems.io.WriteSandbox;
import com.openmason.main.systems.threading.MainThreadExecutor;
import com.openmason.main.systems.uiEditor.automation.UiAutomation;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An agent authors a UI screen with only the {@code ui_*} MCP tools (#324): new component +
 * screen, edits through {@code ui_ops}, a capture, save at the convention path, SBUI export,
 * then load the export the way the game does. Also pins that preview tools never touch the
 * source and that writes stay in the sandbox. Headless: the tool handlers run against a real
 * {@link UiAutomation} with the UI thread bound to the test thread.
 */
@Tag("integration")
class UiToolsEndToEndTest {

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
        UiProjectContext projectCtx = new UiProjectContext(() -> project);
        ctx = new UiEditorContext(new UiDocumentService(projectCtx, new UiRecoveryService(tmp.resolve("rec"))));
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
        Object result = registry.get(tool).handler().call(mapper.readTree(argsJson));
        return result instanceof McpImageContent img ? mapper.valueToTree(Map.of("png", img.base64Data()))
            : mapper.valueToTree(result);
    }

    private UiEditorDocument doc(String id) {
        return ctx.service.documents().stream().filter(d -> id.equals(d.archive().manifest().documentId()))
            .findFirst().orElseThrow();
    }

    private static BufferedImage png(JsonNode capture) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(capture.get("png").asText())));
    }

    @Test
    void agentAuthorsSavesExportsAndTheGameLoadsIt() throws Exception {
        // a component, saved at its convention path so the screen can place it from the project
        JsonNode button = call("ui_new", """
            {"template":"button_component","id":"test:ui/components/big_button","name":"Big Button"}""");
        assertEquals(true, button.get("dirty").asBoolean());
        assertEquals("UI/test/ui/components/big_button.omui", button.get("saveTarget").asText());
        JsonNode saved = call("ui_save", "{}");
        assertEquals("UI/test/ui/components/big_button.omui", saved.get("file").asText());
        assertFalse(saved.get("dirty").asBoolean());
        assertFalse(saved.get("promptedUser").asBoolean(), "a new file in the project is written silently");

        call("ui_new", "{\"id\":\"test:ui/screens/hud\",\"name\":\"HUD\"}");
        JsonNode ops = call("ui_ops", """
            {"label":"Agent: build HUD","ops":[
              {"op":"create","type":"Box","id":"panel","name":"panel","classes":["panel"],
               "style":{"width":400,"height":300,"flex-direction":"column","align-items":"center","row-gap":12},
               "as":"panel"},
              {"op":"create","parent":"$panel","type":"Label","name":"status_label","props":{"text":"Status"},
               "bindings":[{"target":"prop:text","path":"session.name"}],"as":"status"},
              {"op":"add_instance","parent":"$panel","component":"test:ui/components/big_button",
               "params":{"label":"Resume"},"as":"btn"},
              {"op":"set_style","keys":"$btn/button","style":{"width":200}},
              {"op":"add_sheet","id":"hud"},
              {"op":"add_rule","sheet":"hud","selector":"#panel","style":{"background-color":"#203040FF"}}
            ]}""");
        assertTrue(ops.get("changed").asBoolean());
        assertEquals("Agent: build HUD", ops.get("step").asText());
        String btn = ops.get("bound").get("btn").get(0).asText();
        UiEditorDocument hud = doc("test:ui/screens/hud");
        assertEquals(1, hud.history().undoLabels().size(), "the whole batch is one History step");

        // reads
        JsonNode tree = call("ui_tree", "{\"internals\":true}");
        assertEquals("root", tree.get("key").asText());
        assertTrue(tree.toString().contains("\"" + btn + "/label\""), "internals listed: " + tree);
        JsonNode panel = call("ui_get", "{\"key\":\"panel\",\"computed\":true}");
        assertEquals(400, panel.get("rect").get("w").asDouble(), 0.5);
        assertTrue(panel.get("computed").get("background-color").get("from").asText().endsWith("hud#0 #panel"),
            panel.get("computed").toString());
        assertEquals("label", call("ui_get", "{\"key\":\"#" + "status_label\"}").get("key").asText());
        JsonNode sheets = call("ui_style_sheets", "{\"key\":\"panel\"}");
        assertEquals("#panel", sheets.get("matching").get(0).get("selector").asText());
        JsonNode findings = call("ui_diagnostics", "{}");
        assertFalse(findings.get("findings").toString().contains("\"severity\":\"error\""), findings.toString());

        // capture: the engine's paint at the requested frame size
        BufferedImage img = png(call("ui_capture", "{\"source\":\"design\",\"width\":640,\"height\":360,"
            + "\"max_size\":0}"));
        assertEquals(640, img.getWidth());
        assertEquals(360, img.getHeight());
        assertEquals(0x203040, img.getRGB(130, 180) & 0xFFFFFF, "the panel (x 120..520) in its rule colour");
        assertEquals(0x203040 == (img.getRGB(110, 180) & 0xFFFFFF), false, "outside the panel");
        BufferedImage hi = png(call("ui_capture", "{\"source\":\"design\",\"width\":1280,\"height\":720,"
            + "\"ui_scale\":2,\"max_size\":0}"));
        assertEquals(0x203040, hi.getRGB(250, 360) & 0xFFFFFF, "800 px wide at ui scale 2 (x 240..1040)");
        assertEquals(0x203040 == (hi.getRGB(230, 360) & 0xFFFFFF), false);

        // save at the convention path, export, load like the game
        call("ui_save", "{}");
        Path omui = project.resolve("UI/test/ui/screens/hud.omui");
        assertTrue(Files.isRegularFile(omui));
        assertEquals(hud.archive(), OmuiReader.read(omui).archive());
        JsonNode export = call("ui_export", "{\"mode\":\"collect_all\"}");
        Path sbui = Path.of(export.get("path").asText());
        assertEquals(project.resolve("Exports/UI/hud.sbui").toRealPath(), sbui.toRealPath());
        assertTrue(Files.isRegularFile(Path.of(export.get("report").asText())));

        SbuiArchive loaded = SbuiReader.read(sbui, SbuiReader.Options.RUNTIME).archive();
        try (UiDocumentView view = GameUiDocuments.open(loaded, Map.of(), ctx::typeface, Map.of())) {
            UiSnapshot.render(view, ctx.typeface(), 1280, 720, 1f);
            UiDocumentInstance ui = view.instance();
            assertEquals(400, ui.find("panel").rect().width(), 0.5f);
            UiElement inner = ui.find(btn + "/button");
            assertNotNull(inner, "the component instantiates from the collected copy");
            assertEquals(200, inner.rect().width(), 0.5f, "the override travelled with the export");
        }

        // undo / redo are History steps the author sees too
        call("ui_undo", "{}");
        assertTrue(hud.isDirty());
        call("ui_redo", "{}");
        assertFalse(hud.isDirty(), "redo back to the saved step is clean again");
    }

    @Test
    void previewToolsNeverTouchTheSourceOrDirtyState() throws Exception {
        call("ui_new", "{\"template\":\"menu_screen\",\"id\":\"test:ui/screens/menu\"}");
        call("ui_save", "{}");
        UiEditorDocument menu = doc("test:ui/screens/menu");
        OmuiArchive before = menu.archive();
        long revision = menu.revision();

        JsonNode state = call("ui_preview", "{\"mode\":\"preview\",\"width\":1280,\"height\":720,\"ui_scale\":1.5}");
        assertEquals("preview", state.get("mode").asText());
        assertEquals(1280, state.get("frame").get("width").asInt());
        JsonNode input = call("ui_preview_input", """
            {"events":[{"type":"move","key":"play"},{"type":"click","key":"quit"},{"type":"key","name":"tab"},
                       {"type":"text","text":"x"}],"advance_ms":200}""");
        assertEquals(4, input.get("consumed").size());
        assertFalse(input.get("dirty").asBoolean());
        BufferedImage live = png(call("ui_capture", "{\"source\":\"preview\",\"max_size\":0}"));
        assertEquals(1280, live.getWidth());
        call("ui_console", "{}");
        call("ui_preview", "{\"mode\":\"design\",\"force\":{\"play\":[\"hover\"]}}");
        JsonNode cleared = call("ui_preview", "{\"clear_forced\":true}");
        assertFalse(cleared.has("forced"));

        assertSame(before, menu.archive(), "preview never replaces the source snapshot");
        assertEquals(revision, menu.revision());
        assertFalse(menu.isDirty());
        assertFalse(menu.history().canUndo(), "no History step either");

        IllegalStateException notPreview = assertThrows(IllegalStateException.class,
            () -> call("ui_preview_input", "{\"events\":[{\"type\":\"click\",\"key\":\"play\"}]}"));
        assertTrue(notPreview.getMessage().contains("preview"));
    }

    @Test
    void failuresTeachAndWritesStayInTheSandbox() throws Exception {
        call("ui_new", "{\"id\":\"test:ui/screens/x\"}");
        UiEditorDocument x = doc("test:ui/screens/x");
        IllegalArgumentException failed = assertThrows(IllegalArgumentException.class, () -> call("ui_ops", """
            {"ops":[{"op":"create","type":"Label"},{"op":"set_prop","keys":"missing","prop":"text","value":1}]}"""));
        assertTrue(failed.getMessage().startsWith("op 1 (set_prop)"), failed.getMessage());
        assertTrue(failed.getMessage().contains("unchanged"));
        assertFalse(x.history().canUndo());

        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
            () -> call("ui_ops", "{\"ops\":[{\"op\":\"explode\"}]}"));
        assertTrue(invalid.getMessage().contains("nothing was applied"));

        IllegalArgumentException outside = assertThrows(IllegalArgumentException.class,
            () -> call("ui_save_as", "{\"path\":\"" + tmp.resolve("elsewhere/x.omui") + "\"}"));
        assertTrue(outside.getMessage().contains("path_outside_sandbox"), outside.getMessage());
        assertFalse(Files.exists(tmp.resolve("elsewhere")));
        IllegalArgumentException wrongKind = assertThrows(IllegalArgumentException.class,
            () -> call("ui_save_as", "{\"path\":\"project:UI/x.png\"}"));
        assertTrue(wrongKind.getMessage().contains("wrong_extension"), wrongKind.getMessage());

        JsonNode saveAs = call("ui_save_as", "{\"path\":\"exports:copy\"}");
        assertTrue(saveAs.get("file").asText().endsWith("copy.omui"), "the copy is now the document's file");
        IllegalArgumentException noPath = assertThrows(IllegalArgumentException.class, () -> call("ui_save_as", "{}"));
        assertTrue(noPath.getMessage().contains("needs path"));
        assertTrue(Files.isRegularFile(tmp.resolve("exports/copy.omui")));
        assertFalse(saveAs.get("dirty").asBoolean());

        IllegalStateException dirtyClose = assertThrows(IllegalStateException.class, () -> {
            call("ui_ops", "{\"ops\":[{\"op\":\"create\",\"type\":\"Box\"}]}");
            call("ui_close", "{}");
        });
        assertTrue(dirtyClose.getMessage().contains("discard"));
        call("ui_close", "{\"discard\":true}");
        assertEquals(0, call("ui_documents", "{}").size());
    }

    @Test
    void reviewRegressions() throws Exception {
        call("ui_new", "{\"id\":\"test:ui/screens/dup\"}");
        IllegalArgumentException dup = assertThrows(IllegalArgumentException.class,
            () -> call("ui_new", "{\"id\":\"test:ui/screens/dup\"}"));
        assertTrue(dup.getMessage().contains("already open"));

        // a refused ui_preview changes nothing
        assertThrows(IllegalArgumentException.class,
            () -> call("ui_preview", "{\"mode\":\"preview\",\"force\":{\"nope\":[\"hover\"]}}"));
        assertEquals("design", call("ui_preview", "{}").get("mode").asText());
        assertThrows(IllegalArgumentException.class, () -> call("ui_preview", "{\"mode\":\"preview\",\"width\":5}"));
        assertEquals("design", call("ui_preview", "{}").get("mode").asText());

        // an in-place save of a game-resources file goes through the policy (no Save Sheet here = declined)
        Path gameUi = Files.createDirectories(tmp.resolve("game/ui")).resolve("pause_menu.omui");
        Files.copy(Path.of("../openmason-engine/src/test/resources/ui/omui/pause_menu.omui"), gameUi);
        byte[] shipped = Files.readAllBytes(gameUi);
        call("ui_open", "{\"path\":\"game:ui/pause_menu.omui\"}");
        call("ui_ops", "{\"ops\":[{\"op\":\"set_prop\",\"keys\":\"title\",\"prop\":\"text\",\"value\":\"x\"}]}");
        JsonNode declined = offMain("ui_save", "{}");
        assertEquals("declined", declined.get("status").asText(), declined.toString());
        assertEquals("prompt_unavailable", declined.get("reason").asText());
        assertTrue(java.util.Arrays.equals(shipped, Files.readAllBytes(gameUi)), "the shipped file is untouched");
        call("ui_close", "{\"discard\":true}");
        call("ui_activate", "{\"doc\":\"test:ui/screens/dup\"}");

        // a newer crash-recovery copy left by another session (a crash) blocks automation saves until
        // the author decides; this session's own autosave never does (UiAgentHardeningTest)
        Path rec = Files.createDirectories(project.resolve("UI/rec")).resolve("pause_menu.omui");
        Files.copy(Path.of("../openmason-engine/src/test/resources/ui/omui/pause_menu.omui"), rec);
        Files.setLastModifiedTime(rec, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        call("ui_open", "{\"path\":\"project:UI/rec/pause_menu.omui\"}");
        call("ui_ops", "{\"ops\":[{\"op\":\"set_prop\",\"keys\":\"title\",\"prop\":\"text\",\"value\":\"y\"}]}");
        UiEditorDocument recovered = doc("stonebreak:ui/pause_menu");
        UiRecoveryService crashed = new UiRecoveryService(tmp.resolve("rec"));
        assertTrue(crashed.write(recovered));
        assertTrue(call("ui_documents", "{}").toString().contains("crash-recovery"));
        IllegalStateException buried = assertThrows(IllegalStateException.class, () -> call("ui_save", "{}"));
        assertTrue(buried.getMessage().contains("Restore or Discard"), buried.getMessage());
        crashed.clear(recovered);
        call("ui_save", "{}");
        assertFalse(recovered.isDirty());
        call("ui_close", "{}");
        call("ui_activate", "{\"doc\":\"test:ui/screens/dup\"}");

        // import writes through the policy and lands at a fresh convention path
        call("ui_save", "{}");
        JsonNode export = call("ui_export", "{\"mode\":\"collect_all\"}");
        call("ui_close", "{}");
        JsonNode imported = call("ui_import_sbui", "{\"path\":\"" + export.get("path").asText() + "\"}");
        assertEquals("UI/test/ui/screens/dup_2.omui", imported.get("file").asText(), "never overwrites");
        assertTrue(Files.isRegularFile(project.resolve("UI/test/ui/screens/dup_2.omui")));
    }

    /** Runs a tool off the bound UI thread (prompt paths refuse the UI thread), draining UI work meanwhile. */
    private JsonNode offMain(String tool, String args) throws Exception {
        var future = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return call(tool, args);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        while (!future.isDone()) {
            MainThreadExecutor.drain();
            Thread.onSpinWait();
        }
        return future.get();
    }
}
