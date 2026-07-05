package com.stonebreak.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Parsing guard for {@link Main#parseNvidiaDriverVersion(String)} — the value it extracts from
 * the OpenGL {@code GL_VERSION} string (the same source the F3 debug overlay uses) is matched
 * against the Wayland driver blocklist, so a parsing regression would silently stop the X11
 * switch from firing for a bad driver.
 */
class NvidiaDriverVersionTest {

    @Test
    void parsesDriverFromRealNvidiaGlVersionString() {
        assertEquals("595.71.05", Main.parseNvidiaDriverVersion("4.6.0 NVIDIA 595.71.05"));
    }

    @Test
    void parsesTwoPartDriverVersion() {
        assertEquals("550.120", Main.parseNvidiaDriverVersion("4.6.0 NVIDIA 550.120"));
    }

    @Test
    void parsesCompatibilityProfileVariant() {
        // GLFW requests a compatibility profile; NVIDIA reflects that in GL_VERSION.
        assertEquals("595.71.05",
                Main.parseNvidiaDriverVersion("4.6.0 NVIDIA 595.71.05 compatibility profile"));
    }

    @Test
    void returnsNullForNonNvidiaGpu() {
        assertNull(Main.parseNvidiaDriverVersion("4.6 (Core Profile) Mesa 24.1.0 - AMD Radeon"));
        assertNull(Main.parseNvidiaDriverVersion("OpenGL ES 3.2 Mesa 24.1.0"));
    }

    @Test
    void returnsNullForNullInput() {
        assertNull(Main.parseNvidiaDriverVersion((String) null));
    }
}
