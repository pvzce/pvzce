package com.pvzce.server.level;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.SceneElementDef;
import com.pvzce.api.content.ZombieDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SceneCells;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.level.SceneGrid;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Collects every problem in a level definition into one list.
 *
 * <p>Level data used to be checked in four places with four different outcomes: a
 * bad rule value threw out of the {@code LevelServer} constructor (so the level
 * simply failed to load), an unknown rule was dropped silently, an unknown slot id
 * produced a card on the client that the server then refused, and a malformed scene
 * position crashed level construction with an
 * {@code ArrayIndexOutOfBoundsException}. Validation is now one pass with one
 * reporting path, and it never aborts construction - a level with a typo still
 * loads, minus the broken part, with the reason on the log.
 */
public final class LevelValidator {
    public static List<String> validateSlots(List<Identifier> slots) {
        List<String> errors = new ArrayList<>();
        if (slots == null) {
            return errors;
        }
        for (Identifier slotId : slots) {
            if (slotId == null) {
                continue;
            }
            if (SlotResolver.resolve(slotId).isEmpty()) {
                errors.add("Unknown card '" + slotId + "': no slot, plant, tool or resource with that id");
            }
        }
        return errors;
    }

    public static List<String> validateScene(LevelDef def) {
        List<String> errors = new ArrayList<>();
        if (def.scene() == null) {
            return errors;
        }
        for (Map.Entry<Identifier, List<String>> entry : def.scene().entrySet()) {
            SceneElementDef element = BuiltInRegistries.SCENE_ELEMENTS.get(entry.getKey());
            if (element == null) {
                errors.add("Unknown scene element '" + entry.getKey() + "'");
                continue;
            }
            if (entry.getValue() == null) {
                continue;
            }
            for (String position : entry.getValue()) {
                int[] parsed = SceneCells.parsePosition(position);
                if (parsed == null) {
                    errors.add("Malformed scene position '" + position + "' for " + entry.getKey()
                            + " (expected \"x,y\")");
                } else if (parsed[0] < 0 || parsed[0] >= def.width()
                        || parsed[1] < 0 || parsed[1] >= def.height()) {
                    errors.add("Scene position '" + position + "' for " + entry.getKey()
                            + " is outside the " + def.width() + "x" + def.height() + " board");
                }
            }
        }
        errors.addAll(unpaintedCells(def));
        return errors;
    }

    /**
     * Reports cells of the board that no scene element covers.
     *
     * <p>An unpainted cell is not grass: the renderer draws the level background
     * through it (a visible hole in the lawn) and the server refuses every plant
     * because {@code sceneAt} returns null. The level editor used to make this
     * invisible - its own {@code sceneAt} falls back to grass, so a half-painted
     * board looked complete on the canvas and had holes in game. Naming the cells
     * here is what turns that silent difference into a message the author can act on.
     */
    private static List<String> unpaintedCells(LevelDef def) {
        List<String> errors = new ArrayList<>();
        int width = def.width();
        int height = def.height();
        if (width <= 0 || height <= 0) {
            return errors;
        }
        boolean[][] painted = new boolean[height][width];
        for (Map.Entry<Identifier, List<String>> entry : def.scene().entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            for (String position : entry.getValue()) {
                int[] parsed = SceneCells.parsePosition(position);
                if (parsed != null && parsed[0] >= 0 && parsed[0] < width
                        && parsed[1] >= 0 && parsed[1] < height) {
                    painted[parsed[1]][parsed[0]] = true;
                }
            }
        }
        List<String> holes = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!painted[y][x]) {
                    holes.add(x + "," + y);
                }
            }
        }
        if (!holes.isEmpty()) {
            errors.add("Scene leaves " + holes.size() + " of " + (width * height)
                    + " cells unpainted (they render as bare background and accept no plants): "
                    + (holes.size() > 12 ? holes.subList(0, 12) + "..." : holes));
        }
        return errors;
    }

    public static List<String> validateInitialEntities(LevelDef def) {
        List<String> errors = new ArrayList<>();
        for (var init : def.initialEntities()) {
            String kind = init.kind() == null ? "" : init.kind();
            Identifier id = init.id();
            if (kind.startsWith("p")) {
                PlantDef plant = id == null ? null : BuiltInRegistries.PLANTS.get(id);
                if (plant == null) {
                    errors.add("Unknown initial plant '" + id + "'");
                }
            } else if (kind.startsWith("z")) {
                ZombieDef zombie = id == null ? null : BuiltInRegistries.ZOMBIES.get(id);
                if (zombie == null) {
                    errors.add("Unknown initial zombie '" + id + "'");
                }
            } else {
                errors.add("Unknown initial entity kind '" + kind + "' (expected plant/p or zombie/z)");
            }
            if (init.x() < 0 || init.x() >= def.width() || init.y() < 0 || init.y() >= def.height()) {
                errors.add("Initial entity '" + id + "' at (" + init.x() + "," + init.y()
                        + ") is outside the " + def.width() + "x" + def.height() + " board");
            }
        }
        return errors;
    }

    public static List<String> validateEnvVars(Map<Identifier, com.pvzce.api.content.EnvValue> envVars) {
        List<String> errors = new ArrayList<>();
        if (envVars == null) {
            return errors;
        }
        for (Map.Entry<Identifier, com.pvzce.api.content.EnvValue> entry : envVars.entrySet()) {
            com.pvzce.api.content.EnvValue value = entry.getValue();
            if (value == null) {
                errors.add("Environment variable '" + entry.getKey() + "' has no value");
                continue;
            }
            var type = BuiltInRegistries.ENV_VAR_TYPES.get(value.type());
            if (type == null) {
                errors.add("Environment variable '" + entry.getKey() + "' uses unknown type " + value.type());
                continue;
            }
            var result = type.codec().parse(com.mojang.serialization.JsonOps.INSTANCE, value.value());
            if (result.error().isPresent()) {
                errors.add("Invalid value for environment variable '" + entry.getKey() + "': "
                        + result.error().get().message());
            }
        }
        return errors;
    }

    /** True when the element in a cell is within the board (helper for callers). */
    public static boolean inBounds(SceneGrid<?> grid, int x, int y) {
        return grid != null && grid.inBounds(x, y);
    }

    private LevelValidator() {
    }
}
