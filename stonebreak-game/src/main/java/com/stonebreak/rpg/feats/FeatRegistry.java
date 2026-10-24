package com.stonebreak.rpg.feats;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Central registry of all feats.
 * To add a feat: append a new entry to {@code ALL}.
 * To remove a feat: delete its entry.
 * To edit a feat: modify the entry directly.
 */
public final class FeatRegistry {

  private FeatRegistry() {}

  /** All feats. Add new feats here. */
  public static final List<FeatDefinition> ALL = List.of(
      new FeatDefinition("double_jump", "Double Jump",
          "Grants the player an extra jump while mid-air.", 1, true)
  );

  /** The feat with the given id, if the registry knows it. */
  public static Optional<FeatDefinition> findById(String featId) {
    return ALL.stream().filter(f -> f.id().equals(featId)).findFirst();
  }

  /**
   * True when a character of {@code characterLevel} may take the feat: it exists and its
   * required level is not above the character's (issue #421). The one level rule shared by
   * the server's prefs validation, the character sheet and the creation screen.
   */
  public static boolean isAvailableAt(String featId, int characterLevel) {
    return findById(featId).map(f -> f.level() <= characterLevel).orElse(false);
  }

  /**
   * Returns feats matching the given filters, sorted alphabetically by name.
   *
   * @param level       the required level, or {@code 0} to include all levels
   * @param generalOnly if true, only returns feats where {@code isGeneral == true}
   */
  public static List<FeatDefinition> filter(int level, boolean generalOnly) {
    return ALL.stream()
        .filter(f -> level == 0 || f.level() == level)
        .filter(f -> !generalOnly || f.isGeneral())
        .sorted(Comparator.comparing(FeatDefinition::name))
        .collect(Collectors.toList());
  }
}
