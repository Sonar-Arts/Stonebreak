package com.stonebreak.mobs.sbe;

import org.joml.Vector3f;

/**
 * A named attachment point (socket) on an SBE/OMO model — a local frame where
 * another model can be visually mounted at runtime, following its host part
 * through animation.
 *
 * <p>{@code localPos}/{@code localRotDeg}/{@code localScale} are authored in
 * <b>rest-pose model space</b> — the same space the mesh vertices and part
 * pivots live in — NOT relative to the host part's pivot. The socket's world
 * frame — the model matrix an attached model renders with — is the host part's
 * animated world matrix times {@code T(localPos) · R_xyz(localRotDeg) ·
 * S(localScale)} (see {@code SbePoseSolver.socketWorldMatrix}): the socket
 * translates, rotates, AND scales whatever is mounted on it.
 *
 * <p>The host part is resolved by {@code parentPartId} first, then by
 * {@code parentPartName} — the same id-then-name convention animation tracks
 * use, since tool part UUIDs are not stable across re-imports. A null parent
 * binds the socket to the model root frame.
 *
 * @param id             OMO attachment point UUID
 * @param name           socket name — the runtime lookup key (e.g. "face")
 * @param parentPartId   host part UUID, or {@code null} for the model root
 * @param parentPartName host part name fallback, or {@code null}
 * @param localPos       socket position in rest-pose model space
 * @param localRotDeg    socket Euler rotation in degrees (XYZ order)
 * @param localScale     scale applied to the attached model (1,1,1 = unchanged)
 */
public record SbeAttachmentPoint(
        String id,
        String name,
        String parentPartId,
        String parentPartName,
        Vector3f localPos,
        Vector3f localRotDeg,
        Vector3f localScale
) {
    /** Convenience constructor for sockets with no scale (unit scale). */
    public SbeAttachmentPoint(String id, String name,
                              String parentPartId, String parentPartName,
                              Vector3f localPos, Vector3f localRotDeg) {
        this(id, name, parentPartId, parentPartName, localPos, localRotDeg,
                new Vector3f(1f, 1f, 1f));
    }

    /** True if this socket is bound to the model root rather than a part. */
    public boolean isModelRoot() {
        return (parentPartId == null || parentPartId.isBlank())
                && (parentPartName == null || parentPartName.isBlank());
    }
}
