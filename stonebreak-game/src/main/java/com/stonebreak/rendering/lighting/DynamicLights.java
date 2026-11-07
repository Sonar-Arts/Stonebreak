package com.stonebreak.rendering.lighting;

import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.torch.TorchBlock;
import com.stonebreak.items.ItemStack;
import com.stonebreak.mobs.entities.Entity;
import com.stonebreak.mobs.entities.RemotePlayer;
import com.stonebreak.player.Camera;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.models.blocks.AnimatedBlockRenderer;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;

import static org.lwjgl.opengl.GL20.*;

/**
 * Render-thread snapshot shared by terrain, water and entity lighting, in two tiers ranked
 * together by distance. The near tier is the {@link PointLightGlsl#MAX_LIGHTS} nearest within
 * 64 blocks, each with a point shadow and bounce light. The far tier is the next
 * {@link #MAX_FAR_LIGHTS}, unshadowed, which keeps torches lit past that reach and on the
 * FastLOD terrain of unloaded chunks ({@link RememberedLights}); only the world shader reads it.
 */
public final class DynamicLights {
    private static final float MAX_DISTANCE_SQ = 64f * 64f;
    /** Held-torch ember offset in the camera's own basis (right hand, below the view axis). */
    private static final float HELD_FORWARD = 0.35f;
    private static final float HELD_RIGHT = 0.30f;
    private static final float HELD_UP = -0.25f;
    /** A remote player's torch flame sits this far above their hand. */
    private static final float REMOTE_FLAME_UP = 0.25f;
    /** A remote player's torch falls back to their chest when the hand is inside a wall. */
    private static final float REMOTE_CHEST_UP = 1.2f;
    /** Distant, unshadowed lights; matches {@code MAX_FAR_POINT_LIGHTS} in far_lights.glsl. */
    public static final int MAX_FAR_LIGHTS = 64;
    private static final NearestLightSources nearest =
            new NearestLightSources(PointLightGlsl.MAX_LIGHTS + MAX_FAR_LIGHTS);
    private static final Vector3f[] positions = new Vector3f[PointLightGlsl.MAX_LIGHTS];
    /** Who carries each light: a remote player for their held torch, null otherwise. */
    private static final Entity[] holders = new Entity[PointLightGlsl.MAX_LIGHTS];
    private static final Vector3f scratch = new Vector3f();
    private static final FloatBuffer positionData = BufferUtils.createFloatBuffer(PointLightGlsl.MAX_LIGHTS * 4);
    private static final FloatBuffer colorData = BufferUtils.createFloatBuffer(PointLightGlsl.MAX_LIGHTS * 4);
    private static final FloatBuffer farPositionData = BufferUtils.createFloatBuffer(MAX_FAR_LIGHTS * 4);
    private static final FloatBuffer farColorData = BufferUtils.createFloatBuffer(MAX_FAR_LIGHTS * 4);
    private static final Map<ShaderProgram, Upload> uploads = new WeakHashMap<>();
    private static int count;
    private static int farCount;
    private static long frame;
    private static boolean held;
    private static boolean shadowsEnabled;
    private static boolean indirectEnabled;

    /** Cached locations and last uploaded frame; repeated entity draws need no light uploads. */
    private static final class Upload {
        final int countLocation, positionLocation, colorLocation, shadowLocation, samplerLocation;
        final int indirectLocation, indirectSamplerLocation;
        final int farCountLocation, farPositionLocation, farColorLocation;
        long frame = -1;
        Upload(ShaderProgram shader) {
            int program = shader.getProgramId();
            countLocation = glGetUniformLocation(program, "u_pointLightCount");
            positionLocation = glGetUniformLocation(program, "u_pointLightPos[0]");
            colorLocation = glGetUniformLocation(program, "u_pointLightColor[0]");
            shadowLocation = glGetUniformLocation(program, "u_pointShadowsEnabled");
            samplerLocation = glGetUniformLocation(program, "u_pointShadowMap");
            indirectLocation = glGetUniformLocation(program, "u_pointIndirectEnabled");
            indirectSamplerLocation = glGetUniformLocation(program, "u_pointIndirectMap");
            // -1 in shaders without far_lights.glsl (water, entities); GL ignores those uploads.
            farCountLocation = glGetUniformLocation(program, "u_farLightCount");
            farPositionLocation = glGetUniformLocation(program, "u_farLightPos[0]");
            farColorLocation = glGetUniformLocation(program, "u_farLightColor[0]");
        }
    }

    static {
        for (int i = 0; i < positions.length; i++) positions[i] = new Vector3f();
    }

    private DynamicLights() {}

    /** {@link #update(World, Player, Vector3f, float, Iterable)} without other players' torches. */
    public static void update(World world, Player player, Vector3f cameraPos, float totalTime) {
        update(world, player, cameraPos, totalTime, null);
    }

    /**
     * Select the nearest emitting lights, reserving one near slot for the held torch: placed
     * {@link BlockLightSource}s, those remembered in unloaded chunks, and the torches
     * {@code remotePlayers} hold (issue #344 — their held item arrives with
     * {@code PlayerHeldItemS2C}), ranked together by distance. The nearest fill the shadowed
     * near tier; the rest, and any past its reach, fall to the far tier.
     */
    public static void update(World world, Player player, Vector3f cameraPos, float totalTime,
                              Iterable<RemotePlayer> remotePlayers) {
        frame++;
        count = 0;
        farCount = 0;
        Arrays.fill(holders, null);
        shadowsEnabled = false;
        indirectEnabled = false;
        held = world != null && player != null && !player.getCamera().isCinematicActive() && isHoldingTorch(player);
        positionData.clear();
        colorData.clear();
        farPositionData.clear();
        farColorData.clear();
        int nearLimit = PointLightGlsl.MAX_LIGHTS - (held ? 1 : 0);
        nearest.clear(nearLimit + MAX_FAR_LIGHTS);
        if (world != null && cameraPos != null) {
            for (BlockLightSource source : BlockLightSource.VALUES) {
                for (BlockPos pos : source.candidates(world)) {
                    float distance = distanceSquared(pos, cameraPos);
                    // Reject sources that can't make the list before querying blocks or parsing their state.
                    if (nearest.wouldAccept(distance) && source.isEmitting(world, pos)) {
                        nearest.offer(pos, distance);
                    }
                }
            }
            RememberedLights remembered = world.getRememberedLights();
            if (remembered != null) {
                for (RememberedLights.Light[] lights : remembered.chunks()) {
                    for (RememberedLights.Light light : lights) {
                        nearest.offer(light.pos(), distanceSquared(light.pos(), cameraPos));
                    }
                }
            }
            if (remotePlayers != null) {
                for (RemotePlayer rp : remotePlayers) {
                    if (!isHoldingTorch(rp) || !com.stonebreak.rendering.models.entities.EntityRenderer
                            .isInRenderableChunk(rp, world)) continue;
                    float distance = rp.handPosition(scratch).distanceSquared(cameraPos);
                    if (distance <= MAX_DISTANCE_SQ) nearest.offer(rp, distance);
                }
            }
            if (held) {
                heldTorchPosition(world, player, positions[count]);
                addLight(TorchLight.PROFILE, totalTime);
            }
            for (int i = 0; i < nearest.size(); i++) {
                // Sorted by distance, so once one light is far every later one is too.
                boolean near = i < nearLimit && nearest.distance(i) <= MAX_DISTANCE_SQ;
                // Near slots left empty by distant lights leave the list longer than the far tier.
                if (!near && farCount == MAX_FAR_LIGHTS) break;
                Vector3f out = near ? positions[count] : scratch;
                LightProfile profile;
                float elapsed;
                if (nearest.holder(i) instanceof RemotePlayer rp) {
                    remoteTorchPosition(world, rp, out);
                    if (near) holders[count] = rp;
                    profile = TorchLight.PROFILE;
                    // Each holder flickers on its own phase, as placed torches do.
                    elapsed = totalTime + (rp.getPlayerId() & 0xFF) / 256f * profile.period();
                } else {
                    BlockPos pos = nearest.get(i);
                    RememberedLights.Light light = remembered != null ? remembered.find(pos) : null;
                    if (light != null) {
                        out.set(light.x(), light.y(), light.z());
                        profile = light.source().profile();
                    } else {
                        BlockLightSource source = BlockLightSource.of(world.getBlockAt(pos.x(), pos.y(), pos.z()));
                        if (source == null) continue;
                        source.emitterPosition(world, pos, out);
                        profile = source.profile();
                    }
                    elapsed = totalTime + AnimatedBlockRenderer.loopPhaseOffset(pos, profile.period());
                }
                if (near) {
                    addLight(profile, elapsed);
                } else {
                    putLight(farPositionData, farColorData, out, profile, elapsed, 0f);
                    farCount++;
                }
            }
        }
        positionData.flip();
        colorData.flip();
        farPositionData.flip();
        farColorData.flip();
    }

    private static float distanceSquared(BlockPos pos, Vector3f cameraPos) {
        float dx = pos.x() + 0.5f - cameraPos.x;
        float dy = pos.y() + 0.5f - cameraPos.y;
        float dz = pos.z() + 0.5f - cameraPos.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * The held torch's ember, built in the camera's own basis so it tracks pitch as well
     * as yaw. The rendered torch is view-space geometry; the previous world-space offset
     * used the unflattened forward vector, so looking up or down collapsed the ember onto
     * the eye and the light appeared to swim (and sat inside the player's own head).
     * Pulled back toward the eye when the offset would land inside a block, which would
     * otherwise black the torch out whenever the player faces a nearby wall.
     */
    private static void heldTorchPosition(World world, Player player, Vector3f out) {
        Camera camera = player.getCamera();
        Vector3f eye = camera.getPosition();
        for (float scale = 1f; scale >= 0.5f; scale *= 0.5f) {
            out.set(eye)
                    .fma(HELD_FORWARD * scale, camera.getFront())
                    .fma(HELD_RIGHT * scale, camera.getRight())
                    .fma(HELD_UP * scale, camera.getUp());
            if (!isSolidAt(world, out)) return;
        }
        out.set(eye);
    }

    /**
     * A remote player's torch flame: just above the hand their held item is drawn at, falling
     * back to the hand and then their chest when it would sit inside a block (a player hugging a
     * wall would otherwise black their own torch out).
     */
    private static void remoteTorchPosition(World world, RemotePlayer rp, Vector3f out) {
        rp.handPosition(out);
        out.y += REMOTE_FLAME_UP;
        if (!isSolidAt(world, out)) return;
        out.y -= REMOTE_FLAME_UP;
        if (!isSolidAt(world, out)) return;
        Vector3f feet = rp.getPosition();
        out.set(feet.x, feet.y + REMOTE_CHEST_UP, feet.z);
    }

    private static boolean isSolidAt(World world, Vector3f p) {
        BlockType block = world.getBlockAt((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z));
        return block != null && block.isSolid();
    }

    /** A near light at {@code positions[count]}, whose shadow slot is its index. */
    private static void addLight(LightProfile profile, float elapsed) {
        putLight(positionData, colorData, positions[count], profile, elapsed, count);
        count++;
    }

    private static void putLight(FloatBuffer posOut, FloatBuffer colorOut, Vector3f p,
                                 LightProfile profile, float elapsed, float slot) {
        // positions[] stay in world space (shadow views, indirect volumes and chunk
        // lookups want that); the uniform goes up in render space, because the
        // shaders subtract it from a render-space fragment position. The origin is
        // fixed for the frame before update() runs (Renderer.renderWorld).
        posOut.put(p.x - RenderOrigin.x()).put(p.y).put(p.z - RenderOrigin.z()).put(PointLightGlsl.RADIUS);
        float k = profile.peak() * profile.intensity(elapsed);
        colorOut.put(profile.red() * k).put(profile.green() * k).put(profile.blue() * k).put(slot);
    }

    /** Whether a player figure (a remote player or a decoy) is alive and holding a torch. */
    public static boolean isHoldingTorch(RemotePlayer rp) {
        return rp != null && rp.isAlive() && TorchBlock.isTorchItem(rp.getHeldItem());
    }

    public static boolean isHoldingTorch(Player player) {
        if (player == null || player.getInventory() == null) return false;
        ItemStack selected = player.getInventory().getSelectedHotbarSlot();
        return selected != null && !selected.isEmpty() && TorchBlock.isTorchItem(selected.getItem());
    }

    public static int count() { return count; }

    /** Distant, unshadowed lights selected this frame; never given a shadow slot. */
    public static int farCount() { return farCount; }

    /**
     * Slot of the held torch's light, or {@code -1} when none is held. Always slot 0 —
     * {@link #update} reserves it before the placed sources. Callers that draw the local
     * player as a shadow caster must skip this light: it rides the camera, so the player's
     * own body would stamp a head/torso shadow over everything they look at.
     */
    public static int heldTorchIndex() { return held && count > 0 ? 0 : -1; }

    public static Vector3f position(int index, Vector3f out) { return out.set(positions[index]); }

    /**
     * The entity carrying light {@code index}, or null for a placed source or the local held
     * torch. Its own body must not cast a shadow from its own torch, for the same reason the
     * local player's must not ({@link #heldTorchIndex}).
     */
    public static Entity holder(int index) { return holders[index]; }

    /** Called after the depth pass, before any receiver renders. */
    public static void enableShadows() { shadowsEnabled = true; }
    public static void enableIndirectLight() { indirectEnabled = true; }

    /** Two packed array uploads per shader per frame, regardless of entity/draw count. */
    public static void applyTo(ShaderProgram shader) {
        if (shader == null) return;
        Upload upload = uploads.computeIfAbsent(shader, Upload::new);
        if (upload.frame == frame) return;
        glUniform1i(upload.countLocation, count);
        if (count > 0) {
            glUniform4fv(upload.positionLocation, positionData);
            glUniform4fv(upload.colorLocation, colorData);
        }
        glUniform1i(upload.shadowLocation, shadowsEnabled ? 1 : 0);
        glUniform1i(upload.samplerLocation, POINT_SHADOW_TEXTURE_UNIT);
        glUniform1i(upload.indirectLocation, indirectEnabled ? 1 : 0);
        glUniform1i(upload.indirectSamplerLocation, INDIRECT_TEXTURE_UNIT);
        glUniform1i(upload.farCountLocation, farCount);
        if (farCount > 0) {
            glUniform4fv(upload.farPositionLocation, farPositionData);
            glUniform4fv(upload.farColorLocation, farColorData);
        }
        upload.frame = frame;
    }

    /**
     * No point lights, torch shadows or bounce light — for draws outside the world
     * (UI model previews). Drops the per-frame cache so the next {@link #applyTo}
     * re-uploads this frame's lights instead of trusting the zeroed uniforms.
     */
    public static void applyNoneTo(ShaderProgram shader) {
        if (shader == null) return;
        Upload upload = uploads.computeIfAbsent(shader, Upload::new);
        glUniform1i(upload.countLocation, 0);
        glUniform1i(upload.shadowLocation, 0);
        // Samplers keep their own units even while unused: two sampler types on one unit
        // (these defaulting to the albedo's unit 0) fail every draw on strict drivers.
        glUniform1i(upload.samplerLocation, POINT_SHADOW_TEXTURE_UNIT);
        glUniform1i(upload.indirectLocation, 0);
        glUniform1i(upload.indirectSamplerLocation, INDIRECT_TEXTURE_UNIT);
        glUniform1i(upload.farCountLocation, 0);
        upload.frame = -1;
    }

    /** Separate from the sun map (5), pulled quads (7), block textures (0/1) and water's scene depth (9). */
    public static final int POINT_SHADOW_TEXTURE_UNIT = 6;
    public static final int INDIRECT_TEXTURE_UNIT = 8;

    /** Light level supplied to the player's arm/item, whose vertices aren't in world space. */
    public static float heldTorchLight() { return 0.9f; }
}
