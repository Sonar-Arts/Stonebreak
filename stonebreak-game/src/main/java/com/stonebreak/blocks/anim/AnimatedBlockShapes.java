package com.stonebreak.blocks.anim;

import com.openmason.engine.format.oma.OMAReader;
import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.format.omo.OMOReader;
import com.stonebreak.format.sbo.SBOParseResult;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.door.DoorState;
import com.stonebreak.blocks.registry.BlockRegistry;
import com.openmason.engine.entity.model.EntityModelLoader;
import com.openmason.engine.entity.model.EntityFace;
import com.openmason.engine.entity.model.EntityGeometry;
import com.openmason.engine.entity.model.EntityPart;
import com.openmason.engine.rendering.model.draw.ClipLayers;
import com.openmason.engine.rendering.model.draw.ModelPoseSolver;
import com.openmason.engine.rig.JointPalette;
import com.openmason.engine.rig.RigModel;
import com.openmason.engine.rig.Skin;
import com.stonebreak.world.World;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Model-derived collision/targeting shapes for animated blocks (doors).
 *
 * <p>Nothing here is hardcoded to a particular asset: the shape of a
 * (blockType, state) is the AABB of the SBO's model geometry <em>posed at the
 * state clip's end pose</em> by the engine's native rig — the same posing and
 * skinning {@code SbeEntityRenderer} draws with (skin matrices
 * {@code W_pose · W_rest⁻¹}, deformed on the CPU by {@link Skin#deform}). Re-export
 * the asset with a different hinge, size or swing and collision follows automatically.
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
        if (com.stonebreak.blocks.torch.TorchBlock.isTorch(type)) {
            // Hand-sized targeting box (the model is only 2 px wide), no collision.
            return com.stonebreak.blocks.torch.TorchState.parse(raw).worldAabb(x, y, z);
        }
        // prefix:state=<clip>;payload strings (a campfire's cooking) pick their clip by the state
        // key alone; keying on the raw string would miss the clip and cache every payload.
        String variant = com.stonebreak.blocks.BlockRenderState.meshVariantKey(raw);
        String state = variant != null ? variant : defaultStateOf(type);
        float[] m = settledModelAabb(type, state);
        // Plain block SBOs are centred on the model origin; AnimatedBlockRenderer draws that
        // origin at the cell centre, so the box sits there too.
        float c = PLAIN_MODEL_ANCHOR;
        return new float[]{x + c + m[0], y + c + m[1], z + c + m[2],
                x + c + m[3], y + c + m[4], z + c + m[5]};
    }

    /**
     * Where a plain (non-door, non-torch) animated block's model origin sits in its cell, on every
     * axis: such SBOs are authored like static blocks, spanning -0.5..0.5 about the origin. The
     * renderer and this class's world boxes must agree, or the block is targeted off its model.
     */
    public static final float PLAIN_MODEL_ANCHOR = 0.5f;

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
        EntityGeometry geometry = EntityModelLoader.buildGeometry(omo);

        byte[] clipBytes = sbo.clipBytesFor(stateName);
        ParsedAnimClip clip = clipBytes != null ? new OMAReader().read(clipBytes) : null;

        float[] box = settledAabb(geometry, clip);
        logger.info("Settled model bounds for {} '{}': [{}, {}, {}] -> [{}, {}, {}]",
                type.name(), stateName, box[0], box[1], box[2], box[3], box[4], box[5]);
        return box;
    }

    /**
     * Model-space AABB {@code {minX,minY,minZ,maxX,maxY,maxZ}} of the vertices the parts draw, posed at
     * {@code clip}'s end pose (null clip = rest pose) — the pose a play-once clip holds, whatever the
     * clip's own loop flag. {@link #UNIT_CELL} when the geometry draws nothing.
     */
    static float[] settledAabb(EntityGeometry geometry, ParsedAnimClip clip) {
        ParsedAnimClip settled = clip;
        if (clip != null && clip.loop()) {
            // A looping clip would wrap its end time back to the start; the settled pose is the end.
            settled = new ParsedAnimClip(clip.name(), clip.fps(), clip.duration(), false,
                    clip.tracks(), clip.layer());
        }
        JointPalette palette = ModelPoseSolver.pose(geometry,
                ClipLayers.single(settled, settled != null ? settled.duration() : 0f),
                new Matrix4f(), 0f, 0f, null, new JointPalette());

        RigModel rig = geometry.rig();
        float[] vertices = geometry.vertices();
        float[] posed;
        if (rig.influences().isEmpty()) {
            posed = Skin.deform(null, rig.partJointOfVertex(), vertices, palette, null);
        } else {
            try (Skin skin = rig.newSkin()) {
                posed = Skin.deform(skin, rig.partJointOfVertex(), vertices, palette, null);
            }
        }

        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        int[] indices = geometry.indices();
        for (EntityPart part : geometry.parts()) {
            for (EntityFace face : part.faces()) {
                for (int i = face.indexStart(); i < face.indexStart() + face.indexCount(); i++) {
                    int vi = indices[i] * 3;
                    float x = posed[vi];
                    float y = posed[vi + 1];
                    float z = posed[vi + 2];
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (z < minZ) minZ = z;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                    if (z > maxZ) maxZ = z;
                }
            }
        }

        if (minX > maxX) {
            return UNIT_CELL; // no geometry — shouldn't happen
        }
        return new float[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    private static String defaultStateOf(BlockType type) {
        BlockRegistry.BlockEntry entry = BlockRegistry.getInstance().getById(type.getId()).orElse(null);
        SBOParseResult sbo = entry != null ? entry.sboData() : null;
        return sbo != null && sbo.hasStates() ? sbo.defaultStateName() : "";
    }
}
