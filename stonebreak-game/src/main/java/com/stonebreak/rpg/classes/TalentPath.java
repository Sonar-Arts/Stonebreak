package com.stonebreak.rpg.classes;

import java.util.List;

/**
 * A specialization path: four nodes, one per tier, where {@code nodes.get(t).tier() == t}.
 *
 * @param focus one-line summary shown under the path name
 */
public record TalentPath(String name, String focus, List<TalentNode> nodes) {

  public static final int TIERS = 4;

  public TalentPath {
    nodes = List.copyOf(nodes);
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
