package com.openmason.main.systems.menus.dialogs;

import com.openmason.api.asset.AssetEditorSection;
import com.openmason.api.asset.AssetSectionContribution;
import com.openmason.api.asset.AssetSectionHost;
import com.openmason.api.asset.AssetSurface;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import imgui.ImGui;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The contributed {@link AssetEditorSection}s of one SBO/SBE window (#431): creates them from the
 * registered {@link AssetSectionContribution}s for the window's surface, merges their tabs with the
 * window's core tabs, and fans load / write / validate / render out to them. A section that throws
 * is logged and shown as an error line; it never takes the window down.
 *
 * @param <M> the surface's manifest model
 */
public final class AssetSectionSet<M> {

    private static final Logger logger = LoggerFactory.getLogger(AssetSectionSet.class);

    /** The registered sections (every surface); wired to the plugin registries by {@code UiComposition}. */
    private static volatile Supplier<List<AssetSectionContribution<?>>> source = List::of;

    /** Where contributions come from: the editor's registries, or a fixed list in tests (null = none). */
    public static void setSource(Supplier<List<AssetSectionContribution<?>>> contributions) {
        source = contributions != null ? contributions : List::of;
    }

    /** One tab of a window: a core tab ({@code section == null}) or a contributed section's. */
    public record Tab(String key, String label, int order, AssetEditorSection<?> section) {
    }

    /** A problem reported by a section. */
    public record Problem(AssetEditorSection<?> section, String message) {
    }

    private record Entry<M>(AssetSectionContribution<M> contribution, AssetEditorSection<M> section) {
    }

    private final AssetSurface<M> surface;
    private final AssetSectionHost host;
    private List<Entry<M>> entries = List.of();

    public AssetSectionSet(AssetSurface<M> surface, AssetSectionHost host) {
        this.surface = Objects.requireNonNull(surface);
        this.host = Objects.requireNonNull(host);
    }

    /** The contributions currently registered for this surface, by order. */
    private List<AssetSectionContribution<M>> registered() {
        List<AssetSectionContribution<M>> out = new ArrayList<>();
        for (AssetSectionContribution<?> c : source.get()) {
            AssetSectionContribution<M> typed = c.as(surface);
            if (typed != null) {
                out.add(typed);
            }
        }
        out.sort(Comparator.comparingInt(AssetSectionContribution::order));
        return out;
    }

    /** Re-creates the sections when the registered contributions changed (plugins load after the windows). */
    private void refresh() {
        List<AssetSectionContribution<M>> now = registered();
        boolean same = now.size() == entries.size();
        for (int i = 0; same && i < now.size(); i++) {
            same = now.get(i) == entries.get(i).contribution();
        }
        if (same) {
            return;
        }
        close();
        List<Entry<M>> created = new ArrayList<>();
        for (AssetSectionContribution<M> c : now) {
            try {
                created.add(new Entry<>(c, c.create(host)));
            } catch (RuntimeException e) {
                logger.error("Asset section {} could not be created on {}", c.id(), surface, e);
            }
        }
        entries = List.copyOf(created);
    }

    /** True when any section is installed for this surface (after the last {@link #load}). */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Picks up newly registered sections and loads every section from {@code manifest}. */
    public void load(M manifest) {
        refresh();
        for (Entry<M> e : entries) {
            try {
                e.section().load(manifest);
            } catch (RuntimeException ex) {
                logger.error("Asset section {} failed to load", e.contribution().id(), ex);
            }
        }
    }

    /** {@code manifest} with every section's form applied, in order. */
    public M write(M manifest) {
        M out = manifest;
        for (Entry<M> e : entries) {
            try {
                M next = e.section().write(out);
                if (next != null) {
                    out = next;
                }
            } catch (RuntimeException ex) {
                logger.error("Asset section {} failed to write; its data is kept as loaded",
                        e.contribution().id(), ex);
            }
        }
        return out;
    }

    /** The first save-blocking problem, or null. */
    public Problem firstError() {
        for (Entry<M> e : entries) {
            String err = guarded(e, e.section()::validate);
            if (err != null) {
                return new Problem(e.section(), err);
            }
        }
        return null;
    }

    /** Every overridable conflict, in order. */
    public List<Problem> conflicts() {
        List<Problem> out = new ArrayList<>();
        for (Entry<M> e : entries) {
            String c = guarded(e, e.section()::conflict);
            if (c != null) {
                out.add(new Problem(e.section(), c));
            }
        }
        return out;
    }

    /**
     * Runs {@code write} once every conflicted section let it through: each one may ask the person
     * first ({@link AssetEditorSection#confirmWrite}) and continue the chain only on confirmation.
     */
    public void confirmThenWrite(Runnable write) {
        confirmFrom(conflicts(), 0, write);
    }

    private void confirmFrom(List<Problem> conflicts, int index, Runnable write) {
        if (index >= conflicts.size()) {
            write.run();
            return;
        }
        AssetEditorSection<?> section = conflicts.get(index).section();
        Runnable next = () -> confirmFrom(conflicts, index + 1, write);
        boolean tookOver;
        try {
            tookOver = section.confirmWrite(next);
        } catch (RuntimeException e) {
            logger.error("Asset section '{}' failed to confirm a write", section.title(), e);
            tookOver = false;
        }
        if (!tookOver) {
            next.run();
        }
    }

    /** The core tabs plus every {@link AssetEditorSection.Placement#TAB TAB} section, by order. */
    public List<Tab> tabs(List<Tab> coreTabs) {
        List<Tab> out = new ArrayList<>(coreTabs);
        for (Entry<M> e : entries) {
            if (e.section().placement() == AssetEditorSection.Placement.TAB) {
                out.add(new Tab(e.contribution().id(), e.section().title(), e.contribution().order(), e.section()));
            }
        }
        out.sort(Comparator.comparingInt(Tab::order)); // stable: core tabs keep their order on ties
        return List.copyOf(out);
    }

    /** Draws {@code section}'s form (a {@link Tab#section()}). */
    public void renderSection(AssetEditorSection<?> section) {
        try {
            section.render();
        } catch (RuntimeException e) {
            logger.error("Asset section '{}' failed to render", section.title(), e);
            ThemedWidgets.inlineError("This section failed to draw (see the log).");
        }
    }

    /** Draws the {@link AssetEditorSection.Placement#METADATA METADATA} sections, each under its title. */
    public void renderMetadataSections() {
        for (Entry<M> e : entries) {
            if (e.section().placement() == AssetEditorSection.Placement.METADATA) {
                ThemedWidgets.sectionLabel(e.section().title());
                ImGui.pushID(e.contribution().id());
                renderSection(e.section());
                ImGui.popID();
            }
        }
    }

    /** True when {@code section} is drawn on the Metadata tab. */
    public static boolean onMetadataTab(AssetEditorSection<?> section) {
        return section.placement() == AssetEditorSection.Placement.METADATA;
    }

    /** Draws every section's overlays (popups), whatever tab is shown. */
    public void renderOverlays() {
        for (Entry<M> e : entries) {
            try {
                e.section().renderOverlays();
            } catch (RuntimeException ex) {
                logger.error("Asset section {} failed to render its overlays", e.contribution().id(), ex);
            }
        }
    }

    /** Tells every section the manifest was saved. */
    public void written() {
        for (Entry<M> e : entries) {
            try {
                e.section().written();
            } catch (RuntimeException ex) {
                logger.error("Asset section {} failed after a save", e.contribution().id(), ex);
            }
        }
    }

    /** Releases every section (GL context current). */
    public void close() {
        for (Entry<M> e : entries) {
            try {
                e.section().close();
            } catch (RuntimeException ex) {
                logger.error("Asset section {} failed to close", e.contribution().id(), ex);
            }
        }
        entries = List.of();
    }

    private static <M> String guarded(Entry<M> e, Supplier<String> check) {
        try {
            return check.get();
        } catch (RuntimeException ex) {
            logger.error("Asset section {} failed to validate", e.contribution().id(), ex);
            return "section '" + e.section().title() + "' failed to validate (see the log)";
        }
    }
}
