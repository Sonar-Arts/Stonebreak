package com.stonebreak.format.sbo;

import com.openmason.engine.entity.model.EntityModelImporter;
import com.openmason.engine.entity.model.EntityModelSource;
import com.openmason.engine.format.spi.FormatCodec;

import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * SBO registered through the engine's format plugin points (#88): found by extension through
 * {@link com.openmason.engine.format.spi.Formats}, and an {@link EntityModelImporter} so the engine's
 * entity model loader reads it without naming it.
 */
public final class SBOCodec implements FormatCodec<SBOParseResult>, EntityModelImporter {

    public static final String ID = "sbo";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Stonebreak Object";
    }

    @Override
    public List<String> extensions() {
        return List.of(SBOFormat.FILE_EXTENSION);
    }

    @Override
    public Class<SBOParseResult> modelType() {
        return SBOParseResult.class;
    }

    @Override
    public SBOParseResult read(Path file) throws IOException {
        return new SBOParser().parse(file);
    }

    /**
     * The SBO's embedded {@code model.omo} as a model-only entity source (an attachable accessory).
     * Model-bearing SBOs always carry this entry — for stated SBOs it mirrors the default state's
     * model (format 1.3+ legacy mirror) — so no full parse is needed. Texture-only SBOs are refused.
     */
    @Override
    public EntityModelSource importEntity(String sourceId, InputStream in) throws IOException {
        byte[] omoBytes = null;
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (SBOFormat.EMBEDDED_OMO_FILENAME.equals(entry.getName())) {
                    omoBytes = zis.readAllBytes();
                }
                zis.closeEntry();
            }
        }
        if (omoBytes == null) {
            throw new IOException("SBO has no embedded model — texture-only SBOs cannot be attached: " + sourceId);
        }
        return EntityModelSource.modelOnly(sourceId, omoBytes);
    }
}
