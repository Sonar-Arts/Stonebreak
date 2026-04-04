package com.openmason.engine.format.mesh;

/**
 * Material data extracted from an OMO/SBO file.
 * Contains the raw PNG texture bytes for a single material.
 *
 * @param materialId   unique material identifier
 * @param name         human-readable material name
 * @param textureFile  filename within the OMO ZIP (e.g. "material_0.png")
 * @param texturePng   raw PNG image bytes
 * @param renderLayer  render layer ("OPAQUE", "CUTOUT", "TRANSLUCENT")
 * @param emissive     whether the material is emissive
 * @param tintColor    RGBA tint color
 */
public record ParsedMaterialData(
        int materialId,
        String name,
        String textureFile,
        byte[] texturePng,
        String renderLayer,
        boolean emissive,
        int tintColor
) {
}
