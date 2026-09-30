package com.stonebreak.world.chunk.api.mightyMesh.mmsIntegration;

import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The native {@code ck_mesh_chunk} kernel is compiled for a 16×256×16 chunk and
 * CLAMPS {@code max_y} to 255 rather than reporting that it could not do the
 * job. {@code MmsCcoAdapter} treats a successful kernel call as authoritative
 * and skips the Java cube loop, so on this 1024-tall world the entire surface
 * (sea level 320) went unmeshed while Java-side special cells still drew.
 *
 * <p>If someone rebuilds the kernel for the taller column, raise
 * {@link CendaMesher#KERNEL_WORLD_HEIGHT} to match — that is what re-enables the
 * native path, and this test is the reminder that the two must move together.
 */
class CendaMesherHeightContractTest {

    @Test
    void theNativeMesherIsDeclinedWhileTheWorldOutgrowsTheKernel() {
        if (WorldConfiguration.WORLD_HEIGHT <= CendaMesher.KERNEL_WORLD_HEIGHT) {
            // Kernel covers the column; nothing to guard against.
            return;
        }
        assertFalse(CendaMesher.enabled(),
            "WORLD_HEIGHT " + WorldConfiguration.WORLD_HEIGHT + " exceeds the kernel's "
                + CendaMesher.KERNEL_WORLD_HEIGHT + "; the native mesher would silently "
                + "truncate every column at y=255");
    }

    @Test
    void theKernelContractConstantMatchesTheNativeSource() {
        // mesher.cpp: `constexpr int WH = 256;` and `if (max_y >= WH) max_y = WH - 1;`
        assertTrue(CendaMesher.KERNEL_WORLD_HEIGHT == 256,
            "update this alongside cenda/native/kernels/src/mesher.cpp");
    }
}
