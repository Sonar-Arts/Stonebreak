package com.stonebreak.rendering.models.entities;

import com.openmason.engine.rendering.model.IndexedDrawBatch;
import com.stonebreak.mobs.sbe.*;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class SbeDrawPlanTest {
    private static final MaterialImage IMAGE = new MaterialImage(1, 1, new byte[]{1, 2, 3, -1});

    private static SbePart part(String id, SbeFace... faces) {
        return new SbePart(id, id, null, new Vector3f(), new Vector3f(), new Vector3f(),
                new Vector3f(1), List.of(faces));
    }

    @Test
    void coalescesWithinEachAnimatedPartAndSkipsOnlyUntexturedFaces() {
        SbePart first = part("head", new SbeFace(0, 7, 0, 6), new SbeFace(1, 7, 6, 3),
                new SbeFace(2, 99, 9, 3), new SbeFace(3, 7, 12, 6), new SbeFace(4, 8, 18, 3));
        SbePart second = part("jaw", new SbeFace(5, 8, 21, 3));
        var geometry = new SbeModelGeometry(new float[0], new float[0], new int[24],
                List.of(first, second), Map.of(7, IMAGE, 8, IMAGE, 123, IMAGE), 0, List.of());
        var plan = new SbeDrawPlan(geometry);

        assertEquals(List.of(new IndexedDrawBatch(7, 0, 9), new IndexedDrawBatch(7, 12, 6),
                new IndexedDrawBatch(8, 18, 3)), plan.forPart(first).textured());
        assertEquals(List.of(new IndexedDrawBatch(0, 0, 21)), plan.forPart(first).untextured());
        assertEquals(List.of(new IndexedDrawBatch(8, 21, 3)), plan.forPart(second).textured());
        assertEquals(List.of(new IndexedDrawBatch(0, 21, 3)), plan.forPart(second).untextured());
        assertEquals(java.util.Set.of(7, 8), plan.materialIds());
    }

    @Test
    void preservesSparseAndOutOfOrderUntexturedRanges() {
        SbePart part = part("sparse", new SbeFace(0, -1, 9, 3), new SbeFace(1, -1, 0, 3),
                new SbeFace(2, -1, 6, 3));
        var geometry = new SbeModelGeometry(new float[0], new float[0], new int[12],
                List.of(part), Map.of(), 0, List.of());
        var plan = new SbeDrawPlan(geometry);
        assertTrue(plan.forPart(part).textured().isEmpty());
        assertEquals(List.of(new IndexedDrawBatch(0, 9, 3), new IndexedDrawBatch(0, 0, 3),
                new IndexedDrawBatch(0, 6, 3)), plan.forPart(part).untextured());
    }

    @Test
    void rejectsRangesBeyondTheUploadedIndexBuffer() {
        var geometry = new SbeModelGeometry(new float[0], new float[0], new int[3],
                List.of(part("bad", new SbeFace(0, 1, 0, 6))), Map.of(1, IMAGE), 0, List.of());
        assertThrows(IllegalArgumentException.class, () -> new SbeDrawPlan(geometry));
    }

    @Test
    void shippedPlayerAndHairRetainEveryIndexAndMaterialWithOver99PercentFewerDraws() {
        for (String name : List.of("Mobs/SB_Player", "PlayerCustomize/SB_MHair1", "PlayerCustomize/SB_MHair2",
                "Player/SB_FPArm")) {
            var asset = SbeEntityLoader.loadAttachableResource("/sbe/" + name + ".sbe");
            var geometry = asset.geometryFor(null);
            var plan = new SbeDrawPlan(geometry);
            int faces = 0, batches = 0;
            var usedMaterials = new HashSet<Integer>();
            for (SbePart part : geometry.parts()) {
                var original = part.faces();
                faces += original.size();
                batches += plan.forPart(part).textured().size();
                // Compare the complete ordered stream of EBO positions, not
                // just triangle totals (which could hide missing/duplicate faces).
                int[] before = original.stream().flatMapToInt(f -> IntStream.range(f.indexStart(),
                        f.indexStart() + f.indexCount())).toArray();
                int[] after = plan.forPart(part).untextured().stream().flatMapToInt(b ->
                        IntStream.range(b.indexStart(), b.indexStart() + b.indexCount())).toArray();
                assertArrayEquals(before, after, name + " / " + part.name());
                int[] texturedBefore = original.stream().filter(f -> geometry.materials().containsKey(f.materialId()))
                        .flatMapToInt(f -> IntStream.range(f.indexStart(), f.indexStart() + f.indexCount())).toArray();
                int[] texturedAfter = plan.forPart(part).textured().stream().flatMapToInt(b ->
                        IntStream.range(b.indexStart(), b.indexStart() + b.indexCount())).toArray();
                assertArrayEquals(texturedBefore, texturedAfter, name + " / " + part.name());
                int[] materialsBefore = original.stream().filter(f -> geometry.materials().containsKey(f.materialId()))
                        .flatMapToInt(f -> IntStream.range(0, f.indexCount()).map(i -> f.materialId())).toArray();
                int[] materialsAfter = plan.forPart(part).textured().stream().flatMapToInt(b ->
                        IntStream.range(0, b.indexCount()).map(i -> b.materialId())).toArray();
                assertArrayEquals(materialsBefore, materialsAfter, name + " / " + part.name());
                for (SbeFace face : original) usedMaterials.add(face.materialId());
            }
            assertTrue(batches < faces / 100, name + ": " + faces + " -> " + batches);
            assertTrue(usedMaterials.containsAll(geometry.materials().keySet()), "Unused textures decoded");
            assertEquals(geometry.materials().keySet(), plan.materialIds());
            System.out.printf("SBE batches %s: %,d -> %,d draws; %d uploaded textures%n",
                    name, faces, batches, plan.materialIds().size());
        }
    }
}
