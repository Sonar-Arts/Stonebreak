package com.stonebreak.rendering.gameWorld.shadow;

import com.openmason.engine.rendering.shadow.CascadedShadowMap;
import com.openmason.engine.rendering.shadow.PointShadowProjection;
import com.openmason.engine.rendering.shaders.ShaderProgram;
import com.openmason.engine.rendering.shaders.ShaderResourceLoader;
import com.stonebreak.rendering.lighting.PointLightGlsl;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;

/** Real per-light depth maps + production lighting math, read back without 8-bit clipping. */
class MultiLightShadowRenderTest {
    private static final float RADIUS = 9.5f;
    private static final Vector3f LEFT = new Vector3f(-2, 3, -2);
    private static final Vector3f RIGHT = new Vector3f(2, 3, -2);
    private static final Vector3f ALBEDO = new Vector3f(.8f, .6f, .4f);
    private static final Vector3f BASE = new Vector3f(.08f, .06f, .04f);
    private static final Vector3f OPEN = new Vector3f(0, 0, -3);
    private static final int LAST_SLOT = PointLightGlsl.MAX_LIGHTS - 1;
    private long window;
    private int vao, vbo, colorFbo, colorTexture;
    private ShaderProgram caster, receiver;
    private CascadedShadowMap shadows;

    @BeforeEach
    void createContextAndScene() {
        assumeTrue(Boolean.getBoolean("stonebreak.lighting.gl"));
        assertTrue(glfwInit(), "GLFW display unavailable");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        window = glfwCreateWindow(16, 16, "Multiple light regression", 0, 0);
        assertNotEquals(0, window);
        glfwMakeContextCurrent(window);
        GL.createCapabilities();
        caster = ChunkShadowShader.create();
        receiver = new ShaderProgram();
        receiver.createVertexShader("""
                #version 330 core
                void main() {
                    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                }
                """);
        receiver.createFragmentShader("#version 330 core\n"
                + ShaderResourceLoader.load(PointLightGlsl.PATH, p -> MultiLightShadowRenderTest.class.getResourceAsStream(p))
                + """
                uniform vec3 samplePosition, sampleNormal, baseLight, albedo;
                uniform float ambient, skyExposure, sunVisibility;
                uniform int composite;
                out vec4 color;
                void main() {
                    if (composite == 2) {
                        color = vec4(pointShadowVisibility(samplePosition, sampleNormal,
                                u_pointLightPos[0].xyz, 9.5, int(u_pointLightColor[0].w)),
                                pointShadowVisibility(samplePosition, sampleNormal,
                                u_pointLightPos[1].xyz, 9.5, int(u_pointLightColor[1].w)), 0, 1);
                        return;
                    }
                    vec3 torch = pointLightContribution(samplePosition, sampleNormal,
                            pointLightWeight(ambient, skyExposure, sunVisibility));
                    color = vec4(composite == 0 ? torch : applyPointLight(baseLight, albedo, torch), 1);
                }
                """);
        receiver.link();
        receiver.bind();
        receiver.setInt("u_pointShadowMap", 6);
        receiver.setInt("u_pointIndirectMap", 8);
        receiver.setInt("u_pointShadowsEnabled", 1);
        receiver.setFloat("ambient", .3f);
        receiver.setFloat("skyExposure", 1);
        receiver.setFloat("sunVisibility", 1);
        receiver.setVec3("baseLight", BASE);
        receiver.setVec3("albedo", ALBEDO);
        receiver.setVec3("sampleNormal", new Vector3f(0, 1, 0));

        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0);
        glEnableVertexAttribArray(0);
        glVertexAttrib4f(5, 0, 0, 0, 1);
        glDisable(GL_CULL_FACE);
        glDisable(GL_BLEND);
        shadows = new CascadedShadowMap(128, PointLightGlsl.MAX_LIGHTS * 6);

        colorTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, colorTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, 1, 1, 0, GL_RGBA, GL_FLOAT, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        colorFbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, colorFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTexture, 0);
        glDrawBuffer(GL_COLOR_ATTACHMENT0);
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));
    }

    @AfterEach
    void cleanup() {
        if (window == 0) return;
        if (shadows != null) shadows.close();
        if (caster != null) caster.cleanup();
        if (receiver != null) receiver.cleanup();
        glDeleteFramebuffers(colorFbo);
        glDeleteTextures(colorTexture);
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        int error = glGetError();
        GL.setCapabilities(null);
        glfwDestroyWindow(window);
        glfwTerminate();
        assertEquals(GL_NO_ERROR, error);
    }

    @Test
    void oneObjectCastsTwoIndependentShadowsAndOverlapsPreserveAmbient() {
        renderObject(0);
        Vector3f leftShadow = new Vector3f(1, 0, 1);
        Vector3f rightShadow = new Vector3f(-1, 0, 1);
        for (Vector3f point : new Vector3f[]{leftShadow, rightShadow, OPEN}) {
            setLights(true, false, false);
            Vector3f a = sample(point, false);
            setLights(false, true, false);
            Vector3f b = sample(point, false);
            setLights(true, true, false);
            Vector3f combined = sample(point, false);
            assertColor(new Vector3f(a).add(b), combined, "sum of individually shadowed sources");
            if (point == leftShadow) {
                assertColor(new Vector3f(), a, "left light blocked by object");
                assertTrue(b.length() > .05f, "right light fills left light's shadow");
            } else if (point == rightShadow) {
                assertColor(new Vector3f(), b, "right light blocked by object");
                assertTrue(a.length() > .05f, "left light fills right light's shadow");
            } else {
                assertTrue(a.length() > .05f && b.length() > .05f, "both lights reach open ground");
            }
            Vector3f shaded = sample(point, true);
            setLights(true, true, true);
            assertColor(combined, sample(point, false), "source order must not change shadow ownership");
            assertColor(shaded, sample(point, true), "source order must not change final brightness");
        }
        setLights(true, true, false);
        assertColor(new Vector3f(), sample(new Vector3f(), false), "both lights blocked");
        assertColor(BASE, sample(new Vector3f(), true), "overlapping shadows do not subtract ambient twice");

        setLights(false, true, false);
        Vector3f rightOnly = sample(leftShadow, false);
        setLights(true, true, true);
        receiver.setInt("u_pointLightCount", 1);
        assertColor(rightOnly, sample(leftShadow, false), "removing a source preserves the remaining source's shadow slot");
        assertColor(new Vector3f(), sample(rightShadow, false), "removed sources cannot leave residual illumination");

        renderObject(10); // Same two sources, but the moving caster leaves their paths.
        setLights(true, true, false);
        Vector3f moved = sample(leftShadow, false);
        receiver.setInt("u_pointShadowsEnabled", 0);
        assertColor(sample(leftShadow, false), moved, "neither source retains the object's old shadow");
    }

    @Test
    void enclosedRoomWallsAndCeilingDoNotShadowThemselves() {
        Vector3f left = new Vector3f(-2.8f, 2.2f, -1.3f);
        Vector3f right = new Vector3f(.4f, 2.8f, 2.85f);
        float[] room = {-3,0,-3, 3,0,-3, 3,4,-3, -3,4,-3,
                -3,0,3, 3,0,3, 3,4,3, -3,4,3};
        for (int resolution : new int[]{128, 256, 512}) {
            shadows.close();
            shadows = new CascadedShadowMap(resolution, PointLightGlsl.MAX_LIGHTS * 6);
            renderBox(room, left, right);
            receiver.bind();
            setLight(0, left, new Vector3f(1), 0);
            setLight(1, right, new Vector3f(1), LAST_SLOT);
            // Every surface in an empty convex room sees both sources. Include grazing
            // angles and cube-face transitions; receivers are also present in the depth map.
            for (int axis = 0; axis < 3; axis++) for (int side = 0; side < 2; side++) {
                Vector3f normal = new Vector3f().setComponent(axis, side == 0 ? 1 : -1);
                receiver.setVec3("sampleNormal", normal);
                for (int a = 0; a <= 16; a++) for (int b = 0; b <= 16; b++) {
                    Vector3f point = new Vector3f();
                    point.setComponent(axis, axis == 1 ? side * 4 : (side == 0 ? -3 : 3));
                    int u = (axis + 1) % 3, v = (axis + 2) % 3;
                    point.setComponent(u, u == 1 ? .3f + a * 3.4f / 16 : -2.7f + a * 5.4f / 16);
                    point.setComponent(v, v == 1 ? .3f + b * 3.4f / 16 : -2.7f + b * 5.4f / 16);
                    Vector3f visibility = sample(point, 2);
                    assertEquals(1f, visibility.x, .01f, "left light at " + point + " resolution " + resolution);
                    assertEquals(1f, visibility.y, .01f, "right light at " + point + " resolution " + resolution);
                }
            }
        }
    }

    @Test
    void reflectedLightFillsDirectShadowsButDoesNotCrossWallsOrSourceSlots() {
        renderObject(0);
        setLights(true, false, false);
        Vector3f point = new Vector3f(1.5f, 0, 1.5f);
        assertColor(new Vector3f(), sample(point, false), "object blocks direct light");
        int size = com.openmason.engine.rendering.lighting.IndirectLightAtlas.SIZE;
        Vector3f origin = new Vector3f((float) Math.floor(LEFT.x) - 11,
                (float) Math.floor(LEFT.y) - 11, (float) Math.floor(LEFT.z) - 11);
        var volume = new com.openmason.engine.voxel.lighting.IndirectLightVolume(size);
        for (int z = 0; z < size; z++) for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            int wx = (int) origin.x + x, wy = (int) origin.y + y, wz = (int) origin.z + z;
            // Conservative voxelization of the box, plus the floor it stands on.
            boolean solid = wy < 0 || (wx >= -1 && wx <= 0 && wz >= -1 && wz <= 0 && wy < 2);
            volume.setCell(x, y, z, solid, solid);
        }
        volume.rebuild(LEFT.x - origin.x, LEFT.y - origin.y, LEFT.z - origin.z, RADIUS);
        try (var atlas = new com.openmason.engine.rendering.lighting.IndirectLightAtlas()) {
            atlas.upload(0, volume.data());
            atlas.upload(LAST_SLOT, new float[size * size * size]);
            atlas.bind(8);
            receiver.setInt("u_pointIndirectEnabled", 1);
            Vector3f fill = sample(point, false);
            assertTrue(fill.x > .0001f, "warm bounce reaches the direct shadow around the corner");
            assertTrue(fill.x < .1f, "reflected light stays dim");
            assertTrue(fill.x > fill.y && fill.y > fill.z, "bounce retains its source's warm color");

            setLights(false, true, false);
            Vector3f rightOnly = sample(point, false);
            receiver.setInt("u_pointIndirectEnabled", 0);
            assertColor(rightOnly, sample(point, false), "slot zero's bounce cannot leak into the other light");

            // An unbroken wall must stop both seeding and propagation into its far side.
            for (int z = 0; z < size; z++) for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
                boolean wall = origin.x + x == 0;
                volume.setCell(x, y, z, wall, wall);
            }
            volume.rebuild(LEFT.x - origin.x, LEFT.y - origin.y, LEFT.z - origin.z, RADIUS);
            atlas.upload(0, volume.data());
            setLights(true, false, false);
            receiver.setInt("u_pointIndirectEnabled", 1);
            assertColor(new Vector3f(), sample(point, false), "no indirect light through a sealed wall");
        }
    }

    @Test
    void lowTorchShadowDirectionsAgreeWithGeometricOcclusion() {
        Vector3f[][] placements = {
                {new Vector3f(-1.5f, .6f, -1.5f), new Vector3f(1.5f, .6f, -1.5f)},
                {new Vector3f(-1.5f, .6f, 0), new Vector3f(1.5f, .6f, 0)},
                {new Vector3f(-.7f, .68f, -1.5f), new Vector3f(1.5f, 2.68f, .3f)}
        };
        int checked = 0;
        for (Vector3f[] lights : placements) {
            renderObject(0, lights[0], lights[1]);
            receiver.bind();
            receiver.setInt("u_pointLightCount", 2);
            setLight(0, lights[0], new Vector3f(1), 0);
            setLight(1, lights[1], new Vector3f(1), LAST_SLOT);
            for (int x = -12; x <= 12; x++) {
                for (int z = -12; z <= 12; z++) {
                    Vector3f point = new Vector3f(x * .25f, 0, z * .25f);
                    if (Math.abs(point.x) < .55f && Math.abs(point.z) < .55f) continue;
                    Vector3f visibility = sample(point, 2);
                    for (int source = 0; source < 2; source++) {
                        boolean blocked = blockedByBox(lights[source], point);
                        // Skip silhouette-edge samples where the finite texel footprint and
                        // hardware PCF intentionally mix lit and blocked rays.
                        boolean stable = true;
                        for (float dx : new float[]{-.18f, 0, .18f}) {
                            for (float dz : new float[]{-.18f, 0, .18f}) {
                                stable &= blocked == blockedByBox(lights[source], new Vector3f(point).add(dx, 0, dz));
                            }
                        }
                        if (!stable) continue;
                        assertEquals(blocked ? 0f : 1f, visibility.get(source), .05f,
                                "torch " + lights[source] + " floor point " + point);
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 2000, "cover stable lit and shadowed regions across all placements");
    }

    @Test
    void solidSideAndTopFacesBlockSourcesOnTheOtherSide() {
        Vector3f left = new Vector3f(-1.5f, .6f, 0);
        Vector3f right = new Vector3f(1.5f, .6f, 0);
        renderObject(0, left, right);
        receiver.bind();
        receiver.setInt("u_pointLightCount", 2);
        setLight(0, left, new Vector3f(1), 0);
        setLight(1, right, new Vector3f(1), LAST_SLOT);
        Vector3f[] points = {new Vector3f(-.45f, .8f, 0), new Vector3f(.45f, .8f, 0),
                new Vector3f(0, .8f, -.45f), new Vector3f(0, .8f, .45f), new Vector3f(0, 1.6f, 0)};
        Vector3f[] normals = {new Vector3f(-1, 0, 0), new Vector3f(1, 0, 0),
                new Vector3f(0, 0, -1), new Vector3f(0, 0, 1), new Vector3f(0, 1, 0)};
        for (int face = 0; face < points.length; face++) {
            receiver.setVec3("sampleNormal", normals[face]);
            Vector3f visibility = sample(points[face], 2);
            assertEquals(blockedByBox(left, points[face]) ? 0f : 1f, visibility.x, .05f, "left torch, face " + face);
            assertEquals(blockedByBox(right, points[face]) ? 0f : 1f, visibility.y, .05f, "right torch, face " + face);
        }
    }

    @Test
    void directLightDoesNotInventDiffuseFillOnBackFacingSurfaces() {
        receiver.bind();
        receiver.setInt("u_pointShadowsEnabled", 0);
        receiver.setInt("u_pointLightCount", 1);
        setLight(0, LEFT, new Vector3f(1), 0);
        Vector3f towardLight = new Vector3f(LEFT).sub(OPEN).normalize();
        receiver.setVec3("sampleNormal", towardLight);
        float facing = sample(OPEN, false).x;
        receiver.setVec3("sampleNormal", new Vector3f(towardLight).negate());
        assertEquals(0f, sample(OPEN, false).x / facing, .00001f, "back-facing direct light");
        receiver.setVec3("sampleNormal", new Vector3f(towardLight).cross(0, 1, 0).normalize());
        assertEquals(0f, sample(OPEN, false).x / facing, .00001f, "grazing-angle direct light");
    }

    private static boolean blockedByBox(Vector3f light, Vector3f point) {
        float entry = 0f, exit = 1f;
        for (int axis = 0; axis < 3; axis++) {
            float min = axis == 1 ? 0 : -.45f;
            float max = axis == 1 ? 1.6f : .45f;
            float origin = light.get(axis), direction = point.get(axis) - origin;
            if (Math.abs(direction) < 1e-6f) {
                if (origin < min || origin > max) return false;
                continue;
            }
            float a = (min - origin) / direction, b = (max - origin) / direction;
            entry = Math.max(entry, Math.min(a, b));
            exit = Math.min(exit, Math.max(a, b));
            if (entry > exit) return false;
        }
        return entry < .9999f && exit > 0;
    }

    @Test
    void clusteredLightsApproachTheCeilingWithoutFlatteningShadowContrast() {
        receiver.bind();
        receiver.setInt("u_pointShadowsEnabled", 0);
        for (int i = 0; i < PointLightGlsl.MAX_LIGHTS; i++) setLight(i, LEFT, new Vector3f(4), 0);
        receiver.setInt("u_pointLightCount", 0);
        assertColor(BASE, sample(OPEN, true), "no lights preserve the base lighting");
        Vector3f previous = new Vector3f(BASE);
        for (int count : new int[]{1, 2, 3, PointLightGlsl.MAX_LIGHTS}) {
            receiver.setInt("u_pointLightCount", count);
            Vector3f current = sample(OPEN, true);
            for (int channel = 0; channel < 3; channel++) {
                assertTrue(current.get(channel) > previous.get(channel) + .0001f,
                        "losing one of " + count + " overlapping sources must still affect brightness");
                assertTrue(current.get(channel) <= ALBEDO.get(channel) * .95f + .00001f);
            }
            previous.set(current);
        }
        Vector3f brightSun = new Vector3f(.9f, .8f, .5f);
        receiver.setVec3("baseLight", brightSun);
        assertColor(brightSun, sample(OPEN, true), "torches cannot darken existing brighter sunlight");
    }

    @Test
    void sunlightAndTorchShadowsStayIndependent() {
        setLights(true, true, false);
        receiver.setInt("u_pointShadowsEnabled", 0);
        Vector3f night = sample(OPEN, false);
        receiver.setFloat("ambient", 1);
        assertColor(new Vector3f(), sample(OPEN, false), "direct noon sunlight suppresses torch boost");
        assertColor(BASE, sample(OPEN, true), "torch suppression preserves existing sunlight");
        receiver.setFloat("sunVisibility", 0);
        assertColor(night, sample(OPEN, false), "torches can fill a sun shadow at noon");
        receiver.setFloat("sunVisibility", .25f);
        assertColor(new Vector3f(night).mul(.75f), sample(OPEN, false), "smooth partial sun-shadow fill");
        receiver.setFloat("sunVisibility", 1);
        receiver.setFloat("skyExposure", 0);
        assertColor(night, sample(OPEN, false), "caves receive torchlight regardless of time of day");
    }

    private void setLights(boolean left, boolean right, boolean reverse) {
        receiver.bind();
        receiver.setInt("u_pointLightCount", 2);
        // Distinct colors expose cross-source shadow/slot errors; also exercise the last atlas slot.
        setLight(reverse ? 1 : 0, LEFT, left ? new Vector3f(1, .25f, .05f) : new Vector3f(), 0);
        setLight(reverse ? 0 : 1, RIGHT, right ? new Vector3f(.1f, .3f, 1) : new Vector3f(), LAST_SLOT);
    }

    private void setLight(int index, Vector3f position, Vector3f color, int slot) {
        receiver.setVec4("u_pointLightPos[" + index + "]", new org.joml.Vector4f(position, RADIUS));
        receiver.setVec4("u_pointLightColor[" + index + "]", new org.joml.Vector4f(color, slot));
    }

    private Vector3f sample(Vector3f position, boolean composite) {
        return sample(position, composite ? 1 : 0);
    }

    private Vector3f sample(Vector3f position, int mode) {
        glBindFramebuffer(GL_FRAMEBUFFER, colorFbo);
        glViewport(0, 0, 1, 1);
        glDisable(GL_DEPTH_TEST);
        receiver.bind();
        receiver.setVec3("samplePosition", position);
        receiver.setInt("composite", mode);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        float[] pixel = new float[4];
        glReadPixels(0, 0, 1, 1, GL_RGBA, GL_FLOAT, pixel);
        return new Vector3f(pixel[0], pixel[1], pixel[2]);
    }

    private void renderObject(float offsetX) {
        renderObject(offsetX, LEFT, RIGHT);
    }

    private void renderObject(float offsetX, Vector3f left, Vector3f right) {
        // One world-space box, rendered unchanged from both lights' perspectives.
        float[] corners = {-.45f, 0, -.45f, .45f, 0, -.45f, .45f, 1.6f, -.45f, -.45f, 1.6f, -.45f,
                -.45f, 0, .45f, .45f, 0, .45f, .45f, 1.6f, .45f, -.45f, 1.6f, .45f};
        for (int i = 0; i < corners.length; i += 3) corners[i] += offsetX;
        renderBox(corners, left, right);
    }

    private void renderBox(float[] corners, Vector3f left, Vector3f right) {
        int[] indices = {0,1,2, 0,2,3, 4,6,5, 4,7,6, 0,4,5, 0,5,1,
                3,2,6, 3,6,7, 0,3,7, 0,7,4, 1,5,6, 1,6,2};
        float[] vertices = new float[indices.length * 3];
        for (int i = 0; i < indices.length; i++) {
            for (int axis = 0; axis < 3; axis++) vertices[i * 3 + axis] = corners[indices[i] * 3 + axis];
        }
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STREAM_DRAW);
        glEnable(GL_DEPTH_TEST);
        glDepthMask(true);
        // Match the production TorchShadowRenderer's caster bias as well as its shader.
        glEnable(GL_POLYGON_OFFSET_FILL);
        glPolygonOffset(1f, 1f);
        caster.bind();
        Matrix4f projection = PointShadowProjection.projection(RADIUS, new Matrix4f());
        for (int source = 0; source < 2; source++) {
            int slot = source == 0 ? 0 : LAST_SLOT;
            for (int face = 0; face < 6; face++) {
                shadows.beginCascade(slot * 6 + face);
                Matrix4f view = PointShadowProjection.view(source == 0 ? left : right, face, new Matrix4f());
                caster.setUniform("u_lightViewProj", new Matrix4f(projection).mul(view));
                glDrawArrays(GL_TRIANGLES, 0, indices.length);
            }
        }
        shadows.bindForSampling(6);
    }

    private static void assertColor(Vector3f expected, Vector3f actual, String message) {
        for (int channel = 0; channel < 3; channel++) {
            assertEquals(expected.get(channel), actual.get(channel), .00002f, message + " channel " + channel);
        }
    }
}
