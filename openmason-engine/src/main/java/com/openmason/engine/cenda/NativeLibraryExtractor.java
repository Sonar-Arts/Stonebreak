package com.openmason.engine.cenda;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Turns the Cenda library packaged as a classpath resource ({@code natives/<platform>/<lib>},
 * copied in by the engine POM) into a file the dynamic loader can open: a shipped game has no
 * {@code cenda/build/} directory, and UI scripting and layout have no Java fallback.
 *
 * <p>The file goes to {@code <cache>/<sha256 prefix>/<lib>}, keyed by the bytes, so two game
 * versions never overwrite each other's library (one may still have it mapped) and a second
 * launch reuses the extraction. Writes go to a temp file in the same directory and are moved into
 * place atomically, so a concurrent launch never loads a half-written library.
 */
final class NativeLibraryExtractor {

    private NativeLibraryExtractor() {
    }

    /** Opens a classpath resource; null when absent. */
    @FunctionalInterface
    interface Opener {
        InputStream open(String resource) throws IOException;
    }

    /** {@code linux-x86_64}, {@code windows-x86_64}, {@code macos-aarch64}, ...: the POM's {@code cenda.natives.dir}. */
    static String platform() {
        return platform(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static String platform(String osName, String osArch) {
        String os = osName.toLowerCase(Locale.ROOT);
        String o = os.startsWith("windows") ? "windows" : os.startsWith("mac") || os.contains("darwin") ? "macos"
            : os.startsWith("linux") ? "linux" : os.replaceAll("[^a-z0-9]", "");
        String arch = osArch.toLowerCase(Locale.ROOT);
        String a = switch (arch) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch.replaceAll("[^a-z0-9_]", "");
        };
        return o + "-" + a;
    }

    /** {@code natives/<platform>/<System.mapLibraryName(name)>}. */
    static String resourceName(String libName) {
        return "natives/" + platform() + "/" + libName;
    }

    /** Where extractions live: {@code -Dcenda.natives.cache}, else the per-user cache directory. */
    static Path cacheRoot() {
        String prop = System.getProperty("cenda.natives.cache");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, "Stonebreak", "natives");
        }
        String xdg = System.getenv("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg, "stonebreak", "natives");
        }
        return Path.of(System.getProperty("user.home", "."), ".cache", "stonebreak", "natives");
    }

    /**
     * The packaged library as a file, extracting it on first use.
     *
     * @return the extracted path, or null when the resource is not on the classpath
     */
    static Path extract(String resource, String libName, Opener opener, Path cacheRoot) throws IOException {
        byte[] bytes;
        try (InputStream in = opener.open(resource)) {
            if (in == null) {
                return null;
            }
            bytes = in.readAllBytes();
        }
        String hash = sha256(bytes).substring(0, 16);
        Path dir = cacheRoot.resolve(hash);
        Path target = dir.resolve(libName);
        if (Files.isRegularFile(target) && Files.size(target) == bytes.length) {
            return target; // bytes-keyed directory: same size means the same, complete file
        }
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, libName, ".part");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        return target;
    }

    /** Looks the resource up through this module, the context loader and the system loader. */
    static InputStream openResource(String resource) {
        InputStream in = NativeLibraryExtractor.class.getResourceAsStream("/" + resource);
        if (in == null) {
            ClassLoader context = Thread.currentThread().getContextClassLoader();
            if (context != null) {
                in = context.getResourceAsStream(resource);
            }
        }
        if (in == null) {
            in = ClassLoader.getSystemResourceAsStream(resource);
        }
        return in;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
