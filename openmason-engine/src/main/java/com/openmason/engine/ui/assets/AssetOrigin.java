package com.openmason.engine.ui.assets;

/** Where a resolved dependency's bytes came from. */
public enum AssetOrigin {
    /** A snapshot embedded in the OMUI document ({@code assets/...}); owned by the document. */
    DOCUMENT,
    /** Collected into an SBUI at export ({@code assets/...} of the SBUI). */
    EXPORT,
    /** The open Open Mason project (editor only). */
    PROJECT,
    /** A declared shared runtime resource pack. */
    PACK,
    /** Resources packaged with the game or tool (classpath). */
    PACKAGED
}
