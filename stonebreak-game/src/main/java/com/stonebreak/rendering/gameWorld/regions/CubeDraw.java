package com.stonebreak.rendering.gameWorld.regions;

import com.openmason.engine.voxel.mms.mmsRegion.MmsRegionMeshHandle;

/**
 * One cube's GPU-resident terrain mesh as the renderer holds it: up to five region-arena handles (QUAD16
 * opaque + cutout in the solid region, QUAD16 translucent, COMPACT20 stamps, WATERQUAD16 water), the cube's
 * region-local bounds, and the per-frame draw state (column readiness for the FastLOD handover, camera
 * visibility from {@code cv_visible_cubes}).
 *
 * <p>A NEW instance replaces the old one on every remesh: its identity is the cube's mesh identity (the
 * torch-shadow terrain cache compares it, and {@code Cube.renderState()} holds it). GL-thread only.
 */
public final class CubeDraw {

    public final long cx, cy, cz;
    /** Mesh revision ({@code cv_gpu_mesh.revision}). */
    public final long revision;
    MmsRegionMeshHandle opaque;
    MmsRegionMeshHandle cutout;
    MmsRegionMeshHandle translucent;
    MmsRegionMeshHandle stamp;
    MmsRegionMeshHandle water;
    /** Region-local bounds of the cube's geometry (all layers). */
    final float minX, minY, minZ, maxX, maxY, maxZ;
    final int quads;
    final long bytes;
    /** CV_EV_COLUMN_READY of its column (drawn at all when the handover is active). */
    boolean columnReady;
    /** Frame stamp of the last camera visibility walk that reached the cube. */
    int visibleFrame = -1;
    /** Position in the renderer's dense list (swap-remove). */
    int listIndex = -1;

    CubeDraw(long cx, long cy, long cz, long revision,
             float minX, float minY, float minZ, float maxX, float maxY, float maxZ, int quads, long bytes) {
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.revision = revision;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.quads = quads;
        this.bytes = bytes;
    }

    /** Lowest block coordinates of the cube. */
    public long minBlockX() {
        return cx << 5;
    }

    public long minBlockY() {
        return cy << 5;
    }

    public long minBlockZ() {
        return cz << 5;
    }

    public boolean hasTranslucent() {
        return translucent != null;
    }

    public boolean hasWater() {
        return water != null;
    }

    public boolean hasSolid() {
        return opaque != null || cutout != null || stamp != null;
    }

    public boolean isColumnReady() {
        return columnReady;
    }

    /** Applies one draw decision to every handle (both cull paths read the enable bit). */
    void setEnabled(boolean enabled) {
        if (opaque != null) opaque.setEnabled(enabled);
        if (cutout != null) cutout.setEnabled(enabled);
        if (translucent != null) translucent.setEnabled(enabled);
        if (stamp != null) stamp.setEnabled(enabled);
        if (water != null) water.setEnabled(enabled);
    }

    /** Returns every handle to its region (GL thread). */
    void close() {
        if (opaque != null) opaque.close();
        if (cutout != null) cutout.close();
        if (translucent != null) translucent.close();
        if (stamp != null) stamp.close();
        if (water != null) water.close();
        opaque = cutout = translucent = stamp = water = null;
    }

    @Override
    public String toString() {
        return "CubeDraw[" + cx + "," + cy + "," + cz + " rev " + revision + "]";
    }
}
