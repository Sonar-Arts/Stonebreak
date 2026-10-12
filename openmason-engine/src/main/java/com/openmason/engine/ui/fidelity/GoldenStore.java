package com.openmason.engine.ui.fidelity;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Committed baseline PNGs and the check against them (#296). Tests run from their module
 * directory, so {@code dir} is a source path such as {@code src/test/resources/ui/fidelity/pause}:
 * baselines are read from where they are reviewed, never from a stale {@code target/} copy.
 *
 * <p>When the source tree is not under the working directory (a runner started elsewhere),
 * baselines are read from the classpath copy at {@code /<resourceDir>/} instead, and write mode
 * refuses rather than writing somewhere unexpected. A screen's first baselines may be written
 * into a directory that does not exist yet, as long as the module's resource root does.
 *
 * <p>In write mode ({@code -D<flag>=true}) {@link #verify} records the capture as the new baseline
 * and passes; review the PNG diff before committing. On a mismatch the capture and a diff image
 * are written to {@code out} (under {@code target/}) and the check fails with a summary.
 */
public final class GoldenStore {

    private final Path dir;
    private final Path out;
    private final boolean write;
    private final String flag;
    private final String classpathDir;
    private final Path sourceRoot;

    public GoldenStore(Path dir, Path out, boolean write, String flag) {
        this(dir, out, write, flag, null, null);
    }

    private GoldenStore(Path dir, Path out, boolean write, String flag, String classpathDir, Path sourceRoot) {
        this.dir = Objects.requireNonNull(dir, "dir");
        this.out = Objects.requireNonNull(out, "out");
        this.write = write;
        this.flag = Objects.requireNonNull(flag, "flag");
        this.classpathDir = classpathDir;
        this.sourceRoot = sourceRoot;
    }

    /**
     * The usual layout: baselines in {@code src/test/resources/<resourceDir>}, failures in
     * {@code target/ui-fidelity/<resourceDir>}, write mode from system property {@code flag}.
     */
    public static GoldenStore forTests(String resourceDir, String flag) {
        Path root = Path.of("src/test/resources");
        return new GoldenStore(root.resolve(resourceDir), Path.of("target/ui-fidelity").resolve(resourceDir),
            Boolean.getBoolean(flag), flag, resourceDir, root);
    }

    public boolean writing() {
        return write;
    }

    public Path baseline(String name) {
        return dir.resolve(name + ".png");
    }

    /**
     * Whether the source tree is reachable from the working directory: the screen's directory,
     * or (for a screen with no baselines yet) the module's resource root it will be created in.
     */
    private boolean sourceTreeVisible() {
        return Files.isDirectory(dir) || classpathDir == null || (sourceRoot != null && Files.isDirectory(sourceRoot));
    }

    /** The committed baseline, or null when none exists. */
    public FidelityImage read(String name) {
        Path file = baseline(name);
        try {
            if (Files.isRegularFile(file)) {
                return FidelityImage.read(file);
            }
            if (classpathDir == null || sourceTreeVisible()) {
                return null;
            }
            String resource = classpathDir + "/" + name + ".png";
            try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
                return in == null ? null : FidelityImage.read(in, resource);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read baseline " + name, e);
        }
    }

    /**
     * Checks {@code actual} against baseline {@code name}.
     *
     * @throws AssertionError when the baseline is missing or the capture is outside {@code tolerance}
     */
    public PixelReport verify(String name, FidelityImage actual, PixelTolerance tolerance) {
        if (write) {
            if (!sourceTreeVisible()) {
                throw new AssertionError("write mode needs the module directory as working directory: "
                    + dir.toAbsolutePath() + " does not exist");
            }
            actual.write(baseline(name));
            return new PixelReport(true, actual.width(), actual.height(), 0, 0, 0, null, null, "");
        }
        FidelityImage expected = read(name);
        if (expected == null) {
            actual.write(out.resolve(name + ".actual.png"));
            throw new AssertionError("missing baseline " + baseline(name) + " (run with -D" + flag + "=true)");
        }
        PixelReport report = PixelComparator.compare(expected, actual, tolerance);
        if (!report.passed()) {
            actual.write(out.resolve(name + ".actual.png"));
            if (report.diff() != null) {
                report.diff().write(out.resolve(name + ".diff.png"));
            }
            throw new AssertionError(name + ": " + report.summary() + " (capture and diff in " + out.toAbsolutePath()
                + "; regenerate with -D" + flag + "=true after an intended change)");
        }
        return report;
    }
}
