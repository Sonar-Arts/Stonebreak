package com.openmason.main.systems.mcp;

import com.openmason.main.systems.MainImGuiInterface;
import com.openmason.main.systems.io.AssetWriteService;
import com.openmason.main.systems.io.AssetWriteService.WriteOutcome;
import com.openmason.main.systems.io.AssetWriteService.WriteRequest;
import com.openmason.main.systems.io.WriteKind;
import com.openmason.main.systems.io.WriteTarget;
import com.openmason.main.systems.services.ModelOperationService;
import com.openmason.main.systems.stateHandling.ModelState;
import com.openmason.main.systems.threading.MainThreadExecutor;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The model editor's file lifecycle for agents: save the current model as
 * {@code .omo} (through the write sandbox / Save Sheet), open a {@code .omo},
 * start a blank one. Replacing unsaved work is approval-gated exactly like
 * {@code asset_open}.
 */
public final class ModelFileService {

    private static final long MAIN_TIMEOUT_S = 30;

    private final MainImGuiInterface mainInterface;
    private final AssetWriteService writes;

    public ModelFileService(MainImGuiInterface mainInterface, AssetWriteService writes) {
        this.mainInterface = mainInterface;
        this.writes = writes;
    }

    /** Snapshot of the editor state read on the main thread. */
    private record Snapshot(boolean loaded, boolean canSave, boolean dirty, boolean hasFile,
                            String filePath, String name) {
    }

    // ---------------------------------------------------------------- save

    public WriteOutcome save(String filePath, boolean prompt, boolean overwrite) {
        return save(filePath, prompt, overwrite, List.of(), false);
    }

    /**
     * @param extraDetails         extra lines for the Save Sheet (e.g. why an export needs this)
     * @param requiresPriorOmoSave marks the sheet as the .omo step of an export chain
     */
    public WriteOutcome save(String filePath, boolean prompt, boolean overwrite,
                             List<String> extraDetails, boolean requiresPriorOmoSave) {
        Snapshot s = snapshot();
        if (!s.loaded()) {
            throw new IllegalArgumentException("no_model: nothing is loaded in the model editor");
        }
        if (!s.canSave()) {
            throw new IllegalArgumentException("read_only_model: the loaded model is a read-only "
                    + "browser reference — create or open an .omo first");
        }
        ModelOperationService ops = requireOps();
        AssetWriteService.Writer writer = p -> ops.saveModelToPath(p.toString());
        if (filePath == null && !prompt && s.hasFile()) {
            return writes.saveInPlace(WriteKind.OMO, s.filePath(), writer);
        }
        String suggested = s.name() == null || s.name().isBlank() ? "model" : s.name();
        List<String> details = new ArrayList<>();
        details.add("Model: " + suggested);
        details.addAll(extraDetails);
        return writes.save(WriteRequest.of(WriteKind.OMO, filePath, prompt, overwrite, suggested)
                .withDetails(details).withPriorOmoSave(requiresPriorOmoSave), writer);
    }

    // ---------------------------------------------------------------- open

    public Map<String, Object> open(String filePath, int timeoutSeconds) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("file_path is required");
        }
        // Same grammar as writes (absolute / project: / game: / bare name) and
        // the same containment rule: agents read models from the roots only.
        WriteTarget t = writes.sandbox().resolve(WriteKind.OMO, filePath);
        if (!Files.isRegularFile(t.path())) {
            throw new IllegalArgumentException("no_such_file: " + t.path());
        }
        Snapshot s = snapshot();
        if (s.loaded() && s.dirty()) {
            Map<String, Object> declined = confirmReplace("model_open",
                    "An agent asks to open '" + t.fileName() + "' into the editor",
                    List.of("File: " + t.path(), "The current model has unsaved changes."),
                    timeoutSeconds);
            if (declined != null) {
                return declined;
            }
        }
        ModelOperationService ops = requireOps();
        String path = t.path().toString();
        return onMain(() -> {
            ops.loadOMOModel(path);
            ModelState st = mainInterface.getModelState();
            boolean ok = st != null && st.isModelLoaded() && path.equals(st.getCurrentOMOFilePath());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("opened", ok);
            out.put("path", path);
            if (ok) {
                out.put("name", st.getCurrentModelPath());
            } else {
                out.put("reason", "load_failed");
                out.put("message", "the model did not load — see the Open Mason log");
            }
            return out;
        });
    }

    // ----------------------------------------------------------------- new

    public Map<String, Object> newModel(int timeoutSeconds) {
        Snapshot s = snapshot();
        if (s.loaded() && s.dirty()) {
            Map<String, Object> declined = confirmReplace("model_new",
                    "An agent asks to start a new blank model",
                    List.of("The current model has unsaved changes."), timeoutSeconds);
            if (declined != null) {
                return declined;
            }
        }
        ModelOperationService ops = requireOps();
        return onMain(() -> {
            ops.newModel();
            ModelState st = mainInterface.getModelState();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("opened", st != null && st.isModelLoaded());
            out.put("name", st != null ? st.getCurrentModelPath() : null);
            return out;
        });
    }

    // ------------------------------------------------------------- helpers

    /** Null when approved; otherwise the structured decline to return. */
    private Map<String, Object> confirmReplace(String action, String title, List<String> details,
                                               int timeoutSeconds) {
        return McpApprovals.confirm(mainInterface != null ? mainInterface.getApprovalGate() : null,
                action, title, details, timeoutSeconds);
    }

    private Snapshot snapshot() {
        return onMain(() -> {
            ModelState st = mainInterface != null ? mainInterface.getModelState() : null;
            if (st == null) {
                throw new IllegalStateException("Model editor unavailable — is the UI running?");
            }
            return new Snapshot(st.isModelLoaded(), st.canSaveModel(), st.hasUnsavedChanges(),
                    st.hasOMOFile(), st.getCurrentOMOFilePath(), st.getCurrentModelPath());
        });
    }

    private ModelOperationService requireOps() {
        ModelOperationService ops = mainInterface != null ? mainInterface.getModelOperations() : null;
        if (ops == null) {
            throw new IllegalStateException("Model editor unavailable — is the UI running?");
        }
        return ops;
    }

    private static <T> T onMain(java.util.concurrent.Callable<T> task) {
        try {
            return MainThreadExecutor.submit(task).get(MAIN_TIMEOUT_S, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Operation timed out on main thread", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        }
    }
}
