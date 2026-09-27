package com.stonebreak.ui.terrainMapper.visualization;

/**
 * One seed's cached view of the terrain: where its column values come from and where they are
 * kept. Travels inside a sample request, so a pass that outlives a reseed keeps reading and
 * writing the seed it started under rather than mixing two worlds into one store.
 *
 * <p>{@code overviewColumns}, when present, serve every spacing of {@code overviewMinSpacing}
 * blocks or more: cheap coarse terrain for far zoom (see {@code TerrainMapperConfig.OVERVIEW_LOD}).
 */
public record PreviewSource(long seed, TerrainColumns columns, PreviewSampleStore store,
                            TerrainColumns overviewColumns, int overviewMinSpacing) {

    /** A source with no far-zoom overview: every spacing reads full-detail columns. */
    public PreviewSource(long seed, TerrainColumns columns, PreviewSampleStore store) {
        this(seed, columns, store, null, Integer.MAX_VALUE);
    }

    /**
     * See {@link PreviewSampleStore#valueAt}. The store keys values by spacing, so overview and
     * full-detail values never mix.
     */
    public float valueAt(PreviewChannel channel, int spacing, int worldX, int worldZ) {
        if (overviewColumns == null) {
            return store.valueAt(seed, spacing, worldX, worldZ, channel, columns);
        }
        TerrainColumns source = spacing >= overviewMinSpacing ? overviewColumns : columns;
        return store.valueAt(seed, spacing, worldX, worldZ, channel, source, overviewMinSpacing);
    }
}
