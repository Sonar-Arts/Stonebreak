package com.stonebreak.rendering.models.blocks;

import com.openmason.engine.format.oma.ParsedAnimClip;
import com.openmason.engine.format.omo.OMOReader;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.engine.format.sbo.SBOParseResult;
import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.anim.AnimatedBlockRegistry;
import com.stonebreak.blocks.door.DoorState;
import com.stonebreak.mobs.sbe.AnimState;
import com.stonebreak.mobs.sbe.SbeEntityAsset;
import com.stonebreak.mobs.sbe.SbeEntityLoader;
import com.stonebreak.mobs.sbe.SbeModelGeometry;
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
 * {@link SbeEntityAsset}: geometry from the SBO's default-state OMO through
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
 * <p>V1 limits: these blocks receive cascaded shadows but do not cast them,
 * and they are skipped beyond {@link #MAX_RENDER_DISTANCE}.
 */
public final class AnimatedBlockRenderer {

    private static final Logger logger = LoggerFactory.getLogger(AnimatedBlockRenderer.class);

    private static final float MAX_RENDER_DISTANCE = 96f;
    private static final float MAX_RENDER_DISTANCE_SQ = MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE;

    private final SbeEntityRenderer sbeRenderer = new SbeEntityRenderer();
    private final Vector3f unitScale = new Vector3f(1f, 1f, 1f);
    private final Vector3f reusablePosition = new Vector3f();

    private SBOBlockBridge bridge;

    /** Lazily built per animated block type; a build failure is cached so a
     *  broken asset logs once instead of every frame. */
    private final Map<BlockType, SbeEntityAsset> assets = new HashMap<>();
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
                       Vector3f cameraPos, float totalTime) {
        if (bridge == null || world == null) return;

        Set<BlockPos> positions = world.getAnimatedBlockRegistry().positions();
        if (positions.isEmpty()) {
            if (!playbacks.isEmpty()) playbacks.clear();
            return;
        }
        playbacks.keySet().retainAll(positions);

        for (BlockPos pos : positions) {
            float dx = pos.x() + 0.5f - cameraPos.x;
            float dy = pos.y() + 0.5f - cameraPos.y;
            float dz = pos.z() + 0.5f - cameraPos.z;
            if (dx * dx + dy * dy + dz * dz > MAX_RENDER_DISTANCE_SQ) continue;

            BlockType type = world.getBlockAt(pos.x(), pos.y(), pos.z());
            if (!AnimatedBlockRegistry.isAnimatedType(type)) {
                playbacks.remove(pos); // stale index entry — block is gone
                continue;
            }
            SbeEntityAsset asset = assetFor(type);
            if (asset == null) continue;

            // Resolve render state + orientation from the block-state string.
            String raw = world.getBlockStateAt(pos.x(), pos.y(), pos.z());
            String renderState;
            float yaw;
            float anchorX;
            float anchorZ;
            if (type == BlockType.OAK_DOOR || DoorState.isDoorState(raw)) {
                DoorState door = DoorState.parse(raw);
                renderState = door.renderState();
                yaw = door.facing().yawDegrees();
                anchorX = door.facing().anchorOffsetX();
                anchorZ = door.facing().anchorOffsetZ();
            } else {
                renderState = raw != null && !raw.isBlank() ? raw : defaultStateOf(type);
                yaw = 0f;
                anchorX = 0f;
                anchorZ = 0f;
            }

            Playback playback = playbacks.get(pos);
            if (playback == null) {
                playback = new Playback();
                playback.renderState = renderState;
                // Discovered mid-pose: hold the settled end pose, don't replay.
                playback.startTime = totalTime - clipDuration(asset, renderState) - 1f;
                playbacks.put(pos, playback);
            } else if (!playback.renderState.equals(renderState)) {
                playback.renderState = renderState;
                playback.startTime = totalTime;
            }
            float elapsed = totalTime - playback.startTime;

            reusablePosition.set(pos.x() + anchorX, pos.y(), pos.z() + anchorZ);
            sbeRenderer.render(asset, SbeEntityAsset.DEFAULT_VARIANT,
                    AnimState.single(renderState, elapsed), reusablePosition, yaw, unitScale,
                    viewMatrix, projectionMatrix, world, cameraPos, 0f, 0f);
        }
    }

    private String defaultStateOf(BlockType type) {
        String name = bridge.getDefaultStateName(type);
        return name != null ? name : "";
    }

    private float clipDuration(SbeEntityAsset asset, String stateName) {
        ParsedAnimClip clip = asset.clipFor(stateName);
        return clip != null ? clip.duration() : 0f;
    }

    /**
     * Build (once) the synthetic entity asset for an animated block type:
     * default-state OMO geometry + one clip per state, with each clip's loop
     * flag replaced by the SBO manifest's authored loop choice.
     */
    private SbeEntityAsset assetFor(BlockType type) {
        SbeEntityAsset cached = assets.get(type);
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
            SbeModelGeometry geometry = SbeEntityLoader.buildGeometry(omo);

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

            SbeEntityAsset asset = new SbeEntityAsset(sbo.getObjectId(),
                    Map.of(SbeEntityAsset.DEFAULT_VARIANT, geometry), clips);
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
