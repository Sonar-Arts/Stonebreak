package com.openmason.engine.ui.script;

import com.openmason.engine.cenda.CendaLua;
import com.openmason.engine.cenda.LuaState;
import com.openmason.engine.ui.script.UiScriptDiagnostic.Code;
import com.openmason.engine.ui.script.UiScriptDiagnostic.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The editor's static check of a code-behind module (#292), without running it: Lua syntax
 * (compiled in a throwaway sandboxed state), {@code ui.x} members the API does not have (with a
 * suggestion), event names that are not UI events, and lifecycle hooks with near-miss names
 * ({@code onOpen} for {@code on_open}). LuaLS with {@link UiApiStubs} gives the same API
 * diagnostics in external editors.
 */
public final class UiScriptChecker {

    private static final Pattern UI_MEMBER = Pattern.compile("(?<![\\w.:])ui\\.([A-Za-z_]\\w*)");
    private static final Pattern ON_EVENT = Pattern.compile(":on\\(\\s*([\"'])([^\"']+)\\1");
    private static final Pattern FUNCTION = Pattern.compile("function\\s+(?:[A-Za-z_]\\w*[.:])?([A-Za-z_]\\w*)\\s*\\(");

    private UiScriptChecker() {
    }

    public static List<UiScriptDiagnostic> check(String source, String chunk) {
        List<UiScriptDiagnostic> out = new ArrayList<>();
        syntax(source, chunk, out);
        String code = stripCommentsAndStrings(source);
        Set<String> api = UiApiCatalog.uiNames();
        Matcher m = UI_MEMBER.matcher(code);
        while (m.find()) {
            String name = m.group(1);
            if (!api.contains(name)) {
                String near = nearest(name, api);
                out.add(new UiScriptDiagnostic(Severity.WARNING, Code.UNKNOWN_API, chunk, line(source, m.start()), "",
                    "ui." + name + " is not part of ui API " + UiApiCatalog.VERSION
                        + (near == null ? "" : "; did you mean ui." + near + "?")));
            }
        }
        Matcher e = ON_EVENT.matcher(source);
        while (e.find()) {
            String event = e.group(2);
            if (ScriptEvents.type(event) == null) {
                out.add(new UiScriptDiagnostic(Severity.INFO, Code.UNKNOWN_API, chunk, line(source, e.start()), "",
                    "'" + event + "' is not a UI event; fine if it is a signal of the component instance"));
            }
        }
        Matcher f = FUNCTION.matcher(code);
        while (f.find()) {
            String name = f.group(1);
            for (String hook : UiApiCatalog.hookNames()) {
                if (!name.equals(hook) && squash(name).equals(squash(hook))) {
                    out.add(new UiScriptDiagnostic(Severity.WARNING, Code.UNKNOWN_API, chunk, line(source, f.start()), "",
                        name + " is never called; did you mean the " + hook + " hook?"));
                }
            }
        }
        return out;
    }

    private static void syntax(String source, String chunk, List<UiScriptDiagnostic> out) {
        if (!CendaLua.isAvailable()) {
            out.add(new UiScriptDiagnostic(Severity.WARNING, Code.UNAVAILABLE, chunk, 0, "",
                "syntax not checked: the Lua host is unavailable"));
            return;
        }
        if (!source.isEmpty() && source.charAt(0) == '\u001b') {
            out.add(new UiScriptDiagnostic(Severity.ERROR, Code.BINARY_SCRIPT, chunk, 0, "",
                "binary chunks are refused; only Lua source runs"));
            return;
        }
        try (LuaState lua = CendaLua.newState(32L << 20)) {
            lua.run("function __check(src, name) local _, err = load(src, '=' .. name, 't') return err end",
                "checker", 0);
            int fn = lua.refFunction(0, "__check");
            lua.args().string(source).string(chunk);
            if (lua.callValues(fn, 1) == LuaState.OK) {
                String err = lua.results().string();
                if (err != null) {
                    out.add(UiScriptDiagnostic.fromLua(Severity.ERROR, Code.SYNTAX, chunk, "", err));
                }
            }
        }
    }

    /** Blanks comments and string literals (keeping newlines) so patterns only see code. */
    static String stripCommentsAndStrings(String s) {
        StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
                int level = longBracket(s, i + 2);
                int end = level >= 0 ? closeLongBracket(s, i + 2 + level + 2, level) : s.indexOf('\n', i);
                end = end < 0 ? n : end;
                blank(b, s, i, end);
                i = end;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && s.charAt(j) != c && s.charAt(j) != '\n') {
                    j += s.charAt(j) == '\\' ? 2 : 1;
                }
                int end = Math.min(n, j + 1);
                blank(b, s, i, end);
                i = end;
            } else if (c == '[' && longBracket(s, i) >= 0) {
                int level = longBracket(s, i);
                int end = closeLongBracket(s, i + level + 2, level);
                end = end < 0 ? n : end;
                blank(b, s, i, end);
                i = end;
            } else {
                b.append(c);
                i++;
            }
        }
        return b.toString();
    }

    /** Level of a {@code [==[} opening at {@code i}, or -1. */
    private static int longBracket(String s, int i) {
        if (i >= s.length() || s.charAt(i) != '[') {
            return -1;
        }
        int j = i + 1;
        while (j < s.length() && s.charAt(j) == '=') {
            j++;
        }
        return j < s.length() && s.charAt(j) == '[' ? j - i - 1 : -1;
    }

    /** Index just past the matching {@code ]==]}, or -1. */
    private static int closeLongBracket(String s, int from, int level) {
        String close = "]" + "=".repeat(level) + "]";
        int at = s.indexOf(close, from);
        return at < 0 ? -1 : at + close.length();
    }

    private static void blank(StringBuilder b, String s, int from, int to) {
        for (int k = from; k < to; k++) {
            b.append(s.charAt(k) == '\n' ? '\n' : ' ');
        }
    }

    private static int line(String s, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String squash(String name) {
        return name.replace("_", "").toLowerCase(Locale.ROOT);
    }

    private static String nearest(String name, Set<String> names) {
        String best = null;
        int bestD = 3;
        for (String n : names) {
            int d = distance(name.toLowerCase(Locale.ROOT), n.toLowerCase(Locale.ROOT));
            if (d < bestD) {
                bestD = d;
                best = n;
            }
        }
        return best;
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
