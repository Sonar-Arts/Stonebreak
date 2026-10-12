package com.openmason.engine.rendering.gl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Turns off the NVIDIA driver's threaded command dispatch ("threaded optimization") for this
 * process. Call it before {@code glfwInit()}: the driver reads the switch when it loads.
 *
 * <p>Why: with threaded optimization on (the Linux driver's default heuristics enable it a few
 * frames in), Skia GPU paints in Open Mason's UI editor came out with garbage geometry (black
 * slabs, coloured wedges fanning from a point) until the next repaint. Deterministic with
 * {@code __GL_THREADED_OPTIMIZATIONS=1}, never with {@code =0} or a synchronous debug context, and
 * no GL error. Stonebreak paints Skia on the GPU too, so it applies the same guard as insurance;
 * its frames are dominated by multidraws and fence waits, which the threaded driver cannot speed
 * up (each sync point stalls its worker thread anyway).
 *
 * <p>Scope, deliberately narrow:
 * <ul>
 *   <li>Linux only: the Windows driver takes this setting from its control panel, not the
 *       environment.</li>
 *   <li>NVIDIA only: Mesa's {@code mesa_glthread} is left alone. The bug was never seen there and
 *       glthread is a real win on Mesa.</li>
 *   <li>A value already in the environment wins, and the caller's opt-out system property keeps
 *       the driver default.</li>
 *   <li>The JVM's environment snapshot ({@link System#getenv}, {@code ProcessBuilder}) is not
 *       changed, so child processes do not inherit it.</li>
 * </ul>
 *
 * <p>{@code setenv} is not thread-safe against concurrent native {@code getenv}; call it during
 * single-threaded startup, before any thread that could read the environment natively.
 */
public final class GlDriverThreading {

    private static final Logger logger = LoggerFactory.getLogger(GlDriverThreading.class);

    /** The NVIDIA driver's switch and the value that turns threaded dispatch off. */
    public static final String NVIDIA_SWITCH = "__GL_THREADED_OPTIMIZATIONS";
    static final String OFF = "0";

    /** What {@link #disableThreadedDispatch} did. */
    public enum Outcome { NOT_LINUX, OPTED_OUT, ENVIRONMENT_WINS, DISABLED, FAILED }

    private GlDriverThreading() {
    }

    /**
     * @param optOutProperty system property that, when {@code true}, keeps the driver default
     *                       (e.g. {@code stonebreak.gl.threaded})
     */
    public static Outcome disableThreadedDispatch(String optOutProperty) {
        Outcome o = apply(System.getProperty("os.name", "").toLowerCase().contains("linux"),
                optOutProperty != null && Boolean.getBoolean(optOutProperty), System.getenv(NVIDIA_SWITCH));
        switch (o) {
            case DISABLED -> logger.info("GL driver: NVIDIA threaded optimization off (-D{}=true keeps it)",
                    optOutProperty);
            case ENVIRONMENT_WINS -> logger.info("GL driver: {}={} set by the environment, left as is",
                    NVIDIA_SWITCH, System.getenv(NVIDIA_SWITCH));
            case FAILED -> logger.warn("GL driver: could not turn off NVIDIA threaded optimization; set {}=0 in the"
                    + " launch environment if Skia-drawn UI shows transient garbage shapes", NVIDIA_SWITCH);
            default -> { }
        }
        return o;
    }

    /** The decision and the native call, with the inputs as parameters (tests). */
    static Outcome apply(boolean linux, boolean optedOut, String javaEnvValue) {
        if (!linux) {
            return Outcome.NOT_LINUX;
        }
        if (optedOut) {
            return Outcome.OPTED_OUT;
        }
        if (javaEnvValue != null) {
            return Outcome.ENVIRONMENT_WINS;
        }
        try {
            // overwrite = 0: never replaces a value set natively after the JVM took its snapshot
            return setenv(NVIDIA_SWITCH, OFF, false) == 0 ? Outcome.DISABLED : Outcome.FAILED;
        } catch (Throwable t) {
            logger.debug("setenv failed", t);
            return Outcome.FAILED;
        }
    }

    static int setenv(String name, String value, boolean overwrite) throws Throwable {
        Linker linker = Linker.nativeLinker();
        MethodHandle setenv = linker.downcallHandle(linker.defaultLookup().find("setenv").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT));
        try (Arena arena = Arena.ofConfined()) {
            return (int) setenv.invokeExact(arena.allocateFrom(name), arena.allocateFrom(value), overwrite ? 1 : 0);
        }
    }

    /** The process's native environment value (what the driver sees), or null; tests. */
    static String nativeGetenv(String name) throws Throwable {
        Linker linker = Linker.nativeLinker();
        MethodHandle getenv = linker.downcallHandle(linker.defaultLookup().find("getenv").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment p = (MemorySegment) getenv.invokeExact(arena.allocateFrom(name));
            return p.equals(MemorySegment.NULL) ? null : p.reinterpret(Long.MAX_VALUE).getString(0);
        }
    }
}
