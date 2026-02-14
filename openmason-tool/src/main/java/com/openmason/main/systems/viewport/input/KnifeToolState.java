package com.openmason.main.systems.viewport.input;

import org.joml.Vector3f;

/**
 * Immutable state record for the knife tool.
 * Tracks the current phase and cut point information for the two-click workflow.
 *
 * <p>State machine phases:
 * <ul>
 *   <li>{@link Phase#INACTIVE} — Tool is off</li>
 *   <li>{@link Phase#AWAITING_FIRST_CLICK} — Tool active, waiting for first edge click</li>
 *   <li>{@link Phase#AWAITING_SECOND_CLICK} — First edge cut, waiting for second edge on same face</li>
 * </ul>
 */
public record KnifeToolState(
    Phase phase,
    int firstEdgeIndex,
    float firstT,
    int[] firstEdgeAdjacentFaceIds,
    int firstUniqueVertexA,
    int firstUniqueVertexB,
    Vector3f firstCutPosition
) {

    public enum Phase {
        INACTIVE,
        AWAITING_FIRST_CLICK,
        AWAITING_SECOND_CLICK
    }

    public static KnifeToolState inactive() {
        return new KnifeToolState(Phase.INACTIVE, -1, 0f, null, -1, -1, null);
    }

    public static KnifeToolState awaitingFirst() {
        return new KnifeToolState(Phase.AWAITING_FIRST_CLICK, -1, 0f, null, -1, -1, null);
    }

    public KnifeToolState withFirstCut(int edgeIndex, float t,
                                        int[] adjacentFaceIds,
                                        int uniqueVertexA, int uniqueVertexB,
                                        Vector3f cutPosition) {
        return new KnifeToolState(
            Phase.AWAITING_SECOND_CLICK,
            edgeIndex, t, adjacentFaceIds,
            uniqueVertexA, uniqueVertexB,
            cutPosition
        );
    }

    public boolean isActive() {
        return phase != Phase.INACTIVE;
    }
}
