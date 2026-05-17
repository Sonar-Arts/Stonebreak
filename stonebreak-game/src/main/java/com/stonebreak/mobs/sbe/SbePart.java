package com.stonebreak.mobs.sbe;

import org.joml.Vector3f;

import java.util.List;

/**
 * A named, animatable part of an SBE cow model.
 *
 * <p>The part owns a contiguous run of {@link SbeFace}s in the combined mesh.
 * Its rest transform is the pose authored in the OMO; animation clips replace
 * (not offset) this pose for parts they drive — keyframes carry absolute local
 * transforms. {@code id} is the OMO part UUID, which animation tracks key on.
 *
 * @param id          OMO part UUID (animation track key)
 * @param name        human-readable part name (fallback track key)
 * @param parentId    parent part UUID, or {@code null} for a model-root part
 * @param restOrigin  transform pivot in part-local space
 * @param restPos     rest-pose local translation
 * @param restRot     rest-pose local Euler rotation (degrees)
 * @param restScale   rest-pose local scale
 * @param faces       the faces this part renders
 */
public record SbePart(
        String id,
        String name,
        String parentId,
        Vector3f restOrigin,
        Vector3f restPos,
        Vector3f restRot,
        Vector3f restScale,
        List<SbeFace> faces
) {
    public SbePart {
        faces = faces == null ? List.of() : List.copyOf(faces);
    }
}
