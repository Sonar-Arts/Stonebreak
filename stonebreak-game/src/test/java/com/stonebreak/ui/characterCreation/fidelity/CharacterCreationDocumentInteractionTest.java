package com.stonebreak.ui.characterCreation.fidelity;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.format.sbui.SbuiArchive;
import com.openmason.engine.ui.data.ActionCall;
import com.openmason.engine.ui.data.CallSite;
import com.openmason.engine.ui.data.UiScope;
import com.openmason.engine.ui.runtime.UiRect;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.player.PlayerLooks;
import com.stonebreak.rpg.backgrounds.BackgroundRegistry;
import com.stonebreak.items.weapon.WeaponGroup;
import com.stonebreak.rpg.SkillListMode;
import com.stonebreak.rpg.TalentSubTab;
import com.stonebreak.rpg.proficiency.ProficiencyRank;
import com.stonebreak.rpg.classes.ClassRegistry;
import com.stonebreak.rpg.feats.FeatRegistry;
import com.stonebreak.rpg.skills.SkillRegistry;
import com.stonebreak.ui.characterCreation.CharacterCreationScreen;
import com.stonebreak.ui.characterCreation.CharacterCreationTab;
import com.stonebreak.ui.chat.ChatDocumentGateTest;
import com.stonebreak.ui.fidelity.DocumentStage;
import com.stonebreak.ui.fidelity.UiRaster;
import com.stonebreak.ui.runtime.GameUiDocuments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;

/**
 * The character creation document's presses (#301) run the legacy rules through the screen's own actions:
 * tabs, background rows, ability [-]/[+], class rows, class tree nodes, +1 SP, Acquire, Equip on the Looks tab's hair and
 * hat lists, and the wheel scrolls the active list (clamped as the screen clamps it each frame).
 * Escape asks before leaving and Tab does nothing (#302).
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
        try (UiRaster fonts = new UiRaster(1920, 1080, 1f)) {
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
                assertEquals(CharacterCreationTab.TALENTS, screen.state().getActiveTab());
                assertEquals(TalentSubTab.CLASS_ABILITIES, screen.state().getTalentSubTab());
                click(stage, "cls0");
                assertEquals(0, screen.state().getSelectedClassIndex());
                // the tree: a core ability takes no press; the starting CP buys a Tier I path node (the second path's,
                // after the passive, the core abilities and the first path), which unlocks that path's ability
                String berserker = ClassRegistry.ALL.get(0).id();
                int cp = stats.getRemainingCp();
                click(stage, "node1");
                assertEquals(cp, stats.getRemainingCp(), "core abilities are not bought");
                String tierOne = ClassRegistry.pathKey(berserker, 1, 0);
                click(stage, "node" + (1 + ClassRegistry.ALL.get(0).abilities().size() + 4));
                assertEquals(cp - 1, stats.getRemainingCp());
                assertEquals(1, stats.getRank(tierOne));
                assertTrue(stats.hasAbility(ClassRegistry.coreKey(berserker, 1)), "Wrecker unlocks Skull Crusher");
                assertFalse(stats.hasAbility(ClassRegistry.coreKey(berserker, 0)));

                click(stage, "talent1");
                assertEquals(TalentSubTab.SKILLS, screen.state().getTalentSubTab());
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

                // the Weapons list: the starting WTP trains one group, then every Train is dead
                click(stage, "mode1");
                assertEquals(SkillListMode.WEAPONS, screen.state().getSkillMode());
                click(stage, "train1");
                assertEquals(ProficiencyRank.TRAINED, stats.getProficiency(WeaponGroup.values()[1]));
                click(stage, "train2");
                assertEquals(ProficiencyRank.UNTRAINED, stats.getProficiency(WeaponGroup.values()[2]), "no WTP left");
                click(stage, "mode0");
                assertEquals(SkillListMode.GENERAL, screen.state().getSkillMode());

                click(stage, "talent2");
                assertEquals(TalentSubTab.FEATS, screen.state().getTalentSubTab());
                click(stage, "acquire0");
                assertTrue(stats.hasFeat(FeatRegistry.ALL.get(0).id()));

                // Undo takes the allocations back, newest first, and does nothing once they are all gone
                click(stage, "undo");
                assertFalse(stats.hasFeat(FeatRegistry.ALL.get(0).id()), "the feat comes off first");
                click(stage, "undo");
                assertEquals(ProficiencyRank.UNTRAINED, stats.getProficiency(WeaponGroup.values()[1]),
                    "then the weapon training");
                click(stage, "undo");
                assertEquals(level, stats.getSkillLevel(skill), "then the skill point");
                while (stats.canUndo()) {
                    click(stage, "undo");
                }
                assertEquals(0, stats.getRank(tierOne), "and the tree node's CP");
                int[] scores = stats.getAbilityScores();
                click(stage, "undo"); // disabled: takes the press, changes nothing
                assertEquals(java.util.Arrays.toString(scores), java.util.Arrays.toString(stats.getAbilityScores()));
            } finally {
                screen.dispose();
            }
        }
    }

    /**
     * the Looks tab's hair list equips hair and its hat list hats, never the other way round; the equipped cosmetic shows no
     * Equip button, and its action ignores a press. {@link PlayerLooks} is stood in so nothing reaches the
     * settings file or the player model.
     */
    @Test
    void looksEquipsHairAndHatsFromTheirOwnLists() throws Exception {
        String[] hair = {"MALE_HAIR_1"};
        String[] hat = {"TOP_HAT"};
        List<String> equipped = new ArrayList<>();
        try (MockedStatic<PlayerLooks> looks = Mockito.mockStatic(PlayerLooks.class, Mockito.CALLS_REAL_METHODS);
             UiRaster fonts = new UiRaster(1920, 1080, 1f)) {
            looks.when(PlayerLooks::getSelectedHairId).thenAnswer(i -> hair[0]);
            looks.when(PlayerLooks::getSelectedHatId).thenAnswer(i -> hat[0]);
            looks.when(() -> PlayerLooks.selectHair(anyString())).thenAnswer(i -> {
                hair[0] = i.getArgument(0);
                equipped.add("hair " + hair[0]);
                return null;
            });
            looks.when(() -> PlayerLooks.selectHat(anyString())).thenAnswer(i -> {
                hat[0] = i.getArgument(0);
                equipped.add("hat " + hat[0]);
                return null;
            });
            CharacterCreationScreen screen = new CharacterCreationScreen(fonts.backend());
            try (DocumentStage stage = DocumentCharacterCreationCapture.stage(shipped, screen, 1920, 1080, 1f)) {
                click(stage, "tab3");
                assertEquals(CharacterCreationTab.LOOKS, screen.state().getActiveTab());
                // rows: None, Male Hair 1 (equipped), Male Hair 2
                assertTrue(DocumentCharacterCreationCapture.element(stage, "equip1").isCollapsed(),
                    "the equipped hair has no Equip button");
                click(stage, "equip2");
                assertEquals(List.of("hair MALE_HAIR_2"), equipped);
                click(stage, "equip0");
                assertEquals(List.of("hair MALE_HAIR_2", "hair NONE"), equipped);

                // the hat list, beside the hair: No Hat, Top Hat (equipped)
                assertTrue(DocumentCharacterCreationCapture.element(stage, "hat1").isCollapsed(),
                    "the equipped hat has no Equip button");
                click(stage, "hat0");
                assertFalse(DocumentCharacterCreationCapture.element(stage, "hat1").isCollapsed());
                click(stage, "hat1");
                assertEquals(List.of("hair MALE_HAIR_2", "hair NONE", "hat NONE", "hat TOP_HAT"), equipped,
                    "the hat list equips hats only");

                UiScope scope = stage.host.host().openScope("stonebreak:ui/screens/test", null, p -> { });
                CallSite site = scope.site("t", CallSite.Origin.SCRIPT);
                for (String kind : new String[]{"hair", "hat"}) {
                    String id = kind.equals("hair") ? hair[0] : hat[0];
                    ActionCall call = scope.invoke("stonebreak:screen.character-creation." + kind,
                        new UiValue.Obj(Map.of("id", UiValue.of(id))), site);
                    assertEquals(ActionCall.State.SUCCEEDED, call.state());
                }
                assertEquals(4, equipped.size(), "pressing the equipped cosmetic changes nothing");
            } finally {
                screen.dispose();
            }
        }
    }

    /**
     * Escape asks before leaving (#302): it opens the confirmation (Escape again, Stay or a press on the scrim
     * close it), Leave or Enter runs the footer's Back, and nothing under the dialog takes a press while it is up.
     * Tab is swallowed by the document (no focus walking, never reaches the game).
     */
    @Test
    void escapeAsksBeforeLeavingAndTabIsDisabled() throws Exception {
        try (UiRaster fonts = new UiRaster(1920, 1080, 1f)) {
            CharacterCreationScreen screen = new CharacterCreationScreen(fonts.backend());
            int[] backs = {0};
            screen.state().getBackToWorldSelectButton().onClick(() -> backs[0]++);
            try (DocumentStage stage = DocumentCharacterCreationCapture.stage(shipped, screen, 1920, 1080, 1f)) {
                java.util.function.BooleanSupplier asking = () -> !stage.q("#cc-confirm").isCollapsed();
                Runnable poll = () -> {
                    stage.host.characterCreation().poll();
                    stage.settle();
                };
                assertFalse(asking.getAsBoolean(), "closed when the screen opens");

                assertTrue(stage.view.input().keyDown(GLFW_KEY_TAB, 0, false), "Tab is consumed");
                assertTrue(stage.view.input().keyDown(GLFW_KEY_TAB, 0, true), "a held Tab too");
                stage.view.input().keyUp(GLFW_KEY_TAB, 0);
                stage.settle();
                assertFalse(asking.getAsBoolean());
                assertEquals(CharacterCreationTab.BACKGROUND, screen.state().getActiveTab(), "Tab cycles nothing");

                stage.key(GLFW_KEY_ESCAPE);
                assertTrue(asking.getAsBoolean(), "Escape asks");
                stage.key(GLFW_KEY_ESCAPE);
                assertFalse(asking.getAsBoolean(), "Escape again stays");
                stage.key(GLFW_KEY_ESCAPE);
                stage.click("cc-confirm-stay");
                assertFalse(asking.getAsBoolean(), "Stay closes it");
                stage.key(GLFW_KEY_ESCAPE);
                UiRect tab = stage.view.instance().qAll(".cc-tab").get(3).rect();
                stage.clickAt(tab.x() + tab.width() / 2f, tab.y() + tab.height() / 2f);
                poll.run();
                assertFalse(asking.getAsBoolean(), "a press on the scrim stays");
                assertEquals(CharacterCreationTab.BACKGROUND, screen.state().getActiveTab(), "and reaches nothing under it");
                assertEquals(0, backs[0]);

                stage.key(GLFW_KEY_ESCAPE);
                stage.click("cc-confirm-leave");
                poll.run();
                assertEquals(1, backs[0], "Leave runs Back");
                assertFalse(asking.getAsBoolean());
                stage.key(GLFW_KEY_ESCAPE);
                stage.key(GLFW_KEY_ENTER);
                poll.run();
                assertEquals(2, backs[0], "Enter confirms");
                stage.key(GLFW_KEY_ENTER);
                poll.run();
                assertEquals(2, backs[0], "Enter does nothing while nobody asked");
            } finally {
                screen.dispose();
            }
        }
    }

    @Test
    void actionsAreRefusedWhenTheScreenIsNotShowing() throws Exception {
        try (UiRaster fonts = new UiRaster(1920, 1080, 1f)) {
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
