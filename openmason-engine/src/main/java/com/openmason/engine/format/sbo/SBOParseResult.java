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
 * @param manifest          SBO manifest metadata (objectId, objectName, objectType, etc.)
 * @param omoDocument       embedded OMO manifest (geometry dimensions, model type)
 * @param meshData          parsed mesh geometry (vertices, indices, UVs)
 * @param faceMappings      per-face UV mappings (may be empty for legacy files)
 * @param materials         material definitions with texture PNG bytes
 * @param defaultTexturePng raw bytes of the default texture.omt flattened to PNG (may be null)
 */
public record SBOParseResult(
        SBOFormat.Document manifest,
        OMOFormat.Document omoDocument,
        ParsedMeshData meshData,
        List<ParsedFaceMapping> faceMappings,
        List<ParsedMaterialData> materials,
        byte[] defaultTexturePng
) {
    public String getObjectId() { return manifest.objectId(); }
    public String getObjectName() { return manifest.objectName(); }
    public String getObjectType() { return manifest.objectType(); }
}
