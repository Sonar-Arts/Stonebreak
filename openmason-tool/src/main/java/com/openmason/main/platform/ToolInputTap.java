package com.openmason.main.platform;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.lwjgl.glfw.GLFW.glfwSetCharCallback;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;

/**
 * Raw GLFW key and character events of the main window for panels that host Stonebreak UI
 * documents (#288), which need GLFW key codes and whole code points rather than ImGui's view of
 * the keyboard. Installed before ImGui's GLFW backend, which chains to these callbacks, so ImGui
 * keeps receiving everything too. Listeners decide for themselves whether they have focus. Letter
 * keys arrive translated to the active keyboard layout ({@code LayoutKeys}, as the game host does).
 */
public final class ToolInputTap {

    /** Receives every key and character event of the main window. */
    public interface Listener {
        void key(int key, int action, int mods);

        void character(int codePoint);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private ToolInputTap() {
    }

    /** Must run before {@code ImGuiImplGlfw.init(window, true)}. */
    static void install(long window) {
        glfwSetKeyCallback(window, (w, key, scancode, action, mods) -> {
            if (LISTENERS.isEmpty()) {
                return;
            }
            // documents see letter keys of the active layout (C8): Ctrl+Z on a QWERTZ keyboard is
            // the key labelled Z, as in the game window; ImGui keeps its own physical-key view
            int layoutKey = com.stonebreak.ui.runtime.LayoutKeys.translate(key, scancode);
            for (Listener l : LISTENERS) {
                l.key(layoutKey, action, mods);
            }
        });
        glfwSetCharCallback(window, (w, codePoint) -> {
            for (Listener l : LISTENERS) {
                l.character(codePoint);
            }
        });
    }

    public static void add(Listener l) {
        LISTENERS.add(l);
    }

    public static void remove(Listener l) {
        LISTENERS.remove(l);
    }
}
