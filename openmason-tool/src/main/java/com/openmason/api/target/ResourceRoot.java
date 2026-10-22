package com.openmason.api.target;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Where a {@link GameTarget}'s resources are (#380): a source tree on disk, found from the
 * working directory, and the class path the packaged game carries them on.
 *
 * <p>{@link #locate()} resolves, in order: the {@code property} system property (names the tree
 * directly), a fixed tree ({@link #at}), then each relative {@code searchPaths} entry against the
 * working directory and every parent of it, so launching from the repository root, a module
 * folder or a nested run directory all work. It is null when no tree is found (a packaged
 * launch); {@link #open} then reads from {@code classpath}.
 */
public final class ResourceRoot {

    private final List<String> searchPaths;
    private final String property;
    private final ClassLoader classpath;
    private final Path fixed;

    /**
     * @param searchPaths source-tree paths relative to the working directory or one of its parents
     * @param property    system property that names the tree directly, or null
     * @param classpath   loader the packaged resources are on, or null for none
     */
    public ResourceRoot(List<String> searchPaths, String property, ClassLoader classpath) {
        this(searchPaths, property, classpath, null);
    }

    private ResourceRoot(List<String> searchPaths, String property, ClassLoader classpath, Path fixed) {
        this.searchPaths = List.copyOf(searchPaths == null ? List.of() : searchPaths);
        this.property = property;
        this.classpath = classpath;
        this.fixed = fixed;
    }

    /** A tree at a fixed location, with no class path behind it. */
    public static ResourceRoot at(Path tree) {
        return new ResourceRoot(List.of(), null, null, Objects.requireNonNull(tree, "tree").toAbsolutePath().normalize());
    }

    public List<String> searchPaths() {
        return searchPaths;
    }

    /** The system property naming the tree directly, or null. */
    public String property() {
        return property;
    }

    /** The loader the packaged resources are on, or null. */
    public ClassLoader classpath() {
        return classpath;
    }

    /** Absolute path of the source tree, or null when there is none on disk. */
    public Path locate() {
        if (property != null) {
            String named = System.getProperty(property);
            if (named != null && !named.isBlank()) {
                Path p = Path.of(named.trim()).toAbsolutePath().normalize();
                if (Files.isDirectory(p)) {
                    return p;
                }
            }
        }
        if (fixed != null) {
            return Files.isDirectory(fixed) ? fixed : null;
        }
        return locateFrom(Path.of("").toAbsolutePath());
    }

    /** The first search path that exists under {@code start} or one of its ancestors, or null. */
    public Path locateFrom(Path start) {
        for (Path dir = start.toAbsolutePath(); dir != null; dir = dir.getParent()) {
            for (String rel : searchPaths) {
                Path candidate = dir.resolve(rel);
                if (Files.isDirectory(candidate)) {
                    return candidate.toAbsolutePath().normalize();
                }
            }
        }
        return null;
    }

    /**
     * Opens {@code path} (relative to the resource root): the tree's file first, so fresh edits are
     * seen without a rebuild, then the class path.
     *
     * @throws FileNotFoundException when neither has it
     */
    public InputStream open(String path) throws IOException {
        String rel = path.replace('\\', '/');
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        Path tree = locate();
        if (tree != null) {
            Path file = tree.resolve(rel).normalize();
            if (file.startsWith(tree) && Files.isRegularFile(file)) {
                return Files.newInputStream(file);
            }
        }
        if (classpath != null) {
            InputStream in = openFromClasspath(rel);
            if (in != null) {
                return in;
            }
        }
        throw new FileNotFoundException("resource " + rel + " not found in " + describe());
    }

    /**
     * A game that is a named module counts its resource folders as packages, so a plain
     * {@code getResourceAsStream} from another module is refused. Like the game's own registries,
     * resolve the folder's URL first and open the file beneath it.
     */
    private InputStream openFromClasspath(String rel) throws IOException {
        URL direct = classpath.getResource(rel);
        if (direct != null) {
            return direct.openStream();
        }
        int slash = rel.lastIndexOf('/');
        if (slash <= 0) {
            return null;
        }
        URL folder = classpath.getResource(rel.substring(0, slash));
        if (folder == null) {
            return null;
        }
        try {
            return URI.create(folder + "/" + rel.substring(slash + 1)).toURL().openStream();
        } catch (FileNotFoundException | IllegalArgumentException e) {
            return null;
        }
    }

    private String describe() {
        Path tree = locate();
        return (tree != null ? tree.toString() : "no source tree") + (classpath != null ? " or the class path" : "");
    }

    @Override
    public String toString() {
        return "ResourceRoot[" + describe() + "]";
    }
}
