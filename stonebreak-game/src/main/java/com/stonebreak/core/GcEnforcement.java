package com.stonebreak.core;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

/**
 * Startup-time guard that requires Generational ZGC.
 *
 * <p>Stonebreak's frame budget is 16.6 ms at 60 FPS; G1 young-gen pauses
 * frequently exceed that under voxel allocation rates, producing visible
 * stutter. ZGC's sub-millisecond pauses are the right pick for any real-time
 * renderer, and the generational variant (JDK 21+) reclaims young objects
 * without scanning the whole heap.
 *
 * <p>This guard fails fast in development so a misconfigured run configuration
 * is caught immediately rather than chased through frame-time symptoms. Set
 * the system property {@code stonebreak.gc.allowAny=true} to bypass (e.g. when
 * profiling alternative collectors).
 */
public final class GcEnforcement {

    private GcEnforcement() {}

    /**
     * Verifies the JVM is using Generational ZGC; otherwise prints the missing
     * flags and aborts process startup.
     */
    public static void enforce() {
        if (Boolean.getBoolean("stonebreak.gc.allowAny")) {
            System.out.println("[GC] stonebreak.gc.allowAny=true — skipping ZGC enforcement");
            return;
        }

        boolean isZgc = false;
        boolean isGenerational = false;
        StringBuilder detected = new StringBuilder();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            String name = bean.getName();
            if (detected.length() > 0) detected.append(", ");
            detected.append(name);
            // ZGC bean names: "ZGC Cycles" / "ZGC Pauses" (non-generational)
            //                 "ZGC Major Cycles" / "ZGC Minor Cycles" (generational)
            if (name.startsWith("ZGC")) {
                isZgc = true;
                if (name.contains("Major") || name.contains("Minor")) {
                    isGenerational = true;
                }
            }
        }

        if (isZgc && isGenerational) {
            System.out.println("[GC] Generational ZGC active — collectors: " + detected);
            return;
        }

        String reason = !isZgc
            ? "ZGC is not active (detected: " + detected + ")"
            : "ZGC is active but not generational (need -XX:+ZGenerational)";

        String banner = """

            ════════════════════════════════════════════════════════════════
              Stonebreak requires Generational ZGC.
              %s

              Add these JVM options to your run configuration:
                -XX:+UseZGC
                -Xms2g -Xmx8g
                --enable-native-access=ALL-UNNAMED
                --add-exports=java.base/sun.nio.ch=ALL-UNNAMED
                --add-exports=java.base/sun.security.util=ALL-UNNAMED

              IntelliJ: Run | Edit Configurations… | Modify options |
                        Add VM options.

              Bypass (development only): -Dstonebreak.gc.allowAny=true
            ════════════════════════════════════════════════════════════════
            """.formatted(reason);

        System.err.println(banner);
        throw new IllegalStateException("Generational ZGC is required. " + reason);
    }
}
