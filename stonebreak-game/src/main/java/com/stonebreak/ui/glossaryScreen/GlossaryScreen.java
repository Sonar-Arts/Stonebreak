package com.stonebreak.ui.glossaryScreen;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.player.EntityDiscoveries;
import com.stonebreak.player.Player;
import com.stonebreak.player.PlayerStats;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;
import com.stonebreak.ui.runtime.screens.PresentationSlot;
import com.stonebreak.ui.runtime.screens.ScreenPresentation;

import java.util.EnumMap;
import java.util.Map;

/**
 * Entity Glossary screen: an entity list sidebar plus one detail pane for the
 * selected mob (see {@link GlossaryLayout} for the shared geometry). Owns all
 * interaction state — selected entity, selected variant per entity, and the
 * hover flags the renderer paints from — while {@link SkijaGlossaryRenderer}
 * stays draw-only.
 *
 * <p>Since #299 it may be shown as the shipped UI document {@value #DOCUMENT_ID}
 * ({@link #setPresentation}): selection stays here, the document reads it through the UI host's
 * {@code glossary} roots and changes it with {@link #select} and {@link #cycleVariant(int)}.
 */
public class GlossaryScreen {

    /** The shipped document's screen id ({@code ui/documents/glossary.sbui}). */
    public static final String DOCUMENT_ID = "glossary";

    private final SkijaGlossaryRenderer skijaRenderer;

    /** Selected variant index per entity type (index into the discovered list). */
    private final Map<EntityType, Integer> selectedVariant = new EnumMap<>(EntityType.class);

    private int selectedEntityIndex = 0;
    private int hoveredRowIndex = -1;
    private boolean leftArrowHovered;
    private boolean rightArrowHovered;
    private boolean backButtonHovered;
    private final PresentationSlot presentation = new PresentationSlot();
    private java.util.function.Supplier<EntityDiscoveries> discoveries = GlossaryScreen::liveDiscoveries;
    private java.util.function.Supplier<PlayerStats> stats = GlossaryScreen::liveStats;

    public GlossaryScreen(SkijaUIBackend backend) {
        this.skijaRenderer = new SkijaGlossaryRenderer(backend);
    }

    public void render(int windowWidth, int windowHeight) {
        if (!isVisible() || presentation.paint(windowWidth, windowHeight)) return;
        skijaRenderer.render(windowWidth, windowHeight, this);
    }

    public boolean isVisible() { return presentation.isVisible(); }

    public void setVisible(boolean visible) {
        presentation.setVisible(visible);
    }

    /** Installs (or, with null, removes) the alternative presentation; the legacy one is the default. */
    public void setPresentation(ScreenPresentation p) {
        presentation.install(p);
    }

    /** The legacy renderer (fixtures read its layout sink). */
    public SkijaGlossaryRenderer renderer() {
        return skijaRenderer;
    }

    // ─────────────────────────────────────────────── Data (the local player's by default)

    /** Where discoveries and kill counts come from; fixtures pin their own. */
    public void setDataSource(java.util.function.Supplier<EntityDiscoveries> discoveries,
                              java.util.function.Supplier<PlayerStats> stats) {
        this.discoveries = discoveries == null ? GlossaryScreen::liveDiscoveries : discoveries;
        this.stats = stats == null ? GlossaryScreen::liveStats : stats;
    }

    public EntityDiscoveries discoveries() {
        return discoveries.get();
    }

    public PlayerStats stats() {
        return stats.get();
    }

    private static EntityDiscoveries liveDiscoveries() {
        Player player = Game.getPlayer();
        return player != null ? player.getEntityDiscoveries() : null;
    }

    private static PlayerStats liveStats() {
        Player player = Game.getPlayer();
        return player != null ? player.getStats() : null;
    }

    // ─────────────────────────────────────────────── Selection state

    public int getSelectedEntityIndex() { return selectedEntityIndex; }

    public EntityType getSelectedEntityType() {
        return EntityType.GLOSSARY_TYPES[selectedEntityIndex];
    }

    /**
     * Selected variant index for a type, clamped to {@code [0, count)}.
     * Returns 0 when there are no discovered variants.
     */
    public int getSelectedVariantIndex(EntityType type, int count) {
        if (count <= 0) return 0;
        int idx = selectedVariant.getOrDefault(type, 0);
        return ((idx % count) + count) % count;
    }

    /** Selects sidebar row {@code index}. @return false when there is no such row */
    public boolean select(int index) {
        if (index < 0 || index >= GlossaryLayout.rowCount()) {
            return false;
        }
        selectedEntityIndex = index;
        return true;
    }

    /**
     * Steps the selected entity's shown variant by {@code delta}, wrapping, as the preview arrows do.
     *
     * @return false when it has fewer than two discovered variants (no arrows are shown)
     */
    public boolean cycleVariant(int delta) {
        int count = discoveredVariantCount();
        if (count <= 1) {
            return false;
        }
        cycleVariant(getSelectedEntityType(), count, delta);
        return true;
    }

    // ─────────────────────────────────────────────── Hover state (read by the renderer)

    public int getHoveredRowIndex() { return hoveredRowIndex; }
    public boolean isLeftArrowHovered() { return leftArrowHovered; }
    public boolean isRightArrowHovered() { return rightArrowHovered; }
    public boolean isBackButtonHovered() { return backButtonHovered; }

    // ─────────────────────────────────────────────── Input

    public boolean isBackButtonClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        if (!isVisible()) return false;
        float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
        return GlossaryLayout.contains(mouseX, mouseY,
                GlossaryLayout.backButtonRect(windowWidth, windowHeight, scale));
    }

    /**
     * Handles a left-click while the glossary is open: selects a sidebar row
     * or cycles the selected entity's variant when a preview arrow is hit.
     * Returns {@code true} if the click was consumed.
     */
    public boolean handleClick(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        if (!isVisible()) return false;
        float scale = com.stonebreak.config.Settings.getInstance().getUiScale();

        for (int i = 0; i < GlossaryLayout.rowCount(); i++) {
            if (GlossaryLayout.contains(mouseX, mouseY,
                    GlossaryLayout.listRowRect(i, windowWidth, windowHeight, scale))) {
                selectedEntityIndex = i;
                return true;
            }
        }

        int count = discoveredVariantCount();
        if (count > 1) {
            EntityType type = getSelectedEntityType();
            if (GlossaryLayout.contains(mouseX, mouseY,
                    GlossaryLayout.leftArrowRect(windowWidth, windowHeight, scale))) {
                cycleVariant(type, count, -1);
                return true;
            }
            if (GlossaryLayout.contains(mouseX, mouseY,
                    GlossaryLayout.rightArrowRect(windowWidth, windowHeight, scale))) {
                cycleVariant(type, count, +1);
                return true;
            }
        }
        return false;
    }

    public void updateHover(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        hoveredRowIndex = -1;
        leftArrowHovered = false;
        rightArrowHovered = false;
        backButtonHovered = false;
        if (!isVisible()) return;

        float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
        for (int i = 0; i < GlossaryLayout.rowCount(); i++) {
            if (GlossaryLayout.contains(mouseX, mouseY,
                    GlossaryLayout.listRowRect(i, windowWidth, windowHeight, scale))) {
                hoveredRowIndex = i;
                break;
            }
        }
        if (discoveredVariantCount() > 1) {
            leftArrowHovered = GlossaryLayout.contains(mouseX, mouseY,
                    GlossaryLayout.leftArrowRect(windowWidth, windowHeight, scale));
            rightArrowHovered = GlossaryLayout.contains(mouseX, mouseY,
                    GlossaryLayout.rightArrowRect(windowWidth, windowHeight, scale));
        }
        backButtonHovered = GlossaryLayout.contains(mouseX, mouseY,
                GlossaryLayout.backButtonRect(windowWidth, windowHeight, scale));
    }

    private int discoveredVariantCount() {
        return SkijaGlossaryRenderer.discoveredVariants(getSelectedEntityType(), discoveries()).size();
    }

    private void cycleVariant(EntityType type, int count, int delta) {
        int idx = getSelectedVariantIndex(type, count);
        selectedVariant.put(type, ((idx + delta) % count + count) % count);
    }

    public void cleanup() {
        if (skijaRenderer != null) skijaRenderer.dispose();
    }
}
