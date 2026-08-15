package com.stonebreak.mobs.entities.ai.nav;

import com.openmason.engine.wayfind.CancelToken;
import org.joml.Vector3f;

/**
 * One in-flight search, and the handoff between the worker that runs it and the tick that asked
 * for it.
 *
 * <p>The handoff is deliberately one volatile field. The worker builds an immutable {@link Path}
 * and publishes it with a single write; the tick thread polls {@link #isDone()} at the top of its
 * update. Nothing else crosses the boundary — no callbacks into the entity, no shared mutable
 * state, no locks — because a worker touching an entity mid-tick is exactly the race that makes
 * asynchronous AI unmaintainable.
 *
 * <p>A cancelled or failed search still publishes ({@link Path#EMPTY}), so a caller can never be
 * left waiting on a result that will not arrive.
 */
public final class PathRequest {

    private final Vector3f goal;
    private final float goalRadius;
    private final CancelToken cancel = new CancelToken();

    /** {@code null} until the search finishes; the single point of publication. */
    private volatile Path result;

    PathRequest(Vector3f goal, float goalRadius) {
        this.goal = new Vector3f(goal);
        this.goalRadius = goalRadius;
    }

    /** The world position this search was aimed at — used to notice the goal has since moved. */
    public Vector3f goal(Vector3f out) {
        return out.set(goal);
    }

    public float goalRadius() {
        return goalRadius;
    }

    public boolean isDone() {
        return result != null;
    }

    /** The finished route, or {@code null} while the search is still running. */
    public Path result() {
        return result;
    }

    /**
     * Abandons the search. The worker notices within a few dozen node expansions and publishes an
     * empty result; callers that have stopped caring can simply drop the request.
     */
    public void cancel() {
        cancel.cancel();
    }

    public boolean isCancelled() {
        return cancel.isCancelled();
    }

    CancelToken cancelToken() {
        return cancel;
    }

    /** Publishes the outcome. Called once, from the worker thread. */
    void publish(Path path) {
        this.result = path;
    }
}
