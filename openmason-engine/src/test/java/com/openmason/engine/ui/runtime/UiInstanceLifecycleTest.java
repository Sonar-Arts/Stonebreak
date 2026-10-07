package com.openmason.engine.ui.runtime;

import com.openmason.engine.cenda.CendaFlex;
import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiRuntimeDiagnostic.Code;
import org.junit.jupiter.api.Test;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Instance lifecycle and bookkeeping edges (#282 hardening): duplicates, close, layout findings, sub-pixel slides. */
class UiInstanceLifecycleTest {

    @Test
    void theSameDuplicateKeyIsRefusedEveryTimeNotJustTheFirst() {
        UiDocumentInstance ui = UiDocumentInstance.instantiate(screen("t:ui/dup", box("root").kids(box("a"))),
            UiRuntimeContext.basic());
        UiElement original = ui.find("a");
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThrows(IllegalArgumentException.class, () -> ui.root().insertChild(-1, box("a").build()));
            assertEquals(original, ui.find("a"), "the original keeps its key (attempt " + attempt + ")");
            assertEquals(1, ui.root().children().size());
        }
    }

    @Test
    void anInstanceClosedBeforeItsFirstLayoutNeverBuildsANativeTree() {
        UiDocumentInstance ui = UiDocumentInstance.instantiate(screen("t:ui/early", box("root")),
            UiRuntimeContext.basic());
        assertFalse(ui.isClosed());
        ui.close();
        assertTrue(ui.isClosed(), "closed even though it never laid out");
        assertEquals(UiDocumentInstance.UpdateStats.NONE.laidOut(), ui.update().laidOut());
        assertTrue(ui.isClosed());
        ui.close(); // idempotent
    }

    @Test
    void layoutFindingsFollowTheCurrentStylesNotTheFirstOnes() {
        assumeTrue(CendaFlex.isAvailable(), "Cenda library with the retained flex ABI not built");
        OmuiArchive doc = screen("t:ui/findings", box("root").kids(
            box("row").style("flex-direction", "row").style("align-self", "flex-start")
                .kids(box("pct").style("width", "50%").style("height", 10)),
            box("conflict").style("min-width", 100).style("max-width", 50)));
        try (UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic())) {
            ui.setMetrics(UiMetrics.of(800, 600, 1));
            ui.update();
            assertTrue(reported(ui, Code.CYCLIC_PERCENTAGE, "pct"));
            assertTrue(reported(ui, Code.CONFLICTING_CONSTRAINTS, "conflict"));

            ui.find("conflict").setStyle("max-width", UiValue.of(200));
            ui.update();
            assertFalse(reported(ui, Code.CONFLICTING_CONSTRAINTS, "conflict"), "a fixed conflict stops being reported");

            // Only the PARENT changes: the child's own record is untouched, its percentage is now definite.
            ui.find("row").setStyle("width", UiValue.of(400));
            ui.update();
            assertFalse(reported(ui, Code.CYCLIC_PERCENTAGE, "pct"), "re-checked through the parent's change");
            ui.find("row").clearStyle("width");
            ui.update();
            assertTrue(reported(ui, Code.CYCLIC_PERCENTAGE, "pct"));
        }
    }

    private static boolean reported(UiDocumentInstance ui, Code code, String key) {
        return ui.diagnostics().stream().anyMatch(d -> d.code() == code && d.element().equals(key));
    }

    @Test
    void subPixelModeLetsAnimatedTranslationsGlideWhileStaticOnesStaySnapped() {
        assumeTrue(CendaFlex.isAvailable(), "Cenda library with the retained flex ABI not built");
        OmuiArchive doc = screen("t:ui/slide", box("root").kids(
            box("slide").style("width", 10).style("height", 10).kids(box("child").style("width", 4).style("height", 4)),
            box("still").style("width", 10).style("height", 10).style("translate-x", 2.4)));
        try (UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic())) {
            ui.setMetrics(UiMetrics.of(200, 100, 1));
            ui.update();
            ui.find("slide").setAnimatedStyle("translate-x", UiValue.of(3.3));
            ui.update();
            assertEquals(3, ui.find("slide").rect().x(), 1e-6, "snapped by default");
            ui.setSubpixelAnimation(true);
            ui.update();
            assertEquals(3.3, ui.find("slide").rect().x(), 1e-4, "glides when enabled");
            assertEquals(3.3, ui.find("child").rect().x(), 1e-4, "its subtree moves with it");
            assertEquals(2, ui.find("still").rect().x(), 1e-6, "static translations stay on whole pixels");
            ui.find("slide").clearAnimatedStyle("translate-x");
            ui.update();
            assertEquals(0, ui.find("slide").rect().x(), 1e-6);
        }
    }
}
