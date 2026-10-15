package com.stonebreak.rpg.classes;

import com.stonebreak.player.PlayerConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.stonebreak.rpg.classes.TalentNodeType.ABILITY;
import static com.stonebreak.rpg.classes.TalentNodeType.CAPSTONE;
import static com.stonebreak.rpg.classes.TalentNodeType.MODIFIER;
import static com.stonebreak.rpg.classes.TalentNodeType.PASSIVE;

/**
 * Central registry of all player classes.
 * To add a class: append a new entry to {@code ALL}.
 * To remove a class: delete its entry.
 * To edit a class: modify its entry or replace {@code placeholder()} with an explicit definition.
 *
 * <p>Tree node keys: a core ability is {@code "<classId>:<abilityIndex>"}; a path node is
 * {@code "<classId>:p<path>:<tier>"}. A key containing {@code ":p"} is a path node. Only path nodes take
 * ranks; each path grants one core ability ({@link TalentPath#ability()}), which a rank anywhere in that path
 * unlocks, so the path a player opens first decides the ability they start with.</p>
 */
public final class ClassRegistry {

  private ClassRegistry() {}

  /** The 6 available player classes. */
  public static final List<PlayerClassDefinition> ALL = List.of(
      berserker(),
      rogue(),
      monk(),
      arcanist(),
      ranger(),
      illusionist()
  );

  /** Returns the class with the given id, or empty if not found. */
  public static Optional<PlayerClassDefinition> findById(String id) {
    return ALL.stream().filter(c -> c.id().equals(id)).findFirst();
  }

  // ── Tree keys ──────────────────────────────────────────────────────────────

  /** The key of a path node, e.g. {@code pathKey("ranger", 2, 3)} = {@code "ranger:p2:3"}. */
  public static String pathKey(String classId, int path, int tier) {
    return classId + ":p" + path + ":" + tier;
  }

  /** The key of a core ability, e.g. {@code coreKey("ranger", 0)} = {@code "ranger:0"}. */
  public static String coreKey(String classId, int abilityIndex) {
    return classId + ":" + abilityIndex;
  }

  public static boolean isPathKey(String key) {
    return key != null && key.contains(":p");
  }

  /** The class id part of a tree key, or null if the key has none. */
  public static String classIdOf(String key) {
    int sep = key == null ? -1 : key.indexOf(':');
    return sep > 0 ? key.substring(0, sep) : null;
  }

  public static Optional<PlayerClassDefinition> classOfKey(String key) {
    String id = classIdOf(key);
    return id == null ? Optional.empty() : findById(id);
  }

  /** The path node with this key, or empty for core-ability or unknown keys. */
  public static Optional<TalentNode> findNode(String key) {
    if (!isPathKey(key)) return Optional.empty();
    String[] parts = key.split(":");
    if (parts.length != 3) return Optional.empty();
    int path = parseIndex(parts[1].substring(1));
    int tier = parseIndex(parts[2]);
    return classOfKey(key)
        .filter(c -> path >= 0 && path < c.paths().size() && tier >= 0 && tier < TalentPath.TIERS)
        .map(c -> c.paths().get(path).nodes().get(tier));
  }

  /** True if the key names a path node of a registered class: the only rankable keys (core abilities are not bought). */
  public static boolean isValidKey(String key) {
    return isPathKey(key) && findNode(key).isPresent();
  }

  /** True if the key names a core ability ({@code "<classId>:<index>"}) of a registered class. */
  public static boolean isAbilityKey(String key) {
    if (key == null || isPathKey(key)) return false;
    Optional<PlayerClassDefinition> cls = classOfKey(key);
    int idx = abilityIndexOf(key);
    return cls.isPresent() && idx >= 0 && idx < cls.get().abilities().size();
  }

  /** The ability index of a core ability key, or -1 when the key is not one. */
  public static int abilityIndexOf(String key) {
    if (key == null || isPathKey(key) || key.indexOf(':') < 0) return -1;
    return parseIndex(key.substring(key.indexOf(':') + 1));
  }

  /** The paths whose ranks unlock core ability {@code abilityKey}, in path order; empty for any other key. */
  public static List<TalentPath> pathsGranting(String abilityKey) {
    if (!isAbilityKey(abilityKey)) return List.of();
    int idx = abilityIndexOf(abilityKey);
    return classOfKey(abilityKey).orElseThrow().paths().stream().filter(p -> p.ability() == idx).toList();
  }

  /** 0..3 for Tier I..IV of a path node; -1 for any other key. */
  public static int tierOf(String key) {
    return findNode(key).map(TalentNode::tier).orElse(-1);
  }

  /** The rank cap of a path node; 0 for any other key. */
  public static int maxRankOf(String key) {
    return findNode(key).map(TalentNode::maxRank).orElse(0);
  }

  /** The node a path node hangs from (same path, one tier up), or null for tier I and core keys. */
  public static String parentKey(String key) {
    Optional<TalentNode> node = findNode(key);
    if (node.isEmpty() || node.get().tier() == 0) return null;
    int path = parseIndex(key.split(":")[1].substring(1));
    return pathKey(classIdOf(key), path, node.get().tier() - 1);
  }

  /** Every rankable key of a class: each path's nodes by tier. */
  public static List<String> keysOf(PlayerClassDefinition cls) {
    List<String> keys = new ArrayList<>();
    for (TalentPath path : cls.paths()) {
      for (TalentNode n : path.nodes()) keys.add(n.key());
    }
    return keys;
  }

  private static int parseIndex(String s) {
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  // ── Definition helpers ─────────────────────────────────────────────────────

  private record NodeSpec(TalentNodeType type, String name, String description) {}

  private static NodeSpec node(TalentNodeType type, String name, String description) {
    return new NodeSpec(type, name, description);
  }

  /**
   * A path whose nodes are given Tier I to IV, keyed and ranked from their type; a rank in it unlocks core ability
   * {@code ability}.
   */
  private static TalentPath path(String classId, int index, int ability, String name, String focus,
                                 NodeSpec... specs) {
    List<TalentNode> nodes = new ArrayList<>(specs.length);
    for (int t = 0; t < specs.length; t++) {
      NodeSpec n = specs[t];
      nodes.add(new TalentNode(pathKey(classId, index, t), n.name(), n.description(), n.type(), t,
          n.type().defaultMaxRank(), null));
    }
    return new TalentPath(name, focus, ability, nodes);
  }

  /**
   * Pure STR melee class built around Rage: gained by dealing/receiving hits, escalating
   * through three thresholds (T1/T2/T3) that buff base stats and ability effects, and
   * decaying rapidly out of combat. A rank in a path unlocks that path's ability for casting
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
        "/ui/abilities/berserker/Rage.png",
        new CorePassive("Rage",
            "Builds through 3 tiers (damage → attack speed → lifesteal). Abilities scale with the tier at cast, then consume it.", null),
        List.of(
            path("berserker", 0, 0, "Bloodletter", "Sustain: turns the lifesteal tier into a self-healing engine.",
                node(PASSIVE, "Open Veins", "Kills at Rage III heal 3% max HP."),
                node(ABILITY, "Crimson Howl", "Consume Rage to heal; the heal scales per tier consumed."),
                node(MODIFIER, "Rampage: Bloodtrail", "Rampage leaves Bleed on every enemy it passes."),
                node(CAPSTONE, "Unending", "Lethal damage at Rage III leaves you at 1 HP for 4s. 90s cooldown.")),
            path("berserker", 1, 1, "Wrecker", "Burst: fewer, heavier swings that break enemies.",
                node(PASSIVE, "Heavy Hands", "The damage tier deals +15% to Staggered targets."),
                node(ABILITY, "Earthsplitter", "Forward line slam that knocks targets up and breaks blocks."),
                node(MODIFIER, "Skull Crusher: Concuss", "Cast at Rage III to stun for 1.5s."),
                node(CAPSTONE, "Cataclysm", "Consuming Rage III releases a shockwave around you.")),
            path("berserker", 2, 0, "Frenzy", "Speed: stay in the fight and keep Rage from decaying.",
                node(PASSIVE, "Quickened Blood", "Rage decays 50% slower while you keep hitting."),
                node(ABILITY, "Whirlwind", "Channelled spin; each hit extends the duration slightly."),
                node(MODIFIER, "Rampage: Relentless", "Each hit during Rampage adds 0.25s to it."),
                node(CAPSTONE, "Red Haze", "Unlocks a fourth Rage tier; the attack speed bonus stops decaying."))
        )
    );
  }

  /**
   * Pure DEX skirmisher built around stealth and Momentum. Dodging (and, later, parrying) builds
   * Momentum stacks that are spent in full on the next crit to amplify it and inflict a tier debuff.
   * Rogue-tuned stealth is quieter, faster, and re-enters sooner, and lands a guaranteed crit on a
   * flat-footed target. A rank in a path unlocks that path's ability for casting
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
        null,
        new CorePassive("Momentum",
            "0–3 stacks from dodges, consumed on crits (T1 damage, T2 damage + Bleed, T3 damage + Cripple).", null),
        List.of(
            path("rogue", 0, 0, "Assassin", "One target, one opening, from stealth.",
                node(PASSIVE, "Opportunist", "+20% crit chance against Unaware targets."),
                node(ABILITY, "Garrote", "From stealth: silence and Bleed a target for 3s."),
                node(MODIFIER, "Shadow Step: Reset", "Kills within 2s of Shadow Step refund its cooldown."),
                node(CAPSTONE, "Death Mark", "Mark a target; damage dealt to it is stored, then detonates after 4s.")),
            path("rogue", 1, 1, "Trickster", "Misdirection and battlefield control.",
                node(PASSIVE, "Sleight", "Dodging leaves a decoy that holds aggro for 1.5s."),
                node(ABILITY, "Smoke Bomb", "An area that grants stealth while you stay inside it."),
                node(MODIFIER, "Caltrop Scatter: Long Throw", "Thrown further; slows 30%."),
                node(CAPSTONE, "Vanishing Act", "Kills re-enter stealth instantly. 20s cooldown.")),
            path("rogue", 2, 0, "Skirmisher", "Open-field fighter who never stops moving.",
                node(PASSIVE, "Footwork", "Near-misses also grant Momentum."),
                node(ABILITY, "Flurry", "Rapid five-hit combo; each hit can consume Momentum."),
                node(MODIFIER, "Dodge: Lunge", "Dodging toward an enemy ends with a free strike."),
                node(CAPSTONE, "Untouchable", "At 3 Momentum, gain 2s of full evasion once per 30s."))
        )
    );
  }

  /**
   * CON+DEX martial artist built around Ki. The class itself is still a placeholder: two
   * placeholder core abilities, but its talent tree is designed.
   */
  private static PlayerClassDefinition monk() {
    PlayerClassDefinition base = PlayerClassDefinition.placeholder("monk", "Monk", 2);
    return new PlayerClassDefinition(base.id(), base.name(), base.description(), base.abilities(),
        base.iconPath(),
        new CorePassive("Ki",
            "Unarmed hits and dodges build Ki. Spending Ki empowers techniques; full Ki grants Flow (faster combos).", null),
        List.of(
            path("monk", 0, 0, "Iron Fist", "Hard-hitting strikes.",
                node(PASSIVE, "Conditioned Body", "Unarmed damage scales with CON."),
                node(ABILITY, "Palm Strike", "Heavy push that staggers."),
                node(MODIFIER, "Palm Strike: Inner Burst", "Delayed second hit 1s later."),
                node(CAPSTONE, "Hundred Hands", "Spend all Ki on a long combo that scales per point.")),
            path("monk", 1, 1, "Flowing Water", "Evasion and redirection.",
                node(PASSIVE, "Yield", "Dodging an attack builds double Ki."),
                node(ABILITY, "Redirect", "Turn an enemy's attack onto another enemy."),
                node(MODIFIER, "Flow: Current", "Flow also boosts dodge distance."),
                node(CAPSTONE, "Formless", "While in Flow, take no damage from your first hit every 5s.")),
            path("monk", 2, 0, "Inner Sanctum", "Self-sustain and resilience.",
                node(PASSIVE, "Still Mind", "Regenerate HP while at full Ki."),
                node(ABILITY, "Meditate", "Channel: convert Ki into healing."),
                node(MODIFIER, "Ki: Diamond Skin", "Spending Ki grants a brief shield."),
                node(CAPSTONE, "Enlightenment", "Status effects on you end early as Ki fills."))
        )
    );
  }

  /**
   * Pure INT backliner built around Resonance: casting spells of different magic schools
   * builds Resonance stacks (0-4); at 4 the Arcanist is Overloaded and the next cast
   * unleashes an empowered variant, consuming all stacks. Repeating the same school
   * builds nothing — each consecutive repeat is cheaper but weaker. Spells cost mana
   * (WIS-driven pool). A rank in a path unlocks that path's ability for casting
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
        null,
        new CorePassive("Resonance",
            "Alternating Magic Schools builds toward a free Overloaded cast.", null),
        List.of(
            path("arcanist", 0, 0, "Conjurer", "Leans into Conjuration: zones and summoned structures.",
                node(PASSIVE, "Lingering Breach", "Leyline Breach persists 50% longer."),
                node(ABILITY, "Arcane Lattice", "Conjure a barrier that blocks projectiles."),
                node(MODIFIER, "Leyline Breach: Undertow", "The Breach pulls enemies toward its center."),
                node(CAPSTONE, "Ley Nexus", "Two active Breaches link and damage anything between them.")),
            path("arcanist", 1, 1, "Nullifier", "Leans into Arcana: strip and silence magic.",
                node(PASSIVE, "Unravel", "Arcana hits remove one buff from the target."),
                node(ABILITY, "Null Field", "An area where enemy spells cost more and cast slower."),
                node(MODIFIER, "Null Spike: Pierce", "Null Spike passes through all targets in a line."),
                node(CAPSTONE, "Silence the Weave", "Overloaded Null Spike silences everything hit for 3s.")),
            path("arcanist", 2, 0, "Overcharger", "Leans into Resonance itself.",
                node(PASSIVE, "Harmonics", "Resonance builds 25% faster."),
                node(ABILITY, "Channel Surge", "Spend Resonance early for a partial Overload."),
                node(MODIFIER, "Overload: Echo", "Overloaded casts repeat at 40% power."),
                node(CAPSTONE, "Critical Mass", "Overloaded casts no longer reset Resonance to zero."))
        )
    );
  }

  /**
   * WIS+DEX hybrid hunter built around the Quarry: the first hit marks a single target,
   * and further hits or trap triggers build up to three Study stacks on it — revealing
   * its defenses, exposing a weak point, and finally rendering it Marked Prey (visible
   * through terrain, with a chase speed bonus once wounded). Stacks decay slowly when the
   * Quarry goes unstudied. A rank in a path unlocks that path's ability for casting
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
        "/ui/abilities/ranger/Quarry.png",
        new CorePassive("Quarry",
            "Study stacks reveal weaknesses; at max stacks the target becomes Marked Prey.", null),
        List.of(
            path("ranger", 0, 1, "Hunter", "Single-target tracking.",
                node(PASSIVE, "Keen Senses", "Study stacks also build from tracks and blood, not just hits."),
                node(ABILITY, "Hunter's Mark", "Instantly apply two Study stacks."),
                node(MODIFIER, "Marked Prey: Pursuit", "Move faster toward Marked Prey."),
                node(CAPSTONE, "Apex Predator", "Marked Prey takes +25% damage from all sources.")),
            path("ranger", 1, 0, "Trapper", "Control the ground with Snare.",
                node(PASSIVE, "Set Ground", "Carry two Snares at once."),
                node(ABILITY, "Net Launcher", "Ranged net that slows."),
                node(MODIFIER, "Snare: Barbed", "Snare applies Bleed and a Study stack."),
                node(CAPSTONE, "Killing Field", "Enemies caught in traps become Marked Prey instantly.")),
            path("ranger", 2, 1, "Marksman", "Range and the finishing shot.",
                node(PASSIVE, "Steady Aim", "Standing still increases range and crit chance."),
                node(ABILITY, "Volley", "Arc of arrows onto an area."),
                node(MODIFIER, "Culling Shot: Pierce", "Culling Shot passes through targets."),
                node(CAPSTONE, "One Shot", "Culling Shot on Marked Prey below 25% HP always kills."))
        )
    );
  }

  /**
   * INT+CHA trickster built around Doubt: a per-enemy resource that accumulates whenever an
   * enemy is fooled into striking one of the Illusionist's decoys. Doubt makes targets hesitate
   * (Shaken) and decays when an enemy goes without illusory contact. A rank in a path unlocks
   * that path's ability for casting (R = Mirrored Deceit, F = Fracture).
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
        null,
        new CorePassive("Doubt",
            "Up to 3 stacks per enemy; Bewildered at max.", null),
        List.of(
            path("illusionist", 0, 0, "Deceiver", "Decoys built on Mirrored Deceit.",
                node(PASSIVE, "Many Faces", "Decoys apply Doubt when attacked."),
                node(ABILITY, "Doppelganger", "A decoy that mimics your last ability."),
                node(MODIFIER, "Mirrored Deceit: Swap", "Swap places with a decoy."),
                node(CAPSTONE, "Hall of Mirrors", "Create a decoy each time you take damage.")),
            path("illusionist", 1, 1, "Shatterer", "Detonation via Fracture.",
                node(PASSIVE, "Fragile Minds", "Doubt stacks last longer."),
                node(ABILITY, "Splinter", "Fast-cast single-target Doubt applier."),
                node(MODIFIER, "Fracture: Resonant", "Fracture re-applies one Doubt stack after detonating."),
                node(CAPSTONE, "Mindbreak", "Fracture on Bewildered targets stuns.")),
            path("illusionist", 2, 1, "Phantasmist", "Fear and phantom damage.",
                node(PASSIVE, "Night Terrors", "Bewildered enemies may flee."),
                node(ABILITY, "Phantasm", "Summon a phantom that deals psychic damage."),
                node(MODIFIER, "Phantasm: Dread", "Phantom damage applies Doubt."),
                node(CAPSTONE, "Waking Nightmare", "Bewildered enemies take damage over time from phantoms."))
        )
    );
  }
}
