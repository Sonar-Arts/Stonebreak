package com.stonebreak.ui.characterScreen;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.runtime.UiRect;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.rpg.CharacterPanelTab;
import com.stonebreak.rpg.classes.ClassRegistry;
import com.stonebreak.rpg.feats.FeatRegistry;
import com.stonebreak.rpg.skills.SkillRegistry;
import com.stonebreak.ui.chat.ChatDocumentGateTest;
import com.stonebreak.ui.fidelity.DocumentStage;
import com.stonebreak.ui.fidelity.LegacyUiRaster;
import com.stonebreak.ui.runtime.GameUiDocuments;
import org.joml.Vector2f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The character sheet document's presses (#301) run the legacy rules through the screen's own renderers: tabs,
 * ability [+]/[-], class rows, Spend CP, +1 SP, the feat level filter (left forward, right back), General, a feat
 * row, Acquire, and the wheel over the class list, the ability list and the feat list.
 */
@Tag("regression")
class CharacterSheetDocumentInteractionTest {

    private static SbuiArchive shipped;

    @BeforeAll
    static void load() throws Exception {
        ChatDocumentGateTest.requireLua();
        shipped = GameUiDocuments.readScreen(CharacterScreen.DOCUMENT_ID);
    }

    private static void click(DocumentStage stage, String name, int button) {
        UiRect r = DocumentCharacterSheetCapture.element(stage, name).rect();
        float x = r.x() + r.width() / 2f;
        float y = r.y() + r.height() / 2f;
        stage.view.input().pointerMove(x, y);
        stage.view.input().pointerDown(x, y, button, 0);
        stage.view.input().pointerUp(x, y, button, 0);
        stage.settle();
        stage.host.characterSheet().poll();
        stage.settle();
    }

    private static void click(DocumentStage stage, String name) {
        click(stage, name, 0);
    }

    @Test
    void pressesRunTheLegacyRules() throws Exception {
        try (LegacyUiRaster fonts = new LegacyUiRaster(1920, 1080, 1f)) {
            CharacterScreen screen = CharacterSheetFixtures.screen(fonts.backend(), new Vector2f(-100, -100));
            CharacterStats stats = screen.coordinator().stats();
            var controller = screen.getController();
            try (DocumentStage stage = DocumentCharacterSheetCapture.stage(shipped, screen, 1920, 1080, 1f)) {
                int str = stats.getAbilityScores()[2];
                click(stage, "plus2");
                assertEquals(str + 1, stats.getAbilityScores()[2]);

                click(stage, "tab2");
                assertEquals(CharacterPanelTab.CLASSES, controller.getActiveTab());
                click(stage, "cls2");
                assertEquals(ClassRegistry.ALL.get(2).id(), screen.coordinator().classes().displayedClassId());
                int cp = stats.getRemainingCp();
                UiRect spend = stage.view.instance().qAll(".spend").get(2 * 3).rect();
                stage.clickAt(spend.x() + 5, spend.y() + 5);
                assertEquals(cp - 1, stats.getRemainingCp(), "Spend CP on the fourth ability");
                assertEquals(1, stats.getSpentCp(ClassRegistry.ALL.get(2).id() + ":3"));

                // the wheel reaches the class list over the sidebar and the ability list elsewhere; both
                // fit their clips with the shipped registries, so the legacy clamp keeps them at the top
                UiRect side = DocumentCharacterSheetCapture.element(stage, "cls0").rect();
                stage.view.input().wheel(side.x() + 5, side.y() + 5, 0, 3, 0);
                stage.settle();
                assertEquals(0f, screen.coordinator().classes().leftScroll());
                UiRect panel = stage.q("#panel").rect();
                stage.view.input().wheel(panel.x() + panel.width() - 50, panel.y() + 200, 0, 1, 0);
                stage.settle();
                assertEquals(0f, screen.coordinator().classes().rightScroll());

                click(stage, "tab3");
                String skill = SkillRegistry.ALL.get(4).id();
                int level = stats.getSkillLevel(skill);
                click(stage, "invest4");
                assertEquals(level + 1, stats.getSkillLevel(skill));

                click(stage, "tab4");
                var feats = screen.coordinator().feats();
                click(stage, "level");
                assertEquals(1, feats.filterLevel());
                click(stage, "level", 1);
                assertEquals(0, feats.filterLevel(), "a right press steps back");
                click(stage, "general");
                assertTrue(feats.filterGeneral());
                click(stage, "general");
                assertFalse(feats.filterGeneral());
                click(stage, "feat0");
                String first = FeatRegistry.filter(0, false).get(0).id();
                assertEquals(first, feats.selectedFeatId());
                click(stage, "acquire");
                assertTrue(stats.hasFeat(first));

                click(stage, "tab1");
                assertEquals(CharacterPanelTab.OVERVIEW, controller.getActiveTab());
            }
        }
    }

    @Test
    void actionsAreRefusedWhileTheSheetIsClosed() throws Exception {
        try (LegacyUiRaster fonts = new LegacyUiRaster(1920, 1080, 1f)) {
            CharacterScreen screen = CharacterSheetFixtures.screen(fonts.backend(), new Vector2f(-100, -100));
            try (DocumentStage stage = new DocumentStage(shipped, 1920, 1080, 1f,
                    new com.stonebreak.ui.fidelity.RecordingServices(),
                    com.stonebreak.network.MultiplayerSession.Mode.SINGLEPLAYER,
                    java.util.Map.of(com.stonebreak.ui.runtime.providers.EntityPreviewProvider.ID, DocumentStage.NO_GL))) {
                stage.clickAt(960, 540);
                assertEquals(CharacterPanelTab.OVERVIEW, screen.getController().getActiveTab());
                assertEquals(java.util.List.of(), stage.services.calls);
            }
        }
    }
}
