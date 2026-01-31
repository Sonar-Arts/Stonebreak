package com.openmason.main.systems.rendering.api;

/**
 * Enumeration of render passes in order of execution.
 * Controls the layering order of different rendering components.
 *
 * Render order: BACKGROUND → SCENE → OVERLAY → UI → DEBUG
 */
public enum RenderPass {
    /**
     * Background elements rendered first.
     * Examples: Grid, skybox, environment maps.
     */
    BACKGROUND(0),

    /**
     * Main scene content.
     * Examples: Models, blocks, items, meshes.
     */
    SCENE(1),

    /**
     * Overlay elements rendered on top of scene.
     * Examples: Vertices, edges, face highlights.
     */
    OVERLAY(2),

    /**
     * UI elements rendered on top of everything except debug.
     * Examples: Gizmos, handles, widgets.
     */
    UI(3),

    /**
     * Debug visualization rendered last.
     * Examples: Bounding boxes, normals, wireframes.
     */
    DEBUG(4);

    private final int order;

    RenderPass(int order) {
        this.order = order;
    }

    /**
     * Get the execution order for this pass.
     * Lower values execute first.
     *
     * @return The order value
     */
    public int getOrder() {
        return order;
    }
}
