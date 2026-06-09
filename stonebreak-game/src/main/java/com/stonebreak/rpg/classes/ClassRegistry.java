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
      ranger(),
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
                1,
                "/ui/abilities/berserker/Rampage.png"
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
                1,
                "/ui/abilities/berserker/Skull_Crusher.png"
            )
        ),
        "/ui/abilities/berserker/Rage.png"
    );
  }

  /**
   * WIS+DEX hybrid hunter built around the Quarry: the first hit marks a single target,
   * and further hits or trap triggers build up to three Study stacks on it — revealing
   * its defenses, exposing a weak point, and finally rendering it Marked Prey (visible
   * through terrain, with a chase speed bonus once wounded). Stacks decay slowly when the
   * Quarry goes unstudied. Spending CP on either ability unlocks it for casting
   * (R = Snare, F = Culling Shot).
   */
  private static PlayerClassDefinition ranger() {
    return new PlayerClassDefinition(
        "ranger",
        "Ranger",
        "A WIS+DEX hunter who studies a single Quarry before striking. Hits and traps "
            + "build Study stacks that reveal the target's defenses, expose weak points, and "
            + "finally mark it as prey to be run down and executed.",
        List.of(
            new ClassAbility(
                "Snare",
                "Place a ground trap at a targeted spot. It arms after a short delay and "
                    + "persists until triggered (one trap at a time; placing another replaces it). "
                    + "The victim is rooted in place and instantly studied — gaining 2 Study "
                    + "stacks and becoming your Quarry if it wasn't already. Trapping a Quarry "
                    + "you have already studied roots it longer and leaves it Exposed, taking "
                    + "increased damage from all sources.",
                1,
                "/ui/abilities/ranger/Snare.png"
            ),
            new ClassAbility(
                "Culling Shot",
                "Channel briefly, locking your aim, then fire a long-range shot that cannot "
                    + "be redirected. Scales with Study stacks on the target when it lands:\n"
                    + "0-1 stacks: strong damage and a Bleed.\n"
                    + "2 stacks: strikes the exposed weak point for bonus damage.\n"
                    + "3 stacks (Marked Prey): a guaranteed critical that Cripples the target — "
                    + "and if the prey is already badly wounded, you dash forward to close the trap.",
                1,
                "/ui/abilities/ranger/Culling_Shot.png"
            )
        ),
        "/ui/abilities/ranger/Quarry.png"
    );
  }
}
