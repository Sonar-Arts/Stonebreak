package com.stonebreak.rendering.lighting;

import com.openmason.engine.rendering.shadow.PointShadowProjection;
import com.openmason.engine.util.BlockPos;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.anim.AnimatedBlockRegistry;
import com.stonebreak.blocks.furnace.FurnaceState;
import com.stonebreak.blocks.furnace.FurnaceStateRegistry;
import com.stonebreak.crafting.SmeltingManager;
import com.stonebreak.world.World;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Lit furnaces join the torch light pipeline through {@link BlockLightSource} (issue #309). */
class BlockLightSourceTest {
    private static final BlockPos FURNACE = new BlockPos(10, 64, -3);

    private World world;
    private FurnaceStateRegistry furnaces;

    @BeforeEach
    void setUp() {
        world = mock(World.class);
        furnaces = new FurnaceStateRegistry(null);
        when(world.getFurnaceRegistry()).thenReturn(furnaces);
        when(world.getAnimatedBlockRegistry()).thenReturn(new AnimatedBlockRegistry());
        when(world.getBlockAt(anyInt(), anyInt(), anyInt())).thenReturn(BlockType.AIR);
        when(world.getBlockAt(FURNACE.x(), FURNACE.y(), FURNACE.z())).thenReturn(BlockType.FURNACE);
    }

    private FurnaceState furnaceBurning(int ticks) {
        FurnaceState state = furnaces.getOrCreate(FURNACE);
        state.applyStateString("furnace:state=Lit;burn=" + ticks + ";burnTotal=200");
        return state;
    }

    @Test
    void blockTypesMapToTheirSource() {
        assertSame(BlockLightSource.TORCH, BlockLightSource.of(BlockType.TORCH_PLACED));
        assertSame(BlockLightSource.FURNACE, BlockLightSource.of(BlockType.FURNACE));
        assertNull(BlockLightSource.of(BlockType.STONE));
        assertNull(BlockLightSource.of(null));
    }

    @Test
    void onlyALitFurnaceEmits() {
        furnaces.getOrCreate(FURNACE);
        assertFalse(BlockLightSource.FURNACE.isEmitting(world, FURNACE), "unlit furnace is dark");
        furnaceBurning(40);
        assertTrue(BlockLightSource.FURNACE.isEmitting(world, FURNACE));
        assertFalse(BlockLightSource.TORCH.isEmitting(world, FURNACE), "a furnace is not a torch");
    }

    @Test
    void lightGoesOutWhenTheFuelRunsOut() {
        FurnaceState state = furnaceBurning(1);
        assertTrue(BlockLightSource.FURNACE.isEmitting(world, FURNACE));
        assertTrue(state.tick(mock(SmeltingManager.class), 0.05f), "last burn tick flips the lit state");
        assertFalse(BlockLightSource.FURNACE.isEmitting(world, FURNACE));
    }

    @Test
    void staleRegistryEntryOrSealedMouthEmitsNothing() {
        furnaceBurning(40);
        when(world.getBlockAt(FURNACE.x(), FURNACE.y(), FURNACE.z() + 1)).thenReturn(BlockType.STONE);
        assertFalse(BlockLightSource.FURNACE.isEmitting(world, FURNACE), "opaque block over the mouth");
        when(world.getBlockAt(FURNACE.x(), FURNACE.y(), FURNACE.z() + 1)).thenReturn(BlockType.AIR);
        when(world.getBlockAt(FURNACE.x(), FURNACE.y(), FURNACE.z())).thenReturn(BlockType.AIR);
        assertFalse(BlockLightSource.FURNACE.isEmitting(world, FURNACE), "broken furnace with a leftover state");
    }

    @Test
    void emitterSitsInFrontOfTheMouthOutsideTheFurnaceVoxel() {
        Vector3f p = BlockLightSource.FURNACE.emitterPosition(world, FURNACE, new Vector3f());
        assertEquals(FURNACE.x() + 0.5f, p.x, 1e-6f);
        assertTrue(p.y > FURNACE.y() && p.y < FURNACE.y() + 1, "within the mouth's height");
        // The SBO authors the mouth on +Z; the light must be in the air cell in front of it,
        // or the furnace's own voxel occludes it in the shadow and bounce passes.
        assertEquals(FURNACE.z() + 1, (int) Math.floor(p.z));
        assertTrue(p.z - (FURNACE.z() + 1) > PointShadowProjection.NEAR, "clear of the face");
    }

    @Test
    void dynamicLightsSelectsALitFurnaceAndDropsItWhenUnlit() {
        Vector3f camera = new Vector3f(FURNACE.x() + 4, FURNACE.y() + 1, FURNACE.z() + 4);
        furnaces.getOrCreate(FURNACE);
        DynamicLights.update(world, null, camera, 0f);
        assertEquals(0, DynamicLights.count());

        furnaceBurning(40);
        DynamicLights.update(world, null, camera, 0f);
        assertEquals(1, DynamicLights.count());
        assertEquals(BlockLightSource.FURNACE.emitterPosition(world, FURNACE, new Vector3f()),
                DynamicLights.position(0, new Vector3f()));

        DynamicLights.update(world, null, new Vector3f(camera).add(100, 0, 0), 0f);
        assertEquals(0, DynamicLights.count(), "out of range");
    }

    @Test
    void furnaceHasItsOwnWarmFlicker() {
        LightProfile furnace = BlockLightSource.FURNACE.profile();
        assertNotEquals(TorchLight.PROFILE, furnace);
        assertTrue(furnace.red() >= furnace.green() && furnace.green() >= furnace.blue(), "warm");
        for (float t = 0f; t < 10f; t += 0.01f) {
            float i = furnace.intensity(t);
            assertTrue(i >= furnace.floor() - 1e-6f && i <= 1f + 1e-6f, "t=" + t + " i=" + i);
        }
        assertEquals(furnace.intensity(0f), furnace.intensity(3 * furnace.period()), 1e-3f, "periodic");
    }
}
