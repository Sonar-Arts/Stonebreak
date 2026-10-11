package com.stonebreak.ui.characterScreen;

import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.chat.ChatDocumentGateTest;
import com.stonebreak.ui.fidelity.GateAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The character sheet migration gate (#301): {@code ui/documents/character_sheet.sbui} against the legacy
 * {@code CharacterRenderCoordinator} (panel, tab strip, tiles, class rows, buttons and feat rows {@code FLOAT_EXACT},
 * pixels {@code EXACT}; the 3D preview is GL and absent on both raster sides).
 */
@Tag("regression")
class CharacterSheetDocumentGateTest {

    @Test
    void theShippedCharacterSheetPassesTheMigrationGate() throws Exception {
        ChatDocumentGateTest.requireLua();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("character-sheet", CharacterSheetFixtures.cases(), new LegacyCharacterSheetCapture(),
                DocumentCharacterSheetCapture.shipped());
        GateAssert.passed(r);
    }
}
