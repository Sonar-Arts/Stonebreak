package com.stonebreak.rendering.lighting;

import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.torch.TorchBlock;
import com.stonebreak.blocks.torch.TorchState;
import com.stonebreak.items.ItemStack;
import com.stonebreak.player.Player;
import com.stonebreak.rendering.models.blocks.AnimatedBlockRenderer;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-frame set of dynamic point lights, uploaded to every lit shader (world,
 * water, SBE entities) through the shared {@code point_lights.glsl} uniforms.
 *
 * <p>Sources, gathered once per frame by {@link #update}:
 * <ul>
 *   <li>every placed torch the world's {@code AnimatedBlockRegistry} tracks,
 *       lit at its ember with the intensity of its own flicker phase (the same
 *       phase {@link AnimatedBlockRenderer} plays the clip at);</li>
 *   <li>a torch in the player's hand, carried just below the eye.</li>
 * </ul>
 * The shaders take at most {@link PointLightGlsl#MAX_LIGHTS}; when more are in
 * range the nearest to the camera win. Render-thread only — a static snapshot
 * is the simplest way for the three independent renderers to agree on one
 * light set per frame.
 */
public final class DynamicLights {

    /** Torches beyond this distance from the camera are not considered. */
    private static final float MAX_DISTANCE = 64f;
    private static final float MAX_DISTANCE_SQ = MAX_DISTANCE * MAX_DISTANCE;

    private record Light(float x, float y, float z, float radius,
                         float r, float g, float b, float distSq) {}

    private static final List<Light> lights = new ArrayList<>();
    private static final List<Light> candidates = new ArrayList<>();
    private static final Vector3f scratch = new Vector3f();
    private static final Vector4f scratchPos = new Vector4f();
    private static final Vector3f scratchColor = new Vector3f();
    private static final String[] POS_NAMES = new String[PointLightGlsl.MAX_LIGHTS];
    private static final String[] COLOR_NAMES = new String[PointLightGlsl.MAX_LIGHTS];

    static {
        for (int i = 0; i < PointLightGlsl.MAX_LIGHTS; i++) {
            POS_NAMES[i] = "u_pointLightPos[" + i + "]";
            COLOR_NAMES[i] = "u_pointLightColor[" + i + "]";
        }
    }

    private DynamicLights() {}

    /**
     * Rebuild the frame's light set.
     *
     * @param totalTime {@code Game.getTotalTimeElapsed()} — the clip clock
     */
    public static void update(World world, Player player, Vector3f cameraPos, float totalTime) {
        candidates.clear();
        lights.clear();
        if (world == null || cameraPos == null) return;

        for (BlockPos pos : world.getAnimatedBlockRegistry().positions()) {
            BlockType type = world.getBlockAt(pos.x(), pos.y(), pos.z());
            if (!TorchBlock.isTorch(type)) continue;
            float dx = pos.x() + 0.5f - cameraPos.x;
            float dy = pos.y() + 0.5f - cameraPos.y;
            float dz = pos.z() + 0.5f - cameraPos.z;
            float distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > MAX_DISTANCE_SQ) continue;

            TorchState state = TorchState.parse(world.getBlockStateAt(pos.x(), pos.y(), pos.z()));
            state.emberPosition(pos.x(), pos.y(), pos.z(), scratch);
            float elapsed = totalTime + AnimatedBlockRenderer.loopPhaseOffset(pos, TorchLight.CLIP_DURATION);
            addTorch(scratch.x, scratch.y, scratch.z, TorchLight.intensity(elapsed), distSq);
        }

        if (player != null && isHoldingTorch(player)) {
            // Carried a little below and ahead of the eye so the hand itself
            // isn't the brightest thing on screen; the held torch runs on the
            // bare world clock (its own phase).
            Vector3f eye = player.getCamera().getPosition();
            Vector3f forward = player.getCamera().getFront();
            float x = eye.x + forward.x * 0.3f;
            float y = eye.y - 0.25f;
            float z = eye.z + forward.z * 0.3f;
            addTorch(x, y, z, TorchLight.intensity(totalTime), 0f);
        }

        if (candidates.size() > PointLightGlsl.MAX_LIGHTS) {
            candidates.sort((a, b) -> Float.compare(a.distSq, b.distSq));
        }
        for (int i = 0; i < candidates.size() && i < PointLightGlsl.MAX_LIGHTS; i++) {
            lights.add(candidates.get(i));
        }
    }

    private static void addTorch(float x, float y, float z, float intensity, float distSq) {
        float k = TorchLight.PEAK * intensity;
        candidates.add(new Light(x, y, z, TorchLight.RADIUS,
                TorchLight.COLOR_R * k, TorchLight.COLOR_G * k, TorchLight.COLOR_B * k, distSq));
    }

    /** True when the player's selected hotbar item is the torch item. */
    public static boolean isHoldingTorch(Player player) {
        if (player == null || player.getInventory() == null) return false;
        ItemStack selected = player.getInventory().getSelectedHotbarSlot();
        return selected != null && !selected.isEmpty() && TorchBlock.isTorchItem(selected.getItem());
    }

    /** Number of lights in the current frame's set. */
    public static int count() {
        return lights.size();
    }

    /**
     * Upload the frame's lights to a bound shader that includes
     * {@code point_lights.glsl}. Uses the tolerant auto-registering setters, so
     * no {@code createUniform} bookkeeping is needed per element.
     */
    public static void applyTo(ShaderProgram shader) {
        if (shader == null) return;
        int n = lights.size();
        shader.setInt("u_pointLightCount", n);
        for (int i = 0; i < n; i++) {
            Light l = lights.get(i);
            scratchPos.set(l.x, l.y, l.z, l.radius);
            scratchColor.set(l.r, l.g, l.b);
            shader.setVec4(POS_NAMES[i], scratchPos);
            shader.setVec3(COLOR_NAMES[i], scratchColor);
        }
    }

    /** Light level (0..1) a held torch gives the player's own arm/item, on top of the sky sample. */
    public static float heldTorchLight() {
        return 0.9f;
    }
}
