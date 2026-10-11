package com.stonebreak.ui.characterCreation.fidelity;

import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.chat.ChatDocumentGateTest;
import com.stonebreak.ui.fidelity.GateAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The character creation migration gate (#301): {@code ui/documents/character_creation.sbui} against the legacy
 * {@code SkijaCharacterCreationRenderer} (panels, tabs, rows, tiles and buttons {@code FLOAT_EXACT}, pixels
 * {@code EXACT}; the 3D preview is GL and absent on both sides).
 */
@Tag("regression")
class CharacterCreationDocumentGateTest {

    @Test
    void theShippedCharacterCreationPassesTheMigrationGate() throws Exception {
        ChatDocumentGateTest.requireLua();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("character-creation", CharacterCreationFixtures.cases(), new LegacyCharacterCreationCapture(),
                DocumentCharacterCreationCapture.shipped());
        GateAssert.passed(r);
    }
}
