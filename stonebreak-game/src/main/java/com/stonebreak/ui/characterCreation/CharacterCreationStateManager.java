package com.stonebreak.ui.characterCreation;

import com.stonebreak.player.CharacterStats;
import com.stonebreak.rendering.UI.masonryUI.MButton;
import com.stonebreak.rendering.UI.masonryUI.MStyle;
import com.stonebreak.rpg.TalentSubTab;

/**
 * All mutable state for the character creation screen. Pure state plus widget
 * references — no rendering, no input dispatch, no business actions.
 */
public final class CharacterCreationStateManager {

    private static final float NAV_BTN_W = 180f;
    private static final float NAV_BTN_H = 44f;

    private CharacterStats characterStats;
    private CharacterCreationTab activeTab = CharacterCreationTab.BACKGROUND;

    private final MButton backToWorldSelectButton;
    private final MButton terrainMapperButton;
    private final MButton undoButton;

    private float classScroll  = 0f;
    private float skillScroll  = 0f;
    private float featScroll   = 0f;

    private int selectedClassIndex = -1;

    private TalentSubTab talentSubTab = TalentSubTab.CLASS_ABILITIES;

    private float mouseX = 0f;
    private float mouseY = 0f;

    public CharacterCreationStateManager() {
        this.characterStats = new CharacterStats(null);
        this.backToWorldSelectButton = new MButton("World Select")
                .arrow(MButton.Arrow.LEFT).size(NAV_BTN_W, NAV_BTN_H);
        this.terrainMapperButton     = new MButton("Terrain Mapper")
                .arrow(MButton.Arrow.RIGHT).size(NAV_BTN_W, NAV_BTN_H);
        this.undoButton              = new MButton("Undo")
                .fontSize(MStyle.FONT_BUTTON).size(120f, NAV_BTN_H);
    }

    public CharacterStats getCharacterStats() { return characterStats; }

    public CharacterCreationTab getActiveTab()              { return activeTab; }
    public void setActiveTab(CharacterCreationTab tab)      { this.activeTab = tab; }

    public MButton getBackToWorldSelectButton() { return backToWorldSelectButton; }
    public MButton getTerrainMapperButton()     { return terrainMapperButton; }
    public MButton getUndoButton()              { return undoButton; }

    public float getClassScroll()           { return classScroll; }
    public void  setClassScroll(float v)    { this.classScroll = v; }

    public float getSkillScroll()           { return skillScroll; }
    public void  setSkillScroll(float v)    { this.skillScroll = v; }

    public float getFeatScroll()            { return featScroll; }
    public void  setFeatScroll(float v)     { this.featScroll = v; }

    public int  getSelectedClassIndex()     { return selectedClassIndex; }
    public void setSelectedClassIndex(int i){ this.selectedClassIndex = i; }

    public TalentSubTab getTalentSubTab()             { return talentSubTab; }
    public void         setTalentSubTab(TalentSubTab t) { this.talentSubTab = t; }

    public float getMouseX() { return mouseX; }
    public float getMouseY() { return mouseY; }
    public void setMousePosition(float x, float y) { this.mouseX = x; this.mouseY = y; }

    /** Discards all progress and resets to the default blank-character state. */
    public void reset() {
        this.characterStats    = new CharacterStats(null);
        this.activeTab         = CharacterCreationTab.BACKGROUND;
        this.classScroll       = 0f;
        this.skillScroll       = 0f;
        this.featScroll        = 0f;
        this.selectedClassIndex = -1;
        this.talentSubTab       = TalentSubTab.CLASS_ABILITIES;
        this.mouseX            = 0f;
        this.mouseY            = 0f;
    }
}
