package com.stonebreak.rpg.backgrounds;

import java.util.List;
import java.util.Optional;

public final class BackgroundRegistry {

    private BackgroundRegistry() {}

    public static final List<BackgroundDefinition> ALL = List.of(
        new BackgroundDefinition("soldier",  "Soldier",  "Trained in the art of war, you served in a great army."),
        new BackgroundDefinition("scholar",  "Scholar",  "Years of study have sharpened your mind and broadened your knowledge."),
        new BackgroundDefinition("criminal", "Criminal", "You lived outside the law, surviving by cunning and daring."),
        new BackgroundDefinition("noble",    "Noble",    "Born into privilege, you carry the weight of a great name.")
    );

    public static Optional<BackgroundDefinition> findById(String id) {
        return ALL.stream().filter(b -> b.id().equals(id)).findFirst();
    }
}
