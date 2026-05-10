package com.openmason.engine.format.sbo;

import com.openmason.engine.format.mesh.ParsedFaceMapping;
import com.openmason.engine.format.mesh.ParsedMaterialData;
import com.openmason.engine.format.mesh.ParsedMeshData;
import com.openmason.engine.format.omo.OMOFormat;
import com.openmason.engine.format.omo.OMOReader;

import java.util.Collections;
import java.util.List;
import java.util.Map;

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
        byte[] embeddedOmtBytes,
        Map<String, byte[]> stateOmoBytes,
        Map<String, byte[]> stateOmtBytes,
        Map<String, OMOReader.ReadResult> stateOmoData
) {
    public SBOParseResult {
        stateOmoBytes = stateOmoBytes == null ? Collections.emptyMap() : Map.copyOf(stateOmoBytes);
        stateOmtBytes = stateOmtBytes == null ? Collections.emptyMap() : Map.copyOf(stateOmtBytes);
        stateOmoData = stateOmoData == null ? Collections.emptyMap() : Map.copyOf(stateOmoData);
    }

    public String getObjectId() { return manifest.objectId(); }
    public String getObjectName() { return manifest.objectName(); }
    public String getObjectType() { return manifest.objectType(); }

    /** True when this SBO carries a 3D model (blocks, entities). */
    public boolean isModelBearing() { return manifest.isModelBearing(); }

    /** True when this SBO carries only a texture (sprite items, 1.2+). */
    public boolean isTextureOnly() { return manifest.isTextureOnly(); }

    /** True when this SBO declares one or more named states (1.3+). */
    public boolean hasStates() { return manifest.hasStates(); }

    /** Default state name (1.3+); null when {@link #hasStates()} is false. */
    public String defaultStateName() { return manifest.defaultStateName(); }
}
