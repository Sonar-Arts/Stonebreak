package com.stonebreak.ui.terrainMapper;

import com.stonebreak.ui.terrainMapper.config.TerrainMapperConfig;
import com.stonebreak.world.generation.TerrainGeneratorType;

/**
 * Pure layout math. Given a window size, computes the rectangular regions
 * for sidebar, map viewport, and footer. Shared by every renderer and mouse
 * handler so hit tests and draws agree on bounds.
 */
public final class TerrainMapperLayout {

    /** Axis-aligned rect in pixel space. */
    public record Rect(float x, float y, float width, float height) {
        public float right() { return x + width; }
        public float bottom() { return y + height; }
        public float centerX() { return x + width / 2f; }
        public boolean contains(float px, float py) {
            return px >= x && px <= x + width && py >= y && py <= y + height;
        }
    }

    private final Rect sidebar;
    private final Rect map;
    private final Rect footer;
    private final Rect title;
    private final Rect worldNameField;
    private final Rect seedField;
    private final Rect generatorListStart;
    private final Rect modeListStart;
    private final Rect spawnButton;
    private final Rect centerOnSpawnButton;

    /** @param modeCount mode buttons the selected generator shows (0 before one is picked) */
    public TerrainMapperLayout(int windowWidth, int windowHeight, int modeCount) {
        float w = Math.max(1, windowWidth);
        float h = Math.max(1, windowHeight);
        float footerY = h - TerrainMapperConfig.FOOTER_HEIGHT;
        this.sidebar = new Rect(0f, 0f, TerrainMapperConfig.SIDEBAR_WIDTH, footerY);
        this.map = new Rect(
                TerrainMapperConfig.SIDEBAR_WIDTH,
                0f,
                Math.max(0f, w - TerrainMapperConfig.SIDEBAR_WIDTH),
                footerY
        );
        this.footer = new Rect(0f, footerY, w, TerrainMapperConfig.FOOTER_HEIGHT);

        float pad = TerrainMapperConfig.SIDEBAR_PADDING;
        float innerWidth = TerrainMapperConfig.SIDEBAR_WIDTH - pad * 2f;

        this.title = new Rect(pad, pad, innerWidth, 32f);
        float cursorY = title.bottom() + TerrainMapperConfig.SIDEBAR_SECTION_GAP;

        // Name label + field
        cursorY += 16f; // leave room for label above field
        this.worldNameField = new Rect(pad, cursorY, innerWidth, TerrainMapperConfig.TEXT_FIELD_HEIGHT);
        cursorY = worldNameField.bottom() + TerrainMapperConfig.SIDEBAR_SECTION_GAP + 16f;

        this.seedField = new Rect(pad, cursorY, innerWidth, TerrainMapperConfig.TEXT_FIELD_HEIGHT);
        cursorY = seedField.bottom() + TerrainMapperConfig.SIDEBAR_SECTION_GAP + 16f;

        // Generator choice sits above the modes: the modes are the chosen generator's.
        this.generatorListStart = new Rect(pad, cursorY, innerWidth, TerrainMapperConfig.MODE_BUTTON_HEIGHT);
        cursorY = rowY(generatorListStart, TerrainGeneratorType.values().length)
                + TerrainMapperConfig.SIDEBAR_SECTION_GAP + 16f;

        this.modeListStart = new Rect(pad, cursorY, innerWidth, TerrainMapperConfig.MODE_BUTTON_HEIGHT);

        int modeRows = (modeCount + TerrainMapperConfig.MODE_BUTTON_COLUMNS - 1) / TerrainMapperConfig.MODE_BUTTON_COLUMNS;
        float spawnSectionY = rowY(modeListStart, modeRows)
                + TerrainMapperConfig.SIDEBAR_SECTION_GAP + 16f;
        this.spawnButton = new Rect(pad, spawnSectionY, innerWidth, TerrainMapperConfig.MODE_BUTTON_HEIGHT);
        this.centerOnSpawnButton = new Rect(pad,
                spawnSectionY + TerrainMapperConfig.MODE_BUTTON_HEIGHT + TerrainMapperConfig.MODE_BUTTON_SPACING,
                innerWidth, TerrainMapperConfig.MODE_BUTTON_HEIGHT);
    }

    public Rect sidebar() { return sidebar; }
    public Rect map() { return map; }
    public Rect footer() { return footer; }
    public Rect title() { return title; }
    public Rect worldNameField() { return worldNameField; }
    public Rect seedField() { return seedField; }

    /** Position of the first generator button; the rest stack beneath it, full width. */
    public Rect firstGeneratorButton() { return generatorListStart; }

    /** Top of the n-th generator button. */
    public float generatorButtonY(int index) {
        return rowY(generatorListStart, index);
    }

    /** Position of the first mode button; the rest fill a {@code MODE_BUTTON_COLUMNS}-wide grid. */
    public Rect firstModeButton() { return modeListStart; }

    /** Left of the n-th mode button. */
    public float modeButtonX(int index) {
        float columnWidth = (modeListStart.width() - TerrainMapperConfig.MODE_BUTTON_SPACING)
                / TerrainMapperConfig.MODE_BUTTON_COLUMNS;
        int column = index % TerrainMapperConfig.MODE_BUTTON_COLUMNS;
        return modeListStart.x() + column * (columnWidth + TerrainMapperConfig.MODE_BUTTON_SPACING);
    }

    /** Top of the n-th mode button. */
    public float modeButtonY(int index) {
        return rowY(modeListStart, index / TerrainMapperConfig.MODE_BUTTON_COLUMNS);
    }

    /** Top of row {@code row} of a button list starting at {@code first}. */
    private static float rowY(Rect first, int row) {
        return first.y() + row * (TerrainMapperConfig.MODE_BUTTON_HEIGHT + TerrainMapperConfig.MODE_BUTTON_SPACING);
    }

    public Rect spawnButton() { return spawnButton; }
    public Rect centerOnSpawnButton() { return centerOnSpawnButton; }
}
