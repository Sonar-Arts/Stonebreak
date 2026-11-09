package com.stonebreak.fx.sbfx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.format.fx.FxData;
import com.openmason.engine.format.fx.FxDef;
import com.openmason.engine.format.fx.FxJson;
import com.openmason.engine.format.fx.FxParamValue;
import com.openmason.engine.rendering.particles.FxParameter;
import com.openmason.engine.rendering.particles.FxSystem;
import com.openmason.engine.rendering.particles.FxWorld;
import com.openmason.engine.rendering.particles.ParticleSystem;
import com.stonebreak.fx.FxLibrary;
import com.stonebreak.fx.FxParams;
import com.stonebreak.fx.FxRuntime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped FX and the assets that bind them (Stonebreak#435/#437): every {@code sbfx/*.sbfx} loads the way the
 * game loads it at start-up and compiles in the engine runtime; every {@code fx[]} binding in a shipped SBO/SBE
 * names a shipped FX and only parameters that FX declares — a typo in asset data fails here, not silently in game.
 */
class ShippedFxTest {

    private static final Path RESOURCES = Path.of("src/main/resources");
    private static FxLibrary library;

    @BeforeAll
    static void load() {
        ShippedFx.LoadResult result = ShippedFx.load(ShippedFx.discover());
        assertEquals(List.of(), result.failures(), "every shipped .sbfx loads");
        library = new FxLibrary(result.documents());
    }

    @Test
    void theFirstContentShips() {
        for (String id : List.of("stonebreak:block_debris", "stonebreak:leaf_litter", "stonebreak:torch_flame",
                "stonebreak:furnace_smoke", "stonebreak:water_splash", "stonebreak:footstep_dust")) {
            assertNotNull(library.find(id), id + " is shipped");
        }
        assertNotNull(library.find("block_debris"), "bare system ids resolve too");
    }

    @Test
    void everyShippedFxCompilesInTheRuntime() {
        try (FxWorld world = new FxWorld(new ParticleSystem(256))) {
            for (SbfxDocument doc : library.documents()) {
                world.register(doc.system());
            }
        }
    }

    @Test
    void shippedFxAreSelfContained() {
        for (SbfxDocument doc : library.documents()) {
            assertEquals(java.util.Set.of(), doc.fx().externalTextures(),
                    doc.objectId() + " embeds every sprite it uses");
        }
    }

    @Test
    void everyBindingNamesAShippedFxAndItsParameters() throws IOException {
        List<String> problems = new ArrayList<>();
        int bindings = 0;
        for (Map.Entry<Path, FxData> asset : boundAssets().entrySet()) {
            for (FxDef def : asset.getValue().fx()) {
                bindings++;
                String where = RESOURCES.relativize(asset.getKey()) + " " + def.event() + "->" + def.fx();
                FxSystem system = library.find(def.fx());
                if (system == null) {
                    problems.add(where + ": no shipped FX has that id");
                    continue;
                }
                for (Map.Entry<String, FxParamValue> p : def.params().entrySet()) {
                    FxParameter declared = FxParams.find(system, p.getKey());
                    if (declared == null || components(declared.type()) != p.getValue().size()) {
                        problems.add(where + ": parameter '" + p.getKey() + "' is not a "
                                + p.getValue().size() + "-component parameter of the FX");
                    }
                }
                if (def.tintFromTexture()) {
                    FxParameter tint = FxParams.find(system, FxRuntime.PARAM_TINT);
                    if (tint == null || tint.type() != FxParameter.Type.COLOR) {
                        problems.add(where + ": tintFromTexture but the FX has no 'tint' colour parameter");
                    }
                }
                if (def.event().equals(FxData.EVENT_AMBIENT) && !system.endless()) {
                    problems.add(where + ": an ambient binding needs an endless FX (it would stop on its own)");
                }
            }
        }
        assertEquals(List.of(), problems);
        assertTrue(bindings >= 28, "first-content bindings are present (found " + bindings + ")");
    }

    @Test
    void firstContentBindingsAreWhereTheGameLooksForThem() throws IOException {
        Map<Path, FxData> bound = boundAssets();
        assertFalse(events(bound, "sbo/blocks/SB_Stone.sbo").isEmpty());
        assertTrue(events(bound, "sbo/blocks/SB_Stone.sbo").containsAll(List.of("break", "hit")));
        assertTrue(events(bound, "sbo/blocks/SB_Dirt.sbo").contains("step"));
        assertTrue(events(bound, "sbo/blocks/SB_Torch_Placed.sbo").contains("ambient"));
        assertTrue(events(bound, "sbo/blocks/SB_Furnace.sbo").contains("ambient"));
        assertTrue(events(bound, "sbe/Mobs/SB_Player.sbe").contains("splash"));
    }

    private static List<String> events(Map<Path, FxData> bound, String asset) {
        FxData data = bound.get(RESOURCES.resolve(asset));
        return data == null ? List.of() : List.copyOf(data.events());
    }

    private static int components(FxParameter.Type type) {
        return switch (type) {
            case FLOAT -> 1;
            case VECTOR -> 3;
            case COLOR -> 4;
        };
    }

    /** Every shipped SBO/SBE (source tree, not target/classes) with an fx[] section. */
    static Map<Path, FxData> boundAssets() throws IOException {
        Map<Path, FxData> out = new java.util.TreeMap<>();
        ObjectMapper mapper = new ObjectMapper();
        for (String folder : List.of("sbo", "sbe")) {
            try (Stream<Path> files = Files.walk(RESOURCES.resolve(folder))) {
                for (Path file : files.filter(p -> p.toString().endsWith(".sbo") || p.toString().endsWith(".sbe"))
                        .toList()) {
                    try (ZipFile zip = new ZipFile(file.toFile())) {
                        var entry = zip.getEntry("manifest.json");
                        if (entry == null) continue;
                        JsonNode root = mapper.readTree(zip.getInputStream(entry));
                        FxData data = FxJson.read(root);
                        if (data != null && !data.isEmpty()) {
                            out.put(file, data);
                        }
                    }
                }
            }
        }
        return out;
    }
}
