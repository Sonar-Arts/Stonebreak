package com.stonebreak.mobs.sbe;

import java.util.List;
import java.util.Map;

/**
 * CPU-side resolved geometry for one OMO model (a cow variant).
 *
 * <p>The mesh arrays are taken verbatim from the OMO — vertices, texture
 * coordinates and part transforms are exactly as authored in the SBE, with no
 * remapping or re-anchoring. Parts index into the combined {@code indices}
 * buffer; faces within a part select their material texture by id.
 *
 * @param vertices   interleaved x,y,z vertex positions (part-local)
 * @param texCoords  interleaved u,v texture coordinates
 * @param indices    combined triangle index buffer
 * @param parts      model parts in draw order
 * @param materials  material id → decoded texture image
 */
public record SbeModelGeometry(
        float[] vertices,
        float[] texCoords,
        int[] indices,
        List<SbePart> parts,
        Map<Integer, MaterialImage> materials
) {
    public SbeModelGeometry {
        parts = parts == null ? List.of() : List.copyOf(parts);
        materials = materials == null ? Map.of() : Map.copyOf(materials);
    }
}
