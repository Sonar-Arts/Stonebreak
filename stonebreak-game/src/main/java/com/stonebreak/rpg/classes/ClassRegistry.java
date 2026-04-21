package com.stonebreak.rpg.classes;

import java.util.List;
import java.util.Optional;

/**
 * Central registry of all player classes.
 * To add a class: append a new entry to {@code ALL}.
 * To remove a class: delete its entry.
 * To edit a class: modify its entry or replace {@code placeholder()} with an explicit definition.
 */
public final class ClassRegistry {

  private ClassRegistry() {}

  /** All 21 available player classes. */
  public static final List<PlayerClassDefinition> ALL = List.of(
      PlayerClassDefinition.placeholder("artificer",     "Artificer",     5),
      PlayerClassDefinition.placeholder("barbarian",     "Barbarian",     5),
      PlayerClassDefinition.placeholder("bard",          "Bard",          5),
      PlayerClassDefinition.placeholder("berserker",     "Berserker",     5),
      PlayerClassDefinition.placeholder("bloodhunter",   "Blood Hunter",  5),
      PlayerClassDefinition.placeholder("cleric",        "Cleric",        5),
      PlayerClassDefinition.placeholder("druid",         "Druid",         5),
      PlayerClassDefinition.placeholder("elementalmage", "Elemental Mage", 5),
      PlayerClassDefinition.placeholder("fighter",       "Fighter",       5),
      PlayerClassDefinition.placeholder("mage",          "Mage",          5),
      PlayerClassDefinition.placeholder("monk",          "Monk",          5),
      PlayerClassDefinition.placeholder("necromancer",   "Necromancer",   5),
      PlayerClassDefinition.placeholder("paladin",       "Paladin",       5),
      PlayerClassDefinition.placeholder("ranger",        "Ranger",        5),
      PlayerClassDefinition.placeholder("rogue",         "Rogue",         5),
      PlayerClassDefinition.placeholder("shadowdancer",  "Shadow Dancer", 5),
      PlayerClassDefinition.placeholder("sorcerer",      "Sorcerer",      5),
      PlayerClassDefinition.placeholder("templar",       "Templar",       5),
      PlayerClassDefinition.placeholder("warlock",       "Warlock",       5),
      PlayerClassDefinition.placeholder("warrior",       "Warrior",       5),
      PlayerClassDefinition.placeholder("wizard",        "Wizard",        5)
  );

  /** Returns the class with the given id, or empty if not found. */
  public static Optional<PlayerClassDefinition> findById(String id) {
    return ALL.stream().filter(c -> c.id().equals(id)).findFirst();
  }
}
