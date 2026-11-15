package com.stonebreak.format.sbe;

import com.openmason.engine.entity.model.EntityModelImporter;
import com.openmason.engine.entity.model.EntityModelSource;
import com.openmason.engine.format.spi.FormatCodec;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * SBE registered through the engine's format plugin points (#88): found by extension through
 * {@link com.openmason.engine.format.spi.Formats}, and an {@link EntityModelImporter} so the engine's
 * entity model loader reads it without naming it.
 */
public final class SBECodec implements FormatCodec<SBEParser.ParsedSBE>, EntityModelImporter {

    public static final String ID = "sbe";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Stonebreak Entity";
    }

    @Override
    public List<String> extensions() {
        return List.of(SBEFormat.FILE_EXTENSION);
    }

    @Override
    public Class<SBEParser.ParsedSBE> modelType() {
        return SBEParser.ParsedSBE.class;
    }

    @Override
    public SBEParser.ParsedSBE read(Path file) throws IOException {
        return new SBEParser().parse(file);
    }

    /** The SBE as a neutral entity model: base + variant OMOs, per-state clips, sounds, FX. */
    @Override
    public EntityModelSource importEntity(String sourceId, InputStream in) throws IOException {
        SBEParser.ParsedSBE parsed = new SBEParser().parse(in);
        SBEFormat.Document doc = parsed.document();
        Map<String, byte[]> variants = new LinkedHashMap<>();
        for (SBEFormat.VariantEntry variant : doc.variants()) {
            byte[] bytes = parsed.variantModelFor(variant.name());
            if (bytes != null) {
                variants.put(variant.name(), bytes);
            }
        }
        // Per-state OMOs are pose snapshots of the same skeleton: only the clips are kept.
        Map<String, byte[]> clips = new LinkedHashMap<>();
        for (SBEFormat.StateEntry state : doc.states()) {
            byte[] clip = parsed.clipFor(state.name());
            if (clip != null) {
                clips.put(state.name(), clip);
            }
        }
        return new EntityModelSource(doc.objectId(), doc.objectName(), parsed.omoBytes(), variants, clips,
                doc.sounds(), parsed.soundBytes(), doc.fx());
    }
}
