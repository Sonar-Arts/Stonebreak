package com.stonebreak.world.generation.diffusion.tgmpipe;

import com.stonebreak.world.generation.TerrainModelRef;
import com.stonebreak.world.generation.diffusion.TGMPipeException;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * Where the terrain models live and which one a world runs (review-python HIGH-1, FX1 2026-11-19). Pure file lookups:
 * no service, no {@link TGMPipe} instance (so save code and tests can ask what new worlds get without starting a
 * watchdog thread). The terrain pipeline reads the model through {@link TGMPipe#modelPath()}, which returns the world's
 * model chosen here ({@link TGMPipe#useWorldModel}) or {@link #defaultModelPath()}: the in-process native pipeline
 * ({@code NativeTerrainLauncher}) reads the release export under {@code <model>/native}.
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
     * when set, else {@link TGMPipe#DEFAULT_MODEL}. There is no fallback model (EXP-1's v4 was retired 2026-11-19): a
     * checkout without the release folder fails at start with {@link #installProblem}'s message.
     */
    public static String defaultModelPath() {
        String set = System.getProperty("stonebreak.tgmpipe.model");
        if (set != null && !set.isBlank()) {
            return set;
        }
        return TGMPipe.DEFAULT_MODEL;
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

    /** The release export the game reads inside a model folder (manifest.json + *.cim networks). */
    static final String NATIVE_EXPORT = "native";

    /**
     * A model folder the game can run: it carries the native release export ({@code native/manifest.json}; its networks
     * may still be Git LFS pointers, see {@link #installProblem}). The {@code *.pt} weights are for training and for
     * writing the export; the game never reads them.
     */
    static boolean isModelDir(Path dir) {
        return Files.isRegularFile(dir.resolve(NATIVE_EXPORT).resolve("manifest.json"));
    }

    /**
     * Models that were removed from the checkout (2026-11-19: EXP-1, trained on WorldClim, must never ship). A world
     * naming one ({@link TerrainModelRef#LEGACY}: every world saved without a model) runs the default model with a
     * notice, even where an old copy of the folder is still on disk.
     */
    static final List<String> RETIRED_MODELS = List.of("checkpoints/v2", "checkpoints/v3", "checkpoints/v4");

    static boolean isRetired(String path) {
        String p = path.replace('\\', '/');
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return RETIRED_MODELS.contains(p);
    }

    /** First bytes of every Git LFS pointer file (https://github.com/git-lfs/git-lfs/blob/main/docs/spec.md). */
    private static final byte[] LFS_POINTER = "version https://git-lfs.github.com/spec/".getBytes(StandardCharsets.US_ASCII);
    /** LFS pointers are ~130 bytes; the spec caps them below 1024. */
    private static final long LFS_POINTER_MAX_BYTES = 1024;

    /** True when {@code file} is a Git LFS pointer (a checkout that has not run {@code git lfs pull}), not the real file. */
    static boolean isLfsPointer(Path file) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) >= LFS_POINTER_MAX_BYTES) {
                return false;
            }
            byte[] head = new byte[LFS_POINTER.length];
            try (var in = Files.newInputStream(file)) {
                if (in.readNBytes(head, 0, head.length) != head.length) {
                    return false;
                }
            }
            return Arrays.equals(head, LFS_POINTER);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Why the model folder {@code path} cannot be run, or null when it can: folder or its native export
     * ({@code native/manifest.json}) missing, or export files that are still Git LFS pointers. The message names the
     * folder and how to fetch it ({@code git lfs pull}): the release is stored with Git LFS, so a clone without git-lfs
     * has tiny text files instead.
     */
    public static String installProblem(String path) {
        Path dir = dirOf(path);
        Path export = dir.resolve(NATIVE_EXPORT);
        String how = "The DaedalusTGM-Exp model release is stored with Git LFS: install git-lfs, then run "
                + "'git lfs install' and 'git lfs pull' in the Stonebreak checkout";
        if (isRetired(path)) {
            return "terrain model " + path + " was retired (EXP-1 models were removed on 2026-11-19); the default is "
                    + TGMPipe.DEFAULT_MODEL;
        }
        if (!isModelDir(dir)) {
            return "terrain model " + path + " is not installed (" + export.resolve("manifest.json") + " is missing). "
                    + how + (path.equals(TGMPipe.DEFAULT_MODEL) ? "." : ", or pick an installed model with -Dstonebreak.tgmpipe.model.");
        }
        List<String> pointers = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(export)) {
            for (Path f : files) {
                if (isLfsPointer(f)) {
                    pointers.add(NATIVE_EXPORT + "/" + f.getFileName());
                }
            }
        } catch (IOException | RuntimeException e) {
            return "terrain model " + path + " is unreadable (" + export + "): " + e.getMessage();
        }
        if (!pointers.isEmpty()) {
            pointers.sort(null);
            return "terrain model " + path + " has Git LFS pointer files instead of its export (" + String.join(", ", pointers)
                    + " in " + dir + "). " + how + ".";
        }
        return null;
    }

    /** {@link #installProblem} as a {@link TGMPipeException}: called before the terrain pipeline starts. */
    static void requireInstalled(String path) {
        String problem = installProblem(path);
        if (problem != null) {
            throw new TGMPipeException(problem);
        }
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
     *   <li>a retired model ({@link #RETIRED_MODELS}: EXP-1's v2 / v3 / v4, and so every world saved without a model):
     *       the default, with a notice;</li>
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
        if (isRetired(ref.path())) {
            String fallback = defaultModelPath();
            return new Choice(fallback, false, notice(ref, fallback, "retired"));
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
        String fix = isRetired(ref.path()) ? "That model was removed from the game (EXP-1, 2026-11-19)."
                : "Install " + ref.path() + " to keep this world seamless.";
        return "This world was made with terrain model " + ref.displayName() + " (" + why + "): unexplored terrain "
                + "will come from " + refOf(running).displayName() + " instead and will not line up with the explored "
                + "area. " + fix;
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
