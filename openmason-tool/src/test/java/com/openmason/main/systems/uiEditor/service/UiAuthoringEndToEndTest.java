package com.openmason.main.systems.uiEditor.service;

import com.openmason.engine.format.omui.OmuiReader;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.format.sbui.SbuiReader;
import com.openmason.engine.ui.assets.export.ExportMode;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.layout.HitTester;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.openmason.main.systems.uiEditor.canvas.Box;
import com.openmason.main.systems.uiEditor.canvas.DropTargets;
import com.openmason.main.systems.uiEditor.command.DocumentCommands;
import com.openmason.main.systems.uiEditor.command.NodeCommands;
import com.openmason.main.systems.uiEditor.command.UiCommand;
import com.openmason.main.systems.uiEditor.document.NodeLocation;
import com.openmason.main.systems.uiEditor.document.UiEditorDocument;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.GameUiResources;
import io.github.humbleui.skija.Typeface;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Author a screen with editor commands, save the OMUI, export the SBUI, load it the way the game
 * does, and check the canvas geometry the designer relies on (#293 validation).
 */
class UiAuthoringEndToEndTest {

    private static Typeface typeface;

    @TempDir
    Path project;

    @BeforeAll
    static void font() throws Exception {
        typeface = GameUiResources.loadTypeface();
    }

    @AfterAll
    static void release() {
        typeface.close();
    }

    @Test
    void authorSaveExportLoad() throws Exception {
        UiProjectContext ctx = new UiProjectContext(() -> project);
        UiDocumentService service = new UiDocumentService(ctx, new UiRecoveryService(project.resolve(".rec")));

        // a component, saved first so the screen can place it from the project
        UiEditorDocument button = service.create(UiDocumentTemplates.BUTTON_COMPONENT, "test:ui/components/big_button",
            "Big Button");
        assertNull(service.save(button));

        UiEditorDocument doc = service.create(UiDocumentTemplates.BLANK_SCREEN, "test:ui/screens/hud", "HUD");
        assertTrue(doc.execute(NodeCommands.create("Box", NodeLocation.endOf("root"), n -> n)));
        String panel = doc.primary();
        assertTrue(doc.execute(UiCommand.compound("Style panel", List.of(
            NodeCommands.setStyle(List.of(panel), Map.of("width", UiValue.of(400), "height", UiValue.of(300),
                "align-items", UiValue.of("center"), "row-gap", UiValue.of(12)), "size"),
            NodeCommands.rename(panel, "panel")))));
        assertTrue(doc.execute(NodeCommands.create("Label", NodeLocation.endOf(panel), null)));
        String label = doc.primary();
        assertTrue(doc.execute(NodeCommands.setBinding(label, "prop:text", new UiNode.UiBinding("prop:text", "session.name"))));
        assertTrue(doc.execute(DocumentCommands.addStyleSheet("hud")));
        assertTrue(doc.execute(DocumentCommands.addRule("hud", "#panel", Map.of("background-color", UiValue.of("#203040")))));
        var entry = ctx.entries(true).stream().filter(e -> "test:ui/components/big_button".equals(e.documentId()))
            .findFirst().orElseThrow();
        assertTrue(doc.execute(DocumentCommands.addInstance("test:ui/components/big_button",
            ctx.sharedRow("test:ui/components/big_button", com.openmason.engine.format.omui.UiDependency.Kind.COMPONENT,
                entry.path()), NodeLocation.endOf(panel))));
        String instance = doc.primary();

        assertNull(service.save(doc));
        assertEquals(doc.archive(), OmuiReader.read(doc.file()).archive());

        UiDocumentService.ExportResult export = service.export(doc, project.resolve("Exports/hud.sbui"), ExportMode.COLLECT_ALL);
        assertNull(export.error(), export.error() + " " + export.diagnostics());
        assertTrue(Files.isRegularFile(project.resolve("Exports/hud.report.json")));

        SbuiArchive sbui = SbuiReader.read(export.target(), SbuiReader.Options.RUNTIME).archive();
        try (UiDocumentView view = GameUiDocuments.open(sbui, Map.of(), () -> typeface, Map.of())) {
            UiSnapshot.render(view, typeface, 1280, 720, 1f);
            UiDocumentInstance ui = view.instance();
            UiElement p = ui.find(panel);
            assertNotNull(p);
            assertEquals(400, p.rect().width(), 0.5f);
            assertNotNull(ui.find(instance + "/button"), "the component instantiated from the collected copy");
            assertTrue(ui.diagnostics().stream().noneMatch(d -> d.code().name().contains("MISSING")), ui.diagnostics().toString());
        }
    }

    @Test
    void canvasGeometryScalesAndPicksLikeTheDesigner() throws Exception {
        UiProjectContext ctx = new UiProjectContext(() -> project);
        var doc = OmuiReader.read(UiDocumentServiceTest.FIXTURES.resolve("pause_menu.omui")).archive();
        for (float scale : new float[]{1f, 2f}) {
            try (UiDocumentView view = GameUiDocuments.open(doc, ctx.sources(), () -> typeface, Map.of())) {
                int w = (int) (1280 * scale);
                int h = (int) (720 * scale);
                BufferedImage img = UiSnapshot.render(view, typeface, w, h, scale);
                UiDocumentInstance ui = view.instance();
                UiElement panel = ui.find("panel");
                assertEquals(520 * scale, panel.rect().width(), 0.5f, "frame px = logical px x scale");
                Box b = Box.of(panel.rect());
                assertEquals(w / 2f, b.cx(), 1f, "centred by its flex parent");

                UiElement picked = HitTester.pickDesign(ui.paintOrder(), b.cx(), b.t() + 4 * scale, e -> true);
                assertNotNull(picked);
                assertEquals("panel", picked.key(), "design picking ignores picking-mode and hits the panel's own rect");
                UiElement title = ui.find("title");
                UiElement onTitle = HitTester.pickDesign(ui.paintOrder(), title.rect().x() + 2, title.rect().y() + 2,
                    e -> true);
                assertEquals("title", onTitle.key());
                UiElement inside = HitTester.pickDesign(ui.paintOrder(), ui.find("quit").rect().x() + 4 * scale,
                    ui.find("quit").rect().y() + 4 * scale, e -> true);
                assertTrue(inside.key().startsWith("quit"), inside.key());

                DropTargets.Drop drop = DropTargets.resolve(ui, doc.document().root(), b.cx(), b.t() + 2, Set.of());
                assertNotNull(drop);
                assertEquals("panel", drop.location().parentId());
                assertEquals(0, drop.location().index(), "above the first child");
                DropTargets.Drop end = DropTargets.resolve(ui, doc.document().root(), b.cx(), b.b() - 2, Set.of("quit"));
                assertEquals("panel", end.location().parentId());

                Set<Integer> colors = new java.util.HashSet<>();
                for (int y = (int) title.rect().y(); y < (int) title.rect().bottom(); y++) {
                    for (int x = (int) title.rect().x(); x < (int) title.rect().right(); x++) {
                        colors.add(img.getRGB(x, y));
                    }
                }
                assertTrue(colors.size() > 2, "the title's glyphs are painted into the frame");
                assertNotEquals(img.getRGB(2, 2), img.getRGB((int) b.cx(), (int) b.t() + 2), "the panel is painted");
            }
        }
    }
}
