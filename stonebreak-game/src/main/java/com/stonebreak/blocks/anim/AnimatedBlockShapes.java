package com.stonebreak.blocks.anim;

import com.openmason.engine.format.oma.AnimSampler;
import com.openmason.engine.format.oma.OMAReader;
import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.format.oma.ParsedAnimTrack;
import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.format.sbo.SBOParseResult;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.door.DoorState;
import com.stonebreak.blocks.registry.BlockRegistry;
import com.stonebreak.mobs.sbe.SbeEntityLoader;
import com.stonebreak.mobs.sbe.SbeFace;
import com.stonebreak.mobs.sbe.SbeModelGeometry;
import com.stonebreak.mobs.sbe.SbePart;
import com.stonebreak.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Model-derived collision/targeting shapes for animated blocks (doors).
 *
 * <p>Nothing here is hardcoded to a particular asset: the shape of a
 * (blockType, state) is the AABB of the SBO's model geometry <em>posed at the
 * state clip's end pose</em> — each part's vertices transformed by
 * {@code M_anim * M_rest⁻¹} with the part's authored rotation origin, the
 * exact math {@code SbeEntityRenderer} draws with. Re-export the asset with a
 * different hinge, size or swing and collision follows automatically.
 *
 * <p>Shapes are cached per (type, state); a failed computation falls back to
 * the full unit cell (safe, just blocky) and logs once.
 */
public final class AnimatedBlockShapes {

    private static final Logger logger = LoggerFactory.getLogger(AnimatedBlockShapes.class);

    /** Full-cell fallback used when the model/clip cannot be resolved. */
    private static final float[] UNIT_CELL = {0f, 0f, 0f, 1f, 1f, 1f};

    /** (blockTypeName + "/" + stateName) → settled model-space AABB. */
    private static final Map<String, float[]> CACHE = new ConcurrentHashMap<>();

    private AnimatedBlockShapes() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * World-space AABB {@code {minX,minY,minZ,maxX,maxY,maxZ}} of the animated
     * block at {@code (x, y, z)} in its settled pose, honoring the door's
     * facing orientation. This is the single shape used by player collision,
     * raycast targeting and the breaking overlay.
     */
    public static float[] worldAabb(World world, int x, int y, int z, BlockType type) {
        String raw = world.getBlockStateAt(x, y, z);
        if (type == BlockType.OAK_DOOR || DoorState.isDoorState(raw)) {
            DoorState door = DoorState.parse(raw);
            return door.modelBoxToWorld(settledModelAabb(type, door.renderState()), x, y, z);
        }
        String state = raw != null && !raw.isBlank() ? raw : defaultStateOf(type);
        float[] m = settledModelAabb(type, state);
        return new float[]{x + m[0], y + m[1], z + m[2], x + m[3], y + m[4], z + m[5]};
    }

    /**
     * Model-space AABB of the block's geometry posed at the given state
     * clip's end pose (the pose a play-once clip holds). Cached.
     */
    public static float[] settledModelAabb(BlockType type, String stateName) {
        String key = type.name() + '/' + stateName;
        float[] cached = CACHE.get(key);
        if (cached != null) return cached;
        float[] box;
        try {
            box = computeSettledModelAabb(type, stateName);
        } catch (Exception e) {
            logger.error("Failed to compute settled model bounds for {} state '{}' — "
                    + "falling back to the full cell", type.name(), stateName, e);
            box = UNIT_CELL;
        }
        CACHE.put(key, box);
        return box;
    }

    private static float[] computeSettledModelAabb(BlockType type, String stateName) throws Exception {
        BlockRegistry.BlockEntry entry = BlockRegistry.getInstance().getById(type.getId()).orElse(null);
        SBOParseResult sbo = entry != null ? entry.sboData() : null;
        if (sbo == null || !sbo.hasStates()) {
            return UNIT_CELL;
        }

        // Same geometry the renderer draws: the DEFAULT state's model, with
        // per-state clips posing it (AnimatedBlockRenderer.assetFor mirrors this).
        OMOReader.ReadResult omo = sbo.stateOmoData().get(sbo.defaultStateName());
        if (omo == null) {
            return UNIT_CELL;
        }
        SbeModelGeometry geometry = SbeEntityLoader.buildGeometry(omo);

        byte[] clipBytes = sbo.clipBytesFor(stateName);
        ParsedAnimClip clip = clipBytes != null ? new OMAReader().read(clipBytes) : null;

        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;

        float[] vertices = geometry.vertices();
        int[] indices = geometry.indices();
        Matrix4f pose = new Matrix4f();
        Matrix4f restInverse = new Matrix4f();
        Vector3f p = new Vector3f();

        for (SbePart part : geometry.parts()) {
            ParsedAnimTrack track = clip != null ? findTrack(clip, part) : null;
            pose.identity();
            if (track != null && !track.keyframes().isEmpty()) {
                // End pose of the clip: what a play-once clip settles on.
                AnimSampler.PartPose end = AnimSampler.sample(track, clip.duration());
                partTransform(restInverse.identity(),
                        part.restPos(), part.restRot(), part.restScale(), part.restOrigin())
                        .invert();
                partTransform(pose,
                        end.position(), end.rotationDeg(), end.scale(), part.restOrigin())
                        .mul(restInverse);
            }
            for (SbeFace face : part.faces()) {
                for (int i = face.indexStart(); i < face.indexStart() + face.indexCount(); i++) {
                    int vi = indices[i] * 3;
                    p.set(vertices[vi], vertices[vi + 1], vertices[vi + 2]);
                    pose.transformPosition(p);
                    if (p.x < minX) minX = p.x;
                    if (p.y < minY) minY = p.y;
                    if (p.z < minZ) minZ = p.z;
                    if (p.x > maxX) maxX = p.x;
                    if (p.y > maxY) maxY = p.y;
                    if (p.z > maxZ) maxZ = p.z;
                }
            }
        }

        if (minX > maxX) {
            return UNIT_CELL; // no geometry — shouldn't happen
        }
        float[] box = {minX, minY, minZ, maxX, maxY, maxZ};
        logger.info("Settled model bounds for {} '{}': [{}, {}, {}] -> [{}, {}, {}]",
                type.name(), stateName, minX, minY, minZ, maxX, maxY, maxZ);
        return box;
    }

    /** Track lookup mirroring SbeEntityRenderer: part id first, then part name. */
    private static ParsedAnimTrack findTrack(ParsedAnimClip clip, SbePart part) {
        ParsedAnimTrack track = clip.trackByPartId().get(part.id());
        if (track != null) return track;
        for (ParsedAnimTrack t : clip.tracks()) {
            if (part.name() != null && part.name().equals(t.partName())) {
                return t;
            }
        }
        return null;
    }

    /**
     * Part-local TRS: {@code T(pos) · T(origin) · R(rotXYZ°) · S(scale) · T(-origin)}.
     * Mirrors {@code SbePoseSolver.partTransform} — the two must stay in
     * lockstep or collision drifts from what is rendered.
     */
    private static Matrix4f partTransform(Matrix4f dest, Vector3f pos, Vector3f rotDeg,
                                          Vector3f scale, Vector3f origin) {
        return dest
                .translate(pos)
                .translate(origin)
                .rotateXYZ((float) Math.toRadians(rotDeg.x),
                        (float) Math.toRadians(rotDeg.y),
                        (float) Math.toRadians(rotDeg.z))
                .scale(scale)
                .translate(-origin.x, -origin.y, -origin.z);
    }

    private static String defaultStateOf(BlockType type) {
        BlockRegistry.BlockEntry entry = BlockRegistry.getInstance().getById(type.getId()).orElse(null);
        SBOParseResult sbo = entry != null ? entry.sboData() : null;
        return sbo != null && sbo.hasStates() ? sbo.defaultStateName() : "";
    }
}
