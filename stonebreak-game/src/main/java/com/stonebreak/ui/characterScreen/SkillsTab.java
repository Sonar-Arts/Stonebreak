package com.stonebreak.ui.characterScreen;

import com.stonebreak.items.weapon.WeaponGroup;
import com.stonebreak.player.CharacterStats;
import com.stonebreak.rpg.SkillListMode;
import com.stonebreak.rpg.skills.SkillDefinition;
import com.stonebreak.rpg.skills.SkillRegistry;

import java.util.List;

/**
 * The Skills tab's state and rules (#301: the {@code stonebreak:screen.character-sheet} actions): a
 * General | Weapons toggle, one +1 SP button per general skill, and one Train button per weapon group
 * (bought with Weapon Training Points, not SP).
 */
public class SkillsTab {

  private SkillListMode mode = SkillListMode.GENERAL;

  public SkillListMode mode() { return mode; }

  public void setMode(SkillListMode mode) {
    if (mode != null) this.mode = mode;
  }

  /** A press on the {@code index}-th +1 SP button: invests when points remain. */
  public boolean invest(CharacterStats stats, int index) {
    List<SkillDefinition> skills = SkillRegistry.ALL;
    if (stats.getRemainingSkillPoints() <= 0 || index < 0 || index >= skills.size()) {
      return false;
    }
    stats.investSkillPoint(skills.get(index).id());
    return true;
  }

  /** A press on a weapon group's Train button: one proficiency rank for 1 WTP, if allowed. */
  public boolean train(CharacterStats stats, WeaponGroup group) {
    return group != null && stats.trainWeapon(group);
  }
}
