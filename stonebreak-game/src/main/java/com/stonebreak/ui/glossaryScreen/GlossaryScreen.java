package com.stonebreak.ui.glossaryScreen;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.player.EntityDiscoveries;
import com.stonebreak.player.Player;
import com.stonebreak.player.PlayerStats;
import com.stonebreak.ui.runtime.screens.PresentationSlot;
import com.stonebreak.ui.runtime.screens.ScreenPresentation;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Entity Glossary screen: an entity list sidebar plus one detail pane for the selected mob. Owns the
 * selection state (selected entity, selected variant per entity) and shows as the shipped UI
 * document {@value #DOCUMENT_ID} ({@link #setPresentation}), which reads it through the UI host's
 * {@code glossary} roots and changes it with {@link #select} and {@link #cycleVariant(int)}.
 */
public class GlossaryScreen {

    /** The shipped document's screen id ({@code ui/documents/glossary.sbui}). */
    public static final String DOCUMENT_ID = "glossary";

    /** Selected variant index per entity type (index into the discovered list). */
    private final Map<EntityType, Integer> selectedVariant = new EnumMap<>(EntityType.class);

    private int selectedEntityIndex = 0;
    private final PresentationSlot presentation = new PresentationSlot();
    private java.util.function.Supplier<EntityDiscoveries> discoveries = GlossaryScreen::liveDiscoveries;
    private java.util.function.Supplier<PlayerStats> stats = GlossaryScreen::liveStats;

    public void render(int windowWidth, int windowHeight) {
        presentation.paint(windowWidth, windowHeight);
    }

    public boolean isVisible() { return presentation.isVisible(); }

    public void setVisible(boolean visible) {
        presentation.setVisible(visible);
    }

    /** Installs (or, with null, removes) the presentation. */
    public void setPresentation(ScreenPresentation p) {
        presentation.install(p);
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
        if (index < 0 || index >= EntityType.GLOSSARY_TYPES.length) {
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

    private int discoveredVariantCount() {
        return discoveredVariants(getSelectedEntityType(), discoveries()).size();
    }

    /** The variants of {@code type} the player has seen, in the type's own order. */
    public static List<String> discoveredVariants(EntityType type, EntityDiscoveries discoveries) {
        List<String> out = new ArrayList<>();
        String[] all = type.getTextureVariants();
        if (all == null) return out;
        for (String v : all) {
            if (discoveries != null && discoveries.hasSeenVariant(type, v)) out.add(v);
        }
        return out;
    }

    private void cycleVariant(EntityType type, int count, int delta) {
        int idx = getSelectedVariantIndex(type, count);
        selectedVariant.put(type, ((idx + delta) % count + count) % count);
    }
}
