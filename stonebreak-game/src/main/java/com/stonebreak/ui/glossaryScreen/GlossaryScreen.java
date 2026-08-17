package com.stonebreak.ui.glossaryScreen;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.player.EntityDiscoveries;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;

import java.util.EnumMap;
import java.util.Map;

/**
 * Entity Glossary screen: an entity list sidebar plus one detail pane for the
 * selected mob (see {@link GlossaryLayout} for the shared geometry). Owns all
 * interaction state — selected entity, selected variant per entity, and the
 * hover flags the renderer paints from — while {@link SkijaGlossaryRenderer}
 * stays draw-only.
 */
public class GlossaryScreen {

    private final SkijaGlossaryRenderer skijaRenderer;

    /** Selected variant index per entity type (index into the discovered list). */
    private final Map<EntityType, Integer> selectedVariant = new EnumMap<>(EntityType.class);

    private int selectedEntityIndex = 0;
    private int hoveredRowIndex = -1;
    private boolean leftArrowHovered;
    private boolean rightArrowHovered;
    private boolean backButtonHovered;
    private boolean visible = false;

    public GlossaryScreen(SkijaUIBackend backend) {
        this.skijaRenderer = new SkijaGlossaryRenderer(backend);
    }

    public void render(int windowWidth, int windowHeight) {
        if (!visible) return;
        skijaRenderer.render(windowWidth, windowHeight, this);
    }

    public boolean isVisible() { return visible; }

    public void setVisible(boolean visible) {
        this.visible = visible;
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

    // ─────────────────────────────────────────────── Hover state (read by the renderer)

    public int getHoveredRowIndex() { return hoveredRowIndex; }
    public boolean isLeftArrowHovered() { return leftArrowHovered; }
    public boolean isRightArrowHovered() { return rightArrowHovered; }
    public boolean isBackButtonHovered() { return backButtonHovered; }

    // ─────────────────────────────────────────────── Input

    public boolean isBackButtonClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        if (!visible) return false;
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
        if (!visible) return false;
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
        if (!visible) return;

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
        Player player = Game.getPlayer();
        EntityDiscoveries discoveries = (player != null) ? player.getEntityDiscoveries() : null;
        return SkijaGlossaryRenderer.discoveredVariants(getSelectedEntityType(), discoveries).size();
    }

    private void cycleVariant(EntityType type, int count, int delta) {
        int idx = getSelectedVariantIndex(type, count);
        selectedVariant.put(type, ((idx + delta) % count + count) % count);
    }

    public void cleanup() {
        if (skijaRenderer != null) skijaRenderer.dispose();
    }
}
