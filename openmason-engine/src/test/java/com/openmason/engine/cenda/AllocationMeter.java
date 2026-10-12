package com.openmason.engine.cenda;

import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;

/**
 * Bytes the current thread has allocated on the Java heap, read reflectively (the engine module
 * does not read {@code jdk.management}). {@link #available()} is false on JVMs without the meter.
 */
public final class AllocationMeter {

    private static final Method ALLOCATED = find();

    private AllocationMeter() {
    }

    public static boolean available() {
        return ALLOCATED != null;
    }

    public static long allocatedBytes() {
        if (ALLOCATED == null) {
            return 0;
        }
        try {
            return (long) ALLOCATED.invoke(ManagementFactory.getThreadMXBean());
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    private static Method find() {
        try {
            Method m = Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes");
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
