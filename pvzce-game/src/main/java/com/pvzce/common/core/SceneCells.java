package com.pvzce.common.core;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.level.SceneGrid;
import com.pvzce.common.network.packet.SceneSyncS2C;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses a level's authored scene map ({@code {"pvzce:water": ["2,0", "2,1"]}}) and
 * serializes it for packets.
 *
 * <p>This is the single parser for that format. It used to exist twice: a
 * defensive copy here and a bare {@code split(",")/parseInt} inside the
 * {@code LevelServer} constructor that threw {@code ArrayIndexOutOfBoundsException}
 * on a malformed entry and aborted level creation.
 */
public final class SceneCells {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("PVZCE/Scene");
    private SceneCells() {
    }

    /** Validated cells; malformed entries and out-of-bounds cells are dropped. */
    public static List<SceneGrid.Cell<Identifier>> parse(Map<Identifier, List<String>> scene, int width, int height) {
        List<SceneGrid.Cell<Identifier>> cells = new ArrayList<>();
        if (scene == null) {
            return List.of();
        }
        for (Map.Entry<Identifier, List<String>> entry : scene.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            for (String pos : entry.getValue()) {
                int[] parsed = parsePosition(pos);
                if (parsed == null) {
                    LOGGER.warn("Ignoring malformed scene position '{}' for {}", pos, entry.getKey());
                    continue;
                }
                if (width > 0 && height > 0
                        && (parsed[0] < 0 || parsed[0] >= width || parsed[1] < 0 || parsed[1] >= height)) {
                    continue;
                }
                cells.add(new SceneGrid.Cell<>(parsed[0], parsed[1], entry.getKey()));
            }
        }
        return List.copyOf(cells);
    }

    /** {@code "x,y"} with optional whitespace, or {@code null} when malformed. */
    public static int[] parsePosition(String pos) {
        if (pos == null) {
            return null;
        }
        String[] parts = pos.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Which element a cell ends up with, or {@code null} when nothing declares it.
     *
     * <p><strong>The last declaration wins</strong>, which is what playing the level does: the
     * server walks {@link #parse} in the map's own order and calls {@code scene.set} for every
     * cell, so a file that lists every cell as grass and then names four of them water plays as
     * water. The level editor's canvas used to answer the opposite way - first match wins - so the
     * same file showed grass where the game showed water, roof and graves, and an author had no way
     * to see the difference. Both sides read the rule from here now.
     *
     * <p>Keys are generic because the editor holds the file's own spelling (it writes the JSON back
     * untouched) while the server holds parsed {@link Identifier}s.
     */
    public static <K> K lookup(Map<K, List<String>> scene, int x, int y) {
        if (scene == null) {
            return null;
        }
        String pos = x + "," + y;
        K found = null;
        for (Map.Entry<K, List<String>> entry : scene.entrySet()) {
            if (entry.getValue() != null && entry.getValue().contains(pos)) {
                found = entry.getKey();
            }
        }
        return found;
    }

    /** Packet form of a level's authored scene map. */
    public static List<SceneSyncS2C.Cell> forLevel(LevelDef def) {
        List<SceneSyncS2C.Cell> cells = new ArrayList<>();
        for (SceneGrid.Cell<Identifier> cell : parse(def.scene(), def.width(), def.height())) {
            cells.add(new SceneSyncS2C.Cell(cell.x(), cell.y(), cell.value().toString()));
        }
        return List.copyOf(cells);
    }

    /**
     * Packet form of a scene grid that has already been built, every cell included.
     *
     * <p>{@link #forLevel} answers with the <em>authored</em> cells, which is what a level file
     * can say; a grid that a level has finished building also holds what its mechanics put
     * there - the tombstones {@code grave_field} scattered, the ones {@code grave_spawner}
     * raised for the opening board. Those cells exist only in the grid, so a caller that wants
     * to show the board a run will actually open on has to read it here rather than re-deriving
     * it from the file.
     */
    public static List<SceneSyncS2C.Cell> forGrid(SceneGrid<Identifier> grid) {
        List<SceneSyncS2C.Cell> cells = new ArrayList<>();
        for (int x = 0; x < grid.width(); x++) {
            for (int y = 0; y < grid.height(); y++) {
                Identifier element = grid.get(x, y);
                if (element != null) {
                    cells.add(new SceneSyncS2C.Cell(x, y, element.toString()));
                }
            }
        }
        return List.copyOf(cells);
    }
}
