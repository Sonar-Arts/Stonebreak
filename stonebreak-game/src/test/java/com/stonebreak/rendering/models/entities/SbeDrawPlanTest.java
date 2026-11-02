package com.stonebreak.rendering.models.entities;

import com.openmason.engine.entity.sbe.SbeFace;
import com.openmason.engine.entity.sbe.SbePart;
import com.openmason.engine.rendering.model.draw.ModelDrawPlan;
import com.stonebreak.mobs.sbe.GameSbeAssets;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The engine draw plan ({@link ModelDrawPlan}, #90) over Stonebreak's shipped
 * player assets: every index and material survives batching, with >99% fewer
 * draws. The synthetic plan cases live in OpenMason's {@code ModelDrawPlanTest}.
 */
class SbeDrawPlanTest {

    @Test
    void shippedPlayerAndHairRetainEveryIndexAndMaterialWithOver99PercentFewerDraws() {
        for (String name : List.of("Mobs/SB_Player", "PlayerCustomize/SB_MHair1", "PlayerCustomize/SB_MHair2",
                "Player/SB_FPArm")) {
            var asset = GameSbeAssets.loadAttachableResource("/sbe/" + name + ".sbe");
            var geometry = asset.geometryFor(null);
            var plan = ModelDrawPlan.of(geometry);
            int faces = 0, batches = 0;
            var usedMaterials = new HashSet<Integer>();
            for (int p = 0; p < geometry.parts().size(); p++) {
                SbePart part = geometry.parts().get(p);
                var original = part.faces();
                faces += original.size();
                batches += plan.forPart(p).textured().size();
                // Compare the complete ordered stream of EBO positions, not
                // just triangle totals (which could hide missing/duplicate faces).
                int[] before = original.stream().flatMapToInt(f -> IntStream.range(f.indexStart(),
                        f.indexStart() + f.indexCount())).toArray();
                int[] after = plan.forPart(p).untextured().stream().flatMapToInt(b ->
                        IntStream.range(b.indexStart(), b.indexStart() + b.indexCount())).toArray();
                assertArrayEquals(before, after, name + " / " + part.name());
                int[] texturedBefore = original.stream().filter(f -> geometry.materials().containsKey(f.materialId()))
                        .flatMapToInt(f -> IntStream.range(f.indexStart(), f.indexStart() + f.indexCount())).toArray();
                int[] texturedAfter = plan.forPart(p).textured().stream().flatMapToInt(b ->
                        IntStream.range(b.indexStart(), b.indexStart() + b.indexCount())).toArray();
                assertArrayEquals(texturedBefore, texturedAfter, name + " / " + part.name());
                int[] materialsBefore = original.stream().filter(f -> geometry.materials().containsKey(f.materialId()))
                        .flatMapToInt(f -> IntStream.range(0, f.indexCount()).map(i -> f.materialId())).toArray();
                int[] materialsAfter = plan.forPart(p).textured().stream().flatMapToInt(b ->
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
