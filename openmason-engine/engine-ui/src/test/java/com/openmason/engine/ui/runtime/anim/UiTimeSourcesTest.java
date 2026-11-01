package com.openmason.engine.ui.runtime.anim;

import com.openmason.engine.format.omui.UiAnimationClip;
import com.openmason.engine.format.omui.UiAnimationClip.LoopMode;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import org.junit.jupiter.api.Test;

import java.util.function.UnaryOperator;

import static com.openmason.engine.ui.runtime.UiDocs.box;
import static com.openmason.engine.ui.runtime.UiDocs.screen;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.clip;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.frame;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.key;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.num;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.run;
import static com.openmason.engine.ui.runtime.anim.AnimDocs.track;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** UI, game and external clocks (#295): paused gameplay never freezes UI-only animation. */
class UiTimeSourcesTest {

    private static final UnaryOperator<String> SCREEN = k -> k;

    private static UiAnimationClip slide(String id, String target) {
        return clip(id, 1, LoopMode.ONCE, track(target, "style:translate-x", key(0, 0), key(1, 100)));
    }

    private static UiDocumentInstance twoPanels() {
        return run(screen("t:ui/t", box("root").kids(box("menu"), box("world_hint"))));
    }

    private static UiAnimator.PlayOptions on(String clock) {
        return new UiAnimator.PlayOptions(clock, 1, null, 0, UiAnimator.Fill.HOLD, 0, true);
    }

    @Test
    void pausedGameplayFreezesGameClipsButNotUiOnes() {
        UiDocumentInstance ui = twoPanels();
        ui.animator().play(null, slide("menu_in", "menu"), SCREEN, on(UiClocks.UI), null);
        ui.animator().play(null, slide("hint", "world_hint"), SCREEN, on(UiClocks.GAME), null);
        // Running: the host advances both clocks by the frame time.
        ui.clocks().advance(UiClocks.GAME, 0.25);
        frame(ui, 0.25);
        assertEquals(25, num(ui, "menu", "translate-x"), 1e-6);
        assertEquals(25, num(ui, "world_hint", "translate-x"), 1e-6);
        // Paused: only the UI clock advances (GameUiDocuments.frame with gameRunning = false).
        frame(ui, 0.5);
        assertEquals(75, num(ui, "menu", "translate-x"), 1e-6, "the pause menu keeps animating");
        assertEquals(25, num(ui, "world_hint", "translate-x"), 1e-6, "gameplay feedback waits for the game");
    }

    @Test
    void externalClocksSampleTheHostsDeterministicTime() {
        UiDocumentInstance ui = twoPanels();
        ui.clocks().define("battle");
        ui.clocks().set("battle", 10);
        ui.animator().play(null, slide("strike", "menu"), SCREEN, on("battle"), null);
        frame(ui, 3); // UI frames alone do nothing to an encounter clip
        assertEquals(0, num(ui, "menu", "translate-x"), 1e-9);
        ui.clocks().set("battle", 10.4); // slow-motion or not, the encounter says where it is
        frame(ui, 0);
        assertEquals(40, num(ui, "menu", "translate-x"), 1e-6);
        ui.clocks().set("battle", 10.1); // a replay rewinds
        frame(ui, 0);
        assertEquals(10, num(ui, "menu", "translate-x"), 1e-6);
    }

    @Test
    void unknownClocksAreRefusedAndTheUiClockFollowsFramesOnly() {
        UiDocumentInstance ui = twoPanels();
        assertThrows(IllegalArgumentException.class,
            () -> ui.animator().play(null, slide("x", "menu"), SCREEN, on("intro"), null));
        assertThrows(IllegalArgumentException.class, () -> ui.clocks().set(UiClocks.UI, 5));
        ui.clocks().advance(UiClocks.UI, -1);
        ui.clocks().advance(UiClocks.UI, Double.NaN);
        assertEquals(0, ui.clock(), "non-positive and non-finite steps are ignored");
    }
}
