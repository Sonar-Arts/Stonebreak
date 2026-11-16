package com.stonebreak.world.physics;

import com.stonebreak.blocks.BlockType;
import com.stonebreak.blocks.waterSystem.WaterHeightUtil;
import com.stonebreak.world.World;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A side view of a slice of a world as the physics sees it, for the opt-in visual physics tests
 * ({@code PhysicsVisualTest}): every pixel is classified by asking the engine what occupies that
 * point (so the picture shows the shapes physics collides with, not the block textures), with
 * bodies, paths and rays drawn over it. Pure Java2D: no GL, runs headless.
 *
 * <p>The view is the plane {@code axis = slice} with the horizontal image axis along the other
 * horizontal world axis ({@link Axis#X}: z right; {@link Axis#Z}: x right) and y up.
 */
final class PhysicsSketch {

    enum Axis { X, Z }

    static final Color SOLID = new Color(0x6B6F76);
    static final Color PARTIAL = new Color(0x9AA0A8);
    static final Color PICK_ONLY = new Color(0x7FB069);
    static final Color DOOR = new Color(0xA0703C);
    static final Color WATER = new Color(0x3B7DD8);
    static final Color BODY = new Color(0xE8A33D);
    static final Color PATH = new Color(0xD9534F);
    static final Color RAY = new Color(0xC2185B);
    static final Color HIT = new Color(0xFFEB3B);

    private final World world;
    private final WorldPhysics physics;
    private final Axis axis;
    private final float slice;
    private final float minU, minY, maxU, maxY;
    private final int px;
    private final BufferedImage image;
    private final Graphics2D g;
    private final int header = 28;

    PhysicsSketch(World world, Axis axis, float slice, float minU, float minY, float maxU, float maxY, int pxPerBlock) {
        this.world = world;
        this.physics = WorldPhysics.of(world);
        this.axis = axis;
        this.slice = slice;
        this.minU = minU;
        this.minY = minY;
        this.maxU = maxU;
        this.maxY = maxY;
        this.px = pxPerBlock;
        int w = Math.round((maxU - minU) * px);
        int h = Math.round((maxY - minY) * px) + header;
        this.image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        this.g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0xF4F1EA));
        g.fillRect(0, 0, w, h);
        drawWorld();
        drawGrid();
    }

    private double sx(float u) {
        return (u - minU) * px;
    }

    private double sy(float y) {
        return header + (maxY - y) * px;
    }

    private float[] worldPoint(float u, float y) {
        return axis == Axis.X ? new float[] {slice, y, u} : new float[] {u, y, slice};
    }

    private void drawWorld() {
        float e = 0.5f / px;
        for (int iy = 0; iy < image.getHeight() - header; iy++) {
            float y = maxY - (iy + 0.5f) / px;
            for (int iu = 0; iu < image.getWidth(); iu++) {
                float u = minU + (iu + 0.5f) / px;
                float[] p = worldPoint(u, y);
                Color c = classify(p[0], p[1], p[2], e);
                if (c != null) {
                    image.setRGB(iu, iy + header, c.getRGB());
                }
            }
        }
    }

    private Color classify(float x, float y, float z, float e) {
        if (physics.overlaps(x - e, y - e, z - e, x + e, y + e, z + e, WorldPhysics.SOLID)) {
            int cx = (int) Math.floor(x), cy = (int) Math.floor(y), cz = (int) Math.floor(z);
            float top = physics.cellTop(cx, cy, cz, WorldPhysics.SOLID);
            return top > 0f && top < 1f ? PARTIAL : SOLID;
        }
        if (physics.overlaps(x - e, y - e, z - e, x + e, y + e, z + e, WorldPhysics.PANEL | WorldPhysics.DOOR_CELL)) {
            return DOOR;
        }
        if (physics.overlaps(x - e, y - e, z - e, x + e, y + e, z + e, WorldPhysics.PICK | WorldPhysics.MODEL_PICK)) {
            return PICK_ONLY;
        }
        int cx = (int) Math.floor(x), cy = (int) Math.floor(y), cz = (int) Math.floor(z);
        if (world.getBlockAt(cx, cy, cz) == BlockType.WATER) {
            int level = Math.max(0, world.getWaterLevelAt(cx, cy, cz));
            boolean covered = world.getBlockAt(cx, cy + 1, cz) == BlockType.WATER;
            if (covered || y - cy <= WaterHeightUtil.surfaceFraction(level)) {
                return WATER;
            }
        }
        return null;
    }

    private void drawGrid() {
        g.setColor(new Color(0, 0, 0, 28));
        g.setStroke(new BasicStroke(1f));
        for (int u = (int) Math.ceil(minU); u <= maxU; u++) {
            g.draw(new Line2D.Double(sx(u), header, sx(u), image.getHeight()));
        }
        for (int y = (int) Math.ceil(minY); y <= maxY; y++) {
            g.draw(new Line2D.Double(0, sy(y), image.getWidth(), sy(y)));
        }
    }

    private float u(float x, float z) {
        return axis == Axis.X ? z : x;
    }

    /** A body box at a position: half width along the view axis, from y - below to y + above. */
    void body(float x, float y, float z, float halfU, float below, float above, Color color, float alpha) {
        float uc = u(x, z);
        Rectangle2D r = new Rectangle2D.Double(sx(uc - halfU), sy(y + above), halfU * 2 * px, (above + below) * px);
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.round(alpha * 70)));
        g.fill(r);
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.round(alpha * 255)));
        g.setStroke(new BasicStroke(1.5f));
        g.draw(r);
    }

    /** A path through world points (x, y, z triplets). */
    void path(List<float[]> points, Color color) {
        if (points.size() < 2) return;
        Path2D.Double p = new Path2D.Double();
        float[] first = points.getFirst();
        p.moveTo(sx(u(first[0], first[2])), sy(first[1]));
        for (float[] q : points) {
            p.lineTo(sx(u(q[0], q[2])), sy(q[1]));
        }
        g.setColor(color);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(p);
    }

    /** A ray segment from a point to another, with a dot when it hit. */
    void ray(float[] from, float[] to, boolean hit) {
        g.setColor(new Color(RAY.getRed(), RAY.getGreen(), RAY.getBlue(), hit ? 220 : 90));
        g.setStroke(hit ? new BasicStroke(1.5f)
            : new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {4f, 4f}, 0f));
        g.draw(new Line2D.Double(sx(u(from[0], from[2])), sy(from[1]), sx(u(to[0], to[2])), sy(to[1])));
        if (hit) {
            dot(to, HIT);
        }
    }

    void dot(float[] p, Color color) {
        double cx = sx(u(p[0], p[2])), cy = sy(p[1]);
        g.setColor(Color.DARK_GRAY);
        g.fill(new java.awt.geom.Ellipse2D.Double(cx - 4, cy - 4, 8, 8));
        g.setColor(color);
        g.fill(new java.awt.geom.Ellipse2D.Double(cx - 3, cy - 3, 6, 6));
    }

    void title(String text) {
        g.setColor(new Color(0x22252A));
        g.fillRect(0, 0, image.getWidth(), header);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        g.drawString(text, 8, 19);
    }

    void note(String text, int line) {
        g.setColor(new Color(0x22252A));
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        g.drawString(text, 8, header + 14 + line * 13);
    }

    Path save(Path dir, String name) throws IOException {
        g.dispose();
        Files.createDirectories(dir);
        Path out = dir.resolve(name + ".png");
        ImageIO.write(image, "png", out.toFile());
        return out;
    }
}
