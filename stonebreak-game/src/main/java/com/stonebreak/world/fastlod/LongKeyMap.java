package com.stonebreak.world.fastlod;

import java.util.Arrays;

/**
 * A small thread-safe {@code long -> V} hash map (open addressing, linear probing, backward-shift deletion) without
 * boxing (2026-11-20): {@link FastLodManager}'s ring scan looks up every column of the ring each tick, and a
 * {@code Map<Long, ...>} boxed a {@code Long} per lookup. Every method is synchronized; the scan's lookups are
 * uncontended. Null values are not stored.
 */
final class LongKeyMap<V> {

    private long[] keys;
    private Object[] values;
    private int size;
    private int mask;

    LongKeyMap() {
        this(64);
    }

    LongKeyMap(int capacity) {
        int n = Integer.highestOneBit(Math.max(16, capacity * 2 - 1));
        keys = new long[n];
        values = new Object[n];
        mask = n - 1;
    }

    private static int hash(long k) {
        long h = k * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32));
    }

    @SuppressWarnings("unchecked")
    synchronized V get(long key) {
        for (int i = hash(key) & mask; ; i = (i + 1) & mask) {
            Object v = values[i];
            if (v == null) {
                return null;
            }
            if (keys[i] == key) {
                return (V) v;
            }
        }
    }

    @SuppressWarnings("unchecked")
    synchronized V put(long key, V value) {
        if (value == null) {
            throw new IllegalArgumentException("null value");
        }
        if ((size + 1) * 4 > keys.length * 3) {
            grow();
        }
        for (int i = hash(key) & mask; ; i = (i + 1) & mask) {
            Object v = values[i];
            if (v == null) {
                keys[i] = key;
                values[i] = value;
                size++;
                return null;
            }
            if (keys[i] == key) {
                values[i] = value;
                return (V) v;
            }
        }
    }

    /** Removes {@code key} only while it maps to {@code expected} (identity or equals); true if removed. */
    synchronized boolean remove(long key, Object expected) {
        for (int i = hash(key) & mask; ; i = (i + 1) & mask) {
            Object v = values[i];
            if (v == null) {
                return false;
            }
            if (keys[i] == key) {
                if (v != expected && !v.equals(expected)) {
                    return false;
                }
                deleteAt(i);
                return true;
            }
        }
    }

    /** True when some key satisfies {@code test}. */
    synchronized boolean anyKey(java.util.function.LongPredicate test) {
        for (int i = 0; i < keys.length; i++) {
            if (values[i] != null && test.test(keys[i])) {
                return true;
            }
        }
        return false;
    }

    /** Removes every entry whose value satisfies {@code test}. */
    @SuppressWarnings("unchecked")
    synchronized void removeValuesIf(java.util.function.Predicate<V> test) {
        long[] doomed = null;
        int n = 0;
        for (int i = 0; i < keys.length; i++) {
            if (values[i] != null && test.test((V) values[i])) {
                if (doomed == null) doomed = new long[Math.max(4, size)];
                doomed[n++] = keys[i];
            }
        }
        for (int i = 0; i < n; i++) {
            for (int s = hash(doomed[i]) & mask; values[s] != null; s = (s + 1) & mask) {
                if (keys[s] == doomed[i]) {
                    deleteAt(s);
                    break;
                }
            }
        }
    }

    synchronized boolean isEmpty() {
        return size == 0;
    }

    synchronized void clear() {
        Arrays.fill(values, null);
        size = 0;
    }

    synchronized int size() {
        return size;
    }

    private void deleteAt(int slot) {
        values[slot] = null;
        size--;
        // Backward-shift: pull later entries of the probe run into the hole so lookups never stop early.
        int hole = slot;
        for (int i = (slot + 1) & mask; values[i] != null; i = (i + 1) & mask) {
            int home = hash(keys[i]) & mask;
            boolean movable = hole <= i ? (home <= hole || home > i) : (home <= hole && home > i);
            if (movable) {
                keys[hole] = keys[i];
                values[hole] = values[i];
                values[i] = null;
                hole = i;
            }
        }
    }

    private void grow() {
        long[] oldKeys = keys;
        Object[] oldValues = values;
        keys = new long[oldKeys.length * 2];
        values = new Object[oldValues.length * 2];
        mask = keys.length - 1;
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldValues[i] != null) {
                insertFresh(oldKeys[i], oldValues[i]);
            }
        }
    }

    private void insertFresh(long key, Object value) {
        for (int i = hash(key) & mask; ; i = (i + 1) & mask) {
            if (values[i] == null) {
                keys[i] = key;
                values[i] = value;
                size++;
                return;
            }
        }
    }
}
