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
      rogue(),
      PlayerClassDefinition.placeholder("monk",       "Monk",       5),
      arcanist(),
      ranger(),
      illusionist()
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
   * Pure DEX skirmisher built around stealth and Momentum. Dodging (and, later, parrying) builds
   * Momentum stacks that are spent in full on the next crit to amplify it and inflict a tier debuff.
   * Rogue-tuned stealth is quieter, faster, and re-enters sooner, and lands a guaranteed crit on a
   * flat-footed target. Spending CP on either ability unlocks it for casting
   * (R = Shadow Step, F = Caltrop Scatter).
   *
   * <p>Icon paths are null until art lands; intended:
   * /ui/abilities/rogue/Shadow_Step.png, Caltrop_Scatter.png, Momentum.png.</p>
   */
  private static PlayerClassDefinition rogue() {
    return new PlayerClassDefinition(
        "rogue",
        "Rogue",
        "A pure DEX skirmisher who stalks from stealth and turns momentum into lethal crits. "
            + "Dodges build Momentum that detonates the next critical hit; openers from the shadows "
            + "and a field of caltrops leave foes flat-footed for guaranteed strikes.",
        List.of(
            new ClassAbility(
                "Shadow Step",
                "From full stealth, blink behind a nearby unaware target and leave it flat-footed, "
                    + "opening a guaranteed critical strike. Breaking stealth this way starts your "
                    + "(short) re-entry cooldown.",
                1,
                null
            ),
            new ClassAbility(
                "Caltrop Scatter",
                "Scatter a fan of caltrop clusters across the ground in front of you. The first foe "
                    + "to step on a cluster is flat-footed and the cluster is spent; untriggered "
                    + "clusters linger before expiring. You are immune to your own caltrops, and "
                    + "scattering them does not break stealth.",
                1,
                null
            )
        ),
        null
    );
  }

  /**
   * Pure INT backliner built around Resonance: casting spells of different magic schools
   * builds Resonance stacks (0-4); at 4 the Arcanist is Overloaded and the next cast
   * unleashes an empowered variant, consuming all stacks. Repeating the same school
   * builds nothing — each consecutive repeat is cheaper but weaker. Spells cost mana
   * (WIS-driven pool). Spending CP on either ability unlocks it for casting
   * (R = Leyline Breach, F = Null Spike).
   *
   * <p>Icon paths are null until art lands; intended:
   * /ui/abilities/arcanist/Leyline_Breach.png, Null_Spike.png, Resonance.png.</p>
   */
  private static PlayerClassDefinition arcanist() {
    return new PlayerClassDefinition(
        "arcanist",
        "Arcanist",
        "A pure INT spellslinger who weaves between schools of magic. Varying schools "
            + "builds Resonance toward an Overloaded cast of devastating power; leaning on "
            + "one school grows cheap but sloppy.",
        List.of(
            new ClassAbility(
                "Leyline Breach",
                "Tear open a leyline at a targeted spot. The breach persists on the ground, "
                    + "pulsing arcane damage that leaves enemies Amplified — taking increased "
                    + "magical damage — while a constant vortex drags them toward its center. "
                    + "Conjuration school; costs mana.\n"
                    + "Overloaded: the breach widens dramatically, the vortex strengthens, "
                    + "pulses hit far harder, and everything caught inside is rooted.",
                1,
                null
            ),
            new ClassAbility(
                "Null Spike",
                "Fire a fast arcane bolt that damages the first enemy struck and Spellmarks "
                    + "it — the next arcane spell to hit detonates the mark for bonus damage. "
                    + "Damage rises with your Resonance stacks at the moment of firing. "
                    + "Arcana school; costs mana.\n"
                    + "Overloaded: the spike pierces every enemy in its line (marks last "
                    + "twice as long) and detonates in a radial burst where it ends.",
                1,
                null
            )
        ),
        null
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

  /**
   * INT+CHA trickster built around Doubt: a per-enemy resource that accumulates whenever an
   * enemy is fooled into striking one of the Illusionist's decoys. Doubt makes targets hesitate
   * (Shaken) and decays when an enemy goes without illusory contact. Spending CP on either
   * ability unlocks it for casting (R = Mirrored Deceit, F = Fracture).
   *
   * <p>Icon paths are null until art lands; intended:
   * /ui/abilities/illusionist/Mirrored_Deceit.png, Fracture.png, Doubt.png.</p>
   */
  private static PlayerClassDefinition illusionist() {
    return new PlayerClassDefinition(
        "illusionist",
        "Illusionist",
        "An INT+CHA trickster who turns an enemy's own senses against them. Decoys and "
            + "mind games sow Doubt — making foes hesitate, strike at phantoms, and finally "
            + "shatter into panic that spreads to those around them.",
        List.of(
            new ClassAbility(
                "Mirrored Deceit",
                "Conjure two illusory doubles that fan out beside you and mirror your every "
                    + "move, baiting enemies into attacking the wrong target. Each decoy shatters "
                    + "in a single hit — and the attacker that pops one is slowed, Revealed "
                    + "through terrain, and left doubting what's real, gaining a stack of Doubt.",
                1,
                null
            ),
            new ClassAbility(
                "Fracture",
                "Shatter the accumulated Doubt on every nearby enemy at once, stunning each for "
                    + "a duration that grows with its Doubt:\n"
                    + "1 stack: a brief stun.\n"
                    + "2 stacks: a longer stun, and the target lashes out at the nearest creature.\n"
                    + "3 stacks (Bewildered): a full stun followed by panic — the target attacks "
                    + "friend and foe alike, spreading Doubt to those around it.",
                1,
                null
            )
        ),
        null
    );
  }
}
