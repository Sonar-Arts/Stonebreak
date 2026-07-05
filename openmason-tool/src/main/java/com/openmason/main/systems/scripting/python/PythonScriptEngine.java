package com.openmason.main.systems.scripting.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.main.systems.scripting.ScriptExecutor;
import com.openmason.main.systems.scripting.ScriptExecutor.PythonScriptException;
import com.openmason.main.systems.scripting.commands.CommandException;
import com.openmason.main.systems.scripting.commands.ModelCommands;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sandboxed GraalPy runner for the {@code om} scripting API.
 *
 * <p>Sandbox: no filesystem, no network, no native access, no threads or
 * subprocesses, no host class lookup — the only host surface is the
 * explicitly-@Export'ed {@link OmHostBridge}. A watchdog interrupts scripts
 * that exceed the timeout. Stdout is captured (truncated at
 * {@link #STDOUT_CAP_BYTES}) and returned with the result.
 *
 * <p>The polyglot {@link Engine} is shared across runs (code caching); each
 * run gets a fresh {@link Context}, so scripts cannot observe each other.
 * Guest failures are translated to short teaching errors with the user
 * script's line number — no raw tracebacks.
 */
public final class PythonScriptEngine implements ScriptExecutor.PythonRunner {

    private static final Logger logger = LoggerFactory.getLogger(PythonScriptEngine.class);

    /** Name of the user script source — used to pick its frames out of guest stacks. */
    public static final String USER_SOURCE_NAME = "script.py";

    private static final int STDOUT_CAP_BYTES = 4096;
    private static final String SHIM_RESOURCE = "/scripting/om.py";

    private static final ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "OpenMason-Python-Watchdog");
                t.setDaemon(true);
                return t;
            });

    private static volatile Engine sharedEngine;
    private static volatile String shimSource;

    /**
     * The engine when GraalPy is on the classpath, else null (the executor
     * then reports Python as unavailable instead of failing at class load).
     */
    public static PythonScriptEngine ifAvailable() {
        try {
            Class.forName("org.graalvm.polyglot.Engine");
            return new PythonScriptEngine();
        } catch (Throwable t) {
            logger.info("GraalPy not on classpath — Python scripting disabled ({})", t.toString());
            return null;
        }
    }

    @Override
    public String run(ModelCommands commands, String source, long timeoutMs) {
        CappedOutputStream stdout = new CappedOutputStream(STDOUT_CAP_BYTES);
        CappedOutputStream stderr = new CappedOutputStream(STDOUT_CAP_BYTES);

        try (Context ctx = Context.newBuilder("python")
                .engine(engine())
                .allowAllAccess(false)
                .allowIO(IOAccess.NONE)
                .allowNativeAccess(false)
                .allowCreateThread(false)
                .allowCreateProcess(false)
                .allowHostClassLookup(className -> false)
                .allowHostAccess(HostAccess.newBuilder(HostAccess.EXPLICIT)
                        .allowArrayAccess(true)
                        .allowListAccess(true)
                        .build())
                .allowPolyglotAccess(PolyglotAccess.NONE)
                .out(stdout)
                .err(stderr)
                .build()) {

            ctx.getBindings("python").putMember("_om_bridge",
                    new OmHostBridge(commands, new ObjectMapper()));

            ScheduledFuture<?> interrupter = WATCHDOG.schedule(() -> {
                try {
                    ctx.interrupt(Duration.ZERO);
                } catch (Exception e) {
                    logger.warn("Could not interrupt Python script", e);
                }
            }, timeoutMs, TimeUnit.MILLISECONDS);

            try {
                ctx.eval(Source.newBuilder("python", shim(), "om.py").buildLiteral());
                ctx.eval(Source.newBuilder("python", source, USER_SOURCE_NAME).buildLiteral());
            } catch (PolyglotException e) {
                // Translate while the context is STILL OPEN — reading the guest
                // exception's __traceback__ (line numbers, OmError cause) fails
                // after close.
                throw translate(e, timeoutMs);
            } finally {
                interrupter.cancel(false);
            }
            return stdout.asString();

        } catch (PythonScriptException | CommandException e) {
            throw e;
        } catch (Exception e) {
            throw new PythonScriptException("Python runtime error: " + e.getMessage(), null, null);
        }
    }

    private static Engine engine() {
        Engine e = sharedEngine;
        if (e == null) {
            synchronized (PythonScriptEngine.class) {
                e = sharedEngine;
                if (e == null) {
                    e = Engine.newBuilder()
                            .option("engine.WarnInterpreterOnly", "false")
                            .build();
                    sharedEngine = e;
                }
            }
        }
        return e;
    }

    private static String shim() {
        String s = shimSource;
        if (s == null) {
            try (InputStream in = PythonScriptEngine.class.getResourceAsStream(SHIM_RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException("Missing classpath resource " + SHIM_RESOURCE);
                }
                s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read " + SHIM_RESOURCE, e);
            }
            shimSource = s;
        }
        return s;
    }

    /** Translate a guest failure into a short teaching error, never a traceback dump. */
    private static PythonScriptException translate(PolyglotException e, long timeoutMs) {
        if (e.isInterrupted() || e.isCancelled()) {
            return new PythonScriptException(
                    "Script timed out after " + (timeoutMs / 1000) + "s and was stopped",
                    null, "avoid unbounded loops; raise timeout_sec for long builds");
        }
        // Line numbers come from the guest exception's __traceback__ — Truffle
        // source sections are root-granular in the fallback runtime (they
        // report line 1 for everything) and host exceptions carry none at all.
        Value guest = e.getGuestObject();
        Integer line = tracebackLine(guest);

        // The om shim wraps bridge failures as OmError(cause) so they gain a
        // Python traceback; unwrap the cause for the real message + hint.
        if (guest != null && isOmError(guest)) {
            Throwable host = unwrapHostCause(guest);
            if (host instanceof CommandException ce) {
                return new PythonScriptException(ce.getMessage(), line, ce.hint());
            }
            if (host != null) {
                return new PythonScriptException("Command failed: " + host.getMessage(), line, null);
            }
            return new PythonScriptException(omErrorMessage(guest, e), line, null);
        }

        if (e.isHostException()) {
            Throwable host = e.asHostException();
            if (host instanceof CommandException ce) {
                return new PythonScriptException(ce.getMessage(), line, ce.hint());
            }
            return new PythonScriptException(
                    "Internal error: " + host.getMessage(), line, null);
        }
        // Plain guest exception: message is already "TypeError: ..." style.
        String message = e.getMessage() != null ? e.getMessage() : "Python error";
        String hint = null;
        if (message.contains("ModuleNotFoundError") || message.contains("ImportError")) {
            hint = "the sandbox bundles only Python's core modules plus `om`; "
                    + "no filesystem, network, or pip packages";
        }
        return new PythonScriptException(message, line, hint);
    }

    /** Deepest user-script line from the Python traceback chain, or null. */
    private static Integer tracebackLine(Value guest) {
        if (guest == null) return null;
        try {
            Value tb = guest.getMember("__traceback__");
            Integer line = null;
            int guard = 0;
            while (tb != null && !tb.isNull() && guard++ < 64) {
                Value frame = tb.getMember("tb_frame");
                if (frame != null && !frame.isNull()) {
                    Value code = frame.getMember("f_code");
                    if (code != null && !code.isNull()) {
                        Value filename = code.getMember("co_filename");
                        if (filename != null && filename.isString()
                                && USER_SOURCE_NAME.equals(filename.asString())) {
                            line = (int) tb.getMember("tb_lineno").asLong();
                        }
                    }
                }
                tb = tb.getMember("tb_next");
            }
            return line;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean isOmError(Value guest) {
        try {
            Value meta = guest.getMetaObject();
            return meta != null && "OmError".equals(meta.getMetaSimpleName());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** The host throwable wrapped as OmError's first arg, if it is one. */
    private static Throwable unwrapHostCause(Value omError) {
        try {
            Value args = omError.getMember("args");
            if (args == null || !args.hasArrayElements() || args.getArraySize() == 0) return null;
            Value cause = args.getArrayElement(0);
            if (cause == null || cause.isNull()) return null;
            if (cause.isHostObject() && cause.asHostObject() instanceof Throwable t) {
                return t;
            }
            if (cause.isException()) {
                try {
                    throw cause.throwException();
                } catch (PolyglotException pe) {
                    return pe.isHostException() ? pe.asHostException() : null;
                }
            }
            return null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Message for an OmError wrapping a guest-level cause (e.g. a TypeError). */
    private static String omErrorMessage(Value omError, PolyglotException e) {
        try {
            Value args = omError.getMember("args");
            if (args != null && args.hasArrayElements() && args.getArraySize() > 0) {
                return "Command failed: " + args.getArrayElement(0).toString();
            }
        } catch (RuntimeException ignored) {
        }
        return e.getMessage() != null ? e.getMessage() : "Command failed";
    }

    /** Byte-capped stream: keeps the first N bytes, notes truncation. */
    private static final class CappedOutputStream extends OutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final int cap;
        private boolean truncated;

        CappedOutputStream(int cap) {
            this.cap = cap;
        }

        @Override
        public synchronized void write(int b) {
            if (buffer.size() < cap) buffer.write(b);
            else truncated = true;
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            int room = cap - buffer.size();
            if (room > 0) buffer.write(b, off, Math.min(len, room));
            if (len > room) truncated = true;
        }

        synchronized String asString() {
            String s = buffer.toString(StandardCharsets.UTF_8);
            return truncated ? s + "\n…(output truncated)" : s;
        }
    }
}
