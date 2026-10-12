package com.stonebreak.ui.pauseMenu;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiMetrics;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.openmason.engine.ui.script.UiScriptServices;
import com.stonebreak.ui.runtime.GameUiDocuments;
import com.stonebreak.ui.runtime.GameUiInput;
import com.stonebreak.ui.DeathMenu;
import com.stonebreak.ui.runtime.screens.DocumentScreen;
import com.stonebreak.ui.runtime.screens.DocumentScreenHost;
import com.stonebreak.ui.runtime.screens.PresentedDocument;
import com.stonebreak.ui.runtime.screens.UiLayer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pausing while dead (#302): the pause menu opens above the death screen and takes the input; dying while
 * paused (online, where the world keeps running) puts the death screen above the pause menu. The modal pass
 * paints in {@link DocumentScreenHost#stackIndex} order, so the screen on top is the one that takes the clicks.
 */
class PauseDocumentTest {

    private final DocumentScreenHost host =
        new DocumentScreenHost(Set.of(PauseDocument.ID, DeathMenu.DOCUMENT_ID)::contains, this::open);
    private final PauseDocument pause = new PauseDocument(host);
    private final PresentedDocument death = new PresentedDocument(DeathMenu.DOCUMENT_ID, host, DocumentScreen.Options.screen());

    @AfterEach
    void closeAll() {
        host.closeAll();
    }

    private UiDocumentView open(String id, UiScriptServices s, UiLayer layer) throws Exception {
        UiNode root = new UiNode("root", null, "Box", 1, List.of(), Map.of(),
            Map.of("width", UiValue.of("100%"), "height", UiValue.of("100%")), null, List.of(), null, List.of(),
            Map.of());
        OmuiArchive doc = OmuiArchive.of(UiManifest.create("t:ui/" + id, UiManifest.DocumentKind.SCREEN, id),
            new UiDocument(root, List.of(), null, null, Map.of()));
        UiDocumentView v = GameUiDocuments.open(doc, () -> null, Map.of());
        v.instance().setMetrics(UiMetrics.of(400, 300, 1));
        v.instance().update();
        GameUiInput.get().open(v, layer);
        return v;
    }

    private UiDocumentView top() {
        List<UiDocumentView> views = GameUiInput.get().views();
        return views.get(views.size() - 1);
    }

    @Test
    void pausingWhileDeadOpensThePauseMenuAboveTheDeathScreen() {
        death.shown();
        pause.shown();
        assertTrue(host.isOpen(PauseDocument.ID), "paused while dead");
        assertTrue(host.stackIndex(PauseDocument.ID) > host.stackIndex(DeathMenu.DOCUMENT_ID), "painted above it");
        assertSame(host.find(PauseDocument.ID).orElseThrow().view(), top(), "and takes the input first");

        pause.hidden();
        assertFalse(host.isOpen(PauseDocument.ID));
        host.endFrame(); // closes are performed at frame end
        assertSame(host.find(DeathMenu.DOCUMENT_ID).orElseThrow().view(), top(), "resuming hands Respawn back");
    }

    @Test
    void dyingWhilePausedPutsTheDeathScreenOnTop() {
        pause.shown();
        death.shown();
        assertTrue(host.stackIndex(DeathMenu.DOCUMENT_ID) > host.stackIndex(PauseDocument.ID));
        assertSame(host.find(DeathMenu.DOCUMENT_ID).orElseThrow().view(), top());
    }

    @Test
    void aScreenThatIsNotOpenHasNoStackIndex() {
        assertEquals(-1, host.stackIndex(PauseDocument.ID));
    }
}
