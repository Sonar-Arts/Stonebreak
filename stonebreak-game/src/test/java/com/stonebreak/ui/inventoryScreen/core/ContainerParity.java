package com.stonebreak.ui.inventoryScreen.core;

import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.input.PointerEvent;
import com.openmason.engine.ui.runtime.paint.UiDocumentView;
import com.stonebreak.items.Inventory;
import com.stonebreak.items.ItemStack;
import com.stonebreak.ui.inventoryScreen.handlers.ContainerSlotInput;
import com.stonebreak.ui.inventoryScreen.handlers.InventoryDragDropHandler;
import com.stonebreak.ui.inventoryScreen.handlers.PointerFrame;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Drives one interaction script through a container screen two ways (#300): as the legacy screen's
 * polled pointer frames at the legacy geometry, and as pointer events on its document (whose Lua
 * routes them to the {@code stonebreak:inventory.slot-*} and button actions), and renders both
 * states as one comparable line. Shared by the crafting table and inventory parity tests. Not a test class.
 */
public final class ContainerParity {

    public static final int LEFT = PointerEvent.PRIMARY;
    public static final int RIGHT = PointerEvent.SECONDARY;
    public static final int MIDDLE = PointerEvent.MIDDLE;
    public static final int SHIFT = 1;

    private ContainerParity() {
    }

    /** One step: a press at {@code at} ({@code shift}-held), optionally swept over more targets before release. */
    public record Step(String at, int button, boolean shift, List<String> sweep) {
        public static Step click(String at) {
            return new Step(at, LEFT, false, List.of());
        }

        public static Step shiftClick(String at) {
            return new Step(at, LEFT, true, List.of());
        }

        public static Step press(String at, int button) {
            return new Step(at, button, false, List.of());
        }

        public static Step rightSweep(String at, String... over) {
            return new Step(at, RIGHT, false, List.of(over));
        }
    }

    /** Legacy: the frames the screen's poll would see, at the centres of the legacy rects. */
    public static void legacy(ContainerSlotInput input, Map<String, float[]> rects, Step s, int w, int h) {
        float[] at = legacyPoint(rects, s.at());
        boolean right = s.button() == RIGHT;
        input.handlePointer(new PointerFrame(at[0], at[1], s.button() == LEFT, right, right, s.button() == MIDDLE,
            s.shift()), w, h);
        float[] end = at;
        for (String over : s.sweep()) {
            end = legacyPoint(rects, over);
            input.handlePointer(new PointerFrame(end[0], end[1], false, false, true, false, false), w, h);
        }
        input.handlePointer(PointerFrame.idle(end[0], end[1]), w, h);
    }

    private static float[] legacyPoint(Map<String, float[]> rects, String name) {
        return switch (name) {
            case "outside" -> new float[]{5, 5};
            case "panel" -> {
                float[] p = rects.get("panel");
                yield new float[]{p[0] + 1, p[1] + 1};
            }
            default -> {
                float[] r = rects.get(name);
                if (r == null) {
                    throw new IllegalArgumentException("no legacy rect " + name);
                }
                yield new float[]{r[0] + r[2] / 2f, r[1] + r[3] / 2f};
            }
        };
    }

    /** Document: a press, the sweep and a release through its router, then a settled frame. */
    public static void document(UiDocumentView view, Function<String, float[]> point, Step s, Runnable settle) {
        float[] at = point.apply(s.at());
        int mods = s.shift() ? SHIFT : 0;
        var router = view.input();
        router.pointerMove(at[0], at[1]);
        router.pointerDown(at[0], at[1], s.button(), mods);
        float[] end = at;
        for (String over : s.sweep()) {
            end = point.apply(over);
            router.pointerMove(end[0], end[1]);
        }
        router.pointerUp(end[0], end[1], s.button(), mods);
        settle.run();
    }

    /** Inventory, hotbar, crafting grid, output and the cursor stack, as one line. */
    public static String snapshot(Inventory inv, ItemStack[] grid, ItemStack output,
                                  InventoryDragDropHandler.DragState drag) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            b.append('m').append(i).append('=').append(stack(inv.getMainInventorySlot(i))).append(' ');
        }
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            b.append('h').append(i).append('=').append(stack(inv.getHotbarSlot(i))).append(' ');
        }
        b.append('|');
        for (int i = 0; i < grid.length; i++) {
            b.append(" c").append(i).append('=').append(stack(grid[i]));
        }
        b.append(" out=").append(stack(output));
        b.append(" | carried=").append(drag.isDragging() ? stack(drag.draggedItemStack) : "-");
        return b.toString();
    }

    /** The same line read off a document's slot elements: {@code element} maps mainN/hotN/craftN/output. */
    public static String shown(Function<String, UiElement> element, int gridSize, UiElement carried) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            b.append('m').append(i).append('=').append(shown(element.apply("main" + i))).append(' ');
        }
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            b.append('h').append(i).append('=').append(shown(element.apply("hot" + i))).append(' ');
        }
        b.append('|');
        for (int i = 0; i < gridSize; i++) {
            b.append(" c").append(i).append('=').append(shown(element.apply("craft" + i)));
        }
        b.append(" out=").append(shown(element.apply("output")));
        b.append(" | carried=").append(carried.computedStyle().collapsed() ? "-" : record(carried.prop("stack")));
        return b.toString();
    }

    /** The record an item slot instance's inner {@code ItemSlot} draws. */
    private static String shown(UiElement instance) {
        return record(instance.children().getFirst().prop("stack"));
    }

    private static String record(UiValue v) {
        if (!(v instanceof UiValue.Obj o) || !(o.get("count") instanceof UiValue.Num c) || c.value() <= 0) {
            return "-";
        }
        return (int) ((UiValue.Num) o.get("item")).value() + "x" + (int) c.value();
    }

    public static String stack(ItemStack s) {
        return s == null || s.isEmpty() ? "-" : s.getBlockTypeId() + "x" + s.getCount();
    }

    /** Items held by the inventory, the grid and the cursor (not the output: it is a preview). */
    public static int total(Inventory inv, ItemStack[] grid, InventoryDragDropHandler.DragState drag) {
        int n = 0;
        for (int i = 0; i < Inventory.MAIN_INVENTORY_SIZE; i++) {
            n += count(inv.getMainInventorySlot(i));
        }
        for (int i = 0; i < Inventory.HOTBAR_SIZE; i++) {
            n += count(inv.getHotbarSlot(i));
        }
        for (ItemStack s : grid) {
            n += count(s);
        }
        return n + (drag.isDragging() ? count(drag.draggedItemStack) : 0);
    }

    public static int count(ItemStack s) {
        return s == null || s.isEmpty() ? 0 : s.getCount();
    }
}
