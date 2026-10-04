package com.pvzce.server.level;

import com.pvzce.common.level.SceneBoard;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.LevelRewards;
import com.pvzce.api.content.LevelUnlock;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.SceneElementDef;
import com.pvzce.api.content.WaveDef;
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
     * Reports an opening dialogue the game could not present as written.
     *
     * <p>The failures that read as "nothing happened" in game and point nowhere: an unknown
     * character id (no portrait, no name), a misspelt {@code side} or {@code enter}/{@code exit}
     * (they decode into {@code UNKNOWN} rather than failing the level, exactly like a misspelt
     * reward {@code type}), a line whose {@code animation.type} this version does not know, and
     * a portrait name that is not a valid identifier path. A line with no text at all is a
     * portrait-only beat and is deliberately not one of these.
     *
     * <p>Three more come with the stage and the answers: a line with neither a character nor a
     * {@code speaker_name} (nobody to name over the bubble), a {@code slots} entry that names an
     * unknown character or half the window twice (the second one is dropped, so the picture is not
     * what the file says), and a question on the last line (nothing is written under it, so the
     * conversation simply ends when it is answered). An empty answer is not one of these: the same
     * beat a silent line is.
     */
    public static List<String> validateDialogue(LevelDef def) {
        List<String> errors = new ArrayList<>();
        if (def.dialogue() == null) {
            return errors;
        }
        for (String side : List.of("enter", "exit")) {
            com.pvzce.api.content.DialogueEffect effect = "enter".equals(side)
                    ? def.dialogue().enter() : def.dialogue().exit();
            if (effect == com.pvzce.api.content.DialogueEffect.UNKNOWN) {
                errors.add("dialogue." + side + " is not 'slide' or 'none', so the dialogue slides");
            }
        }
        List<com.pvzce.api.content.DialogueLine> lines = def.dialogue().lines();
        for (int i = 0; i < lines.size(); i++) {
            com.pvzce.api.content.DialogueLine line = lines.get(i);
            String where = "dialogue.lines[" + i + "]";
            com.pvzce.api.content.DialogueCharacterDef character = line.character() == null
                    ? null : BuiltInRegistries.DIALOGUE_CHARACTERS.get(line.character());
            if (character == null && line.character() != null) {
                errors.add(where + " names unknown character '" + line.character()
                        + "': no portrait or name will be shown");
            } else if (character != null && character.portraitTexture(line.portrait()) == null) {
                if (!line.portrait().isBlank()) {
                    errors.add(where + " names portrait '" + line.portrait()
                            + "', which is not a valid file name");
                }
            }
            if (character == null && line.character() == null && line.speakerName().isBlank()) {
                errors.add(where + " names no character and no speaker_name,"
                        + " so the bubble is drawn without a name");
            }
            if (line.side() == com.pvzce.api.content.DialogueLine.Side.UNKNOWN) {
                errors.add(where + ".side is not 'left', 'center' or 'right',"
                        + " so the speaker is drawn on the left");
            }
            if (line.animation() != null && !line.animation().isKnown()) {
                errors.add(where + ".animation.type is '" + line.animation().type()
                        + "', which this version does not know (expected 'shake' or 'scale'),"
                        + " so the line is played with no animation");
            }
            errors.addAll(validateStage(where, line));
            if (line.hasChoices()) {
                // What the answer reveals is the line under the gate - the player's own words, which
                // the conversation never draws - and the character's reply is the line after it. A
                // question whose answer has nowhere to point is a conversation that stops there.
                if (i + 1 >= lines.size() || !isPlayerLine(lines.get(i + 1))) {
                    errors.add(where + " offers choices but the line under it is not the player's own"
                            + " answer (no character, with a speaker_name), so pressing a button has"
                            + " nothing to reveal");
                } else if (i + 2 >= lines.size()) {
                    errors.add(where + " offers choices but nothing follows the player's answer,"
                            + " so the conversation ends the moment one is pressed");
                }
                for (int c = 0; c < line.choices().size(); c++) {
                    if (line.choices().get(c).text() == null || line.choices().get(c).text().isBlank()) {
                        errors.add(where + ".choices[" + c + "] is empty, so the button carries no text");
                    }
                }
            }
            // An empty text is a portrait-only beat, not a mistake: the character is on
            // screen and nobody is talking, and the overlay draws no bubble for it. It used
            // to be reported as "the player clicks through an empty bubble", which was true
            // of the overlay that drew one.
        }
        return errors;
    }

    /**
     * True for the player's own line: no character, and a name over the bubble.
     *
     * <p>The same rule the overlay hides such a line by - it is what the buttons say, not something
     * the conversation draws - and the only shape a question's answer may have.
     */
    private static boolean isPlayerLine(com.pvzce.api.content.DialogueLine line) {
        return line.character() == null && !line.speakerName().isBlank();
    }

    /**
     * Reports a {@code slots} list the stage cannot build as written.
     *
     * <p>Three slots are the whole window, so a fourth entry has nowhere to stand and a repeated one
     * is a character who cannot be in two places at once; a misspelt slot is dropped by the decoder
     * (it is {@code UNKNOWN}, not a failed level) and a misspelt character id is the same "no
     * portrait, no name" failure an unknown speaker is.
     */
    private static List<String> validateStage(String where, com.pvzce.api.content.DialogueLine line) {
        List<String> errors = new ArrayList<>();
        java.util.Set<com.pvzce.api.content.DialogueSlot> used =
                java.util.EnumSet.noneOf(com.pvzce.api.content.DialogueSlot.class);
        java.util.Set<com.pvzce.api.util.Identifier> named = new java.util.HashSet<>();
        for (int s = 0; s < line.slots().size(); s++) {
            com.pvzce.api.content.DialogueLine.DialogueSlotEntry entry = line.slots().get(s);
            String at = where + ".slots[" + s + "]";
            if (entry.slot() == com.pvzce.api.content.DialogueSlot.UNKNOWN) {
                errors.add(at + ".slot is not 'left', 'center' or 'right', so nobody stands there");
                continue;
            }
            if (!used.add(entry.slot())) {
                errors.add(at + " puts a second character in the " + entry.slot().id()
                        + " slot, which already has one");
            }
            if (!named.add(entry.character())) {
                errors.add(at + " names '" + entry.character() + "' twice: one character, one slot");
            }
            if (BuiltInRegistries.DIALOGUE_CHARACTERS.get(entry.character()) == null) {
                errors.add(at + " names unknown character '" + entry.character()
                        + "', so nobody stands there");
            }
        }
        return errors;
    }

    /**
     * Reports hints that can never do what they say.
     *
     * <p>The hint system is deliberately forgiving at runtime - an unknown trigger falls
     * back to {@code on_start} and a misspelt resource simply never matches - so a broken
     * hint shows up as "nothing happened", which points at nothing. These are the three
     * ways that happens, and all three are properties of the file:
     *
     * <ul>
     *   <li>{@code on_resource} with no {@code resource}: there is nothing to wait for;</li>
     *   <li>a {@code resource} that is not in the registry: the drop it names never
     *       appears, so the line never fires;</li>
     *   <li>no {@code text} on a tutorial trigger: the box would come up empty.</li>
     * </ul>
     *
     * <p>{@code on_card_refused} is exempt from the text check: its sentences are built in.
     */
    public static List<String> validateHints(LevelDef def) {
        List<String> errors = new ArrayList<>();
        if (def.hints() == null) {
            return errors;
        }
        List<com.pvzce.api.content.LevelHint> hints = def.hints();
        for (int i = 0; i < hints.size(); i++) {
            com.pvzce.api.content.LevelHint hint = hints.get(i);
            String where = "hints[" + i + "]";
            if (hint.trigger() == com.pvzce.api.content.LevelHint.Trigger.ON_CARD_REFUSED) {
                continue;
            }
            if (hint.text().isBlank()) {
                errors.add(where + " has no 'text', so the box would come up empty");
            }
            if (hint.needsResource()) {
                if (hint.resource().isEmpty()) {
                    errors.add(where + " is 'on_resource' but names no 'resource',"
                            + " so nothing can ever trigger it");
                } else if (BuiltInRegistries.RESOURCES.get(hint.resource().get()) == null) {
                    errors.add(where + " waits for unknown resource '" + hint.resource().get()
                            + "', so it will never fire");
                }
            }
        }
        return errors;
    }

    /**
     * Reports a buff block that names something nobody can act on.
     *
     * <p>Two ways to get this wrong, and both are silent at runtime: a buff id that is not
     * registered (a typo, or a mod that is not loaded) would sit in {@code def.buffs()} doing
     * nothing, and a {@code max_buff_slots} of zero would leave a level that offers
     * {@code pvzce:player_choice} with a page the player can never put anything on.
     *
     * <p>{@code pvzce:player_choice} is exempt from the id check, because it is the marker that
     * <em>means</em> "offer the player a choice" and is deliberately not a registered buff.
     */
    public static List<String> validateBuffs(LevelDef def) {
        List<String> errors = new ArrayList<>();
        if (def.buffPlan() == null) {
            return errors;
        }
        for (Identifier buff : def.buffPlan().fixedBuffs()) {
            if (com.pvzce.common.buff.LevelBuffs.get(buff) == null) {
                errors.add("buffs names unknown level buff '" + buff
                        + "', so it is never switched on");
            }
        }
        if (def.buffPlan().declaresMaxBuffSlots() && def.buffPlan().maxBuffSlots() <= 0) {
            errors.add("max_buff_slots is " + def.buffPlan().maxBuffSlots()
                    + ", so no buff can ever be switched on here");
        }
        return errors;
    }

    /**
     * Reports dialogue art that no loaded pack provides.
     *
     * <p>Separate from {@link #validateDialogue} because it needs the resource manager, and
     * the level's own constructor has no business reading packs. Called once per reload by
     * the server, which is where an author looks for "why is my character invisible".
     */
    public static List<String> validateDialogueAssets(LevelDef def,                                                      com.pvzce.common.resource.PvzceResourceManager resources) {
        List<String> errors = new ArrayList<>();
        if (def.dialogue() == null || resources == null) {
            return errors;
        }
        List<com.pvzce.api.content.DialogueLine> lines = def.dialogue().lines();
        for (int i = 0; i < lines.size(); i++) {
            com.pvzce.api.content.DialogueLine line = lines.get(i);
            com.pvzce.api.content.DialogueCharacterDef character = line.character() == null
                    ? null : BuiltInRegistries.DIALOGUE_CHARACTERS.get(line.character());
            if (character == null) {
                continue;
            }
            String where = "dialogue.lines[" + i + "]";
            Identifier portrait = character.portraitTexture(line.portrait());
            if (portrait != null && !resources.hasTexture(portrait)) {
                errors.add(where + " portrait '" + portrait + "' is not in any loaded resource pack");
            }
            Identifier box = character.box(!line.side().isRight());
            if (!resources.hasTexture(box)) {
                errors.add("Character '" + character.id() + "' speech bubble '" + box
                        + "' is not in any loaded resource pack");
            }
        }
        return errors;
    }

    /** Every loaded level's dialogue problems, for the reload report. */
    public static List<String> validateAllDialogues(
            com.pvzce.common.resource.PvzceResourceManager resources) {
        List<String> errors = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.LEVELS.keySet()) {
            LevelDef def = BuiltInRegistries.LEVELS.get(id);
            if (def == null) {
                continue;
            }
            for (String problem : validateDialogue(def)) {
                errors.add(id + ": " + problem);
            }
            for (String problem : validateDialogueAssets(def, resources)) {
                errors.add(id + ": " + problem);
            }
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
            // Not a cap - the wallet has none - just a price no level should ever ask for,
            // and one that is almost always a stray digit.
            errors.add("unlock.cost is " + unlock.cost().get() + ", which no player can earn; "
                    + "check for a stray digit");
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
            } else if (reward.isBuff()) {
                Identifier buff = reward.id().orElse(null);
                if (buff == null) {
                    errors.add("rewards." + block + ": a buff entry needs an \"id\"");
                } else if (!com.pvzce.common.buff.LevelBuffs.isRegistered(buff)) {
                    // The entry would decode and be skipped at payout time, so the symptom is
                    // "the first clear handed over nothing", which points at nothing.
                    errors.add("rewards." + block + ": unknown level buff '" + buff
                            + "', so nothing would be unlocked");
                }
            } else if (reward.isCoins()) {
                if (reward.amount() <= 0) {
                    errors.add("rewards." + block + ": a coins entry needs a positive \"amount\"");
                }
            } else if (reward.isResource()) {
                Identifier resource = reward.id().orElse(null);
                if (resource == null) {
                    errors.add("rewards." + block + ": a resource entry needs an \"id\"");
                } else if (BuiltInRegistries.RESOURCES.get(resource) == null) {
                    // The entry would still credit its worth - a resource nobody defined is
                    // worth nothing - so the symptom is "the first clear paid a diamond and
                    // the wallet did not move", which points at nothing.
                    errors.add("rewards." + block + ": unknown resource '" + resource
                            + "', which is worth nothing to the wallet");
                } else if (reward.amount() <= 0) {
                    errors.add("rewards." + block + ": a resource entry needs a positive \"amount\"");
                }
            } else {
                errors.add("rewards." + block + ": unknown type '" + reward.type()
                        + "' (expected \"unlock\", \"coins\" or \"resource\")");
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
        // The effective count, not the raw one: a level that declares nothing follows the
        // backpack, and with the default backpack a 13-card level is just as fixed as one
        // that wrote "13".
        int slots = def.effectiveMaxSeedSlots(com.pvzce.common.PvzceConstants.DEFAULT_SEED_SLOTS);
        if (def.slots().isEmpty() || def.slots().size() < slots) {
            return null;
        }
        return "fixed deck: the level's " + def.slots().size() + " cards fill all "
                + slots + " slots, so the player only picks when they unlock more"
                + " (raise max_seed_slots above " + def.slots().size() + " to leave room)";
    }

    /**
     * Checks the parts of a wave table a typo can break silently.
     *
     * <p>The wave tables themselves are balance data and are deliberately not judged here (see
     * {@code WavePacingMetricsTest} for what the pacing is measured against). What is checked is
     * the two things that are facts rather than tuning: a lane that this board does not have, so
     * the zombie would arrive nowhere, and an entry that sends nothing at all.
     */
    public static List<String> validateWaves(LevelDef def) {
        List<String> errors = new ArrayList<>();
        com.pvzce.common.level.SceneBoard board = SceneBoard.forLevel(def);
        for (int index = 0; index < def.waves().size(); index++) {
            WaveDef wave = def.waves().get(index);
            int number = index + 1;
            for (WaveDef.Entry entry : wave.entries()) {
                if (entry.count() <= 0) {
                    errors.add("waves." + number + ": entry '" + entry.id()
                            + "' asks for " + entry.count() + " zombies, so it sends none");
                }
                List<Integer> arrivalRows = entry.rows().isEmpty()
                        ? java.util.stream.IntStream.range(0, def.height()).boxed().toList() : entry.rows();
                if (arrivalRows.stream().allMatch(row -> row >= 0 && row < def.height())
                        && arrivalRows.stream().noneMatch(row -> board.exists(entry.surface(), def.width() - 1, row))) {
                    errors.add("waves." + number + ": no supported arrival on surface " + entry.surface());
                }
                for (int row : entry.rows()) {
                    if (row < 0 || row >= def.height()) {
                        errors.add("waves." + number + ": entry '" + entry.id() + "' names row "
                                + row + ", which this " + def.height()
                                + "-row board does not have");
                    }
                }
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
        java.util.Set<Identifier> surfaces = new java.util.HashSet<>();
        surfaces.add(Identifier.parse(SceneBoard.DEFAULT_SURFACE));
        for (var surface : def.surfaces()) {
            if (!surfaces.add(surface.id())) errors.add("Duplicate surface '" + surface.id() + "'");
            for (var entry : surface.scene().entrySet()) {
                if (!BuiltInRegistries.SCENE_ELEMENTS.containsKey(entry.getKey()))
                    errors.add("Unknown scene element '" + entry.getKey() + "' on " + surface.id());
                for (String position : entry.getValue()) {
                    int[] cell = SceneCells.parsePosition(position);
                    if (cell == null || cell[0] < 0 || cell[0] >= def.width() || cell[1] < 0 || cell[1] >= def.height())
                        errors.add("Invalid scene position '" + position + "' on " + surface.id());
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
            if (init.surface().isPresent() && !init.surface().get().toString().equals(SceneBoard.DEFAULT_SURFACE)) {
                var surface = def.surfaces().stream().filter(v -> v.id().equals(init.surface().get())).findFirst().orElse(null);
                if (surface == null || SceneCells.parse(surface.scene(), def.width(), def.height()).stream()
                        .noneMatch(cell -> cell.x() == init.x() && cell.y() == init.y()))
                    errors.add("Initial entity '" + init.id() + "' has no supporting surface at " + init.x() + "," + init.y());
            }
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

    /**
     * Reports damage types named by content that no loaded pack declares.
     *
     * <p>Checked across the whole content set rather than per level, so it runs on
     * reload next to the other content passes. A typo is not fatal -
     * {@code ZombieEntity.damageType} reads an unknown id as {@code pvzce:projectile},
     * which is "armour applies" - but it is silent: an author who wrote
     * {@code "pvzce:ashh"} would get a cherry bomb whose blast stops at a cone, and
     * nothing in the game would say why.
     *
     * <p>Both the plant and projectile registries are walked because both declare one
     * ({@code explosive}, {@code freeze_all} and {@code cone} on plants, {@code splash} on
     * projectiles). Each reports the fallback its own capability would actually take, rather
     * than one shared sentence: "so it falls back to pvzce:projectile" was a lie for the ones
     * whose fallback is the ash or the spray line.
     */
    public static List<String> validateDamageTypes() {
        List<String> errors = new ArrayList<>();
        for (PlantDef plant : BuiltInRegistries.PLANTS) {
            for (com.pvzce.api.content.capability.TypedCapability<
                    com.pvzce.api.content.capability.PlantCapability> entry : plant.resolvedCapabilities()) {
                if (entry.value() instanceof com.pvzce.common.capability.plant.ExplosiveCapability explosive
                        && BuiltInRegistries.DAMAGE_TYPES.get(explosive.damageType()) == null) {
                    errors.add("Plant '" + plant.id() + "' declares unknown damage type '"
                            + explosive.damageType() + "', so its blast falls back to "
                            + com.pvzce.common.capability.plant.ExplosiveCapability.DEFAULT_DAMAGE_TYPE);
                }
                if (entry.value() instanceof com.pvzce.common.capability.plant.FreezeAllCapability freeze
                        && BuiltInRegistries.DAMAGE_TYPES.get(freeze.damageType()) == null) {
                    errors.add("Plant '" + plant.id() + "' declares unknown damage type '"
                            + freeze.damageType() + "', so its freeze falls back to "
                            + com.pvzce.common.capability.plant.FreezeAllCapability.DEFAULT_DAMAGE_TYPE);
                }
                if (entry.value() instanceof com.pvzce.common.capability.plant.ConeAttackCapability cone
                        && BuiltInRegistries.DAMAGE_TYPES.get(cone.damageType()) == null) {
                    errors.add("Plant '" + plant.id() + "' declares unknown damage type '"
                            + cone.damageType() + "', so its cone falls back to "
                            + com.pvzce.common.capability.plant.ConeAttackCapability.DEFAULT_DAMAGE_TYPE);
                }
            }
        }
        for (com.pvzce.api.content.ProjectileDef projectile : BuiltInRegistries.PROJECTILES) {
            for (com.pvzce.api.content.capability.TypedCapability<
                    com.pvzce.api.content.capability.ProjectileCapability> entry
                    : projectile.resolvedCapabilities()) {
                if (entry.value() instanceof com.pvzce.common.capability.projectile.SplashImpactCapability splash
                        && BuiltInRegistries.DAMAGE_TYPES.get(splash.damageType()) == null) {
                    errors.add("Projectile '" + projectile.id() + "' declares unknown damage type '"
                            + splash.damageType() + "', so its blast falls back to "
                            + com.pvzce.common.capability.projectile.SplashImpactCapability.DEFAULT_DAMAGE_TYPE);
                }
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
