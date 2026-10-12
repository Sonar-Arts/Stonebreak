package com.openmason.main.systems.uiEditor.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.OmuiWriter;
import com.openmason.engine.format.omui.UiDependency;
import com.openmason.engine.ui.assets.ProjectFolder;
import com.openmason.main.systems.uiEditor.command.UiCommandException;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.openmason.main.systems.uiEditor.service.UiDocumentTemplates;
import com.openmason.main.systems.uiEditor.service.UiProjectContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dependency-table, graph and state-machine ops of {@code ui_ops} (#282 hardening): an agent
 * adds, configures, embeds, extracts and removes assets, and authors graphs and state machines,
 * each batch one undo step whose project writes are reverted with it.
 */
class UiAssetOpsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path project;

    private UiProjectContext ctx;
    private UiEditorDocument doc;
    private UiOpBatch.Components components;

    @BeforeEach
    void setUp() throws Exception {
        ctx = new UiProjectContext(() -> project);
        OmuiArchive a = com.openmason.main.systems.uiEditor.command.UiHistory.withRequiredFeatures(
            UiDocumentTemplates.BLANK_SCREEN.create("test:ui/screens/s", "S"));
        doc = new UiEditorDocument(a, null, UiEditorDocument.Origin.NEW, null);
        doc.setProject(new ProjectFolder(project));
        components = new UiOpBatch.Components() {
            @Override
            public List<UiDependency> rows(String componentId) {
                return List.of();
            }

            @Override
            public UiProjectContext project() {
                return ctx;
            }
        };
        png("UI/test/ui/textures/panel.png", 0xFF336699);
    }

    private Path png(String rel, int argb) throws Exception {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                img.setRGB(x, y, argb);
            }
        }
        Path file = project.resolve(rel);
        Files.createDirectories(file.getParent());
        ImageIO.write(img, "png", file.toFile());
        return file;
    }

    private boolean run(String json) throws Exception {
        UiOpBatch b = UiOpBatch.parse(MAPPER.readTree(json));
        return doc.execute(b.command(components));
    }

    private UiDependency row(String id) {
        return doc.archive().dependencies().find(id);
    }

    @Test
    void addConfigureAndRemoveARowFromAProjectFile() throws Exception {
        assertTrue(run("""
            {"ops":[{"op":"add_dependency","path":"UI/test/ui/textures/panel.png","optional":true},
                    {"op":"set_dependency","id":"test:ui/textures/panel","license":"CC0"}]}"""), doc.lastMessage());
        UiDependency r = row("test:ui/textures/panel");
        assertNotNull(r, "the convention id");
        assertEquals(UiDependency.Kind.IMAGE, r.kind());
        assertEquals(UiDependency.Mode.SHARED, r.mode());
        assertEquals("UI/test/ui/textures/panel.png", r.sourceHint());
        assertTrue(r.optional());
        assertEquals("CC0", r.license());
        assertEquals(1, doc.history().undoLabels().size(), "one batch, one undo step");
        OmuiWriter.write(doc.archive()); // the document still saves

        assertTrue(run("{\"ops\":[{\"op\":\"remove_dependency\",\"id\":\"test:ui/textures/panel\"}]}"));
        assertNull(row("test:ui/textures/panel"));
    }

    @Test
    void removingAReferencedRowIsRefusedUnlessForced() throws Exception {
        assertTrue(run("""
            {"ops":[{"op":"add_dependency","path":"UI/test/ui/textures/panel.png"},
                    {"op":"create","type":"Box","id":"bg","style":{"width":10,"height":10,
                     "background-image":"test:ui/textures/panel"}}]}"""), doc.lastMessage());
        OmuiArchive before = doc.archive();
        assertFalse(run("{\"ops\":[{\"op\":\"remove_dependency\",\"id\":\"test:ui/textures/panel\"}]}"));
        assertTrue(doc.lastMessage().contains("still used"), doc.lastMessage());
        assertEquals(before, doc.archive());
    }

    @Test
    void embedAndExtractWriteTheProjectAndUndoTogether() throws Exception {
        assertTrue(run("""
            {"ops":[{"op":"add_dependency","path":"UI/test/ui/textures/panel.png","embed":true}]}"""),
            doc.lastMessage());
        UiDependency embedded = row("test:ui/textures/panel");
        assertEquals(UiDependency.Mode.EMBEDDED, embedded.mode());
        assertNotNull(doc.archive().assets().get(embedded.entry()));

        // extract into a new place, then fail later in the same batch: the written file goes again
        Files.delete(project.resolve("UI/test/ui/textures/panel.png"));
        OmuiArchive before = doc.archive();
        assertFalse(run("""
            {"ops":[{"op":"extract_dependency","id":"test:ui/textures/panel"},
                    {"op":"set_prop","keys":"nope","prop":"text","value":"x"}]}"""));
        assertEquals(before, doc.archive());
        assertFalse(Files.exists(project.resolve("UI/test/ui/textures/panel.png")),
            "the failed batch's project write was reverted");

        assertTrue(run("{\"ops\":[{\"op\":\"extract_dependency\",\"id\":\"test:ui/textures/panel\"}]}"),
            doc.lastMessage());
        assertTrue(Files.isRegularFile(project.resolve("UI/test/ui/textures/panel.png")));
        assertEquals(UiDependency.Mode.SHARED, row("test:ui/textures/panel").mode());
        assertTrue(doc.undo(), doc.lastMessage());
        assertFalse(Files.exists(project.resolve("UI/test/ui/textures/panel.png")), "undo reverts the write");
    }

    @Test
    void relinkPointsARowAtAnotherProjectFile() throws Exception {
        Path other = png("Art/panel_v2.png", 0xFFAA0000);
        assertTrue(run("{\"ops\":[{\"op\":\"add_dependency\",\"path\":\"UI/test/ui/textures/panel.png\"}]}"));
        assertTrue(run("""
            {"ops":[{"op":"relink_dependency","id":"test:ui/textures/panel","path":"Art/panel_v2.png"}]}"""),
            doc.lastMessage());
        UiDependency r = row("test:ui/textures/panel");
        assertEquals("Art/panel_v2.png", r.sourceHint());
        assertArrayEquals(Files.readAllBytes(other), ctx.folder().read("Art/panel_v2.png").toArray());
    }

    @Test
    void pathsOutsideTheProjectAreRefused() throws Exception {
        assertFalse(run("{\"ops\":[{\"op\":\"add_dependency\",\"path\":\"../outside.png\"}]}"));
        assertTrue(doc.lastMessage().contains("outside the project"), doc.lastMessage());
    }

    @Test
    void graphsAndStateMachinesAreAuthoredByOps() throws Exception {
        assertTrue(run("""
            {"ops":[{"op":"create","type":"Button","id":"resume"},
                    {"op":"put_clip","clip":{"id":"lift","duration":0.2,"tracks":[]}},
                    {"op":"put_graph","graph":{"id":"behaviors","nodes":[],"edges":[]}},
                    {"op":"put_state_machine","machine":{"id":"button","driver":"interaction","element":"resume",
                     "initial":"normal","states":[{"name":"normal"},{"name":"hover","clip":"lift"}],
                     "transitions":[{"to":"hover","blend":0.1}]}}]}"""), doc.lastMessage());
        assertTrue(doc.archive().graphs().containsKey("behaviors"));
        assertTrue(doc.archive().stateMachines().containsKey("button"));
        OmuiWriter.write(doc.archive());

        assertTrue(run("""
            {"ops":[{"op":"remove_graph","id":"behaviors"},{"op":"remove_state_machine","id":"button"}]}"""),
            doc.lastMessage());
        assertTrue(doc.archive().graphs().isEmpty());
        assertTrue(doc.archive().stateMachines().isEmpty());
    }

    @Test
    void badGraphJsonIsATeachingFailure() throws Exception {
        assertFalse(run("{\"ops\":[{\"op\":\"put_graph\",\"graph\":{\"id\":\"Bad Id\"}}]}"));
        assertTrue(doc.lastMessage().contains("not a valid graph id"), doc.lastMessage());
        UiOpException e = org.junit.jupiter.api.Assertions.assertThrows(UiOpException.class,
            () -> UiOpBatch.parse(MAPPER.readTree("[{\"op\":\"add_dependency\"}]")));
        assertTrue(e.getMessage().contains("path") || e.getMessage().contains("id"), e.getMessage());
    }

    @Test
    void withoutAProjectFileOpsTeach() {
        UiCommandException e = org.junit.jupiter.api.Assertions.assertThrows(UiCommandException.class,
            () -> UiAssetOps.kind("bogus"));
        assertTrue(e.getMessage().contains("texture"), e.getMessage());
    }
}
