package com.openmason.engine.ui.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the functions a graph may call from Lua source (#291): every exported function whose
 * definition is preceded by a LuaLS {@code ---@} annotation block. Exported means a field of the
 * table the module returns ({@code function M.format_score(n)}) or an environment global
 * ({@code function format_score(n)}); locals and methods are not callable from a graph.
 *
 * <pre>
 * --- Formats a score for the HUD.
 * ---@param points integer
 * ---@param prefix string?
 * ---@return string text
 * function M.format_score(points, prefix) ... end
 * </pre>
 *
 * Types map to port types: {@code boolean} bool, {@code integer} int, {@code number} number,
 * {@code string} string, {@code T[]} list, {@code table}/{@code table<K,V>}/{@code {...}} object,
 * anything else any. A trailing {@code ?} or {@code |nil} makes a parameter optional.
 * {@code ---@async} marks a function that awaits, which only runs inside a task.
 * Lifecycle hooks ({@code on_open}, {@code update}, ...) are never nodes.
 */
public final class LuaSignatures {

    private static final Pattern MODULE_FN = Pattern.compile(
        "^function\\s+([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)");
    private static final Pattern MODULE_ASSIGN = Pattern.compile(
        "^([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*function\\s*\\(([^)]*)\\)");
    private static final Pattern GLOBAL_FN = Pattern.compile(
        "^function\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(([^)]*)\\)");
    private static final Pattern RETURN_TABLE = Pattern.compile("^return\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*;?\\s*$");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** Lifecycle hooks a code-behind defines; equals {@code UiApiCatalog.hookNames()} (tested). */
    static final Set<String> HOOKS = Set.of("on_open", "on_close", "update", "on_input", "on_reload");

    private LuaSignatures() {
    }

    /**
     * @param module {@code ""} for a code-behind, otherwise the module's {@code require} name
     * @return the callable annotated functions, in source order
     */
    public static List<LuaFunction> parse(String module, String source) {
        List<LuaFunction> out = new ArrayList<>();
        if (source == null) {
            return out;
        }
        String[] lines = source.split("\r?\n", -1);
        String table = moduleTable(lines);
        List<String> block = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].strip();
            if (t.startsWith("---")) {
                block.add(t.substring(3).strip());
                continue;
            }
            if (!block.isEmpty() && !t.isEmpty()) {
                LuaFunction f = definition(module, table, t, block, i + 1);
                if (f != null) {
                    out.add(f);
                }
            }
            if (!t.isEmpty()) {
                block.clear();
            }
        }
        return out;
    }

    /** The name of the table the module returns ({@code return M}), or null. */
    private static String moduleTable(String[] lines) {
        for (int i = lines.length - 1; i >= 0; i--) {
            String t = lines[i].strip();
            if (t.isEmpty() || t.startsWith("--")) {
                continue;
            }
            Matcher m = RETURN_TABLE.matcher(t);
            return m.matches() ? m.group(1) : null;
        }
        return null;
    }

    private static LuaFunction definition(String module, String table, String line, List<String> block, int lineNo) {
        String name;
        String params;
        boolean global;
        Matcher m = MODULE_FN.matcher(line);
        Matcher a = MODULE_ASSIGN.matcher(line);
        Matcher g = GLOBAL_FN.matcher(line);
        if (m.find()) {
            if (!m.group(1).equals(table)) {
                return null;
            }
            name = m.group(2);
            params = m.group(3);
            global = false;
        } else if (a.find()) {
            if (!a.group(1).equals(table)) {
                return null;
            }
            name = a.group(2);
            params = a.group(3);
            global = false;
        } else if (g.find()) {
            name = g.group(1);
            params = g.group(2);
            global = true;
        } else {
            return null;
        }
        if (HOOKS.contains(name) || !block.stream().anyMatch(s -> s.startsWith("@"))) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (String p : params.split(",")) {
            String n = p.strip();
            if (IDENT.matcher(n).matches()) {
                names.add(n);
            }
        }
        List<PortSpec> ins = new ArrayList<>();
        for (String n : names) {
            ins.add(param(block, n));
        }
        List<PortSpec> outs = new ArrayList<>();
        StringBuilder doc = new StringBuilder();
        boolean async = false;
        for (String s : block) {
            if (s.startsWith("@return")) {
                outs.add(ret(s.substring(7).strip(), outs.size()));
            } else if (s.equals("@async") || s.startsWith("@async ")) {
                async = true;
            } else if (!s.startsWith("@")) {
                if (!doc.isEmpty()) {
                    doc.append(' ');
                }
                doc.append(s);
            }
        }
        return new LuaFunction(module, name, global, ins, outs, async, doc.toString(), lineNo);
    }

    private static PortSpec param(List<String> block, String name) {
        for (String s : block) {
            if (!s.startsWith("@param")) {
                continue;
            }
            String rest = s.substring(6).strip();
            int sp = rest.indexOf(' ');
            if (sp < 0) {
                continue;
            }
            String[] typed = splitType(rest.substring(sp + 1).strip());
            String[] parts = {rest.substring(0, sp), typed[0], typed[1]};
            String n = parts[0];
            boolean opt = n.endsWith("?");
            if (opt) {
                n = n.substring(0, n.length() - 1);
            }
            if (!n.equals(name)) {
                continue;
            }
            Typed t = type(parts[1]);
            return new PortSpec(name, t.type, null, opt || t.optional, parts[2]);
        }
        return new PortSpec(name, PortType.ANY, null, true, "untyped");
    }

    private static PortSpec ret(String spec, int index) {
        String[] typed = splitType(spec);
        Typed t = type(typed[0]);
        String[] words = typed[1].split("\\s+", 2);
        boolean named = !words[0].isEmpty() && IDENT.matcher(words[0]).matches();
        String name = named ? words[0] : index == 0 ? "result" : "result" + (index + 1);
        String doc = named ? (words.length > 1 ? words[1] : "") : typed[1];
        return new PortSpec(name, t.type, null, t.optional, doc);
    }

    /**
     * {type, rest}: the type runs to the first space outside brackets, so
     * {@code table<string, integer>} and {@code { x: number }} stay one type.
     */
    static String[] splitType(String text) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '<' || c == '{' || c == '(' || c == '[') {
                depth++;
            } else if ((c == '>' || c == '}' || c == ')' || c == ']') && depth > 0) {
                depth--;
            } else if (Character.isWhitespace(c) && depth == 0) {
                return new String[]{text.substring(0, i), text.substring(i).strip()};
            }
        }
        return new String[]{text, ""};
    }

    private record Typed(PortType type, boolean optional) {
    }

    static Typed type(String lua) {
        String t = lua.strip();
        boolean optional = false;
        if (t.endsWith("?")) {
            optional = true;
            t = t.substring(0, t.length() - 1);
        }
        if (t.endsWith("|nil")) {
            optional = true;
            t = t.substring(0, t.length() - 4);
        }
        if (t.startsWith("nil|")) {
            optional = true;
            t = t.substring(4);
        }
        if (t.endsWith("[]")) {
            return new Typed(PortType.LIST, optional);
        }
        if (t.startsWith("table") || t.startsWith("{")) {
            return new Typed(PortType.OBJECT, optional);
        }
        PortType p = switch (t.toLowerCase(Locale.ROOT)) {
            case "boolean", "bool" -> PortType.BOOL;
            case "integer", "int" -> PortType.INT;
            case "number" -> PortType.NUMBER;
            case "string" -> PortType.STRING;
            default -> PortType.ANY;
        };
        return new Typed(p, optional);
    }
}
