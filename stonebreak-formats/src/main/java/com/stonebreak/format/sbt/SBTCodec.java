package com.stonebreak.format.sbt;

import com.openmason.engine.format.spi.FormatCodec;
import com.openmason.engine.format.spi.TextureDecoder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * SBT registered through the engine's format plugin points (#88): as a whole format and as a
 * texture container around an OMT (what the engine's {@code TextureBytes} and {@code MTexture} decode,
 * and what the Open Mason UI editor edits through a sibling OMT).
 */
public final class SBTCodec implements FormatCodec<SBTParser.Result>, TextureDecoder {

    public static final String ID = "sbt";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Stonebreak Texture";
    }

    @Override
    public List<String> extensions() {
        return List.of(SBTFormat.FILE_EXTENSION);
    }

    @Override
    public Class<SBTParser.Result> modelType() {
        return SBTParser.Result.class;
    }

    @Override
    public SBTParser.Result read(Path file) throws IOException {
        return new SBTParser().read(file);
    }

    @Override
    public byte[] unwrapOmt(byte[] data) {
        try {
            return new SBTParser().read(data).omtBytes();
        } catch (IOException | RuntimeException notSbt) {
            return null;
        }
    }

    /**
     * {@code container} with its OMT replaced, keeping the SBT's identity and metadata (id, name, type,
     * pack, author, description) — the UI editor's "edit the layers, re-wrap on save" loop.
     */
    @Override
    public byte[] rewrap(byte[] container, byte[] omtBytes) throws IOException {
        SBTFormat.Document m = new SBTParser().read(container).manifest();
        SBTFormat.ExportParameters p = new SBTFormat.ExportParameters();
        p.setTextureId(m.textureId());
        p.setTextureName(m.textureName());
        p.setTextureType(SBTFormat.TextureType.fromId(m.textureType()));
        p.setTexturePack(m.texturePack());
        p.setAuthor(m.author());
        p.setDescription(m.description());
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("sbt-rewrap");
        try {
            java.nio.file.Path omt = java.nio.file.Files.write(dir.resolve("texture.omt"), omtBytes);
            java.nio.file.Path out = dir.resolve("texture" + SBTFormat.FILE_EXTENSION);
            if (!new SBTSerializer().export(p, omt, out.toString())) {
                return null;
            }
            return java.nio.file.Files.readAllBytes(out);
        } finally {
            try (var files = java.nio.file.Files.list(dir)) {
                for (java.nio.file.Path f : files.toList()) {
                    java.nio.file.Files.deleteIfExists(f);
                }
            }
            java.nio.file.Files.deleteIfExists(dir);
        }
    }
}
