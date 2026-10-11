package com.stonebreak.ui.characterScreen;

import com.stonebreak.player.CharacterStats;
import com.stonebreak.rpg.feats.FeatDefinition;
import com.stonebreak.rpg.feats.FeatRegistry;

import java.util.List;

/**
 * The Feats tab's state: the level and General filters, the selected feat and the list's scroll,
 * and the rules its presses run (#301: the {@code stonebreak:screen.character-sheet} actions).
 */
public class FeatsTab {

  private static final float LIST_H = 414f;
  private static final float ROW_H  = 24f;

  private int     filterLevel   = 0;     // 0 = All, 1-10 = specific level
  private boolean filterGeneral = false;
  private String  selectedFeatId = null;
  private float   scrollOffset   = 0f;

  /** Scrolls the feat list. */
  public void handleScroll(float deltaY, float scale) {
    List<FeatDefinition> filtered = FeatRegistry.filter(filterLevel, filterGeneral);
    float totalH = filtered.size() * ROW_H * scale;
    float maxScroll = Math.max(0f, totalH - LIST_H * scale);
    scrollOffset = Math.clamp(scrollOffset + deltaY * 20f, 0f, maxScroll);
  }

  public int filterLevel() { return filterLevel; }
  public boolean filterGeneral() { return filterGeneral; }
  public String selectedFeatId() { return selectedFeatId; }
  public float scrollOffset() { return scrollOffset; }

  /** The level filter button: left press {@code +1}, right press {@code -1} (wraps through All). */
  public void cycleLevel(int direction) {
    filterLevel = ((filterLevel + direction) % 11 + 11) % 11;
    selectedFeatId = null;
    scrollOffset = 0f;
  }

  public void toggleGeneral() {
    filterGeneral = !filterGeneral;
    selectedFeatId = null;
    scrollOffset = 0f;
  }

  public void selectFeat(String featId) {
    if (FeatRegistry.filter(filterLevel, filterGeneral).stream().anyMatch(f -> f.id().equals(featId))) {
      selectedFeatId = featId;
    }
  }

  /** The Acquire button: acquires the selected feat, whatever the button looks like. */
  public boolean acquire(CharacterStats stats) {
    if (selectedFeatId == null) {
      return false;
    }
    stats.acquireFeat(selectedFeatId);
    return true;
  }
}
