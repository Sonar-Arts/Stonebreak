package com.stonebreak.rendering.lighting;

import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.torch.TorchBlock;
import com.stonebreak.blocks.torch.TorchState;
import com.stonebreak.items.ItemStack;
import com.stonebreak.player.Camera;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.models.blocks.AnimatedBlockRenderer;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.util.Map;
import java.util.WeakHashMap;

import static org.lwjgl.opengl.GL20.*;

/** Render-thread snapshot shared by terrain, water and entity lighting. */
public final class DynamicLights {
    private static final float MAX_DISTANCE_SQ = 64f * 64f;
    /** Held-torch ember offset in the camera's own basis (right hand, below the view axis). */
    private static final float HELD_FORWARD = 0.35f;
    private static final float HELD_RIGHT = 0.30f;
    private static final float HELD_UP = -0.25f;
    private static final NearestLightSources nearest = new NearestLightSources(PointLightGlsl.MAX_LIGHTS);
    private static final Vector3f[] positions = new Vector3f[PointLightGlsl.MAX_LIGHTS];
    private static final FloatBuffer positionData = BufferUtils.createFloatBuffer(PointLightGlsl.MAX_LIGHTS * 4);
    private static final FloatBuffer colorData = BufferUtils.createFloatBuffer(PointLightGlsl.MAX_LIGHTS * 4);
    private static final Map<ShaderProgram, Upload> uploads = new WeakHashMap<>();
    private static int count;
    private static long frame;
    private static boolean held;
    private static boolean shadowsEnabled;
    private static boolean indirectEnabled;

    /** Cached locations and last uploaded frame; repeated entity draws need no light uploads. */
    private static final class Upload {
        final int countLocation, positionLocation, colorLocation, shadowLocation, samplerLocation;
        final int indirectLocation, indirectSamplerLocation;
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
        }
    }

    static {
        for (int i = 0; i < positions.length; i++) positions[i] = new Vector3f();
    }

    private DynamicLights() {}

    /** Select nearest placed sources, reserving one slot for the held torch. */
    public static void update(World world, Player player, Vector3f cameraPos, float totalTime) {
        frame++;
        count = 0;
        shadowsEnabled = false;
        indirectEnabled = false;
        held = world != null && player != null && !player.getCamera().isCinematicActive() && isHoldingTorch(player);
        positionData.clear();
        colorData.clear();
        nearest.clear(PointLightGlsl.MAX_LIGHTS - (held ? 1 : 0));
        if (world != null && cameraPos != null) {
            for (BlockPos pos : world.getAnimatedBlockRegistry().positions()) {
                float dx = pos.x() + 0.5f - cameraPos.x;
                float dy = pos.y() + 0.5f - cameraPos.y;
                float dz = pos.z() + 0.5f - cameraPos.z;
                float distance = dx * dx + dy * dy + dz * dz;
                // Reject distant sources before querying blocks or parsing their state.
                if (distance <= MAX_DISTANCE_SQ && TorchBlock.isTorch(world.getBlockAt(pos.x(), pos.y(), pos.z()))) {
                    nearest.offer(pos, distance);
                }
            }
            if (held) {
                heldTorchPosition(world, player, positions[count]);
                addTorch(totalTime);
            }
            for (int i = 0; i < nearest.size(); i++) {
                BlockPos pos = nearest.get(i);
                TorchState.parse(world.getBlockStateAt(pos.x(), pos.y(), pos.z()))
                        .emberPosition(pos.x(), pos.y(), pos.z(), positions[count]);
                addTorch(totalTime + AnimatedBlockRenderer.loopPhaseOffset(pos, TorchLight.CLIP_DURATION));
            }
        }
        positionData.flip();
        colorData.flip();
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

    private static boolean isSolidAt(World world, Vector3f p) {
        BlockType block = world.getBlockAt((int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z));
        return block != null && block.isSolid();
    }

    private static void addTorch(float elapsed) {
        Vector3f p = positions[count];
        positionData.put(p.x).put(p.y).put(p.z).put(TorchLight.RADIUS);
        float k = TorchLight.PEAK * TorchLight.intensity(elapsed);
        colorData.put(TorchLight.COLOR_R * k).put(TorchLight.COLOR_G * k).put(TorchLight.COLOR_B * k).put(count);
        count++;
    }

    public static boolean isHoldingTorch(Player player) {
        if (player == null || player.getInventory() == null) return false;
        ItemStack selected = player.getInventory().getSelectedHotbarSlot();
        return selected != null && !selected.isEmpty() && TorchBlock.isTorchItem(selected.getItem());
    }

    public static int count() { return count; }

    /**
     * Slot of the held torch's light, or {@code -1} when none is held. Always slot 0 —
     * {@link #update} reserves it before the placed sources. Callers that draw the local
     * player as a shadow caster must skip this light: it rides the camera, so the player's
     * own body would stamp a head/torso shadow over everything they look at.
     */
    public static int heldTorchIndex() { return held && count > 0 ? 0 : -1; }

    public static Vector3f position(int index, Vector3f out) { return out.set(positions[index]); }

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
        upload.frame = frame;
    }

    /** Separate from the sun map (5), pulled quads (7) and block textures (0/1). */
    public static final int POINT_SHADOW_TEXTURE_UNIT = 6;
    public static final int INDIRECT_TEXTURE_UNIT = 8;

    /** Light level supplied to the player's arm/item, whose vertices aren't in world space. */
    public static float heldTorchLight() { return 0.9f; }
}
