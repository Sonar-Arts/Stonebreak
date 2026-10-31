package com.openmason.api.asset;

import java.util.Objects;
import java.util.function.Function;

/**
 * Registers an {@link AssetEditorSection} for one {@link AssetSurface} (#431). Each window that
 * shows the surface creates its own instance through {@link #factory()}.
 *
 * <p>Tabs (core and contributed) are ordered by {@code order}; the core uses these slots:
 * SBO editor Metadata 0, States 200, Sounds 500; SBO export Metadata 0, States 200; SBE editor
 * Metadata 0, States 100, Variants 200, Sounds 300; SBE export Metadata 0, States 100, Variants 200.
 * Metadata-placed sections are drawn in {@code order} too.
 *
 * @param id      unique id ({@code <plugin>.<surface>.<section>})
 * @param surface where the section is shown
 * @param order   position among the window's tabs
 * @param factory creates a section for one window
 */
public record AssetSectionContribution<M>(String id, AssetSurface<M> surface, int order,
                                          Function<AssetSectionHost, AssetEditorSection<M>> factory) {

    public AssetSectionContribution {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(factory, "factory");
        if (id.isBlank()) {
            throw new IllegalArgumentException("asset section id is blank");
        }
    }

    /** A new section for a window on {@link #surface()}. */
    public AssetEditorSection<M> create(AssetSectionHost host) {
        return Objects.requireNonNull(factory.apply(host), () -> "asset section " + id + " factory returned null");
    }

    /** This contribution typed for {@code wanted}, or null when it belongs to another surface. */
    @SuppressWarnings("unchecked")
    public <T> AssetSectionContribution<T> as(AssetSurface<T> wanted) {
        return surface == wanted ? (AssetSectionContribution<T>) this : null;
    }
}
