package com.stonebreak.ui.startupIntro;

import com.openmason.engine.ui.fidelity.FidelityCase;
import com.openmason.engine.ui.fidelity.GoldenStore;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Pixel baselines of the legacy startup intro (#301), the first deterministic captures of it: a seeded
 * scene at six moments of its timeline at 1920x1080, and the revealed logo at the other standard
 * viewports (the intro scales itself to fit 1920x1080 and ignores the UI scale).
 *
 * <p>Regenerate after an intended legacy change with {@code -Dui.fidelity.write=true} and review the
 * PNGs in {@code src/test/resources/ui/fidelity/intro/}.
 */
@Tag("regression")
class LegacyIntroBaselineTest {

    static final GoldenStore STORE = GoldenStore.forTests("ui/fidelity/intro", "ui.fidelity.write");

    static List<FidelityCase> cases() {
        List<FidelityCase> out = new ArrayList<>(FidelityCase.matrix("intro", IntroFixtures.VARIANTS,
            List.of(FidelityCase.STANDARD.get(0))));
        out.addAll(FidelityCase.matrix("intro", List.of("t2-4"), FidelityCase.STANDARD.subList(1, 4)));
        return out;
    }

    @Test
    void legacyIntroMatchesItsBaselines() {
        LegacyIntroCapture legacy = new LegacyIntroCapture();
        for (FidelityCase c : cases()) {
            STORE.verify(c.id(), legacy.render(c).image(), PixelTolerance.RASTER_DRIFT);
        }
    }
}
