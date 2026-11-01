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
 * @param soundBytes        embedded sound sample bytes keyed by ZIP entry filename (1.7+);
 *                          empty when no sound def embeds audio (resource-referenced defs
 *                          carry no bytes)
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
        Map<String, OMOReader.ReadResult> stateOmoData,
        Map<String, byte[]> stateClipBytes,
        Map<String, byte[]> soundBytes
) {
    public SBOParseResult {
        stateOmoBytes = stateOmoBytes == null ? Collections.emptyMap() : Map.copyOf(stateOmoBytes);
        stateOmtBytes = stateOmtBytes == null ? Collections.emptyMap() : Map.copyOf(stateOmtBytes);
        stateOmoData = stateOmoData == null ? Collections.emptyMap() : Map.copyOf(stateOmoData);
        stateClipBytes = stateClipBytes == null ? Collections.emptyMap() : Map.copyOf(stateClipBytes);
        soundBytes = soundBytes == null ? Collections.emptyMap() : Map.copyOf(soundBytes);
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

    /** True when any state embeds an animation clip (1.6+). */
    public boolean hasAnimations() { return !stateClipBytes.isEmpty(); }

    /** Raw {@code .omanim} bytes for a state's clip, or null when the state has none (1.6+). */
    public byte[] clipBytesFor(String stateName) { return stateClipBytes.get(stateName); }

    /**
     * The manifest {@link SBOFormat.AnimationRef} for a state (clip metadata
     * snapshot incl. the resolved loop flag), or null when the state has no
     * clip (1.6+).
     */
    public SBOFormat.AnimationRef animationFor(String stateName) {
        for (SBOFormat.StateEntry e : manifest.states()) {
            if (e.name().equals(stateName)) return e.animation();
        }
        return null;
    }

    /** True when this SBO declares one or more sound bindings (1.7+). */
    public boolean hasSounds() { return manifest.hasSounds(); }

    /** The manifest sound bindings, or null when the SBO declares none (1.7+). */
    public com.openmason.engine.format.sound.SoundData sounds() { return manifest.sounds(); }

    /** Bytes of an embedded sound sample by entry filename, or null (1.7+). */
    public byte[] soundBytesFor(String filename) { return soundBytes.get(filename); }
}
