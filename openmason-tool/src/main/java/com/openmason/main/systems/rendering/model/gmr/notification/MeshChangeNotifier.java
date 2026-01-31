package com.openmason.main.systems.rendering.model.gmr.notification;

import com.openmason.main.systems.rendering.model.MeshChangeListener;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of IMeshChangeNotifier.
 * Manages mesh change listeners using the Observer pattern.
 */
public class MeshChangeNotifier implements IMeshChangeNotifier {

    private static final Logger logger = LoggerFactory.getLogger(MeshChangeNotifier.class);

    private final List<MeshChangeListener> listeners = new ArrayList<>();

    @Override
    public void addListener(MeshChangeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
            logger.debug("Added mesh change listener: {}", listener.getClass().getSimpleName());
        }
    }

    @Override
    public void removeListener(MeshChangeListener listener) {
        if (listener != null) {
            boolean removed = listeners.remove(listener);
            if (removed) {
                logger.debug("Removed mesh change listener: {}", listener.getClass().getSimpleName());
            }
        }
    }

    @Override
    public void notifyVertexPositionChanged(int uniqueIndex, Vector3f newPosition, int[] affectedMeshIndices) {
        for (MeshChangeListener listener : listeners) {
            try {
                listener.onVertexPositionChanged(uniqueIndex, newPosition, affectedMeshIndices);
            } catch (Exception e) {
                logger.error("Error notifying listener {} of vertex change",
                    listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public void notifyGeometryRebuilt() {
        for (MeshChangeListener listener : listeners) {
            try {
                listener.onGeometryRebuilt();
            } catch (Exception e) {
                logger.error("Error notifying listener {} of geometry rebuild",
                    listener.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public int getListenerCount() {
        return listeners.size();
    }

    @Override
    public boolean hasListener(MeshChangeListener listener) {
        return listener != null && listeners.contains(listener);
    }
}
