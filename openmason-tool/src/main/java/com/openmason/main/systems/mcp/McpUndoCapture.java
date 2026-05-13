package com.openmason.main.systems.mcp;

import com.openmason.engine.rendering.model.GenericModelRenderer;
import com.openmason.main.systems.MainImGuiInterface;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.services.commands.MeshSnapshot;
import com.openmason.main.systems.services.commands.ModelCommandHistory;
import com.openmason.main.systems.services.commands.RendererSynchronizer;
import com.openmason.main.systems.services.commands.SnapshotCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * Helper that records MCP-driven model + face-texture mutations into the
 * shared {@link ModelCommandHistory} via snapshot commands, so they appear
 * in the model editor's undo/redo timeline alongside interactive UI edits.
 *
 * <p>Usage:
 * <pre>{@code
 * McpUndoCapture.runRecorded(mainInterface, "Create Part", () -> {
 *     // … mutation that touches the GMR / FaceTextureManager …
 *     return someResult;
 * });
 * }</pre>
 *
 * <p>If the snapshot before/after are byte-for-byte identical (the mutation
 * was a no-op), nothing is recorded.
 */
public final class McpUndoCapture {

    private static final Logger logger = LoggerFactory.getLogger(McpUndoCapture.class);

    private McpUndoCapture() {}

    /**
     * Run a mutation and push a snapshot command into {@link ModelCommandHistory}
     * describing the resulting before/after mesh state.
     *
     * <p>Must be called on the GL/main thread — captures GPU-bound state.
     */
    public static <T> T runRecorded(MainImGuiInterface mainInterface,
                                     String description,
                                     Supplier<T> mutation) {
        ViewportController vp = mainInterface.getViewport3D();
        GenericModelRenderer gmr = vp != null ? vp.getModelRenderer() : null;
        ModelCommandHistory history = vp != null ? vp.getCommandHistory() : null;
        RendererSynchronizer sync = vp != null ? vp.getRendererSynchronizer() : null;

        // Without all three we can still run the mutation — just unrecorded.
        if (gmr == null || history == null || sync == null) {
            logger.debug("Undo capture unavailable for '{}' — running unrecorded", description);
            return mutation.get();
        }

        MeshSnapshot before = MeshSnapshot.capture(gmr);
        T result = mutation.get();
        MeshSnapshot after = MeshSnapshot.capture(gmr);

        if (snapshotsEqual(before, after)) {
            logger.debug("No mesh change for '{}' — skipping history push", description);
            return result;
        }

        history.pushCompleted(SnapshotCommand.custom(description, before, after, gmr, sync));
        logger.debug("Recorded MCP mutation: {}", description);
        return result;
    }

    /** Convenience overload for void mutations. */
    public static void runRecorded(MainImGuiInterface mainInterface,
                                    String description,
                                    Runnable mutation) {
        runRecorded(mainInterface, description, () -> {
            mutation.run();
            return null;
        });
    }

    private static boolean snapshotsEqual(MeshSnapshot a, MeshSnapshot b) {
        if (a == null || b == null) return a == b;
        return java.util.Arrays.equals(a.vertices(), b.vertices())
                && java.util.Arrays.equals(a.texCoords(), b.texCoords())
                && java.util.Arrays.equals(a.indices(), b.indices())
                && java.util.Arrays.equals(a.triangleToFaceId(), b.triangleToFaceId())
                && java.util.Objects.equals(a.faceMappings(), b.faceMappings())
                && java.util.Objects.equals(a.materials(), b.materials());
    }
}
