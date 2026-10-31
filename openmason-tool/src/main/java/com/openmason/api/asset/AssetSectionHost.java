package com.openmason.api.asset;

/**
 * What the window hosting an {@link AssetEditorSection} tells it about the asset being edited.
 * Values are live: they follow the core form (the Metadata tab) as the user types.
 */
public interface AssetSectionHost {

    /** The surface the section is shown on. */
    AssetSurface<?> surface();

    /** The object id typed on the Metadata tab (trimmed, may be blank). */
    String objectId();

    /**
     * The SBO object type in lower case ({@code block}, {@code item}, {@code entity},
     * {@code decoration}, {@code particle}, {@code other}); empty on the SBE surfaces.
     */
    String objectType();

    /**
     * True when the asset's payload is a texture (a texture-only SBO, or an SBO export started from
     * the Texture Editor) rather than a model.
     */
    boolean texturePayload();

    /** Marks the draft unsaved (editors; a no-op on the export windows). */
    void markDirty();
}
