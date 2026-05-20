package com.stonebreak.rpg.backgrounds;

import java.util.List;
import java.util.Optional;

public final class BackgroundRegistry {

    private BackgroundRegistry() {}

    public static final List<BackgroundDefinition> ALL = List.of(
        new BackgroundDefinition("adventurer",       "Adventurer",       "A wanderer ready for anything.",
            new int[]{0, 0, 0, 0, 0, 0}, new String[]{}),
        new BackgroundDefinition("arcane_associate", "Arcane Associate", "Your studies have sharpened your mind. Nerd",
            new int[]{0, -1, 0, 2, 0, 0}, new String[]{"STAFF"}),
        new BackgroundDefinition("mercenary",        "Mercenary",        "Steel and coin — that's your trade.",
            new int[]{2, 0, 1, -2, 0, 0}, new String[]{"SWORD"})
    );

    public static Optional<BackgroundDefinition> findById(String id) {
        return ALL.stream().filter(b -> b.id().equals(id)).findFirst();
    }
}
