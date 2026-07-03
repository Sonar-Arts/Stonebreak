package com.openmason.main.systems.skeleton;

import com.stonebreak.mobs.sbe.SbeEntityAsset;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-level "socket test" state: which decoded model is previewed on which
 * attachment point (socket). Purely an editor aid — never serialized — so
 * authors can see what an accessory will look like mounted on a socket and
 * adjust the socket's position/rotation/scale live against it.
 *
 * <p>Assets are decoded via the game's {@code SbeEntityLoader.loadAttachable}
 * (GL-free), so the preview shows exactly the geometry/textures the game would
 * mount. Rendering is {@code AttachmentPreviewRenderer}'s job.
 */
public final class AttachmentPreviewStore {

    /** One active preview: display label (file name) + the decoded asset. */
    public record Preview(String label, SbeEntityAsset asset) {}

    private final Map<String, Preview> bySocketId = new ConcurrentHashMap<>();

    /** Set (or replace) the test model previewed on a socket. */
    public void set(String socketId, String label, SbeEntityAsset asset) {
        bySocketId.put(socketId, new Preview(label, asset));
    }

    /** Remove a socket's test model. Returns true if one was set. */
    public boolean clear(String socketId) {
        return bySocketId.remove(socketId) != null;
    }

    /** Remove every test model (e.g. when a different model file is loaded). */
    public void clearAll() {
        bySocketId.clear();
    }

    /** The preview on a socket, or null. */
    public Preview get(String socketId) {
        return socketId != null ? bySocketId.get(socketId) : null;
    }

    public boolean isEmpty() {
        return bySocketId.isEmpty();
    }

    /** Snapshot of socket id → preview for render-time iteration. */
    public Map<String, Preview> snapshot() {
        return Map.copyOf(bySocketId);
    }
}
