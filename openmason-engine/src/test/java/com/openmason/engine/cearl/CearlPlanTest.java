package com.openmason.engine.cearl;

import com.openmason.engine.diagnostics.GpuMemoryTracker;
import com.openmason.engine.vram.VramPlan;
import com.openmason.engine.vram.VramPool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plan half of CEARL: pool inheritance ({@code from}), {@code when}
 * guards against the host environment, percent-budget resolution, validation,
 * and pressure rules — all in the native end-block syntax.
 */
class CearlPlanTest {

    private static final String PLAN = """
        plan test
            device
                budget 1 GiB
                headroom 10%
            end

            pool a
                category CHUNK_MESH
                budget 50%
                priority 9
                arena
                    vertex 2 MiB
                    index 256 KiB
                    growth 2.0
                    reserve 10%
                    align 8
                end
            end

            pool b from a
                budget 128 MiB
                arena
                    vertex 1 MiB
                end
            end

            when vram > 0 and vram < 512 MiB
                pool b
                    priority 1
                end
            end

            on pressure > 90%
                shed b, a
            end
        end
        """;

    private static VramPlan compile(String source, long vram) {
        return CearlCompiler.compile(source, "plan.CEARL", Map.of("vram", vram)).plan();
    }

    @Test
    void valuesResolveExactly() {
        VramPlan plan = compile(PLAN, 0);
        assertEquals("test", plan.name());
        assertEquals(1L << 30, plan.deviceBudgetBytes());
        assertEquals((long) ((1L << 30) * 0.9), plan.softBudgetBytes());

        VramPool a = plan.pool("a");
        assertEquals(GpuMemoryTracker.Category.CHUNK_MESH, a.category());
        assertEquals((1L << 30) / 2, a.budgetBytes());
        assertEquals(9, a.priority());
        assertEquals(2L << 20, a.arena().vertexInitialBytes());
        assertEquals(256L << 10, a.arena().indexInitialBytes());
        assertEquals(2.0, a.arena().growthFactor());
        assertEquals(0.10, a.arena().growthReserve(), 1e-9);
        assertEquals(8, a.arena().alignElements());
    }

    @Test
    void inheritanceMergesEverythingButBudget() {
        VramPool b = compile(PLAN, 0).pool("b");
        // Own budget (never inherited — sharing would double-count).
        assertEquals(128L << 20, b.budgetBytes());
        // Inherited from a: category, priority, and the arena fields b left unset.
        assertEquals(GpuMemoryTracker.Category.CHUNK_MESH, b.category());
        assertEquals(9, b.priority());
        assertEquals(1L << 20, b.arena().vertexInitialBytes());   // overridden
        assertEquals(256L << 10, b.arena().indexInitialBytes());  // inherited
        assertEquals(2.0, b.arena().growthFactor());              // inherited
        assertEquals(8, b.arena().alignElements());               // inherited
    }

    @Test
    void whenGuardsReadTheEnvironment() {
        assertEquals(9, compile(PLAN, 0).pool("b").priority());            // vram unknown: off
        assertEquals(1, compile(PLAN, 256L << 20).pool("b").priority());   // small card: on
        assertEquals(9, compile(PLAN, 8L << 30).pool("b").priority());     // big card: off
    }

    @Test
    void pressureRulesShedInOrder() {
        VramPlan plan = compile(PLAN, 0);
        assertEquals(List.of(), plan.shedAt(0.5));
        assertEquals(List.of("b", "a"), plan.shedAt(0.95));
        assertEquals(List.of(), plan.shedAt(Double.NaN));
    }

    @Test
    void planlessProgramHasNullPlan() {
        assertNull(CearlCompiler.compile(
            "kernel k\n    take r: u32[] out\n    r[0] = 1\nend", "k.CEARL", Map.of()).plan());
    }

    // ─── Validation ───────────────────────────────────────────────────────

    private static CearlException fails(String source) {
        return assertThrows(CearlException.class, () -> compile(source, 0));
    }

    @Test
    void percentBudgetNeedsDeviceBudget() {
        CearlException e = fails("""
            plan p
                pool a
                    budget 50%
                end
            end
            """);
        assertTrue(e.getMessage().contains("device budget"), e.getMessage());
    }

    @Test
    void overcommittedBudgetsAreRejected() {
        CearlException e = fails("""
            plan p
                device
                    budget 1 GiB
                end
                pool a
                    budget 60%
                end
                pool b
                    budget 60%
                end
            end
            """);
        assertTrue(e.getMessage().contains("above the device budget"), e.getMessage());
    }

    @Test
    void unknownCategoryListsTheRealOnes() {
        CearlException e = fails("""
            plan p
                pool a
                    category NOPE
                end
            end
            """);
        assertTrue(e.getMessage().contains("CHUNK_MESH"), e.getMessage());
    }

    @Test
    void inheritanceCycleIsNamed() {
        CearlException e = fails("""
            plan p
                pool a from b
                end
                pool b from a
                end
            end
            """);
        assertTrue(e.getMessage().contains("cycle"), e.getMessage());
    }

    @Test
    void shedTargetsMustExist() {
        CearlException e = fails("""
            plan p
                device
                    budget 1 GiB
                end
                pool a
                    budget 10%
                end
                on pressure > 80%
                    shed ghost
                end
            end
            """);
        assertTrue(e.getMessage().contains("ghost"), e.getMessage());
    }

    @Test
    void pressureRuleNeedsBudget() {
        CearlException e = fails("""
            plan p
                pool a
                end
                on pressure > 80%
                    shed a
                end
            end
            """);
        assertTrue(e.getMessage().contains("device budget"), e.getMessage());
    }

    @Test
    void duplicatePoolAndAttributeAreRejected() {
        assertTrue(fails("plan p\n    pool a\n    end\n    pool a\n    end\nend")
            .getMessage().contains("duplicate pool"));
        assertTrue(fails("plan p\n    pool a\n        priority 1\n        priority 2\n    end\nend")
            .getMessage().contains("duplicate"));
    }

    @Test
    void badUnitsAndGrowthTeach() {
        assertTrue(fails("plan p\n    pool a\n        budget 2 GB2\n    end\nend")
            .getMessage().contains("KiB"));
        assertTrue(fails("""
            plan p
                pool a
                    arena
                        vertex 1 MiB
                        index 1 KiB
                        growth 1.0
                    end
                end
            end
            """).getMessage().contains("never grow"));
        assertTrue(fails("""
            plan p
                pool a
                    arena
                        vertex 1 MiB
                        index 1 KiB
                        align 3
                    end
                end
            end
            """).getMessage().contains("power of two"));
    }

    @Test
    void deviceBudgetIsAnExpressionWithOtherwiseFallback() {
        String source = """
            pin FLOOR: i64 = 6 GiB

            plan p
                device
                    budget (vram otherwise FLOOR) * 3 / 4
                end
                pool a
                    budget 10%
                end
            end
            """;
        // Detection failed: the min-spec floor carries the budget.
        assertEquals(6L * (1L << 30) * 3 / 4, compile(source, 0).deviceBudgetBytes());
        // Real hardware wins when known.
        assertEquals(8L * (1L << 30) * 3 / 4,
            compile(source, 8L << 30).deviceBudgetBytes());
    }

    @Test
    void deviceBudgetMustResolveToBytes() {
        CearlException e = fails("""
            plan p
                device
                    budget vram > 0
                end
            end
            """);
        assertTrue(e.getMessage().contains("byte count"), e.getMessage());
    }

    @Test
    void trimParsesInheritsAndValidates() {
        VramPlan plan = compile("""
            plan p
                pool a
                    arena
                        vertex 1 MiB
                        index 64 KiB
                        trim 40%
                    end
                end
                pool b from a
                    arena
                        vertex 2 MiB
                    end
                end
            end
            """, 0);
        assertEquals(0.4, plan.pool("a").arena().trimFraction(), 1e-9);
        assertEquals(0.4, plan.pool("b").arena().trimFraction(), 1e-9); // inherited
        assertEquals(0.0, compile("""
            plan p
                pool a
                    arena
                        vertex 1 MiB
                        index 64 KiB
                    end
                end
            end
            """, 0).pool("a").arena().trimFraction()); // default: off
        assertTrue(fails("""
            plan p
                pool a
                    arena
                        vertex 1 MiB
                        index 64 KiB
                        trim 95%
                    end
                end
            end
            """).getMessage().contains("90%"));
    }

    @Test
    void growSparseFlowsIntoTheArenaPolicyAndInherits() {
        VramPlan plan = compile("""
            plan p
                pool a
                    grow sparse
                    arena
                        vertex 1 MiB
                        index 64 KiB
                    end
                end
                pool b from a
                    arena
                        vertex 2 MiB
                    end
                end
                pool c
                    arena
                        vertex 1 MiB
                        index 64 KiB
                    end
                end
            end
            """, 0);
        assertTrue(plan.pool("a").arena().sparseGrowth());
        assertEquals(VramPool.Grow.SPARSE, plan.pool("a").grow());
        assertTrue(plan.pool("b").arena().sparseGrowth()); // inherited via 'from'
        assertTrue(!plan.pool("c").arena().sparseGrowth()); // default: copy
    }

    @Test
    void pinsFeedWhenGuards() {
        VramPlan plan = compile("""
            pin SMALL: i64 = 4 GiB

            plan p
                device
                    budget 1 GiB
                end
                pool a
                    budget 10%
                end
                when vram < SMALL
                    pool a
                        budget 20%
                    end
                end
            end
            """, 2L << 30);
        assertEquals((long) ((1L << 30) * 0.20), plan.pool("a").budgetBytes());
    }
}
