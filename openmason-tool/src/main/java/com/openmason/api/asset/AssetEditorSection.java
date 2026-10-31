package com.openmason.api.asset;

/**
 * One block of game data in an SBO/SBE editor or export window, contributed by a plugin (#431).
 * The window keeps the generic container data (identity, states, clips, variants, sounds, render
 * layer, transparency); everything a game reads beyond that is edited by sections.
 *
 * <p>A section instance belongs to one window ({@link AssetSectionContribution#create}) and runs on
 * the UI thread. The window calls {@link #load} whenever the manifest is replaced (a file is
 * opened, an agent applies a patch, an export window is reset), {@link #render} while its tab is
 * shown, and {@link #write} to fold the section's form back into the manifest being saved. A
 * section must leave every field it does not own as it was in the model it is given, so data from
 * other sections, and data no installed section shows, survives a save.
 *
 * @param <M> the surface's manifest model ({@link AssetSurface#modelType()})
 */
public interface AssetEditorSection<M> {

    /** Where a section is drawn. */
    enum Placement {
        /** Its own tab, labelled {@link #title()}, ordered by {@link AssetSectionContribution#order()}. */
        TAB,
        /** Inside the Metadata tab, after the core Classification fields, under a {@link #title()} label. */
        METADATA
    }

    /** Tab label (or the section label for {@link Placement#METADATA}). */
    String title();

    default Placement placement() {
        return Placement.TAB;
    }

    /** Replaces the form with the manifest's data. */
    void load(M manifest);

    /** Draws the form (ImGui). Call {@link AssetSectionHost#markDirty()} on every edit. */
    void render();

    /** Drawn every frame the window is open, whatever tab is shown (popups). */
    default void renderOverlays() {
    }

    /** Null when the form can be written; otherwise the save-blocking problem. */
    default String validate() {
        return null;
    }

    /**
     * A problem a person may choose to save over (a duplicate id, say), or null. Agent writes refuse
     * it; a person is asked through {@link #confirmWrite}.
     */
    default String conflict() {
        return null;
    }

    /**
     * Gives the section a chance to ask before a person's save goes ahead (only called when
     * {@link #conflict()} is non-null). Return true when the section took over: it must call
     * {@code proceed} itself if the person confirms. Return false to let the save continue.
     */
    default boolean confirmWrite(Runnable proceed) {
        return false;
    }

    /**
     * The manifest with this section's form applied. Editors pass an immutable {@code Document} and
     * use the returned copy; export windows pass their mutable {@code ExportParameters} (return it).
     * Must not throw on an invalid form: keep the manifest's last good value instead.
     */
    M write(M manifest);

    /** The manifest was saved (caches derived from saved assets can be refreshed). */
    default void written() {
    }

    /** Releases GPU/UI resources; the window is closing. Runs with a current GL context. */
    default void close() {
    }
}
