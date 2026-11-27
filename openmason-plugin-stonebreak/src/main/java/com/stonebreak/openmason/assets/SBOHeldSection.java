package com.stonebreak.openmason.assets;

import com.openmason.main.systems.menus.dialogs.EditorWidgets;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import com.stonebreak.gameplay.SboGameplay;
import imgui.ImGui;
import imgui.type.ImBoolean;
import imgui.type.ImFloat;
import imgui.type.ImString;

/**
 * Held tab body for the SBO editor (#458): how the object sits in a character's hand, overriding the default grip of
 * its held kind (the game's {@code held/held_grips.json}: block, tool, item, model).
 *
 * <p>Edits {@link SboGameplay.HeldData}: the character socket ({@code grip_r}, {@code grip_l}, ...) and the grip's
 * position, rotation (XYZ Euler degrees) and uniform scale in that socket's frame. The object is drawn in its canonical
 * held frame first: a sprite centred, one unit across, its bottom-left-to-top-right diagonal along +Y and its front
 * facing +Z; a block a unit cube on the origin. Since #459 also the object's named POINTS in that canonical frame
 * ({@code flame}, {@code tip}, {@code head}, a second grip): where a torch's light, a rod's line or a staff's bolts
 * leave from. Pure UI - never touches disk.
 */
public class SBOHeldSection {

    private static final String[] SOCKET_PRESETS = {"grip_r", "grip_l", "back", "head"};

    private final ImBoolean enabled = new ImBoolean(false);
    private final ImString socket = new ImString(64);
    private final float[] position = new float[3];
    private final float[] rotation = new float[3];
    private final ImFloat scale = new ImFloat(1.0f);
    private final java.util.List<PointRow> points = new java.util.ArrayList<>();
    private final Runnable onDirty;

    /** One editable held point. */
    private static final class PointRow {
        final ImString name = new ImString(32);
        final float[] position = new float[3];
        final float[] rotation = new float[3];
    }

    /** The point names the game reads. */
    private static final String[] POINT_PRESETS = {"tip", "flame", "head", "grip_2"};

    public SBOHeldSection(Runnable onDirty) {
        this.onDirty = onDirty != null ? onDirty : () -> {};
    }

    public void setFromHeldData(SboGameplay.HeldData data) {
        enabled.set(data != null);
        socket.set(data != null ? data.socket() : "grip_r");
        set(position, data != null ? data.position() : SboGameplay.Vec3.ZERO);
        set(rotation, data != null ? data.rotation() : SboGameplay.Vec3.ZERO);
        scale.set(data != null ? data.scale() : 1.0f);
        points.clear();
        if (data != null) {
            data.points().forEach((name, point) -> {
                PointRow row = new PointRow();
                row.name.set(name);
                set(row.position, point.position());
                set(row.rotation, point.rotation());
                points.add(row);
            });
        }
    }

    private static void set(float[] out, SboGameplay.Vec3 v) {
        out[0] = v.x();
        out[1] = v.y();
        out[2] = v.z();
    }

    /** {@code null} when the object keeps its held kind's default grip. Call {@link #validate()} first. */
    public SboGameplay.HeldData toHeldData() {
        if (!enabled.get()) return null;
        java.util.LinkedHashMap<String, SboGameplay.HeldPoint> out = new java.util.LinkedHashMap<>();
        for (PointRow row : points) {
            out.put(row.name.get().strip(), new SboGameplay.HeldPoint(
                    new SboGameplay.Vec3(row.position[0], row.position[1], row.position[2]),
                    new SboGameplay.Vec3(row.rotation[0], row.rotation[1], row.rotation[2])));
        }
        return new SboGameplay.HeldData(socket.get(),
                new SboGameplay.Vec3(position[0], position[1], position[2]),
                new SboGameplay.Vec3(rotation[0], rotation[1], rotation[2]), scale.get(), out);
    }

    /** {@code null} when the form can be saved; otherwise the first blocking problem. */
    public String validate() {
        if (!enabled.get()) return null;
        if (socket.get().isBlank()) return "Held: a socket is required (e.g. grip_r)";
        if (!(scale.get() > 0f)) return "Held: scale must be greater than 0";
        for (float f : position) if (!Float.isFinite(f)) return "Held: position must be finite";
        for (float f : rotation) if (!Float.isFinite(f)) return "Held: rotation must be finite";
        java.util.Set<String> names = new java.util.HashSet<>();
        for (PointRow row : points) {
            String name = row.name.get().strip();
            if (name.isEmpty()) return "Held: every point needs a name";
            if (!names.add(name.toLowerCase(java.util.Locale.ROOT))) return "Held: duplicate point " + name;
            for (float f : row.position) if (!Float.isFinite(f)) return "Held: point " + name + " position must be finite";
            for (float f : row.rotation) if (!Float.isFinite(f)) return "Held: point " + name + " rotation must be finite";
        }
        return null;
    }

    public void render() {
        ImGui.textDisabled("How the object sits in a character's hand. Off = the default grip of its kind.");
        if (ImGui.checkbox("Override the held grip", enabled)) onDirty.run();
        if (!enabled.get()) return;

        ThemedWidgets.sectionLabel("Socket");
        ImGui.pushItemWidth(EditorWidgets.NAME_FIELD_WIDTH);
        if (ImGui.inputTextWithHint("##held_socket", "e.g. grip_r", socket)) onDirty.run();
        ImGui.popItemWidth();
        ImGui.sameLine();
        ImGui.pushItemWidth(EditorWidgets.LOOP_COMBO_WIDTH);
        if (ImGui.beginCombo("Socket", "Presets")) {
            for (String preset : SOCKET_PRESETS) {
                if (ImGui.selectable(preset, preset.equals(socket.get().trim()))) {
                    socket.set(preset);
                    onDirty.run();
                }
            }
            ImGui.endCombo();
        }
        ImGui.popItemWidth();
        ImGui.textDisabled("grip_r = main hand, grip_l = off hand (a bow).");

        ThemedWidgets.sectionLabel("Grip (socket space)");
        if (ImGui.inputFloat3("Position##held_pos", position, "%.3f")) onDirty.run();
        if (ImGui.inputFloat3("Rotation##held_rot", rotation, "%.1f")) onDirty.run();
        ImGui.pushItemWidth(EditorWidgets.NUMERIC_ID_WIDTH);
        if (ImGui.inputFloat("Scale##held_scale", scale, 0.05f, 0.1f, "%.2f")) {
            if (scale.get() < 0.01f) scale.set(0.01f);
            onDirty.run();
        }
        ImGui.popItemWidth();
        ImGui.textDisabled("Position in blocks, rotation in degrees (X, Y, Z order). The character faces -Z;");
        ImGui.textDisabled("at rest the grip sockets carry the model axes (right hand outside = +X).");

        ThemedWidgets.sectionLabel("Points (object space)");
        ImGui.textDisabled("Named points of the object in its held frame: flame (torch light), tip (rod line),");
        ImGui.textDisabled("head (staff bolts), grip_2 (second hand). A sprite without a tip gets its top voxel.");
        int remove = -1;
        for (int i = 0; i < points.size(); i++) {
            PointRow row = points.get(i);
            ImGui.pushID("held_point_" + i);
            ImGui.pushItemWidth(EditorWidgets.NAME_FIELD_WIDTH);
            if (ImGui.inputTextWithHint("##name", "name", row.name)) onDirty.run();
            ImGui.popItemWidth();
            ImGui.sameLine();
            if (ImGui.smallButton("Remove")) remove = i;
            if (ImGui.inputFloat3("Position##pos", row.position, "%.4f")) onDirty.run();
            if (ImGui.inputFloat3("Rotation##rot", row.rotation, "%.1f")) onDirty.run();
            ImGui.popID();
        }
        if (remove >= 0) {
            points.remove(remove);
            onDirty.run();
        }
        ImGui.pushItemWidth(EditorWidgets.LOOP_COMBO_WIDTH);
        if (ImGui.beginCombo("##add_point", "Add point")) {
            for (String preset : POINT_PRESETS) {
                if (ImGui.selectable(preset)) {
                    PointRow row = new PointRow();
                    row.name.set(preset);
                    points.add(row);
                    onDirty.run();
                }
            }
            ImGui.endCombo();
        }
        ImGui.popItemWidth();
    }
}
