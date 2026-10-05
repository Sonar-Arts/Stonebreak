package com.stonebreak.rendering.UI.backend.skija;

import com.openmason.engine.ui.masonry.MasonryEnvironment;
import com.stonebreak.core.Game;

import static org.lwjgl.glfw.GLFW.glfwGetClipboardString;
import static org.lwjgl.glfw.GLFW.glfwSetClipboardString;

/**
 * The game window's system clipboard for Masonry text fields ({@code MClipboard}). GLFW
 * clipboard calls need the window handle and must run on the main thread; both hold for UI
 * input callbacks. Failures read as empty — a missing clipboard never takes the game down.
 */
public final class GlfwClipboard implements MasonryEnvironment.ClipboardProvider {

    @Override
    public String read() {
        try {
            long window = Game.getInstance().getWindow();
            if (window != 0) {
                String s = glfwGetClipboardString(window);
                if (s != null) return s;
            }
        } catch (Exception ignored) {
            // see class doc
        }
        return "";
    }

    @Override
    public void write(String text) {
        if (text == null) return;
        try {
            long window = Game.getInstance().getWindow();
            if (window != 0) glfwSetClipboardString(window, text);
        } catch (Exception ignored) {
            // see class doc
        }
    }
}
