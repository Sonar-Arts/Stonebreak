package com.openmason.main.systems.scripting.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openmason.main.systems.scripting.ScriptExecutor;
import com.openmason.main.systems.scripting.ScriptExecutor.Language;
import com.openmason.main.systems.scripting.ScriptExecutor.RunOptions;
import com.openmason.main.systems.scripting.ScriptExecutor.ScriptResult;
import com.openmason.main.systems.scripting.ScriptExecutor.ScriptSource;
import com.openmason.main.systems.scripting.commands.CommandException;
import com.openmason.main.systems.scripting.doc.HeadlessModelDocument;
import com.openmason.main.systems.scripting.python.PythonScriptEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Headless CLI: run a modeling script and write the result to a .omo file —
 * no window, no GL context, no running Open Mason instance.
 *
 * <pre>
 * java -cp openmason-tool.jar com.openmason.main.systems.scripting.runner.HeadlessScriptRunner \
 *      --script crab.py|crab.json --out crab.omo [--validate] [--timeout 30] [--trace] [--json]
 * </pre>
 *
 * <p>Language is inferred from the script extension ({@code .py} /
 * {@code .json}). On any failure nothing is written and the exit code is 1;
 * {@code --json} prints the full machine-readable result to stdout.
 */
public final class HeadlessScriptRunner {

    private HeadlessScriptRunner() {
    }

    public static void main(String[] args) {
        int exit = run(args);
        System.exit(exit);
    }

    static int run(String[] args) {
        String scriptPath = null;
        String outPath = null;
        boolean validateOnly = false;
        boolean trace = false;
        boolean json = false;
        long timeoutSeconds = 30;

        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--script" -> scriptPath = argValue(args, ++i, "--script");
                    case "--out" -> outPath = argValue(args, ++i, "--out");
                    case "--validate" -> validateOnly = true;
                    case "--trace" -> trace = true;
                    case "--json" -> json = true;
                    case "--timeout" -> timeoutSeconds = Long.parseLong(argValue(args, ++i, "--timeout"));
                    case "--help", "-h" -> {
                        printUsage();
                        return 0;
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
                }
            }
            if (scriptPath == null) {
                throw new IllegalArgumentException("--script is required");
            }
            if (outPath == null && !validateOnly) {
                throw new IllegalArgumentException("--out is required (or pass --validate)");
            }
        } catch (RuntimeException e) {
            System.err.println("error: " + e.getMessage());
            printUsage();
            return 1;
        }

        ScriptSource source;
        try {
            Path path = Path.of(scriptPath);
            source = new ScriptSource(languageOf(path), path.getFileName().toString(),
                    Files.readString(path));
        } catch (IOException e) {
            System.err.println("error: cannot read script: " + e.getMessage());
            return 1;
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            return 1;
        }

        ObjectMapper mapper = new ObjectMapper();
        ScriptExecutor executor = new ScriptExecutor(mapper, PythonScriptEngine.ifAvailable());
        HeadlessModelDocument doc = new HeadlessModelDocument();
        // Relative animation-save paths resolve next to the output model.
        Path baseDir = outPath != null
                ? Path.of(outPath).toAbsolutePath().getParent()
                : Path.of("").toAbsolutePath();
        RunOptions opts = new RunOptions(validateOnly, trace, timeoutSeconds * 1000L, baseDir);

        ScriptResult result = executor.run(doc, source, opts);

        if (result.ok() && !validateOnly) {
            try {
                String modelName = stripExtension(Path.of(outPath).getFileName().toString());
                HeadlessOmoWriter.write(doc, outPath, modelName);
            } catch (CommandException e) {
                result = ScriptResult.failure(new ScriptExecutor.ScriptError(
                        e.getMessage(), e.hint(), null, null));
            }
        }

        report(result, mapper, json, validateOnly, outPath);
        return result.ok() ? 0 : 1;
    }

    private static void report(ScriptResult result, ObjectMapper mapper,
                               boolean json, boolean validateOnly, String outPath) {
        if (json) {
            try {
                ObjectNode node = mapper.valueToTree(result);
                if (result.ok() && !validateOnly) node.put("out", outPath);
                System.out.println(mapper.writeValueAsString(node));
            } catch (Exception e) {
                System.err.println("error: cannot serialize result: " + e.getMessage());
            }
            return;
        }
        if (!result.ok()) {
            ScriptExecutor.ScriptError err = result.error();
            StringBuilder sb = new StringBuilder("error: ").append(err.error());
            if (err.opIndex() != null) sb.append(" (op ").append(err.opIndex()).append(')');
            if (err.line() != null) sb.append(" (line ").append(err.line()).append(')');
            System.err.println(sb);
            if (err.hint() != null) System.err.println("hint: " + err.hint());
            return;
        }
        if (validateOnly) {
            System.out.println("valid");
            return;
        }
        var totals = result.summary().totals();
        System.out.println("wrote " + outPath + " — " + totals.parts() + " parts, "
                + totals.vertices() + " vertices, " + totals.faces() + " faces");
        if (result.files() != null) {
            for (String file : result.files()) {
                System.out.println("wrote " + file);
            }
        }
        if (result.stdout() != null && !result.stdout().isBlank()) {
            System.out.println("--- script output ---");
            System.out.println(result.stdout());
        }
    }

    private static Language languageOf(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".py")) return Language.PYTHON;
        if (name.endsWith(".json")) return Language.JSON_OPS;
        throw new IllegalArgumentException("cannot infer language from '" + name
                + "' — use a .py or .json script");
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String argValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return args[index];
    }

    private static void printUsage() {
        System.err.println("""
                usage: HeadlessScriptRunner --script <file.py|file.json> --out <model.omo>
                                            [--validate] [--timeout <seconds>] [--trace] [--json]
                  --script    modeling script (.py = Python om API, .json = op batch)
                  --out       output .omo path (omit with --validate)
                  --validate  check the script without writing anything (JSON batches only)
                  --timeout   Python execution timeout in seconds (default 30)
                  --trace     include the normalized ops trace in --json output
                  --json      print the machine-readable result to stdout""");
    }
}
