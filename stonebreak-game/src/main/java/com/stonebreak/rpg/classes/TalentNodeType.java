package com.stonebreak.rpg.classes;

import com.stonebreak.player.PlayerConstants;

/** What a talent node is, which sets its default rank cap. */
public enum TalentNodeType {
  PASSIVE, ABILITY, MODIFIER, CAPSTONE;

  public int defaultMaxRank() {
    return switch (this) {
      case PASSIVE  -> PlayerConstants.TALENT_MAX_RANK_PASSIVE;
      case ABILITY  -> PlayerConstants.TALENT_MAX_RANK_ABILITY;
      case MODIFIER -> PlayerConstants.TALENT_MAX_RANK_MODIFIER;
      case CAPSTONE -> PlayerConstants.TALENT_MAX_RANK_CAPSTONE;
    };
  }
}
