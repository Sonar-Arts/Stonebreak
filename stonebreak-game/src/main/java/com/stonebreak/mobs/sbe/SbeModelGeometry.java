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
 * @param restMinY   lowest model-space Y across all rendered vertices in the
 *                   rest pose — the model's "feet". Renderers ground-anchor a
 *                   mob by placing this at {@code position.y - legHeight}, so
 *                   a mob never floats or sinks regardless of where the model
 *                   author put the origin.
 * @param attachmentPoints authored attachment points (sockets) where other
 *                   models can be mounted at runtime; empty for most models
 */
public record SbeModelGeometry(
        float[] vertices,
        float[] texCoords,
        int[] indices,
        List<SbePart> parts,
        Map<Integer, MaterialImage> materials,
        float restMinY,
        List<SbeAttachmentPoint> attachmentPoints
) {
    public SbeModelGeometry {
        parts = parts == null ? List.of() : List.copyOf(parts);
        materials = materials == null ? Map.of() : Map.copyOf(materials);
        attachmentPoints = attachmentPoints == null ? List.of() : List.copyOf(attachmentPoints);
    }
}
