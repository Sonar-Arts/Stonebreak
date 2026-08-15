package com.openmason.main.systems.scene.dnd;

/**
 * Drag-and-drop payload identity for dropping a model into a scene.
 *
 * <p>The payload value is just the model's absolute path: imgui-java carries a real Java
 * object for the drag's lifetime, so nothing has to be serialized. Deliberately not a
 * list index — the Project Browser re-sorts and re-filters its entries every frame, so an
 * index would refer to a different asset by the time the drop lands.
 */
public final class ScenePayloads {

    /**
     * ImGui drag-drop type id. ImGui truncates these to 32 bytes including the
     * terminator, so it is kept short on purpose.
     */
    public static final String OMO_ASSET = "OM_ASSET_OMO";

    private ScenePayloads() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** Whether a path can be dropped into a scene as an instance. */
    public static boolean isPlaceable(String assetPath) {
        return assetPath != null && assetPath.trim().toLowerCase().endsWith(".omo");
    }
}
