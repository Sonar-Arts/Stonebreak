package com.openmason.engine.ui.graph;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Generated-line → graph-node mapping (#291), read back from the {@code -- @node} markers alone. */
class SourceMapTest {

    static final Pattern TRACE = Pattern.compile("^\\s*dbgv?\\(\"([^\"]+)\"");

    static String[] lines(String lua) {
        return lua.split("\n", -1);
    }

    /** 1-based line of the first line whose stripped text starts with {@code prefix}. */
    static int line(String lua, String prefix) {
        String[] l = lines(lua);
        for (int i = 0; i < l.length; i++) {
            if (l[i].strip().startsWith(prefix)) {
                return i + 1;
            }
        }
        throw new AssertionError("no line starts with " + prefix + " in\n" + lua);
    }

    static String key(SourceMap map, int line) {
        SourceMap.Location l = map.at(line);
        return l == null ? null : l.key();
    }

    @Test
    void everyTraceCallSitsOnTheNodeItTraces() {
        for (Map.Entry<String, GraphSamples.Sample> e : GraphSamples.all().entrySet()) {
            CompiledGraph c = e.getValue().compile(true);
            String[] l = lines(c.lua());
            int traced = 0;
            for (int i = 0; i < l.length; i++) {
                Matcher m = TRACE.matcher(l[i]);
                if (m.find()) {
                    traced++;
                    assertEquals(m.group(1), key(c.sourceMap(), i + 1), e.getKey() + " line " + (i + 1) + ": " + l[i]);
                }
            }
            assertTrue(traced > 0, e.getKey());
        }
    }

    @Test
    void codeLinesMapToTheNodesThatWroteThem() {
        CompiledGraph c = GraphSamples.counter().compile(false);
        String lua = c.lua();
        SourceMap map = c.sourceMap();
        assertEquals("count", key(map, line(lua, "v_clicks = (v_clicks or 0) + 1")));
        assertEquals("enough", key(map, line(lua, "local t_enough_result")));
        assertEquals("br", key(map, line(lua, "if t_enough_result then")));
        assertEquals("fade", key(map, line(lua, "ui.await(ui.tween(")));
        assertEquals("req", key(map, line(lua, "ui.request(")));
        assertEquals("ping", key(map, line(lua, "local result, err = ui.await(ui.action(")));
        assertEquals("close", key(map, line(lua, "ui.close()")));
        assertEquals("on_resume", key(map, line(lua, "H.on_resume = function(ev)")));
        assertEquals("on_resume", key(map, line(lua, "ui.get(\"resume\"):on(\"click\", H.on_resume)")));
        assertEquals("opened", key(map, line(lua, "ui.async(H.opened)")));
        assertEquals("frame", key(map, line(lua, "H.frame(dt)")));

        SourceMap.Location ret = c.sourceMap().at(line(lua, "ui.log(\"bye\")"));
        assertEquals(new SourceMap.Location("counter", "", "log", "ui:log"), ret);
        assertEquals("counter#log", ret.toString());
    }

    @Test
    void functionBodiesMapWithTheirFunction() {
        CompiledGraph c = GraphSamples.functions().compile(false);
        String lua = c.lua();
        SourceMap.Location mul = c.sourceMap().at(line(lua, "local t_mul_result"));
        assertEquals(new SourceMap.Location("functions", "double", "mul", "ui:math"), mul);
        assertEquals("fn:double/mul", mul.key());
        assertEquals("functions#fn:double/mul", mul.toString());
        assertEquals("fn:classify/hi", key(c.sourceMap(), line(lua, "do return 1, \"high\" end")));
        assertEquals("fn:classify/entry", key(c.sourceMap(), line(lua, "F.classify = function(a_score)")));
        assertEquals("rank", key(c.sourceMap(), line(lua, "local exit, label = F.classify(")));
    }

    @Test
    void scaffoldingMapsToNoNode() {
        CompiledGraph c = GraphSamples.counter().compile(false);
        String lua = c.lua();
        SourceMap map = c.sourceMap();
        for (int i = 1; i <= line(lua, "local v_online = false"); i++) {
            assertNull(map.at(i), "header and variables, line " + i);
        }
        assertNull(map.at(line(lua, "function G.on_open()")));
        assertNull(map.at(line(lua, "function G.update(dt)")));
        assertNull(map.at(line(lua, "function G.on_close()")));
        assertNull(map.at(line(lua, "return G")));
        assertNull(map.at(0));
        assertNull(map.at(lines(lua).length + 1));
        assertEquals("counter", map.graph());
    }

    @Test
    void lineOfFindsANodesLines() {
        CompiledGraph c = GraphSamples.functions().compile(false);
        SourceMap map = c.sourceMap();
        String lua = c.lua();
        int show = map.lineOf("", "show");
        assertTrue(show > 0);
        assertEquals("show", key(map, show));
        assertTrue(show <= line(lua, "ui.get(\"status\"):setText(o_rank_label)"));
        int hi = map.lineOf("classify", "hi");
        assertEquals("fn:classify/hi", key(map, hi));
        assertEquals(0, map.lineOf("", "hi"), "hi lives in function classify, not in the event graph");
        assertEquals(0, map.lineOf("", "nope"));
        assertEquals(0, map.lineOf("nope", "entry"));
    }

    @Test
    void lineOfIsTheLineAfterTheMarker() {
        CompiledGraph c = GraphSamples.counter().compile(false);
        int count = c.sourceMap().lineOf("", "count");
        assertFalse(lines(c.lua())[count - 1].strip().startsWith(LuaWriter.MARKER), lines(c.lua())[count - 1]);
    }

    @Test
    void aResetMarkerEndsTheNode() {
        String lua = """
            local G = {}
            -- @node a ui:log
            ui.log(1)
              -- @node fn:f/b ui:math
              local x = 1
            -- @node -
            return G""";
        SourceMap map = SourceMap.parse("g", lua);
        assertNull(map.at(1));
        assertEquals(new SourceMap.Location("g", "", "a", "ui:log"), map.at(3));
        assertEquals(new SourceMap.Location("g", "f", "b", "ui:math"), map.at(5), "indented markers count");
        assertNull(map.at(6));
        assertNull(map.at(7));
        assertEquals(3, map.lineOf("", "a"), "the first code line, not the marker");
        assertEquals(5, map.lineOf("f", "b"));
    }

    @Test
    void aCachedChunkMapsExactlyLikeAFreshOne() {
        for (Map.Entry<String, GraphSamples.Sample> e : GraphSamples.all().entrySet()) {
            for (boolean debug : new boolean[]{false, true}) {
                CompiledGraph c = e.getValue().compile(debug);
                assertNotNull(c.sourceMap());
                SourceMap cached = SourceMap.parse(c.graphId(), new String(c.lua().getBytes()));
                int n = lines(c.lua()).length;
                for (int i = 0; i <= n + 1; i++) {
                    assertEquals(c.sourceMap().at(i), cached.at(i), e.getKey() + " line " + i);
                }
            }
        }
    }

    @Test
    void markersNeverStackUp() {
        for (Map.Entry<String, GraphSamples.Sample> e : GraphSamples.all().entrySet()) {
            for (boolean debug : new boolean[]{false, true}) {
                String[] l = lines(e.getValue().compile(debug).lua());
                for (int i = 1; i < l.length; i++) {
                    boolean marker = l[i].strip().startsWith(LuaWriter.MARKER);
                    boolean previous = l[i - 1].strip().startsWith(LuaWriter.MARKER);
                    assertFalse(marker && previous, e.getKey() + (debug ? " (debug)" : "") + " lines " + i + "-" + (i + 1)
                        + ": " + l[i - 1] + " / " + l[i]);
                }
            }
        }
    }

    @Test
    void aMarkerOnlyAppearsWhenTheOwnerChanges() {
        for (Map.Entry<String, GraphSamples.Sample> e : GraphSamples.all().entrySet()) {
            String[] l = lines(e.getValue().compile(false).lua());
            String last = "-";
            for (String s : l) {
                String t = s.strip();
                if (t.startsWith(LuaWriter.MARKER)) {
                    String loc = t.substring(LuaWriter.MARKER.length()).split("\\s+")[0];
                    assertFalse(loc.equals(last), e.getKey() + ": repeated marker " + t);
                    last = loc;
                }
            }
        }
    }
}
