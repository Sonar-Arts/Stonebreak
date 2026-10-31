package com.openmason.api.asset;

import com.openmason.engine.format.sbe.SBEFormat;
import com.openmason.engine.format.sbo.SBOFormat;

import java.util.List;
import java.util.Objects;

/**
 * Where an {@link AssetEditorSection} is shown, and the manifest model it edits there (#431). The
 * editors edit a parsed manifest ({@code Document}); the export windows fill in the parameters of a
 * new file ({@code ExportParameters}, mutable). A section is written for one surface; a plugin that
 * wants the same data in an editor and an export window registers one section per surface.
 *
 * @param <M> the manifest model the section reads and writes
 */
public final class AssetSurface<M> {

    /** The SBO editor window: an opened {@code .sbo}'s manifest. */
    public static final AssetSurface<SBOFormat.Document> SBO_EDITOR =
            new AssetSurface<>("sbo.editor", "SBO Editor", SBOFormat.Document.class);
    /** The Export SBO window: the parameters of a new {@code .sbo}. */
    public static final AssetSurface<SBOFormat.ExportParameters> SBO_EXPORT =
            new AssetSurface<>("sbo.export", "Export SBO", SBOFormat.ExportParameters.class);
    /** The SBE editor window: an opened {@code .sbe}'s manifest. */
    public static final AssetSurface<SBEFormat.Document> SBE_EDITOR =
            new AssetSurface<>("sbe.editor", "SBE Editor", SBEFormat.Document.class);
    /** The Export SBE window: the parameters of a new {@code .sbe}. */
    public static final AssetSurface<SBEFormat.ExportParameters> SBE_EXPORT =
            new AssetSurface<>("sbe.export", "Export SBE", SBEFormat.ExportParameters.class);

    private final String id;
    private final String displayName;
    private final Class<M> modelType;

    private AssetSurface(String id, String displayName, Class<M> modelType) {
        this.id = Objects.requireNonNull(id);
        this.displayName = Objects.requireNonNull(displayName);
        this.modelType = Objects.requireNonNull(modelType);
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public Class<M> modelType() {
        return modelType;
    }

    /** Every surface, in a stable order. */
    public static List<AssetSurface<?>> all() {
        return List.of(SBO_EDITOR, SBO_EXPORT, SBE_EDITOR, SBE_EXPORT);
    }

    @Override
    public String toString() {
        return id;
    }
}
