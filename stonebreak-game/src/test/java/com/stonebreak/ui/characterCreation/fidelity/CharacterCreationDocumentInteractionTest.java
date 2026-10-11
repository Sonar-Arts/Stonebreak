package com.stonebreak.ui.characterCreation.fidelity;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.runtime.UiRect;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.rpg.backgrounds.BackgroundRegistry;
import com.stonebreak.rpg.classes.ClassRegistry;
import com.stonebreak.rpg.feats.FeatRegistry;
import com.stonebreak.rpg.skills.SkillRegistry;
import com.stonebreak.ui.characterCreation.CharacterCreationScreen;
import com.stonebreak.ui.characterCreation.CharacterCreationTab;
import com.stonebreak.ui.chat.ChatDocumentGateTest;
import com.stonebreak.ui.fidelity.DocumentStage;
import com.stonebreak.ui.fidelity.LegacyUiRaster;
import com.stonebreak.ui.runtime.GameUiDocuments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The character creation document's presses (#301) run the legacy rules through the screen's own actions:
 * tabs, background rows, ability [-]/[+], class rows, Spend CP, +1 SP, Acquire, and the wheel scrolls the
 * active list (clamped as the legacy render clamped it).
 */
@Tag("regression")
class CharacterCreationDocumentInteractionTest {

    private static SbuiArchive shipped;

    @BeforeAll
    static void load() throws Exception {
        ChatDocumentGateTest.requireLua();
        shipped = GameUiDocuments.readScreen(CharacterCreationScreen.DOCUMENT_ID);
    }

    private static void click(DocumentStage stage, String name) {
        UiRect r = DocumentCharacterCreationCapture.element(stage, name).rect();
        stage.clickAt(r.x() + r.width() / 2f, r.y() + r.height() / 2f);
        stage.host.characterCreation().poll();
        stage.settle();
    }

    @Test
    void pressesRunTheLegacyRules() throws Exception {
        try (LegacyUiRaster fonts = new LegacyUiRaster(1920, 1080, 1f)) {
            CharacterCreationScreen screen = new CharacterCreationScreen(fonts.backend());
            CharacterStats stats = screen.getCharacterStats();
            try (DocumentStage stage = DocumentCharacterCreationCapture.stage(shipped, screen, 1920, 1080, 1f)) {
                click(stage, "bg2");
                assertEquals(BackgroundRegistry.ALL.get(2).id(), stats.getSelectedBackground());

                click(stage, "tab1");
                assertEquals(CharacterCreationTab.ABILITY_SCORE, screen.state().getActiveTab());
                int ap = stats.getRemainingAp();
                int dex = stats.getAbilityScores()[1];
                click(stage, "plus1");
                assertEquals(dex + 1, stats.getAbilityScores()[1]);
                assertEquals(ap - 1, stats.getRemainingAp());
                click(stage, "minus1");
                assertEquals(dex, stats.getAbilityScores()[1]);

                click(stage, "tab2");
                click(stage, "cls0");
                assertEquals(0, screen.state().getSelectedClassIndex());
                int cp = stats.getRemainingCp();
                click(stage, "spend1");
                assertEquals(cp - 1, stats.getRemainingCp());
                assertEquals(1, stats.getSpentCp(ClassRegistry.ALL.get(0).id() + ":1"));

                click(stage, "tab3");
                String skill = SkillRegistry.ALL.get(3).id();
                int level = stats.getSkillLevel(skill);
                click(stage, "invest3");
                assertEquals(level + 1, stats.getSkillLevel(skill));
                UiRect pane = stage.q("#pane-SKILLS").rect();
                stage.view.input().wheel(pane.x() + 10, pane.y() + 10, 0, -10, 0);
                stage.settle();
                stage.host.characterCreation().poll();
                assertEquals(SkillRegistry.ALL.size() * 42f - 320f, screen.state().getSkillScroll(), 1e-4,
                    "ten notches down, clamped at the list's end");

                click(stage, "tab4");
                click(stage, "acquire0");
                assertTrue(stats.hasFeat(FeatRegistry.ALL.get(0).id()));
            } finally {
                screen.dispose();
            }
        }
    }

    @Test
    void actionsAreRefusedWhenTheScreenIsNotShowing() throws Exception {
        try (LegacyUiRaster fonts = new LegacyUiRaster(1920, 1080, 1f)) {
            CharacterCreationScreen screen = new CharacterCreationScreen(fonts.backend());
            com.stonebreak.ui.fidelity.RecordingServices none = new com.stonebreak.ui.fidelity.RecordingServices();
            try (DocumentStage stage = new DocumentStage(shipped, 1920, 1080, 1f, none,
                    com.stonebreak.network.MultiplayerSession.Mode.SINGLEPLAYER,
                    java.util.Map.of(com.stonebreak.ui.runtime.providers.EntityPreviewProvider.ID, DocumentStage.NO_GL))) {
                UiRect r = stage.view.instance().qAll(".cc-tab").get(3).rect();
                stage.clickAt(r.x() + r.width() / 2f, r.y() + r.height() / 2f);
                assertEquals(CharacterCreationTab.BACKGROUND, screen.state().getActiveTab());
            } finally {
                screen.dispose();
            }
        }
    }
}
