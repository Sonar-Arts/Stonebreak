package com.stonebreak.battletest;

import static org.lwjgl.opengl.GL33.*;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Scene renderer with a procedural polar skybox, snow-capped horizon and softly drifting snowfall. */
public final class BattleTestRenderer implements AutoCloseable {
    private ShaderProgram scene, sky;
    private int vao, vbo, skyVao;
    private int count;
    private long bytes;
    private BattleTestMesh uploaded;

    public void render(BattleTestMesh mesh, Matrix4f projection, Matrix4f view, Vector3f camera, float time) {
        if (uploaded != mesh) {
            close();
            try {
                initialize(mesh);
            } catch (RuntimeException e) {
                close();
                throw e;
            }
        }
        int program = glGetInteger(GL_CURRENT_PROGRAM), previousVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int depthFunc = glGetInteger(GL_DEPTH_FUNC);
        boolean depth = glIsEnabled(GL_DEPTH_TEST), cull = glIsEnabled(GL_CULL_FACE),
                blend = glIsEnabled(GL_BLEND);
        boolean depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        try {
            glDisable(GL_CULL_FACE);
            glDisable(GL_BLEND);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_LEQUAL);
            glDepthMask(false);
            sky.bind();
            sky.setMat4("inverseProjection", new Matrix4f(projection).invert());
            sky.setMat4("inverseView", new Matrix4f(view).invert());
            sky.setFloat("time", time);
            glBindVertexArray(skyVao);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            glDepthFunc(GL_LESS);
            glDepthMask(true);
            scene.bind();
            scene.setMat4("projection", projection);
            scene.setMat4("view", view);
            scene.setVec3("camera", camera);
            scene.setFloat("time", time);
            glBindVertexArray(vao);
            glDrawArrays(GL_TRIANGLES, 0, count);
        } finally {
            glUseProgram(program);
            glBindVertexArray(previousVao);
            glDepthFunc(depthFunc);
            glDepthMask(depthMask);
            if (depth)
                glEnable(GL_DEPTH_TEST);
            else
                glDisable(GL_DEPTH_TEST);
            if (cull)
                glEnable(GL_CULL_FACE);
            else
                glDisable(GL_CULL_FACE);
            if (blend)
                glEnable(GL_BLEND);
            else
                glDisable(GL_BLEND);
        }
    }
    private void initialize(BattleTestMesh mesh) {
        scene = shader("scene");
        sky = shader("sky");
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        skyVao = glGenVertexArrays();
        int oldVao = glGetInteger(GL_VERTEX_ARRAY_BINDING), oldBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        try {
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, mesh.vertices(), GL_STATIC_DRAW);
            int stride = BattleTestMesh.STRIDE * Float.BYTES;
            for (int i = 0; i < 4; i++) {
                glVertexAttribPointer(i, i == 3 ? 1 : 3, GL_FLOAT, false, stride, (long) i * 3 * Float.BYTES);
                glEnableVertexAttribArray(i);
            }
        } finally {
            glBindVertexArray(oldVao);
            glBindBuffer(GL_ARRAY_BUFFER, oldBuffer);
        }
        count = mesh.vertices().length / BattleTestMesh.STRIDE;
        bytes = (long) mesh.vertices().length * Float.BYTES;
        GpuMemoryTracker.getInstance().track(GpuMemoryTracker.Category.ENTITY_MESH, bytes);
        uploaded = mesh;
    }
    private static ShaderProgram shader(String name) {
        var p = new ShaderProgram();
        try {
            p.createVertexShader(resource(name + ".vert"));
            p.createFragmentShader(resource(name + ".frag"));
            p.link();
            return p;
        } catch (RuntimeException e) {
            p.cleanup();
            throw e;
        }
    }
    private static String resource(String file) {
        try (var in = BattleTestRenderer.class.getResourceAsStream("/battletest/" + file)) {
            if (in == null)
                throw new IOException("Missing arena shader " + file);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
    @Override
    public void close() {
        if (scene != null) {
            scene.cleanup();
            scene = null;
        }
        if (sky != null) {
            sky.cleanup();
            sky = null;
        }
        if (vao != 0) {
            glDeleteVertexArrays(vao);
            vao = 0;
        }
        if (vbo != 0) {
            glDeleteBuffers(vbo);
            vbo = 0;
        }
        if (skyVao != 0) {
            glDeleteVertexArrays(skyVao);
            skyVao = 0;
        }
        if (bytes != 0) {
            GpuMemoryTracker.getInstance().untrack(GpuMemoryTracker.Category.ENTITY_MESH, bytes);
            bytes = 0;
        }
        uploaded = null;
    }
}
