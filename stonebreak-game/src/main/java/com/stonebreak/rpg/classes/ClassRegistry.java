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

  /** The 6 available player classes. */
  public static final List<PlayerClassDefinition> ALL = List.of(
      PlayerClassDefinition.placeholder("berserker",  "Berserker",  5),
      PlayerClassDefinition.placeholder("rogue",      "Rogue",      5),
      PlayerClassDefinition.placeholder("monk",       "Monk",       5),
      PlayerClassDefinition.placeholder("arcanist",   "Arcanist",   5),
      PlayerClassDefinition.placeholder("ranger",     "Ranger",     5),
      PlayerClassDefinition.placeholder("illusionist","Illusionist", 5)
  );

  /** Returns the class with the given id, or empty if not found. */
  public static Optional<PlayerClassDefinition> findById(String id) {
    return ALL.stream().filter(c -> c.id().equals(id)).findFirst();
  }
}
