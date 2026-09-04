package com.stonebreak.world.generation.water;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * One computation per key at a time: the first caller to ask for a key runs it,
 * every other caller waits for that result instead of running it again.
 *
 * <p>Both caches below hold pure functions of their key, so duplicate work was
 * previously written off as harmless. It is not harmless when the work is a
 * cold GPU elevation fetch or a 23 ms basin solve and the world generator asks
 * from a dozen chunk threads at once: a single L1 region was observed solved
 * fourteen times over, each solve pulling its own copy of sixteen coarse
 * chunks across the bridge. That is the cost that shows up as a slow world
 * load — and, downstream of it, as fourteen threads racing to rename the same
 * cache file.
 *
 * <p>The loader is expected to re-check its own cache before computing: a
 * waiter that arrives just as the owner finishes takes ownership of a key
 * whose value has already been stored, and the re-check turns that into a hit
 * rather than one more solve.
 */
final class SingleFlight<K, V> {

    private final ConcurrentHashMap<K, CompletableFuture<V>> running = new ConcurrentHashMap<>();

    V compute(K key, Function<K, V> loader) {
        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> theirs = running.putIfAbsent(key, mine);
        if (theirs != null) {
            return await(theirs);
        }
        try {
            V value = loader.apply(key);
            mine.complete(value);
            return value;
        } catch (RuntimeException | Error e) {
            // Failures are never cached: the slot is dropped in the finally
            // block, so the next caller retries rather than inheriting this.
            mine.completeExceptionally(e);
            throw e;
        } finally {
            running.remove(key, mine);
        }
    }

    private static <V> V await(CompletableFuture<V> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            // The owner's failure is the waiter's failure, thrown in its
            // original shape so a caller's catch clauses still match.
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            if (e.getCause() instanceof Error err) {
                throw err;
            }
            throw e;
        }
    }
}
