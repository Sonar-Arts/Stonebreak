package com.stonebreak.rpg.classes;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable definition of a player class and its talent tree: an always-on core passive, the
 * core abilities (tier I, keyed {@code "<id>:<index>"}) and exactly {@link #PATHS} specialization
 * paths of four tiers each (keyed {@code "<id>:p<path>:<tier>"}).
 *
 * @param iconPath classpath resource path to a small icon shown next to the class name in
 *                 class-select UIs (e.g. {@code /ui/abilities/berserker/Rage.png}), or
 *                 {@code null} if the class has no icon yet.
 */
public record PlayerClassDefinition(
    String id,
    String name,
    String description,
    List<ClassAbility> abilities,
    String iconPath,
    CorePassive corePassive,
    List<TalentPath> paths
) {
  public static final int PATHS = 3;

  public PlayerClassDefinition {
    abilities = List.copyOf(abilities);
    paths = List.copyOf(paths);
    if (paths.size() != PATHS) {
      throw new IllegalArgumentException(id + ": a class needs " + PATHS + " paths, got " + paths.size());
    }
  }

  /**
   * Creates a class with the given number of placeholder abilities, a placeholder core passive
   * and three placeholder paths. Use this during early development; replace with explicit
   * definitions later.
   */
  public static PlayerClassDefinition placeholder(String id, String name, int abilityCount) {
    List<ClassAbility> abs = new ArrayList<>(abilityCount);
    for (int i = 1; i <= abilityCount; i++) {
      abs.add(new ClassAbility(
          "Placeholder Ability " + i,
          "Placeholder ability description.",
          1,
          null
      ));
    }
    return new PlayerClassDefinition(id, name, "Placeholder description.", abs, null,
        placeholderPassive(), placeholderPaths(id));
  }

  /** A stand-in core passive for classes whose tree is not designed yet. */
  public static CorePassive placeholderPassive() {
    return new CorePassive("Placeholder Passive", "Placeholder passive description.", null);
  }

  /** Three stand-in paths, each PASSIVE, ABILITY, MODIFIER, CAPSTONE from Tier I to IV. */
  public static List<TalentPath> placeholderPaths(String id) {
    TalentNodeType[] order = TalentNodeType.values();
    List<TalentPath> paths = new ArrayList<>(PATHS);
    for (int p = 0; p < PATHS; p++) {
      List<TalentNode> nodes = new ArrayList<>(TalentPath.TIERS);
      for (int t = 0; t < TalentPath.TIERS; t++) {
        TalentNodeType type = order[t];
        nodes.add(new TalentNode(ClassRegistry.pathKey(id, p, t), "Placeholder Node",
            "Placeholder node description.", type, t, type.defaultMaxRank(), null));
      }
      paths.add(new TalentPath("Placeholder Path " + (p + 1), "Placeholder focus.", nodes));
    }
    return paths;
  }
}
