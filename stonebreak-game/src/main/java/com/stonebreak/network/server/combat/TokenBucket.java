package com.stonebreak.network.server.combat;

/**
 * A classic token bucket: up to {@code capacity} actions in a burst, refilled continuously at
 * {@code refillPerSecond}. Starts full. Time is supplied by the caller (monotonic nanos), so the
 * rules are testable without sleeping.
 */
final class TokenBucket {

    private final double capacity;
    private final double refillPerNano;
    private double tokens;
    private long lastNanos;
    private boolean started;

    TokenBucket(double capacity, double refillPerSecond) {
        this.capacity = capacity;
        this.refillPerNano = refillPerSecond / 1_000_000_000d;
        this.tokens = capacity;
    }

    /** Takes one token if available; false (nothing taken) when the bucket is empty. */
    boolean tryTake(long nowNanos) {
        if (started) {
            long elapsed = Math.max(0L, nowNanos - lastNanos);
            tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
        }
        started = true;
        lastNanos = nowNanos;
        if (tokens >= 1d) {
            tokens -= 1d;
            return true;
        }
        return false;
    }
}
