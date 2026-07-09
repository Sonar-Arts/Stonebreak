package com.openmason.main.systems.scripting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.main.systems.scripting.commands.CommandException;
import com.openmason.main.systems.scripting.commands.ModelCommands;
import com.openmason.main.systems.scripting.commands.ModelSummary;
import com.openmason.main.systems.scripting.doc.CanvasSurface;
import com.openmason.main.systems.scripting.doc.ModelDocument;
import com.openmason.main.systems.scripting.json.OpBatchException;
import com.openmason.main.systems.scripting.json.OpBatchExecutor;

import java.util.List;

/**
 * Runs a script (JSON op-batch or Python) against a {@link ModelDocument} and
 * returns a compact, token-efficient {@link ScriptResult}.
 *
 * <p>The executor itself never guarantees atomicity — callers do: the
 * headless runner discards the document (and writes nothing) on failure, the
 * live service restores the before-snapshot. JSON batches are validated in
 * full before any op executes, so most failures never touch the document.
 *
 * <p>Python support is pluggable via {@link PythonRunner} so the JSON layer
 * carries no GraalPy dependency at runtime until a Python script is actually run.
 */
public final class ScriptExecutor {

    /** Script language, normally inferred from the file extension. */
    public enum Language { PYTHON, JSON_OPS }

    /** A script to run: language + display name (for errors) + source text. */
    public record ScriptSource(Language language, String name, String source) {
    }

    /**
     * @param baseDir directory relative animation-save paths resolve against
     *                (CLI: the output directory; live: null = absolute only)
     */
    public record RunOptions(boolean validateOnly, boolean includeTrace, long timeoutMs,
                             java.nio.file.Path baseDir) {
        public RunOptions(boolean validateOnly, boolean includeTrace, long timeoutMs) {
            this(validateOnly, includeTrace, timeoutMs, null);
        }

        public static RunOptions defaults() {
            return new RunOptions(false, false, 30_000);
        }
    }

    /** Failure detail: message + optional hint + failing op index (JSON) or line (Python). */
    public record ScriptError(String error, String hint, Integer opIndex, Integer line) {
    }

    /**
     * Compact result for all frontends. {@code summary} is present on success;
     * {@code opsTrace} only when requested; {@code files} lists any .omanim
     * clips the script wrote.
     */
    public record ScriptResult(
            boolean ok,
            ScriptError error,
            ModelSummary summary,
            String stdout,
            List<ObjectNode> opsTrace,
            List<String> files) {

        public static ScriptResult failure(ScriptError error) {
            return new ScriptResult(false, error, null, null, null, null);
        }
    }

    /** Seam for the GraalPy engine (registered when Python support is present). */
    public interface PythonRunner {
        /**
         * Execute Python source against the command layer.
         *
         * @return captured stdout (possibly truncated)
         * @throws PythonScriptException on any guest failure
         */
        String run(ModelCommands commands, String source, long timeoutMs);
    }

    /** A Python guest error, already translated to a short teaching message. */
    public static class PythonScriptException extends RuntimeException {
        private final Integer line;
        private final String hint;

        public PythonScriptException(String message, Integer line, String hint) {
            super(message);
            this.line = line;
            this.hint = hint;
        }

        public Integer line() {
            return line;
        }

        public String hint() {
            return hint;
        }
    }

    private final ObjectMapper mapper;
    private final PythonRunner pythonRunner;

    public ScriptExecutor(ObjectMapper mapper, PythonRunner pythonRunner) {
        this.mapper = mapper;
        this.pythonRunner = pythonRunner;
    }

    public ScriptResult run(ModelDocument doc, ScriptSource src, RunOptions opts) {
        return run(doc, null, src, opts);
    }

    /**
     * @param canvas the open texture editor's canvas surface, or null when it
     *               isn't open (canvas ops then raise a teaching error)
     */
    public ScriptResult run(ModelDocument doc, CanvasSurface canvas,
                            ScriptSource src, RunOptions opts) {
        return switch (src.language()) {
            case JSON_OPS -> runJson(doc, canvas, src, opts);
            case PYTHON -> runPython(doc, canvas, src, opts);
        };
    }

    private ScriptResult runJson(ModelDocument doc, CanvasSurface canvas,
                                 ScriptSource src, RunOptions opts) {
        OpBatchExecutor batch = new OpBatchExecutor(mapper);
        JsonNode root;
        try {
            root = batch.parse(src.source());
            batch.validate(root);
        } catch (OpBatchException e) {
            return ScriptResult.failure(new ScriptError(
                    e.getMessage(), e.hint(), e.opIndex() >= 0 ? e.opIndex() : null, null));
        }
        if (opts.validateOnly()) {
            return new ScriptResult(true, null, null, null, null, null);
        }

        ModelCommands cmds = new ModelCommands(doc, mapper, opts.baseDir(), canvas);
        try {
            batch.execute(root, cmds);
        } catch (OpBatchException e) {
            return ScriptResult.failure(new ScriptError(
                    e.getMessage(), e.hint(), e.opIndex() >= 0 ? e.opIndex() : null, null));
        }
        return success(cmds, null, opts);
    }

    private ScriptResult runPython(ModelDocument doc, CanvasSurface canvas,
                                   ScriptSource src, RunOptions opts) {
        if (pythonRunner == null) {
            return ScriptResult.failure(new ScriptError(
                    "Python scripting is not available in this build", null, null, null));
        }
        if (opts.validateOnly()) {
            return ScriptResult.failure(new ScriptError(
                    "validate-only is not supported for Python scripts",
                    "run against a scratch document instead, or use a JSON op batch", null, null));
        }
        ModelCommands cmds = new ModelCommands(doc, mapper, opts.baseDir(), canvas);
        try {
            String stdout = pythonRunner.run(cmds, src.source(), opts.timeoutMs());
            return success(cmds, stdout, opts);
        } catch (PythonScriptException e) {
            return ScriptResult.failure(new ScriptError(
                    e.getMessage(), e.hint(), null, e.line()));
        } catch (CommandException e) {
            return ScriptResult.failure(new ScriptError(e.getMessage(), e.hint(), null, null));
        }
    }

    private ScriptResult success(ModelCommands cmds, String stdout, RunOptions opts) {
        // Deferred file output (.omanim saves, canvas PNG exports) flushes only
        // now, after the script succeeded — a failing script never writes files.
        List<String> files = new java.util.ArrayList<>();
        try {
            files.addAll(cmds.anim().flushSaves());
            files.addAll(cmds.canvas().flushExports());
        } catch (CommandException e) {
            return ScriptResult.failure(new ScriptError(e.getMessage(), e.hint(), null, null));
        }
        return new ScriptResult(
                true, null, cmds.summary(),
                stdout == null || stdout.isEmpty() ? null : stdout,
                opts.includeTrace() ? cmds.opsTrace() : null,
                files.isEmpty() ? null : files);
    }
}
