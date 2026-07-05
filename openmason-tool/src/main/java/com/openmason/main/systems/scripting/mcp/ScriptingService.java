package com.openmason.main.systems.scripting.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.main.systems.MainImGuiInterface;
import com.openmason.main.systems.ViewportController;
import com.openmason.main.systems.scripting.ScriptExecutor;
import com.openmason.main.systems.scripting.ScriptExecutor.Language;
import com.openmason.main.systems.scripting.ScriptExecutor.RunOptions;
import com.openmason.main.systems.scripting.ScriptExecutor.ScriptError;
import com.openmason.main.systems.scripting.ScriptExecutor.ScriptResult;
import com.openmason.main.systems.scripting.ScriptExecutor.ScriptSource;
import com.openmason.main.systems.scripting.doc.LiveModelDocument;
import com.openmason.main.systems.scripting.json.OpBatchException;
import com.openmason.main.systems.scripting.json.OpBatchExecutor;
import com.openmason.main.systems.scripting.live.PartManagerSnapshot;
import com.openmason.main.systems.scripting.live.ScriptRunCommand;
import com.openmason.main.systems.scripting.python.PythonScriptEngine;
import com.openmason.main.systems.services.commands.MeshSnapshot;
import com.openmason.main.systems.threading.MainThreadExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs scripts against the LIVE viewport model as a single atomic step:
 * one entry in the shared model undo history (part manager + mesh both
 * restore), and a failed script rolls the model back to its pre-run state —
 * a half-applied script never leaks.
 *
 * <p>Marshals to the GL/main thread like every MCP service; the await
 * timeout scales with the script timeout.
 */
public final class ScriptingService {

    private static final Logger logger = LoggerFactory.getLogger(ScriptingService.class);

    /** Extra slack over the script timeout for snapshot/restore work. */
    private static final long AWAIT_SLACK_MS = 10_000;

    public static final long DEFAULT_TIMEOUT_MS = 30_000;
    public static final long MAX_TIMEOUT_MS = 300_000;

    private final MainImGuiInterface mainInterface;
    private final ObjectMapper mapper;
    private final ScriptExecutor executor;

    public ScriptingService(MainImGuiInterface mainInterface, ObjectMapper mapper) {
        this.mainInterface = mainInterface;
        this.mapper = mapper;
        this.executor = new ScriptExecutor(mapper, PythonScriptEngine.ifAvailable());
    }

    /** Validate a JSON op batch — pure, no document access, no main-thread hop. */
    public ScriptResult validateOps(String opsJson) {
        OpBatchExecutor batch = new OpBatchExecutor(mapper);
        try {
            batch.validate(batch.parse(opsJson));
            return new ScriptResult(true, null, null, null, null, null);
        } catch (OpBatchException e) {
            return ScriptResult.failure(new ScriptError(
                    e.getMessage(), e.hint(), e.opIndex() >= 0 ? e.opIndex() : null, null));
        }
    }

    /** Run a script against the live model. */
    public ScriptResult runScript(Language language, String source,
                                  long timeoutMs, boolean includeTrace) {
        long timeout = Math.min(Math.max(timeoutMs, 1_000), MAX_TIMEOUT_MS);
        RunOptions opts = new RunOptions(false, includeTrace, timeout);
        String name = language == Language.PYTHON ? "script.py" : "ops.json";
        ScriptSource src = new ScriptSource(language, name, source);
        return await(MainThreadExecutor.submit(() -> runOnMainThread(src, opts)),
                timeout + AWAIT_SLACK_MS);
    }

    private ScriptResult runOnMainThread(ScriptSource src, RunOptions opts) {
        ViewportController vp = mainInterface.getViewport3D();
        if (vp == null) {
            return ScriptResult.failure(new ScriptError(
                    "Editor not initialized", null, null, null));
        }
        // Match create_part's behavior: auto-create a blank model when none is
        // open (the starter model contributes one default part).
        if ((vp.getPartManager() == null || vp.getPartManager().getPartCount() == 0)
                && mainInterface.getModelOperations() != null) {
            mainInterface.getModelOperations().newModel();
        }
        if (vp.getPartManager() == null || vp.getModelRenderer() == null) {
            return ScriptResult.failure(new ScriptError(
                    "No model is open and none could be created", null, null, null));
        }

        PartManagerSnapshot partsBefore = PartManagerSnapshot.capture(vp.getPartManager());
        MeshSnapshot meshBefore = MeshSnapshot.capture(vp.getModelRenderer());

        LiveModelDocument doc = new LiveModelDocument(vp);
        ScriptResult result;
        try {
            result = executor.run(doc, src, opts);
        } catch (RuntimeException e) {
            logger.error("Script execution failed unexpectedly", e);
            result = ScriptResult.failure(new ScriptError(
                    "Script failed: " + e.getMessage(), null, null, null));
        }

        if (!result.ok()) {
            // Roll the model back — part manager AND mesh state.
            try {
                partsBefore.restore(vp.getPartManager());
                meshBefore.restore(vp.getModelRenderer());
                if (vp.getRendererSynchronizer() != null) {
                    vp.getRendererSynchronizer().synchronize();
                }
            } catch (RuntimeException e) {
                logger.error("Rollback after failed script also failed", e);
            }
            return result;
        }

        // One undo entry for the whole run.
        if (vp.getCommandHistory() != null && vp.getRendererSynchronizer() != null) {
            PartManagerSnapshot partsAfter = PartManagerSnapshot.capture(vp.getPartManager());
            MeshSnapshot meshAfter = MeshSnapshot.capture(vp.getModelRenderer());
            vp.getCommandHistory().pushCompleted(new ScriptRunCommand(
                    "Run Script", partsBefore, meshBefore, partsAfter, meshAfter,
                    vp.getPartManager(), vp.getModelRenderer(), vp.getRendererSynchronizer()));
        }
        return result;
    }

    /** Serialize a result to a JsonNode for the tool layer. */
    public JsonNode toJson(ScriptResult result) {
        return mapper.valueToTree(result);
    }

    private static <T> T await(CompletableFuture<T> future, long timeoutMs) {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RuntimeException("Script run timed out on main thread", e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new RuntimeException(cause);
        }
    }
}
