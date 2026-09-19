package com.openmason.main.systems.menus.dialogs;

import com.openmason.main.systems.io.WriteKind;
import com.openmason.main.systems.io.WriteRoot;
import com.openmason.main.systems.io.WriteSandbox;
import com.openmason.main.systems.io.WriteTarget;
import com.openmason.main.systems.mcp.approval.PromptGate;
import com.openmason.main.systems.mcp.approval.SaveSheetGate;
import com.openmason.main.systems.mcp.approval.SaveSheetRequest;
import com.openmason.main.systems.mcp.approval.SaveSheetResult;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The in-app "Save Sheet": an agent (MCP client or the assistant) wants to
 * write a file and the human picks where — root, folder, name — without an OS
 * file chooser. Modal, rendered every frame at app level like
 * {@link ApprovalDialog}; answers the {@link SaveSheetGate}.
 *
 * <p>Only sandboxed targets can be chosen: every candidate goes through
 * {@link WriteSandbox#check} before the gate is resolved. "Choose different…"
 * still hands the human the native dialog (never the agent), and the picked
 * path is checked the same way.
 */
public final class SaveSheetDialog {

    private static final Logger logger = LoggerFactory.getLogger(SaveSheetDialog.class);
    private static final String POPUP_ID = "Save Sheet##saveSheetDialog";
    private static final int MAX_LISTED = 200;

    /** Native fallback: show the OS dialog for a kind and report the chosen path. */
    @FunctionalInterface
    public interface NativePicker {
        void pick(WriteKind kind, String suggestedFileName, String preferredDirectory,
                  Consumer<String> onPicked);
    }

    private final SaveSheetGate gate;
    private final WriteSandbox sandbox;
    private NativePicker nativePicker;

    // Per-request state
    private PromptGate.Pending<SaveSheetRequest, SaveSheetResult> shownFor;
    private WriteRoot selectedRoot;
    private final ImString folder = new ImString(512);
    private final ImString name = new ImString(256);
    private final ImString newFolder = new ImString(128);
    private String error = "";
    private String listingKey = null;
    private List<String> listing = List.of();
    private List<String> subfolders = List.of();

    public SaveSheetDialog(SaveSheetGate gate, WriteSandbox sandbox) {
        this.gate = gate;
        this.sandbox = sandbox;
    }

    public void setNativePicker(NativePicker picker) {
        this.nativePicker = picker;
    }

    public void render() {
        var pending = gate.pending();
        if (pending == null) {
            shownFor = null;
            return;
        }
        if (pending != shownFor) {
            initFrom(pending.request());
            shownFor = pending;
        }
        if (!ImGui.isPopupOpen(POPUP_ID)) {
            ImGui.openPopup(POPUP_ID);
        }
        ImGui.setNextWindowSize(560, 0, ImGuiCond.Appearing);
        if (!ImGui.beginPopupModal(POPUP_ID,
                ImGuiWindowFlags.AlwaysAutoResize | ImGuiWindowFlags.NoSavedSettings)) {
            return;
        }
        SaveSheetRequest req = pending.request();
        WriteKind kind = req.kind();

        ImGui.textWrapped(req.title());
        for (String line : req.detailLines()) {
            ImGui.bulletText(line);
        }
        if (req.requiresPriorOmoSave()) {
            ImGui.textColored(0.6f, 0.8f, 1.0f, 1.0f,
                    "The model is unsaved; its .omo will be written first, then this export.");
        }
        ImGui.spacing();

        // ---- Where -------------------------------------------------------
        Map<WriteRoot, Path> present = sandbox.roots().present();
        if (present.isEmpty()) {
            ImGui.textColored(1f, 0.4f, 0.4f, 1f, "No writable root is available in this session.");
        }
        ImGui.text("Where");
        ImGui.sameLine(90);
        boolean first = true;
        for (Map.Entry<WriteRoot, Path> e : present.entrySet()) {
            if (!first) {
                ImGui.sameLine();
            }
            first = false;
            String label = switch (e.getKey()) {
                case PROJECT -> "Project";
                case GAME_RESOURCES -> "Game resources";
                case EXPORTS -> "Exports";
            };
            if (ImGui.radioButton(label + "##root_" + e.getKey().name(), selectedRoot == e.getKey())
                    && selectedRoot != e.getKey()) {
                selectedRoot = e.getKey();
                folder.set(defaultFolderFor(kind, selectedRoot, req));
                error = "";
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip(e.getValue().toString());
            }
        }
        if (selectedRoot == null && !present.isEmpty()) {
            selectedRoot = present.keySet().iterator().next();
            folder.set(defaultFolderFor(kind, selectedRoot, req));
        }

        // ---- Folder ------------------------------------------------------
        ImGui.text("Folder");
        ImGui.sameLine(90);
        ImGui.pushItemWidth(300);
        ImGui.inputText("##sheetFolder", folder);
        ImGui.popItemWidth();
        ImGui.sameLine();
        refreshListing(kind);
        ImGui.pushItemWidth(120);
        if (ImGui.beginCombo("##sheetSubfolders", "Browse")) {
            if (ImGui.selectable("(root)", folder.get().isEmpty())) {
                folder.set("");
            }
            for (String sub : subfolders) {
                if (ImGui.selectable(sub, sub.equals(folder.get()))) {
                    folder.set(sub);
                }
            }
            ImGui.endCombo();
        }
        ImGui.popItemWidth();
        ImGui.text("");
        ImGui.sameLine(90);
        ImGui.pushItemWidth(200);
        ImGui.inputTextWithHint("##sheetNewFolder", "new sub-folder", newFolder);
        ImGui.popItemWidth();
        ImGui.sameLine();
        if (ImGui.button("Add folder") && !newFolder.get().isBlank()) {
            try {
                String plain = WriteSandbox.requirePlainFileName(newFolder.get());
                folder.set(folder.get().isEmpty() ? plain : folder.get() + "/" + plain);
                newFolder.set("");
                error = "";
            } catch (IllegalArgumentException ex) {
                error = ex.getMessage();
            }
        }

        // ---- Name --------------------------------------------------------
        ImGui.text("Name");
        ImGui.sameLine(90);
        ImGui.pushItemWidth(300);
        ImGui.inputText("##sheetName", name);
        ImGui.popItemWidth();
        ImGui.sameLine();
        ImGui.textDisabled(kind.extension());

        // ---- Existing files ---------------------------------------------
        ImGui.textDisabled("Existing " + kind.extension() + " files here");
        if (ImGui.beginChild("##sheetListing", 540, 110, true)) {
            if (listing.isEmpty()) {
                ImGui.textDisabled("(none)");
            }
            String current = candidateFileName(kind);
            for (String f : listing) {
                boolean same = f.equalsIgnoreCase(current);
                if (ImGui.selectable(f + (same ? "   (will overwrite)" : ""), same)) {
                    name.set(stripExtension(f, kind));
                }
            }
        }
        ImGui.endChild();

        Path candidate = candidatePath(kind);
        if (candidate != null && Files.exists(candidate)) {
            ImGui.textColored(1.0f, 0.65f, 0.2f, 1.0f, "Will overwrite " + candidate.getFileName());
        }
        if (!error.isEmpty()) {
            ImGui.textColored(1f, 0.4f, 0.4f, 1f, error);
        }
        ImGui.textDisabled("Times out in " + gate.secondsRemaining() + "s");
        ImGui.separator();

        // ---- Buttons -----------------------------------------------------
        if (ImGui.button("Save")) {
            confirm(kind);
        }
        if (nativePicker != null) {
            ImGui.sameLine();
            if (ImGui.button("Choose different...")) {
                Path dir = currentDir();
                String suggested = candidateFileName(kind);
                nativePicker.pick(kind, suggested == null ? kind.fallbackName() + kind.extension() : suggested,
                        dir == null ? null : dir.toString(), this::acceptNativePath);
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Open the OS file dialog. The chosen path must still be inside a writable root.");
            }
        }
        ImGui.sameLine();
        if (ImGui.button("Cancel")) {
            gate.resolve(SaveSheetResult.DECLINED);
            ImGui.closeCurrentPopup();
        }
        ImGui.endPopup();
    }

    // ------------------------------------------------------------- helpers

    private void initFrom(SaveSheetRequest req) {
        error = "";
        listingKey = null;
        newFolder.set("");
        Map<WriteRoot, Path> present = sandbox.roots().present();
        WriteRoot wanted = req.suggestedRoot() != null ? req.suggestedRoot() : req.kind().defaultRoot();
        selectedRoot = present.containsKey(wanted) ? wanted
                : present.isEmpty() ? null : present.keySet().iterator().next();
        folder.set(selectedRoot == null ? "" : defaultFolderFor(req.kind(), selectedRoot, req));
        String suggested = req.suggestedName().isBlank() ? req.kind().fallbackName() : req.suggestedName();
        name.set(stripExtension(suggested, req.kind()));
    }

    private static String defaultFolderFor(WriteKind kind, WriteRoot root, SaveSheetRequest req) {
        if (req.suggestedRoot() != null ? root == req.suggestedRoot() : root == kind.defaultRoot()) {
            return req.suggestedRoot() != null ? req.suggestedSubdir() : kind.defaultSubdir();
        }
        return root == kind.defaultRoot() ? kind.defaultSubdir() : "";
    }

    private Path currentDir() {
        if (selectedRoot == null) {
            return null;
        }
        Path root = sandbox.roots().path(selectedRoot);
        if (root == null) {
            return null;
        }
        String f = folder.get().trim();
        return f.isEmpty() ? root : root.resolve(f);
    }

    private String candidateFileName(WriteKind kind) {
        String n = name.get().trim();
        if (n.isEmpty()) {
            return null;
        }
        return kind.hasExtension(n) ? n : n + kind.extension();
    }

    private Path candidatePath(WriteKind kind) {
        Path dir = currentDir();
        String file = candidateFileName(kind);
        return dir == null || file == null ? null : dir.resolve(file);
    }

    private void confirm(WriteKind kind) {
        try {
            Path dir = currentDir();
            if (dir == null) {
                error = "pick a root first";
                return;
            }
            String plain = WriteSandbox.requirePlainFileName(name.get());
            WriteTarget target = sandbox.check(kind, dir.resolve(kind.ensureExtension(plain)));
            gate.resolve(SaveSheetResult.saved(target.path()));
            ImGui.closeCurrentPopup();
        } catch (IllegalArgumentException ex) {
            error = ex.getMessage();
        }
    }

    private void acceptNativePath(String picked) {
        if (picked == null || picked.isBlank()) {
            return;
        }
        var pending = gate.pending();
        if (pending == null) {
            return;
        }
        try {
            WriteTarget target = sandbox.check(pending.request().kind(), Path.of(picked));
            gate.resolve(SaveSheetResult.saved(target.path()));
        } catch (IllegalArgumentException ex) {
            error = "That location is outside the writable roots: " + ex.getMessage();
            logger.info("Native pick rejected by sandbox: {}", picked);
        }
    }

    private void refreshListing(WriteKind kind) {
        Path dir = currentDir();
        String key = (dir == null ? "" : dir.toString()) + "|" + kind.id();
        if (key.equals(listingKey)) {
            return;
        }
        listingKey = key;
        listing = list(dir, p -> Files.isRegularFile(p) && kind.hasExtension(p.getFileName().toString()));
        Path root = selectedRoot == null ? null : sandbox.roots().path(selectedRoot);
        subfolders = list(root, Files::isDirectory);
        if (!folder.get().isEmpty() && dir != null && Files.isDirectory(dir)) {
            List<String> nested = new ArrayList<>(subfolders);
            for (String child : list(dir, Files::isDirectory)) {
                nested.add(folder.get() + "/" + child);
            }
            subfolders = nested;
        }
    }

    private static List<String> list(Path dir, java.util.function.Predicate<Path> filter) {
        List<String> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(filter)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> !n.startsWith("."))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .limit(MAX_LISTED)
                    .forEach(out::add);
        } catch (IOException ignored) {
            // unreadable folder — show nothing
        }
        return out;
    }

    private static String stripExtension(String fileName, WriteKind kind) {
        return kind.hasExtension(fileName)
                ? fileName.substring(0, fileName.length() - kind.extension().length())
                : fileName;
    }
}
