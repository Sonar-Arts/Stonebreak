package com.stonebreak.world.bench;

import com.openmason.engine.voxel.mms.mmsCore.MmsMeshBuilder;
import com.openmason.engine.voxel.mms.mmsCore.MmsMeshData;
import com.openmason.engine.voxel.mms.mmsCore.MmsQuadCodec;
import com.openmason.engine.voxel.mms.mmsCore.MmsQuadMeshBuilder;
import com.openmason.engine.voxel.mms.mmsCore.MmsRenderableHandle;
import com.openmason.engine.voxel.mms.mmsCore.MmsVertexFormat;
import com.stonebreak.core.window.DisplayBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * GL-level proof that a {@link MmsVertexFormat#QUAD16} mesh drawn through the
 * production upload path covers the SAME pixels as the equivalent
 * {@link MmsVertexFormat#COMPACT20} mesh, using the exact pull-decode GLSL
 * block shipped in {@code world.vert}. Needs a display — gated on
 * {@code -Dstonebreak.bench=true} like the lab.
 */
class PulledQuadRenderTest {

    private long window;

    @AfterEach
    void tearDown() {
        MmsVertexFormat.override(MmsVertexFormat.DEFAULT);
        if (window != 0) {
            GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
            window = 0;
        }
    }

    @Test
    void pulledQuadCoversTheSamePixelsAsCompact20() throws Exception {
        assumeTrue(Boolean.getBoolean("stonebreak.bench"), "manual GL test (-Dstonebreak.bench=true)");
        DisplayBackend.initialize();
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 2);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_COMPAT_PROFILE);
        window = GLFW.glfwCreateWindow(64, 64, "pull-test", 0, 0);
        assumeTrue(window != 0, "no GL window");
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();

        // The shipped vertex shader's pull block, verbatim.
        String worldVert = Files.readString(Path.of(LabConfig.repoRoot().toString(),
            "stonebreak-game/src/main/resources/shaders/world/world.vert"), StandardCharsets.UTF_8);
        int start = worldVert.indexOf("// ── Vertex pulling");
        int end = worldVert.indexOf("void main()");
        String pullBlock = worldVert.substring(start, end);

        String vert = """
            #version 330 core
            layout (location=0) in vec3 position;
            layout (location=1) in vec2 texCoord;
            layout (location=2) in vec3 normal;
            layout (location=3) in vec4 aFlags;
            layout (location=4) in float aLayer;
            layout (location=5) in vec4 aOrigin;
            uniform mat4 u_mvp;
            out float v_light;
            """ + pullBlock + """
            void main() {
                vec3 localPos; vec2 uv; vec3 nrm; vec4 flags; float layer;
                if (aOrigin.w < 0.0) {
                    pullQuad(localPos, uv, nrm, flags, layer);
                    localPos += aOrigin.xyz;
                } else {
                    localPos = aOrigin.xyz + position * aOrigin.w;
                    flags = aFlags;
                }
                v_light = flags.w;
                gl_Position = u_mvp * vec4(localPos, 1.0);
            }
            """;
        String frag = """
            #version 330 core
            in float v_light;
            out vec4 color;
            void main() { color = vec4(1.0, v_light, 0.0, 1.0); }
            """;
        int program = link(vert, frag);
        GL20.glUseProgram(program);
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "u_quads"), MmsQuadCodec.QUAD_TEXTURE_UNIT);
        // Ortho top-down over the 16×16 block square at x,z ∈ [0,16), looking down -Y.
        float[] mvp = ortho(0f, 16f, 0f, 16f);
        GL20.glUniformMatrix4fv(GL20.glGetUniformLocation(program, "u_mvp"), false, mvp);

        // COMPACT20 reference: one top face quad, 16×16 at y=0, light 1.
        MmsVertexFormat.override(MmsVertexFormat.COMPACT20);
        MmsMeshBuilder b = MmsMeshBuilder.createWithCapacity(4).setOrigin(0f, 0f, 0f);
        b.beginFace();
        b.addVertex(0, 0, 16, 0, 16, 0, 1, 0, 0, 0, 0, 1, 0);
        b.addVertex(16, 0, 16, 16, 16, 0, 1, 0, 0, 0, 0, 1, 0);
        b.addVertex(16, 0, 0, 16, 0, 0, 1, 0, 0, 0, 0, 1, 0);
        b.addVertex(0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0);
        b.endFace();
        MmsMeshData ref = b.build();
        int refPixels = drawAndCount(ref);

        MmsVertexFormat.override(MmsVertexFormat.QUAD16);
        MmsQuadMeshBuilder qb = new MmsQuadMeshBuilder(1).setOrigin(0f, 0f, 0f);
        qb.addQuad(0, 0, 0, 0, 16, 16, 0, false, false, 0, 1f, 1f, 1f, 1f);
        MmsMeshData pulled = qb.build();
        int pulledPixels = drawAndCount(pulled);

        // Half-size pulled quad: 8×16 → half the coverage.
        qb.reset().setOrigin(0f, 0f, 0f);
        qb.addQuad(0, 0, 0, 0, 8, 16, 0, false, false, 0, 1f, 1f, 1f, 1f);
        int halfPixels = drawAndCount(qb.build());

        System.out.printf("[pull-test] compact20=%d px, quad16=%d px, quad16 half=%d px (64x64 viewport)%n",
            refPixels, pulledPixels, halfPixels);
        assertTrue(refPixels > 3000, "reference quad must cover most of the viewport: " + refPixels);
        assertEquals(refPixels, pulledPixels, "pulled quad must cover exactly the reference pixels");
        assertEquals(refPixels / 2, halfPixels, 64, "8×16 pulled quad covers half");
    }

    private static int drawAndCount(MmsMeshData mesh) {
        GL11.glViewport(0, 0, 64, 64);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glCullFace(GL11.GL_BACK);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glClearColor(0, 0, 0, 1);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        MmsRenderableHandle h = MmsRenderableHandle.upload(mesh, false);
        h.render();
        GL11.glFinish();
        ByteBuffer px = BufferUtils.createByteBuffer(64 * 64 * 4);
        GL11.glReadPixels(0, 0, 64, 64, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
        int count = 0;
        for (int i = 0; i < 64 * 64; i++) {
            if ((px.get(i * 4) & 0xFF) > 128) {
                count++;
            }
        }
        h.close();
        int err = GL11.glGetError();
        assertEquals(0, err, "GL error 0x" + Integer.toHexString(err));
        return count;
    }

    private static float[] ortho(float l, float r, float b, float t) {
        // Column-major; maps x∈[l,r] → [-1,1], z∈[b,t] → [-1,1], y collapsed.
        return new float[]{
            2f / (r - l), 0, 0, 0,
            0, 0, 0, 0,
            0, 2f / (t - b), 0, 0,
            -(r + l) / (r - l), -(t + b) / (t - b), 0, 1};
    }

    private static int link(String vs, String fs) {
        int v = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(v, vs);
        GL20.glCompileShader(v);
        assertEquals(1, GL20.glGetShaderi(v, GL20.GL_COMPILE_STATUS), GL20.glGetShaderInfoLog(v));
        int f = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(f, fs);
        GL20.glCompileShader(f);
        assertEquals(1, GL20.glGetShaderi(f, GL20.GL_COMPILE_STATUS), GL20.glGetShaderInfoLog(f));
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, v);
        GL20.glAttachShader(p, f);
        GL20.glLinkProgram(p);
        assertEquals(1, GL20.glGetProgrami(p, GL20.GL_LINK_STATUS), GL20.glGetProgramInfoLog(p));
        return p;
    }
}
