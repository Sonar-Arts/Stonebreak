package com.openmason.main.systems.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.format.sbui.SbuiReader;
import com.openmason.engine.ui.assets.export.ExportMode;
import com.openmason.main.systems.io.AssetWriteService;
import com.openmason.main.systems.io.WritePolicy;
import com.openmason.main.systems.io.WriteRoots;
import com.openmason.main.systems.io.WriteSandbox;
import com.openmason.main.systems.threading.MainThreadExecutor;
import com.openmason.main.systems.uiEditor.automation.UiAutomation;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.service.UiDocumentService;
import com.openmason.main.systems.uiEditor.service.UiGameDeploy;
import com.openmason.main.systems.uiEditor.service.UiProjectContext;
import com.openmason.main.systems.uiEditor.service.UiRecoveryService;
import com.openmason.main.systems.uiEditor.view.UiEditorContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent-facing data safety of the UI tools (#282 hardening): the editor's own autosave never
 * blocks {@code ui_save}, {@code ui_close discard} keeps the author's work recoverable, a timed-out
 * call never applies late, exports report what the real game host would say, and deploy ships
 * the screen and its shared assets into the game's layout without clobbering.
 */
class UiAgentHardeningTest {

    private final ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @TempDir
    Path tmp;

    private Path project;
    private Path game;
    private UiEditorContext ctx;
    private McpToolRegistry registry;

    @BeforeEach
    void setUp() throws Exception {
        MainThreadExecutor.bindToCurrentThread();
        project = Files.createDirectories(tmp.resolve("proj"));
        game = Files.createDirectories(tmp.resolve("game"));
        ctx = new UiEditorContext(new UiDocumentService(new UiProjectContext(() -> project),
            new UiRecoveryService(tmp.resolve("rec"))));
        UiAutomation automation = new UiAutomation(ctx, null);
        AssetWriteService writes = new AssetWriteService(null, new WriteSandbox(WriteRoots.of(project, game,
            Files.createDirectories(tmp.resolve("exports")))), () -> WritePolicy.ASK_RISKY);
        registry = new McpToolRegistry();
        new UiToolDefinitions(new UiEditingService(() -> automation, writes, mapper, null), mapper).registerAll(registry);
    }

    @AfterEach
    void tearDown() {
        ctx.close();
        MainThreadExecutor.bindToCurrentThread();
    }

    private JsonNode call(String tool, String argsJson) throws Exception {
        return mapper.valueToTree(registry.get(tool).handler().call(mapper.readTree(argsJson)));
    }

    private UiEditorDocument active() {
        return ctx.service.active();
    }

    @Test
    void theEditorsOwnAutosaveNeverBlocksAnAgentSave() throws Exception {
        call("ui_new", "{\"id\":\"test:ui/screens/a\",\"name\":\"A\"}");
        call("ui_save", "{}");
        call("ui_ops", "{\"ops\":[{\"op\":\"create\",\"type\":\"Label\",\"id\":\"l\"}]}");
        Files.setLastModifiedTime(active().file(), java.nio.file.attribute.FileTime.fromMillis(1000));
        assertTrue(ctx.service.recovery().write(active()), "the 20 s autosave ran");
        assertFalse(call("ui_documents", "{}").get(0).has("recovery"), "no false recovery warning");
        JsonNode saved = call("ui_save", "{}");
        assertFalse(saved.get("dirty").asBoolean(), saved.toString());
    }

    @Test
    void discardingAnAgentCloseKeepsTheWorkInRecovery() throws Exception {
        call("ui_new", "{\"id\":\"test:ui/screens/b\",\"name\":\"B\"}");
        call("ui_save", "{}");
        Path file = active().file();
        call("ui_ops", "{\"ops\":[{\"op\":\"create\",\"type\":\"Label\",\"id\":\"kept\"}]}");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1000));
        JsonNode closed = call("ui_close", "{\"discard\":true}");
        assertTrue(closed.get("recovery").asText().contains("crash recovery"), closed.toString());
        assertNotNull(ctx.service.open(file).recovery(), "reopening offers the dropped changes");
    }

    @Test
    void aCallThatTimedOutBeforeRunningNeverRunsLater() throws Exception {
        Thread busyMain = new Thread(MainThreadExecutor::bindToCurrentThread);
        busyMain.start();
        busyMain.join(); // the "main thread" is now a thread that never drains
        AtomicBoolean ran = new AtomicBoolean();
        RuntimeException e = assertThrows(RuntimeException.class,
            () -> UiEditingService.onMain(() -> ran.getAndSet(true), 50));
        assertTrue(e.getMessage().contains("nothing was applied"), e.getMessage());
        MainThreadExecutor.drain(); // the editor catches up
        assertFalse(ran.get(), "a retry cannot apply the edit twice");
    }

    @Test
    void exportsReportWhatTheRealGameHostWouldSay() throws Exception {
        call("ui_new", "{\"id\":\"test:ui/screens/c\",\"name\":\"C\"}");
        call("ui_ops", """
            {"ops":[{"op":"create","type":"Label","id":"l",
                     "bindings":[{"target":"prop:text","path":"nosuchroot.value"}]}]}""");
        JsonNode export = call("ui_export", "{}");
        assertFalse(export.get("hostCheck").get("gameWouldOpen").asBoolean(), export.toString());
        assertTrue(export.get("hostCheck").get("findings").toString().contains("nosuchroot"), export.toString());

        JsonNode deploy = call("ui_export", "{\"deploy\":true}");
        assertFalse(deploy.get("ok").asBoolean());
        try (var files = Files.walk(game)) {
            assertEquals(1, files.count(), "nothing was written into the game");
        }
    }

    private void png(Path file, int argb) throws Exception {
        BufferedImage img = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, argb);
        Files.createDirectories(file.getParent());
        ImageIO.write(img, "png", file.toFile());
    }

    @Test
    void deployShipsTheScreenAndItsSharedProjectAssetsWithoutClobbering() throws Exception {
        png(project.resolve("UI/test/ui/textures/panel.png"), 0xFF112233);
        call("ui_new", "{\"id\":\"test:ui/screens/hud\",\"name\":\"HUD\"}");
        call("ui_ops", """
            {"ops":[{"op":"add_dependency","path":"UI/test/ui/textures/panel.png"},
                    {"op":"create","type":"Box","id":"bg","style":{"width":10,"height":10,
                     "background-image":"test:ui/textures/panel"}}]}""");
        UiEditorDocument doc = active();

        UiGameDeploy.Plan plan = ctx.service.planDeploy(doc, ExportMode.SHARED, game);
        assertTrue(plan.check().runnable(), plan.check().lines().toString());
        assertEquals("hud", plan.screenId());
        assertTrue(plan.conflicts().isEmpty());
        UiGameDeploy.write(plan, false);
        Path sbui = game.resolve("ui/documents/hud.sbui");
        Path asset = game.resolve("ui/shared/test/ui/textures/panel.png");
        assertTrue(Files.isRegularFile(sbui));
        assertTrue(Files.isRegularFile(asset));
        assertEquals("test:ui/screens/hud", SbuiReader.read(sbui, SbuiReader.Options.RUNTIME).archive()
            .source().manifest().documentId());

        // the project art changes; redeploying would replace the shipped file: only on confirmation
        png(project.resolve("UI/test/ui/textures/panel.png"), 0xFFFF0000);
        byte[] shipped = Files.readAllBytes(asset);
        UiGameDeploy.Plan again = ctx.service.planDeploy(doc, ExportMode.SHARED, game);
        assertFalse(again.conflicts().isEmpty());
        assertThrows(IllegalStateException.class, () -> UiGameDeploy.write(again, false));
        assertEquals(java.util.Arrays.toString(shipped), java.util.Arrays.toString(Files.readAllBytes(asset)),
            "nothing written without confirmation");
        UiGameDeploy.write(again, true);
        assertFalse(java.util.Arrays.equals(shipped, Files.readAllBytes(asset)));

        // the MCP form reports the conflict instead of writing
        png(project.resolve("UI/test/ui/textures/panel.png"), 0xFF00FF00);
        JsonNode viaMcp = call("ui_export", "{\"deploy\":true}");
        assertFalse(viaMcp.get("ok").asBoolean(), viaMcp.toString());
        assertTrue(viaMcp.get("conflicts").toString().contains("panel.png"), viaMcp.toString());
    }
}
