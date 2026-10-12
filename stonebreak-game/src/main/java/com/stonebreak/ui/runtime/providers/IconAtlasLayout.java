package com.stonebreak.ui.runtime.providers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where each cached icon lives: square cells of one pixel size packed row-major into fixed-size
 * pages, one page list per icon size (icons render at their exact device-pixel size, as the
 * legacy per-slot viewport did, so a UI-scale change adds a size rather than resampling). GL-free
 * so the bookkeeping is testable; {@link ItemIconAtlas} owns the textures.
 *
 * @param <K> what a cell shows (block type)
 */
final class IconAtlasLayout<K> {

    /** A cell: page index within its size, and its rect in that page (GL origin bottom-left). */
    record Cell(int size, int page, int x, int y) {
    }

    private record SizedKey<K>(K key, int size) {
    }

    private final int pageSize;
    private final Map<SizedKey<K>, Cell> cells = new HashMap<>();
    private final Map<Integer, List<Integer>> usedPerPage = new HashMap<>();

    IconAtlasLayout(int pageSize) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("page size " + pageSize);
        }
        this.pageSize = pageSize;
    }

    int pageSize() {
        return pageSize;
    }

    /** Cells one page of {@code size} holds. */
    int cellsPerPage(int size) {
        int perRow = pageSize / size;
        return perRow * perRow;
    }

    /** The cell already holding {@code key} at {@code size}, or null. */
    Cell find(K key, int size) {
        return cells.get(new SizedKey<>(key, size));
    }

    /**
     * Allocates the next free cell for {@code key} at {@code size} (the caller renders into it).
     *
     * @throws IllegalArgumentException when one icon cannot fit a page
     */
    Cell allocate(K key, int size) {
        if (size <= 0 || size > pageSize) {
            throw new IllegalArgumentException("icon size " + size + " outside 1.." + pageSize);
        }
        SizedKey<K> k = new SizedKey<>(key, size);
        Cell existing = cells.get(k);
        if (existing != null) {
            return existing;
        }
        List<Integer> used = usedPerPage.computeIfAbsent(size, s -> new ArrayList<>());
        int perPage = cellsPerPage(size);
        int page = used.isEmpty() || used.getLast() >= perPage ? used.size() : used.size() - 1;
        if (page == used.size()) {
            used.add(0);
        }
        int index = used.get(page);
        used.set(page, index + 1);
        int perRow = pageSize / size;
        Cell cell = new Cell(size, page, (index % perRow) * size, (index / perRow) * size);
        cells.put(k, cell);
        return cell;
    }

    /** Pages allocated for {@code size}. */
    int pages(int size) {
        List<Integer> used = usedPerPage.get(size);
        return used == null ? 0 : used.size();
    }

    /** Drops every cell (textures are rebuilt from scratch, e.g. after a texture-pack reload). */
    void clear() {
        cells.clear();
        usedPerPage.clear();
    }

    int cellCount() {
        return cells.size();
    }
}
