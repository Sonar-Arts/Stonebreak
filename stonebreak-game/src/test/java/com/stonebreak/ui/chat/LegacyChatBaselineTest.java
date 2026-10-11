package com.stonebreak.ui.chat;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.GoldenStore;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Pixel baselines of the legacy chat (#301): the closed chat's fading lines, the open chat (lines with
 * inline and animated emoji, input with caret), scrolled history, the commands tab, completion ghost
 * text, the emoji picker with a favorite and recents, and a long input scrolled left, at 1920x1080; the
 * open chat at every standard viewport (it follows the UI scale).
 *
 * <p>Regenerate after an intended legacy change with {@code -Dui.fidelity.write=true} and review the
 * PNGs in {@code src/test/resources/ui/fidelity/chat/}.
 */
@Tag("regression")
class LegacyChatBaselineTest {

    static final GoldenStore STORE = GoldenStore.forTests("ui/fidelity/chat", "ui.fidelity.write");

    static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("chat", ChatFixtures.VARIANTS,
            List.of(FidelityCase.STANDARD.get(0))));
        out.addAll(FidelityCase.matrix("chat", List.of("open", "picker"), FidelityCase.STANDARD.subList(1, 4)));
        return out;
    }

    @Test
    void legacyChatMatchesItsBaselines() {
        LegacyChatCapture legacy = new LegacyChatCapture();
        for (FidelityCase c : cases()) {
            STORE.verify(c.id(), legacy.render(c).image(), PixelTolerance.RASTER_DRIFT);
        }
    }
}
