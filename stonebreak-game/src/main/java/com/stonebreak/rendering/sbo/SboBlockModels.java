package com.stonebreak.rendering.sbo;

import com.openmason.engine.format.mesh.ParsedMeshData;
import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.voxel.stamp.BlockModelSource;
import com.stonebreak.format.sbo.SBOParseResult;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An SBO as the engine's stamp pipeline wants it (OpenMason#88): the neutral {@link BlockModelSource}
 * (default mesh + per-state meshes). The engine never sees the SBO container itself.
 */
public final class SboBlockModels {

    private SboBlockModels() {
    }

    /** The block model of {@code sbo}; null when it has no mesh (a texture-only SBO). */
    public static BlockModelSource of(SBOParseResult sbo) {
        if (sbo == null || sbo.meshData() == null) {
            return null;
        }
        Map<String, ParsedMeshData> states = new LinkedHashMap<>();
        if (sbo.hasStates()) {
            for (Map.Entry<String, OMOReader.ReadResult> e : sbo.stateOmoData().entrySet()) {
                if (e.getValue() != null && e.getValue().meshData() != null) {
                    states.put(e.getKey(), e.getValue().meshData());
                }
            }
        }
        return new BlockModelSource(sbo.meshData(), states);
    }

    /** {@link #of(SBOParseResult)} for every block, keeping order; meshless SBOs are left out. */
    public static <K> Map<K, BlockModelSource> of(Map<K, SBOParseResult> sbos) {
        Map<K, BlockModelSource> out = new LinkedHashMap<>();
        sbos.forEach((k, sbo) -> {
            BlockModelSource model = of(sbo);
            if (model != null) {
                out.put(k, model);
            }
        });
        return out;
    }
}
