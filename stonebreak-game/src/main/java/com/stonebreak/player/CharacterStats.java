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

  private String selectedClassId = null;

  /** Maps "classId:abilityIndex" to the number of CP spent on that ability. */
  private final Map<String, Integer> spentAbilityCp = new HashMap<>();

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
    }
  }

  // ─────────────────────────────────────────────── Point currencies

  private int remainingCp = 100;
  private int remainingSp = 100;
  private int remainingFp = 100;

  public CharacterStats(Player player) {
    this.player = player;
  }

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
