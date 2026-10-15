package com.stonebreak.rpg.classes;

import java.util.List;

/**
 * A specialization path: four nodes, one per tier, where {@code nodes.get(t).tier() == t}.
 *
 * @param focus   one-line summary shown under the path name
 * @param ability index of the class's core ability this path grants: a rank anywhere in the path unlocks it
 */
public record TalentPath(String name, String focus, int ability, List<TalentNode> nodes) {

  public static final int TIERS = 4;

  public TalentPath {
    nodes = List.copyOf(nodes);
    if (ability < 0) {
      throw new IllegalArgumentException(name + ": no core ability " + ability);
    }
    if (nodes.size() != TIERS) {
      throw new IllegalArgumentException(name + ": a path needs " + TIERS + " nodes, got " + nodes.size());
    }
    for (int t = 0; t < TIERS; t++) {
      if (nodes.get(t).tier() != t) {
        throw new IllegalArgumentException(name + ": node " + t + " is in tier " + nodes.get(t).tier());
      }
    }
  }
}
