package com.stonebreak.ui.runtime.providers;

import com.openmason.engine.format.omui.OmuiArchive;
import com.openmason.engine.format.omui.UiDocument;
import com.openmason.engine.format.omui.UiManifest;
import com.openmason.engine.format.omui.UiNode;
import com.openmason.engine.format.omui.UiValue;
import com.openmason.engine.ui.data.UiHost;
import com.openmason.engine.ui.runtime.UiDocumentInstance;
import com.openmason.engine.ui.runtime.UiElement;
import com.openmason.engine.ui.runtime.UiRect;
import com.openmason.engine.ui.runtime.UiRuntimeContext;
import com.stonebreak.blocks.BlockType;
import com.stonebreak.items.ItemType;
import io.github.humbleui.types.Rect;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The GL-free parts of the item icon provider: what an element names and where the icon goes. */
class ItemIconProviderTest {

    private static UiElement element(String type, Map<String, UiValue> props) {
        UiNode node = new UiNode("icon", null, type, 1, List.of(), props, Map.of(), null, List.of(), null,
            List.of(), Map.of());
        UiNode root = UiNode.of("root", "Box", List.of(node));
        UiManifest m = UiManifest.create("stonebreak:ui/test_icons", UiManifest.DocumentKind.SCREEN, "Icons");
        OmuiArchive doc = OmuiArchive.of(m, new UiDocument(root, List.of(), null, null, Map.of()));
        UiDocumentInstance ui = UiDocumentInstance.instantiate(doc, UiRuntimeContext.basic());
        return ui.find("icon");
    }

    private static Map<String, UiValue> props(Object... kv) {
        Map<String, UiValue> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            Object v = kv[i + 1];
            m.put((String) kv[i], v instanceof UiValue u ? u
                : v instanceof Number n ? UiValue.of(n.doubleValue()) : UiValue.of((String) v));
        }
        return m;
    }

    @Test
    void typedSlotPropsNameTheItemAndCount() {
        UiElement el = element("ItemSlot", props("provider", ItemIconProvider.ID,
            "item", "stonebreak:dirt", "count", 12));
        ItemRef ref = ItemRef.of(el);
        assertNotNull(ref);
        assertSame(BlockType.DIRT, ref.item());
        assertEquals(12, ref.count());
        assertTrue(Double.isNaN(ref.durability()));
    }

    @Test
    void aBoundSlotRecordWorksLikeTheInventoryContractPublishesIt() {
        UiValue.Obj stack = UiValue.Obj.sorted(Map.of(
            "objectId", UiValue.of("stonebreak:stone"), "count", UiValue.of(3.0)));
        ItemRef ref = ItemRef.of(element("ItemSlot", props("stack", stack)));
        assertNotNull(ref);
        assertSame(BlockType.STONE, ref.item());
        assertEquals(3, ref.count());
    }

    @Test
    void hostSlotRecordsResolveByObjectIdOrNumericItem() {
        // contracts.SlotRecords shape: {slot, item, objectId, name, count, state, maxStack}
        UiValue.Obj withId = UiValue.Obj.sorted(Map.of("slot", UiValue.of("main:4"),
            "item", UiValue.of((double) BlockType.DIRT.getId()), "objectId", UiValue.of("stonebreak:dirt"),
            "name", UiValue.of("Dirt"), "count", UiValue.of(5.0), "state", UiValue.of(""), "maxStack", UiValue.of(64.0)));
        ItemRef a = ItemRef.of(element("ItemSlot", props("stack", withId)));
        assertNotNull(a);
        assertSame(BlockType.DIRT, a.item());
        assertEquals(5, a.count());
        assertNull(a.state(), "a blank state is no state");

        UiValue.Obj blankId = UiValue.Obj.sorted(Map.of("item", UiValue.of((double) BlockType.STONE.getId()),
            "objectId", UiValue.of(""), "count", UiValue.of(2.0)));
        assertSame(BlockType.STONE, ItemRef.of(element("ItemSlot", props("stack", blankId))).item());

        UiValue.Obj empty = UiValue.Obj.sorted(Map.of("item", UiValue.of(0.0), "objectId", UiValue.of(""),
            "count", UiValue.of(0.0)));
        assertNull(ItemRef.of(element("ItemSlot", props("stack", empty))), "an empty host slot shows nothing");
    }

    @Test
    void aDrawProviderReadsItsParams() {
        UiValue.Obj params = UiValue.Obj.sorted(Map.of(
            "item", UiValue.of(Integer.toString(BlockType.DIRT.getId())), "count", UiValue.of(64.0)));
        ItemRef ref = ItemRef.of(element("DrawProvider", props("provider", ItemIconProvider.ID, "params", params)));
        assertNotNull(ref);
        assertSame(BlockType.DIRT, ref.item(), "digit strings are numeric ids, blocks first");
        assertEquals(64, ref.count());
    }

    @Test
    void emptyUnknownAirAndZeroCountShowNothing() {
        assertNull(ItemRef.of(element("ItemSlot", props())));
        assertNull(ItemRef.of(element("ItemSlot", props("item", "stonebreak:no_such_thing"))));
        assertNull(ItemRef.of(element("ItemSlot", props("item", Integer.toString(BlockType.AIR.getId())))));
        assertNull(ItemRef.of(element("ItemSlot", props("item", "stonebreak:dirt", "count", 0))));
    }

    @Test
    void itemsResolveByObjectIdAndName() {
        ItemType anyItem = ItemType.all().stream().filter(t -> ItemType.objectIdFor(t) != null).findFirst().orElseThrow();
        assertSame(anyItem, ItemRef.resolve(ItemType.objectIdFor(anyItem)));
        assertSame(anyItem, ItemRef.resolve(UiValue.of(ItemType.objectIdFor(anyItem))));
        assertSame(BlockType.DIRT, ItemRef.resolve("DIRT"));
        assertSame(BlockType.DIRT, ItemRef.resolve("dirt"));
        assertNull(ItemRef.resolve(UiValue.of(true)));
    }

    @Test
    void slotIconsUseTheLegacyInsetAtEveryScale() {
        Rect r1 = ItemIconProvider.iconRect("ItemSlot", new UiRect(100, 50, 40, 40), 1f);
        assertEquals(Rect.makeXYWH(103, 53, 34, 34), r1);
        Rect r2 = ItemIconProvider.iconRect("ItemSlot", new UiRect(200, 100, 80, 80), 2f);
        assertEquals(Rect.makeXYWH(206, 106, 68, 68), r2);
    }

    @Test
    void bareProvidersFillAndCentreASquareOnWholePixels() {
        Rect r = ItemIconProvider.iconRect("DrawProvider", new UiRect(10.4f, 20.6f, 60, 40), 1f);
        assertEquals(40, r.getWidth());
        assertEquals(40, r.getHeight());
        assertEquals(20, r.getLeft()); // centred in 60
        assertEquals(21, r.getTop());
        assertNull(ItemIconProvider.iconRect("ItemSlot", new UiRect(0, 0, 6, 6), 1f), "nothing left after the inset");
    }

    @Test
    void declaredProvidersMatchTheImplementationsAndAreOffered() {
        try (GameDrawProviders set = new GameDrawProviders((t, x, y, s) -> { }, () -> null)) {
            assertEquals(GameDrawProviders.DECLARED.keySet(), set.providers().keySet());
        }
        UiHost host = new UiHost();
        GameDrawProviders.offerTo(host);
        assertEquals(ItemIconProvider.VERSION, host.profile().providers().get(ItemIconProvider.ID));
        assertEquals(EntityPreviewProvider.VERSION, host.profile().providers().get(EntityPreviewProvider.ID));
    }

    @Test
    void entityFramingMatchesTheLegacyOrbit() {
        float[] bounds = {-0.5f, 0f, -0.5f, 0.5f, 2f, 0.5f};
        EntityPreviewProvider.Orbit orbit = EntityPreviewProvider.frame(bounds, 0f, 0f, 1.25f, 1f);
        org.joml.Vector3f centre = orbit.view().transformPosition(new org.joml.Vector3f(0f, 1f, 0f));
        assertEquals(0f, centre.x, 1e-4f, "looks at the AABB centre");
        assertEquals(0f, centre.y, 1e-4f);
        assertTrue(centre.z < 0f, "in front of the camera");
    }
}
