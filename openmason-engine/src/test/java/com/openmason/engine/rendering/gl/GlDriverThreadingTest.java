package com.openmason.engine.rendering.gl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The NVIDIA threaded-optimization guard: who it leaves alone, and that the driver really sees the switch. */
class GlDriverThreadingTest {

    private static boolean linux() {
        return System.getProperty("os.name", "").toLowerCase().contains("linux");
    }

    @Test
    void leavesOtherPlatformsOptOutsAndUserValuesAlone() {
        assertEquals(GlDriverThreading.Outcome.NOT_LINUX, GlDriverThreading.apply(false, false, null));
        assertEquals(GlDriverThreading.Outcome.OPTED_OUT, GlDriverThreading.apply(true, true, null));
        assertEquals(GlDriverThreading.Outcome.ENVIRONMENT_WINS, GlDriverThreading.apply(true, false, "1"));
    }

    @Test
    void setsTheSwitchInTheNativeEnvironmentWithoutOverwriting() throws Throwable {
        assumeTrue(linux() && System.getenv(GlDriverThreading.NVIDIA_SWITCH) == null,
            "Linux, and the variable not set by the test environment");
        String before = GlDriverThreading.nativeGetenv(GlDriverThreading.NVIDIA_SWITCH);
        if (before == null) {
            assertEquals(GlDriverThreading.Outcome.DISABLED, GlDriverThreading.apply(true, false, null));
            assertEquals("0", GlDriverThreading.nativeGetenv(GlDriverThreading.NVIDIA_SWITCH),
                "the driver reads the native environment, not the JVM's snapshot");
        }
        // A value set natively (after the JVM snapshot) is never replaced.
        GlDriverThreading.setenv(GlDriverThreading.NVIDIA_SWITCH, "1", true);
        assertEquals(GlDriverThreading.Outcome.DISABLED, GlDriverThreading.apply(true, false, null));
        assertEquals("1", GlDriverThreading.nativeGetenv(GlDriverThreading.NVIDIA_SWITCH));
        GlDriverThreading.setenv(GlDriverThreading.NVIDIA_SWITCH, "0", true); // leave the fork as the guard would
    }
}
