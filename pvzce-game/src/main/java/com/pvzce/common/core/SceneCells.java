package com.pvzce.common.core;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.level.SceneGrid;
import com.pvzce.common.network.packet.SceneSyncS2C;

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
                    System.err.println("[PVZCE] Ignoring malformed scene position '" + pos
                            + "' for " + entry.getKey());
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

    /** Packet form of a level's authored scene map. */
    public static List<SceneSyncS2C.Cell> forLevel(LevelDef def) {
        List<SceneSyncS2C.Cell> cells = new ArrayList<>();
        for (SceneGrid.Cell<Identifier> cell : parse(def.scene(), def.width(), def.height())) {
            cells.add(new SceneSyncS2C.Cell(cell.x(), cell.y(), cell.value().toString()));
        }
        return List.copyOf(cells);
    }
}
