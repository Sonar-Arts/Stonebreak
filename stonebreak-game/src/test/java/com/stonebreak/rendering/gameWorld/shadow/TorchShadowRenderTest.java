package com.stonebreak.rendering.gameWorld.shadow;

import com.openmason.engine.rendering.shadow.CascadedShadowMap;
import com.openmason.engine.rendering.shadow.PointShadowProjection;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rendering.shaders.ShaderResourceLoader;
import com.stonebreak.rendering.models.entities.SbeEntityRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;
import static org.mockito.Mockito.*;

/** Opt-in hidden-window GL proof, including production shader compilation and depth-copy behavior. */
class TorchShadowRenderTest {
    @Test
    void allDirectionsOccludeAndMovingShadowsDoNotLeaveTrails() {
        assumeTrue(Boolean.getBoolean("stonebreak.lighting.gl"));
        assertTrue(glfwInit(), "GLFW display unavailable");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        long window = glfwCreateWindow(32, 32, "Torch shadow regression", 0, 0);
        assertNotEquals(0, window);
        try {
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            compileReceivers();
            verifyDepthAndMotion();
            verifyLiveCasterScheduling();
            assertEquals(GL_NO_ERROR, glGetError());
        } finally {
            GL.setCapabilities(null);
            glfwDestroyWindow(window);
            glfwTerminate();
        }
    }

    private static void verifyLiveCasterScheduling() {
        var world = mock(com.stonebreak.world.World.class);
        var registry = mock(com.stonebreak.blocks.anim.AnimatedBlockRegistry.class);
        when(world.getAnimatedBlockRegistry()).thenReturn(registry);
        when(registry.positions()).thenReturn(java.util.Set.of(new com.openmason.engine.util.BlockPos(0, 0, 0)));
        when(world.getBlockAt(0, 0, 0)).thenReturn(com.stonebreak.blocks.BlockType.TORCH_PLACED);
        var entities = mock(com.stonebreak.rendering.models.entities.EntityRenderer.class);
        var animated = mock(com.stonebreak.rendering.models.blocks.AnimatedBlockRenderer.class);
        var textures = mock(com.stonebreak.rendering.textures.BlockTextureArray.class);
        var settings = com.stonebreak.config.Settings.getInstance();
        boolean enabled = settings.getShadowsEnabled();
        String quality = settings.getShadowQuality();
        ShaderProgram receiver = new ShaderProgram();
        try (TorchShadowRenderer renderer = new TorchShadowRenderer(textures)) {
            settings.setShadowsEnabled(true);
            settings.setShadowQuality("LOW");
            receiver.createVertexShader("#version 330 core\nvoid main(){gl_Position=vec4(0,0,0,1);}");
            receiver.createFragmentShader("#version 330 core\n"
                    + resource("/shaders/lighting/point_lights.glsl")
                    + "\nout vec4 color; void main(){color=vec4(pointLightContribution(vec3(1),vec3(0,1,0),1.0),1);}");
            receiver.link();
            for (int frame = 0; frame < 2; frame++) {
                com.stonebreak.rendering.lighting.DynamicLights.update(world, null, new Vector3f(), frame);
                glViewport(2, 3, 17, 19);
                glEnable(GL_BLEND);
                glDisable(GL_DEPTH_TEST);
                renderer.render(world, null, java.util.List.of(), entities, animated, frame);
                int[] viewport = new int[4];
                glGetIntegerv(GL_VIEWPORT, viewport);
                assertArrayEquals(new int[]{2, 3, 17, 19}, viewport);
                assertTrue(glIsEnabled(GL_BLEND));
                assertFalse(glIsEnabled(GL_DEPTH_TEST));
                assertEquals(0, glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));
                receiver.bind();
                com.stonebreak.rendering.lighting.DynamicLights.applyTo(receiver);
                assertEquals(1, glGetUniformi(receiver.getProgramId(), glGetUniformLocation(receiver.getProgramId(), "u_pointLightCount")));
                assertEquals(1, glGetUniformi(receiver.getProgramId(), glGetUniformLocation(receiver.getProgramId(), "u_pointShadowsEnabled")));
                receiver.unbind();
            }
            // The stationary light/terrain is cached on frame two, but moving actors still
            // render into every face on BOTH frames, independently of daylight.
            verify(entities, times(12)).renderShadowCasters(isNull(), any(), any(), any(), eq(9.5f));
            verify(animated, times(12)).renderShadowCasters(eq(world), any(), any(), any(), eq(9.5f), anyFloat());
            settings.setShadowsEnabled(false);
            com.stonebreak.rendering.lighting.DynamicLights.update(world, null, new Vector3f(), 2);
            renderer.render(world, null, java.util.List.of(), entities, animated, 2);
            receiver.bind();
            com.stonebreak.rendering.lighting.DynamicLights.applyTo(receiver);
            assertEquals(0, glGetUniformi(receiver.getProgramId(), glGetUniformLocation(receiver.getProgramId(), "u_pointShadowsEnabled")));
        } finally {
            receiver.cleanup();
            settings.setShadowsEnabled(enabled);
            settings.setShadowQuality(quality);
            com.stonebreak.rendering.lighting.DynamicLights.update(null, null, null, 0);
        }
    }

    private static void compileReceivers() {
        for (String name : new String[]{"world/world", "water/water"}) {
            ShaderProgram shader = new ShaderProgram();
            try {
                shader.createVertexShader(resource("/shaders/" + name + ".vert"));
                shader.createFragmentShader(resource("/shaders/" + name + ".frag"));
                shader.link();
            } finally { shader.cleanup(); }
        }
        SbeEntityRenderer entities = new SbeEntityRenderer();
        try { entities.initialize(); } finally { entities.cleanup(); }
    }

    private static String resource(String path) {
        return ShaderResourceLoader.load(path, p -> TorchShadowRenderTest.class.getResourceAsStream(p));
    }

    private static void verifyDepthAndMotion() {
        ShaderProgram caster = ChunkShadowShader.create();
        ShaderProgram receiver = new ShaderProgram();
        int vao = glGenVertexArrays(), vbo = glGenBuffers();
        try (CascadedShadowMap terrain = new CascadedShadowMap(128, 6);
             CascadedShadowMap live = new CascadedShadowMap(128, 6)) {
            receiver.createVertexShader("""
                    #version 330 core
                    void main() {
                        vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                        gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                    }
                    """);
            receiver.createFragmentShader("#version 330 core\n" + resource("/shaders/shadow/point_shadow.glsl") + """
                    uniform vec3 samplePosition;
                    out vec4 color;
                    void main() {
                        float visibility = pointShadowVisibility(samplePosition, vec3(0), vec3(0), 9.5, 0);
                        color = vec4(vec3(visibility), 1);
                    }
                    """);
            receiver.link();
            receiver.bind();
            receiver.setInt("u_pointShadowMap", 6);
            receiver.setInt("u_pointShadowsEnabled", 1);
            glBindVertexArray(vao);
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0);
            glEnableVertexAttribArray(0);
            glVertexAttrib4f(5, 0, 0, 0, 1); // production attribute-based position path
            glDisable(GL_CULL_FACE);
            glDisable(GL_BLEND);
            Matrix4f projection = PointShadowProjection.projection(9.5f, new Matrix4f());
            Vector3f origin = new Vector3f();
            for (int face = 0; face < 6; face++) {
                terrain.beginCascade(face);
                drawBlocker(caster, projection, PointShadowProjection.view(origin, face, new Matrix4f()), 1f);
                terrain.copyLayerTo(face, live);
            }
            live.bindForSampling(6);
            for (int face = 0; face < 6; face++) {
                Matrix4f inverse = PointShadowProjection.view(origin, face, new Matrix4f()).invert();
                assertVisibility(receiver, inverse.transformPosition(new Vector3f(0, 0, -3)), false);
                assertVisibility(receiver, inverse.transformPosition(new Vector3f(0, 0, -1)), true);
                assertVisibility(receiver, inverse.transformPosition(new Vector3f(2.7f, 0, -3)), true);
                float edgeVisibility = readVisibility(receiver, inverse.transformPosition(new Vector3f(1.5f, 0, -3)));
                assertTrue(edgeVisibility > .05f && edgeVisibility < .95f,
                        "finite filtering footprint softens the silhouette on face " + face);
            }

            // A moving caster now covers the previously open edge of +X. Re-copying terrain
            // next frame must erase its old shadow while preserving the stationary wall.
            Matrix4f view = PointShadowProjection.view(origin, 0, new Matrix4f());
            terrain.copyLayerTo(0, live);
            drawBlocker(caster, projection, view, 2f);
            Vector3f edge = new Matrix4f(view).invert().transformPosition(new Vector3f(2.7f, 0, -3));
            assertVisibility(receiver, edge, false);
            terrain.copyLayerTo(0, live);
            assertVisibility(receiver, edge, true);
            assertVisibility(receiver, new Vector3f(3, 0, 0), false);
            receiver.bind();
            receiver.setInt("u_pointShadowsEnabled", 0);
            assertVisibility(receiver, new Vector3f(3, 0, 0), true);
        } finally {
            glBindVertexArray(0);
            glDeleteBuffers(vbo);
            glDeleteVertexArrays(vao);
            caster.cleanup();
            receiver.cleanup();
        }
    }

    private static void drawBlocker(ShaderProgram caster, Matrix4f projection, Matrix4f view, float halfSize) {
        Matrix4f inverse = new Matrix4f(view).invert();
        float[] vertices = new float[18];
        int[] corners = {0, 1, 2, 0, 2, 3};
        Vector3f[] points = {new Vector3f(-halfSize, -halfSize, -2), new Vector3f(halfSize, -halfSize, -2),
                new Vector3f(halfSize, halfSize, -2), new Vector3f(-halfSize, halfSize, -2)};
        for (int i = 0; i < corners.length; i++) {
            Vector3f p = inverse.transformPosition(new Vector3f(points[corners[i]]));
            vertices[i * 3] = p.x; vertices[i * 3 + 1] = p.y; vertices[i * 3 + 2] = p.z;
        }
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STREAM_DRAW);
        glEnable(GL_DEPTH_TEST);
        glDepthMask(true);
        caster.bind();
        caster.setUniform("u_lightViewProj", new Matrix4f(projection).mul(view));
        glDrawArrays(GL_TRIANGLES, 0, 6);
    }

    private static void assertVisibility(ShaderProgram receiver, Vector3f position, boolean lit) {
        assertEquals(lit ? 1f : 0f, readVisibility(receiver, position), .02f, "visibility at " + position);
    }

    private static float readVisibility(ShaderProgram receiver, Vector3f position) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, 32, 32);
        glDisable(GL_DEPTH_TEST);
        receiver.bind();
        receiver.setVec3("samplePosition", position);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        float[] pixel = new float[4];
        glReadPixels(16, 16, 1, 1, GL_RGBA, GL_FLOAT, pixel);
        return pixel[0];
    }
}
