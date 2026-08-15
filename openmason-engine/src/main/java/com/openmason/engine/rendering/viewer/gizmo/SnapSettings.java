package com.openmason.engine.rendering.viewer.gizmo;

/**
 * The only thing the gizmo needs to know about grid snapping.
 *
 * <p>The gizmo previously took a whole {@code ViewportUIState}, which is built from ImGui
 * types ({@code ImBoolean}/{@code ImFloat}) — a dependency that would drag ImGui into the
 * engine when the gizmo moves there. Narrowing it to these two reads keeps the widget
 * reusable by any host: {@code ViewportUIState} implements this, and a scene viewport can
 * supply its own without touching ImGui at all.
 */
public interface SnapSettings {

    /** Whether positions should be snapped to the grid. */
    boolean isSnapEnabled();

    /** Grid increment to snap to; only meaningful when {@link #isSnapEnabled()}. */
    float getSnapIncrement();

    /** Snapping switched off — the safe default when a host supplies nothing. */
    SnapSettings DISABLED = new SnapSettings() {
        @Override public boolean isSnapEnabled() { return false; }
        @Override public float getSnapIncrement() { return 0.0f; }
    };
}
