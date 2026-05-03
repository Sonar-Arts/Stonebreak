package com.stonebreak.ui.terrainMapper.components;

import com.stonebreak.ui.terrainMapper.config.TerrainMapperConfig;

/**
 * Converts between screen-pixel coordinates inside the map viewport and
 * world-block coordinates (x, z). Owns pan + zoom state and a dirty flag so
 * the preview cache knows when to rebuild.
 *
 * The transform is: worldX = panX + (pixelX - viewportCenterX) / zoom.
 * Zooming changes the block-to-pixel ratio; pan translates the world origin.
 */
public final class TerrainMapViewport {

    private float panX;
    private float panZ;
    private float zoom = 1f;

    // Monotonic counter bumped on every mutation. Callers compare against a
    // cached value to detect "has the viewport moved" without reading pan/zoom
    // individually.
    private int stamp;

    public float panX() { return panX; }
    public float panZ() { return panZ; }
    public float zoom() { return zoom; }

    public int stamp() { return stamp; }

    public void panBy(float screenDx, float screenDy) {
        if (screenDx == 0f && screenDy == 0f) return;
        panX -= screenDx / zoom;
        panZ -= screenDy / zoom;
        bumpStamp();
    }

    /** Zoom around a screen anchor (usually the mouse). */
    public void zoomAt(float anchorScreenX, float anchorScreenZ,
                       float viewportCenterX, float viewportCenterZ,
                       float factor) {
        float clamped = Math.max(TerrainMapperConfig.ZOOM_MIN,
                Math.min(TerrainMapperConfig.ZOOM_MAX, zoom * factor));
        if (clamped == zoom) return;
        float worldAnchorX = screenToWorldX(anchorScreenX, viewportCenterX);
        float worldAnchorZ = screenToWorldZ(anchorScreenZ, viewportCenterZ);
        zoom = clamped;
        // Keep the world point under the anchor stationary after the zoom.
        panX = worldAnchorX - (anchorScreenX - viewportCenterX) / zoom;
        panZ = worldAnchorZ - (anchorScreenZ - viewportCenterZ) / zoom;
        bumpStamp();
    }

    public float screenToWorldX(float screenX, float viewportCenterX) {
        return panX + (screenX - viewportCenterX) / zoom;
    }

    public float screenToWorldZ(float screenZ, float viewportCenterZ) {
        return panZ + (screenZ - viewportCenterZ) / zoom;
    }

    public void centerOn(float worldX, float worldZ) {
        this.panX = worldX;
        this.panZ = worldZ;
        bumpStamp();
    }

    public void reset() {
        panX = 0f;
        panZ = 0f;
        zoom = 1f;
        bumpStamp();
    }

    private void bumpStamp() {
        stamp++;
    }
}
