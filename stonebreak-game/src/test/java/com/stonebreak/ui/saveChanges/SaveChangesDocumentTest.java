package com.stonebreak.ui.saveChanges;

import com.stonebreak.ui.chat.ChatDocumentGateTest;
import com.stonebreak.ui.fidelity.DocumentStage;
import com.stonebreak.ui.runtime.GameUiDocuments;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The "Save changes?" prompt (Daedalus) as its shipped {@code ui/documents/save_changes.sbui}: Yes and No reach
 * their host actions, the dim around the panel and the title take a press and do nothing (the prompt is modal),
 * and the panel scales with the UI.
 */
@Tag("regression")
class SaveChangesDocumentTest {

    @Test
    void yesAndNoReachTheirActionsAndNothingElseDoes() throws Exception {
        ChatDocumentGateTest.requireLua();
        try (DocumentStage stage = new DocumentStage(GameUiDocuments.readScreen(SaveChangesDialog.DOCUMENT_ID),
                1920, 1080, 1f)) {
            assertEquals(List.of("stonebreak:screen.save-changes.yes"), stage.click("yes"));
            assertEquals(List.of("stonebreak:screen.save-changes.no"), stage.click("no"));
            assertEquals(List.of(), stage.clickAt(20, 20), "the dim");
            assertEquals(List.of(), stage.click("title"));
            float[] panel = stage.rects(List.of("panel")).get("panel");
            assertEquals(760f, panel[0], 1e-3, "400 wide, centred");
            assertEquals(455f, panel[1], 1e-3, "170 high, centred");
        }
    }

    @Test
    void thePanelScalesWithTheUi() throws Exception {
        ChatDocumentGateTest.requireLua();
        try (DocumentStage stage = new DocumentStage(GameUiDocuments.readScreen(SaveChangesDialog.DOCUMENT_ID),
                1920, 1080, 2f)) {
            float[] r = stage.rects(List.of("panel", "yes")).get("panel");
            assertEquals(800f, r[2], 1e-3);
            assertEquals(340f, r[3], 1e-3);
            float[] yes = stage.rects(List.of("yes")).get("yes");
            assertEquals(r[0] + 58f * 2f, yes[0], 1e-3, "the button pair centred in the panel");
            assertEquals(r[1] + (170f - 42f - 22f) * 2f, yes[1], 1e-3, "22 above the panel's foot");
        }
    }
}
