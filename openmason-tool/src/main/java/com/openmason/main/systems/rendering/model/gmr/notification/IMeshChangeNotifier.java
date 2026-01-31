package com.openmason.main.systems.rendering.model.gmr.notification;

import com.openmason.main.systems.rendering.model.MeshChangeListener;
import org.joml.Vector3f;

/**
 * Interface for mesh change notification using the Observer pattern.
 * Notifies listeners when vertex positions change or geometry is rebuilt.
 */
public interface IMeshChangeNotifier {

    /**
     * Add a listener to receive mesh change notifications.
     *
     * @param listener The listener to add
     */
    void addListener(MeshChangeListener listener);

    /**
     * Remove a listener from mesh change notifications.
     *
     * @param listener The listener to remove
     */
    void removeListener(MeshChangeListener listener);

    /**
     * Notify all listeners that a vertex position has changed.
     *
     * @param uniqueIndex The unique vertex index
     * @param newPosition The new position
     * @param affectedMeshIndices All mesh indices that were updated
     */
    void notifyVertexPositionChanged(int uniqueIndex, Vector3f newPosition, int[] affectedMeshIndices);

    /**
     * Notify all listeners that geometry has been rebuilt.
     * Called after subdivision, UV mode change, or model loading.
     */
    void notifyGeometryRebuilt();

    /**
     * Get the number of registered listeners.
     *
     * @return Number of listeners
     */
    int getListenerCount();

    /**
     * Check if a listener is registered.
     *
     * @param listener The listener to check
     * @return true if registered
     */
    boolean hasListener(MeshChangeListener listener);
}
