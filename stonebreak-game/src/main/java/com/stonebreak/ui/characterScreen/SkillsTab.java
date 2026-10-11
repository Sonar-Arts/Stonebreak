package com.stonebreak.ui.characterScreen;

import com.stonebreak.player.CharacterStats;
import com.stonebreak.rpg.skills.SkillDefinition;
import com.stonebreak.rpg.skills.SkillRegistry;

import java.util.List;

/** The Skills tab's rule: one +1 SP button per skill (#301: the {@code stonebreak:screen.character-sheet} actions). */
public class SkillsTab {

  /** A press on the {@code index}-th +1 SP button: invests when points remain. */
  public boolean invest(CharacterStats stats, int index) {
    List<SkillDefinition> skills = SkillRegistry.ALL;
    if (stats.getRemainingSkillPoints() <= 0 || index < 0 || index >= skills.size()) {
      return false;
    }
    stats.investSkillPoint(skills.get(index).id());
    return true;
  }
}
