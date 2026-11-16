package com.stonebreak.openmason;

import java.nio.file.Files;
import java.nio.file.Path;

/** The game checkout's resource tree, as the plugin's tests see it (their working directory is the module). */
public final class StonebreakTestTree {

    private StonebreakTestTree() {
    }

    /** {@code stonebreak-game/src/main/resources} of this checkout, or null when it is not there. */
    public static Path resources() {
        Path tree = Path.of("..", "stonebreak-game", "src", "main", "resources").toAbsolutePath().normalize();
        return Files.isDirectory(tree) ? tree : null;
    }
}
