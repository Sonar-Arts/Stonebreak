package com.openmason.main.systems.uiEditor.service;

import com.openmason.engine.format.omui.OmuiFormat;
import com.openmason.engine.format.omui.UiSpriteSheet;
import com.openmason.engine.format.omui.UiSpriteSheet.Skin;
import com.openmason.engine.format.omui.UiSpriteSheet.Sprite;
import com.openmason.engine.format.omui.UiSpriteSheets;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * A sprite sheet being edited in the Sprites panel (#294): the sheet as loaded, the working
 * copy, the selected sprite or skin, and a local undo stack. Edits stay in the draft until the
 * panel applies it as one document step ({@link UiImageAssets#saveSheet}), like the Script
 * panel's Apply, so dragging a region never writes a file per frame.
 *
 * <p>Consecutive edits with the same merge key (one drag, one field) are one local undo step
 * until {@link #endInteraction()}. Renaming a sprite renames it in every skin, so a skin never
 * points at a name that no longer exists.
 */
public final class SpriteSheetDraft {

    private final String sheetId;
    private UiSpriteSheet base;
    private UiSpriteSheet sheet;
    private String selected;
    private final Deque<UiSpriteSheet> undo = new ArrayDeque<>();
    private final Deque<UiSpriteSheet> redo = new ArrayDeque<>();
    private String openMerge;

    public SpriteSheetDraft(String sheetId, UiSpriteSheet loaded) {
        this.sheetId = Objects.requireNonNull(sheetId, "sheetId");
        this.base = Objects.requireNonNull(loaded, "loaded");
        this.sheet = loaded;
    }

    public String sheetId() {
        return sheetId;
    }

    public UiSpriteSheet sheet() {
        return sheet;
    }

    public boolean dirty() {
        return !sheet.equals(base);
    }

    /** The draft was applied: what is now on disk / in the document becomes the clean state. */
    public void markApplied() {
        base = sheet;
    }

    /** Replaces the draft with a freshly loaded sheet (the file changed, or a revert). */
    public void reload(UiSpriteSheet loaded) {
        base = loaded;
        sheet = loaded;
        undo.clear();
        redo.clear();
        openMerge = null;
        if (selected != null && loaded.sprite(selected).isEmpty() && loaded.skin(selected).isEmpty()) {
            selected = null;
        }
    }

    public String selected() {
        return selected;
    }

    public void select(String name) {
        selected = name;
    }

    public Sprite selectedSprite() {
        return selected == null ? null : sheet.sprite(selected).orElse(null);
    }

    public Skin selectedSkin() {
        return selected == null ? null : sheet.skin(selected).orElse(null);
    }

    // ── edits ───────────────────────────────────────────────────────────────

    /** Applies {@code next} as an edit; same non-null {@code mergeKey} as the open one amends it. */
    public void edit(UiSpriteSheet next, String mergeKey) {
        if (next.equals(sheet)) {
            return;
        }
        if (mergeKey == null || !mergeKey.equals(openMerge)) {
            undo.push(sheet);
            redo.clear();
        }
        openMerge = mergeKey;
        sheet = next;
    }

    /** Seals the open merge (mouse up, field deactivated). */
    public void endInteraction() {
        openMerge = null;
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    public void undo() {
        if (!undo.isEmpty()) {
            redo.push(sheet);
            sheet = undo.pop();
            openMerge = null;
        }
    }

    public void redo() {
        if (!redo.isEmpty()) {
            undo.push(sheet);
            sheet = redo.pop();
            openMerge = null;
        }
    }

    /** Adds a sprite over {@code (x, y, w, h)} with a fresh name and selects it. */
    public String addSprite(int x, int y, int w, int h) {
        return addSprite(x, y, w, h, null);
    }

    /** As {@link #addSprite(int, int, int, int)}; a merge key lets the drag that sizes it join the same step. */
    public String addSprite(int x, int y, int w, int h, String mergeKey) {
        String name = freshName("sprite");
        List<Sprite> sprites = new ArrayList<>(sheet.sprites());
        sprites.add(Sprite.of(name, x, y, Math.max(1, w), Math.max(1, h)));
        edit(sheet.withSprites(sprites), mergeKey);
        selected = name;
        return name;
    }

    /** Replaces the sprite named {@code name} (its name may change; skins follow). */
    public void setSprite(String name, Sprite next, String mergeKey) {
        List<Sprite> sprites = new ArrayList<>();
        for (Sprite s : sheet.sprites()) {
            sprites.add(s.name().equals(name) ? next : s);
        }
        UiSpriteSheet out = sheet.withSprites(sprites);
        if (!name.equals(next.name())) {
            out = out.withSkins(renameInSkins(out.skins(), name, next.name()));
            if (name.equals(selected)) {
                selected = next.name();
            }
        }
        edit(out, mergeKey);
    }

    /**
     * Renames a sprite or skin. Refused (false) when {@code to} is not a valid name or is taken.
     */
    public boolean rename(String from, String to) {
        if (from.equals(to)) {
            return true;
        }
        if (!OmuiFormat.LOCAL_ID.matcher(to).matches() || taken(to)) {
            return false;
        }
        Sprite s = sheet.sprite(from).orElse(null);
        if (s != null) {
            setSprite(from, s.withName(to), null);
            return true;
        }
        Skin k = sheet.skin(from).orElse(null);
        if (k == null) {
            return false;
        }
        setSkin(from, k.withName(to), null);
        if (from.equals(selected)) {
            selected = to;
        }
        return true;
    }

    /** Duplicates the selected sprite or skin with a fresh name. */
    public void duplicate(String name) {
        Sprite s = sheet.sprite(name).orElse(null);
        if (s != null) {
            String copy = freshName(name);
            List<Sprite> sprites = new ArrayList<>(sheet.sprites());
            sprites.add(s.withName(copy));
            edit(sheet.withSprites(sprites), null);
            selected = copy;
            return;
        }
        Skin k = sheet.skin(name).orElse(null);
        if (k != null) {
            String copy = freshName(name);
            List<Skin> skins = new ArrayList<>(sheet.skins());
            skins.add(k.withName(copy));
            edit(sheet.withSkins(skins), null);
            selected = copy;
        }
    }

    /** Removes a sprite (and clears it from skins; a skin left without a normal region is removed) or a skin. */
    public void remove(String name) {
        if (sheet.sprite(name).isPresent()) {
            List<Sprite> sprites = sheet.sprites().stream().filter(s -> !s.name().equals(name)).toList();
            List<Skin> skins = new ArrayList<>();
            for (Skin k : sheet.skins()) {
                if (name.equals(k.normal())) {
                    continue;
                }
                Skin out = k;
                for (String state : Skin.STATES) {
                    if (name.equals(out.region(state))) {
                        out = out.withState(state, null);
                    }
                }
                skins.add(out);
            }
            edit(sheet.withSprites(sprites).withSkins(skins), null);
        } else {
            edit(sheet.withSkins(sheet.skins().stream().filter(k -> !k.name().equals(name)).toList()), null);
        }
        if (name.equals(selected)) {
            selected = null;
        }
    }

    /** Adds a skin whose normal state is {@code normal} and selects it. */
    public String addSkin(String normal) {
        String name = freshName("skin");
        List<Skin> skins = new ArrayList<>(sheet.skins());
        skins.add(Skin.of(name, normal));
        edit(sheet.withSkins(skins), null);
        selected = name;
        return name;
    }

    public void setSkin(String name, Skin next, String mergeKey) {
        List<Skin> skins = new ArrayList<>();
        for (Skin k : sheet.skins()) {
            skins.add(k.name().equals(name) ? next : k);
        }
        edit(sheet.withSkins(skins), mergeKey);
    }

    /** Records the texture size the regions are now authored against (after a resize was reviewed). */
    public void acceptTextureSize(int width, int height) {
        edit(sheet.withTexture(sheet.texture(), width, height), null);
    }

    /** The draft's regions checked against a {@code w x h} texture. */
    public UiSpriteSheets.Check check(int w, int h) {
        return UiSpriteSheets.check(sheet, w, h, sheetId);
    }

    public boolean taken(String name) {
        return sheet.sprite(name).isPresent() || sheet.skin(name).isPresent();
    }

    /** {@code stem}, or {@code stem_2}, {@code stem_3}, ... whichever is free. */
    public String freshName(String stem) {
        String base = stem.replaceAll("_\\d+$", "");
        if (!taken(base)) {
            return base;
        }
        for (int i = 2; ; i++) {
            String n = base + "_" + i;
            if (!taken(n)) {
                return n;
            }
        }
    }

    private static List<Skin> renameInSkins(List<Skin> skins, String from, String to) {
        List<Skin> out = new ArrayList<>();
        for (Skin k : skins) {
            Skin r = k;
            for (String state : Skin.STATES) {
                if (from.equals(r.region(state))) {
                    r = r.withState(state, to);
                }
            }
            out.add(r);
        }
        return out;
    }
}
