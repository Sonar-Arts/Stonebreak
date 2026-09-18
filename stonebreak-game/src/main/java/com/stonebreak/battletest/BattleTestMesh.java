package com.stonebreak.battletest;

import com.openmason.engine.format.mesh.ParsedMaterialData;
import com.openmason.engine.format.omo.OMOReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import javax.imageio.ImageIO;
import org.joml.Vector3f;

/** CPU mesh for the authored solid-palette OMO and deterministic glacial surroundings. No GL dependencies. */
public record BattleTestMesh(float[] vertices, int authoredTriangles) {
    public static final int STRIDE = 10; // position, normal, RGB, emissive
    public static BattleTestMesh load(BattleTestArena arena) throws IOException {
        Builder b = new Builder();
        int authored;
        try (var in = BattleTestMesh.class.getResourceAsStream(arena.model())) {
            if (in == null)
                throw new IOException("Missing arena model: " + arena.model());
            var model = new OMOReader().read(in);
            // The editor overview is already baked to scene space. Reject an accidental unbaked replacement.
            for (var p : model.parts()) {
                if (p.posX() != 0 || p.posY() != 0 || p.posZ() != 0 || p.rotX() != 0 || p.rotY() != 0
                    || p.rotZ() != 0 || p.scaleX() != 1 || p.scaleY() != 1 || p.scaleZ() != 1 || !p.visible())
                    throw new IOException("Arena overview must have baked visible parts: " + p.name());
            }
            Map<Integer, float[]> palette = new HashMap<>();
            for (var m : model.materials()) palette.put(m.materialId(), color(m));
            Map<Integer, float[]> faces = new HashMap<>();
            for (var f : model.faceMappings()) faces.put(f.faceId(), palette.get(f.materialId()));
            var mesh = model.meshData();
            authored = mesh.getTriangleCount();
            for (int t = 0; t < authored; t++) {
                float[] c = faces.get(mesh.triangleToFaceId()[t]);
                if (c == null)
                    throw new IOException("Arena face has no material");
                Vector3f[] v = new Vector3f[3];
                for (int j = 0; j < 3; j++) {
                    int i = mesh.indices()[t * 3 + j] * 3;
                    v[j] = new Vector3f(mesh.vertices()[i], mesh.vertices()[i + 1], mesh.vertices()[i + 2]);
                }
                b.triangle(v[0], v[1], v[2], c);
            }
        }
        surroundings(b);
        marker(b, arena.playerSpawn(), false);
        marker(b, arena.archonSpawn(), true);
        snow(b);
        return new BattleTestMesh(b.finish(), authored);
    }
    private static float[] color(ParsedMaterialData m) throws IOException {
        if (m.texturePng() == null)
            throw new IOException("Missing arena palette texture");
        var image = ImageIO.read(new ByteArrayInputStream(m.texturePng()));
        if (image == null)
            throw new IOException("Unreadable arena texture");
        int c = image.getRGB(0, 0);
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = 0; x < image.getWidth(); x++)
                if (image.getRGB(x, y) != c)
                    throw new IOException("Arena material must be a solid palette swatch: " + m.name());
        return new float[] {
            ((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f, m.emissive() ? 1 : 0};
    }
    private static void snow(Builder b) {
        var random = new Random(9173);
        float[] color = {.8f, .93f, 1f, 2f};
        for (int i = 0; i < 650; i++) {
            float x = random.nextFloat() * 84 - 42;
            float y = random.nextFloat() * 25;
            float z = random.nextFloat() * 84 - 42;
            float r = .018f + random.nextFloat() * .022f;
            b.triangle(
                new Vector3f(x - r, y, z), new Vector3f(x + r, y, z), new Vector3f(x, y + r * 2, z), color);
            b.triangle(
                new Vector3f(x, y, z - r), new Vector3f(x, y, z + r), new Vector3f(x, y + r * 2, z), color);
        }
    }

    private static void surroundings(Builder b) {
        var random = new Random(0xF2057L);
        float[] snow = {.57f, .73f, .83f, 0}, ice = {.2f, .4f, .54f, 0};
        b.quad(new Vector3f(-300, -3.17f, -300), new Vector3f(-300, -3.17f, 300),
            new Vector3f(300, -3.17f, 300), new Vector3f(300, -3.17f, -300), snow);
        // Two irregular mountain rings create depth around every camera direction.
        for (int ring = 0; ring < 2; ring++)
            for (int i = 0; i < 36; i++) {
                double a = (i + random.nextFloat() * .6) * Math.PI * 2 / 36;
                float radius = ring == 0 ? 90 + random.nextFloat() * 30 : 170 + random.nextFloat() * 35;
                float x = (float) Math.cos(a) * radius, z = (float) Math.sin(a) * radius;
                float h = ring == 0 ? 22 + random.nextFloat() * 38 : 50 + random.nextFloat() * 60;
                float width = 20 + random.nextFloat() * 22;
                Vector3f peak = new Vector3f(x + width * .12f, h, z - width * .17f);
                for (int j = 0; j < 7; j++) {
                    double q = j * Math.PI * 2 / 7, r = (j + 1) * Math.PI * 2 / 7;
                    Vector3f v0 =
                        new Vector3f(x + (float) Math.cos(q) * width, -3.2f, z + (float) Math.sin(q) * width);
                    Vector3f v1 =
                        new Vector3f(x + (float) Math.cos(r) * width, -3.2f, z + (float) Math.sin(r) * width);
                    Vector3f s0 = new Vector3f(v0).lerp(peak, .48f + random.nextFloat() * .16f);
                    Vector3f s1 = new Vector3f(v1).lerp(peak, .48f + random.nextFloat() * .16f);
                    b.triangle(peak, s1, s0, snow);
                    b.quad(s0, s1, v1, v0, ice);
                }
            }
    }
    private static void marker(Builder b, BattleTestArena.Spawn s, boolean enemy) {
        float[] color = {s.r(), s.g(), s.b(), 1};
        float y = .032f;
        for (int i = 0; i < 96; i++) {
            double a = i * Math.PI * 2 / 96, c = (i + 1) * Math.PI * 2 / 96;
            b.quad(at(s, a, 1.2f, y), at(s, c, 1.2f, y), at(s, c, 1.1f, y), at(s, a, 1.1f, y), color);
        }
        // Player's arrow points north; the Archon's three-point crown faces south.
        if (!enemy) {
            b.triangle(new Vector3f(s.x(), y, s.z() - .65f), new Vector3f(s.x() - .4f, y, s.z() + .2f),
                new Vector3f(s.x() + .4f, y, s.z() + .2f), color);
        } else
            for (int i = -1; i <= 1; i++) {
                float x = s.x() + i * .32f;
                b.triangle(new Vector3f(x - .16f, y, s.z() - .35f), new Vector3f(x + .16f, y, s.z() - .35f),
                    new Vector3f(x, y, s.z() + .5f - (Math.abs(i) * .22f)), color);
            }
    }
    private static Vector3f at(BattleTestArena.Spawn s, double a, float r, float y) {
        return new Vector3f(s.x() + (float) Math.cos(a) * r, y, s.z() + (float) Math.sin(a) * r);
    }
    private static final class Builder {
        private float[] data = new float[1 << 20];
        private int size;
        void triangle(Vector3f a, Vector3f b, Vector3f c, float[] color) {
            Vector3f n = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
            if (n.lengthSquared() < 1e-16f)
                throw new IllegalArgumentException("Degenerate arena triangle");
            n.normalize();
            for (Vector3f p : new Vector3f[] {a, b, c}) {
                if (size + STRIDE > data.length)
                    data = Arrays.copyOf(data, data.length * 2);
                for (float f :
                    new float[] {p.x, p.y, p.z, n.x, n.y, n.z, color[0], color[1], color[2], color[3]}) {
                    if (!Float.isFinite(f))
                        throw new IllegalArgumentException("Nonfinite arena geometry");
                    data[size++] = f;
                }
            }
        }
        void quad(Vector3f a, Vector3f b, Vector3f c, Vector3f d, float[] color) {
            triangle(a, b, c, color);
            triangle(a, c, d, color);
        }
        float[] finish() {
            return Arrays.copyOf(data, size);
        }
    }
}
