package com.stonebreak.battletest;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.system.MemoryUtil.*;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL;

/** Opt-in real GL shader/asset smoke check. Never opens a visible game window. */
class BattleTestRenderTest {
    @Test
    void renderAuthoredArenaWithPolarSky() throws Exception {
        assumeTrue(Boolean.getBoolean("stonebreak.battletest.gl"));
        assertTrue(glfwInit(), "GLFW display unavailable");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        int width = 1400, height = 900;
        long window = glfwCreateWindow(width, height, "Arena smoke test", 0, 0);
        assertNotEquals(0, window);
        try {
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            try (var renderer = new BattleTestRenderer()) {
                var mesh = BattleTestMesh.load(BattleTestArena.load());
                Matrix4f projection =
                    new Matrix4f().perspective((float) Math.toRadians(72), (float) width / height, .05f, 500);
                capture(renderer, mesh, projection, new Vector3f(0, 1.7f, 9), new Vector3f(0, 2, -8), width,
                    height, "/tmp/battletest-player.png");
                capture(renderer, mesh, projection, new Vector3f(31, 24, 39), new Vector3f(0, 1, 0), width,
                    height, "/tmp/battletest-overview.png");
                assertEquals(GL_NO_ERROR, glGetError());
            }
        } finally {
            glfwDestroyWindow(window);
            glfwTerminate();
        }
    }
    private void capture(BattleTestRenderer renderer, BattleTestMesh mesh, Matrix4f projection, Vector3f eye,
        Vector3f target, int w, int h, String file) throws Exception {
        glViewport(0, 0, w, h);
        glClearColor(1, 0, 1, 1);
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
        renderer.render(mesh, projection, new Matrix4f().lookAt(eye, target, new Vector3f(0, 1, 0)), eye, 25);
        var pixels = memAlloc(w * h * 4);
        try {
            glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            int notClear = 0;
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int i = ((h - y - 1) * w + x) * 4;
                    int rgb = ((pixels.get(i) & 255) << 16) | ((pixels.get(i + 1) & 255) << 8)
                        | (pixels.get(i + 2) & 255);
                    image.setRGB(x, y, rgb);
                    if (rgb != 0xff00ff)
                        notClear++;
                }
            assertTrue(notClear > w * h * .99, "Sky and scene should fill the viewport");
            ImageIO.write(image, "png", Path.of(file).toFile());
        } finally {
            memFree(pixels);
        }
    }
}
