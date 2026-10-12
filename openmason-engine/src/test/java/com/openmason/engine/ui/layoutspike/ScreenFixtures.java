package com.openmason.engine.ui.layoutspike;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.openmason.engine.ui.layoutspike.FlexTree.*;

/**
 * The #283 layout fixtures: the pause menu and the furnace screen, each as
 * (a) a legacy oracle transcribed from today's hand-written layout math
 * (pinned to commit 2b4bcc91, file:line per formula) and (b) a flexbox tree
 * with absolute positioning that should reproduce it. Rects are framebuffer
 * pixels {x, y, w, h}; style values are design tokens resolved at the
 * current UI scale exactly as the legacy code resolves them.
 */
final class ScreenFixtures {

    private ScreenFixtures() {
    }

    /** One screen: named legacy rects plus the flex tree and its named nodes. */
    record Screen(Map<String, float[]> legacy, FlexTree tree, Map<String, Integer> nodes) {
    }

    // ───────────────────────────── pause menu ─────────────────────────────

    /**
     * Legacy: stonebreak-game ui/pauseMenu/SkijaPauseMenuRenderer.java:52-95 and
     * ui/PauseMenu.java:52-116 (hit-test uses the same rects). All float math.
     */
    static Screen pause(int fbW, int fbH, float s, boolean online) {
        Map<String, float[]> legacy = new LinkedHashMap<>();
        float cx = fbW / 2f;
        float cy = fbH / 2f;                                          // :76-77
        float pw = 520f * s, ph = 560f * s;                           // :65-66
        legacy.put("panel", new float[]{cx - pw / 2f, cy - ph / 2f, pw, ph}); // :78-81
        float bw = 360f * s, bh = 50f * s, bx = cx - bw / 2f;         // :63-64, :85
        String[] order = online
            ? new String[]{"resume", "statistics", "glossary", "settings", "resync", "quit"}
            : new String[]{"resume", "statistics", "glossary", "settings", "quit"};
        int count = order.length;                                     // PauseMenu.java:56-58
        for (int i = 0; i < count; i++) {
            float offset = (i - (count - 1) / 2f) * 70f;              // :52-54
            legacy.put(order[i], new float[]{bx, cy + offset * s, bw, bh});
        }

        // Flex: the window centres a fixed panel; the panel's column centres the
        // buttons with a 20 px gap. The legacy offsets place button TOPS on the
        // 70 px pitch around cy, i.e. the group sits 25 px (bh/2) low: a 50 px
        // top padding reproduces that. Resync collapses with display:none.
        FlexTree t = new FlexTree();
        Map<String, Integer> nodes = new LinkedHashMap<>();
        int root = t.add(-1);
        t.size(root, fbW, fbH).justify(root, J_CENTER).alignItems(root, A_CENTER);
        int panel = t.add(root);
        t.size(panel, pw, ph).column(panel).justify(panel, J_CENTER).alignItems(panel, A_CENTER)
            .gap(panel, 20f * s, 0).set(panel, PADDING + TOP, 50f * s);
        nodes.put("panel", panel);
        for (String id : new String[]{"resume", "statistics", "glossary", "settings", "resync", "quit"}) {
            int b = t.add(panel);
            t.size(b, bw, bh);
            if (id.equals("resync")) {
                t.hidden(b, !online);
            }
            nodes.put(id, b);
        }
        nodes.keySet().retainAll(legacy.keySet());
        return new Screen(legacy, t, nodes);
    }

    // ──────────────────────────── furnace screen ────────────────────────────

    /**
     * Legacy: stonebreak-game ui/inventoryScreen/core/InventoryLayoutCalculator.java:271-337
     * (calculateWorkbenchLayout), ui/furnace/core/FurnaceLayout.java:43-82,
     * ui/furnace/renderers/FurnaceRenderCoordinator.java:486-501. Tokens are
     * Math.round(K * uiScale) ints (:149-153); every /2 on ints truncates.
     */
    static Screen furnace(int fbW, int fbH, float s) {
        int ss = Math.round(40 * s), pad = Math.round(8 * s), sp = Math.round(24 * s);
        int th = Math.round(36 * s), pp = Math.round(20 * s);
        int baseW = 9 * (ss + pad) + pad + 2 * pp;                          // :279
        int gridVW = 3 * (ss + pad) - pad;                                  // :282
        int recipeW = Math.max(Math.round(100 * s), (int) (baseW * 0.28f)); // :285-286
        int craftW = gridVW + pad + ss + pad + ss + 2 * pad + recipeW;      // :289
        int panelW = Math.max(baseW, craftW + 2 * pp);                      // :291
        int gridH = 3 * (ss + pad) + pad;                                   // :294
        int mainH = 3 * (ss + pad) + pad, hotH = ss + pad, playerH = mainH + hotH + sp;
        int panelH = th + gridH + th + playerH + 3 * sp + 2 * pp;           // :299
        int panelX = (fbW - panelW) / 2, panelY = (fbH - panelH) / 2;       // :301-302
        int craftingGridStartY = panelY + pp + th + sp;                     // :305
        int playerInvTitleY = craftingGridStartY + gridH + sp + th / 2;     // :315
        int mainInvContentStartY = playerInvTitleY + th / 2 + sp;           // :316
        int hotbarRowY = mainInvContentStartY + 3 * (ss + pad) + sp;        // :319
        int invGridW = 9 * (ss + pad) - pad;
        int invStartX = panelX + (panelW - invGridW) / 2;                   // :331-332

        Map<String, float[]> legacy = new LinkedHashMap<>();
        legacy.put("panel", new float[]{panelX, panelY, panelW, panelH});
        float sectionTop = craftingGridStartY;                              // FurnaceLayout :53-54
        float sectionH = 3 * (ss + pad) + pad;                              // :55
        float cxF = panelX + panelW / 2f, cyF = sectionTop + sectionH / 2f; // :57-58
        float radius = ss + pad;                                            // :62
        legacy.put("ingredient", new float[]{Math.round(cxF - ss / 2f), Math.round(cyF - radius - ss / 2f), ss, ss});
        legacy.put("fuel", new float[]{Math.round(cxF - ss / 2f), Math.round(cyF + radius - ss / 2f), ss, ss});
        legacy.put("output", new float[]{Math.round(cxF + radius - ss / 2f), Math.round(cyF - ss / 2f), ss, ss});
        for (int i = 0; i < 27; i++) {                                      // RenderCoordinator :486-493
            legacy.put("main" + i, new float[]{invStartX + pad + (i % 9) * (ss + pad),
                mainInvContentStartY + pad + (i / 9) * (ss + pad), ss, ss});
        }
        for (int i = 0; i < 9; i++) {                                       // :495-501
            legacy.put("hot" + i, new float[]{invStartX + pad + i * (ss + pad), hotbarRowY, ss, ss});
        }

        // Flex: a column panel sized by its content (the hotbar row) with a
        // uniform sp gap. Legacy quirks are expressed as data, not code:
        //  - the grid sits `pad` right of centre (slot formula adds +pad after
        //    centring) -> position:relative left = pad;
        //  - the inventory title spans 2*(th/2) (int halves) but the panel
        //    height counts a full th, plus 2*pad of slack -> bottom padding.
        if (panelW != baseW) {
            throw new IllegalStateException("crafting width wins at scale " + s + "; fixture assumes baseW");
        }
        FlexTree t = new FlexTree();
        Map<String, Integer> nodes = new LinkedHashMap<>();
        int root = t.add(-1);
        t.size(root, fbW, fbH).justify(root, J_CENTER).alignItems(root, A_CENTER);
        int panel = t.add(root);
        t.column(panel).gap(panel, sp, 0)
            .edges(panel, PADDING, pp + pad, pp, pp + pad, pp + 2 * pad + (th - 2 * (th / 2)));
        nodes.put("panel", panel);
        int title = t.add(panel);
        t.set(title, HEIGHT, th);
        int section = t.add(panel);
        t.set(section, HEIGHT, sectionH);
        slot(t, nodes, "ingredient", section, ss, -ss / 2f, -radius - ss / 2f);
        slot(t, nodes, "fuel", section, ss, -ss / 2f, radius - ss / 2f);
        slot(t, nodes, "output", section, ss, radius - ss / 2f, -ss / 2f);
        int invTitle = t.add(panel);
        t.set(invTitle, HEIGHT, 2 * (th / 2));
        int grid = t.add(panel);
        t.row(grid).set(grid, WRAP, WRAP_ON).gap(grid, pad, pad).set(grid, PADDING + TOP, pad)
            .set(grid, WIDTH, invGridW).set(grid, ALIGN_SELF, A_CENTER).set(grid, POS + LEFT, pad);
        for (int i = 0; i < 27; i++) {
            int c = t.add(grid);
            t.size(c, ss, ss);
            nodes.put("main" + i, c);
        }
        int hot = t.add(panel);
        t.row(hot).gap(hot, 0, pad).set(hot, ALIGN_SELF, A_CENTER).set(hot, POS + LEFT, pad);
        for (int i = 0; i < 9; i++) {
            int c = t.add(hot);
            t.size(c, ss, ss);
            nodes.put("hot" + i, c);
        }
        return new Screen(legacy, t, nodes);
    }

    /** Absolute slot at the section's centre plus an offset (left/top 50% + margins). */
    private static void slot(FlexTree t, Map<String, Integer> nodes, String id, int section, int ss,
                             float dx, float dy) {
        int c = t.add(section);
        t.size(c, ss, ss).set(c, POSITION_TYPE, 1)
            .set(c, POS_PCT + LEFT, 50).set(c, POS_PCT + TOP, 50)
            .set(c, MARGIN + LEFT, dx).set(c, MARGIN + TOP, dy);
        nodes.put(id, c);
    }
}
