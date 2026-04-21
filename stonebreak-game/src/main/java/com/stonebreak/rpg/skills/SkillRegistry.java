package com.stonebreak.rpg.skills;

import java.util.List;

/**
 * Central registry of all skills.
 * To add a skill: append a new entry to {@code ALL}.
 * To remove a skill: delete its entry.
 * To edit a skill: modify the entry directly.
 */
public final class SkillRegistry {

  private SkillRegistry() {}

  /** All 10 available skills. */
  public static final List<SkillDefinition> ALL = List.of(
      new SkillDefinition("acrobatics",   "Acrobatics",   "Placeholder description."),
      new SkillDefinition("alchemy",      "Alchemy",      "Placeholder description."),
      new SkillDefinition("arcana",       "Arcana",       "Placeholder description."),
      new SkillDefinition("athletics",    "Athletics",    "Placeholder description."),
      new SkillDefinition("deception",    "Deception",    "Placeholder description."),
      new SkillDefinition("history",      "History",      "Placeholder description."),
      new SkillDefinition("insight",      "Insight",      "Placeholder description."),
      new SkillDefinition("intimidation", "Intimidation", "Placeholder description."),
      new SkillDefinition("perception",   "Perception",   "Placeholder description."),
      new SkillDefinition("stealth",      "Stealth",      "Placeholder description.")
  );
}
