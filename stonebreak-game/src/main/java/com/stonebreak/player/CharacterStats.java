package com.stonebreak.player;

import com.stonebreak.rpg.classes.ClassRegistry;
import com.stonebreak.rpg.classes.PlayerClassDefinition;
import com.stonebreak.rpg.feats.FeatRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Holds RPG character statistics and mutable progression state.
 * Vitals are live-read from the Player instance; everything else is managed here.
 */
public class CharacterStats {

  private final Player player;

  // ─────────────────────────────────────────────── Classes

  private String selectedClassId = null;

  /** Maps "classId:abilityIndex" to the number of CP spent on that ability. */
  private final Map<String, Integer> spentAbilityCp = new HashMap<>();

  // ─────────────────────────────────────────────── Skills

  /** Maps skill id to its current level. */
  private final Map<String, Integer> skillLevels = new HashMap<>();

  // ─────────────────────────────────────────────── Feats

  private final Set<String> acquiredFeatIds = new HashSet<>();

  // ─────────────────────────────────────────────── Point currencies

  private int remainingCp = 100;
  private int remainingSp = 100;
  private int remainingFp = 100;

  public CharacterStats(Player player) {
    this.player = player;
  }

  // ─────────────────────────────────────────────── Ability scores (stubs)

  public int getStrength()     { return 10; }
  public int getDexterity()    { return 10; }
  public int getConstitution() { return 10; }
  public int getIntelligence() { return 10; }
  public int getWisdom()       { return 10; }
  public int getCharisma()     { return 10; }

  /** Standard modifier: floor((score - 10) / 2). */
  public int getModifier(int score) { return Math.floorDiv(score - 10, 2); }

  // ─────────────────────────────────────────────── Class / progression (stubs)

  public String getCharacterClass() {
    if (selectedClassId != null) {
      return ClassRegistry.findById(selectedClassId)
          .map(PlayerClassDefinition::name)
          .orElse(selectedClassId);
    }
    return "Adventurer";
  }

  /** Returns the names of all acquired feats, in acquisition order. */
  public List<String> getFeats() {
    return acquiredFeatIds.stream()
        .map(id -> FeatRegistry.ALL.stream()
            .filter(f -> f.id().equals(id))
            .findFirst()
            .map(f -> f.name())
            .orElse(id))
        .collect(Collectors.toList());
  }
  public List<String> getStatusEffects() { return List.of(); }

  // ─────────────────────────────────────────────── Point currencies (stubs)

  /** @deprecated Use {@link #getRemainingCp()} for live values. */
  public int getClassPoints() { return remainingCp; }

  /** @deprecated Use {@link #getRemainingSkillPoints()} for live values. */
  public int getSkillPoints() { return remainingSp; }

  /** @deprecated Use {@link #getRemainingFeatPoints()} for live values. */
  public int getFeatPoints()  { return remainingFp; }

  // ─────────────────────────────────────────────── Vitals

  /** Live health from player. */
  public float getHealth()    { return player != null ? player.getHealth()    : 0f; }
  public float getMaxHealth() { return player != null ? player.getMaxHealth() : 20f; }

  /** Mana — not yet implemented. */
  public float getMana()    { return 0f; }
  public float getMaxMana() { return 0f; }

  // ─────────────────────────────────────────────── Class selection

  /** Selects the given class. Does not cost any points. */
  public void selectClass(String classId) {
    this.selectedClassId = classId;
  }

  public String getSelectedClassId() {
    return selectedClassId;
  }

  public Optional<PlayerClassDefinition> getSelectedClass() {
    if (selectedClassId == null) {
      return Optional.empty();
    }
    return ClassRegistry.findById(selectedClassId);
  }

  // ─────────────────────────────────────────────── Class ability spending

  /**
   * Spends 1 CP on the ability identified by {@code key} (format: "classId:abilityIndex").
   * Also selects that class, replacing the "Adventurer" placeholder.
   * Does nothing if CP is already exhausted.
   */
  public void spendCpOnAbility(String key) {
    if (remainingCp <= 0) {
      return;
    }
    remainingCp--;
    spentAbilityCp.merge(key, 1, Integer::sum);
    // Auto-select the class when the first point is spent in it
    int sep = key.lastIndexOf(':');
    if (sep > 0) {
      selectedClassId = key.substring(0, sep);
    }
  }

  /** Returns the total CP invested in all abilities of the given class. */
  public int getTotalCpSpentForClass(String classId) {
    String prefix = classId + ":";
    return spentAbilityCp.entrySet().stream()
        .filter(e -> e.getKey().startsWith(prefix))
        .mapToInt(Map.Entry::getValue)
        .sum();
  }

  /** Returns how many CP have been spent on the given ability key. */
  public int getSpentCp(String key) {
    return spentAbilityCp.getOrDefault(key, 0);
  }

  public int getRemainingCp() {
    return remainingCp;
  }

  // ─────────────────────────────────────────────── Skill investment

  /**
   * Invests 1 SP into the given skill, incrementing its level by 1.
   * Does nothing if SP is already exhausted.
   */
  public void investSkillPoint(String skillId) {
    if (remainingSp <= 0) {
      return;
    }
    remainingSp--;
    skillLevels.merge(skillId, 1, Integer::sum);
  }

  /** Returns the current level of the given skill (0 if never invested). */
  public int getSkillLevel(String skillId) {
    return skillLevels.getOrDefault(skillId, 0);
  }

  public int getRemainingSkillPoints() {
    return remainingSp;
  }

  // ─────────────────────────────────────────────── Feat acquisition

  /**
   * Acquires the feat with the given id, deducting 1 FP.
   * Does nothing if FP is exhausted or the feat is already acquired.
   */
  public void acquireFeat(String featId) {
    if (remainingFp <= 0 || acquiredFeatIds.contains(featId)) {
      return;
    }
    remainingFp--;
    acquiredFeatIds.add(featId);
  }

  /** Returns true if the player has acquired the given feat. */
  public boolean hasFeat(String featId) {
    return acquiredFeatIds.contains(featId);
  }

  public int getRemainingFeatPoints() {
    return remainingFp;
  }

  // ─────────────────────────────────────────────── Read-only accessors for persistence

  public Map<String, Integer> getSpentAbilityCp() {
    return Collections.unmodifiableMap(spentAbilityCp);
  }

  public Map<String, Integer> getSkillLevels() {
    return Collections.unmodifiableMap(skillLevels);
  }

  public Set<String> getAcquiredFeatIds() {
    return Collections.unmodifiableSet(acquiredFeatIds);
  }

  // ─────────────────────────────────────────────── Bulk restore (used by save system)

  /**
   * Restores all RPG state from saved data. Called during world load.
   */
  public void restore(String classId,
                      Map<String, Integer> abilityCp,
                      Map<String, Integer> skills,
                      Set<String> feats,
                      int cp, int sp, int fp) {
    this.selectedClassId = classId;
    this.spentAbilityCp.clear();
    this.spentAbilityCp.putAll(abilityCp);
    this.skillLevels.clear();
    this.skillLevels.putAll(skills);
    this.acquiredFeatIds.clear();
    this.acquiredFeatIds.addAll(feats);
    this.remainingCp = cp;
    this.remainingSp = sp;
    this.remainingFp = fp;
  }
}
