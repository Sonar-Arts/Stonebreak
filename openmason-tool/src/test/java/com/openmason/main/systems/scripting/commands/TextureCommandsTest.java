package com.openmason.main.systems.scripting.commands;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openmason.engine.rendering.model.gmr.uv.MaterialDefinition;
import com.openmason.main.systems.scripting.doc.FakeFacePixelStore;
import com.openmason.main.systems.scripting.doc.HeadlessModelDocument;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureCommandsTest {

    private static final int[] RED = {255, 0, 0, 255};
    private static final int[] GREEN = {0, 255, 0, 255};
    private static final int[] BLUE = {0, 0, 255, 255};
    private static final int[] WHITE = {255, 255, 255, 255};

    private HeadlessModelDocument inner;
    private FakeFacePixelStore store;
    private ModelCommands cmds;

    @BeforeEach
    void setUp() {
        inner = new HeadlessModelDocument();
        store = new FakeFacePixelStore(inner.faceTextures());
        cmds = new ModelCommands(FakeFacePixelStore.document(inner, store), new ObjectMapper());
        cmds.createPart("cube", "body", new Vector3f(2, 2, 2), null, null, null);
    }

    private int textureIdOf(int materialId) {
        return inner.faceTextures().getMaterial(materialId).textureId();
    }

    // ===================== create =====================

    @Test
    void createSharesOneMaterialAcrossAllSelectedFacesAndFills() {
        TextureCommands.CreateInfo created = cmds.tex().create(
                "body", new int[]{0, 1}, 16, 16, new int[]{200, 40, 10, 255}, "skin");
        assertEquals("skin", created.name());
        assertEquals(2, created.faces());
        assertEquals(16, created.width());
        assertEquals(16, created.height());

        TextureCommands.TextureInfo face0 = cmds.tex().info("body", 0);
        TextureCommands.TextureInfo face1 = cmds.tex().info("body", 1);
        assertTrue(face0.mapped());
        assertTrue(face1.mapped());
        assertEquals(face0.material(), face1.material(),
                "create must allocate ONE shared material for the whole selection");
        assertEquals("skin", face0.materialName());
        assertNotEquals(MaterialDefinition.DEFAULT.materialId(), face0.material());

        // Store texture was created filled with the color.
        int textureId = textureIdOf(face0.material());
        assertArrayEquals(new int[]{200, 40, 10, 255}, store.pixel(textureId, 0, 0));
        assertArrayEquals(new int[]{200, 40, 10, 255}, store.pixel(textureId, 15, 15));

        // Painting via one face is visible when reading via the other.
        cmds.tex().fill("body", 0, null, RED);
        assertArrayEquals(RED, cmds.tex().region("body", 1, 3, 3, 1, 1).rgba());
    }

    @Test
    void createRejectsBadSizesMappedFacesAndDuplicateNames() {
        // Size out of [1, 1024].
        CommandException zero = assertThrows(CommandException.class,
                () -> cmds.tex().create("body", new int[]{0}, 0, 16, null, null));
        assertTrue(zero.getMessage().contains("size"));
        assertThrows(CommandException.class,
                () -> cmds.tex().create("body", new int[]{0}, 1025, 16, null, null));

        cmds.tex().create("body", new int[]{0}, 8, 8, null, "skin");

        // Face 0 already carries a non-default material.
        CommandException mapped = assertThrows(CommandException.class,
                () -> cmds.tex().create("body", new int[]{0, 2}, 8, 8, null, null));
        assertTrue(mapped.getMessage().contains("already has a texture"));
        assertTrue(mapped.hint().contains("om.tex"));

        // Material names are unique.
        CommandException dupName = assertThrows(CommandException.class,
                () -> cmds.tex().create("body", new int[]{2}, 8, 8, null, "skin"));
        assertTrue(dupName.getMessage().contains("skin"));
    }

    @Test
    void headlessDocumentWithoutPixelsGetsTeachingError() {
        ModelCommands plain = new ModelCommands(new HeadlessModelDocument(), new ObjectMapper());
        plain.createPart("cube", "p", null, null, null, null);
        CommandException e = assertThrows(CommandException.class,
                () -> plain.tex().create("p", new int[]{0}, 8, 8, null, null));
        assertTrue(e.getMessage().contains("live"), "must point at the live editor");
        assertTrue(e.hint().contains("headless"));
    }

    // ===================== Painting =====================

    @Test
    void paintPrimitivesMutateTheStoreBytes() {
        int materialId = cmds.tex().create("body", new int[]{0}, 8, 8, WHITE, null).material();
        int textureId = textureIdOf(materialId);

        // fill of a sub-rect
        int filled = cmds.tex().fill("body", 0, new int[]{2, 2, 2, 2}, RED);
        assertEquals(4, filled);
        assertArrayEquals(RED, store.pixel(textureId, 2, 2));
        assertArrayEquals(RED, store.pixel(textureId, 3, 3));
        assertArrayEquals(WHITE, store.pixel(textureId, 0, 0));
        // region() (the CPU mirror) agrees with the flushed store bytes.
        assertArrayEquals(RED, cmds.tex().region("body", 0, 2, 2, 1, 1).rgba());

        // outline rect: corners painted, interior untouched
        cmds.tex().rect("body", 0, new int[]{0, 0, 5, 5}, GREEN, false);
        assertArrayEquals(GREEN, store.pixel(textureId, 0, 0));
        assertArrayEquals(GREEN, store.pixel(textureId, 4, 4));
        assertArrayEquals(WHITE, store.pixel(textureId, 1, 1));

        // line along the anti-diagonal
        cmds.tex().line("body", 0, 7, 0, 0, 7, BLUE);
        assertArrayEquals(BLUE, store.pixel(textureId, 7, 0));
        assertArrayEquals(BLUE, store.pixel(textureId, 0, 7));

        // set_pixels
        int changed = cmds.tex().setPixels("body", 0, new int[]{6, 6, 1, 2, 3, 4});
        assertEquals(1, changed);
        assertArrayEquals(new int[]{1, 2, 3, 4}, store.pixel(textureId, 6, 6));
    }

    @Test
    void floodFillsTheConnectedRegionOnly() {
        int materialId = cmds.tex().create("body", new int[]{0}, 8, 8, WHITE, null).material();
        int textureId = textureIdOf(materialId);
        // Black wall down column 4 splits the texture in two.
        cmds.tex().fill("body", 0, new int[]{4, 0, 1, 8}, new int[]{0, 0, 0, 255});

        int changed = cmds.tex().flood("body", 0, 0, 0, RED);
        assertEquals(4 * 8, changed, "only the left region floods");
        assertArrayEquals(RED, store.pixel(textureId, 0, 0));
        assertArrayEquals(RED, store.pixel(textureId, 3, 7));
        assertArrayEquals(new int[]{0, 0, 0, 255}, store.pixel(textureId, 4, 4));
        assertArrayEquals(WHITE, store.pixel(textureId, 5, 0));
    }

    @Test
    void paintValidationTeaches() {
        cmds.tex().create("body", new int[]{0}, 8, 8, null, null);
        // Bad pixel arrays and colors.
        assertThrows(CommandException.class,
                () -> cmds.tex().setPixels("body", 0, new int[]{1, 2, 3}));
        assertThrows(CommandException.class,
                () -> cmds.tex().fill("body", 0, null, new int[]{1, 2, 3}));
        assertThrows(CommandException.class,
                () -> cmds.tex().fill("body", 0, null, new int[]{300, 0, 0, 255}));
        assertThrows(CommandException.class,
                () -> cmds.tex().rect("body", 0, new int[]{0, 0, 0, 4}, RED, true));
        // Painting an unmapped face teaches how to create.
        CommandException noTexture = assertThrows(CommandException.class,
                () -> cmds.tex().fill("body", 3, null, RED));
        assertTrue(noTexture.getMessage().contains("no texture"));
        assertTrue(noTexture.hint().contains("texture_create"));
    }

    // ===================== resize =====================

    @Test
    void resizeReregistersMaterialOnFreshTextureAndScalesContent() {
        int materialId = cmds.tex().create("body", new int[]{0}, 16, 16, RED, null).material();
        int oldTextureId = textureIdOf(materialId);

        TextureCommands.TextureInfo resized = cmds.tex().resize("body", 0, 32, 32);
        assertEquals(32, resized.width());
        assertEquals(32, resized.height());
        assertEquals(materialId, resized.material(), "material id is stable across resize");

        int newTextureId = textureIdOf(materialId);
        assertNotEquals(oldTextureId, newTextureId, "resize must move to a fresh texture");
        assertTrue(store.hasTexture(newTextureId));

        // Content scale preserved: a solid red 16x16 is still red at the corners.
        assertArrayEquals(RED, store.pixel(newTextureId, 0, 0));
        assertArrayEquals(RED, store.pixel(newTextureId, 31, 31));
        assertArrayEquals(RED, cmds.tex().region("body", 0, 31, 0, 1, 1).rgba());

        assertThrows(CommandException.class, () -> cmds.tex().resize("body", 0, 0, 32));
        assertThrows(CommandException.class, () -> cmds.tex().resize("body", 0, 32, 2048));
    }

    // ===================== Queries =====================

    @Test
    void infoReportsUnmappedFacesAndRegionValidatesBounds() {
        TextureCommands.TextureInfo unmapped = cmds.tex().info("body", 0);
        assertFalse(unmapped.mapped());

        cmds.tex().create("body", new int[]{0}, 8, 8, null, null);
        assertThrows(CommandException.class, () -> cmds.tex().region("body", 0, 5, 5, 8, 8));
        assertThrows(CommandException.class, () -> cmds.tex().region("body", 0, -1, 0, 2, 2));
        assertThrows(CommandException.class, () -> cmds.tex().region("body", 0, 0, 0, 0, 1));

        TextureCommands.Region region = cmds.tex().region("body", 0, 6, 6, 2, 2);
        assertEquals(2 * 2 * 4, region.rgba().length);
    }

    // ===================== Trace =====================

    @Test
    void textureOpsAreTraced() {
        cmds.tex().create("body", new int[]{0}, 8, 8, RED, "skin");
        cmds.tex().fill("body", 0, null, GREEN);
        cmds.tex().line("body", 0, 0, 0, 7, 7, BLUE);
        cmds.tex().setPixels("body", 0, new int[]{1, 1, 9, 9, 9, 255});
        cmds.tex().resize("body", 0, 16, 16);

        List<String> ops = cmds.opsTrace().stream().map(n -> n.get("op").asText()).toList();
        assertEquals(List.of("create_part", "texture_create", "texture_fill",
                "texture_line", "texture_set_pixels", "texture_resize"), ops);

        var fill = cmds.opsTrace().get(2);
        assertEquals("body", fill.get("part").asText());
        assertEquals(0, fill.get("face").asInt());
        var create = cmds.opsTrace().get(1);
        assertEquals("body", create.get("part").asText());
        assertEquals(0, create.get("faces").get(0).asInt());
        assertEquals("skin", create.get("name").asText());
    }
}
