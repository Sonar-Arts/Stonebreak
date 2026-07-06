package com.openmason.main.systems.menus.dialogs;

import imgui.ImGui;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiSelectableFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiTableColumnFlags;
import imgui.flag.ImGuiTableFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reusable modal popup that lets the user pick an SBO object by {@code objectId}
 * from the {@link SBOObjectIndex}. The caller opens it via {@link #open(Consumer)}
 * and then calls {@link #render()} every frame.
 *
 * <p>UX: the search field is focused on open (type-to-filter immediately),
 * results are icon-labelled rows sorted prefix-matches-first, a Blocks/Items
 * segment narrows by type, a "Recent" strip of icon tiles fronts the list
 * ({@link IngredientMru}), Enter picks the top match and Escape cancels.
 * Every successful pick feeds the shared MRU.</p>
 */
public class SBOObjectPickerPopup {

    private static final String POPUP_ID = "Pick Ingredient##sbo_object_picker";
    private static final float RECENT_TILE = 30f;
    private static final float ICON_SIZE = 22f;

    /** 0 = all, 1 = blocks, 2 = items. Sticky across opens — a deliberate default. */
    private int typeFilter = 0;

    private final ImString filter = new ImString(128);
    private Consumer<String> onPick;
    private boolean openRequested;
    private boolean focusFilter;

    /** Open the picker. {@code onPick} receives the chosen objectId, or "" if cleared. */
    public void open(Consumer<String> onPick) {
        this.onPick = onPick;
        this.openRequested = true;
        this.focusFilter = true;
        this.filter.set("");
    }

    public void render() {
        if (openRequested) {
            ImGui.openPopup(POPUP_ID);
            openRequested = false;
        }

        ImGui.setNextWindowSize(560, 560);
        int flags = ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove;
        if (!ImGui.beginPopupModal(POPUP_ID, flags)) {
            return;
        }

        if (ImGui.isKeyPressed(ImGuiKey.Escape)) {
            ImGui.closeCurrentPopup();
            ImGui.endPopup();
            return;
        }

        renderSearchRow();
        renderRecentStrip();
        ImGui.dummy(0, 2);

        List<SBOObjectIndex.Entry> visible = visibleEntries();

        if (ImGui.isKeyPressed(ImGuiKey.Enter) || ImGui.isKeyPressed(ImGuiKey.KeypadEnter)) {
            if (!visible.isEmpty()) {
                pick(visible.get(0).objectId());
                ImGui.endPopup();
                return;
            }
        }

        renderResultsTable(visible);

        ImGui.dummy(0, 2);
        renderFooter(visible.size());
        ImGui.endPopup();
    }

    // ---- pieces ------------------------------------------------------------

    private static final String[] TYPE_LABELS = {"All", "Blocks", "Items"};

    private void renderSearchRow() {
        if (focusFilter) {
            ImGui.setKeyboardFocusHere();
            focusFilter = false;
        }

        // Reserve exactly what the segmented type filter needs so it never clips.
        imgui.ImGuiStyle style = ImGui.getStyle();
        float segmentW = 0f;
        for (String label : TYPE_LABELS) {
            segmentW += ImGui.calcTextSize(label).x + style.getFramePaddingX() * 2f + 2f;
        }
        segmentW += style.getItemSpacingX() * TYPE_LABELS.length;
        float inputW = Math.max(140f, ImGui.getContentRegionAvailX() - segmentW);

        ImGui.pushItemWidth(inputW);
        ImGui.inputTextWithHint("##picker_filter", "Search by name or objectId...", filter);
        ImGui.popItemWidth();

        // Segmented All | Blocks | Items pills: active gets the accent fill.
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 10f);
        for (int i = 0; i < TYPE_LABELS.length; i++) {
            ImGui.sameLine();
            boolean active = typeFilter == i;
            if (active) {
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button,
                        ImGui.getColorU32(imgui.flag.ImGuiCol.ButtonActive));
            } else {
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0, 0, 0, 0);
            }
            if (ImGui.button(TYPE_LABELS[i])) typeFilter = i;
            ImGui.popStyleColor();
        }
        ImGui.popStyleVar();
    }

    private void renderRecentStrip() {
        List<String> recents = IngredientMru.shared().list();
        if (recents.isEmpty()) return;

        ImGui.textDisabled("Recent");
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 4, 4);
        float avail = ImGui.getContentRegionAvailX();
        float used = 0;
        for (int i = 0; i < recents.size(); i++) {
            String id = recents.get(i);
            float tileW = RECENT_TILE + 8; // button + frame padding
            if (used + tileW > avail && used > 0) break; // one row only
            if (i > 0) ImGui.sameLine();
            used += tileW;

            ImGui.pushID("recent_" + i);
            int icon = SBOIngredientIcons.glIcon(id);
            boolean clicked;
            if (icon > 0) {
                clicked = ImGui.imageButton("##ric", icon, RECENT_TILE, RECENT_TILE);
            } else {
                clicked = ImGui.button(initial(id), RECENT_TILE + 8, RECENT_TILE + 8);
            }
            if (ImGui.isItemHovered()) ImGui.setTooltip(id);
            if (clicked) pick(id);
            ImGui.popID();
        }
        ImGui.popStyleVar();
    }

    private List<SBOObjectIndex.Entry> visibleEntries() {
        String needle = filter.get().trim().toLowerCase();
        List<SBOObjectIndex.Entry> prefix = new ArrayList<>();
        List<SBOObjectIndex.Entry> contains = new ArrayList<>();
        for (SBOObjectIndex.Entry e : SBOObjectIndex.listAll()) {
            if (typeFilter == 1 && !e.isBlock()) continue;
            if (typeFilter == 2 && !e.isItem()) continue;
            if (needle.isEmpty()) {
                contains.add(e);
                continue;
            }
            String id = e.objectId().toLowerCase();
            String name = e.displayName().toLowerCase();
            String localId = localName(id);
            if (localId.startsWith(needle) || name.startsWith(needle)) {
                prefix.add(e);
            } else if (id.contains(needle) || name.contains(needle)) {
                contains.add(e);
            }
        }
        prefix.addAll(contains);
        return prefix;
    }

    private void renderResultsTable(List<SBOObjectIndex.Entry> visible) {
        int tableFlags = ImGuiTableFlags.RowBg | ImGuiTableFlags.BordersOuter
                | ImGuiTableFlags.ScrollY;
        if (!ImGui.beginTable("##picker_table", 3, tableFlags, 0, 380)) {
            return;
        }
        ImGui.tableSetupColumn("Name", ImGuiTableColumnFlags.WidthStretch, 0.55f);
        ImGui.tableSetupColumn("Object ID", ImGuiTableColumnFlags.WidthStretch, 0.45f);
        ImGui.tableSetupColumn("Type", ImGuiTableColumnFlags.WidthFixed, 56);
        ImGui.tableSetupScrollFreeze(0, 1);
        ImGui.tableHeadersRow();

        for (int i = 0; i < visible.size(); i++) {
            SBOObjectIndex.Entry e = visible.get(i);
            ImGui.tableNextRow(0, ICON_SIZE + 6);

            ImGui.tableSetColumnIndex(0);
            int icon = SBOIngredientIcons.glIcon(e.objectId());
            if (icon > 0) {
                ImGui.image(icon, ICON_SIZE, ICON_SIZE);
            } else {
                ImGui.dummy(ICON_SIZE, ICON_SIZE);
            }
            ImGui.sameLine();
            // Vertically center the label against the taller icon.
            ImGui.setCursorPosY(ImGui.getCursorPosY() + (ICON_SIZE - ImGui.getTextLineHeight()) / 2f);
            if (ImGui.selectable(e.displayName() + "##row_" + i, false,
                    ImGuiSelectableFlags.SpanAllColumns)) {
                pick(e.objectId());
            }

            ImGui.tableSetColumnIndex(1);
            ImGui.textDisabled(e.objectId());

            ImGui.tableSetColumnIndex(2);
            ImGui.textDisabled(e.objectType());
        }
        ImGui.endTable();
    }

    private void renderFooter(int matchCount) {
        if (ImGui.button("Clear slot")) {
            if (onPick != null) onPick.accept("");
            ImGui.closeCurrentPopup();
        }
        ImGui.sameLine();
        if (ImGui.button("Cancel")) {
            ImGui.closeCurrentPopup();
        }
        ImGui.sameLine();
        ImGui.textDisabled("  " + matchCount + (matchCount == 1 ? " object" : " objects")
                + " - Enter picks the top match, Esc cancels");
    }

    private void pick(String objectId) {
        if (onPick != null) onPick.accept(objectId);
        IngredientMru.shared().touch(objectId);
        ImGui.closeCurrentPopup();
    }

    /** "stonebreak:oak_log" -> "oak_log" (lowercase input expected). */
    private static String localName(String objectId) {
        int colon = objectId.indexOf(':');
        return colon >= 0 ? objectId.substring(colon + 1) : objectId;
    }

    /** Uppercase first letter of the local name, for icon-less recent tiles. */
    private static String initial(String objectId) {
        String local = localName(objectId);
        return local.isEmpty() ? "?" : local.substring(0, 1).toUpperCase();
    }
}
