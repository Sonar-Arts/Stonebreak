package com.stonebreak.rendering.models.blocks;

import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.format.omo.OMOReader;
import com.stonebreak.format.sbo.SBOFormat;
import com.stonebreak.format.sbo.SBOParseResult;
import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.anim.AnimatedBlockRegistry;
import com.stonebreak.blocks.door.DoorState;
import com.stonebreak.mobs.sbe.AnimState;
import com.openmason.engine.entity.model.EntityAsset;
import com.openmason.engine.entity.model.EntityModelLoader;
import com.openmason.engine.entity.model.EntityGeometry;
import com.stonebreak.rendering.models.entities.SbeEntityRenderer;
import com.stonebreak.rendering.sbo.SBOBlockBridge;
import com.stonebreak.world.World;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Draws blocks whose SBO embeds animation clips (1.6+) — currently the oak
 * door — per frame, entity-style, instead of baking them into the chunk mesh
 * (they are excluded from the SBO stamp map at startup).
 *
 * <p>Each animated block type is lazily wrapped in a synthetic
 * {@link EntityAsset}: geometry from the SBO's default-state OMO through
 * the same pipeline mob models use, clips from the embedded {@code .omanim}s
 * with the manifest {@code AnimationRef}'s authored loop flag applied (a
 * play-once clip clamps at its last keyframe, so a door's "Open" swing holds
 * the open pose forever after the 0.6&nbsp;s transition).
 *
 * <p>Animation timing is client-local: the renderer watches each tracked
 * position's block-state string and restarts the state's clip the moment the
 * render state changes (however the change arrived — local toggle or
 * {@code BlockStateS2C}). Positions discovered mid-pose (world load, chunk
 * stream-in) start past the clip's end so they hold the settled pose rather
 * than replaying the transition.
 *
 * <p>These blocks cast live torch shadows and receive cascaded sun shadows,
 * and they are skipped beyond {@link #MAX_RENDER_DISTANCE}.
 */
public final class AnimatedBlockRenderer {

    private static final Logger logger = LoggerFactory.getLogger(AnimatedBlockRenderer.class);

    private static final float MAX_RENDER_DISTANCE = 96f;
    private static final float MAX_RENDER_DISTANCE_SQ = MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE;
    private static final org.joml.Vector4f SHADOW_COLOR = new org.joml.Vector4f(1f);
    private final org.joml.FrustumIntersection shadowFrustum = new org.joml.FrustumIntersection();
    private final Matrix4f shadowViewProjection = new Matrix4f();

    private final SbeEntityRenderer sbeRenderer = new SbeEntityRenderer();
    private final Vector3f unitScale = new Vector3f(1f, 1f, 1f);
    private final org.joml.Vector3d reusablePosition = new org.joml.Vector3d();

    private SBOBlockBridge bridge;

    /** Lazily built per animated block type; a build failure is cached so a
     *  broken asset logs once instead of every frame. */
    private final Map<BlockType, EntityAsset> assets = new HashMap<>();
    private final Set<BlockType> failedAssets = new HashSet<>();

    /** Render-thread-only clip timing per tracked position. */
    private final Map<BlockPos, Playback> playbacks = new HashMap<>();

    private static final class Playback {
        String renderState;
        float startTime;
    }

    /** GL-thread init. The bridge supplies parsed SBO models and clips. */
    public void initialize(SBOBlockBridge bridge) {
        this.bridge = bridge;
        sbeRenderer.initialize();
    }

    public void setShadowMapRenderer(
            com.stonebreak.rendering.gameWorld.shadow.ShadowMapRenderer shadowMapRenderer) {
        sbeRenderer.setShadowMapRenderer(shadowMapRenderer);
    }

    public void cleanup() {
        sbeRenderer.cleanup();
        assets.clear();
        playbacks.clear();
    }

    /**
     * Draw every tracked animated block near the camera. Called once per frame
     * from the world renderer, after the entity pass (so doors depth-sort with
     * mobs against opaque terrain and water still blends over them).
     *
     * @param totalTime {@code Game.getTotalTimeElapsed()} — the monotonic
     *                  animation clock clip start times are measured against
     */
    public void render(World world, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                       org.joml.Vector3dc cameraPos, float totalTime) {
        renderBlocks(world, viewMatrix, projectionMatrix, cameraPos, totalTime, MAX_RENDER_DISTANCE_SQ, false);
    }

    /** Live door/animated-block shadows. Emissive torches are excluded to avoid enclosing their light. */
    public void renderShadowCasters(World world, Matrix4f view, Matrix4f projection,
                                    org.joml.Vector3dc lightPosition, float radius, float totalTime) {
        float reach = radius + 2f; // include models extending beyond their anchor cell
        // view is render space (the SBE draws are); block positions are rebased per test below.
        shadowFrustum.set(projection.mul(view, shadowViewProjection));
        renderBlocks(world, view, projection, lightPosition, totalTime, reach * reach, true);
    }

    private void renderBlocks(World world, Matrix4f viewMatrix, Matrix4f projectionMatrix,
                              org.joml.Vector3dc cameraPos, float totalTime, float distanceSquared, boolean shadow) {
        if (bridge == null || world == null) return;

        Set<BlockPos> positions = world.getAnimatedBlockRegistry().positions();
        if (positions.isEmpty()) {
            if (!playbacks.isEmpty()) playbacks.clear();
            return;
        }
        if (!shadow) playbacks.keySet().retainAll(positions);

        for (BlockPos pos : positions) {
            float dx = pos.x() + 0.5f - (float) cameraPos.x();
            float dy = pos.y() + 0.5f - (float) cameraPos.y();
            float dz = pos.z() + 0.5f - (float) cameraPos.z();
            if (dx * dx + dy * dy + dz * dz > distanceSquared) continue;
            if (shadow && !shadowFrustum.testSphere(
                    com.openmason.engine.rendering.RenderOrigin.relativeX((long) pos.x()) + .5f,
                    com.openmason.engine.rendering.RenderOrigin.relativeY((long) pos.y()) + 1f,
                    com.openmason.engine.rendering.RenderOrigin.relativeZ((long) pos.z()) + .5f, 2f)) continue;

            BlockType type = world.getBlockAt(pos.x(), pos.y(), pos.z());
            if (!AnimatedBlockRegistry.isAnimatedType(type)) {
                playbacks.remove(pos); // stale index entry — block is gone
                continue;
            }
            if (shadow && com.stonebreak.blocks.torch.TorchBlock.isTorch(type)) continue;
            // A campfire's light sits inside its own log teepee and flame planes, so drawing
            // them would black out nearly every ray — it never shadows its own fire. Other
            // campfires still cast for this light. In the shadow pass cameraPos is the light.
            if (shadow && type == BlockType.CAMPFIRE && holdsPoint(pos, cameraPos)) continue;
            EntityAsset asset = assetFor(type);
            if (asset == null) continue;

            // Resolve render state + orientation from the block-state string.
            String raw = world.getBlockStateAt(pos.x(), pos.y(), pos.z());
            String renderState;
            float yaw;
            float anchorX;
            float anchorY = 0f;
            float anchorZ;
            boolean torch = com.stonebreak.blocks.torch.TorchBlock.isTorch(type);
            if (type == BlockType.OAK_DOOR || DoorState.isDoorState(raw)) {
                DoorState door = DoorState.parse(raw);
                renderState = door.renderState();
                yaw = door.facing().yawDegrees();
                anchorX = door.facing().anchorOffsetX();
                anchorZ = door.facing().anchorOffsetZ();
            } else if (torch) {
                // Torch models are authored centered on the cell axis, so the
                // facing is a pure yaw about the cell center (anchor +0.5).
                com.stonebreak.blocks.torch.TorchState state =
                        com.stonebreak.blocks.torch.TorchState.parse(raw);
                renderState = state.renderState();
                yaw = state.yawDegrees();
                anchorX = 0.5f;
                anchorZ = 0.5f;
            } else {
                // prefix:state=<clip>;payload strings (a campfire's cooking, a camp) pick their
                // clip by the state key alone.
                String variant = com.stonebreak.blocks.BlockRenderState.meshVariantKey(raw);
                renderState = variant != null ? variant : defaultStateOf(type);
                yaw = 0f;
                // Plain block SBOs (the campfire) are authored like static blocks: centred
                // on the model origin, spanning -0.5..0.5. Put that origin at the cell centre
                // (AnimatedBlockShapes.worldAabb targets the same spot).
                anchorX = com.stonebreak.blocks.anim.AnimatedBlockShapes.PLAIN_MODEL_ANCHOR;
                anchorY = com.stonebreak.blocks.anim.AnimatedBlockShapes.PLAIN_MODEL_ANCHOR;
                anchorZ = com.stonebreak.blocks.anim.AnimatedBlockShapes.PLAIN_MODEL_ANCHOR;
            }

            Playback playback = playbacks.get(pos);
            ParsedAnimClip clip = asset.clipFor(renderState);
            boolean looping = clip != null && clip.loop();
            if (playback == null) {
                playback = new Playback();
                playback.renderState = renderState;
                playback.startTime = looping
                        // Looping clips run on the shared world clock with a
                        // per-position phase so neighbours don't pulse in
                        // lockstep — the same phase the torch light uses.
                        ? -loopPhaseOffset(pos, clip.duration())
                        // Discovered mid-pose: hold the settled end pose, don't replay.
                        : totalTime - clipDuration(asset, renderState) - 1f;
                playbacks.put(pos, playback);
            } else if (!playback.renderState.equals(renderState)) {
                playback.renderState = renderState;
                playback.startTime = looping ? -loopPhaseOffset(pos, clip.duration()) : totalTime;
            }
            float elapsed = totalTime - playback.startTime;

            reusablePosition.set(pos.x() + anchorX, pos.y() + anchorY, pos.z() + anchorZ);
            if (shadow) {
                sbeRenderer.renderColored(asset, EntityAsset.DEFAULT_VARIANT,
                        renderState, elapsed, reusablePosition, yaw, unitScale,
                        viewMatrix, projectionMatrix, SHADOW_COLOR);
                continue;
            }
            // A torch is its own light source: it never reads as unlit.
            sbeRenderer.setSelfGlow(torch ? TORCH_SELF_GLOW : 0f);
            sbeRenderer.render(asset, EntityAsset.DEFAULT_VARIANT,
                    AnimState.single(renderState, elapsed), reusablePosition, yaw, unitScale,
                    viewMatrix, projectionMatrix, world, cameraPos, 0f, 0f);
        }
        sbeRenderer.setSelfGlow(0f);
    }

    /** True when world-space {@code point} lies inside the cell at {@code pos}. */
    private static boolean holdsPoint(BlockPos pos, org.joml.Vector3dc point) {
        return (long) Math.floor(point.x()) == pos.x()
                && (long) Math.floor(point.y()) == pos.y()
                && (long) Math.floor(point.z()) == pos.z();
    }

    /** Extra flat brightness a torch model draws with (it is lit by its own flame). */
    private static final float TORCH_SELF_GLOW = 0.55f;

    /**
     * Phase offset, in seconds within {@code [0, duration)}, that a looping clip
     * at {@code pos} runs ahead of the world clock: its elapsed time is
     * {@code totalTime + loopPhaseOffset(pos, duration)}. Deterministic per
     * position so the renderer and the torch light agree on the flicker phase.
     */
    public static float loopPhaseOffset(BlockPos pos, float duration) {
        if (duration <= 0f) return 0f;
        int h = pos.x() * 73856093 ^ pos.y() * 19349663 ^ pos.z() * 83492791;
        h ^= (h >>> 13);
        h *= 0x5bd1e995;
        h ^= (h >>> 15);
        return ((h & 0xffff) / 65536f) * duration;
    }

    private String defaultStateOf(BlockType type) {
        String name = bridge.getDefaultStateName(type);
        return name != null ? name : "";
    }

    private float clipDuration(EntityAsset asset, String stateName) {
        ParsedAnimClip clip = asset.clipFor(stateName);
        return clip != null ? clip.duration() : 0f;
    }

    /**
     * Build (once) the synthetic entity asset for an animated block type:
     * default-state OMO geometry + one clip per state, with each clip's loop
     * flag replaced by the SBO manifest's authored loop choice.
     */
    private EntityAsset assetFor(BlockType type) {
        EntityAsset cached = assets.get(type);
        if (cached != null) return cached;
        if (failedAssets.contains(type)) return null;

        try {
            SBOParseResult sbo = bridge.getSBODefinition(type);
            if (sbo == null || !sbo.hasStates()) {
                throw new IOException("no SBO states for animated block " + type.name());
            }
            OMOReader.ReadResult omo = bridge.getStateModel(type, sbo.defaultStateName());
            if (omo == null) {
                throw new IOException("no state model for animated block " + type.name());
            }
            EntityGeometry geometry = EntityModelLoader.buildGeometry(omo);

            Map<String, ParsedAnimClip> clips = new LinkedHashMap<>();
            for (SBOFormat.StateEntry state : sbo.manifest().states()) {
                ParsedAnimClip clip = bridge.getStateClip(type, state.name());
                if (clip == null) continue;
                SBOFormat.AnimationRef ref = state.animation();
                if (ref != null && ref.loop() != clip.loop()) {
                    // The export-time loop selection wins over the clip's own flag.
                    clip = new ParsedAnimClip(clip.name(), clip.fps(), clip.duration(),
                            ref.loop(), clip.tracks(), clip.layer());
                }
                clips.put(state.name(), clip);
            }

            EntityAsset asset = new EntityAsset(sbo.getObjectId(),
                    Map.of(EntityAsset.DEFAULT_VARIANT, geometry), clips);
            assets.put(type, asset);
            logger.info("Built animated block asset for {}: {} state clip(s)",
                    type.name(), clips.size());
            return asset;
        } catch (IOException | RuntimeException e) {
            failedAssets.add(type);
            logger.error("Failed to build animated block asset for {}", type.name(), e);
            return null;
        }
    }
}
