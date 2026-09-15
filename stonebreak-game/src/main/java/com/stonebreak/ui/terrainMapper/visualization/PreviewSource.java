package com.stonebreak.ui.terrainMapper.visualization;

/**
 * One seed's cached view of the terrain: where its column values come from and where they are
 * kept. Travels inside a sample request, so a pass that outlives a reseed keeps reading and
 * writing the seed it started under rather than mixing two worlds into one store.
 */
public record PreviewSource(long seed, TerrainColumns columns, PreviewSampleStore store) {

    /** See {@link PreviewSampleStore#valueAt}. */
    public float valueAt(PreviewChannel channel, int spacing, int worldX, int worldZ) {
        return store.valueAt(seed, spacing, worldX, worldZ, channel, columns);
    }
}
