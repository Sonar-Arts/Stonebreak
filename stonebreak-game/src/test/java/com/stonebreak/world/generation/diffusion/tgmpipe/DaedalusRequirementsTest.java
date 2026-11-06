package com.stonebreak.world.generation.diffusion.tgmpipe;

import com.stonebreak.world.generation.diffusion.tgmpipe.DaedalusRequirements.SystemSpecs;
import com.stonebreak.world.generation.diffusion.tgmpipe.DaedalusRequirements.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaedalusRequirementsTest {

    /** The reference dev laptop: meets the RAM and CPU floors, fails on VRAM. */
    private static final SystemSpecs DEV_LAPTOP =
            new SystemSpecs("NVIDIA GeForce RTX 3050 Ti Laptop GPU", 4096, 16_179, 8);

    @Test
    void devLaptopIsBelowTheBarOnVramAlone() {
        Verdict v = DaedalusRequirements.evaluate(DEV_LAPTOP);
        assertFalse(v.met());
        assertTrue(v.reason().contains("4096 MiB of VRAM"), v.reason());
        assertTrue(v.reason().contains("experimental"), v.reason());
        assertFalse(v.reason().contains("GiB of RAM"), v.reason());
        assertFalse(v.reason().contains("logical CPUs"), v.reason());
    }

    @Test
    void aBiggerGpuOnTheSameMachineMeetsThem() {
        Verdict v = DaedalusRequirements.evaluate(new SystemSpecs("NVIDIA GeForce RTX 4070", 12_282, 16_179, 8));
        assertTrue(v.met());
        assertNull(v.reason());
    }

    @Test
    void noNvidiaGpuIsBelowTheBar() {
        Verdict v = DaedalusRequirements.evaluate(new SystemSpecs(null, -1, 32_768, 16));
        assertFalse(v.met());
        assertTrue(v.reason().contains("no NVIDIA GPU"), v.reason());
    }

    @Test
    void everyMissingRequirementIsListed() {
        Verdict v = DaedalusRequirements.evaluate(new SystemSpecs("NVIDIA GeForce RTX 4070", 12_282, 8_000, 4));
        assertFalse(v.met());
        assertTrue(v.reason().contains("GiB of RAM"), v.reason());
        assertTrue(v.reason().contains("4 logical CPUs"), v.reason());
    }

    @Test
    void unknownRamIsNotHeldAgainstTheMachine() {
        assertTrue(DaedalusRequirements.evaluate(new SystemSpecs("NVIDIA GeForce RTX 4070", 12_282, -1, 8)).met());
    }

    @Test
    void picksTheServiceGpuFromNvidiaSmi() {
        assertArrayEquals(new String[] {"NVIDIA GeForce RTX 3050 Ti Laptop GPU", "4096"},
                DaedalusRequirements.pickGpu(List.of("NVIDIA GeForce RTX 3050 Ti Laptop GPU, 4096")));
        // Two GPUs: the service runs on the second, leaving GPU 0 to the game.
        assertArrayEquals(new String[] {"NVIDIA RTX A2000", "6138"},
                DaedalusRequirements.pickGpu(List.of("NVIDIA GeForce RTX 4090, 24564", "NVIDIA RTX A2000, 6138")));
        assertNull(DaedalusRequirements.pickGpu(List.of()));
        assertNull(DaedalusRequirements.pickGpu(List.of("NVIDIA-SMI has failed because it couldn't communicate")));
    }
}
