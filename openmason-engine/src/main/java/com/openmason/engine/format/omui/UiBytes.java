package com.openmason.engine.format.omui;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Immutable byte payload with value equality: embedded assets, scripts and opaque entries.
 * Carried verbatim through every round trip — the format never re-encodes bytes it does not
 * own.
 */
public final class UiBytes {

    private final byte[] data;
    private volatile String sha256;

    private UiBytes(byte[] data) {
        this.data = data;
    }

    public static UiBytes copyOf(byte[] data) {
        return new UiBytes(data.clone());
    }

    public static UiBytes utf8(String text) {
        return new UiBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    /** @return a defensive copy */
    public byte[] toArray() {
        return data.clone();
    }

    public int size() {
        return data.length;
    }

    /** Memoized: validators may ask for the same payload's hash many times. */
    public String sha256() {
        String h = sha256;
        if (h == null) {
            sha256 = h = sha256(data);
        }
        return h;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a mandatory JDK algorithm", e);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof UiBytes other && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "UiBytes[" + data.length + " B]";
    }
}
