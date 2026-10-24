package com.openmason.engine.net.pipeline;

/**
 * A token bucket: up to {@code capacity} in a burst, refilled continuously at
 * {@code refillPerSecond}. Starts full. Time is supplied by the caller (monotonic nanos), so
 * limits are testable without sleeping. Not thread-safe: each instance belongs to one channel
 * (its event loop) or one owning thread.
 */
public final class TokenBucket {

    private final double capacity;
    private final double refillPerNano;
    private double tokens;
    private long lastNanos;
    private boolean started;

    public TokenBucket(double capacity, double refillPerSecond) {
        this.capacity = capacity;
        this.refillPerNano = refillPerSecond / 1_000_000_000d;
        this.tokens = capacity;
    }

    /** Takes {@code amount} tokens if available; false (nothing taken) when there are fewer. */
    public boolean tryTake(double amount, long nowNanos) {
        refill(nowNanos);
        if (tokens >= amount) {
            tokens -= amount;
            return true;
        }
        return false;
    }

    /** Takes one token if available. */
    public boolean tryTake(long nowNanos) {
        return tryTake(1d, nowNanos);
    }

    /** Tokens available at {@code nowNanos} (refills first). */
    public double available(long nowNanos) {
        refill(nowNanos);
        return tokens;
    }

    /** Fills the bucket (a reset the owner decided on, e.g. after a server-issued teleport). */
    public void fill(long nowNanos) {
        tokens = capacity;
        lastNanos = nowNanos;
        started = true;
    }

    private void refill(long nowNanos) {
        if (started) {
            long elapsed = Math.max(0L, nowNanos - lastNanos);
            tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
        }
        started = true;
        lastNanos = nowNanos;
    }
}
