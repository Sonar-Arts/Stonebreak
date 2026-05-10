package com.openmason.engine.format.sbo;

import com.openmason.engine.format.mesh.ParsedFaceMapping;
import com.openmason.engine.format.mesh.ParsedMaterialData;
import com.openmason.engine.format.mesh.ParsedMeshData;
import com.openmason.engine.format.omo.OMOFormat;

import java.util.List;

/**
 * Complete result of parsing an SBO file.
 * Contains all data needed to render or register a Stonebreak object.
 *
 * <p>For model-bearing SBOs (blocks): {@code omoDocument}, {@code meshData},
 * {@code faceMappings}, {@code materials}, and {@code defaultTexturePng} are
 * populated; {@code embeddedOmtBytes} is null.
 *
 * <p>For texture-only SBOs (sprite items, format 1.2+): {@code embeddedOmtBytes}
 * holds the raw OMT archive bytes; the OMO-derived fields are null.
 *
 * @param manifest          SBO manifest metadata (objectId, objectName, objectType, etc.)
 * @param omoDocument       embedded OMO manifest (geometry dimensions, model type); null for texture-only SBOs
 * @param meshData          parsed mesh geometry (vertices, indices, UVs); null for texture-only SBOs
 * @param faceMappings      per-face UV mappings (may be empty for legacy files); null for texture-only SBOs
 * @param materials         material definitions with texture PNG bytes; null for texture-only SBOs
 * @param defaultTexturePng raw bytes of the default texture.omt flattened to PNG (may be null)
 * @param embeddedOmtBytes  raw OMT archive bytes for texture-only SBOs (1.2+); null for model-bearing SBOs
 */
public record SBOParseResult(
        SBOFormat.Document manifest,
        OMOFormat.Document omoDocument,
        ParsedMeshData meshData,
        List<ParsedFaceMapping> faceMappings,
        List<ParsedMaterialData> materials,
        byte[] defaultTexturePng,
        byte[] embeddedOmtBytes
) {
    public String getObjectId() { return manifest.objectId(); }
    public String getObjectName() { return manifest.objectName(); }
    public String getObjectType() { return manifest.objectType(); }

    /** True when this SBO carries a 3D model (blocks, entities). */
    public boolean isModelBearing() { return manifest.isModelBearing(); }

    /** True when this SBO carries only a texture (sprite items, 1.2+). */
    public boolean isTextureOnly() { return manifest.isTextureOnly(); }
}
