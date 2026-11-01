package com.openmason.engine.ui.graph;

import com.openmason.engine.ui.script.UiApiCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Callable Lua functions read from LuaLS annotations (#291): what becomes a node, and its typed ports. */
class LuaSignaturesTest {

    static List<String> names(List<LuaFunction> fns) {
        return fns.stream().map(LuaFunction::name).toList();
    }

    static LuaFunction only(String module, String source) {
        List<LuaFunction> fns = LuaSignatures.parse(module, source);
        assertEquals(1, fns.size(), fns.toString());
        return fns.getFirst();
    }

    static PortSpec param(String luaType) {
        return only("", """
            local M = {}
            ---@param v %s
            function M.f(v) end
            return M
            """.formatted(luaType)).params().getFirst();
    }

    @Test
    void moduleFunctionsOfTheReturnedTable() {
        LuaFunction f = only("hud", """
            local M = {}

            --- Formats a score for the HUD.
            ---@param points integer
            ---@param prefix string? shown before the number
            ---@return string text
            function M.format_score(points, prefix)
              return (prefix or "") .. points
            end

            return M
            """);
        assertEquals("hud", f.module());
        assertEquals("format_score", f.name());
        assertFalse(f.global());
        assertEquals("hud.format_score", f.label());
        assertEquals(List.of(new PortSpec("points", PortType.INT, null, false, ""),
            new PortSpec("prefix", PortType.STRING, null, true, "shown before the number")), f.params());
        assertEquals(List.of(new PortSpec("text", PortType.STRING, null, false, "")), f.returns());
        assertFalse(f.async());
        assertEquals("Formats a score for the HUD.", f.doc());
        assertEquals(7, f.line(), "1-based line of the function keyword");
    }

    @Test
    void assignedModuleFunctions() {
        LuaFunction f = only("", """
            local M = {}
            ---@param a number
            ---@param b number
            M.add = function(a, b) return a + b end
            return M
            """);
        assertEquals("add", f.name());
        assertFalse(f.global());
        assertEquals("add", f.label());
        assertEquals(List.of("a", "b"), f.params().stream().map(PortSpec::name).toList());
    }

    @Test
    void globalsAreCallable() {
        LuaFunction f = only("", """
            ---@return integer
            function best() return 42 end
            """);
        assertEquals("best", f.name());
        assertTrue(f.global());
        assertEquals(List.of(), f.params());
        assertEquals(List.of(new PortSpec("result", PortType.INT, null, false, "")), f.returns());
    }

    @Test
    void localsMethodsAndOtherTablesAreNotCallable() {
        List<LuaFunction> fns = LuaSignatures.parse("", """
            local M = {}
            local Other = {}

            ---@param x integer
            local function helper(x) return x end

            ---@param x integer
            function M:method(x) return x end

            ---@param x integer
            function Other.elsewhere(x) return x end

            ---@param x integer
            Other.assigned = function(x) return x end

            ---@param x integer
            function M.exported(x) return helper(x) end
            return M
            """);
        assertEquals(List.of("exported"), names(fns));
    }

    @Test
    void lifecycleHooksAreNeverNodes() {
        List<LuaFunction> fns = LuaSignatures.parse("", """
            local M = {}
            ---@param ui table
            function M.on_open(ui) end
            ---@param dt number
            function M.update(dt) end
            ---@param ui table
            function on_close(ui) end
            ---@param ev table
            ---@return boolean?
            function M.on_input(ev) end
            ---@param ui table
            function M.on_reload(ui) end
            ---@param x integer
            function M.kept(x) end
            return M
            """);
        assertEquals(List.of("kept"), names(fns));
    }

    @Test
    void functionsWithoutAnAnnotationAreIgnored() {
        List<LuaFunction> fns = LuaSignatures.parse("", """
            local M = {}
            --- Only a description, no ---@ line.
            function M.plain(x) end

            function M.bare(x) end

            ---@param x integer
            -- a plain comment breaks the block
            function M.broken(x) end

            ---@param x integer

            function M.blank_lines_keep_the_block(x) end
            return M
            """);
        assertEquals(List.of("blank_lines_keep_the_block"), names(fns));
    }

    @Test
    void withoutAReturnedTableOnlyGlobalsCount() {
        List<LuaFunction> fns = LuaSignatures.parse("", """
            local M = {}
            ---@param x integer
            function M.f(x) end
            ---@param x integer
            function g(x) end
            """);
        assertEquals(List.of("g"), names(fns));
        assertEquals(List.of(), LuaSignatures.parse("", null));
    }

    @Test
    void typesMapToPortTypes() {
        assertEquals(PortType.BOOL, param("boolean").type());
        assertEquals(PortType.INT, param("integer").type());
        assertEquals(PortType.NUMBER, param("number").type());
        assertEquals(PortType.STRING, param("string").type());
        assertEquals(PortType.LIST, param("string[]").type());
        assertEquals(PortType.LIST, param("integer[]").type());
        assertEquals(PortType.OBJECT, param("table").type());
        assertEquals(PortType.OBJECT, param("table<string,integer>").type());
        assertEquals(PortType.OBJECT, param("{x:number,y:number}").type());
        assertEquals(PortType.ANY, param("any").type());
        assertEquals(PortType.ANY, param("ui.Element").type());
        assertEquals(PortType.ANY, param("integer|string").type());
    }

    @Test
    void optionalTypesAndNames() {
        assertFalse(param("integer").optional());
        PortSpec q = param("integer?");
        assertEquals(PortType.INT, q.type());
        assertTrue(q.optional());
        PortSpec nil = param("string|nil");
        assertEquals(PortType.STRING, nil.type());
        assertTrue(nil.optional());
        PortSpec list = param("string[]?");
        assertEquals(PortType.LIST, list.type());
        assertTrue(list.optional());

        LuaFunction f = only("", """
            ---@param a? integer
            ---@param b number
            function f(a, b, c) end
            """);
        assertTrue(f.params().get(0).optional(), "name? marks an optional parameter");
        assertEquals(PortType.INT, f.params().get(0).type());
        assertFalse(f.params().get(1).optional());
        assertEquals(new PortSpec("c", PortType.ANY, null, true, "untyped"), f.params().get(2),
            "a parameter without ---@param is an optional any");
    }

    @Test
    void returnNamesDefaultToResultResult2() {
        LuaFunction f = only("", """
            ---@return integer
            ---@return string label the shown text
            ---@return boolean
            ---@return number?
            function stats() end
            """);
        assertEquals(List.of(new PortSpec("result", PortType.INT, null, false, ""),
            new PortSpec("label", PortType.STRING, null, false, "the shown text"),
            new PortSpec("result3", PortType.BOOL, null, false, ""),
            new PortSpec("result4", PortType.NUMBER, null, true, "")), f.returns());
    }

    @Test
    void typesWithSpacesKeepTheReturnName() {
        LuaFunction f = only("", """
            ---@return table<string, integer> counts per kind
            function tally() end
            """);
        assertEquals(new PortSpec("counts", PortType.OBJECT, null, false, "per kind"), f.returns().getFirst());
    }

    @Test
    void asyncFunctionsAreMarked() {
        List<LuaFunction> fns = LuaSignatures.parse("", """
            local M = {}
            ---@async
            ---@param s number
            function M.cooldown(s) end
            ---@async the comment after the tag
            function M.later() end
            ---@param s number
            function M.now(s) end
            ---@asynchronous
            function M.not_a_tag() end
            return M
            """);
        assertEquals(List.of("cooldown", "later", "now", "not_a_tag"), names(fns));
        assertEquals(List.of(true, true, false, false), fns.stream().map(LuaFunction::async).toList());
    }

    @Test
    void docLinesJoinAndAnnotationsAreNotDoc() {
        LuaFunction f = only("", """
            --- First line.
            --- Second line.
            ---@param x integer
            ---@return integer
            function twice(x) return x * 2 end
            """);
        assertEquals("First line. Second line.", f.doc());
    }

    @Test
    void windowsLineEndingsParse() {
        LuaFunction f = only("", "local M = {}\r\n---@param x integer\r\nfunction M.f(x) end\r\nreturn M\r\n");
        assertEquals("f", f.name());
        assertEquals(PortType.INT, f.params().getFirst().type());
    }

    @Test
    void hooksMatchTheScriptCatalog() {
        assertEquals(UiApiCatalog.hookNames(), LuaSignatures.HOOKS);
    }
}
