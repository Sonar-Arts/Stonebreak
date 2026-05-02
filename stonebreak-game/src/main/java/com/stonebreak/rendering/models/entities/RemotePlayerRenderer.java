package com.stonebreak.rendering.models.entities;

import com.stonebreak.mobs.entities.RemotePlayer;
import com.stonebreak.rendering.shaders.ShaderProgram;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryUtil.memAllocFloat;
import static org.lwjgl.system.MemoryUtil.memAllocInt;
import static org.lwjgl.system.MemoryUtil.memFree;

/**
 * Procedural cylinder mesh renderer for remote (multiplayer) players.
 * One static cylinder mesh is shared across all remote players; per-player
 * color is derived from playerId.
 */
public final class RemotePlayerRenderer {

    private static final int SEGMENTS = 16;
    private static final float RADIUS = 0.3f;
    private static final float HEIGHT = 1.8f;

    private ShaderProgram shader;
    private int vao;
    private int vbo;
    private int ebo;
    private int indexCount;
    private boolean initialized;

    public void initialize() {
        if (initialized) return;
        createShader();
        createCylinderMesh();
        initialized = true;
    }

    private void createShader() {
        String vs = """
            #version 330 core
            layout (location = 0) in vec3 aPos;
            uniform mat4 model;
            uniform mat4 view;
            uniform mat4 projection;
            void main() {
                gl_Position = projection * view * model * vec4(aPos, 1.0);
            }
            """;
        String fs = """
            #version 330 core
            out vec4 FragColor;
            uniform vec3 tint;
            void main() {
                FragColor = vec4(tint, 1.0);
            }
            """;
        try {
            shader = new ShaderProgram();
            shader.createVertexShader(vs);
            shader.createFragmentShader(fs);
            shader.link();
            shader.createUniform("model");
            shader.createUniform("view");
            shader.createUniform("projection");
            shader.createUniform("tint");
        } catch (Exception e) {
            System.err.println("[RemotePlayerRenderer] Shader init failed: " + e.getMessage());
        }
    }

    private void createCylinderMesh() {
        // Cylinder centered on origin in XZ; Y from 0 to HEIGHT (feet at y=0).
        // Side vertices: 2 per segment (bottom, top).
        // Cap centers at indices [2*SEGMENTS] (bottom) and [2*SEGMENTS+1] (top).
        int sideVerts = SEGMENTS * 2;
        int totalVerts = sideVerts + 2;
        float[] verts = new float[totalVerts * 3];

        for (int i = 0; i < SEGMENTS; i++) {
            double theta = (Math.PI * 2.0 * i) / SEGMENTS;
            float cx = (float) Math.cos(theta) * RADIUS;
            float cz = (float) Math.sin(theta) * RADIUS;
            int b = i * 6;
            verts[b]     = cx;   verts[b + 1] = 0f;     verts[b + 2] = cz;     // bottom
            verts[b + 3] = cx;   verts[b + 4] = HEIGHT; verts[b + 5] = cz;     // top
        }
        int bottomCenter = sideVerts;
        int topCenter = sideVerts + 1;
        verts[bottomCenter * 3]     = 0f;
        verts[bottomCenter * 3 + 1] = 0f;
        verts[bottomCenter * 3 + 2] = 0f;
        verts[topCenter * 3]     = 0f;
        verts[topCenter * 3 + 1] = HEIGHT;
        verts[topCenter * 3 + 2] = 0f;

        // Indices: side quads (2 tris) + bottom fan + top fan.
        int[] idx = new int[SEGMENTS * 6 + SEGMENTS * 3 + SEGMENTS * 3];
        int p = 0;
        for (int i = 0; i < SEGMENTS; i++) {
            int b0 = i * 2;
            int t0 = i * 2 + 1;
            int b1 = ((i + 1) % SEGMENTS) * 2;
            int t1 = ((i + 1) % SEGMENTS) * 2 + 1;
            idx[p++] = b0; idx[p++] = b1; idx[p++] = t0;
            idx[p++] = t0; idx[p++] = b1; idx[p++] = t1;
        }
        for (int i = 0; i < SEGMENTS; i++) {
            int b0 = i * 2;
            int b1 = ((i + 1) % SEGMENTS) * 2;
            idx[p++] = bottomCenter; idx[p++] = b1; idx[p++] = b0; // CW so it faces down
        }
        for (int i = 0; i < SEGMENTS; i++) {
            int t0 = i * 2 + 1;
            int t1 = ((i + 1) % SEGMENTS) * 2 + 1;
            idx[p++] = topCenter; idx[p++] = t0; idx[p++] = t1;
        }
        indexCount = idx.length;

        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();
        ebo = GL15.glGenBuffers();
        GL30.glBindVertexArray(vao);

        FloatBuffer vb = memAllocFloat(verts.length);
        vb.put(verts).flip();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vb, GL15.GL_STATIC_DRAW);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 3 * Float.BYTES, 0);
        GL20.glEnableVertexAttribArray(0);
        memFree(vb);

        IntBuffer ib = memAllocInt(idx.length);
        ib.put(idx).flip();
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ebo);
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, ib, GL15.GL_STATIC_DRAW);
        memFree(ib);

        GL30.glBindVertexArray(0);
    }

    public void render(RemotePlayer entity, Matrix4f viewMatrix, Matrix4f projectionMatrix) {
        if (!initialized || shader == null) return;

        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousVAO = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        boolean wasCullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);

        shader.bind();
        shader.setUniform("view", viewMatrix);
        shader.setUniform("projection", projectionMatrix);

        // Position is at body bottom, but for REMOTE_PLAYER legHeight=0 so feet = position.y.
        Vector3f pos = entity.getPosition();
        Matrix4f model = new Matrix4f()
                .translate(pos)
                .rotateY((float) Math.toRadians(entity.getRotation().y));
        shader.setUniform("model", model);
        shader.setUniform("tint", colorFor(entity.getPlayerId()));

        GL30.glBindVertexArray(vao);
        GL11.glDrawElements(GL11.GL_TRIANGLES, indexCount, GL11.GL_UNSIGNED_INT, 0);

        // Held-item rendering is done in a separate pass through DropRenderer
        // (textured-block path) — see WorldRenderer.renderDrops.

        GL30.glBindVertexArray(previousVAO);
        shader.unbind();
        GL20.glUseProgram(previousProgram);
        if (wasCullEnabled) GL11.glEnable(GL11.GL_CULL_FACE);
    }

    private static Vector3f colorFor(int playerId) {
        // Stable hashed hue per player id.
        float hue = (playerId * 0.61803398875f) % 1.0f;
        return hsvToRgb(hue, 0.7f, 0.9f);
    }

    private static Vector3f hsvToRgb(float h, float s, float v) {
        float r, g, b;
        int i = (int) Math.floor(h * 6);
        float f = h * 6 - i;
        float p = v * (1 - s);
        float q = v * (1 - f * s);
        float t = v * (1 - (1 - f) * s);
        switch (i % 6) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return new Vector3f(r, g, b);
    }

    public void cleanup() {
        if (!initialized) return;
        if (shader != null) shader.cleanup();
        if (vao != 0) GL30.glDeleteVertexArrays(vao);
        if (vbo != 0) GL15.glDeleteBuffers(vbo);
        if (ebo != 0) GL15.glDeleteBuffers(ebo);
        initialized = false;
    }
}
