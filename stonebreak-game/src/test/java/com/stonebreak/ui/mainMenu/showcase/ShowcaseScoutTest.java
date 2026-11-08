package com.stonebreak.ui.mainMenu.showcase;

import com.stonebreak.world.generation.TerrainGenerator;
import com.stonebreak.world.generation.TerrainGeneratorType;
import com.stonebreak.world.generation.biomes.BiomeType;
import com.stonebreak.world.generation.noise.TerrainNoise;
import com.stonebreak.world.operations.WorldConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Offline scout for the main menu showcase's pinned seed (opt-in: {@code -Dstonebreak.showcase.scout=true}).
 * Scores candidate seeds of the Standard generator over a 2 km window for what makes a good drone
 * reel — biome variety, peaks, relief, coastline — and writes, for the best few, a draft
 * {@code showcase.json} whose shots are aimed at the features it found (peak orbit, coast fly-by,
 * high establishing pan, valley push-in, biome-border crane...). The drafts are then eyeballed in
 * game ({@code -Dstonebreak.showcase.seed/.shot}) and one is pinned in {@value ShowcaseConfig#RESOURCE}.
 *
 * <pre>mvn test -pl stonebreak-game -Dtest=ShowcaseScoutTest -Dstonebreak.showcase.scout=true
 *     [-Dstonebreak.showcase.scout.seeds=200] [-Dstonebreak.showcase.scout.out=target/showcase-scout]</pre>
 *
 * Uses the noise backend the game would ({@link TerrainNoise#backend()}); terrain differs between
 * backends, which the runtime terrain lift ({@link FlyoverShot#liftAbove}) absorbs.
 */
class ShowcaseScoutTest {

    private static final int HALF = 1024;
    private static final int STRIDE = 16;
    private static final int N = HALF * 2 / STRIDE;
    private static final int SEA = WorldConfiguration.SEA_LEVEL;

    @Test
    void scout() throws IOException {
        assumeTrue(Boolean.getBoolean("stonebreak.showcase.scout"), "opt-in: -Dstonebreak.showcase.scout=true");
        int count = Integer.getInteger("stonebreak.showcase.scout.seeds", 200);
        Path out = Path.of(System.getProperty("stonebreak.showcase.scout.out", "target/showcase-scout"));
        Files.createDirectories(out);
        System.out.println("[scout] noise backend " + TerrainNoise.backend());

        List<Scan> scans = new ArrayList<>();
        java.util.Random seeds = new java.util.Random(0x5B5EEDL);
        for (int i = 0; i < count; i++) {
            long seed = Math.abs(seeds.nextLong() % 10_000_000_000L);
            scans.add(Scan.of(seed));
        }
        scans.sort((a, b) -> Double.compare(b.score, a.score));
        StringBuilder report = new StringBuilder();
        for (int i = 0; i < Math.min(12, scans.size()); i++) {
            report.append(scans.get(i).summary()).append('\n');
        }
        System.out.print(report);
        Files.writeString(out.resolve("ranking.txt"), report);
        for (int i = 0; i < Math.min(5, scans.size()); i++) {
            Scan s = scans.get(i);
            Files.writeString(out.resolve("showcase-" + s.seed + ".json"), s.draftShots());
            s.writeMap(out.resolve("map-" + s.seed + ".png"));
        }
        System.out.println("[scout] drafts in " + out.toAbsolutePath());
    }

    /**
     * Opt-in probe: {@code -Dstonebreak.showcase.probe=<seed>:<cx>:<cz>:<radius>} prints the terrain
     * height, water level and biome on a ring of 16 points (and the centre), for laying out an orbit.
     */
    @Test
    void probe() {
        String spec = System.getProperty("stonebreak.showcase.probe");
        assumeTrue(spec != null && !spec.isBlank(), "opt-in: -Dstonebreak.showcase.probe=<seed>:<cx>:<cz>:<radius>");
        String[] p = spec.split(":");
        long seed = Long.parseLong(p[0]);
        int cx = Integer.parseInt(p[1]), cz = Integer.parseInt(p[2]), r = Integer.parseInt(p[3]);
        TerrainGenerator t = TerrainGeneratorType.STANDARD.create(seed);
        System.out.printf(Locale.ROOT, "[probe] centre (%d, %d): h %d water %d %s%n", cx, cz,
                t.getFinalTerrainHeightAt(cx, cz), t.getWaterLevelAt(cx, cz), t.getBiomeAt(cx, cz));
        for (int k = 0; k < 16; k++) {
            double a = Math.PI * 2 * k / 16;
            int x = cx + (int) Math.round(Math.sin(a) * r), z = cz + (int) Math.round(Math.cos(a) * r);
            System.out.printf(Locale.ROOT, "[probe] bearing %5.1f (%d, %d): h %d water %d %s%n", Math.toDegrees(a), x, z,
                    t.getFinalTerrainHeightAt(x, z), t.getWaterLevelAt(x, z), t.getBiomeAt(x, z));
        }
    }

    /** One seed's 2 km window at {@value #STRIDE}-block resolution. */
    private static final class Scan {
        final long seed;
        final TerrainGenerator terrain;
        final int[] h = new int[N * N];
        final int[] water = new int[N * N];
        final BiomeType[] biome = new BiomeType[N * N];
        double score;
        final Map<BiomeType, Integer> share = new EnumMap<>(BiomeType.class);
        int peakIdx;
        int coastIdx = -1;
        int valleyIdx = -1;
        int borderIdx = -1;
        int establishIdx;
        double relief;
        double ocean;
        double coast;

        private Scan(long seed) {
            this.seed = seed;
            this.terrain = TerrainGeneratorType.STANDARD.create(seed);
        }

        static Scan of(long seed) {
            Scan s = new Scan(seed);
            s.sample();
            s.rate();
            return s;
        }

        int x(int i) { return -HALF + (i / N) * STRIDE; }
        int z(int i) { return -HALF + (i % N) * STRIDE; }
        int idx(int ix, int iz) { return ix * N + iz; }
        boolean wet(int i) { return water[i] > 0 && h[i] < water[i]; }

        void sample() {
            terrain.sampleRawColumns(-HALF, -HALF, N, STRIDE, h, water, null);
            for (int i = 0; i < N * N; i++) {
                biome[i] = terrain.getBiomeAt(x(i), z(i));
                share.merge(biome[i], 1, Integer::sum);
            }
        }

        void rate() {
            int land = 0;
            int wetCount = 0;
            int rough = 0;
            int shore = 0;
            int peak = -1;
            double bestCoast = -1;
            double bestValley = -1;
            double bestBorder = -1;
            double bestEstablish = -1;
            for (int ix = 2; ix < N - 2; ix++) {
                for (int iz = 2; iz < N - 2; iz++) {
                    int i = idx(ix, iz);
                    if (wet(i)) {
                        wetCount++;
                        continue;
                    }
                    land++;
                    if (peak < 0 || h[i] > h[peak]) {
                        peak = i;
                    }
                    int lo = Integer.MAX_VALUE;
                    int hi = Integer.MIN_VALUE;
                    boolean nearSea = false;
                    boolean nearSnow = false;
                    boolean nearGreen = false;
                    for (int dx = -2; dx <= 2; dx++) {
                        for (int dz = -2; dz <= 2; dz++) {
                            int j = idx(ix + dx, iz + dz);
                            lo = Math.min(lo, h[j]);
                            hi = Math.max(hi, h[j]);
                            nearSea |= wet(j) && biome[j] == BiomeType.OCEAN;
                            nearSnow |= cold(biome[j]);
                            nearGreen |= green(biome[j]);
                        }
                    }
                    int range = hi - lo;
                    if (range > 30) {
                        rough++;
                    }
                    if (nearSea) {
                        shore++;
                        double c = range + (h[i] - SEA) * 0.5;
                        if (c > bestCoast) { bestCoast = c; coastIdx = i; }
                    }
                    if (h[i] < SEA + 25 && range > 40 && !nearSea) {
                        double v = range - (h[i] - SEA);
                        if (v > bestValley) { bestValley = v; valleyIdx = i; }
                    }
                    if (nearSnow && nearGreen) {
                        double b = range + (green(biome[i]) ? 10 : 0);
                        if (b > bestBorder) { bestBorder = b; borderIdx = i; }
                    }
                    double e = range * 0.5 + (h[i] - SEA) * 0.2;
                    if (Math.abs(ix - N / 2) < N / 4 && Math.abs(iz - N / 2) < N / 4 && e > bestEstablish) {
                        bestEstablish = e;
                        establishIdx = i;
                    }
                }
            }
            peakIdx = Math.max(peak, 0);
            int total = Math.max(1, land + wetCount);
            ocean = (double) wetCount / total;
            relief = (double) rough / Math.max(1, land);
            coast = (double) shore / Math.max(1, land);
            int biomes = 0;
            for (Map.Entry<BiomeType, Integer> e : share.entrySet()) {
                if (e.getValue() >= N * N * 0.015) {
                    biomes++;
                }
            }
            double peakHeight = Math.min(1.0, (h[peakIdx] - SEA) / 140.0);
            double oceanFit = 1.0 - Math.min(1.0, Math.abs(ocean - 0.25) / 0.25);
            score = biomes * 1.0
                    + peakHeight * 4.0
                    + Math.min(1.0, relief / 0.3) * 3.0
                    + Math.min(1.0, coast / 0.08) * 2.5
                    + oceanFit * 2.0
                    + (borderIdx >= 0 ? 1.5 : 0)
                    + (valleyIdx >= 0 ? 1.0 : 0);
        }

        static boolean cold(BiomeType b) {
            return b == BiomeType.SNOWY_PLAINS || b == BiomeType.TUNDRA || b == BiomeType.ICE_FIELDS
                    || b == BiomeType.TAIGA || b == BiomeType.STONY_PEAKS;
        }

        static boolean green(BiomeType b) {
            return b == BiomeType.PLAINS || b == BiomeType.MEADOW || b == BiomeType.SWAMP;
        }

        String summary() {
            StringBuilder b = new StringBuilder();
            b.append(String.format(Locale.ROOT, "seed %d score %.2f peak %d relief %.2f ocean %.2f coast %.3f biomes:",
                    seed, score, h[peakIdx], relief, ocean, coast));
            share.entrySet().stream().sorted((a, c) -> c.getValue() - a.getValue())
                    .forEach(e -> b.append(String.format(Locale.ROOT, " %s %.0f%%", e.getKey(),
                            100.0 * e.getValue() / (N * N))));
            return b.toString();
        }

        /** Draft shots aimed at the found features (absolute coordinates; the runtime lift clears terrain). */
        String draftShots() {
            List<String> shots = new ArrayList<>();
            // 1. Peak orbit at golden hour.
            {
                int px = x(peakIdx), pz = z(peakIdx), ph = h[peakIdx];
                shots.add(shot(caption(peakIdx), "ORBIT",
                        new int[]{px + 110, ph + 35, pz}, null, new int[]{px, ph + 4, pz}, null,
                        "40", "[62, 56]", 14, 10600, "CLEAR"));
            }
            // 2. Coast fly-by along the shore, from the sea side.
            if (coastIdx >= 0) {
                int[] g = gradient(coastIdx); // points uphill (inland)
                int cx = x(coastIdx), cz = z(coastIdx);
                int sx = -g[0] * 45, sz = -g[1] * 45;      // seaward offset
                int ax = -g[1] * 70, az = g[0] * 70;       // along the coast
                shots.add(shot(caption(coastIdx), "LINEAR",
                        new int[]{cx + sx - ax, SEA + 24, cz + sz - az}, new int[]{cx + sx + ax, SEA + 30, cz + sz + az},
                        new int[]{cx - ax / 2, h[coastIdx] + 4, cz - az / 2}, new int[]{cx + ax, h[coastIdx] + 4, cz + az},
                        null, "[58, 58]", 13, 6000, "CLEAR"));
            }
            // 3. High establishing pan over the roughest centre ground, morning.
            {
                int ex = x(establishIdx), ez = z(establishIdx), eh = h[establishIdx];
                shots.add(shot(caption(establishIdx), "LINEAR",
                        new int[]{ex - 160, eh + 110, ez - 120}, new int[]{ex + 40, eh + 120, ez - 140},
                        new int[]{ex + 160, eh - 10, ez + 140}, new int[]{ex + 260, eh - 10, ez + 60},
                        null, "[66, 62]", 14, 1500, null));
            }
            // 4. Valley push-in toward the high ground.
            if (valleyIdx >= 0) {
                int[] g = gradient(valleyIdx);
                int vx = x(valleyIdx), vz = z(valleyIdx), vh = h[valleyIdx];
                shots.add(shot(caption(valleyIdx), "LINEAR",
                        new int[]{vx - g[0] * 60, vh + 10, vz - g[1] * 60}, new int[]{vx, vh + 14, vz},
                        new int[]{vx + g[0] * 120, vh + 40, vz + g[1] * 120}, null,
                        null, "[60, 52]", 12, 8000, null));
            }
            // 5. Crane up over the border of snow and green.
            if (borderIdx >= 0) {
                int bx = x(borderIdx), bz = z(borderIdx), bh = h[borderIdx];
                shots.add(shot(caption(borderIdx), "LINEAR",
                        new int[]{bx - 50, bh + 8, bz - 50}, new int[]{bx - 70, bh + 60, bz - 70},
                        new int[]{bx + 40, bh + 6, bz + 40}, new int[]{bx + 60, bh, bz + 60},
                        null, "[64, 70]", 13, 4000, null));
            }
            // 6. Late-afternoon orbit of the second highest region's slope (wide).
            {
                int px = x(peakIdx), pz = z(peakIdx), ph = h[peakIdx];
                shots.add(shot(caption(peakIdx), "ORBIT",
                        new int[]{px - 220, ph + 60, pz + 120}, null, new int[]{px, ph - 20, pz}, null,
                        "-30", "[70, 64]", 15, 9800, "CLOUDY"));
            }
            return "{\n  \"seed\": " + seed + ",\n  \"shots\": [\n" + String.join(",\n", shots) + "\n  ]\n}\n";
        }

        /**
         * Top-down map of the window: biome colour shaded by height (sea blue by depth), a grid line
         * every 256 blocks (origin brighter), the peak marked red. Pixel (px, py) = world
         * (-HALF + px*STRIDE, -HALF + py*STRIDE); x right, z down.
         */
        void writeMap(Path file) throws IOException {
            int scale = 4;
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(N * scale, N * scale,
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int ix = 0; ix < N; ix++) {
                for (int iz = 0; iz < N; iz++) {
                    int i = idx(ix, iz);
                    int rgb;
                    if (wet(i)) {
                        int d = Math.min(40, water[i] - h[i]);
                        rgb = rgb(30, 70 + (40 - d) * 2, 160 + (40 - d));
                    } else {
                        int c = color(biome[i]);
                        float shade = 0.55f + 0.45f * Math.min(1f, (h[i] - SEA) / 150f);
                        rgb = rgb((int) (((c >> 16) & 255) * shade), (int) (((c >> 8) & 255) * shade), (int) ((c & 255) * shade));
                    }
                    int wx = x(i), wz = z(i);
                    if (Math.floorMod(wx, 256) == 0 || Math.floorMod(wz, 256) == 0) {
                        rgb = (wx == 0 || wz == 0) ? 0xFFFFFF : 0x202020;
                    }
                    for (int a = 0; a < scale; a++) {
                        for (int b = 0; b < scale; b++) {
                            img.setRGB(ix * scale + a, iz * scale + b, rgb);
                        }
                    }
                }
            }
            int px = (peakIdx / N) * scale, pz = (peakIdx % N) * scale;
            for (int a = -6; a <= 6; a++) {
                for (int b = -1; b <= 1; b++) {
                    set(img, px + a, pz + b, 0xFF0000);
                    set(img, px + b, pz + a, 0xFF0000);
                }
            }
            javax.imageio.ImageIO.write(img, "png", file.toFile());
        }

        static void set(java.awt.image.BufferedImage img, int x, int y, int rgb) {
            if (x >= 0 && y >= 0 && x < img.getWidth() && y < img.getHeight()) img.setRGB(x, y, rgb);
        }

        static int rgb(int r, int g, int b) {
            return (Math.max(0, Math.min(255, r)) << 16) | (Math.max(0, Math.min(255, g)) << 8) | Math.max(0, Math.min(255, b));
        }

        static int color(BiomeType b) {
            return switch (b) {
                case PLAINS -> 0x7CB342;
                case MEADOW -> 0x9CCC65;
                case DESERT -> 0xE6D58A;
                case RED_SAND_DESERT -> 0xD2783C;
                case BADLANDS -> 0xB0522A;
                case SNOWY_PLAINS -> 0xF0F4F8;
                case TUNDRA -> 0xB8C4C8;
                case ICE_FIELDS -> 0xBFE3F5;
                case TAIGA -> 0x2E6B4A;
                case STONY_PEAKS -> 0x8A8A8A;
                case BEACH -> 0xF2E3A6;
                case SWAMP -> 0x4B5E2E;
                case OCEAN -> 0x2050A0;
            };
        }

        /** Unit direction (rounded to -1..1 per axis) of steepest ascent at a sample. */
        int[] gradient(int i) {
            int ix = i / N, iz = i % N;
            int gx = h[idx(Math.min(N - 1, ix + 1), iz)] - h[idx(Math.max(0, ix - 1), iz)];
            int gz = h[idx(ix, Math.min(N - 1, iz + 1))] - h[idx(ix, Math.max(0, iz - 1))];
            double len = Math.hypot(gx, gz);
            if (len < 1e-6) {
                return new int[]{1, 0};
            }
            return new int[]{(int) Math.round(gx / len), (int) Math.round(gz / len)};
        }

        String caption(int i) {
            String n = biome[i].name().toLowerCase(Locale.ROOT).replace('_', ' ');
            StringBuilder b = new StringBuilder();
            for (String w : n.split(" ")) {
                if (!b.isEmpty()) b.append(' ');
                b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
            return b.toString();
        }

        static String shot(String caption, String motion, int[] eyeFrom, int[] eyeTo, int[] targetFrom, int[] targetTo,
                           String sweep, String fov, int duration, long time, String weather) {
            StringBuilder b = new StringBuilder("    {\"caption\": \"").append(caption).append("\", \"motion\": \"")
                    .append(motion).append("\",\n     \"eyeFrom\": ").append(v(eyeFrom));
            if (eyeTo != null) b.append(", \"eyeTo\": ").append(v(eyeTo));
            b.append(",\n     \"targetFrom\": ").append(v(targetFrom));
            if (targetTo != null) b.append(", \"targetTo\": ").append(v(targetTo));
            if (sweep != null) b.append(", \"sweepDeg\": ").append(sweep);
            b.append(",\n     \"fov\": ").append(fov).append(", \"duration\": ").append(duration)
                    .append(", \"easing\": \"EaseInOutQuad\", \"time\": ").append(time);
            if (weather != null) b.append(", \"weather\": \"").append(weather).append('"');
            return b.append('}').toString();
        }

        static String v(int[] a) {
            return "[" + a[0] + ", " + a[1] + ", " + a[2] + "]";
        }
    }
}
