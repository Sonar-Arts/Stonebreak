package com.stonebreak.ui.characterCreation;

import com.stonebreak.player.CharacterStats;
import com.openmason.engine.ui.masonry.MButton;
import com.openmason.engine.ui.masonry.MStyle;
import com.stonebreak.rpg.SkillListMode;
import com.stonebreak.rpg.TalentSubTab;

/**
 * All mutable state for the character creation screen. Pure state plus widget
 * references — no rendering, no input dispatch, no business actions.
 */
public final class CharacterCreationStateManager {

    private static final float NAV_BTN_W = 180f;
    private static final float NAV_BTN_H = 44f;

    private CharacterStats characterStats;
    /** The blank character creation started from: what right-click refunds stop at. */
    private CharacterStats.RpgSnapshot baseline;
    private CharacterCreationTab activeTab = CharacterCreationTab.BACKGROUND;

    private final MButton backToWorldSelectButton;
    private final MButton terrainMapperButton;
    private final MButton undoButton;

    private float classScroll  = 0f;
    private float skillScroll  = 0f;
    private float featScroll   = 0f;

    private int selectedClassIndex = -1;

    /** The class tree: the node under the pointer, the last one clicked, and how far its grid overflows its clip. */
    private String classTreeHovered = null;
    private String classTreeSelected = null;
    private float classTreeOverflow = 0f;

    private TalentSubTab talentSubTab = TalentSubTab.CLASS_ABILITIES;
    private SkillListMode skillMode = SkillListMode.GENERAL;

    private float mouseX = 0f;
    private float mouseY = 0f;

    public CharacterCreationStateManager() {
        this.characterStats = new CharacterStats(null);
        this.baseline = characterStats.snapshotRpgState();
        this.backToWorldSelectButton = new MButton("World Select")
                .arrow(MButton.Arrow.LEFT).size(NAV_BTN_W, NAV_BTN_H);
        this.terrainMapperButton     = new MButton("Terrain Mapper")
                .arrow(MButton.Arrow.RIGHT).size(NAV_BTN_W, NAV_BTN_H);
        this.undoButton              = new MButton("Undo")
                .fontSize(MStyle.FONT_BUTTON).size(120f, NAV_BTN_H);
    }

    public CharacterStats getCharacterStats() { return characterStats; }
    public CharacterStats.RpgSnapshot getBaseline() { return baseline; }

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

    /** Shows class {@code i}: its tree back at the top, nothing hovered or clicked in it yet. */
    public void setSelectedClassIndex(int i) {
        this.selectedClassIndex = i;
        this.classScroll = 0f;
        this.classTreeHovered = null;
        this.classTreeSelected = null;
    }

    /** The pointer over a tree node ({@code key}), or off every node (null or empty). */
    public void setClassTreeHovered(String key) { this.classTreeHovered = key == null || key.isEmpty() ? null : key; }
    public void setClassTreeSelected(String key) { this.classTreeSelected = key; }

    /** The node the tree's detail panel describes: the hovered one, else the last one clicked; null: the passive. */
    public String getClassTreeFocus() { return classTreeHovered != null ? classTreeHovered : classTreeSelected; }

    /** How far the tree grid's content overflows its clip (the class scroll's extent), as last laid out. */
    public float getClassTreeOverflow()        { return classTreeOverflow; }
    public void  setClassTreeOverflow(float v) { this.classTreeOverflow = v; }

    public TalentSubTab getTalentSubTab()             { return talentSubTab; }
    public void         setTalentSubTab(TalentSubTab t) { this.talentSubTab = t; }

    /** The Skills pane's list: general skills (SP) or weapon training (WTP). */
    public SkillListMode getSkillMode()                 { return skillMode; }
    public void          setSkillMode(SkillListMode m)  { if (m != null) this.skillMode = m; }

    public float getMouseX() { return mouseX; }
    public float getMouseY() { return mouseY; }
    public void setMousePosition(float x, float y) { this.mouseX = x; this.mouseY = y; }

    /** Discards all progress and resets to the default blank-character state. */
    public void reset() {
        this.characterStats    = new CharacterStats(null);
        this.baseline          = characterStats.snapshotRpgState();
        this.activeTab         = CharacterCreationTab.BACKGROUND;
        this.classScroll       = 0f;
        this.skillScroll       = 0f;
        this.featScroll        = 0f;
        this.selectedClassIndex = -1;
        this.classTreeHovered   = null;
        this.classTreeSelected  = null;
        this.classTreeOverflow  = 0f;
        this.talentSubTab       = TalentSubTab.CLASS_ABILITIES;
        this.skillMode          = SkillListMode.GENERAL;
        this.mouseX            = 0f;
        this.mouseY            = 0f;
    }
}
