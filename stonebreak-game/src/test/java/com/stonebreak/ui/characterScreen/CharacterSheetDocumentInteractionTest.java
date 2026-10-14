package com.stonebreak.ui.characterScreen;

import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.runtime.UiRect;
import com.stonebreak.items.weapon.WeaponGroup;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.rpg.proficiency.ProficiencyRank;
import com.stonebreak.rpg.CharacterPanelTab;
import com.stonebreak.rpg.SkillListMode;
import com.stonebreak.rpg.TalentSubTab;
import com.stonebreak.rpg.classes.ClassRegistry;
import com.stonebreak.rpg.feats.FeatRegistry;
import com.stonebreak.rpg.skills.SkillRegistry;
import com.stonebreak.ui.chat.ChatDocumentGateTest;
import com.stonebreak.ui.fidelity.DocumentStage;
import com.stonebreak.ui.fidelity.UiRaster;
import com.stonebreak.ui.runtime.GameUiDocuments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The character sheet document's presses (#301) run the sheet's rules through its controller and tab states: tabs,
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
        try (UiRaster fonts = new UiRaster(1920, 1080, 1f)) {
            CharacterScreen screen = CharacterSheetFixtures.screen(fonts.backend());
            CharacterStats stats = screen.getController().stats();
            var controller = screen.getController();
            try (DocumentStage stage = DocumentCharacterSheetCapture.stage(shipped, screen, 1920, 1080, 1f)) {
                int str = stats.getAbilityScores()[2];
                click(stage, "plus2");
                assertEquals(str + 1, stats.getAbilityScores()[2]);

                click(stage, "tab2");
                assertEquals(CharacterPanelTab.TALENTS, controller.getActiveTab());
                assertEquals(TalentSubTab.CLASS_ABILITIES, controller.getTalentSubTab());
                click(stage, "cls2");
                assertEquals(ClassRegistry.ALL.get(2).id(), screen.getController().classes().displayedClassId());
                int cp = stats.getRemainingCp();
                // tree rows: Core section, core passive, then the core abilities; each row has + twins
                UiRect rank = stage.view.instance().qAll(".rank-btn").get(2 * 5).rect();
                stage.clickAt(rank.x() + 5, rank.y() + 5);
                assertEquals(cp - 1, stats.getRemainingCp(), "a rank in the fourth core ability");
                assertEquals(1, stats.getRank(ClassRegistry.ALL.get(2).id() + ":3"));
                stage.host.characterSheet().poll();
                stage.settle();
                UiRect locked = stage.view.instance().qAll(".rank-btn").get(2 * 5 + 1).rect();
                stage.clickAt(locked.x() + 5, locked.y() + 5);
                assertEquals(1, stats.getRank(ClassRegistry.ALL.get(2).id() + ":3"), "no CP left: the + is dead");

                // the wheel reaches the class list over the sidebar and the tree elsewhere; the class list
                // fits its clip, the tree (core plus three paths) overflows it and scrolls
                UiRect side = DocumentCharacterSheetCapture.element(stage, "cls0").rect();
                stage.view.input().wheel(side.x() + 5, side.y() + 5, 0, 3, 0);
                stage.settle();
                assertEquals(0f, screen.getController().classes().leftScroll());
                UiRect panel = stage.q("#panel").rect();
                stage.view.input().wheel(panel.x() + panel.width() - 50, panel.y() + 200, 0, 1, 0);
                stage.settle();
                assertTrue(screen.getController().classes().rightScroll() > 0f);

                click(stage, "talent1");
                assertEquals(TalentSubTab.SKILLS, controller.getTalentSubTab());
                String skill = SkillRegistry.ALL.get(4).id();
                int level = stats.getSkillLevel(skill);
                click(stage, "invest4");
                assertEquals(level + 1, stats.getSkillLevel(skill));

                click(stage, "mode1");
                assertEquals(SkillListMode.WEAPONS, controller.skills().mode());
                click(stage, "train2");
                assertEquals(ProficiencyRank.TRAINED, stats.getProficiency(WeaponGroup.values()[2]),
                    "the starting WTP trains a group");
                click(stage, "train0");
                assertEquals(ProficiencyRank.UNTRAINED, stats.getProficiency(WeaponGroup.values()[0]), "no WTP left");
                click(stage, "mode0");
                assertEquals(SkillListMode.GENERAL, controller.skills().mode());

                click(stage, "talent2");
                assertEquals(TalentSubTab.FEATS, controller.getTalentSubTab());
                var feats = screen.getController().feats();
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

                click(stage, "talent0");
                assertEquals(TalentSubTab.CLASS_ABILITIES, controller.getTalentSubTab());
                click(stage, "tab1");
                assertEquals(CharacterPanelTab.OVERVIEW, controller.getActiveTab());
                click(stage, "tab2");
                assertEquals(TalentSubTab.CLASS_ABILITIES, controller.getTalentSubTab(),
                    "the Talents tab reopens on the sub-tab last shown");
            }
        }
    }

    @Test
    void actionsAreRefusedWhileTheSheetIsClosed() throws Exception {
        try (UiRaster fonts = new UiRaster(1920, 1080, 1f)) {
            CharacterScreen screen = CharacterSheetFixtures.screen(fonts.backend());
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
