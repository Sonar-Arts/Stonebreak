package com.stonebreak.rpg.feats;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Central registry of all feats.
 * To add a feat: append a new entry to {@code ALL}.
 * To remove a feat: delete its entry.
 * To edit a feat: modify the entry directly.
 *
 * <p>100 placeholder feats — 10 per level (1-10), 5 general per level.
 * General feats are marked with {@code isGeneral = true}.
 */
public final class FeatRegistry {

  private FeatRegistry() {}

  private static FeatDefinition feat(String id, String name, int level, boolean isGeneral) {
    return new FeatDefinition(id, name, "Placeholder description.", level, isGeneral);
  }

  /** All 100 placeholder feats. Add new feats here. */
  public static final List<FeatDefinition> ALL = List.of(
      // ── Level 1 ──────────────────────────────────────── 5 general, 5 non-general
      feat("acrobatic_grace",   "Acrobatic Grace",   1, true),
      feat("alert_mind",        "Alert Mind",        1, true),
      feat("brutal_strike",     "Brutal Strike",     1, false),
      feat("combat_insight",    "Combat Insight",    1, true),
      feat("defenders_stance",  "Defender's Stance", 1, false),
      feat("eagle_eye",         "Eagle Eye",         1, false),
      feat("hardy_frame",       "Hardy Frame",       1, true),
      feat("iron_grip",         "Iron Grip",         1, false),
      feat("shield_mastery",    "Shield Mastery",    1, false),
      feat("swift_stride",      "Swift Stride",      1, true),

      // ── Level 2 ──────────────────────────────────────── 5 general, 5 non-general
      feat("arcane_sensitivity","Arcane Sensitivity",2, true),
      feat("battle_rhythm",     "Battle Rhythm",     2, true),
      feat("blade_dance",       "Blade Dance",       2, false),
      feat("crushing_blow",     "Crushing Blow",     2, false),
      feat("elemental_affinity","Elemental Affinity",2, false),
      feat("focused_effort",    "Focused Effort",    2, true),
      feat("hunters_mark",      "Hunter's Mark",     2, false),
      feat("natural_resilience","Natural Resilience",2, true),
      feat("shadow_step",       "Shadow Step",       2, false),
      feat("tactical_retreat",  "Tactical Retreat",  2, true),

      // ── Level 3 ──────────────────────────────────────── 5 general, 5 non-general
      feat("ambidextrous",      "Ambidextrous",      3, false),
      feat("berserkers_fury",   "Berserker's Fury",  3, true),
      feat("combat_expertise",  "Combat Expertise",  3, true),
      feat("deadly_precision",  "Deadly Precision",  3, true),
      feat("elusive_movement",  "Elusive Movement",  3, false),
      feat("fortunes_favor",    "Fortune's Favor",   3, true),
      feat("great_fortitude",   "Great Fortitude",   3, true),
      feat("heavy_hitter",      "Heavy Hitter",      3, false),
      feat("quick_reflexes",    "Quick Reflexes",    3, false),
      feat("stone_wall",        "Stone Wall",        3, false),

      // ── Level 4 ──────────────────────────────────────── 5 general, 5 non-general
      feat("adrenaline_rush",   "Adrenaline Rush",   4, false),
      feat("battle_hardened",   "Battle Hardened",   4, true),
      feat("cascade_strike",    "Cascade Strike",    4, true),
      feat("dauntless_courage", "Dauntless Courage", 4, true),
      feat("enduring_willpower","Enduring Willpower",4, true),
      feat("fleet_of_foot",     "Fleet of Foot",     4, true),
      feat("ghostly_step",      "Ghostly Step",      4, false),
      feat("iron_will",         "Iron Will",         4, false),
      feat("power_attack",      "Power Attack",      4, false),
      feat("thousand_cuts",     "Thousand Cuts",     4, false),

      // ── Level 5 ──────────────────────────────────────── 5 general, 5 non-general
      feat("arcane_mastery",    "Arcane Mastery",    5, false),
      feat("battle_trance",     "Battle Trance",     5, true),
      feat("combat_surge",      "Combat Surge",      5, true),
      feat("deft_maneuver",     "Deft Maneuver",     5, true),
      feat("enhanced_vitality", "Enhanced Vitality", 5, true),
      feat("flashfire_strike",  "Flashfire Strike",  5, false),
      feat("guardians_resolve", "Guardian's Resolve",5, true),
      feat("improved_initiative","Improved Initiative",5, false),
      feat("rapid_strike",      "Rapid Strike",      5, false),
      feat("steel_nerves",      "Steel Nerves",      5, false),

      // ── Level 6 ──────────────────────────────────────── 5 general, 5 non-general
      feat("accelerated_healing","Accelerated Healing",6, true),
      feat("blade_vortex",      "Blade Vortex",      6, false),
      feat("critical_focus",    "Critical Focus",    6, true),
      feat("devastating_blow",  "Devastating Blow",  6, false),
      feat("empowered_strike",  "Empowered Strike",  6, true),
      feat("fortified_mind",    "Fortified Mind",    6, true),
      feat("greater_endurance", "Greater Endurance", 6, true),
      feat("piercing_strike",   "Piercing Strike",   6, false),
      feat("relentless_assault","Relentless Assault",6, false),
      feat("shadow_reflexes",   "Shadow Reflexes",   6, false),

      // ── Level 7 ──────────────────────────────────────── 5 general, 5 non-general
      feat("advanced_tactics",  "Advanced Tactics",  7, true),
      feat("armor_expertise",   "Armor Expertise",   7, true),
      feat("blademasters_art",  "Blademaster's Art", 7, false),
      feat("cyclone_strike",    "Cyclone Strike",    7, false),
      feat("dual_focus",        "Dual Focus",        7, true),
      feat("elemental_surge",   "Elemental Surge",   7, false),
      feat("flowing_combat",    "Flowing Combat",    7, true),
      feat("hardened_body",     "Hardened Body",     7, true),
      feat("masterful_dodge",   "Masterful Dodge",   7, false),
      feat("thunderous_charge", "Thunderous Charge", 7, false),

      // ── Level 8 ──────────────────────────────────────── 5 general, 5 non-general
      feat("arcane_precision",  "Arcane Precision",  8, true),
      feat("battle_awareness",  "Battle Awareness",  8, true),
      feat("crushing_momentum", "Crushing Momentum", 8, false),
      feat("deaths_door",       "Death's Door Resilience", 8, true),
      feat("effortless_grace",  "Effortless Grace",  8, true),
      feat("fierce_determination","Fierce Determination",8, true),
      feat("grand_strategy",    "Grand Strategy",    8, false),
      feat("legendary_endurance","Legendary Endurance",8, false),
      feat("overwhelming_force","Overwhelming Force",8, false),
      feat("unstoppable_momentum","Unstoppable Momentum",8, false),

      // ── Level 9 ──────────────────────────────────────── 5 general, 5 non-general
      feat("aegis_of_realm",    "Aegis of the Realm",9, true),
      feat("battle_legend",     "Battle Legend",     9, true),
      feat("champions_resolve", "Champion's Resolve",9, true),
      feat("dominance_aura",    "Dominance Aura",    9, false),
      feat("eternal_vigilance", "Eternal Vigilance", 9, true),
      feat("flawless_technique","Flawless Technique",9, true),
      feat("greater_mastery",   "Greater Mastery",   9, false),
      feat("heroic_effort",     "Heroic Effort",     9, false),
      feat("indomitable_spirit","Indomitable Spirit",9, false),
      feat("pinnacle_of_power", "Pinnacle of Power", 9, false),

      // ── Level 10 ─────────────────────────────────────── 5 general, 5 non-general
      feat("apex_predator",     "Apex Predator",     10, true),
      feat("ascended_form",     "Ascended Form",     10, true),
      feat("divine_fortitude",  "Divine Fortitude",  10, true),
      feat("eternal_champion",  "Eternal Champion",  10, true),
      feat("godlike_reflexes",  "Godlike Reflexes",  10, true),
      feat("legends_might",     "Legend's Might",    10, false),
      feat("masterpiece_combat","Masterpiece Combat",10, false),
      feat("supreme_endurance", "Supreme Endurance", 10, false),
      feat("transcendent_power","Transcendent Power",10, false),
      feat("unyielding_dominance","Unyielding Dominance",10, false)
  );

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
