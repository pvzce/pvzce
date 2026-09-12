package com.pvzce.server.level;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.LevelRewards;
import com.pvzce.api.content.LevelUnlock;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.SceneElementDef;
import com.pvzce.api.content.ZombieDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.LevelUnlocks;
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
    /**
     * Reports placement tags that no loaded pack declares.
     *
     * <p>Placement is entirely tag-driven, so a missing tag does not throw: every
     * cell simply stops accepting plants and the game looks broken in a way that
     * points nowhere. Naming the missing tags here is what turns "I cannot plant
     * anything" into "your pack dropped {@code #c:plantable}".
     */
    public static List<String> validatePlacementTags() {
        List<String> errors = new ArrayList<>();
        for (com.pvzce.api.tag.TagKey<?> tag : com.pvzce.common.tag.PvzceTags.missingPlacementTags()) {
            errors.add("No pack declares the placement tag " + tag
                    + " - planting will refuse every cell that needs it");
        }
        return errors;
    }

    /**
     * Checks the level's card setup.
     *
     * <p>A level that fills every slot with its own cards gives the player no choice at all.
     * That is legal - it is what a fixed-deck level is - but it is worth saying out loud,
     * because the symptom of doing it by accident is "there is no card selection screen".
     */
    public static List<String> validateSeedSelection(LevelDef def) {
        List<String> errors = new ArrayList<>();
        if (def.slots().isEmpty()) {
            errors.add("This level has no cards, so the player enters with an empty card bar");
            return errors;
        }
        for (Identifier slot : def.slots()) {
            if (slot != null && SlotResolver.resolve(slot).isEmpty()) {
                errors.add("Unknown card '" + slot + "': no slot, plant, tool or resource with that id");
            }
        }
        return errors;
    }

    /**
     * Reports reward entries the simulation cannot carry out.
     *
     * <p>{@code type} is a string discriminator rather than a codec-built sum type,
     * so a typo ({@code "unlcok"}) decodes fine and then pays nothing at all - the
     * one failure mode the DFU codec cannot see. Unlocking a card that does not
     * exist is reported too: it would otherwise only show up as "the level said it
     * gave me something" with nothing in the backpack.
     */
    public static List<String> validateRewards(LevelDef def) {
        List<String> errors = new ArrayList<>();
        LevelRewards rewards = def.rewards();
        if (rewards == null) {
            return errors;
        }
        reportRewards(errors, "first_clear", rewards.firstClear());
        reportRewards(errors, "repeat", rewards.repeat());
        if (rewards.hasCoinDrops() && BuiltInRegistries.RESOURCES.get(rewards.coinDrop()) == null) {
            errors.add("rewards.coin_drop names unknown resource '" + rewards.coinDrop()
                    + "', so a dying zombie would spawn nothing");
        }
        if (rewards.coinDropChance() > 0F && rewards.coinDropAmount() <= 0) {
            errors.add("rewards.coin_drop_chance is " + rewards.coinDropChance()
                    + " but coin_drop_amount is 0, so no zombie will ever drop a coin");
        }
        return errors;
    }

    /**
     * Reports unlock entries that could never be satisfied, and unlock cycles.
     *
     * <p>A malformed requirement is the same trap {@code rewards} has: the type is a
     * string, so a typo decodes and then blocks forever with no explanation. A cycle is
     * worse - two levels each waiting for the other can never be entered again, and
     * nothing in the running game would say why.
     *
     * <p>The cycle check wants every level, so it is a separate entry point that the
     * server calls once per reload rather than per level.
     */
    public static List<String> validateUnlock(LevelDef def) {
        List<String> errors = new ArrayList<>();
        LevelUnlock unlock = def.unlock();
        if (unlock == null) {
            return errors;
        }
        for (LevelUnlock.Requirement requirement : unlock.requires()) {
            String problem = LevelUnlocks.problemWith(requirement);
            if (problem != null) {
                errors.add("unlock.requires: " + problem);
                continue;
            }
            if (requirement.isLevel() && BuiltInRegistries.LEVELS.get(requirement.id().get()) == null) {
                errors.add("unlock.requires references unknown level '" + requirement.id().get()
                        + "', so this level can never be entered");
            }
            if (requirement.isCard() && SlotResolver.resolve(requirement.id().get()).isEmpty()) {
                errors.add("unlock.requires references unknown card '" + requirement.id().get()
                        + "': no slot, plant, tool or resource with that id");
            }
        }
        if (unlock.cost().isPresent() && unlock.cost().get() > 100_000) {
            errors.add("unlock.cost is " + unlock.cost().get() + ", far beyond the wallet cap of "
                    + com.pvzce.common.PvzceConstants.COIN_CAP);
        }
        if (unlock.isOpen() && unlock.hidden()) {
            // Legal but pointless: an open level is always listed, so "hidden" does nothing.
            errors.add("unlock.hidden is set on a level with no requirements, which changes nothing");
        }
        return errors;
    }

    /** Circular unlock chains across every loaded level, as messages. */
    public static List<String> validateUnlockCycles() {
        Map<Identifier, LevelUnlock> unlocks = new java.util.LinkedHashMap<>();
        for (Identifier id : BuiltInRegistries.LEVELS.keySet()) {
            LevelDef def = BuiltInRegistries.LEVELS.get(id);
            if (def != null && !def.unlock().isOpen()) {
                unlocks.put(id, def.unlock());
            }
        }
        return LevelUnlocks.findCycles(unlocks);
    }

    private static void reportRewards(List<String> errors, String block, List<LevelRewards.Reward> rewards) {
        for (LevelRewards.Reward reward : rewards) {
            if (reward == null) {
                continue;
            }
            if (reward.isUnlock()) {
                Identifier card = reward.id().orElse(null);
                if (card == null) {
                    errors.add("rewards." + block + ": an unlock entry needs an \"id\"");
                } else if (SlotResolver.resolve(card).isEmpty()) {
                    errors.add("rewards." + block + ": unknown card '" + card
                            + "': no slot, plant, tool or resource with that id");
                }
            } else if (reward.isCoins()) {
                if (reward.amount() <= 0) {
                    errors.add("rewards." + block + ": a coins entry needs a positive \"amount\"");
                }
            } else {
                errors.add("rewards." + block + ": unknown type '" + reward.type()
                        + "' (expected \"unlock\" or \"coins\")");
            }
        }
    }

    /**
     * An authoring note rather than a problem: this level fixes its whole deck.
     *
     * <p>That is a legitimate design - it is exactly what the first level is - so it
     * must not be reported as a defect. It is still worth saying out loud, because
     * the same shape also happens by accident when an author lists their whole pool
     * and leaves {@code max_seed_slots} alone, and then wonders why the player never
     * gets to choose anything.
     *
     * @return the note, or {@code null} when the level leaves the player a choice
     */
    public static String describeFixedDeck(LevelDef def) {
        if (def.slots().isEmpty() || def.slots().size() < def.maxSeedSlots()) {
            return null;
        }
        return "fixed deck: the level's " + def.slots().size() + " cards fill all "
                + def.maxSeedSlots() + " slots, so the player only picks when they unlock more"
                + " (raise max_seed_slots above " + def.slots().size() + " to leave room)";
    }

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
