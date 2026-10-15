package com.stonebreak.player;

import com.stonebreak.items.weapon.WeaponGroup;
import com.stonebreak.rpg.classes.ClassRegistry;
import com.stonebreak.rpg.classes.PlayerClassDefinition;
import com.stonebreak.rpg.classes.TalentNode;
import com.stonebreak.rpg.classes.TalentPath;
import com.stonebreak.rpg.feats.FeatRegistry;
import com.stonebreak.rpg.proficiency.ProficiencyRank;
import com.stonebreak.rpg.proficiency.WeaponProficiency;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Holds RPG character statistics and mutable progression state.
 * Vitals are live-read from the Player instance; everything else is managed here.
 */
public class CharacterStats {

  private final Player player;

  // ─────────────────────────────────────────────── Background

  private String selectedBackground = "adventurer";

  public String getSelectedBackground() { return selectedBackground; }
  public void setSelectedBackground(String id) { this.selectedBackground = id; }

  // ─────────────────────────────────────────────── Classes

  /** The primary class: {@code classInvestmentOrder[0]}, or the class picked before any rank. */
  private String selectedClassId = null;

  /**
   * Maps a talent-tree key to its rank: {@code "classId:abilityIndex"} for core abilities,
   * {@code "classId:p<path>:<tier>"} for path nodes. (The field name predates ranks; every rank
   * used to cost exactly 1 CP.)
   */
  private final Map<String, Integer> spentAbilityCp = new HashMap<>();

  /** Class ids in the order the player first ranked them; index sets the CP cost per rank. */
  private final List<String> classInvestmentOrder = new ArrayList<>();

  // ─────────────────────────────────────────────── Skills

  /** Maps skill id to its current level. */
  private final Map<String, Integer> skillLevels = new HashMap<>();

  // ─────────────────────────────────────────────── Feats

  private final Set<String> acquiredFeatIds = new HashSet<>();

  // ─────────────────────────────────────────────── Level / XP

  private int level = 1;
  private int xp    = 0;

  public int getLevel() { return level; }
  public int getXp()    { return xp; }

  /** XP required to advance from current level to level+1. */
  public int getXpForNextLevel() { return 200 + (level / 5) * 50; }

  /** Adds XP and handles level-up chain. */
  public void addXp(int amount) {
    if (amount <= 0) return;
    xp += amount;
    while (xp >= getXpForNextLevel()) {
      xp -= getXpForNextLevel();
      level++;
      int[] grant = grantForLevel(level);
      remainingCp += grant[0];
      remainingSp += grant[1];
      remainingFp += grant[2];
    }
  }

  // ─────────────────────────────────────────────── Point currencies

  private int remainingCp;
  private int remainingSp;
  private int remainingFp;

  /**
   * Per-level currency grants, in {CP, SP, FP} order.
   *
   * <p>Levels are 1-based. Level 1 is the starting grant a character is born
   * with; every level after that adds {@link #PER_LEVEL_GRANT}. Unspent points
   * accumulate, so a level-N character's pool is the sum of grants 1..N minus
   * what they spent. Old saves keep their stored (legacy, larger) pools.
   */
  private static final int[] STARTING_GRANT  = {1, 3, 1};
  private static final int[] PER_LEVEL_GRANT = {1, 1, 1};

  /** The {CP, SP, FP} grant awarded for reaching the given level. */
  private static int[] grantForLevel(int level) {
    return level <= 1 ? STARTING_GRANT : PER_LEVEL_GRANT;
  }

  /** Total CP granted across levels 1..level. */
  public static int grantedCp(int level) { return grantedFor(level, 0); }
  /** Total SP granted across levels 1..level. */
  public static int grantedSp(int level) { return grantedFor(level, 1); }
  /** Total FP granted across levels 1..level. */
  public static int grantedFp(int level) { return grantedFor(level, 2); }

  private static int grantedFor(int level, int idx) {
    int sum = 0;
    for (int l = 1; l <= level; l++) {
      sum += grantForLevel(l)[idx];
    }
    return sum;
  }

  public CharacterStats(Player player) {
    this.player = player;
    this.remainingCp = grantedCp(level);
    this.remainingSp = grantedSp(level);
    this.remainingFp = grantedFp(level);
  }

  // ─────────────────────────────────────────────── Undo history

  private enum UndoKind { CP, SP, FP, AP, WTP }

  /** A reversible allocation, pushed by each spend/invest/acquire/ability-raise. */
  private record UndoOp(UndoKind kind, String key, int index, String prevClassId) {}

  private final Deque<UndoOp> undoHistory = new ArrayDeque<>();

  /**
   * Reverses the most recent point allocation (CP/SP/FP/AP). Returns false when
   * there is nothing to undo. Used by character creation so a player can back
   * out an allocation before the world is created.
   */
  public boolean undoLastAllocation() {
    UndoOp op = undoHistory.pollLast();
    if (op == null) {
      return false;
    }
    switch (op.kind()) {
      case CP -> {
        int before = derivedSpentCp();
        spentAbilityCp.merge(op.key(), -1, Integer::sum);
        spentAbilityCp.remove(op.key(), 0);
        String classId = ClassRegistry.classIdOf(op.key());
        if (getClassRanks(classId) == 0) classInvestmentOrder.remove(classId);
        syncPrimaryClass(op.prevClassId());
        remainingCp -= derivedSpentCp() - before;
      }
      case SP -> {
        remainingSp++;
        skillLevels.merge(op.key(), -1, Integer::sum);
        skillLevels.remove(op.key(), 0);
      }
      case FP -> {
        remainingFp++;
        acquiredFeatIds.remove(op.key());
      }
      case AP -> {
        decrementAbilityScore(op.index());
        return true;
      }
      case WTP -> {
        WeaponGroup group = WeaponGroup.valueOf(op.key());
        setProficiency(group, ProficiencyRank.values()[Math.max(0, getProficiency(group).ordinal() - 1)]);
      }
    }
    if (player != null) player.updateDerivedStats();
    return true;
  }

  /** True when at least one allocation can be undone. */
  public boolean canUndo() { return !undoHistory.isEmpty(); }

  /** Drops all recorded allocations (fresh creation session / world creation). */
  public void clearUndoHistory() { undoHistory.clear(); }

  // ─────────────────────────────────────────────── Ability scores

  /** Index order: 0=STR 1=DEX 2=CON 3=INT 4=WIS 5=CHA */
  private int[] abilityScores = {10, 10, 10, 10, 10, 10};
  private int remainingAp = 27;

  // TODO: STR score to scale strength-based weapon damage (weapons not yet implemented)
  public int getStrength()     { return abilityScores[0]; }
  public int getDexterity()    { return abilityScores[1]; }
  public int getConstitution() { return abilityScores[2]; }
  public int getIntelligence() { return abilityScores[3]; }
  public int getWisdom()       { return abilityScores[4]; }
  public int getCharisma()     { return abilityScores[5]; }

  public int[] getAbilityScores() { return java.util.Arrays.copyOf(abilityScores, 6); }

  public int getRemainingAp() { return remainingAp; }

  /** Spends 1 AP to raise the ability at the given index (0–5). No-op if AP is 0. */
  public void incrementAbilityScore(int index) {
    if (remainingAp <= 0 || index < 0 || index >= 6) return;
    remainingAp--;
    abilityScores[index]++;
    if (player != null) player.updateDerivedStats();
    undoHistory.addLast(new UndoOp(UndoKind.AP, null, index, null));
  }

  /** Refunds 1 AP by lowering the ability at the given index. No-op if score is already 1. */
  public void decrementAbilityScore(int index) {
    if (index < 0 || index >= 6 || abilityScores[index] <= 1) return;
    abilityScores[index]--;
    remainingAp++;
    if (player != null) player.updateDerivedStats();
  }

  /** Standard modifier: floor((score - 10) / 2). */
  public int getModifier(int score) { return Math.floorDiv(score - 10, 2); }

  /** Applies background ability bonuses on top of point-buy scores. Called once at first spawn. */
  public void applyBackgroundBonuses(int[] bonuses) {
    if (bonuses == null || bonuses.length != 6) return;
    for (int i = 0; i < 6; i++) {
      abilityScores[i] = Math.max(1, abilityScores[i] + bonuses[i]);
    }
    if (player != null) player.updateDerivedStats();
  }

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

  // ─────────────────────────────────────────────── Vitals

  /** Live health from player. */
  public float getHealth()    { return player != null ? player.getHealth()    : 0f; }
  public float getMaxHealth() { return player != null ? player.getMaxHealth() : 20f; }

  // ─────────────────────────────────────────────── Derived resource caps

  /** Each CON point contributes HEALTH_PER_CON_POINT HP. CON 10 = 20 HP. */
  public float computeMaxHealth()  { return getConstitution() * com.stonebreak.player.PlayerConstants.HEALTH_PER_CON_POINT; }
  /** Each DEX point contributes STAMINA_PER_DEX_POINT stamina. DEX 10 = 100. */
  public float computeMaxStamina() { return getDexterity()    * com.stonebreak.player.PlayerConstants.STAMINA_PER_DEX_POINT; }
  /** Each WIS point contributes MANA_PER_WIS_POINT mana. WIS 10 = 50. */
  public float computeMaxMana()    { return getWisdom()       * com.stonebreak.player.PlayerConstants.MANA_PER_WIS_POINT; }
  /** Each WIS point contributes MANA_REGEN_PER_WIS_POINT mana/sec. WIS 10 = 2/sec. */
  public float computeManaRegen()  { return getWisdom()       * com.stonebreak.player.PlayerConstants.MANA_REGEN_PER_WIS_POINT; }

  public float getMana()       { return player != null ? player.getMana()       : 0f; }
  public float getMaxMana()    { return player != null ? player.getMaxMana()    : 0f; }
  public float getStamina()    { return player != null ? player.getStamina()    : 0f; }
  public float getMaxStamina() { return player != null ? player.getMaxStamina() : 0f; }

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

  // ─────────────────────────────────────────────── Class talent trees

  /**
   * Adds one rank to the talent-tree node {@code key}. Checks, in order: the key is valid, the
   * node is below its max rank, the tier gate, the parent rule, and that enough CP remain for the
   * class's cost per rank. A class gains its multiclass slot on its first rank.
   *
   * @return true if the rank was added
   */
  public boolean addRank(String key) {
    if (!canAddRank(key)) return false;
    String classId = ClassRegistry.classIdOf(key);
    String prevClassId = selectedClassId;
    int before = derivedSpentCp();
    spentAbilityCp.merge(key, 1, Integer::sum);
    if (!classInvestmentOrder.contains(classId)) classInvestmentOrder.add(classId);
    syncPrimaryClass(prevClassId);
    remainingCp -= derivedSpentCp() - before;
    undoHistory.addLast(new UndoOp(UndoKind.CP, key, -1, prevClassId));
    return true;
  }

  /** Former name of {@link #addRank}: buys one rank of a tree node (core abilities take none). */
  public void spendCpOnAbility(String key) {
    addRank(key);
  }

  /**
   * True when core ability {@code abilityKey} ({@code "<classId>:<index>"}) is unlocked: some path that grants it
   * holds a rank. Abilities are never bought; the paths a player invests in decide which they have.
   */
  public boolean hasAbility(String abilityKey) {
    for (TalentPath path : ClassRegistry.pathsGranting(abilityKey)) {
      for (TalentNode n : path.nodes()) {
        if (getRank(n.key()) > 0) return true;
      }
    }
    return false;
  }

  /** "Bloodletter or Frenzy": the paths that grant a core ability, for its lock text. */
  private static String grantingPathNames(String abilityKey) {
    List<String> names = ClassRegistry.pathsGranting(abilityKey).stream().map(TalentPath::name).toList();
    return names.size() <= 1 ? String.join("", names)
        : String.join(", ", names.subList(0, names.size() - 1)) + " or " + names.getLast();
  }

  public boolean canAddRank(String key) {
    return lockReason(key) == null;
  }

  /**
   * Why {@code key} can't take another rank right now, or null when it can. For the tree UI,
   * e.g. "Needs 15 ranks in Tiers I–II", "Needs Open Veins", "Not enough CP (costs 2)".
   */
  public String lockReason(String key) {
    if (ClassRegistry.isAbilityKey(key)) return "Comes with " + grantingPathNames(key);
    if (!ClassRegistry.isValidKey(key)) return "Unknown talent";
    if (getRank(key) >= ClassRegistry.maxRankOf(key)) return "Max rank";
    String classId = ClassRegistry.classIdOf(key);
    int tier = ClassRegistry.tierOf(key);
    int gate = PlayerConstants.CLASS_TREE_TIER_GATES[tier];
    if (ranksBelowTier(classId, tier) < gate) {
      String tiers = tier == 1 ? "Tier I" : "Tiers I–" + TIER_NUMERALS[tier - 1];
      return "Needs " + gate + " ranks in " + tiers;
    }
    String parent = ClassRegistry.parentKey(key);
    if (parent != null && getRank(parent) <= 0) {
      return "Needs " + ClassRegistry.findNode(parent).map(TalentNode::name).orElse(parent);
    }
    int cost = getCpCostPerRank(classId);
    if (remainingCp < cost) return "Not enough CP (costs " + cost + ")";
    return null;
  }

  private static final String[] TIER_NUMERALS = {"I", "II", "III", "IV"};

  /**
   * True if one rank of {@code key} could be removed without breaking the tree: every remaining
   * rank still meets its tier gate and parent rule, and earned WTP still covers spent WTP.
   * For a future respec; LIFO undo never needs it.
   */
  public boolean canRemoveRank(String key) {
    if (getRank(key) <= 0) return false;
    String classId = ClassRegistry.classIdOf(key);
    spentAbilityCp.merge(key, -1, Integer::sum);
    try {
      if (earnedWtp() < spentWtp()) return false;
      for (String k : ClassRegistry.classOfKey(key).map(ClassRegistry::keysOf).orElse(List.of())) {
        if (getRank(k) <= 0) continue;
        int tier = ClassRegistry.tierOf(k);
        if (ranksBelowTier(classId, tier) < PlayerConstants.CLASS_TREE_TIER_GATES[tier]) return false;
        String parent = ClassRegistry.parentKey(k);
        if (parent != null && getRank(parent) <= 0) return false;
      }
      return true;
    } finally {
      spentAbilityCp.merge(key, 1, Integer::sum);
    }
  }

  /**
   * Removes one rank of {@code key} when {@link #canRemoveRank} allows it, refunding CP at the
   * re-derived slot prices: a class that empties leaves the investment order and every class
   * after it moves to a cheaper slot. Records no undo. The primitive for a future respec.
   *
   * @return true if a rank was removed
   */
  public boolean removeRank(String key) {
    if (!canRemoveRank(key)) return false;
    int before = derivedSpentCp();
    spentAbilityCp.merge(key, -1, Integer::sum);
    spentAbilityCp.remove(key, 0);
    String classId = ClassRegistry.classIdOf(key);
    if (getClassRanks(classId) == 0) classInvestmentOrder.remove(classId);
    syncPrimaryClass(selectedClassId);
    remainingCp -= derivedSpentCp() - before;
    return true;
  }

  /** Ranks the class holds in path tiers strictly below {@code tier}. */
  private int ranksBelowTier(String classId, int tier) {
    int sum = 0;
    for (Map.Entry<String, Integer> e : spentAbilityCp.entrySet()) {
      int t = ClassRegistry.tierOf(e.getKey());
      if (classId.equals(ClassRegistry.classIdOf(e.getKey())) && t >= 0 && t < tier) {
        sum += e.getValue();
      }
    }
    return sum;
  }

  /** Rank of the given talent-tree node (0 if never ranked). */
  public int getRank(String key) {
    return spentAbilityCp.getOrDefault(key, 0);
  }

  /** @deprecated ranks, not CP: use {@link #getRank}. */
  @Deprecated
  public int getSpentCp(String key) {
    return getRank(key);
  }

  /** Total ranks across the class's paths. */
  public int getClassRanks(String classId) {
    String prefix = classId + ":";
    return spentAbilityCp.entrySet().stream()
        .filter(e -> e.getKey().startsWith(prefix))
        .mapToInt(Map.Entry::getValue)
        .sum();
  }

  /** @deprecated ranks, not CP: use {@link #getClassRanks}. */
  @Deprecated
  public int getTotalCpSpentForClass(String classId) {
    return getClassRanks(classId);
  }

  /** True when the class has at least one rank (multiclass-aware replacement for primary-class checks). */
  public boolean hasClass(String classId) {
    return classId != null && getClassRanks(classId) > 0;
  }

  /** Class ids in investment order; index 0 is the primary class. */
  public List<String> getClassInvestmentOrder() {
    return Collections.unmodifiableList(classInvestmentOrder);
  }

  /**
   * CP per rank in the class: {@code 1 + max(0, slot - 1) * MULTICLASS_PENALTY}, where slot is its
   * index in the investment order — or, if not invested yet, the slot it would take next.
   */
  public int getCpCostPerRank(String classId) {
    int slot = classInvestmentOrder.indexOf(classId);
    if (slot < 0) slot = classInvestmentOrder.size();
    return 1 + Math.max(0, slot - 1) * PlayerConstants.MULTICLASS_PENALTY;
  }

  /** CP the current ranks cost at today's slot prices. */
  public int derivedSpentCp() {
    int sum = 0;
    for (String id : classInvestmentOrder) {
      sum += getClassRanks(id) * getCpCostPerRank(id);
    }
    return sum;
  }

  public int getRemainingCp() {
    return remainingCp;
  }

  /** Primary class follows the investment order; with no ranks it keeps {@code fallback}. */
  private void syncPrimaryClass(String fallback) {
    selectedClassId = classInvestmentOrder.isEmpty() ? fallback : classInvestmentOrder.get(0);
  }

  /**
   * Data from before core abilities came from paths held ranks in them ({@code "<classId>:<index>"}): drops those
   * ranks, lets a class left with none leave the investment order, and refunds their CP at the re-derived prices.
   */
  private void dropCoreAbilityRanks() {
    if (spentAbilityCp.keySet().stream().allMatch(ClassRegistry::isPathKey)) return;
    int before = derivedSpentCp();
    spentAbilityCp.keySet().removeIf(k -> !ClassRegistry.isPathKey(k));
    classInvestmentOrder.removeIf(id -> getClassRanks(id) == 0);
    remainingCp += before - derivedSpentCp();
  }

  /** Every class with ranks: {@code selectedClassId} first if invested, then registry order. */
  private List<String> rebuildInvestmentOrder() {
    List<String> order = new ArrayList<>();
    if (hasClass(selectedClassId)) order.add(selectedClassId);
    for (PlayerClassDefinition c : ClassRegistry.ALL) {
      if (hasClass(c.id()) && !order.contains(c.id())) order.add(c.id());
    }
    return order;
  }

  // ─────────────────────────────────────────────── Weapon training (WTP)

  /**
   * WTP earned: {@code WTP_STARTING_GRANT} plus, per invested class,
   * {@code min(ranks / WTP_CLASS_RANKS_PER_POINT, WTP_MAX_PER_CLASS)}. Counted per class, not pooled.
   */
  public int earnedWtp() {
    int sum = PlayerConstants.WTP_STARTING_GRANT;
    for (String id : classInvestmentOrder) {
      sum += Math.min(getClassRanks(id) / PlayerConstants.WTP_CLASS_RANKS_PER_POINT,
          PlayerConstants.WTP_MAX_PER_CLASS);
    }
    return sum;
  }

  /** WTP spent: one per proficiency rank above Untrained, across all weapon groups. */
  public int spentWtp() {
    int sum = 0;
    for (WeaponGroup g : WeaponGroup.values()) sum += getProficiency(g).ordinal();
    return sum;
  }

  /** Unspent WTP, clamped to 0 (a legacy SP-bought proficiency can overdraw it). */
  public int getRemainingWtp() {
    return Math.max(0, earnedWtp() - spentWtp());
  }

  /** True with WTP to spend and the next rank's character-level gate met. */
  public boolean canTrainWeapon(WeaponGroup group) {
    return earnedWtp() - spentWtp() > 0 && WeaponProficiency.canRaise(getProficiency(group), level);
  }

  /** Spends 1 WTP to raise the group one proficiency rank. */
  public boolean trainWeapon(WeaponGroup group) {
    if (!canTrainWeapon(group)) return false;
    setProficiency(group, getProficiency(group).next());
    undoHistory.addLast(new UndoOp(UndoKind.WTP, group.name(), -1, null));
    return true;
  }

  // ─────────────────────────────────────────────── Skill investment

  /**
   * Invests 1 SP into the given skill, incrementing its level by 1.
   * Does nothing if SP is already exhausted. Weapon proficiency is not an SP skill: it is
   * bought with WTP through {@link #trainWeapon}.
   */
  public void investSkillPoint(String skillId) {
    if (remainingSp <= 0 || WeaponProficiency.groupOf(skillId).isPresent()) {
      return;
    }
    remainingSp--;
    skillLevels.merge(skillId, 1, Integer::sum);
    undoHistory.addLast(new UndoOp(UndoKind.SP, skillId, -1, null));
  }

  /** Returns the current level of the given skill (0 if never invested). */
  public int getSkillLevel(String skillId) {
    return skillLevels.getOrDefault(skillId, 0);
  }

  /** Rank in the given weapon group, derived from its proficiency skill level. */
  public ProficiencyRank getProficiency(WeaponGroup group) {
    return ProficiencyRank.fromLevel(getSkillLevel(WeaponProficiency.skillId(group)));
  }

  /**
   * Sets a weapon proficiency rank directly, spending no WTP and recording no undo
   * (debug / future class grants).
   */
  public void setProficiency(WeaponGroup group, ProficiencyRank rank) {
    String id = WeaponProficiency.skillId(group);
    if (rank == ProficiencyRank.UNTRAINED) {
      skillLevels.remove(id);
    } else {
      skillLevels.put(id, rank.ordinal());
    }
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
    undoHistory.addLast(new UndoOp(UndoKind.FP, featId, -1, null));
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

  // ─────────────────────────────────────────────── In-game session snapshot

  /** Immutable capture of the spendable RPG state, for save-or-discard on inventory exit. */
  public record RpgSnapshot(
      String classId,
      List<String> classInvestmentOrder,
      Map<String, Integer> spentAbilityCp,
      Map<String, Integer> skillLevels,
      Set<String> acquiredFeatIds,
      int[] abilityScores,
      int remainingAp,
      int remainingCp,
      int remainingSp,
      int remainingFp,
      int level,
      int xp) {}

  /** Captures the current RPG state (deep copies) so it can be restored later. */
  public RpgSnapshot snapshotRpgState() {
    return new RpgSnapshot(
        selectedClassId,
        List.copyOf(classInvestmentOrder),
        new HashMap<>(spentAbilityCp),
        new HashMap<>(skillLevels),
        new HashSet<>(acquiredFeatIds),
        java.util.Arrays.copyOf(abilityScores, 6),
        remainingAp, remainingCp, remainingSp, remainingFp,
        level, xp);
  }

  /** Restores a previously captured RPG state ("discard changes"). */
  public void restoreRpgState(RpgSnapshot s) {
    if (s == null) return;
    selectedClassId = s.classId();
    classInvestmentOrder.clear();
    classInvestmentOrder.addAll(s.classInvestmentOrder());
    spentAbilityCp.clear();
    spentAbilityCp.putAll(s.spentAbilityCp());
    skillLevels.clear();
    skillLevels.putAll(s.skillLevels());
    acquiredFeatIds.clear();
    acquiredFeatIds.addAll(s.acquiredFeatIds());
    abilityScores = java.util.Arrays.copyOf(s.abilityScores(), 6);
    remainingAp = s.remainingAp();
    remainingCp = s.remainingCp();
    remainingSp = s.remainingSp();
    remainingFp = s.remainingFp();
    level = s.level();
    xp = s.xp();
    if (player != null) player.updateDerivedStats();
  }

  /** True when the spendable RPG state differs from the given snapshot. */
  public boolean hasChangedSince(RpgSnapshot s) {
    if (s == null) return true;
    return !java.util.Objects.equals(selectedClassId, s.classId())
        || !classInvestmentOrder.equals(s.classInvestmentOrder())
        || !spentAbilityCp.equals(s.spentAbilityCp())
        || !skillLevels.equals(s.skillLevels())
        || !acquiredFeatIds.equals(s.acquiredFeatIds())
        || !java.util.Arrays.equals(abilityScores, s.abilityScores())
        || remainingAp != s.remainingAp()
        || remainingCp != s.remainingCp()
        || remainingSp != s.remainingSp()
        || remainingFp != s.remainingFp()
        || level != s.level()
        || xp != s.xp();
  }

  // ─────────────────────────────────────────────── Bulk restore (used by save system)

    /**
     * Restores all RPG state from saved data. Called during world load.
     *
     * @param classOrder the class investment order, or null for data that predates it (old
     *                   saves): the order is then rebuilt from the ranks and CP re-derived from
     *                   the level as {@code grantedCp(level) - derivedSpentCp()}
     */
    public void restore(String classId,
                        List<String> classOrder,
                        Map<String, Integer> abilityCp,
                        Map<String, Integer> skills,
                        Set<String> feats,
                        int cp, int sp, int fp,
                        int[] scores, int ap,
                        int level, int xp) {
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
        if (scores != null && scores.length == 6) {
          this.abilityScores = java.util.Arrays.copyOf(scores, 6);
        }
        this.remainingAp = ap;
        this.level = Math.max(1, level);
        this.xp    = Math.max(0, xp);
        this.classInvestmentOrder.clear();
        if (classOrder != null) {
            this.classInvestmentOrder.addAll(classOrder);
        } else {
            this.classInvestmentOrder.addAll(rebuildInvestmentOrder());
            this.remainingCp = Math.max(0, grantedCp(this.level) - derivedSpentCp());
        }
        dropCoreAbilityRanks();
        syncPrimaryClass(classId);
        if (player != null) player.updateDerivedStats();
    }

    // ─────────────────────────────────────────────── Network serialization

    /**
     * Serializes all character creation data to JSON for transmission to the server
     * on late-join character creation. Covers: background, class, ability scores,
     * CP spending, skill levels, feats, and point budgets.
     */
    public byte[] toCreationJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"background\":\"").append(escapeJson(selectedBackground)).append("\",");
        sb.append("\"class\":").append(selectedClassId != null ? "\"" + escapeJson(selectedClassId) + "\"" : "null");

        // Class investment order (sets each class's CP cost per rank)
        sb.append(",\"classOrder\":[");
        for (int i = 0; i < classInvestmentOrder.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escapeJson(classInvestmentOrder.get(i))).append('"');
        }
        sb.append("]");

        // Ability scores
        sb.append(",\"abilityScores\":[");
        for (int i = 0; i < 6; i++) {
            if (i > 0) sb.append(',');
            sb.append(abilityScores[i]);
        }
        sb.append("]");

        // Point budgets
        sb.append(",\"remainingAp\":").append(remainingAp);
        sb.append(",\"remainingCp\":").append(remainingCp);
        sb.append(",\"remainingSp\":").append(remainingSp);
        sb.append(",\"remainingFp\":").append(remainingFp);

        // CP spending map
        sb.append(",\"spentAbilityCp\":{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : spentAbilityCp.entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(escapeJson(e.getKey())).append("\":").append(e.getValue());
            first = false;
        }
        sb.append("}");

        // Skill levels map
        sb.append(",\"skillLevels\":{");
        first = true;
        for (Map.Entry<String, Integer> e : skillLevels.entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(escapeJson(e.getKey())).append("\":").append(e.getValue());
            first = false;
        }
        sb.append("}");

        // Acquired feats array
        sb.append(",\"acquiredFeats\":[");
        first = true;
        for (String f : acquiredFeatIds) {
            if (!first) sb.append(',');
            sb.append('"').append(escapeJson(f)).append('"');
            first = false;
        }
        sb.append("]");

        sb.append("}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Applies character creation data received from the server on late join.
     * This is a subset of {@link #restore} — it does NOT touch level/xp.
     */
    public void applyFromJoinData(byte[] json) {
        if (json == null || json.length == 0) return;
        String s = new String(json, StandardCharsets.UTF_8);

        // Background
        String bg = extractString(s, "background");
        if (bg != null) this.selectedBackground = bg;

        // Class
        String cls = extractString(s, "class");
        if (cls != null && !"null".equals(cls)) {
            this.selectedClassId = cls;
        }

        // Ability scores
        int[] scores = extractIntArray(s, "abilityScores");
        if (scores != null && scores.length == 6) {
            this.abilityScores = java.util.Arrays.copyOf(scores, 6);
        }

        // Point budgets
        int ap = extractInt(s, "remainingAp");
        if (ap >= 0) this.remainingAp = ap;
        int cp = extractInt(s, "remainingCp");
        if (cp >= 0) this.remainingCp = cp;
        int sp = extractInt(s, "remainingSp");
        if (sp >= 0) this.remainingSp = sp;
        int fp = extractInt(s, "remainingFp");
        if (fp >= 0) this.remainingFp = fp;

        // CP spending
        Map<String, Integer> cpMap = extractStringIntMap(s, "spentAbilityCp");
        if (cpMap != null) {
            this.spentAbilityCp.clear();
            this.spentAbilityCp.putAll(cpMap);
        }

        // Skill levels
        Map<String, Integer> skillMap = extractStringIntMap(s, "skillLevels");
        if (skillMap != null) {
            this.skillLevels.clear();
            this.skillLevels.putAll(skillMap);
        }

        // Acquired feats
        Set<String> feats = extractStringSet(s, "acquiredFeats");
        if (feats != null) {
            this.acquiredFeatIds.clear();
            this.acquiredFeatIds.addAll(feats);
        }

        // Class investment order; older peers don't send it, so rebuild it from the ranks
        List<String> order = extractStringList(s, "classOrder");
        this.classInvestmentOrder.clear();
        this.classInvestmentOrder.addAll(order != null ? order : rebuildInvestmentOrder());
        dropCoreAbilityRanks();
        syncPrimaryClass(this.selectedClassId);

        if (player != null) player.updateDerivedStats();
    }

    // ── Lightweight JSON helpers (mirrors JsonParsingUtil patterns) ──

    static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    static String extractString(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static int extractInt(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*(-?\\d+)";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    static int[] extractIntArray(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\\[([^\\]]+)\\]";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (!m.find()) return null;
        String[] parts = m.group(1).split(",");
        int[] arr = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { arr[i] = Integer.parseInt(parts[i].trim()); }
            catch (NumberFormatException ex) { arr[i] = 10; }
        }
        return arr;
    }

    static Map<String, Integer> extractStringIntMap(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\\{([^}]*)\\}";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (!m.find()) return null;
        Map<String, Integer> result = new HashMap<>();
        String content = m.group(1);
        if (content.trim().isEmpty()) return result;
        java.util.regex.Matcher em = java.util.regex.Pattern.compile(
                "\"([^\"]+)\"\\s*:\\s*(\\d+)").matcher(content);
        while (em.find()) {
            result.put(em.group(1), Integer.parseInt(em.group(2)));
        }
        return result;
    }

    static List<String> extractStringList(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\\[([^\\]]*)\\]";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (!m.find()) return null;
        List<String> result = new ArrayList<>();
        java.util.regex.Matcher em = java.util.regex.Pattern.compile("\"([^\"]*)\"").matcher(m.group(1));
        while (em.find()) {
            result.add(em.group(1));
        }
        return result;
    }

    static Set<String> extractStringSet(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\\[([^\\]]*)\\]";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(json);
        if (!m.find()) return null;
        Set<String> result = new HashSet<>();
        String content = m.group(1).trim();
        if (content.isEmpty()) return result;
        java.util.regex.Matcher em = java.util.regex.Pattern.compile("\"([^\"]*)\"").matcher(content);
        while (em.find()) {
            result.add(em.group(1));
        }
        return result;
    }
}
