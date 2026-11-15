package com.stonebreak.openmason.editor;

import com.openmason.api.asset.AssetSurface;
import com.stonebreak.format.sbe.SBEFormat;
import com.stonebreak.format.sbo.SBOFormat;

/**
 * Where Stonebreak's SBO/SBE windows host asset sections (#431), declared by the plugin that owns the
 * formats (OpenMason#88). Section contributions match a surface by identity.
 */
public final class StonebreakSurfaces {

    /** The SBO editor window: an opened {@code .sbo}'s manifest. */
    public static final AssetSurface<SBOFormat.Document> SBO_EDITOR =
            AssetSurface.of("sbo.editor", "SBO Editor", SBOFormat.Document.class);
    /** The Export SBO window: the parameters of a new {@code .sbo}. */
    public static final AssetSurface<SBOFormat.ExportParameters> SBO_EXPORT =
            AssetSurface.of("sbo.export", "Export SBO", SBOFormat.ExportParameters.class);
    /** The SBE editor window: an opened {@code .sbe}'s manifest. */
    public static final AssetSurface<SBEFormat.Document> SBE_EDITOR =
            AssetSurface.of("sbe.editor", "SBE Editor", SBEFormat.Document.class);
    /** The Export SBE window: the parameters of a new {@code .sbe}. */
    public static final AssetSurface<SBEFormat.ExportParameters> SBE_EXPORT =
            AssetSurface.of("sbe.export", "Export SBE", SBEFormat.ExportParameters.class);

    private StonebreakSurfaces() {
    }
}
