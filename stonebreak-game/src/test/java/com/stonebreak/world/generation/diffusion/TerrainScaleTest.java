package com.stonebreak.world.generation.diffusion;

import com.stonebreak.world.generation.NativeCubeGenerator.TerrainScalePreset;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The handshake's world configs (one per terrain scale, protocol v2) must name exactly the fields of TGMPipe's
 * {@code WorldConfig} — the service refuses a missing or unknown key, so drift would only
 * surface as a failed world load.
 */
class TerrainScaleTest {

    private static final Pattern KEY = Pattern.compile("\"(\\w+)\":");
    private static final Pattern FIELD = Pattern.compile("(?m)^    (\\w+): (?:int|float)(?: = [^\\n]*)?$");

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

        for (TerrainScalePreset preset : TerrainScalePreset.values()) {
            Set<String> sent = new LinkedHashSet<>();
            Matcher k = KEY.matcher(TerrainScale.worldConfigJson(preset));
            while (k.find()) sent.add(k.group(1));
            assertEquals(fields, sent, preset.name());
        }
    }

    @Test
    void handshakeListsOneWorldPerScaleInOrdinalOrder() {
        String json = TerrainScale.handshakeJson();
        StringBuilder expected = new StringBuilder("{\"worlds\":[");
        for (TerrainScalePreset p : TerrainScalePreset.values()) {
            expected.append(TerrainScale.worldConfigJson(p)).append(',');
        }
        expected.setLength(expected.length() - 1);
        assertEquals(expected.append("]}").toString(), json);
    }

    @Test
    void eachWorldCarriesItsScale() {
        String quarter = TerrainScale.worldConfigJson(TerrainScalePreset.ONE_TO_FOUR);
        assertTrue(quarter.startsWith("{\"world_height\":"));
        assertTrue(quarter.contains("\"horizontal_m\":60.0,") && quarter.contains("\"downscale\":2,"));
        assertTrue(quarter.contains("\"lowland_m_per_block\":16.0,"));
        assertTrue(quarter.contains("\"tile_size\":" + TerrainScale.TILE_SIZE_BLOCKS + ","));
        String half = TerrainScale.worldConfigJson(TerrainScalePreset.ONE_TO_TWO);
        assertTrue(half.contains("\"horizontal_m\":30.0,") && half.contains("\"downscale\":1,"));
        assertTrue(half.contains("\"lowland_m_per_block\":8.0,") && half.contains("\"highland_m_per_block\":19.0,"));
        assertEquals(2, TerrainScale.downscale(TerrainScalePreset.ONE_TO_FOUR));
        assertEquals(1, TerrainScale.downscale(TerrainScalePreset.ONE_TO_TWO));
    }
}
