package com.stonebreak.rendering.vram;

import com.openmason.engine.cearl.CearlProgram;
import com.openmason.engine.vram.VramArenaPolicy;
import com.openmason.engine.vram.VramPlans;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped {@code cearl/stonebreak.CEARL} program: it must compile, its
 * budget must derive from detected VRAM with the 6 GiB min-spec floor as the
 * fallback, its arenas must carry the tuned minimal-allocation policy
 * (x1.5 growth / 12.5% reserve / 40% trim over the proven initial sizes),
 * and every failure path must leave the builtin plan active.
 */
class CearlBootstrapTest {

    private static final long GIB = 1L << 30;

    @AfterEach
    void cleanUp() {
        System.clearProperty("stonebreak.cearl");
        VramPlans.reset();
    }

    @Test
    void bundledProgramCompilesWithTunedArenaPolicy() {
        CearlProgram program = CearlBootstrap.install(0);
        assertNotNull(program, "the shipped stonebreak.CEARL must compile");
        assertEquals("stonebreak", VramPlans.active().name());

        // Initial sizes come from the chunk footprint lab (a dense 8x8 region's
        // pulled-quad atlas ≈ 1.3 MiB); growth is tightened and trim enabled —
        // the minimal-allocation posture.
        VramArenaPolicy chunk = VramPlans.arena(VramPlans.POOL_CHUNK_MESH);
        assertEquals(1280L * 1024, chunk.vertexInitialBytes());
        assertEquals(24 * 1024 * 2L, chunk.indexInitialBytes());
        // Water and stamp regions have their own, smaller first allocations and
        // inherit the tuned growth/trim/sparse policy.
        VramArenaPolicy water = VramPlans.arena(VramPlans.POOL_CHUNK_WATER);
        assertEquals(320L * 1024, water.vertexInitialBytes());
        assertEquals(24L * 1024, water.indexInitialBytes());
        assertTrue(water.sparseGrowth());
        assertEquals(0.4, water.trimFraction(), 1e-9);
        VramArenaPolicy stamp = VramPlans.arena(VramPlans.POOL_CHUNK_STAMP);
        assertEquals(1024L * 1024, stamp.vertexInitialBytes());
        assertEquals(64L * 1024, stamp.indexInitialBytes());
        assertEquals(1.5, chunk.growthFactor());
        assertEquals(0.125, chunk.growthReserve());
        assertEquals(0.4, chunk.trimFraction(), 1e-9);
        assertTrue(chunk.sparseGrowth(), "chunk_mesh declares grow sparse");
        assertTrue(VramPlans.arena(VramPlans.POOL_LOD_TERRAIN).sparseGrowth(),
            "LOD pools inherit grow sparse from chunk_mesh");

        // LOD pools inherit the tuned policy, overriding only their sizes.
        VramArenaPolicy lod = VramPlans.arena(VramPlans.POOL_LOD_TERRAIN);
        assertEquals(128 * 1024 * 40L, lod.vertexInitialBytes());
        assertEquals(192 * 1024 * 2L, lod.indexInitialBytes());
        assertEquals(1.5, lod.growthFactor());
        assertEquals(0.4, lod.trimFraction(), 1e-9);
        assertEquals(32 * 1024 * 40L,
            VramPlans.arena(VramPlans.POOL_LOD_WATER).vertexInitialBytes());
        assertEquals(48 * 1024 * 2L,
            VramPlans.arena(VramPlans.POOL_LOD_WATER).indexInitialBytes());

        assertEquals(8L << 20, VramPlans.budgetBytes(VramPlans.POOL_STAGING, 0));
    }

    @Test
    void budgetDerivesFromDetectedVramWithMinSpecFallback() {
        // Detection failed: the 6 GiB min-spec floor carries the budget (75%).
        CearlBootstrap.install(0);
        assertEquals(6 * GIB * 3 / 4, VramPlans.active().deviceBudgetBytes());

        // A bigger card reports honestly instead of pretending to be the floor.
        CearlBootstrap.install(8 * GIB);
        assertEquals(8 * GIB * 3 / 4, VramPlans.active().deviceBudgetBytes());
    }

    @Test
    void smallCardGuardShrinksTheLodBudget() {
        CearlBootstrap.install(2 * GIB);
        long budget = VramPlans.active().deviceBudgetBytes();
        assertEquals(2 * GIB * 3 / 4, budget);
        // vram 2 GiB < 6 GiB floor trips the guard: lod_terrain 20% -> 10%.
        assertEquals((long) (budget * 0.10),
            VramPlans.active().pool(VramPlans.POOL_LOD_TERRAIN).budgetBytes());

        CearlBootstrap.install(8 * GIB);
        assertEquals((long) (VramPlans.active().deviceBudgetBytes() * 0.20),
            VramPlans.active().pool(VramPlans.POOL_LOD_TERRAIN).budgetBytes());
    }

    @Test
    void planFileIsPlanOnly() {
        // Single responsibility: device code lives with its owning system
        // (the culler's kernel is an ENGINE resource, pinned by
        // MmsGpuCullerKernelTest) — the game file carries only the plan.
        CearlProgram program = CearlBootstrap.install(0);
        assertTrue(program.kernels().isEmpty(),
            "stonebreak.CEARL should declare no kernels — those belong to their systems");
        assertNotNull(program.plan());
    }

    @Test
    void offSwitchKeepsBuiltin() {
        System.setProperty("stonebreak.cearl", "off");
        assertNull(CearlBootstrap.install(0));
        assertEquals("builtin", VramPlans.active().name());
    }

    @Test
    void unreadablePlanFileFallsBackToBuiltin() {
        System.setProperty("stonebreak.cearl", "/no/such/file.CEARL");
        assertNull(CearlBootstrap.install(0));
        assertEquals("builtin", VramPlans.active().name());
    }
}
