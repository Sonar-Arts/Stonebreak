package com.openmason.engine.cenda;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds an optional native library the way {@link CendaKernels} finds the engine's: an explicit
 * system property, then an environment variable, then known build directories relative to the
 * working directory, then the copy packaged in the caller's jar ({@code natives/<platform>/<lib>},
 * extracted once per launch to the per-user cache keyed by its bytes).
 *
 * <p>Public so a game can ship its own native library next to the engine's (Stonebreak's worldgen
 * kernels, #386) without copying the discovery and extraction rules. The packaged copy is read
 * through the caller's {@link ResourceOpener}: the engine cannot open another module's resources.
 */
public final class NativeLibraryLocator {

    private static final Logger LOGGER = LoggerFactory.getLogger(NativeLibraryLocator.class);

    /** Opens a classpath resource of the library's owning module; null when absent. */
    @FunctionalInterface
    public interface ResourceOpener {
        InputStream open(String resource) throws IOException;
    }

    /**
     * Where to look for one library.
     *
     * @param baseName     library name without prefix/suffix ({@code cenda_kernels} →
     *                     {@code libcenda_kernels.so} / {@code cenda_kernels.dll})
     * @param pathProperty system property naming the file; when set, the only place looked
     * @param pathEnv      environment variable naming the file; when set (and the property is
     *                     not), the only place looked
     * @param buildDirs    build output directories relative to the working directory, each also
     *                     searched in {@code Release/} and {@code Debug/} (multi-config generators)
     * @param opener       reads the packaged {@code natives/<platform>/<lib>} resource, or null
     *                     when the library is never packaged
     */
    public record Spec(String baseName, String pathProperty, String pathEnv,
                       List<String> buildDirs, ResourceOpener opener) {
        public Spec {
            Objects.requireNonNull(baseName, "baseName");
            buildDirs = List.copyOf(buildDirs);
        }

        /** The platform file name, {@link System#mapLibraryName}. */
        public String libraryName() {
            return System.mapLibraryName(baseName);
        }
    }

    /** One extraction per packaged resource per launch. */
    private static final Map<String, Path> EXTRACTED = new ConcurrentHashMap<>();

    private NativeLibraryLocator() {
    }

    /** The library file, or null when none is found (the caller then runs its fallback). */
    public static Path locate(Spec spec) {
        if (spec.pathProperty() != null) {
            String prop = System.getProperty(spec.pathProperty());
            if (prop != null && !prop.isBlank()) {
                Path p = Path.of(prop);
                return Files.isRegularFile(p) ? p : null;
            }
        }
        if (spec.pathEnv() != null) {
            String env = System.getenv(spec.pathEnv());
            if (env != null && !env.isBlank()) {
                Path p = Path.of(env);
                return Files.isRegularFile(p) ? p : null;
            }
        }
        String libName = spec.libraryName();
        for (String dir : spec.buildDirs()) {
            String base = dir.endsWith("/") ? dir : dir + "/";
            for (String config : new String[]{"", "Release/", "Debug/"}) {
                Path p = Path.of(base + config + libName).toAbsolutePath().normalize();
                if (Files.isRegularFile(p)) {
                    return p;
                }
            }
        }
        return spec.opener() == null ? null : packaged(libName, spec.opener());
    }

    /** {@code natives/<platform>/<libName>}: where a POM copies a library into its jar. */
    public static String packagedResourceName(String libName) {
        return NativeLibraryExtractor.resourceName(libName);
    }

    private static Path packaged(String libName, ResourceOpener opener) {
        String resource = NativeLibraryExtractor.resourceName(libName);
        Path known = EXTRACTED.get(resource);
        if (known != null) {
            return known;
        }
        synchronized (EXTRACTED) {
            known = EXTRACTED.get(resource);
            if (known != null) {
                return known;
            }
            try {
                Path p = NativeLibraryExtractor.extract(resource, libName, opener::open,
                    NativeLibraryExtractor.cacheRoot());
                if (p != null) {
                    EXTRACTED.put(resource, p);
                }
                return p;
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Native library packaged as {} could not be extracted: {}", resource, e.toString());
                return null;
            }
        }
    }
}
