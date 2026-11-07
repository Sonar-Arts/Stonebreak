package com.stonebreak.core.render;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where F2 screenshots go: {@code <the user's Pictures folder>/Stonebreak}.
 *
 * <ul>
 *   <li><b>Linux/BSD</b>: {@code XDG_PICTURES_DIR} from {@code $XDG_CONFIG_HOME/user-dirs.dirs} (the
 *       folder the desktop calls "Pictures", localized or relocated), else {@code ~/Pictures}.</li>
 *   <li><b>Windows</b>: the "My Pictures" shell folder from the registry (follows OneDrive and
 *       user relocation), else {@code %USERPROFILE%\Pictures}.</li>
 *   <li><b>macOS</b>: {@code ~/Pictures}.</li>
 * </ul>
 * {@code -Dstonebreak.screenshots.dir=<path>} overrides all of it. Pure apart from the injected
 * lookups, so the platform rules are unit-testable on any OS.
 */
public final class ScreenshotDirectory {

    /** System property that replaces the resolved folder outright. */
    public static final String OVERRIDE_PROPERTY = "stonebreak.screenshots.dir";
    /** Subfolder created inside the Pictures folder. */
    public static final String SUBFOLDER = "Stonebreak";
    /** Used when no Pictures folder can be found or created: relative to the working directory. */
    public static final Path FALLBACK = Paths.get("screenshots");

    private static final String SHELL_FOLDERS_KEY =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders";
    private static final Pattern XDG_PICTURES =
            Pattern.compile("^\\s*XDG_PICTURES_DIR\\s*=\\s*\"(.*)\"\\s*$");
    private static final Pattern REG_VALUE =
            Pattern.compile("^\\s*My Pictures\\s+REG_(?:EXPAND_)?SZ\\s+(.+?)\\s*$");
    private static final Pattern WINDOWS_ENV = Pattern.compile("%([^%]+)%");

    private ScreenshotDirectory() {}

    /** Resolves the folder for this machine (does not create it). */
    public static Path resolve() {
        return resolve(
                System.getProperty(OVERRIDE_PROPERTY),
                System.getProperty("os.name", ""),
                System.getProperty("user.home", ""),
                System::getenv,
                ScreenshotDirectory::readLines,
                ScreenshotDirectory::queryWindowsPicturesValue,
                Files::isDirectory);
    }

    /**
     * @param override       {@link #OVERRIDE_PROPERTY}'s value, or null
     * @param osName         {@code os.name}
     * @param home           {@code user.home}
     * @param env            environment lookup
     * @param readLines      reads a text file's lines, empty when it cannot be read
     * @param windowsPictures the raw (unexpanded) registry "My Pictures" value, empty when unknown
     * @param isDirectory    existence check: a configured Pictures folder that is missing (stale
     *                       config, mis-decoded registry text) falls back to {@code ~/Pictures}
     *                       rather than having a stray tree created for it
     */
    static Path resolve(String override, String osName, String home,
                        Function<String, String> env,
                        Function<Path, Optional<List<String>>> readLines,
                        Supplier<Optional<String>> windowsPictures,
                        Predicate<Path> isDirectory) {
        if (override != null && !override.isBlank()) {
            Optional<Path> path = toPath(override.strip());
            if (path.isPresent()) {
                return path.get();
            }
        }
        if (home == null || home.isBlank()) {
            return FALLBACK;
        }
        Path homeDir = Paths.get(home);
        String os = osName.toLowerCase(Locale.ROOT);

        Optional<Path> pictures;
        if (os.startsWith("windows")) {
            pictures = windowsPictures.get().flatMap(raw -> toPath(expandWindowsEnv(raw, env)));
        } else if (os.startsWith("mac") || os.contains("darwin")) {
            pictures = Optional.empty();
        } else {
            pictures = xdgPictures(homeDir, env, readLines);
        }
        return pictures.filter(isDirectory).orElse(homeDir.resolve("Pictures")).resolve(SUBFOLDER);
    }

    /** {@code XDG_PICTURES_DIR}, per the xdg-user-dirs format ({@code "$HOME/..."} or absolute). */
    static Optional<Path> xdgPictures(Path home, Function<String, String> env,
                                      Function<Path, Optional<List<String>>> readLines) {
        String configHome = env.apply("XDG_CONFIG_HOME");
        Path config = (configHome != null && !configHome.isBlank() && Paths.get(configHome).isAbsolute())
                ? Paths.get(configHome)
                : home.resolve(".config");
        List<String> lines = readLines.apply(config.resolve("user-dirs.dirs")).orElse(List.of());
        for (String line : lines) {
            Matcher m = XDG_PICTURES.matcher(line);
            if (!m.matches()) {
                continue;
            }
            String value = m.group(1).replace("\\\"", "\"");
            Path dir;
            if (value.equals("$HOME") || value.equals("$HOME/")) {
                return Optional.empty(); // "$HOME/" means the directory is disabled
            } else if (value.startsWith("$HOME/")) {
                Optional<Path> rel = toPath(value.substring("$HOME/".length()));
                if (rel.isEmpty()) {
                    return Optional.empty();
                }
                dir = home.resolve(rel.get());
            } else {
                Optional<Path> abs = toPath(value).filter(Path::isAbsolute);
                if (abs.isEmpty()) {
                    return Optional.empty();
                }
                dir = abs.get();
            }
            return Optional.of(dir);
        }
        return Optional.empty();
    }

    /** Expands {@code %VAR%} references (e.g. {@code %USERPROFILE%\Pictures}); unknown ones stay as-is. */
    static String expandWindowsEnv(String raw, Function<String, String> env) {
        Matcher m = WINDOWS_ENV.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = env.apply(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The "My Pictures" value from {@code reg query} output, if present. */
    static Optional<String> parseRegQuery(List<String> output) {
        for (String line : output) {
            Matcher m = REG_VALUE.matcher(line);
            if (m.matches()) {
                return Optional.of(m.group(1));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> queryWindowsPicturesValue() {
        try {
            Process process = new ProcessBuilder("reg", "query", SHELL_FOLDERS_KEY, "/v", "My Pictures")
                    .redirectErrorStream(true)
                    .start();
            List<String> lines;
            try (InputStream in = process.getInputStream()) {
                lines = new String(in.readAllBytes(), Charset.defaultCharset()).lines().toList();
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            return process.exitValue() == 0 ? parseRegQuery(lines) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static Optional<List<String>> readLines(Path file) {
        try {
            return Optional.of(Files.readAllLines(file));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<Path> toPath(String value) {
        if (value.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Paths.get(value));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }
}
