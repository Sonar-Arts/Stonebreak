package com.stonebreak.world.generation.diffusion.tgmpipe;

import com.stonebreak.world.generation.TerrainModelRef;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Where the terrain models live and which one a world runs (review-python HIGH-1, FX1 2026-11-19). Pure file lookups:
 * no service, no {@link TGMPipe} instance (so save code and tests can ask what new worlds get without starting a
 * watchdog thread). Both terrain backends read the model through {@link TGMPipe#modelPath()}, which returns the world's
 * model chosen here ({@link TGMPipe#useWorldModel}) or {@link #defaultModelPath()}:
 * the TGMPipe child process (its command line) and the in-process native pipeline ({@code NativeTerrainLauncher}: the
 * release export under {@code <model>/native}). A future backend reads it the same way.
 *
 * <p>System properties:
 * <ul>
 *   <li>{@code stonebreak.tgmpipe.model}: the model NEW worlds get (and the terrain mapper previews). Saved worlds keep
 *       theirs.</li>
 *   <li>{@code stonebreak.tgmpipe.forceModel=true} (dev A/B): that model for every world, saved ones too, with a
 *       warning when it is not the world's.</li>
 * </ul>
 */
public final class TerrainModels {

    private static final Logger LOG = Logger.getLogger(TerrainModels.class.getName());
    private static volatile boolean fallbackLogged;

    private TerrainModels() {
    }

    /**
     * The checkout root: the working directory or the nearest parent holding {@link TGMPipe#SLM_DIR}, so the game finds
     * its model whether it runs from the repo root (the IDE) or a module directory (Maven's tests).
     */
    static Path repoRoot(Path start) {
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve(TGMPipe.SLM_DIR))) {
                return dir;
            }
        }
        return start;
    }

    static Path root() {
        return repoRoot(Path.of(System.getProperty("user.dir")).toAbsolutePath());
    }

    /** The model's project folder, where the service runs ({@code -Dstonebreak.tgmpipe.repoDir}). */
    static Path slmDir() {
        Path path = Path.of(System.getProperty("stonebreak.tgmpipe.repoDir", TGMPipe.SLM_DIR));
        return path.isAbsolute() ? path : root().resolve(path);
    }

    /**
     * The model NEW worlds get, relative to {@link TGMPipe#SLM_DIR} (or absolute): {@code -Dstonebreak.tgmpipe.model}
     * when set, else {@link TGMPipe#DEFAULT_MODEL}, else (that folder missing) {@link TGMPipe#LEGACY_MODEL} with a
     * warning.
     */
    public static String defaultModelPath() {
        String set = System.getProperty("stonebreak.tgmpipe.model");
        if (set != null && !set.isBlank()) {
            return set;
        }
        Path slm = slmDir();
        Path dir = slm.resolve(TGMPipe.DEFAULT_MODEL);
        boolean legacyThere = isModelDir(slm.resolve(TGMPipe.LEGACY_MODEL));
        if (isModelDir(dir) || !legacyThere) {
            return TGMPipe.DEFAULT_MODEL;   // neither there: the service's error names the release that is missing
        }
        if (!fallbackLogged) {
            fallbackLogged = true;
            LOG.warning(TGMPipe.MODEL_NAME + " release " + dir + " is missing (build it with Models/" + TGMPipe.MODEL_NAME
                    + "/scripts/make_release.py); falling back to " + TGMPipe.LEGACY_MODEL + ", which must not ship");
        }
        return TGMPipe.LEGACY_MODEL;
    }

    /** The identity new worlds record: {@link #defaultModelPath()} + its {@code release.json} content id, if any. */
    public static TerrainModelRef current() {
        return refOf(defaultModelPath());
    }

    /** The identity of the model folder {@code path} (relative to the SLM dir, or absolute) as it is on disk now. */
    public static TerrainModelRef refOf(String path) {
        Path dir = dirOf(path);
        String stored = path;
        Path slm = slmDir().toAbsolutePath().normalize();
        Path abs = dir.toAbsolutePath().normalize();
        if (Path.of(path).isAbsolute() && abs.startsWith(slm)) {
            stored = slm.relativize(abs).toString();   // inside the checkout: portable
        }
        return new TerrainModelRef(stored, contentIdOf(dir));
    }

    /** The model folder of {@code path}: absolute as is, else under the SLM dir. */
    static Path dirOf(String path) {
        Path p = Path.of(path);
        return p.isAbsolute() ? p : slmDir().resolve(p);
    }

    /** A folder the generators can load (planner.pt is in every model, v4 and EXP-2 alike). */
    static boolean isModelDir(Path dir) {
        return Files.isRegularFile(dir.resolve("planner.pt"));
    }

    /** {@code release.json}'s {@code content_id}, or "" (no manifest / unreadable: a dev folder). */
    static String contentIdOf(Path dir) {
        Path manifest = dir.resolve("release.json");
        if (!Files.isRegularFile(manifest)) {
            return "";
        }
        try {
            return new ObjectMapper().readTree(manifest.toFile()).path("content_id").asText("").trim();
        } catch (IOException e) {
            LOG.warning("unreadable " + manifest + ": " + e.getMessage());
            return "";
        }
    }

    /**
     * Which model a world runs, and what the player must be told.
     *
     * @param path    the model folder to run (relative to the SLM dir, or absolute)
     * @param matches true when that is the world's own model
     * @param notice  a message for the player when it is not (null otherwise)
     */
    public record Choice(String path, boolean matches, String notice) {
    }

    /**
     * The model to run for a world created with {@code ref}:
     * <ol>
     *   <li>{@code -Dstonebreak.tgmpipe.forceModel=true}: {@code stonebreak.tgmpipe.model} (dev), noticed when it is not
     *       the world's;</li>
     *   <li>the world's folder, when it is a model and (for a release) still holds that release;</li>
     *   <li>for a release: any folder under {@code checkpoints/} (two levels) holding the same {@code content_id}
     *       (renamed / moved releases);</li>
     *   <li>otherwise {@link #defaultModelPath()}, with a notice: the world's unexplored terrain will come from another
     *       model and will not meet the explored edge.</li>
     * </ol>
     */
    public static Choice resolve(TerrainModelRef saved) {
        final TerrainModelRef ref = TerrainModelRef.orLegacy(saved);
        String forced = System.getProperty("stonebreak.tgmpipe.model");
        if (Boolean.getBoolean("stonebreak.tgmpipe.forceModel") && forced != null && !forced.isBlank()) {
            boolean same = sameModel(ref, forced);
            return new Choice(forced, same, same ? null : notice(ref, forced, "forced by -Dstonebreak.tgmpipe.forceModel"));
        }
        Path dir = dirOf(ref.path());
        if (isModelDir(dir) && (!ref.isRelease() || ref.contentId().equals(contentIdOf(dir)))) {
            return new Choice(ref.path(), true, null);
        }
        if (ref.isRelease()) {
            String found = findRelease(ref.contentId());
            if (found != null) {
                LOG.info(() -> "terrain model " + ref.displayName() + " found at " + found + " (saved as " + ref.path() + ")");
                return new Choice(found, true, null);
            }
        }
        String fallback = defaultModelPath();
        return new Choice(fallback, false, notice(ref, fallback, "not installed"));
    }

    /** True when the model folder {@code path} is {@code ref}'s model (same release, or the same dev folder). */
    static boolean sameModel(TerrainModelRef ref, String path) {
        TerrainModelRef other = refOf(path);
        if (ref.isRelease()) {
            return ref.contentId().equals(other.contentId());
        }
        return !other.isRelease() && dirOf(ref.path()).toAbsolutePath().normalize()
                .equals(dirOf(path).toAbsolutePath().normalize());
    }

    private static String notice(TerrainModelRef ref, String running, String why) {
        return "This world was made with terrain model " + ref.displayName() + " (" + why + "): unexplored terrain "
                + "will come from " + refOf(running).displayName() + " instead and will not line up with the explored "
                + "area. Install " + ref.path() + " to keep this world seamless.";
    }

    /** A folder under {@code checkpoints/} (one or two levels down) whose release.json has {@code contentId}. */
    static String findRelease(String contentId) {
        Path slm = slmDir();
        Path checkpoints = slm.resolve("checkpoints");
        if (!Files.isDirectory(checkpoints)) {
            return null;
        }
        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> top = Files.newDirectoryStream(checkpoints, Files::isDirectory)) {
            for (Path d : top) {
                candidates.add(d);
                try (DirectoryStream<Path> sub = Files.newDirectoryStream(d, Files::isDirectory)) {
                    sub.forEach(candidates::add);
                } catch (IOException | RuntimeException ignored) {
                    // unreadable folder: not a candidate
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        candidates.sort(null);
        for (Path d : candidates) {
            if (Files.isRegularFile(d.resolve("release.json")) && isModelDir(d) && contentId.equals(contentIdOf(d))) {
                return slm.relativize(d).toString().replace('\\', '/');
            }
        }
        return null;
    }
}
