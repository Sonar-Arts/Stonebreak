package com.stonebreak.openmason.assets;

import com.openmason.api.asset.AssetEditorSection;
import com.openmason.api.asset.AssetSectionHost;
import com.openmason.engine.format.sbo.SBOFormat;
import com.openmason.main.systems.menus.dialogs.EditorWidgets;
import com.openmason.main.systems.themes.utils.ThemedWidgets;
import com.stonebreak.gameplay.MultipartData;
import com.stonebreak.gameplay.SboGameplay;
import imgui.ImGui;
import imgui.flag.ImGuiTableFlags;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The SBO editor's Neighbour Shapes tab: the block's {@code multipart} table. Each row draws one of
 * the SBO's own states when every side's condition holds, and may add collision boxes. Any block
 * can morph by its neighbours this way (a fence's arms, rounded leaves' edges) without a line of
 * game code: the parts are ordinary states, authored on the States tab.
 */
final class MultipartSection implements AssetEditorSection<SBOFormat.Document> {

    /** Per-side choices, in combo order. */
    private static final String[] CHOICES = {
            "Any", "Full cube", "Not full cube", "Same block", "Not same block", "Tag", "Not tag"};

    private static final class Row {
        String state;
        final int[] choice = new int[MultipartData.Side.values().length];
        final ImString[] tag = new ImString[MultipartData.Side.values().length];
        final List<float[]> boxes = new ArrayList<>(); // 6 floats: min xyz, max xyz

        Row(String state) {
            this.state = state;
            for (int i = 0; i < tag.length; i++) tag[i] = new ImString(96);
        }
    }

    private final AssetSectionHost host;
    private final List<Row> rows = new ArrayList<>();
    private List<String> stateNames = List.of();
    private MultipartData lastGood;

    MultipartSection(AssetSectionHost host) {
        this.host = host;
    }

    @Override
    public String title() {
        return "Neighbour Shapes";
    }

    @Override
    public void load(SBOFormat.Document doc) {
        stateNames = doc.states() == null ? List.of()
                : doc.states().stream().map(SBOFormat.StateEntry::name).toList();
        rows.clear();
        lastGood = SboGameplay.of(doc).multipart();
        if (lastGood == null) return;
        for (MultipartData.Part part : lastGood.parts()) {
            Row row = new Row(part.state());
            part.when().forEach((side, c) -> {
                int i = side.ordinal();
                row.choice[i] = switch (c.test()) {
                    case FULL_CUBE -> c.negate() ? 2 : 1;
                    case SAME -> c.negate() ? 4 : 3;
                    case TAG -> c.negate() ? 6 : 5;
                };
                if (c.tag() != null) row.tag[i].set(c.tag());
            });
            for (MultipartData.Box b : part.boxes()) {
                row.boxes.add(new float[]{b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()});
            }
            rows.add(row);
        }
    }

    @Override
    public void render() {
        ThemedWidgets.sectionLabel("Parts");
        ImGui.textWrapped("Each part draws one of this SBO's states when every side's condition holds. "
                + "A side left at Any matches anything. Author the part meshes on the States tab.");
        if (rows.isEmpty()) {
            ImGui.textDisabled("No parts: the block always draws its default model.");
        }
        int flags = ImGuiTableFlags.Borders | ImGuiTableFlags.RowBg | ImGuiTableFlags.SizingFixedFit
                | ImGuiTableFlags.ScrollX;
        if (!rows.isEmpty() && ImGui.beginTable("##multipart", 9, flags)) {
            ImGui.tableSetupColumn("State");
            for (MultipartData.Side side : MultipartData.Side.values()) {
                ImGui.tableSetupColumn(side.key());
            }
            ImGui.tableSetupColumn("Boxes");
            ImGui.tableSetupColumn("");
            ImGui.tableHeadersRow();
            for (int r = 0; r < rows.size(); r++) {
                if (renderRow(r, rows.get(r))) {
                    rows.remove(r);
                    host.markDirty();
                    break;
                }
            }
            ImGui.endTable();
        }
        if (ImGui.button("Add part")) {
            rows.add(new Row(stateNames.isEmpty() ? "" : stateNames.getFirst()));
            host.markDirty();
        }
        String problem = validate();
        if (problem != null) {
            ImGui.textColored(1f, 0.45f, 0.35f, 1f, problem);
        } else if (!rows.isEmpty()) {
            ImGui.textDisabled(atomCount() + " neighbour test(s): " + (1 << atomCount()) + " shape(s).");
        }
    }

    /** One table row; true when its remove button was pressed. */
    private boolean renderRow(int r, Row row) {
        ImGui.tableNextRow();
        ImGui.tableNextColumn();
        ImGui.pushItemWidth(EditorWidgets.NAME_FIELD_WIDTH);
        if (ImGui.beginCombo("##mp_state_" + r, row.state)) {
            for (String name : stateNames) {
                if (ImGui.selectable(name, name.equals(row.state))) {
                    row.state = name;
                    host.markDirty();
                }
            }
            ImGui.endCombo();
        }
        ImGui.popItemWidth();
        for (int s = 0; s < row.choice.length; s++) {
            ImGui.tableNextColumn();
            ImGui.pushItemWidth(EditorWidgets.LOOP_COMBO_WIDTH);
            if (ImGui.beginCombo("##mp_side_" + r + "_" + s, CHOICES[row.choice[s]])) {
                for (int c = 0; c < CHOICES.length; c++) {
                    if (ImGui.selectable(CHOICES[c], c == row.choice[s])) {
                        row.choice[s] = c;
                        host.markDirty();
                    }
                }
                ImGui.endCombo();
            }
            if (row.choice[s] >= 5
                    && ImGui.inputTextWithHint("##mp_tag_" + r + "_" + s, "tag id", row.tag[s])) {
                host.markDirty();
            }
            ImGui.popItemWidth();
        }
        ImGui.tableNextColumn();
        renderBoxes(r, row);
        ImGui.tableNextColumn();
        return ImGui.smallButton("x##mp_rm_" + r);
    }

    private void renderBoxes(int r, Row row) {
        for (int b = 0; b < row.boxes.size(); b++) {
            float[] box = row.boxes.get(b);
            float[] min = {box[0], box[1], box[2]};
            float[] max = {box[3], box[4], box[5]};
            ImGui.pushItemWidth(EditorWidgets.NAME_FIELD_WIDTH);
            boolean changed = ImGui.inputFloat3("min##mp_bmin_" + r + "_" + b, min);
            changed |= ImGui.inputFloat3("max##mp_bmax_" + r + "_" + b, max);
            ImGui.popItemWidth();
            if (changed) {
                row.boxes.set(b, new float[]{min[0], min[1], min[2], max[0], max[1], max[2]});
                host.markDirty();
            }
            ImGui.sameLine();
            if (ImGui.smallButton("x##mp_brm_" + r + "_" + b)) {
                row.boxes.remove(b);
                host.markDirty();
                return;
            }
        }
        if (ImGui.smallButton("+ box##mp_badd_" + r)) {
            row.boxes.add(new float[]{0f, 0f, 0f, 1f, 1f, 1f});
            host.markDirty();
        }
    }

    @Override
    public String validate() {
        Set<String> known = new HashSet<>(stateNames);
        for (int r = 0; r < rows.size(); r++) {
            Row row = rows.get(r);
            if (row.state == null || row.state.isBlank()) return "Part " + (r + 1) + " has no state.";
            if (!known.contains(row.state)) {
                return "Part " + (r + 1) + " names state '" + row.state + "', which this SBO lacks.";
            }
        }
        try {
            toData();
        } catch (IllegalArgumentException ex) {
            return ex.getMessage();
        }
        if (atomCount() > MultipartData.MAX_ATOMS) {
            return atomCount() + " different neighbour tests; at most " + MultipartData.MAX_ATOMS + " are allowed.";
        }
        return null;
    }

    @Override
    public SBOFormat.Document write(SBOFormat.Document doc) {
        try {
            if (validate() == null) lastGood = rows.isEmpty() ? null : toData();
        } catch (IllegalArgumentException ignored) {
            // keep the last good table (validate() reports the problem)
        }
        return SboGameplay.of(doc).withMultipart(lastGood).applyTo(doc);
    }

    private MultipartData toData() {
        List<MultipartData.Part> parts = new ArrayList<>();
        for (Row row : rows) {
            Map<MultipartData.Side, MultipartData.Condition> when = new EnumMap<>(MultipartData.Side.class);
            for (MultipartData.Side side : MultipartData.Side.values()) {
                MultipartData.Condition c = condition(row.choice[side.ordinal()], row.tag[side.ordinal()].get());
                if (c != null) when.put(side, c);
            }
            List<MultipartData.Box> boxes = new ArrayList<>();
            for (float[] b : row.boxes) {
                boxes.add(new MultipartData.Box(b[0], b[1], b[2], b[3], b[4], b[5]));
            }
            parts.add(new MultipartData.Part(row.state, when, boxes));
        }
        return new MultipartData(parts);
    }

    private static MultipartData.Condition condition(int choice, String tag) {
        return switch (choice) {
            case 1 -> new MultipartData.Condition(MultipartData.Test.FULL_CUBE, null, false);
            case 2 -> new MultipartData.Condition(MultipartData.Test.FULL_CUBE, null, true);
            case 3 -> new MultipartData.Condition(MultipartData.Test.SAME, null, false);
            case 4 -> new MultipartData.Condition(MultipartData.Test.SAME, null, true);
            case 5 -> new MultipartData.Condition(MultipartData.Test.TAG, tag, false);
            case 6 -> new MultipartData.Condition(MultipartData.Test.TAG, tag, true);
            default -> null;
        };
    }

    /** Distinct (side, test, tag) pairs the rows use: each is one bit of a cell's shape key. */
    private int atomCount() {
        Set<String> atoms = new HashSet<>();
        for (Row row : rows) {
            for (int s = 0; s < row.choice.length; s++) {
                int c = row.choice[s];
                if (c == 0) continue;
                int test = (c + 1) / 2; // 1-2 full cube, 3-4 same, 5-6 tag
                atoms.add(s + ":" + test + ":" + (test == 3 ? row.tag[s].get().strip() : ""));
            }
        }
        return atoms.size();
    }
}
