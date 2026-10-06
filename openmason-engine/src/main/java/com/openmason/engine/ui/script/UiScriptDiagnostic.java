package com.openmason.engine.ui.script;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A script problem with its source location (#292). {@code chunk} is the module's chunk name
 * ({@code pause.lua}, {@code stonebreak:ui/scripts/common}), {@code line} 0 when unknown.
 */
public record UiScriptDiagnostic(Severity severity, Code code, String chunk, int line, String element,
                                 String message) {

    public enum Severity { INFO, WARNING, ERROR }

    public enum Code {
        /** The Cenda library or its Lua host is missing or has another ABI. */
        UNAVAILABLE,
        /** The document targets a newer {@code ui} API than this host implements. */
        API_VERSION,
        SYNTAX,
        RUNTIME,
        /** A call ran past the watchdog deadline. */
        DEADLINE,
        /** The state hit its memory cap. */
        MEMORY,
        /** The opt-in instruction budget ran out. */
        BUDGET,
        /** A failing handler, hook, watch or converter was switched off. */
        HANDLER_DISABLED,
        MODULE_NOT_FOUND,
        UNDECLARED_MODULE,
        BINARY_SCRIPT,
        /** Static check: a {@code ui.x} member the API does not have. */
        UNKNOWN_API,
        /** A task's action or animation was cancelled (close, reload, world change). */
        TASK_CANCELLED
    }

    private static final Pattern LOCATION = Pattern.compile("^([^\\s:][^:\\n]*):(\\d+):");

    public UiScriptDiagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        chunk = chunk == null ? "" : chunk;
        element = element == null ? "" : element;
        Objects.requireNonNull(message, "message");
    }

    /** {@code chunk:line} when known. */
    public String location() {
        return line > 0 ? chunk + ":" + line : chunk;
    }

    /** First line of the message without its {@code chunk:line:} prefix (a traceback follows on later lines). */
    public String headline() {
        int nl = message.indexOf('\n');
        String first = nl < 0 ? message : message.substring(0, nl);
        String prefix = location() + ": ";
        return line > 0 && first.startsWith(prefix) ? first.substring(prefix.length()) : first;
    }

    /** Builds a diagnostic whose location is read from a Lua message ({@code chunk:line: text}). */
    static UiScriptDiagnostic fromLua(Severity severity, Code code, String fallbackChunk, String element,
                                      String message) {
        Matcher m = LOCATION.matcher(message);
        if (m.find()) {
            return new UiScriptDiagnostic(severity, code, m.group(1), Integer.parseInt(m.group(2)), element, message);
        }
        return new UiScriptDiagnostic(severity, code, fallbackChunk, 0, element, message);
    }

    @Override
    public String toString() {
        String where = location();
        return severity + " " + code + (where.isEmpty() ? "" : " " + where)
            + (element.isEmpty() ? "" : " [" + element + "]") + ": " + headline();
    }
}
