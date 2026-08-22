package com.openmason.main.systems.mcp;

import com.openmason.main.systems.menus.textureCreator.canvas.PixelCanvas;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders a pixel buffer as a palette-indexed character grid — the
 * vision-free way for an LLM to "see" pixel art.
 *
 * <p>Every distinct opaque colour gets one glyph (most frequent first), the
 * legend maps glyph → RGBA/hex/rough colour name/count, and the grid is
 * printed with row/column rulers so coordinates can be read straight off the
 * text. Optional colour merging ({@code tolerance}) collapses near-identical
 * shades (e.g. noise speckle) into one glyph so the structure stays readable.
 *
 * <p>Also computes the structural facts a sighted reviewer would notice at a
 * glance: the opaque bounding box, mirror-symmetry scores, orphan pixels
 * (opaque with no 4-neighbour — reads as dirt), and per-row run-length
 * encoding for precise coordinate reasoning.
 *
 * <p>Pure function of its inputs; no editor state, no GL. Pixels use the
 * texture editor's packing ({@link PixelCanvas#packRGBA} — ABGR in the int),
 * so {@code PixelCanvas.getPixels()} can be passed straight through; every
 * channel access goes through {@link #alpha}/{@link PixelCanvas#unpackRGBA}.
 */
public final class PixelTextCodec {

    /** Glyph for fully transparent pixels. */
    public static final char TRANSPARENT = '.';

    /**
     * Glyph alphabet, in assignment order. Excludes '.' (transparent), space,
     * and characters that are easy to misread in a monospace dump.
     */
    static final String GLYPHS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz0123456789#@$%&*+=?!~^<>/";

    public static final int MAX_COLORS = GLYPHS.length();

    private PixelTextCodec() {
    }

    /** Options controlling the dump. All have sensible defaults via {@link #defaults()}. */
    public record Options(int tolerance, int maxColors, int alphaThreshold, boolean rle, boolean rulers,
                          boolean hexGrid) {

        public static Options defaults() {
            return new Options(0, MAX_COLORS, 8, false, true, false);
        }

        public Options(int tolerance, int maxColors, int alphaThreshold, boolean rle, boolean rulers) {
            this(tolerance, maxColors, alphaThreshold, rle, rulers, false);
        }

        public Options {
            if (tolerance < 0) throw new IllegalArgumentException("tolerance must be >= 0");
            if (maxColors < 1 || maxColors > MAX_COLORS) {
                throw new IllegalArgumentException("max_colors must be in [1, " + MAX_COLORS + "]");
            }
            if (alphaThreshold < 0 || alphaThreshold > 255) {
                throw new IllegalArgumentException("alpha_threshold must be in [0, 255]");
            }
        }
    }

    public record LegendEntry(String glyph, int r, int g, int b, int a, String hex,
                              String name, int count, double percent, int merged) {}

    public record Bounds(int x, int y, int width, int height) {}

    public record Symmetry(double horizontal, double vertical) {}

    public record Result(int x, int y, int width, int height,
                         int opaquePixels, int transparentPixels,
                         int distinctColors, int legendSize,
                         Bounds opaqueBounds,
                         Symmetry symmetry,
                         int orphanPixels, int[] orphanSample,
                         List<LegendEntry> legend,
                         List<String> rows,
                         String grid,
                         List<String> rle,
                         List<String> hexRows) {}

    /**
     * Describe a region of a packed-pixel buffer.
     *
     * @param pixels row-major {@code 0xRRGGBBAA} pixels of the whole buffer
     * @param bufWidth buffer width (pixels per row)
     * @param x region origin (absolute)
     * @param y region origin (absolute)
     * @param w region width
     * @param h region height
     */
    public static Result describe(int[] pixels, int bufWidth, int x, int y, int w, int h, Options opt) {
        if (w <= 0 || h <= 0) throw new IllegalArgumentException("Region must be non-empty");
        int[] region = new int[w * h];
        for (int yy = 0; yy < h; yy++) {
            System.arraycopy(pixels, (y + yy) * bufWidth + x, region, yy * w, w);
        }
        return describeRegion(region, x, y, w, h, opt);
    }

    /** Describe a flat row-major {@code [r,g,b,a, ...]} region (as returned by region reads). */
    public static Result describeRgba(int[] rgba, int x, int y, int w, int h, Options opt) {
        if (rgba.length != w * h * 4) {
            throw new IllegalArgumentException("rgba length " + rgba.length + " != " + (w * h * 4));
        }
        int[] packed = new int[w * h];
        for (int i = 0; i < packed.length; i++) {
            int o = i * 4;
            packed[i] = pack(rgba[o], rgba[o + 1], rgba[o + 2], rgba[o + 3]);
        }
        return describeRegion(packed, x, y, w, h, opt);
    }

    private static Result describeRegion(int[] px, int ox, int oy, int w, int h, Options opt) {
        int alphaMin = opt.alphaThreshold();

        // ---- count distinct opaque colours ----
        Map<Integer, Integer> counts = new HashMap<>();
        int opaque = 0;
        for (int p : px) {
            if (alpha(p) < alphaMin) continue;
            opaque++;
            counts.merge(p, 1, Integer::sum);
        }
        int distinct = counts.size();

        List<Map.Entry<Integer, Integer>> byFreq = new ArrayList<>(counts.entrySet());
        byFreq.sort(Comparator.<Map.Entry<Integer, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparingInt(Map.Entry::getKey));

        // ---- build clusters: frequent colours first, merging near shades into them ----
        List<int[]> clusterColor = new ArrayList<>();      // representative packed colour
        List<Integer> clusterCount = new ArrayList<>();
        List<Integer> clusterMerged = new ArrayList<>();
        Map<Integer, Integer> colorToCluster = new LinkedHashMap<>();
        long tol2 = (long) opt.tolerance() * opt.tolerance();
        for (Map.Entry<Integer, Integer> e : byFreq) {
            int c = e.getKey();
            int best = -1;
            long bestD = Long.MAX_VALUE;
            for (int i = 0; i < clusterColor.size(); i++) {
                long d = dist2(c, clusterColor.get(i)[0]);
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            boolean join = best >= 0 && (bestD <= tol2 || clusterColor.size() >= opt.maxColors());
            if (join) {
                colorToCluster.put(c, best);
                clusterCount.set(best, clusterCount.get(best) + e.getValue());
                clusterMerged.set(best, clusterMerged.get(best) + 1);
            } else {
                colorToCluster.put(c, clusterColor.size());
                clusterColor.add(new int[]{c});
                clusterCount.add(e.getValue());
                clusterMerged.add(0);
            }
        }

        // ---- legend: glyphs by merged pixel count (desc), then seed order — stable across packings ----
        int n = clusterColor.size();
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> {
            int cmp = Integer.compare(clusterCount.get(b), clusterCount.get(a));
            return cmp != 0 ? cmp : Integer.compare(a, b);
        });
        int[] glyphOf = new int[n];            // cluster index → glyph index
        for (int gi = 0; gi < n; gi++) glyphOf[order[gi]] = gi;
        List<LegendEntry> legend = new ArrayList<>(n);
        for (int gi = 0; gi < n; gi++) {
            int i = order[gi];
            int c = clusterColor.get(i)[0];
            int[] ch = PixelCanvas.unpackRGBA(c);
            int r = ch[0], g = ch[1], b = ch[2], a = ch[3];
            int count = clusterCount.get(i);
            legend.add(new LegendEntry(String.valueOf(GLYPHS.charAt(gi)), r, g, b, a,
                    String.format(Locale.ROOT, "#%02x%02x%02x%s", r, g, b, a == 255 ? "" : String.format(Locale.ROOT, "%02x", a)),
                    colorName(r, g, b, a),
                    count, opaque == 0 ? 0 : Math.round(count * 1000.0 / opaque) / 10.0,
                    clusterMerged.get(i)));
        }

        // ---- grid ----
        char[][] grid = new char[h][w];
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                int p = px[yy * w + xx];
                if (alpha(p) < alphaMin) {
                    grid[yy][xx] = TRANSPARENT;
                } else {
                    grid[yy][xx] = GLYPHS.charAt(glyphOf[colorToCluster.get(p)]);
                    if (xx < minX) minX = xx;
                    if (xx > maxX) maxX = xx;
                    if (yy < minY) minY = yy;
                    if (yy > maxY) maxY = yy;
                }
            }
        }
        Bounds bounds = maxX < 0 ? null
                : new Bounds(ox + minX, oy + minY, maxX - minX + 1, maxY - minY + 1);

        // ---- symmetry: fraction of mirrored pixel pairs that match (glyph-wise) ----
        Symmetry symmetry = new Symmetry(mirrorScore(grid, w, h, true), mirrorScore(grid, w, h, false));

        // ---- orphans ----
        List<Integer> orphans = new ArrayList<>();
        int orphanCount = 0;
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                if (grid[yy][xx] == TRANSPARENT) continue;
                boolean lone = (xx == 0 || grid[yy][xx - 1] == TRANSPARENT)
                        && (xx == w - 1 || grid[yy][xx + 1] == TRANSPARENT)
                        && (yy == 0 || grid[yy - 1][xx] == TRANSPARENT)
                        && (yy == h - 1 || grid[yy + 1][xx] == TRANSPARENT);
                if (lone) {
                    orphanCount++;
                    if (orphans.size() < 40) {
                        orphans.add(ox + xx);
                        orphans.add(oy + yy);
                    }
                }
            }
        }

        // ---- rows + rendered grid with rulers ----
        List<String> rows = new ArrayList<>(h);
        for (int yy = 0; yy < h; yy++) rows.add(new String(grid[yy]));
        String rendered = render(rows, ox, oy, w, h, opt.rulers());

        // ---- RLE ----
        List<String> rle = opt.rle() ? runLengths(grid, ox, oy, w, h) : null;

        // ---- exact per-pixel hex (unmerged colours), opt-in: "y3| ff0000 ff0000 ...... 00ff0080" ----
        List<String> hexRows = opt.hexGrid() ? hexRows(px, ox, oy, w, h, alphaMin) : null;

        return new Result(ox, oy, w, h, opaque, w * h - opaque, distinct, legend.size(),
                bounds, symmetry, orphanCount,
                orphans.stream().mapToInt(Integer::intValue).toArray(),
                legend, rows, rendered, rle, hexRows);
    }

    /** Grid with a two-line column ruler (tens / units) and a per-row {@code y|} prefix. */
    static String render(List<String> rows, int ox, int oy, int w, int h, boolean rulers) {
        StringBuilder sb = new StringBuilder((w + 8) * (h + 3));
        int labelW = String.valueOf(oy + h - 1).length();
        if (rulers) {
            String pad = " ".repeat(labelW + 1);
            if (ox + w - 1 >= 10) {
                sb.append(pad);
                for (int xx = 0; xx < w; xx++) {
                    int v = (ox + xx) / 10;
                    sb.append(v == 0 ? ' ' : (char) ('0' + v % 10));
                }
                sb.append('\n');
            }
            sb.append(pad);
            for (int xx = 0; xx < w; xx++) sb.append((char) ('0' + (ox + xx) % 10));
            sb.append('\n');
        }
        for (int yy = 0; yy < h; yy++) {
            if (rulers) {
                String label = String.valueOf(oy + yy);
                sb.append(" ".repeat(labelW - label.length())).append(label).append('|');
            }
            sb.append(rows.get(yy)).append('\n');
        }
        return sb.toString();
    }

    /**
     * One line per row: {@code y<N>|} then a space-separated hex cell per pixel —
     * {@code rrggbb} for opaque, {@code rrggbbaa} when translucent, {@code ......}
     * for transparent. Exact colours (tolerance merging does not apply), so a
     * reader can judge shading and ramps precisely.
     */
    private static List<String> hexRows(int[] px, int ox, int oy, int w, int h, int alphaMin) {
        List<String> out = new ArrayList<>(h);
        StringBuilder sb = new StringBuilder(w * 7 + 8);
        for (int yy = 0; yy < h; yy++) {
            sb.setLength(0);
            sb.append('y').append(oy + yy).append('|');
            for (int xx = 0; xx < w; xx++) {
                int p = px[yy * w + xx];
                int[] ch = PixelCanvas.unpackRGBA(p);
                if (xx > 0) sb.append(' ');
                if (ch[3] < alphaMin) {
                    sb.append("......");
                } else {
                    sb.append(String.format(Locale.ROOT, "%02x%02x%02x", ch[0], ch[1], ch[2]));
                    if (ch[3] != 255) sb.append(String.format(Locale.ROOT, "%02x", ch[3]));
                }
            }
            out.add(sb.toString());
        }
        return out;
    }

    private static List<String> runLengths(char[][] grid, int ox, int oy, int w, int h) {
        List<String> out = new ArrayList<>(h);
        for (int yy = 0; yy < h; yy++) {
            StringBuilder sb = new StringBuilder();
            sb.append("y").append(oy + yy).append(":");
            int xx = 0;
            boolean any = false;
            while (xx < w) {
                char c = grid[yy][xx];
                int start = xx;
                while (xx < w && grid[yy][xx] == c) xx++;
                if (c == TRANSPARENT) continue;
                any = true;
                int a = ox + start, b = ox + xx - 1;
                sb.append(' ').append(c).append('@').append(a);
                if (b != a) sb.append('-').append(b);
            }
            if (!any) sb.append(" (empty)");
            out.add(sb.toString());
        }
        return out;
    }

    private static double mirrorScore(char[][] grid, int w, int h, boolean leftRight) {
        int pairs = 0, match = 0;
        if (leftRight) {
            for (int yy = 0; yy < h; yy++) {
                for (int xx = 0; xx < w / 2; xx++) {
                    char a = grid[yy][xx], b = grid[yy][w - 1 - xx];
                    if (a == TRANSPARENT && b == TRANSPARENT) continue;
                    pairs++;
                    if (a == b) match++;
                }
            }
        } else {
            for (int yy = 0; yy < h / 2; yy++) {
                for (int xx = 0; xx < w; xx++) {
                    char a = grid[yy][xx], b = grid[h - 1 - yy][xx];
                    if (a == TRANSPARENT && b == TRANSPARENT) continue;
                    pairs++;
                    if (a == b) match++;
                }
            }
        }
        return pairs == 0 ? 1.0 : Math.round(match * 1000.0 / pairs) / 1000.0;
    }

    private static long dist2(int a, int b) {
        int[] x = PixelCanvas.unpackRGBA(a), y = PixelCanvas.unpackRGBA(b);
        long dr = x[0] - y[0], dg = x[1] - y[1], db = x[2] - y[2], da = x[3] - y[3];
        return dr * dr + dg * dg + db * db + da * da;
    }

    /** Alpha channel of a canvas-packed pixel. */
    static int alpha(int packed) {
        return (packed >>> 24) & 0xFF;
    }

    /** Pack in the texture editor's layout (delegates to {@link PixelCanvas#packRGBA}). */
    static int pack(int r, int g, int b, int a) {
        return PixelCanvas.packRGBA(r & 0xFF, g & 0xFF, b & 0xFF, a & 0xFF);
    }

    // ===================== Colour naming =====================

    private static String hueName(float hue) {
        if (hue < 15 || hue >= 345) return "red";
        if (hue < 45) return "orange";
        if (hue < 70) return "yellow";
        if (hue < 160) return "green";
        if (hue < 200) return "cyan";
        if (hue < 260) return "blue";
        if (hue < 300) return "purple";
        return "pink";
    }

    /**
     * Rough human colour name ("dark muted orange", "light gray", "white") so a
     * non-visual reader can reason about shading without decoding hex.
     */
    public static String colorName(int r, int g, int b, int a) {
        float[] hsl = rgbToHsl(r, g, b);
        float hue = hsl[0], sat = hsl[1], lum = hsl[2];
        String prefix = a < 255 ? "translucent " : "";
        if (lum >= 0.96f) return prefix + "white";
        if (lum <= 0.06f) return prefix + "black";
        if (sat < 0.10f) {
            return prefix + (lum < 0.3f ? "dark gray" : lum > 0.7f ? "light gray" : "gray");
        }
        String hueName = hueName(hue);
        // Low-saturation warm tones read as brown/tan to a human.
        if ((hueName.equals("orange") || hueName.equals("yellow") || hueName.equals("red"))
                && sat < 0.55f && lum < 0.55f) {
            hueName = "brown";
        } else if (hueName.equals("orange") && lum > 0.6f && sat < 0.6f) {
            hueName = "tan";
        }
        String light = lum < 0.25f ? "dark " : lum > 0.72f ? "light " : "";
        String mute = sat < 0.35f && !hueName.equals("brown") && !hueName.equals("tan") ? "muted " : "";
        return prefix + light + mute + hueName;
    }

    private static float[] rgbToHsl(int r, int g, int b) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf)), min = Math.min(rf, Math.min(gf, bf));
        float l = (max + min) / 2f;
        float h, s;
        if (max == min) {
            h = 0;
            s = 0;
        } else {
            float d = max - min;
            s = l > 0.5f ? d / (2f - max - min) : d / (max + min);
            if (max == rf) h = (gf - bf) / d + (gf < bf ? 6 : 0);
            else if (max == gf) h = (bf - rf) / d + 2;
            else h = (rf - gf) / d + 4;
            h *= 60f;
        }
        return new float[]{h, s, l};
    }

    // ===================== Reverse direction: text → pixels =====================

    /**
     * Parse a glyph grid back into pixels using a legend map (glyph → packed
     * RGBA). Rows may be shorter than {@code w} (remaining pixels are left
     * untouched), and '.' / ' ' mean "leave as is" unless {@code clearDots} is
     * set, in which case '.' writes transparent.
     *
     * @return flat [x,y,r,g,b,a, ...] pixel writes
     */
    public static int[] parseGrid(List<String> rows, Map<Character, Integer> legend,
                                  int ox, int oy, boolean clearDots) {
        List<Integer> out = new ArrayList<>();
        for (int yy = 0; yy < rows.size(); yy++) {
            String row = rows.get(yy);
            for (int xx = 0; xx < row.length(); xx++) {
                char c = row.charAt(xx);
                if (c == ' ') continue;
                Integer packed;
                if (c == TRANSPARENT) {
                    if (!clearDots) continue;
                    packed = 0;
                } else {
                    packed = legend.get(c);
                    if (packed == null) {
                        throw new IllegalArgumentException("Row " + yy + " col " + xx
                                + ": glyph '" + c + "' is not in the legend");
                    }
                }
                int[] ch = PixelCanvas.unpackRGBA(packed);
                out.add(ox + xx);
                out.add(oy + yy);
                out.add(ch[0]);
                out.add(ch[1]);
                out.add(ch[2]);
                out.add(ch[3]);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Parse a {@code "#rrggbb"} / {@code "#rrggbbaa"} / {@code "r,g,b[,a]"} colour string. */
    public static int parseColor(String s) {
        String t = s.trim();
        if (t.startsWith("#")) {
            String hex = t.substring(1);
            if (hex.length() == 6) hex += "ff";
            if (hex.length() != 8) throw new IllegalArgumentException("Bad hex colour: " + s);
            long v = Long.parseLong(hex, 16);
            return pack((int) (v >>> 24), (int) (v >>> 16), (int) (v >>> 8), (int) v);
        }
        String[] parts = t.split(",");
        if (parts.length < 3 || parts.length > 4) {
            throw new IllegalArgumentException("Colour must be #rrggbb[aa] or r,g,b[,a]: " + s);
        }
        int[] c = new int[4];
        c[3] = 255;
        for (int i = 0; i < parts.length; i++) c[i] = Integer.parseInt(parts[i].trim());
        return pack(c[0], c[1], c[2], c[3]);
    }

    /** Legend from a JSON-ish map {@code {"A": "#ff0000", "B": "12,34,56"}}. */
    public static Map<Character, Integer> legendFrom(Map<String, String> legend) {
        Map<Character, Integer> out = new HashMap<>();
        for (Map.Entry<String, String> e : legend.entrySet()) {
            if (e.getKey().length() != 1 || e.getKey().charAt(0) == TRANSPARENT) {
                throw new IllegalArgumentException("Legend keys must be single non-'.' characters: '"
                        + e.getKey() + "'");
            }
            out.put(e.getKey().charAt(0), parseColor(e.getValue()));
        }
        return out;
    }

    static String debugGrid(Result r) {
        return r.grid() + Arrays.toString(r.legend().toArray());
    }
}
