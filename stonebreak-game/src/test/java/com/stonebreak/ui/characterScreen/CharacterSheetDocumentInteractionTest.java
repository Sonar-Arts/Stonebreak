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
 * row, Acquire, and the wheel over the class list, the ability list and the feat list; a right press over a point
 * control refunds one point spent this panel session.
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
                // tree nodes: the core passive, the two core abilities, then each path's four
                String monk = ClassRegistry.ALL.get(2).id();
                UiRect ability = stage.view.instance().qAll(".tn").get(1).rect();
                stage.clickAt(ability.x() + 5, ability.y() + 5);
                assertEquals(cp, stats.getRemainingCp(), "a core ability is not bought");
                stage.host.characterSheet().poll();
                stage.settle();
                UiRect rank = stage.view.instance().qAll(".tn").get(3).rect();
                stage.clickAt(rank.x() + 5, rank.y() + 5);
                assertEquals(cp - 1, stats.getRemainingCp(), "a rank in the first path's Tier I");
                assertEquals(1, stats.getRank(monk + ":p0:0"));
                assertTrue(stats.hasAbility(monk + ":0"), "which unlocks the path's ability");
                stage.host.characterSheet().poll();
                stage.settle();
                stage.clickAt(rank.x() + 5, rank.y() + 5);
                assertEquals(2, stats.getRank(monk + ":p0:0"), "a second rank while CP remain");
                assertEquals(cp - 2, stats.getRemainingCp());
                assertEquals(monk + ":p0:0", controller.classes().focusKey(), "the clicked node fills the detail panel");
                // the pointer over another node shows that one instead, and off the nodes the clicked one again
                UiRect passive = stage.view.instance().qAll(".tn").get(0).rect();
                stage.view.input().pointerMove(passive.x() + 5, passive.y() + 5);
                stage.settle();
                assertEquals(monk + ":passive", controller.classes().focusKey());
                stage.view.input().pointerMove(passive.x() - 120, passive.y() + 5);
                stage.settle();
                assertEquals(monk + ":p0:0", controller.classes().focusKey());

                // the wheel reaches the class list over the sidebar and the tree elsewhere; both fit their clips
                // (the grid is core plus three rows of four), so neither moves
                UiRect side = DocumentCharacterSheetCapture.element(stage, "cls0").rect();
                stage.view.input().wheel(side.x() + 5, side.y() + 5, 0, 3, 0);
                stage.settle();
                assertEquals(0f, screen.getController().classes().leftScroll());
                UiRect panel = stage.q("#panel").rect();
                stage.view.input().wheel(panel.x() + panel.width() - 50, panel.y() + 200, 0, 1, 0);
                stage.settle();
                assertEquals(0f, screen.getController().classes().rightScroll());

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

    private static void rightClick(DocumentStage stage, UiRect r) {
        stage.view.input().pointerMove(r.x() + 5, r.y() + 5);
        stage.view.input().pointerDown(r.x() + 5, r.y() + 5, 1, 0);
        stage.view.input().pointerUp(r.x() + 5, r.y() + 5, 1, 0);
        stage.settle();
        stage.host.characterSheet().poll();
        stage.settle();
    }

    @Test
    void rightPressRefundsOnlyPointsSpentThisSession() throws Exception {
        try (UiRaster fonts = new UiRaster(1920, 1080, 1f)) {
            CharacterScreen screen = CharacterSheetFixtures.screen(fonts.backend());
            CharacterStats stats = screen.getController().stats();
            var controller = screen.getController();
            try (DocumentStage stage = DocumentCharacterSheetCapture.stage(shipped, screen, 1920, 1080, 1f)) {
                click(stage, "plus2");
                CharacterStats.RpgSnapshot saved = stats.snapshotRpgState(); // the point above is saved
                controller.setSessionBaseline(() -> saved);
                int con = stats.getAbilityScores()[2];
                click(stage, "plus2");
                click(stage, "plus2", 1);
                assertEquals(con, stats.getAbilityScores()[2], "the session's point comes back");
                click(stage, "plus2", 1);
                assertEquals(con, stats.getAbilityScores()[2], "the saved one does not");

                click(stage, "tab2");
                click(stage, "cls2");
                String monk = ClassRegistry.ALL.get(2).id();
                int cp = stats.getRemainingCp();
                UiRect rank = stage.view.instance().qAll(".tn").get(3).rect();
                stage.clickAt(rank.x() + 5, rank.y() + 5);
                stage.host.characterSheet().poll();
                stage.settle();
                assertEquals(1, stats.getRank(monk + ":p0:0"));
                rightClick(stage, rank);
                assertEquals(0, stats.getRank(monk + ":p0:0"));
                assertEquals(cp, stats.getRemainingCp());

                click(stage, "talent1");
                String skill = SkillRegistry.ALL.get(4).id();
                int level = stats.getSkillLevel(skill);
                click(stage, "invest4");
                click(stage, "invest4", 1);
                assertEquals(level, stats.getSkillLevel(skill));
                click(stage, "mode1");
                click(stage, "train2");
                click(stage, "train2", 1);
                assertEquals(ProficiencyRank.UNTRAINED, stats.getProficiency(WeaponGroup.values()[2]));
                click(stage, "mode0");

                click(stage, "talent2");
                click(stage, "feat0");
                String first = FeatRegistry.filter(0, false).get(0).id();
                click(stage, "acquire");
                assertTrue(stats.hasFeat(first));
                click(stage, "feat0", 1);
                assertFalse(stats.hasFeat(first));
                assertFalse(stats.hasChangedSince(saved), "every session point refunded");
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
