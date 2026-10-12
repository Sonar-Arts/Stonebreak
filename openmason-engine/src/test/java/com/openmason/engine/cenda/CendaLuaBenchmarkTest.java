package com.openmason.engine.cenda;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * #283 Lua feasibility measurements: state lifecycle, FFM crossing costs,
 * the #292 minigame frame with its garbage, hook overhead, coroutines and
 * per-instance memory (one state per document vs shared state + envs).
 *
 * <p>Always runs as a quick smoke pass (tiny iteration counts, statuses only)
 * so the suite gains no skip. {@code -Dcenda.bench=true} runs the real
 * measurement and writes {@code target/cenda-lua-bench.md}:
 * <pre>
 *   mvn -q test -pl openmason-engine -Dtest=CendaLuaBenchmarkTest -Dcenda.bench=true
 * </pre>
 */
@Tag("regression")
class CendaLuaBenchmarkTest {

    private static final boolean FULL = Boolean.getBoolean("cenda.bench");
    private static final int SCALE = FULL ? 1 : 1000;

    private final StringBuilder report = new StringBuilder();

    @Test
    void measure() throws IOException {
        assumeTrue(CendaLua.isAvailable(), "Cenda library with the Lua host not built");
        header();
        stateLifecycle();
        instanceMemory();
        javaToLua();
        luaToJava();
        minigameFrames();
        coroutines();
        minimumStateSize();
        if (FULL) {
            Path out = Path.of("target", "cenda-lua-bench.md");
            Files.createDirectories(out.getParent());
            Files.writeString(out, report);
            System.out.println(report);
        }
    }

    // ───────────────────────────── sections ─────────────────────────────

    private void header() {
        line("# Cenda Lua host — measurements");
        line("");
        line("- Runtime: " + CendaLua.luaRelease() + ", Lua-host ABI " + CendaLua.EXPECTED_ABI
            + ", JDK " + System.getProperty("java.vm.version") + " (" + System.getProperty("java.vm.name") + ")");
        line("- GC: " + ManagementFactory.getGarbageCollectorMXBeans().stream()
            .map(b -> b.getName()).reduce((a, b) -> a + ", " + b).orElse("?"));
        line("- Platform: " + System.getProperty("os.name") + " " + System.getProperty("os.version")
            + " " + System.getProperty("os.arch") + ", " + cpuModel());
        line("- Mode: " + (FULL ? "full" : "smoke") + "; times are best-of-runs unless marked p99");
        line("");
    }

    private void stateLifecycle() {
        int n = Math.max(2, 2000 / SCALE);
        for (int i = 0; i < n; i++) {
            CendaLua.newState(0).close();
        }
        long t0 = System.nanoTime();
        long used = 0;
        for (int i = 0; i < n; i++) {
            try (LuaState lua = CendaLua.newState(0)) {
                used = lua.memUsed();
            }
        }
        double us = (System.nanoTime() - t0) / 1e3 / n;
        line("## State lifecycle");
        line("");
        line(String.format(Locale.ROOT, "- create + sandbox libs + close: **%.1f µs**; Lua heap of a fresh state: **%,d B**", us, used));
        line("");
    }

    private static final String CODE_BEHIND = """
        local M = {}
        local state = { hovered = 0, online = false, labels = {} }
        local names = {"resume", "statistics", "glossary", "settings", "resync", "quit"}
        for i, n in ipairs(names) do state.labels[n] = string.upper(n:sub(1, 1)) .. n:sub(2) end
        function on_open(online) state.online = online ~= 0 return #names end
        function on_hover(slot) state.hovered = slot return slot end
        function on_click(slot)
          if slot == 1 then return 1 elseif slot == #names then return 2 end
          return 0
        end
        function update(dt)
          state.t = (state.t or 0) + dt
          return state.t
        end
        function label(i) return #state.labels[names[i]] end
        """;

    private void instanceMemory() {
        int instances = Math.max(2, 64 / Math.min(SCALE, 32));
        long separate = 0;
        long separateUs = 0;
        LuaState[] states = new LuaState[instances];
        long t0 = System.nanoTime();
        for (int i = 0; i < instances; i++) {
            states[i] = CendaLua.newState(0);
            check(states[i], states[i].run(CODE_BEHIND, "pause.lua", 0));
        }
        separateUs = (System.nanoTime() - t0) / 1000;
        for (LuaState s : states) {
            s.gcCollect();
            separate += s.memUsed();
            s.close();
        }

        long sharedBase;
        long shared;
        long sharedUs;
        try (LuaState lua = CendaLua.newState(0)) {
            lua.gcCollect();
            sharedBase = lua.memUsed();
            t0 = System.nanoTime();
            for (int i = 0; i < instances; i++) {
                int env = lua.newEnv();
                check(lua, lua.run(CODE_BEHIND, "pause.lua", env));
            }
            sharedUs = (System.nanoTime() - t0) / 1000;
            shared = lua.gcCollect();
        }
        line("## Per-instance memory (" + instances + " instances of a pause-menu code-behind)");
        line("");
        line("| Model | Lua heap total | Per instance | Setup time per instance |");
        line("| --- | --- | --- | --- |");
        line(String.format(Locale.ROOT, "| One `lua_State` per instance | %,d B | %,d B | %.1f µs |",
            separate, separate / instances, separateUs / (double) instances));
        line(String.format(Locale.ROOT, "| Shared state + per-instance env | %,d B | %,d B (+%,d B base) | %.1f µs |",
            shared, (shared - sharedBase) / instances, sharedBase, sharedUs / (double) instances));
        line("");
    }

    private void javaToLua() {
        try (LuaState lua = CendaLua.newState(0)) {
            check(lua, lua.run("function nop() end function add3(a, b, c) return a + b + c end"));
            int nop = lua.refFunction(0, "nop");
            int add3 = lua.refFunction(0, "add3");
            int n = Math.max(10, 2_000_000 / SCALE);
            Crossing c0 = time(n, () -> lua.call(nop, 0, 0));
            lua.setArg(0, 1);
            lua.setArg(1, 2);
            lua.setArg(2, 3);
            Crossing c3 = time(n, () -> lua.call(add3, 3, 1));
            lua.setBudget(1_000_000);
            Crossing hooked = time(n, () -> lua.call(add3, 3, 1));
            line("## Java → Lua (`cl_call` downcall + `lua_pcall`)");
            line("");
            line("| Call | Time per call | Java garbage per call |");
            line("| --- | --- | --- |");
            line(c0.row("`nop()`"));
            line(c3.row("`add3(a, b, c)` → 1 result"));
            line(hooked.row("`add3` with instruction budget armed"));
            line("");
        }
    }

    private void luaToJava() {
        try (LuaState lua = CendaLua.newState(0)) {
            double[] sink = new double[1];
            lua.register(0, "hostnop", call -> 0);
            lua.register(0, "hostadd", call -> {
                call.result(0, call.arg(0) + call.arg(1));
                return 1;
            });
            check(lua, lua.run("""
                local function luanop() end
                function base(n) for i = 1, n do luanop() end end
                function up0(n) for i = 1, n do hostnop() end end
                function up2(n) local s = 0 for i = 1, n do s = s + hostadd(i, 1) end return s end
                """));
            int n = Math.max(10, 2_000_000 / SCALE);
            int base = lua.refFunction(0, "base");
            int up0 = lua.refFunction(0, "up0");
            int up2 = lua.refFunction(0, "up2");
            Crossing loop = timeBatch(n, () -> sink[0] += lua.call1(base, n));
            Crossing c0 = timeBatch(n, () -> sink[0] += lua.call1(up0, n));
            Crossing c2 = timeBatch(n, () -> sink[0] += lua.call1(up2, n));
            line("## Lua → Java (Lua C closure → FFM upcall stub → `LuaHostFunction`)");
            line("");
            line("| Call | Time per call | Java garbage per call |");
            line("| --- | --- | --- |");
            line(loop.row("Lua→Lua function call (baseline)"));
            line(c0.row("`hostnop()`"));
            line(c2.row("`hostadd(i, 1)` → 1 result"));
            line("");
        }
    }

    private static final String GAME = """
        local N, TW = ...
        local W, H, G, DT = 800, 600, 900, 1/144
        local ents = {}
        for i = 1, N do ents[i] = {x = (i * 37) % W, y = (i * 91) % H, vx = (i % 13) * 20 - 120, vy = 0} end
        local function tweens(frame)
          local sum = 0
          for i = 0, TW - 1 do
            local t = (frame * DT + i * 0.01) % 1
            local u = 1 - t
            sum = sum + (1 - u * u * u)
          end
          return sum
        end
        {STEPS}
        """;

    private static final String STEP = """
        function {NAME}(frame)
          local hits, px, py = 0, 400, 300
          for i = 1, #ents do
            local e = ents[i]
            e.vy = e.vy + G * DT
            e.x = e.x + e.vx * DT
            e.y = e.y + e.vy * DT
            if e.x < 0 or e.x > W then e.vx = -e.vx end
            if e.y > H then e.vy = -e.vy * 0.8; e.y = H end
            if math.abs(e.x - px) < 10 and math.abs(e.y - py) < 10 then hits = hits + 1 end
            {EMIT}
          end
          return hits + math.floor(tweens(frame))
        end
        """;

    private void minigameFrames() {
        line("## Minigame frame (#292 workload: N entities + 200 eased tweens, one Java→Lua call per frame)");
        line("");
        line("| Variant | N | Best | p99 | Java garbage / frame | Lua garbage / frame |");
        line("| --- | --- | --- | --- | --- | --- |");
        for (int entities : new int[]{1000, 10_000}) {
            try (LuaState lua = CendaLua.newState(0)) {
                String steps = step("step_compute", "")
                    + step("step_buffer", "draw.emit(e.x, e.y)")
                    + step("step_upcall", "sprite(e.x, e.y)");
                String src = GAME.replace("{STEPS}", steps).replace("local N, TW = ...",
                    "local N, TW = " + entities + ", 200");
                LuaFloatBuffer draw = lua.bindBuffer(0, "draw", entities * 2);
                float[] sinkX = new float[1];
                lua.register(0, "sprite", call -> {
                    sinkX[0] += (float) call.arg(0);
                    return 0;
                });
                check(lua, lua.run(src, "minigame.lua", 0));
                frameRow(lua, "compute only", entities, lua.refFunction(0, "step_compute"), null, 0);
                frameRow(lua, "+ draw list via native buffer", entities, lua.refFunction(0, "step_buffer"), draw, 0);
                if (entities == 1000) {
                    frameRow(lua, "+ draw list via 1 upcall/entity", entities, lua.refFunction(0, "step_upcall"), null, 0);
                    frameRow(lua, "compute, budget hook armed", entities, lua.refFunction(0, "step_compute"), null, 50_000_000);
                    try (LuaWatchdog dog = new LuaWatchdog(500)) {
                        dog.watch(lua, 50);
                        frameRow(lua, "compute, watchdog deadline armed", entities, lua.refFunction(0, "step_compute"), null, 0);
                        dog.unwatch(lua);
                    }
                }
            }
        }
        line("");
        line("Java baseline for the same frame (from #292): 2.1 µs, 0 B. The VM carries the deadline poll"
            + " (two loads + compare per loop back-jump/call) in every row.");
        line("");
    }

    private static String step(String name, String emit) {
        return STEP.replace("{NAME}", name).replace("{EMIT}", emit);
    }

    private void frameRow(LuaState lua, String label, int entities, int fn, LuaFloatBuffer draw, long budget) {
        lua.setBudget(budget);
        int warm = Math.max(3, 3000 / SCALE);
        int frames = Math.max(3, 5000 / SCALE);
        for (int f = 0; f < warm; f++) {
            if (draw != null) {
                draw.reset();
            }
            lua.setArg(0, f);
            check(lua, lua.call(fn, 1, 1));
        }
        long[] samples = new long[frames];
        long javaBefore = allocatedBytes();
        long luaBefore = lua.memAllocated();
        for (int f = 0; f < frames; f++) {
            long t = System.nanoTime();
            if (draw != null) {
                draw.reset();
            }
            lua.setArg(0, f);
            lua.call(fn, 1, 1);
            samples[f] = System.nanoTime() - t;
        }
        long javaGarbage = allocatedBytes() - javaBefore;
        long luaGarbage = lua.memAllocated() - luaBefore;
        if (draw != null) {
            assertEquals(entities * 2, draw.cursor());
        }
        Arrays.sort(samples);
        line(String.format(Locale.ROOT, "| %s | %,d | %.1f µs | %.1f µs | %s | %s |", label, entities,
            samples[0] / 1e3, samples[(int) (frames * 0.99)] / 1e3,
            bytes(javaGarbage / (double) frames), bytes(luaGarbage / (double) frames)));
        lua.setBudget(0);
    }

    private void coroutines() {
        try (LuaState lua = CendaLua.newState(0)) {
            check(lua, lua.run("function job() local r = coroutine.yield(1) return r end"));
            int fn = lua.refFunction(0, "job");
            int n = Math.max(10, 200_000 / SCALE);
            Crossing c = time(n, () -> {
                int th = lua.newThread(fn);
                lua.resume(th, 0, 1);
                lua.closeThread(th);
                lua.unref(th);
            });
            line("## Coroutines");
            line("");
            line("| Operation | Time | Java garbage |");
            line("| --- | --- | --- |");
            line(c.row("create + resume to yield + cancel (`lua_closethread`) + unref"));
            line("");
        }
    }

    private void minimumStateSize() {
        long lo = 1024;
        long hi = 1 << 20;
        while (lo < hi) {
            long mid = (lo + hi) >>> 1;
            boolean ok;
            try {
                CendaLua.newState(mid).close();
                ok = true;
            } catch (IllegalStateException e) {
                ok = false;
            }
            if (ok) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        line("## Memory cap");
        line("");
        line(String.format(Locale.ROOT, "- Smallest cap that can create a sandboxed state: **%,d B**. "
            + "Over-cap allocations raise `LUA_ERRMEM` (after an emergency GC), never a crash — see `CendaLuaTest`.", lo));
        line("");
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private record Crossing(double nanos, double garbage) {
        String row(String label) {
            return String.format(Locale.ROOT, "| %s | %.1f ns | %s |", label, nanos, bytes(garbage));
        }
    }

    /** n calls of op, best of 5 runs after a warm-up run. */
    private static Crossing time(int n, Runnable op) {
        double best = Double.MAX_VALUE;
        double garbage = 0;
        for (int run = 0; run < 6; run++) {
            long a0 = allocatedBytes();
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                op.run();
            }
            long t = System.nanoTime() - t0;
            long a = allocatedBytes() - a0;
            if (run > 0 && t / (double) n < best) {
                best = t / (double) n;
                garbage = a / (double) n;
            }
        }
        return new Crossing(best, garbage);
    }

    /** op performs n inner operations itself; best of 5 runs after a warm-up. */
    private static Crossing timeBatch(int n, Runnable op) {
        Crossing c = time(1, op);
        return new Crossing(c.nanos / n, c.garbage / n);
    }

    private static void check(LuaState lua, int status) {
        if (status != LuaState.OK) {
            throw new IllegalStateException("Lua status " + status + ": " + lua.lastError());
        }
    }

    private void line(String s) {
        report.append(s).append('\n');
    }

    private static String bytes(double b) {
        return b < 0.5 ? "0 B" : String.format(Locale.ROOT, "%,.1f B", b);
    }

    private static final Method ALLOCATED = allocatedMethod();

    private static Method allocatedMethod() {
        try {
            Method m = Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes");
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static long allocatedBytes() {
        if (ALLOCATED == null) {
            return 0;
        }
        try {
            return (long) ALLOCATED.invoke(ManagementFactory.getThreadMXBean());
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    private static String cpuModel() {
        try {
            return Files.readAllLines(Path.of("/proc/cpuinfo")).stream()
                .filter(l -> l.startsWith("model name")).findFirst()
                .map(l -> l.substring(l.indexOf(':') + 1).trim()).orElse("unknown CPU");
        } catch (IOException | RuntimeException e) {
            return "unknown CPU";
        }
    }
}
