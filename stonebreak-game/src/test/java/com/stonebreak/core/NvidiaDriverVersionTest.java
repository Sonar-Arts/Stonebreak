package com.stonebreak.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Parsing guard for {@link Main#parseNvidiaDriverVersion(Iterable)} — the value it extracts from
 * {@code /proc/driver/nvidia/version} is matched against the Wayland driver blocklist, so a
 * parsing regression would silently stop the X11 pin from firing for a bad driver. The version
 * read here is the same one the F3 debug overlay shows via GL_VERSION, but obtained without a
 * GL context (creating one on Wayland would poison a later X11/GLX fallback on NVIDIA).
 */
class NvidiaDriverVersionTest {

    @Test
    void parsesThreePartVersionFromRealNvrmLine() {
        List<String> lines = List.of(
                "NVRM version: NVIDIA UNIX x86_64 Kernel Module  595.71.05  Wed Oct 15 12:00:00 UTC 2025",
                "GCC version:  gcc version 14.2.1 20250101 (GCC)"
        );
        assertEquals("595.71.05", Main.parseNvidiaDriverVersion(lines));
    }

    @Test
    void parsesOpenKernelModuleFormat() {
        // The open-source kernel module words the NVRM line differently: the version
        // follows "for x86_64", not "Kernel Module".
        List<String> lines = List.of(
                "NVRM version: NVIDIA UNIX Open Kernel Module for x86_64  610.43.02  Release Build  (notroot@)  Mon Jun 29 14:55:45 UTC 2026",
                "GCC version:  Selected multilib: .;@m64"
        );
        assertEquals("610.43.02", Main.parseNvidiaDriverVersion(lines));
    }

    @Test
    void parsesTwoPartVersion() {
        List<String> lines = List.of(
                "NVRM version: NVIDIA UNIX x86_64 Kernel Module  550.120  Tue Jan 01 00:00:00 UTC 2025"
        );
        assertEquals("550.120", Main.parseNvidiaDriverVersion(lines));
    }

    @Test
    void ignoresDottedNumbersOnNonNvrmLines() {
        // The GCC line can carry its own dotted version (e.g. "14.2.1") — must not be picked up.
        List<String> lines = List.of(
                "GCC version:  gcc version 14.2.1 20250101 (GCC)"
        );
        assertNull(Main.parseNvidiaDriverVersion(lines));
    }

    @Test
    void returnsNullWhenNoNvrmLinePresent() {
        List<String> lines = List.of(
                "GCC version:  gcc version 14.2.1 20250101 (GCC)",
                "some unrelated content"
        );
        assertNull(Main.parseNvidiaDriverVersion(lines));
    }

    @Test
    void returnsNullForEmptyInput() {
        assertNull(Main.parseNvidiaDriverVersion(List.of()));
    }
}
