package com.stonebreak.ui.runtime.contracts;

import com.stonebreak.ui.inventoryScreen.handlers.ContainerSlotInput;
import com.stonebreak.ui.inventoryScreen.handlers.PointerFrame;

/**
 * Turns the slot actions of the {@code stonebreak:inventory} contract into the pointer frames
 * the open container screen's legacy rules already handle (#289): the frame is synthesized at
 * the centre of the addressed slot in that screen's own layout, so a document's slot click
 * picks up, places, stacks, swaps, splits, distributes, gathers and shift-transfers exactly as
 * the legacy screen does. No slot rule is reimplemented here.
 *
 * <p>Buttons: {@code 0} left, {@code 1} right, {@code 2} middle.
 */
public final class ContainerSlots {

    public static final int LEFT = 0;
    public static final int RIGHT = 1;
    public static final int MIDDLE = 2;

    private ContainerSlots() {
    }

    /**
     * A whole click: the press frame, then a frame with no buttons (which ends a right-drag
     * sweep and lets the screen settle, as the real release frame does).
     *
     * @return null, or why the click was refused
     */
    public static String click(ContainerSlotInput screen, String slot, int button, boolean shift, int width, int height) {
        String p = press(screen, slot, button, shift, width, height);
        if (p != null) {
            return p;
        }
        float[] at = screen.slotCentre(slot, width, height);
        screen.handlePointer(PointerFrame.idle(at[0], at[1]), width, height);
        return null;
    }

    /** The press frame only: the button stays held (a right-drag sweep continues with {@link #drag}). */
    public static String press(ContainerSlotInput screen, String slot, int button, boolean shift, int width, int height) {
        if (button < LEFT || button > MIDDLE) {
            return "button must be 0 (left), 1 (right) or 2 (middle)";
        }
        float[] at = screen.slotCentre(slot, width, height);
        if (at == null) {
            return "this screen has no slot '" + slot + "'";
        }
        screen.handlePointer(new PointerFrame(at[0], at[1], button == LEFT, button == RIGHT, button == RIGHT,
            button == MIDDLE, shift), width, height);
        return null;
    }

    /** The pointer, with {@code button} still held, is over {@code slot} (right-drag distribution). */
    public static String drag(ContainerSlotInput screen, String slot, int button, int width, int height) {
        float[] at = screen.slotCentre(slot, width, height);
        if (at == null) {
            return "this screen has no slot '" + slot + "'";
        }
        screen.handlePointer(new PointerFrame(at[0], at[1], false, false, button == RIGHT, false, false), width, height);
        return null;
    }

    /** All buttons up. */
    public static void release(ContainerSlotInput screen, int width, int height) {
        screen.handlePointer(PointerFrame.idle(ContainerSlotInput.OUTSIDE, ContainerSlotInput.OUTSIDE), width, height);
    }
}
