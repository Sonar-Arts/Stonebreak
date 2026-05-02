package com.stonebreak.rpg.classes;

import java.util.ArrayList;
import java.util.List;

/** Immutable definition of a player class. */
public record PlayerClassDefinition(
    String id,
    String name,
    String description,
    List<ClassAbility> abilities
) {
  /**
   * Creates a class with the given number of placeholder abilities, each costing 1 CP.
   * Use this during early development; replace with explicit definitions later.
   */
  public static PlayerClassDefinition placeholder(String id, String name, int abilityCount) {
    List<ClassAbility> abs = new ArrayList<>(abilityCount);
    for (int i = 1; i <= abilityCount; i++) {
      abs.add(new ClassAbility(
          "Placeholder Ability " + i,
          "Placeholder ability description.",
          1
      ));
    }
    return new PlayerClassDefinition(id, name, "Placeholder description.", List.copyOf(abs));
  }
}
