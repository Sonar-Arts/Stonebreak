package com.stonebreak.rendering.vram;

import com.openmason.engine.cearl.CearlCompiler;
import com.openmason.engine.cearl.CearlException;
import com.openmason.engine.cearl.CearlProgram;
import com.openmason.engine.vram.VramPlans;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Compiles and installs Stonebreak's CEARL program at startup.
 *
 * <p>Loads {@code /cearl/stonebreak.cearl} from the classpath (or the file
 * named by {@code -Dstonebreak.cearl=<path>}; {@code =off} skips CEARL
 * entirely), compiles it with the host environment ({@code vram} = detected
 * dedicated VRAM in bytes, 0 when unknown), and installs the resulting plan
 * into {@link VramPlans} — which the chunk-region arenas, LOD batcher, and
 * staging ring consult when they are created.
 *
 * <p>Must run on the main thread after the GL context exists (for VRAM
 * detection) and <b>before</b> the renderer creates any of those consumers.
 * A plan that fails to compile logs its teaching error and leaves the builtin
 * plan active — a broken plan file can never brick a launch.
 */
public final class CearlBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(CearlBootstrap.class);

    static final String DEFAULT_RESOURCE = "/cearl/stonebreak.CEARL";

    /** GPU_MEMORY_INFO_DEDICATED_VIDMEM_NVX (KB); absent on non-NVIDIA drivers. */
    private static final int GL_DEDICATED_VIDMEM_NVX = 0x9047;

    private static volatile CearlProgram program;

    private CearlBootstrap() {
    }

    /** Startup entry point — detects VRAM from the current GL context. */
    public static void install() {
        install(detectVramBytes());
    }

    /**
     * Compiles and installs with an explicit environment (tests pass 0).
     * Returns the compiled program, or null when CEARL is off or compilation
     * failed (builtin plan active either way).
     */
    static CearlProgram install(long vramBytes) {
        String prop = System.getProperty("stonebreak.cearl", "");
        if ("off".equalsIgnoreCase(prop)) {
            VramPlans.reset();
            program = null;
            logger.info("[CEARL] disabled (-Dstonebreak.cearl=off) — builtin plan active");
            return null;
        }

        String source;
        String sourceName;
        try {
            if (!prop.isEmpty()) {
                source = Files.readString(Path.of(prop));
                sourceName = prop;
            } else {
                try (InputStream in = CearlBootstrap.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                    if (in == null) {
                        logger.warn("[CEARL] {} missing from the classpath — builtin plan active",
                            DEFAULT_RESOURCE);
                        VramPlans.reset();
                        program = null;
                        return null;
                    }
                    source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    sourceName = "cearl/stonebreak.CEARL";
                }
            }
        } catch (IOException e) {
            logger.error("[CEARL] cannot read the plan source: {} — builtin plan active",
                e.getMessage());
            VramPlans.reset();
            program = null;
            return null;
        }

        try {
            CearlProgram compiled = CearlCompiler.compile(source, sourceName,
                Map.of("vram", vramBytes));
            if (compiled.plan() != null) {
                VramPlans.install(compiled.plan());
            }
            program = compiled;
            logger.info("[CEARL] compiled {} — plan '{}' ({} pools, budget {} MiB), {} kernel(s){}",
                sourceName,
                compiled.plan() != null ? compiled.plan().name() : "(none)",
                compiled.plan() != null ? compiled.plan().pools().size() : 0,
                compiled.plan() != null ? compiled.plan().deviceBudgetBytes() >> 20 : 0,
                compiled.kernels().size(),
                vramBytes > 0 ? String.format(", vram %.1f GiB detected", vramBytes / 1073741824.0)
                    : ", vram unknown");
            return compiled;
        } catch (CearlException e) {
            logger.error("[CEARL] {} — builtin plan active", e.getMessage());
            VramPlans.reset();
            program = null;
            return null;
        }
    }

    /** The compiled program (kernels for the future dispatch runtime), or null. */
    public static CearlProgram program() {
        return program;
    }

    /**
     * Dedicated VRAM via the NVX memory-info extension, 0 when unavailable.
     * Requires a current GL context; swallows everything — detection is a
     * hint for {@code when} guards, never a requirement.
     */
    private static long detectVramBytes() {
        try {
            while (GL11.glGetError() != GL11.GL_NO_ERROR) {
                // Clear any pre-existing error state.
            }
            int kb = GL11.glGetInteger(GL_DEDICATED_VIDMEM_NVX);
            boolean clean = GL11.glGetError() == GL11.GL_NO_ERROR;
            return clean && kb > 0 ? kb * 1024L : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }
}
