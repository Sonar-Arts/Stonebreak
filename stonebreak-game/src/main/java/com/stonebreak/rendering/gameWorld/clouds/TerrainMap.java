package com.stonebreak.rendering.gameWorld.clouds;

import com.stonebreak.world.weather.TerrainRelief;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.GL_RGBA16F;

/**
 * The GPU copy of the {@link TerrainRelief} around the camera: two textures of
 * {@link TerrainRelief#PERIOD_TILES}² tiles, addressed toroidally in world
 * space (texel = cell mod size, wrap mode REPEAT) — RGBA = height, smoothed
 * height, slope x, slope z; and RGBA = pile-up, steering shift x, z, steer. Each frame the tile slots in the
 * window are compared with the relief's ready tiles; changed ones are uploaded
 * (a few per frame) and slots whose tile isn't ready are cleared to flat.
 */
final class TerrainMap {

    static final int SIZE = TerrainRelief.PERIOD_TILES * TerrainRelief.TILE_CELLS;
    /** Tile uploads per frame, so a burst of finished tiles never stalls one frame. */
    private static final int UPLOADS_PER_FRAME = 4;
    private static final long EMPTY = Long.MIN_VALUE;

    private final int shapeTexture;
    private final int flowTexture;
    private final long[] slotTile = new long[TerrainRelief.PERIOD_TILES * TerrainRelief.PERIOD_TILES];
    /** Whether each slot holds a ready tile (else it is flat, waiting for one). */
    private final boolean[] slotReady = new boolean[slotTile.length];
    private final FloatBuffer shape = BufferUtils.createFloatBuffer(TerrainRelief.TILE_CELLS * TerrainRelief.TILE_CELLS * 4);
    private final FloatBuffer flow = BufferUtils.createFloatBuffer(TerrainRelief.TILE_CELLS * TerrainRelief.TILE_CELLS * 4);
    private TerrainRelief relief;

    TerrainMap() {
        shapeTexture = create(GL_RGBA16F, GL_RGBA);
        flowTexture = create(GL_RGBA16F, GL_RGBA);
        java.util.Arrays.fill(slotTile, EMPTY);
    }

    private static int create(int internal, int format) {
        int t = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, t);
        // Zero-filled: flat ground, which leaves clouds alone.
        glTexImage2D(GL_TEXTURE_2D, 0, internal, SIZE, SIZE, 0, format, GL_FLOAT,
                BufferUtils.createFloatBuffer(SIZE * SIZE * 4));
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glBindTexture(GL_TEXTURE_2D, 0);
        return t;
    }

    /**
     * Brings the textures up to date for a camera over world ({@code worldX},
     * {@code worldZ}). A different relief (another world, or none) clears every
     * slot. Leaves the active unit's 2D binding changed.
     */
    void update(TerrainRelief relief, double worldX, double worldZ) {
        if (relief != this.relief) {
            this.relief = relief;
            for (int i = 0; i < slotTile.length; i++) {
                if (slotReady[i]) clear(i);
                slotTile[i] = EMPTY;
            }
        }
        if (relief == null) return;
        int fx = Math.floorDiv((int) Math.floor(worldX), TerrainRelief.TILE_BLOCKS);
        int fz = Math.floorDiv((int) Math.floor(worldZ), TerrainRelief.TILE_BLOCKS);
        int r = TerrainRelief.RADIUS_TILES, uploads = 0;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int tx = fx + dx, tz = fz + dz;
                int slot = slot(tx, tz);
                long key = ((long) tx << 32) ^ (tz & 0xFFFFFFFFL);
                if (slotTile[slot] == key && slotReady[slot]) continue;
                TerrainRelief.Tile tile = relief.tile(tx, tz);
                if (tile != null) {
                    if (uploads++ >= UPLOADS_PER_FRAME) continue;
                    upload(slot, tile);
                    slotReady[slot] = true;
                } else if (slotReady[slot]) {
                    // Another tile's relief, from before the camera moved: flat until this one is ready.
                    clear(slot);
                }
                slotTile[slot] = key;
            }
        }
    }

    private static int slot(int tx, int tz) {
        int p = TerrainRelief.PERIOD_TILES;
        return Math.floorMod(tz, p) * p + Math.floorMod(tx, p);
    }

    private void upload(int slot, TerrainRelief.Tile tile) {
        int n = TerrainRelief.TILE_CELLS;
        shape.clear();
        flow.clear();
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                shape.put(tile.at(TerrainRelief.HEIGHT, i, j)).put(tile.at(TerrainRelief.SMOOTH, i, j))
                        .put(tile.at(TerrainRelief.GRAD_X, i, j)).put(tile.at(TerrainRelief.GRAD_Z, i, j));
                flow.put(tile.at(TerrainRelief.PILEUP, i, j)).put(tile.at(TerrainRelief.SHIFT_X, i, j))
                        .put(tile.at(TerrainRelief.SHIFT_Z, i, j)).put(tile.at(TerrainRelief.STEER, i, j));
            }
        }
        shape.flip();
        flow.flip();
        write(slot, shape, flow);
    }

    private void clear(int slot) {
        shape.clear();
        flow.clear();
        int n = TerrainRelief.TILE_CELLS * TerrainRelief.TILE_CELLS;
        for (int i = 0; i < n; i++) {
            shape.put(0f).put(0f).put(0f).put(0f);
            flow.put(0f).put(0f).put(0f).put(0f);
        }
        shape.flip();
        flow.flip();
        write(slot, shape, flow);
        slotReady[slot] = false;
    }

    private void write(int slot, FloatBuffer shapeData, FloatBuffer flowData) {
        int p = TerrainRelief.PERIOD_TILES, n = TerrainRelief.TILE_CELLS;
        int x = (slot % p) * n, z = (slot / p) * n;
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glBindTexture(GL_TEXTURE_2D, shapeTexture);
        glTexSubImage2D(GL_TEXTURE_2D, 0, x, z, n, n, GL_RGBA, GL_FLOAT, shapeData);
        glBindTexture(GL_TEXTURE_2D, flowTexture);
        glTexSubImage2D(GL_TEXTURE_2D, 0, x, z, n, n, GL_RGBA, GL_FLOAT, flowData);
    }

    int shapeTexture() {
        return shapeTexture;
    }

    int flowTexture() {
        return flowTexture;
    }

    void cleanup() {
        glDeleteTextures(shapeTexture);
        glDeleteTextures(flowTexture);
    }
}
