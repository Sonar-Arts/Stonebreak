package com.stonebreak.ui.characterScreen;

import com.openmason.engine.ui.masonry.MFonts;
import com.stonebreak.core.Game;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.rpg.CharacterPanelTab;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator;

/**
 * The Character Screen's state and rules: visibility, the active tab, the character's stats, each
 * tab's state and the presses the sheet's document sends ({@code stonebreak:screen.character-sheet},
 * #301).
 */
public class CharacterController {

  /** The sheet's own panel width; its top edge and height are the inventory panel's. */
  private static final int PANEL_WIDTH = 600;

  private final CharacterStats stats;
  private final MFonts fonts;
  private final ClassesTab classes = new ClassesTab();
  private final SkillsTab skills = new SkillsTab();
  private final FeatsTab feats = new FeatsTab();

  private boolean visible = false;
  private CharacterPanelTab activeTab = CharacterPanelTab.OVERVIEW;

  /** @param fonts the UI fonts the sheet's text is measured with */
  public CharacterController(CharacterStats stats, MFonts fonts) {
    this.stats = stats;
    this.fonts = fonts;
  }

  public void toggleVisibility() {
    this.visible = !this.visible;
  }

  public boolean isVisible() {
    return visible;
  }

  public void setVisible(boolean v) {
    this.visible = v;
  }

  public CharacterPanelTab getActiveTab() {
    return activeTab;
  }

  public void setActiveTab(CharacterPanelTab tab) {
    this.activeTab = tab;
  }

  public CharacterStats stats() { return stats; }
  public MFonts fonts() { return fonts; }
  public ClassesTab classes() { return classes; }
  public SkillsTab skills() { return skills; }
  public FeatsTab feats() { return feats; }

  /** The Inventory tab: closes the sheet and opens the inventory. */
  public void openInventory() {
    setVisible(false);
    Game.getInstance().toggleInventoryScreen();
  }

  /** An ability tile's [+] ({@code delta > 0}) or [-]. */
  public void pressAbility(int index, int delta) {
    if (index < 0 || index >= 6) return;
    if (delta > 0) stats.incrementAbilityScore(index);
    else stats.decrementAbilityScore(index);
  }

  /**
   * The sheet panel in a {@code w}x{@code h} window at UI scale {@code scale}: {x, y, w, h}. Its width
   * is the sheet's own; its top edge and height come from the inventory screen's panel, so the frame
   * and tab strip don't shift when switching between the inventory and the sheet's tabs.
   */
  public static float[] panel(int w, int h, float scale) {
    InventoryLayoutCalculator.InventoryLayout anchor = InventoryLayoutCalculator.calculateLayout(w, h);
    int pw = Math.round(PANEL_WIDTH * scale);
    int px = (w - pw) / 2;
    return new float[]{px, anchor.panelStartY, pw, anchor.inventoryPanelHeight};
  }
}
