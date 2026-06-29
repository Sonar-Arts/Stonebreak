package com.stonebreak.ui.glossaryScreen;

import com.stonebreak.core.Game;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.player.EntityDiscoveries;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.UI.backend.skija.SkijaUIBackend;

import java.util.EnumMap;
import java.util.Map;

/**
 * Entity Glossary screen showing one "entity card" per mob type.
 * Follows the same pattern as {@link com.stonebreak.ui.statisticsScreen.StatisticsScreen}.
 */
public class GlossaryScreen {

    private static final float BASE_BUTTON_WIDTH  = SkijaGlossaryRenderer.BUTTON_WIDTH;
    private static final float BASE_BUTTON_HEIGHT = SkijaGlossaryRenderer.BUTTON_HEIGHT;

    private final SkijaGlossaryRenderer skijaRenderer;

    /** Selected variant index per entity type (index into the discovered list). */
    private final Map<EntityType, Integer> selectedVariant = new EnumMap<>(EntityType.class);

    private boolean visible = false;
    private boolean backButtonHovered = false;

    public GlossaryScreen(SkijaUIBackend backend) {
        this.skijaRenderer = new SkijaGlossaryRenderer(backend);
    }

    public void render(int windowWidth, int windowHeight) {
        if (!visible) return;
        skijaRenderer.render(windowWidth, windowHeight, backButtonHovered, this);
    }

    public boolean isVisible() { return visible; }

    public void setVisible(boolean visible) {
        this.visible = visible;
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

    public boolean isBackButtonClicked(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        return visible && hitBackButton(mouseX, mouseY, windowWidth, windowHeight);
    }

    /**
     * Handles a left-click while the glossary is open: cycles the variant when a
     * card's arrow is hit. Returns {@code true} if the click was consumed.
     */
    public boolean handleClick(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        if (!visible) return false;
        float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
        Player player = Game.getPlayer();
        EntityDiscoveries discoveries = (player != null) ? player.getEntityDiscoveries() : null;

        for (int i = 0; i < EntityType.GLOSSARY_TYPES.length; i++) {
            EntityType type = EntityType.GLOSSARY_TYPES[i];
            int count = SkijaGlossaryRenderer.discoveredVariants(type, discoveries).size();
            if (count <= 1) continue; // nothing to cycle

            if (inRect(mouseX, mouseY, SkijaGlossaryRenderer.leftArrowRect(i, windowWidth, windowHeight, scale))) {
                cycleVariant(type, count, -1);
                return true;
            }
            if (inRect(mouseX, mouseY, SkijaGlossaryRenderer.rightArrowRect(i, windowWidth, windowHeight, scale))) {
                cycleVariant(type, count, +1);
                return true;
            }
        }
        return false;
    }

    private void cycleVariant(EntityType type, int count, int delta) {
        int idx = getSelectedVariantIndex(type, count);
        selectedVariant.put(type, ((idx + delta) % count + count) % count);
    }

    private static boolean inRect(float px, float py, float[] r) {
        return px >= r[0] && px <= r[0] + r[2] && py >= r[1] && py <= r[1] + r[3];
    }

    public void updateHover(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        if (!visible) {
            backButtonHovered = false;
            return;
        }
        backButtonHovered = isBackButtonClicked(mouseX, mouseY, windowWidth, windowHeight);
    }

    public void cleanup() {
        if (skijaRenderer != null) skijaRenderer.dispose();
    }

    private boolean hitBackButton(float mouseX, float mouseY, int windowWidth, int windowHeight) {
        float scale = com.stonebreak.config.Settings.getInstance().getUiScale();
        float bw = BASE_BUTTON_WIDTH  * scale;
        float bh = BASE_BUTTON_HEIGHT * scale;
        float panelHeight = SkijaGlossaryRenderer.PANEL_HEIGHT * scale;
        float cx = windowWidth  / 2f;
        float cy = windowHeight / 2f;
        float panelBottom = cy + panelHeight / 2f;
        float x = cx - bw / 2f;
        float y = panelBottom - (SkijaGlossaryRenderer.BACK_BUTTON_BOTTOM_MARGIN * scale) - bh;
        return mouseX >= x && mouseX <= x + bw
            && mouseY >= y && mouseY <= y + bh;
    }
}
