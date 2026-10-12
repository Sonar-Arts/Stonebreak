package com.openmason.main.systems.layout;

import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiPopupFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real tab strip on a headless ImGui context (no renderer, synthetic mouse events): a right
 * click on a tab opens its menu but never switches to it; a left click does; clicking a popped-out
 * tab asks its window to come forward.
 */
class WorkspaceTabRightClickTest {

    private final WorkspaceState state = new WorkspaceState();
    private final WorkspaceTabStrip strip = new WorkspaceTabStrip();
    private boolean popupOpen;

    @BeforeEach
    void context() {
        ImGui.createContext();
        ImGuiIO io = ImGui.getIO();
        io.setIniFilename(null);
        io.addConfigFlags(ImGuiConfigFlags.DockingEnable);
        io.setDisplaySize(800, 600);
        io.setDeltaTime(1f / 60f);
        io.getFonts().addFontDefault();
        io.getFonts().build();
    }

    @AfterEach
    void destroy() {
        ImGui.destroyContext();
    }

    private void frame() {
        ImGui.newFrame();
        ImGui.setNextWindowPos(0, 0, ImGuiCond.Always);
        ImGui.setNextWindowSize(800, 600, ImGuiCond.Always);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0);
        ImGui.begin("host", ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoSavedSettings);
        ImGui.popStyleVar();
        strip.render(state);
        popupOpen = ImGui.isPopupOpen("", ImGuiPopupFlags.AnyPopupId);
        ImGui.end();
        ImGui.render();
    }

    /** Centre of the UI tab: the strip lays Scene then UI with 14 px frame padding and 2 px between. */
    private float[] uiTabCentre() {
        ImGui.newFrame();
        float scene = ImGui.calcTextSize(Workspace.MODELING.label()).x + 28f;
        float ui = ImGui.calcTextSize(Workspace.UI.label()).x + 28f;
        float h = ImGui.getFontSize() + 10f;
        ImGui.endFrame();
        return new float[]{scene + 2f + ui / 2f, h / 2f};
    }

    private void click(int button, float[] at) {
        ImGuiIO io = ImGui.getIO();
        io.addMousePosEvent(at[0], at[1]);
        frame();
        io.addMouseButtonEvent(button, true);
        frame();
        io.addMouseButtonEvent(button, false);
        frame();
        frame();
        frame();
    }

    @Test
    void rightClickOpensTheMenuWithoutSwitchingTabs() {
        float[] ui = uiTabCentre();
        for (int i = 0; i < 3; i++) {
            frame();
        }
        assertEquals(Workspace.MODELING, state.current());

        click(ImGuiMouseButton.Right, ui);
        assertTrue(popupOpen, "the tab's context menu opened");
        assertEquals(Workspace.MODELING, state.current(), "a right click never switches the workspace");

        click(ImGuiMouseButton.Left, new float[]{400f, 400f}); // dismiss the menu by clicking away
        assertFalse(popupOpen);
        assertEquals(Workspace.MODELING, state.current());

        click(ImGuiMouseButton.Left, ui);
        assertEquals(Workspace.UI, state.current(), "control: a left click does switch");
    }

    @Test
    void clickingAPoppedOutTabRaisesItsWindowWithoutDockingIt() {
        state.detach(Workspace.UI);
        assertTrue(state.takeFocusRequest(Workspace.UI)); // the pop-out's own request, consumed by its window
        ImGui.newFrame();
        float scene = ImGui.calcTextSize(Workspace.MODELING.label()).x + 28f;
        float w = ImGui.calcTextSize(Workspace.UI.label() + WorkspaceTabStrip.ICON_GAP).x + 28f;
        float h = ImGui.getFontSize() + 10f;
        ImGui.endFrame();
        for (int i = 0; i < 3; i++) {
            frame();
        }

        click(ImGuiMouseButton.Left, new float[]{scene + 2f + w / 2f, h / 2f}); // the popped-out tab, after Scene
        assertTrue(state.takeFocusRequest(Workspace.UI), "the window is asked to come forward");
        assertTrue(state.isDetached(Workspace.UI), "it stays in its own window");
        assertEquals(Workspace.MODELING, state.current());
    }
}
