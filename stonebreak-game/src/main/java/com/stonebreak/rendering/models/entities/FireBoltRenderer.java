package com.stonebreak.rendering.models.entities;

import com.openmason.engine.rendering.RenderOrigin;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.stonebreak.mobs.entities.FireBolt;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * The staff's fire bolt (and the Wisp's blue orb, in its own {@link FireBolt.Palette}) as a fireball: one camera-facing quad shaded procedurally into a
 * white-hot core, a yellow-orange body and a red, flickering rim, with flame tongues
 * streaming back into a comet tail along the bolt's heading and a soft additive halo.
 * The quad is stretched along the heading's on-screen direction, so a bolt seen side-on
 * shows its whole tail and one flying at (or away from) the camera reads as a round ball.
 * Output is premultiplied: the hot inside covers what is behind it, the halo only adds.
 */
final class FireBoltRenderer {
    /** Ball radius in blocks (the bolt's hit box is 0.3 across). */
    private static final float RADIUS = 0.2f;
    /** Tail length in ball radii, when the flight is side-on to the camera. */
    private static final float TAIL = 2.2f;
    /** Radius pulse: amplitude (fraction) and rate (rad/s). */
    private static final float PULSE = 0.07f;
    private static final float PULSE_RATE = 23f;

    private ShaderProgram shader;
    private int vao;
    private int vbo;

    private final Vector3f scratchCenter = new Vector3f();
    private final Vector3f scratchHeading = new Vector3f();

    void initialize() {
        createShader();
        createQuad();
    }

    void render(FireBolt bolt, Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        // Once the bolt has impacted the fireball is gone — only its fading trail and
        // impact particles remain (drawn by WorldEffectsRenderer).
        if (shader == null || bolt.isImpacted()) {
            return;
        }
        float time = com.stonebreak.core.Game.getInstance().getTotalTimeElapsed();
        // Stable per bolt, so neighbouring bolts never flicker in lockstep.
        float seed = (System.identityHashCode(bolt) & 0xFFFF) / 997f;

        shader.bind();
        shader.setMat4("u_view", viewMatrix);
        shader.setMat4("u_projection", projectionMatrix);
        shader.setVec3("u_center", RenderOrigin.toRender(bolt.getPosition(), scratchCenter));
        // Until a client shadow has moved, its heading is unknown: draw it round, no tail.
        shader.setVec3("u_heading", bolt.hasHeading() ? bolt.getHeading(scratchHeading) : scratchHeading.zero());
        shader.setFloat("u_radius", RADIUS * (1f + PULSE * (float) Math.sin(time * PULSE_RATE + seed)));
        shader.setFloat("u_tail", TAIL);
        shader.setFloat("u_time", time);
        shader.setFloat("u_seed", seed);
        FireBolt.Palette palette = bolt.palette();
        shader.setVec3("u_rim", palette.rim());
        shader.setVec3("u_body", palette.body());
        shader.setVec3("u_mid", palette.mid());
        shader.setVec3("u_core", palette.core());
        shader.setVec3("u_halo", palette.halo());

        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDepthMask(false);

        GL30.glBindVertexArray(vao);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        GL30.glBindVertexArray(0);

        GL11.glDepthMask(true);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_BLEND);
        if (cull) {
            GL11.glEnable(GL11.GL_CULL_FACE);
        }
        shader.unbind();
    }

    private void createShader() {
        String vertexShader = """
            #version 330 core
            layout (location = 0) in vec2 aCorner; // [-1, 1]^2

            uniform mat4 u_view;
            uniform mat4 u_projection;
            uniform vec3 u_center;   // render space
            uniform vec3 u_heading;  // world travel direction (unit), or zero
            uniform float u_radius;  // ball radius, blocks
            uniform float u_tail;    // tail length in radii when side-on

            out vec2 vLocal;         // (along the tail, across it), in ball radii
            out float vTailLen;      // this frame's on-screen tail length, in radii

            const float PAD = 1.8;   // quad margin around the flame for the halo, in radii

            void main() {
                vec4 center = u_view * vec4(u_center, 1.0);
                vec3 heading = mat3(u_view) * u_heading;
                float side = length(heading.xy); // 1 side-on, 0 head-on
                vec2 axis = side > 1e-4 ? -heading.xy / side : vec2(1.0, 0.0);
                vec2 perp = vec2(-axis.y, axis.x);
                vTailLen = u_tail * side;

                float along = mix(-PAD, vTailLen + PAD, aCorner.x * 0.5 + 0.5);
                float across = aCorner.y * PAD;
                vLocal = vec2(along, across);

                vec3 p = center.xyz + vec3((axis * along + perp * across) * u_radius, 0.0);
                gl_Position = u_projection * vec4(p, 1.0);
            }
            """;

        String fragmentShader = """
            #version 330 core
            in vec2 vLocal;
            in float vTailLen;

            uniform float u_time;
            uniform float u_seed;
            uniform vec3 u_rim;
            uniform vec3 u_body;
            uniform vec3 u_mid;
            uniform vec3 u_core;
            uniform vec3 u_halo;

            out vec4 FragColor;

            const float PAD = 1.8;

            float hash(vec2 p) {
                return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
            }

            float noise(vec2 p) {
                vec2 i = floor(p);
                vec2 f = fract(p);
                vec2 u = f * f * (3.0 - 2.0 * f);
                return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
                           mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
            }

            float fbm(vec2 p) {
                float v = 0.0;
                float a = 0.5;
                for (int i = 0; i < 4; i++) {
                    v += a * noise(p);
                    p = p * 2.03 + 17.1;
                    a *= 0.5;
                }
                return v;
            }

            void main() {
                vec2 q = vLocal;
                // Egg-shaped flame: a round head of radius 1, its rear half stretched into the tail.
                float d = length(vec2(q.x > 0.0 ? q.x / (1.0 + vTailLen) : q.x, q.y));

                // Flame tongues stream back along the tail; a finer, faster layer flickers the edge.
                float flow = fbm(vec2(q.x * 1.6 - u_time * 7.0, q.y * 2.2) + u_seed);
                float flick = fbm(vec2(q.x * 3.5 - u_time * 11.0, q.y * 3.5 - u_seed));
                float shape = d + (flow - 0.5) * 0.6 * smoothstep(0.25, 1.0, d) - (flick - 0.5) * 0.15;
                float heat = clamp(1.0 - shape, 0.0, 1.0);

                vec3 col = mix(u_rim, u_body, smoothstep(0.0, 0.35, heat));
                col = mix(col, u_mid, smoothstep(0.35, 0.7, heat));
                col = mix(col, u_core, smoothstep(0.7, 0.95, heat));
                float body = smoothstep(0.0, 0.12, heat);

                // Soft additive halo, faded out before the quad's edge.
                float edge = min(min(q.x + PAD, vTailLen + PAD - q.x), PAD - abs(q.y));
                float halo = exp(-max(d - 0.8, 0.0) * 2.5) * 0.4 * smoothstep(0.0, 0.7, edge);

                vec3 rgb = col * body + u_halo * halo * (1.0 - body);
                // Only the hot inside hides what is behind; the rim and halo glow over it.
                float alpha = body * smoothstep(0.15, 0.6, heat) * 0.85;
                FragColor = vec4(rgb, alpha);
            }
            """;

        try {
            shader = new ShaderProgram();
            shader.createVertexShader(vertexShader);
            shader.createFragmentShader(fragmentShader);
            shader.link();
        } catch (Exception e) {
            System.err.println("Failed to create fire bolt shader: " + e.getMessage());
            shader = null;
        }
    }

    private void createQuad() {
        float[] corners = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, corners, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 2 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);
        GL30.glBindVertexArray(0);
    }

    void cleanup() {
        if (shader != null) {
            shader.cleanup();
        }
        if (vao != 0) {
            GL30.glDeleteVertexArrays(vao);
        }
        if (vbo != 0) {
            GL15.glDeleteBuffers(vbo);
        }
    }
}
