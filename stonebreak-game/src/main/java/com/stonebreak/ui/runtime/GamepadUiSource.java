package com.stonebreak.ui.runtime;

import com.openmason.engine.ui.runtime.input.GamepadButtons;
import org.lwjgl.glfw.GLFWGamepadState;

import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_AXIS_LEFT_X;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_AXIS_LEFT_Y;
import static org.lwjgl.glfw.GLFW.GLFW_JOYSTICK_1;
import static org.lwjgl.glfw.GLFW.GLFW_JOYSTICK_LAST;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.glfwGetGamepadState;
import static org.lwjgl.glfw.GLFW.glfwJoystickIsGamepad;

/**
 * Polls GLFW's standard-mapping gamepads for UI documents (#288). Before this, the game read no
 * controller at all. Every connected gamepad counts (their buttons are OR-ed), and the left
 * stick acts as a D-pad with hysteresis (on past 0.6, off under 0.35) so it neither chatters
 * nor needs a second binding. Only button edges are reported; holding is the router's job
 * (controller repeat runs on its clock).
 */
final class GamepadUiSource {

    /** Receives one button edge. */
    interface Sink {
        void button(int button, boolean down);
    }

    private static final float STICK_ON = 0.6f;
    private static final float STICK_OFF = 0.35f;

    private final GLFWGamepadState state = GLFWGamepadState.create();
    private final boolean[] previous = new boolean[GamepadButtons.COUNT];
    private final boolean[] stick = new boolean[4]; // up, right, down, left

    void poll(Sink sink) {
        boolean[] now = new boolean[GamepadButtons.COUNT];
        float x = 0;
        float y = 0;
        for (int jid = GLFW_JOYSTICK_1; jid <= GLFW_JOYSTICK_LAST; jid++) {
            if (!glfwJoystickIsGamepad(jid) || !glfwGetGamepadState(jid, state)) {
                continue;
            }
            for (int b = 0; b < GamepadButtons.COUNT; b++) {
                now[b] |= state.buttons(b) == GLFW_PRESS;
            }
            float sx = state.axes(GLFW_GAMEPAD_AXIS_LEFT_X);
            float sy = state.axes(GLFW_GAMEPAD_AXIS_LEFT_Y);
            if (Math.abs(sx) > Math.abs(x)) {
                x = sx;
            }
            if (Math.abs(sy) > Math.abs(y)) {
                y = sy;
            }
        }
        stick[0] = hysteresis(stick[0], -y);
        stick[1] = hysteresis(stick[1], x);
        stick[2] = hysteresis(stick[2], y);
        stick[3] = hysteresis(stick[3], -x);
        now[GamepadButtons.DPAD_UP] |= stick[0];
        now[GamepadButtons.DPAD_RIGHT] |= stick[1];
        now[GamepadButtons.DPAD_DOWN] |= stick[2];
        now[GamepadButtons.DPAD_LEFT] |= stick[3];
        for (int b = 0; b < GamepadButtons.COUNT; b++) {
            if (now[b] != previous[b]) {
                previous[b] = now[b];
                sink.button(b, now[b]);
            }
        }
    }

    private static boolean hysteresis(boolean on, float value) {
        return on ? value > STICK_OFF : value > STICK_ON;
    }
}
