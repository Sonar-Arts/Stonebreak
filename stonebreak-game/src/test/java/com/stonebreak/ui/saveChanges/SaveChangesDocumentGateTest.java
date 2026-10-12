package com.stonebreak.ui.saveChanges;

import com.openmason.engine.ui.fidelity.FrozenCaptures;
import com.openmason.engine.ui.fidelity.GeometryRule;
import com.openmason.engine.ui.fidelity.MigrationGate;
import com.openmason.engine.ui.fidelity.PixelTolerance;
import com.stonebreak.ui.chat.ChatDocumentGateTest;
import com.stonebreak.ui.fidelity.GateAssert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The "Save changes?" migration gate (Daedalus): {@code ui/documents/save_changes.sbui} against the frozen captures of
 * the branch's legacy {@code SkijaSaveChangesDialogRenderer} (panel and buttons {@code FLOAT_EXACT}, pixels
 * {@code EXACT}).
 */
@Tag("regression")
class SaveChangesDocumentGateTest {

    @Test
    void theShippedSaveChangesDocumentPassesTheMigrationGate() throws Exception {
        ChatDocumentGateTest.requireLua();
        MigrationGate.Report r = new MigrationGate(GeometryRule.FLOAT_EXACT, c -> PixelTolerance.EXACT)
            .run("save-changes", DocumentSaveChangesCapture.cases(), FrozenCaptures.forTests("save-changes").replay(),
                DocumentSaveChangesCapture.shipped());
        GateAssert.passed(r);
    }
}
