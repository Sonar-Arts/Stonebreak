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
      berserker(),
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

  /**
   * Pure STR melee class built around Rage: gained by dealing/receiving hits, escalating
   * through three thresholds (T1/T2/T3) that buff base stats and ability effects, and
   * decaying rapidly out of combat. Spending CP on either ability unlocks it for casting
   * (R = Rampage, F = Skull Crusher); both abilities cost and scale off the Rage tier
   * active at the moment they're cast.
   */
  private static PlayerClassDefinition berserker() {
    return new PlayerClassDefinition(
        "berserker",
        "Berserker",
        "A pure STR melee fighter who channels combat into Rage — building it by dealing "
            + "and receiving blows, then unleashing it through devastating, tier-scaling abilities.",
        List.of(
            new ClassAbility(
                "Rampage",
                "Charge forward in a straight line, damaging everything in your path. Costs 1 Rage "
                    + "threshold; scales with your Rage tier at the moment you charge:\n"
                    + "T0: a short, moderate-damage charge.\n"
                    + "T1: the charge travels much farther.\n"
                    + "T2: leaves a burning trail behind you that damages enemies who linger in it.\n"
                    + "T3: the charge widens to fill the passable width of the area — a full cleave "
                    + "that staggers and knocks back everything it hits.",
                1
            ),
            new ClassAbility(
                "Skull Crusher",
                "A slow, heavily telegraphed overhead slam on a single target. Costs 1 Rage threshold; "
                    + "scales with your Rage tier at the moment you commit to the slam:\n"
                    + "T0: high single-target damage, no secondary effect.\n"
                    + "T1: the impact emits a damaging shockwave that radiates outward.\n"
                    + "T2: the shockwave also briefly stuns everything it hits.\n"
                    + "T3: leaves a persistent crater that inflicts Armor Break — reduced damage "
                    + "resistance — on enemies standing within it.",
                1
            )
        )
    );
  }
}
