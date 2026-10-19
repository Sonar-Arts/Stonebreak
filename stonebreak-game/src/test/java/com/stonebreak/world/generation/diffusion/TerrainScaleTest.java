package com.stonebreak.world.generation.diffusion;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The handshake's world config must name exactly the fields of TGMPipe's
 * {@code WorldConfig} — the service refuses a missing or unknown key, so drift would only
 * surface as a failed world load.
 */
class TerrainScaleTest {

    private static final Pattern KEY = Pattern.compile("\"(\\w+)\":");
    private static final Pattern FIELD = Pattern.compile("(?m)^    (\\w+): (?:int|float)$");

    @Test
    void worldConfigKeysMatchThePythonWorldConfig() throws IOException {
        Path python = Stream.of("..", ".")
                .map(root -> Path.of(root, "Models", "DaedalusTGM-Exp", "terrain_slm", "world", "world_config.py"))
                .filter(Files::isRegularFile)
                .findFirst().orElse(null);
        assumeTrue(python != null, "Models/DaedalusTGM-Exp is not checked out next to this module");

        Set<String> fields = new LinkedHashSet<>();
        Matcher f = FIELD.matcher(Files.readString(python));
        while (f.find()) fields.add(f.group(1));

        Set<String> sent = new LinkedHashSet<>();
        Matcher k = KEY.matcher(TerrainScale.worldConfigJson());
        while (k.find()) sent.add(k.group(1));
        sent.remove("world");

        assertEquals(fields, sent);
    }

    @Test
    void worldConfigCarriesTheGameScale() {
        String json = TerrainScale.worldConfigJson();
        assertEquals(true, json.startsWith("{\"world\":{\"world_height\":"));
        assertEquals(true, json.contains("\"downscale\":" + TerrainScale.DOWNSCALE + ","));
        assertEquals(true, json.contains("\"tile_size\":" + TerrainScale.TILE_SIZE_BLOCKS + ","));
    }
}
