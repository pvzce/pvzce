package com.pvzce.common.level;

import java.util.ArrayList;
import java.util.List;

/**
 * A bounds-checked rectangular grid of cells, shared by the server level and the
 * client mirror.
 *
 * <p>It exists so the "which cell is this, and is it inside the board" question
 * has one answer. Previously the server kept a raw {@code [width][height]} array
 * while the client kept a {@code String[][]}, each with its own bounds checks, and
 * entity grid derivation clamped to the <em>default</em> 9x5 board rather than the
 * level's real size.
 */
public final class SceneGrid<T> {
    private final int width;
    private final int height;
    private final Object[][] cells;

    private SceneGrid(int width, int height) {
        this.width = Math.max(0, width);
        this.height = Math.max(0, height);
        this.cells = new Object[this.width][this.height];
    }

    public static <T> SceneGrid<T> create(int width, int height, T fill) {
        SceneGrid<T> grid = new SceneGrid<>(width, height);
        for (int x = 0; x < grid.width; x++) {
            for (int y = 0; y < grid.height; y++) {
                grid.cells[x][y] = fill;
            }
        }
        return grid;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public boolean inBounds(int x, int y) {
        return x >= 0 && x < width && y >= 0 && y < height;
    }

    /** The cell value, or {@code null} when out of bounds / never filled. */
    @SuppressWarnings("unchecked")
    public T get(int x, int y) {
        return inBounds(x, y) ? (T) cells[x][y] : null;
    }

    /** Writes a cell; out-of-bounds writes are ignored rather than corrupting a neighbour. */
    public void set(int x, int y, T value) {
        if (inBounds(x, y)) {
            cells[x][y] = value;
        }
    }

    /** Fills every cell with the same value. */
    public void fill(T value) {
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                cells[x][y] = value;
            }
        }
    }

    /** Every cell, row-major (x outer, y inner) - the order packets expect. */
    public List<Cell<T>> cells() {
        List<Cell<T>> result = new ArrayList<>(width * height);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                result.add(new Cell<>(x, y, get(x, y)));
            }
        }
        return List.copyOf(result);
    }

    public record Cell<T>(int x, int y, T value) {
    }
}
