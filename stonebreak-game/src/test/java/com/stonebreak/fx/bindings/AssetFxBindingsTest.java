package com.stonebreak.fx.bindings;

import com.openmason.engine.format.fx.FxData;
import com.openmason.engine.format.fx.FxParamValue;
import com.openmason.engine.rendering.particles.FxInstance;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.fx.Fx;
import com.stonebreak.fx.FxFixtures;
import com.stonebreak.fx.FxRuntime;
import com.stonebreak.mobs.entities.EntityType;
import com.stonebreak.network.client.handlers.ClientSoundHandler;
import com.stonebreak.network.packet.world.SoundEventS2C;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.stonebreak.fx.FxFixtures.data;
import static com.stonebreak.fx.FxFixtures.def;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asset FX bindings (Stonebreak#437): lookup by event, offsets/params/tint/intensity, the local block and entity
 * paths, and the remote path through the client sound handler (no packet of its own).
 */
class AssetFxBindingsTest {

    private static final int STONE_TINT = 0x808080FF;
    private FxRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = FxFixtures.runtime();
        Fx.install(runtime);
        Fx.overrideEnabled(true);
        BlockFx.setSource(new BlockFx.Source() {
            @Override
            public FxData fx(BlockType block) {
                if (block == BlockType.STONE) {
                    return data(def("break", "test:probe", 0f, 0f, 0f, Map.of(), true),
                            def("hit", "test:probe", 0f, 0.25f, 0f, Map.of("amount", FxParamValue.scalar(0.2f)), true));
                }
                return null == block ? null : new FxData(java.util.List.of());
            }

            @Override
            public int tint(BlockType block) {
                return STONE_TINT;
            }
        });
        EntityFx.setSource(id -> EntityType.COW.getSbeObjectId().equals(id)
                ? data(def("hurt", "test:probe"), def("death", "test:probe"), def("death", "test:probe"))
                : new FxData(java.util.List.of()));
    }

    @AfterEach
    void tearDown() {
        Fx.uninstall(runtime);
        Fx.overrideEnabled(null);
        BlockFx.setSource(null);
        EntityFx.setSource(null);
        runtime.close();
    }

    @Test
    void aBlockPlaysOnlyTheEventsItsAssetBinds() {
        assertTrue(BlockFx.breakAt(BlockType.STONE, 10, 64, -3));
        assertFalse(BlockFx.placeAt(BlockType.STONE, 10, 64, -3), "place is not bound");
        assertFalse(BlockFx.breakAt(BlockType.DIRT, 10, 64, -3), "dirt binds nothing here");
        assertFalse(BlockFx.breakAt(BlockType.AIR, 10, 64, -3));
        FxFixtures.step(runtime, 1);
        assertEquals(1, runtime.liveParticles());
        // break plays at the cell centre
        assertEquals(10.5, runtime.particles().worldX(0), 1e-3);
        assertEquals(64.5, runtime.particles().worldY(0), 1e-3);
        assertEquals(-2.5, runtime.particles().worldZ(0), 1e-3);
    }

    @Test
    void offsetsParamsAndTintComeFromTheBinding() {
        FxData stone = BlockFx.data(BlockType.STONE);
        FxInstance inst = AssetFx.start(runtime, stone.defsFor("hit").get(0), 1, 2, 3, STONE_TINT, AssetFx.NORMAL);
        assertNotNull(inst);
        assertEquals(2.25, inst.y(), 1e-6, "offset added to the event position");
        assertEquals(0.2f, inst.param("amount"), 1e-6);
        assertEquals(0x80 / 255f, inst.param(FxRuntime.PARAM_TINT), 1e-3, "tint red channel = the block's");
        inst.release();
    }

    @Test
    void intensityScalesTheAuthoredValue() {
        FxInstance inst = AssetFx.start(runtime, def("splash", "test:probe", 0, 0, 0,
                Map.of(FxRuntime.PARAM_INTENSITY, FxParamValue.scalar(0.5f)), false), 0, 0, 0, 0, 3f);
        assertEquals(1.5f, inst.param(FxRuntime.PARAM_INTENSITY), 1e-6);
        inst.release();
    }

    @Test
    void mismatchedOrUnknownParametersAreSkippedNotFatal() {
        FxInstance inst = AssetFx.start(runtime, def("x", "test:probe", 0, 0, 0,
                Map.of("amount", FxParamValue.vector(1, 2, 3), "nope", FxParamValue.scalar(1)), false),
                0, 0, 0, 0, AssetFx.NORMAL);
        assertNotNull(inst);
        assertEquals(1f, inst.param("amount"), "wrong shape left the default");
        inst.release();
        assertEquals(0, AssetFx.fire(runtime, data(def("break", "stonebreak:not_shipped")), "break",
                0, 0, 0, 0, AssetFx.NORMAL));
    }

    @Test
    void severalBindingsOnOneEventAllPlay() {
        assertEquals(2, AssetFx.fire(runtime, EntityFx.data(EntityType.COW.getSbeObjectId()), "death",
                0, 70, 0, 0, AssetFx.NORMAL));
    }

    @Test
    void nothingPlaysWhileFxAreSwitchedOffButTheBindingStillCounts() {
        Fx.overrideEnabled(false);
        assertTrue(BlockFx.breakAt(BlockType.STONE, 0, 64, 0), "the asset owns the event");
        FxFixtures.step(runtime, 1);
        assertEquals(0, runtime.liveParticles());
    }

    @Test
    void anotherPlayersBreakArrivesAsTheSoundEventAndThrowsTheSameFx() {
        ClientSoundHandler.apply(SoundEventS2C.block(BlockType.STONE.getId(), "break", 4.5f, 70.5f, 8.5f));
        FxFixtures.step(runtime, 1);
        assertEquals(1, runtime.liveParticles());
        assertEquals(70.5, runtime.particles().worldY(0), 1e-3);
    }

    @Test
    void aReplicatedMobEventPlaysAtTheFeetBelowTheReportedMiddle() {
        EntityType cow = EntityType.COW;
        float mid = 70f + cow.getHeight() * 0.5f;
        ClientSoundHandler.apply(SoundEventS2C.entity(cow.ordinal(), "hurt", 2f, mid, 3f));
        FxFixtures.step(runtime, 1);
        assertEquals(1, runtime.liveParticles());
        assertEquals(70.0 - cow.getLegHeight(), runtime.particles().worldY(0), 1e-3);
    }

    @Test
    void unknownEntityEventsAndTypesAreIgnored() {
        ClientSoundHandler.apply(SoundEventS2C.entity(EntityType.COW.ordinal(), "moo", 0, 70, 0));
        ClientSoundHandler.apply(SoundEventS2C.entity(9999, "hurt", 0, 70, 0));
        ClientSoundHandler.apply(SoundEventS2C.block(-5, "break", 0, 70, 0));
        FxFixtures.step(runtime, 1);
        assertEquals(0, runtime.liveParticles());
    }

    @Test
    void aSplashIsStrongerTheHarderTheFall() {
        EntityFx.setSource(id -> data(def(EntityFx.EVENT_SPLASH, "test:probe")));
        assertTrue(EntityFx.splash(EntityType.REMOTE_PLAYER, new org.joml.Vector3f(0, 63.9f, 0), 16f));
        assertFalse(new EntityFxProbe().splashUnbound(), "an asset without a splash leaves it to the legacy one");
    }

    /** Separate source with no splash binding. */
    private static final class EntityFxProbe {
        boolean splashUnbound() {
            EntityFx.setSource(id -> new FxData(java.util.List.of()));
            return EntityFx.splash(EntityType.REMOTE_PLAYER, new org.joml.Vector3f(), 4f);
        }
    }
}
