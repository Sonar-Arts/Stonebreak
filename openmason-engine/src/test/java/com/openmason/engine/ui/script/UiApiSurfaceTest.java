package com.openmason.engine.ui.script;

import com.openmason.engine.format.omui.OmuiFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code ui} API's three views agree (#292): the prelude that implements it, the catalog,
 * the LuaLS stubs generated from the catalog, and the editor's static check.
 */
class UiApiSurfaceTest {

    private static String prelude() throws Exception {
        try (InputStream in = UiScriptRuntime.class.getResourceAsStream("prelude.lua")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void catalogMatchesThePrelude() throws Exception {
        String prelude = prelude();
        assertEquals(OmuiFormat.UI_API_VERSION, UiApiCatalog.VERSION);
        assertEquals(defined(prelude, "function ui\\.([A-Za-z_]\\w*)"), functions("ui"), "ui.* functions");
        assertEquals(defined(prelude, "function Element:([A-Za-z_]\\w*)"), functions("ui.Element"), "Element methods");
        assertEquals(defined(prelude, "function Canvas:([A-Za-z_]\\w*)"), functions("ui.Canvas"), "Canvas methods");
        assertEquals(defined(prelude, "function Handle:([A-Za-z_]\\w*)"), functions("ui.Handle"), "Handle methods");
        assertEquals(defined(prelude, "^\\s+([a-zA-Z]+) = function\\(e\\)"), functions("ui.Event"), "Event methods");
    }

    private static Set<String> defined(String src, String regex) {
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile(regex, Pattern.MULTILINE).matcher(src);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static Set<String> functions(String owner) {
        Set<String> out = new TreeSet<>();
        for (UiApiCatalog.Member m : UiApiCatalog.of(owner)) {
            if (!m.field()) {
                out.add(m.name());
            }
        }
        return out;
    }

    @Test
    void stubsDescribeEveryMemberForLuaLs(@TempDir Path dir) throws Exception {
        String stub = UiApiStubs.stub();
        assertTrue(stub.startsWith("---@meta\n"));
        for (UiApiCatalog.Member m : UiApiCatalog.MEMBERS) {
            String needle = switch (m.owner()) {
                case "ui" -> m.field() ? "---@field " + m.name() + " " : "function ui." + m.name() + "(";
                case "module" -> "---@field " + m.name() + "? fun(";
                default -> m.field() ? "---@field " + m.name() + " "
                    : "function " + m.owner().substring(3) + ":" + m.name() + "(";
            };
            assertTrue(stub.contains(needle), "stub lacks " + m.owner() + "." + m.name() + " (" + needle + ")");
        }
        assertTrue(stub.contains("---@param args? table\n"), "optional parameters are marked");
        assertTrue(stub.contains("---@return any\n---@return string?\nfunction ui.await(handle) end"), stub);
        UiApiStubs.write(dir);
        assertEquals(stub, Files.readString(dir.resolve(UiApiStubs.STUB_FILE)));
        assertTrue(Files.readString(dir.resolve(UiApiStubs.CONFIG_FILE)).contains("\"io\": \"disable\""));
    }

    /**
     * The stubs drive a real LuaLS (#292 AC): opt-in with {@code -Dui.luals=<lua-language-server>}.
     * A typed module gets undefined-field, parameter-type and arity diagnostics against the API,
     * the sandbox's missing libraries are undefined globals, and the samples check clean.
     */
    @Test
    void luaLanguageServerReportsApiMisuseFromTheStubs(@TempDir Path ws) throws Exception {
        String luals = System.getProperty("ui.luals");
        org.junit.jupiter.api.Assumptions.assumeTrue(luals != null && !luals.isBlank(),
            "set -Dui.luals=<path to lua-language-server> to run the LuaLS check");
        UiApiStubs.write(ws);
        Files.writeString(ws.resolve("good.lua"), ScriptSamples.source("scripted_pause.lua"));
        Files.writeString(ws.resolve("game.lua"), ScriptSamples.source("minigame.lua"));
        Files.writeString(ws.resolve("bad.lua"), """
            ---@type ui.Module
            local M = {}
            function M.on_open(ui)
              ui.qq("#resume")
              ui.q(42)
              ui.q("#resume"):setTxt("x")
              os.exit(1)
            end
            ui.tween()
            return M
            """);
        Path out = ws.resolve("check.json");
        Process p = new ProcessBuilder(luals, "--check=" + ws, "--checklevel=Information", "--check_format=json",
            "--check_out_path=" + out, "--logpath=" + ws.resolve("log")).directory(ws.toFile())
            .redirectErrorStream(true).start();
        p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        assertTrue(p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS), "LuaLS timed out");
        String json = Files.readString(out);
        com.openmason.engine.format.omui.UiValue root = com.openmason.engine.format.omui.io.CanonicalJson.parse(
            json.getBytes(StandardCharsets.UTF_8), "check.json", new com.openmason.engine.format.omui.UiDiagnostics());
        List<String> found = new java.util.ArrayList<>();
        ((com.openmason.engine.format.omui.UiValue.Obj) root).fields().forEach((file, list) -> {
            for (com.openmason.engine.format.omui.UiValue d : ((com.openmason.engine.format.omui.UiValue.Arr) list).items()) {
                var o = (com.openmason.engine.format.omui.UiValue.Obj) d;
                found.add(file.substring(file.lastIndexOf('/') + 1) + ":" + ((com.openmason.engine.format.omui.UiValue.Str) o.get("code")).value());
            }
        });
        assertTrue(found.containsAll(List.of("bad.lua:undefined-field", "bad.lua:param-type-mismatch",
            "bad.lua:missing-parameter", "bad.lua:undefined-global")), found.toString());
        assertTrue(found.stream().noneMatch(f -> f.startsWith("good.lua") || f.startsWith("game.lua")),
            "the samples check clean: " + found);
    }

    @Test
    void stubsAreValidLua() {
        ScriptRig.assumeLua();
        List<UiScriptDiagnostic> d = UiScriptChecker.check(UiApiStubs.stub(), "ui.d.lua").stream()
            .filter(x -> x.code() == UiScriptDiagnostic.Code.SYNTAX).toList();
        assertTrue(d.isEmpty(), d.toString());
    }

    @Test
    void checkerFindsSyntaxUnknownMembersEventsAndHookTypos() {
        ScriptRig.assumeLua();
        String src = """
            -- ui.nothing in a comment is fine, and so is "ui.inString"
            function onOpen(ui)
              ui.qq("#x"):on("clik", function() end)
              ui.tween(ui.root, { opacity = 0 })
            end
            local broken = (
            """;
        List<UiScriptDiagnostic> d = UiScriptChecker.check(src, "main.lua");
        assertTrue(d.stream().anyMatch(x -> x.code() == UiScriptDiagnostic.Code.SYNTAX && x.line() == 7), d.toString());
        assertTrue(d.stream().anyMatch(x -> x.message().contains("ui.qq") && x.message().contains("did you mean ui.q?")
            && x.line() == 3), d.toString());
        assertTrue(d.stream().anyMatch(x -> x.message().contains("'clik' is not a UI event")), d.toString());
        assertTrue(d.stream().anyMatch(x -> x.message().contains("did you mean the on_open hook")), d.toString());
        assertTrue(d.stream().noneMatch(x -> x.message().contains("nothing") || x.message().contains("inString")),
            "comments and strings are not code: " + d);
        assertEquals(4, d.size(), d.toString());
    }

    @Test
    void samplesPassTheCheck() {
        ScriptRig.assumeLua();
        for (String sample : List.of("scripted_pause.lua", "minigame.lua")) {
            List<UiScriptDiagnostic> d = UiScriptChecker.check(ScriptSamples.source(sample), sample);
            assertTrue(d.isEmpty(), sample + ": " + d);
        }
    }
}
