package com.stonebreak.ui.characterScreen;

import com.openmason.engine.ui.masonry.MFonts;
import com.stonebreak.core.Game;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.rpg.CharacterPanelTab;
import com.stonebreak.rpg.TalentSubTab;
import com.stonebreak.rpg.proficiency.WeaponTraining;
import com.stonebreak.rpg.skills.SkillRegistry;
import com.stonebreak.ui.inventoryScreen.core.InventoryLayoutCalculator;

import java.util.function.Supplier;

/**
 * The Character Screen's state and rules: visibility, the active tab, the character's stats, each
 * tab's state and the presses the sheet's document sends ({@code stonebreak:screen.character-sheet},
 * #301).
 */
public class CharacterController {

  /** The sheet's own panel width; its top edge and height are the inventory panel's. */
  public static final int PANEL_WIDTH = 600;

  private final CharacterStats stats;
  private final MFonts fonts;
  private final ClassesTab classes = new ClassesTab();
  private final SkillsTab skills = new SkillsTab();
  private final FeatsTab feats = new FeatsTab();

  /** The open panel session's baseline: what right-click refunds stop at. */
  private Supplier<CharacterStats.RpgSnapshot> sessionBaseline = CharacterController::gameSessionBaseline;

  private boolean visible = false;
  private CharacterPanelTab activeTab = CharacterPanelTab.OVERVIEW;
  private TalentSubTab talentSubTab = TalentSubTab.CLASS_ABILITIES;

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

  public TalentSubTab getTalentSubTab() {
    return talentSubTab;
  }

  public void setTalentSubTab(TalentSubTab subTab) {
    this.talentSubTab = subTab;
  }

  public CharacterStats stats() { return stats; }
  public MFonts fonts() { return fonts; }
  public ClassesTab classes() { return classes; }
  public SkillsTab skills() { return skills; }
  public FeatsTab feats() { return feats; }

  /** The Inventory tab: closes the sheet and opens the inventory. */
  public void openInventory() {
    Game.getInstance().switchToInventory();
  }

  /** An ability tile's [+] ({@code delta > 0}) or [-]. */
  public void pressAbility(int index, int delta) {
    if (index < 0 || index >= 6) return;
    if (delta > 0) stats.incrementAbilityScore(index);
    else stats.decrementAbilityScore(index);
  }

  /**
   * A right press: takes back one point of what is under the pointer, if it was spent since the
   * panel session opened. Saved points (the session's baseline) are never touched. {@code target}
   * is rank (a tree node key), ability (an index), skill (an index), train (a weapon group name) or
   * feat (an id; empty for the selected feat).
   *
   * @return false for an unknown target
   */
  public boolean refund(String target, String key) {
    CharacterStats.RpgSnapshot saved = sessionBaseline.get();
    switch (target) {
      case "rank" -> stats.refundSessionRank(key, saved);
      case "ability" -> stats.refundSessionAbilityPoint(index(key), saved);
      case "skill" -> {
        int i = index(key);
        if (i >= 0 && i < SkillRegistry.ALL.size()) stats.refundSessionSkillPoint(SkillRegistry.ALL.get(i).id(), saved);
      }
      case "train" -> stats.refundSessionWeaponRank(WeaponTraining.group(key), saved);
      case "feat" -> stats.refundSessionFeat(key.isEmpty() ? feats.selectedFeatId() : key, saved);
      default -> {
        return false;
      }
    }
    return true;
  }

  /** Replaces where {@link #refund} reads the session baseline (tests; the game's panel session by default). */
  public void setSessionBaseline(Supplier<CharacterStats.RpgSnapshot> source) {
    sessionBaseline = source;
  }

  private static CharacterStats.RpgSnapshot gameSessionBaseline() {
    Game game = Game.getInstance();
    return game == null || game.getStateController() == null ? null : game.getStateController().rpgSessionBaseline();
  }

  private static int index(String s) {
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return -1;
    }
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
