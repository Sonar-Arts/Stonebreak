package com.stonebreak.world.voxel;

import com.openmason.engine.voxel.world.StreamMesh;
import com.stonebreak.world.World;
import com.stonebreak.world.chunk.Cube;

/**
 * What a {@link World}'s stream tick tells the rest of the game (Phase 3 API, see
 * {@code Dev Working/docs/Cubic-Voxel-Runtime.md}). Every callback runs on the world's OWNER thread (client:
 * the main/GL thread; server: the server tick thread), inside {@link World#tickStream()}, in the order the
 * engine reported the events. Register with the {@code World.add*} methods.
 */
public final class CubeEvents {

    private CubeEvents() {
    }

    /** A cube entered the world (generated, loaded from the store, or decoded from the network). */
    @FunctionalInterface
    public interface InstallListener {
        void onCubeInstalled(World world, Cube cube);
    }

    /**
     * A cube leaves NEXT tick (CV_EV_UNLOAD_PENDING): last chance to put game state into its blob. Hooks
     * contribute named blob sections ({@link Cube#putBlobSection}); the world writes the blob right after
     * the hooks ran (3B-S / 3B-E: entities).
     */
    @FunctionalInterface
    public interface UnloadPendingHook {
        void onCubeUnloadPending(World world, Cube cube);
    }

    /**
     * Put this cube's CURRENT game state into its blob sections ({@link Cube#putBlobSection}) - a snapshot,
     * never a removal: it runs at every save point ({@code World.flushCubeBlobs}: autosave, pause, quit) AND
     * right before the unload-pending hooks when the cube leaves. Owner thread; authoritative worlds only.
     * Write a section only when it changed (an equal section does not re-dirty the cube). Not run by
     * {@code World.cleanup()} (owners may already be torn down): {@code SaveService.close()} runs it first.
     */
    @FunctionalInterface
    public interface SaveHook {
        void onCubeSave(World world, Cube cube);
    }

    /** A cube was saved (when the world has a store) and removed. */
    @FunctionalInterface
    public interface UnloadListener {
        void onCubeUnloaded(World world, Cube cube);
    }

    /**
     * An installed cube's content changed after its INSTALLED event (CV_EV_CHANGED): a population finished
     * writing into it, edits, or a network decode over it. At most once per cube per tick, never in the tick
     * that installed it, never for a neighbour's border dirt; sent by meshing and {@code NO_MESH} (server)
     * streams alike, so it is THE content-changed signal (the world already invalidated the cube's physics
     * before the listeners run). Layer-only / blob-only writes surface with the next dirtying write.
     */
    @FunctionalInterface
    public interface ChangeListener {
        void onCubeChanged(World world, Cube cube);
    }

    /**
     * A GPU-ready mesh for a cube (CV_EV_REMESHED; client / display worlds only - a server stream builds none): region-local QUAD16 / WATERQUAD16 / COMPACT20 records the
     * renderer uploads (3B-R). {@code mesh} and the memory it points to are valid only during the callback
     * (released at the next tick): upload or copy now. {@link StreamMesh#isEmpty()} = drop the cube's mesh.
     */
    @FunctionalInterface
    public interface MeshConsumer {
        void onCubeMesh(World world, StreamMesh mesh);
    }

    /**
     * Column readiness for the FastLOD handover (3B-R): READY = every band cube of column cube (cx, cz) is
     * installed and meshed, far LOD may stop drawing it; UNREADY = draw it again (sent the tick BEFORE the
     * detail cubes' UNLOADED events).
     */
    public interface ColumnReadinessListener {
        void onColumnReady(World world, long cx, long cz);

        void onColumnUnready(World world, long cx, long cz);
    }
}
