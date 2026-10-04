package com.openmason.engine.format.omui;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@code MAJOR.MINOR} schema version, written as a string on the wire ({@code "1.0"}).
 *
 * <p>Negotiation rule (see the wire contract): a reader accepts any minor of a major it
 * supports. A newer minor may only add optional fields, which the reader preserves; anything
 * an older reader must understand is announced through the manifest's {@code requires} list.
 * A newer major is refused. An older major goes through an explicit upgrade stage.
 */
public record SchemaVersion(int major, int minor) implements Comparable<SchemaVersion> {

    private static final Pattern WIRE = Pattern.compile("(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,3})");

    public SchemaVersion {
        if (major < 0 || minor < 0) {
            throw new IllegalArgumentException("Negative schema version " + major + "." + minor);
        }
    }

    /** @return the parsed version, or {@code null} when {@code text} is not {@code MAJOR.MINOR} */
    public static SchemaVersion parse(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = WIRE.matcher(text);
        return m.matches() ? new SchemaVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))) : null;
    }

    public boolean sameMajor(SchemaVersion other) {
        return major == Objects.requireNonNull(other).major;
    }

    @Override
    public int compareTo(SchemaVersion o) {
        return major != o.major ? Integer.compare(major, o.major) : Integer.compare(minor, o.minor);
    }

    @Override
    public String toString() {
        return major + "." + minor;
    }
}
