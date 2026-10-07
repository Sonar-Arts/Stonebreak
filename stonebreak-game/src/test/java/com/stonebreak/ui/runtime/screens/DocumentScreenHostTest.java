package com.stonebreak.ui.runtime.screens;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The game-side lifecycle of document screens: open, one ordered stack, frame-end close and navigation. */
class DocumentScreenHostTest {

    private final List<String> opened = new ArrayList<>();
    private final List<UiScriptServices> services = new ArrayList<>();
    private final DocumentScreenHost host = new DocumentScreenHost(
        new DocumentScreenPolicy(null, Set.of("pause", "settings", "furnace", "broken")::contains), this::open);

    @AfterEach
    void closeAll() {
        host.closeAll();
    }

    private UiDocumentView open(String id, UiScriptServices s, UiLayer layer) throws Exception {
        if (id.equals("broken")) {
            throw new IllegalStateException("activation: missing contract stonebreak:nothing");
        }
        opened.add(id);
        services.add(s);
        UiNode button = new UiNode("b", null, "Button", 1, List.of(), Map.of(),
            Map.of("position", UiValue.of("absolute"), "left", UiValue.of(10), "top", UiValue.of(10),
                "width", UiValue.of(50), "height", UiValue.of(20)), null, List.of(), null, List.of(), Map.of());
        UiNode root = new UiNode("root", null, "Box", 1, List.of(), Map.of(),
            Map.of("width", UiValue.of("100%"), "height", UiValue.of("100%")), null, List.of(), null, List.of(button),
            Map.of());
        OmuiArchive doc = OmuiArchive.of(UiManifest.create("t:ui/" + id, UiManifest.DocumentKind.SCREEN, id),
            new UiDocument(root, List.of(), null, null, Map.of()));
        UiDocumentView v = GameUiDocuments.open(doc, () -> null, Map.of());
        v.instance().setMetrics(UiMetrics.of(400, 300, 1));
        v.instance().update();
        GameUiInput.get().open(v, layer); // as openBound does
        return v;
    }

    @Test
    void unshippedOrRefusedScreensStayLegacy() {
        assertTrue(host.open("inventory", DocumentScreen.Options.screen()).isEmpty(), "no export ships");
        assertTrue(host.open("broken", DocumentScreen.Options.screen()).isEmpty(), "a gate refused it");
        assertEquals(List.of(), opened);
        assertTrue(host.screens().isEmpty());
        host.policy().setOverride("pause", true);
        assertTrue(host.open("pause", DocumentScreen.Options.screen()).isEmpty(), "rolled back");
    }

    @Test
    void anOpenScreenJoinsTheStackInItsLayerAndFreesThePointer() {
        DocumentScreen overlay = host.open("settings", DocumentScreen.Options.overlay()).orElseThrow();
        DocumentScreen pause = host.open("pause", DocumentScreen.Options.screen()).orElseThrow();
        assertSame(pause, host.open("pause", DocumentScreen.Options.screen()).orElseThrow(), "already open");
        assertEquals(List.of(pause.view(), overlay.view()), GameUiInput.get().views(), "screen below overlay");
        assertTrue(host.needsPointer());
        assertEquals(List.of("settings", "pause"), opened);
    }

    @Test
    void closeRequestsWaitForTheEndOfTheFrame() {
        boolean[] after = {false};
        DocumentScreen pause = host.open("pause", DocumentScreen.Options.screen().withOnClosed(() -> after[0] = true))
            .orElseThrow();
        services.getFirst().requestClose(); // a script's ui.close(), mid-dispatch
        assertTrue(pause.isClosing());
        assertTrue(GameUiInput.get().isOpen(pause.view()), "nothing is torn down under the dispatch");
        assertFalse(pause.view().instance().isClosed());
        assertFalse(after[0]);
        host.endFrame();
        assertTrue(pause.isClosed());
        assertFalse(GameUiInput.get().isOpen(pause.view()), "out of the input stack");
        assertTrue(pause.view().instance().isClosed(), "and disposed");
        assertTrue(after[0]);
        assertFalse(host.needsPointer());
        assertTrue(host.find("pause").isEmpty());
    }

    @Test
    void navigationReplacesTheAskingScreenAtFrameEnd() {
        DocumentScreen pause = host.open("pause", DocumentScreen.Options.screen()).orElseThrow();
        assertTrue(services.getFirst().navigate("settings", new UiValue.Obj(Map.of())));
        assertTrue(host.find("settings").isEmpty(), "queued");
        host.endFrame();
        assertTrue(pause.isClosed());
        DocumentScreen settings = host.find("settings").orElseThrow();
        assertEquals(UiLayer.SCREEN, settings.options().layer(), "the target takes the asker's place");
    }

    @Test
    void pushNavigationKeepsTheAskingScreen() {
        DocumentScreen pause = host.open("pause", DocumentScreen.Options.screen()).orElseThrow();
        assertTrue(host.requestNavigate(pause, "furnace", new UiValue.Obj(Map.of("push", UiValue.TRUE))));
        host.endFrame();
        assertFalse(pause.isClosed());
        assertTrue(host.isOpen("furnace"));
        assertEquals(List.of(pause.view(), host.find("furnace").orElseThrow().view()), GameUiInput.get().views());
    }

    @Test
    void unknownTargetsAreRefusedAndLegacyOnesAccepted() {
        DocumentScreen pause = host.open("pause", DocumentScreen.Options.screen()).orElseThrow();
        assertFalse(services.getFirst().navigate("nowhere", new UiValue.Obj(Map.of())), "the script gets an error");
        assertTrue(LegacyNavigation.has("glossary"));
        assertTrue(host.requestNavigate(null, "glossary", null), "a screen that has not migrated yet");
        assertFalse(pause.isClosing());
    }

    @Test
    void leavingTheWorldClosesPerWorldScreensOnly() {
        DocumentScreen pause = host.open("pause", DocumentScreen.Options.screen()).orElseThrow();
        DocumentScreen overlay = host.open("settings", DocumentScreen.Options.overlay()).orElseThrow();
        host.worldLeft();
        host.endFrame();
        assertTrue(pause.isClosed());
        assertFalse(overlay.isClosed());
    }

    @Test
    void anAdoptedViewSharesTheLifecycle() throws Exception {
        DocumentScreen dev = host.reserve("dev:x", DocumentScreen.Options.overlay());
        UiScriptServices s = host.services(dev);
        UiDocumentView v = open("dev", s, UiLayer.OVERLAY);
        host.adopt(dev, v);
        assertTrue(GameUiInput.get().isOpen(v));
        s.requestClose();
        host.endFrame();
        assertFalse(GameUiInput.get().isOpen(v), "a closed overlay no longer eats input (#282 review)");
        assertTrue(v.instance().isClosed());
    }

    @Test
    void worldScopedScreensNeverOpenBeforeAWorldRuns() {
        boolean[] world = {false};
        DocumentScreenHost h = new DocumentScreenHost(
            new DocumentScreenPolicy(null, Set.of("pause", "settings")::contains), this::open, () -> world[0]);
        try {
            assertTrue(h.open("pause", DocumentScreen.Options.screen()).isEmpty(), "under the intro or a menu");
            assertEquals(List.of(), opened);
            assertTrue(h.open("settings", DocumentScreen.Options.menu()).isPresent(), "shell menus open anywhere");
            world[0] = true;
            assertTrue(h.open("pause", DocumentScreen.Options.screen()).isPresent());
        } finally {
            h.closeAll();
        }
    }

    @Test
    void scheduledClicksWaitUntilArmed() throws Exception {
        boolean[] armed = {false};
        DocumentScreen dev = host.reserve("dev:clicks", DocumentScreen.Options.overlay());
        UiDocumentView v = open("dev", host.services(dev), UiLayer.OVERLAY);
        host.adopt(dev, v);
        int[] clicks = {0};
        v.instance().find("b").on(com.openmason.engine.ui.runtime.input.UiEventType.CLICK, e -> clicks[0]++);
        host.attachAutoClick(dev, com.openmason.engine.ui.runtime.input.UiAutoClick.parse("b@0"), () -> armed[0]);
        for (int i = 0; i < 3; i++) {
            host.frame();
        }
        assertEquals(0, clicks[0], "the dev overlay never clicks a pause action under the intro (#282 final run)");
        armed[0] = true;
        host.frame();
        host.frame();
        assertEquals(1, clicks[0], "once the world runs, the schedule starts");
    }
}
