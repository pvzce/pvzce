package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.ScaryPotterData;
import com.pvzce.api.content.mechanic.FieldSpec;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@code pvzce:scary_potter} mechanic: rounds of vases with something inside each.
 *
 * <p>This is 4-5, the original's Scary Potter level, and it is the one adventure level whose
 * zombies do not walk in at all: the level has no waves, its lawn starts full of pots, and every
 * zombie in it comes out of one. The round is over when every pot is broken and the zombies that
 * came out are dead; then the next round is laid out, one column further towards the house.
 *
 * <p>Three things about it are worth stating, because each is a decision rather than a detail:
 *
 * <ul>
 *   <li><b>The pots are scattered, not written down.</b> Which cell each pot stands in is drawn
 *       from the level's own random source when the round is laid out - the original does the
 *       same, and a fixed layout would be a puzzle to memorise rather than one to read. What the
 *       level <em>does</em> write down is how many pots hold what, and how far in they may
 *       stand.</li>
 *   <li><b>The level ends on the last pot, not on a wave.</b> A level with no waves never reaches
 *       "all waves released", so the wave director cannot call it won; the mechanic does, once the
 *       final round is clear. That is {@link LevelServer#declareVictory()}.</li>
 *   <li><b>Contents live in this mechanic's own state, not in the level's vase map.</b> A vase
 *       the player places is a tool they own and the card inside it is theirs; a pot is the
 *       level's, and the two must not be able to hand each other their contents. They are also
 *       different pictures (`pvzce:pot_question` / `pvzce:pot_leaf` against `pvzce:vase`), so a
 *       player can see which is which.</li>
 * </ul>
 */
public final class ScaryPotterMechanic implements LevelMechanic<ScaryPotterData> {
    /** The NBT key this mechanic's run state is written under. */
    private static final String KEY_STATE = "ScaryPotter";

    @Override
    public MapCodec<ScaryPotterData> codec() {
        return ScaryPotterData.MAP_CODEC;
    }

    @Override
    public List<String> validate(LevelDef def, ScaryPotterData data) {
        List<String> errors = new ArrayList<>(data.validate(def.width(), def.height()));
        for (int index = 0; index < data.rounds().size(); index++) {
            for (ScaryPotterData.Pot pot : data.rounds().get(index).pots()) {
                String where = "scary_potter round " + (index + 1) + " pot '" + pot.id() + "'";
                if (pot.isPlant()) {
                    if (SlotResolver.resolve(pot.id()).isEmpty()) {
                        errors.add(where + " holds a plant, but no card or plant of that id"
                                + " exists, so breaking it would give the player nothing");
                    }
                } else if (pot.isResource()) {
                    if (BuiltInRegistries.RESOURCES.get(pot.id()) == null) {
                        errors.add(where + " holds a resource that does not exist, so breaking it"
                                + " would drop nothing");
                    }
                } else if (!BuiltInRegistries.ZOMBIES.containsKey(pot.id())) {
                    errors.add(where + " holds a zombie that does not exist");
                }
            }
        }
        return errors;
    }

    @Override
    public List<FieldSpec> editorFields() {
        // Deliberately no fields: a round is a list of lists, which is exactly the shape the
        // field table cannot express. The level editor shows the block as JSON instead.
        return List.of();
    }

    @Override
    public void onLevelCreated(LevelServer level, ScaryPotterData data) {
        State state = new State();
        level.setMechanicState(PvzceIds.MECHANIC_SCARY_POTTER, state);
        layOutRound(level, data, state);
    }

    @Override
    public void tick(LevelServer level, ScaryPotterData data) {
        State state = level.mechanicStateOrNull(PvzceIds.MECHANIC_SCARY_POTTER, State.class);
        if (state == null || state.cleared) {
            return;
        }
        recoverLostPots(level, state);
        if (!state.contents.isEmpty() || level.hostileZombieCount() > 0) {
            return;
        }
        // The round is over: everything is broken and everything that came out is dead.
        state.round++;
        if (state.round >= data.rounds().size()) {
            state.cleared = true;
            level.declareVictory();
            return;
        }
        layOutRound(level, data, state);
        level.emitEffect("", level.width() / 2F, level.height() / 2F, PvzceSounds.AMBIENT_HUGE_WAVE);
    }

    @Override
    public void collectSave(LevelServer level, ScaryPotterData data, CompoundTag root) {
        State state = level.mechanicStateOrNull(PvzceIds.MECHANIC_SCARY_POTTER, State.class);
        if (state == null) {
            return;
        }
        CompoundTag tag = new CompoundTag();
        tag.putInt("round", state.round);
        tag.putInt("cleared", state.cleared ? 1 : 0);
        ListTag pots = new ListTag();
        for (Map.Entry<Long, Contents> entry : state.contents.entrySet()) {
            CompoundTag pot = new CompoundTag();
            pot.putInt("x", (int) (entry.getKey() >> 32));
            pot.putInt("y", (int) (long) entry.getKey());
            pot.putString("kind", entry.getValue().kind());
            pot.putString("id", entry.getValue().id().toString());
            pots.add(pot);
        }
        tag.put("pots", pots);
        root.put(KEY_STATE, tag);
    }

    @Override
    public void applySave(LevelServer level, ScaryPotterData data, CompoundTag root) {
        State state = level.mechanicStateOrNull(PvzceIds.MECHANIC_SCARY_POTTER, State.class);
        if (state == null || !root.contains(KEY_STATE)) {
            return;
        }
        CompoundTag tag = root.getCompound(KEY_STATE);
        // The board is the rest of the state: which pots are left is the scene block, and what
        // each one holds is what this map adds to it. Both have to come back together, or a pot
        // would stand there with nothing behind it.
        state.round = tag.getInt("round");
        state.cleared = tag.getInt("cleared") != 0;
        state.contents.clear();
        ListTag pots = tag.getList("pots");
        for (int index = 0; index < pots.size(); index++) {
            CompoundTag pot = pots.getCompound(index);
            Contents contents = new Contents(pot.getString("kind"),
                    Identifier.parse(pot.getString("id")));
            state.contents.put(cellKey(pot.getInt("x"), pot.getInt("y")), contents);
        }
    }

    // ------------------------------------------------------------------
    // The board
    // ------------------------------------------------------------------

    /** What is inside one pot. */
    public record Contents(String kind, Identifier id) {
        public boolean isPlant() {
            return ScaryPotterData.KIND_PLANT.equals(kind);
        }

        /** True when this pot holds a resource drop rather than a card or a zombie. */
        public boolean isResource() {
            return ScaryPotterData.KIND_SUN.equals(kind);
        }
    }

    /** One level's run of this mechanic: which round, and what the pots still hold. */
    public static final class State {
        int round;
        boolean cleared;
        /** Cell to contents; a cell leaves the map when its pot is broken. */
        final Map<Long, Contents> contents = new HashMap<>();

        public int round() {
            return round;
        }

        public boolean cleared() {
            return cleared;
        }

        /** How many pots are still standing. */
        public int potsLeft() {
            return contents.size();
        }
    }

    /** True when this cell holds a pot of the vase level. */
    public static boolean isPot(LevelServer level, int x, int y) {
        var element = level.sceneAt(x, y);
        return element != null
                && (PvzceIds.POT_QUESTION.equals(element.id())
                    || PvzceIds.POT_LEAF.equals(element.id()));
    }

    /** What is inside the pot in this cell, or {@code null} when there is no pot there. */
    public static Contents contentsAt(LevelServer level, int x, int y) {
        State state = level.mechanicStateOrNull(PvzceIds.MECHANIC_SCARY_POTTER, State.class);
        return state == null ? null : state.contents.get(cellKey(x, y));
    }

    /**
     * Takes the pot out of the bookkeeping: the caller has already put the cell back to lawn.
     *
     * <p>Called by the level's own break path ({@code LevelServer.useHammer}), which owns the
     * scene write and the zombie or card that comes out; this only forgets the pot, which is what
     * makes the round advance when the last one goes.
     */
    public static void forget(LevelServer level, int x, int y) {
        State state = level.mechanicStateOrNull(PvzceIds.MECHANIC_SCARY_POTTER, State.class);
        if (state != null) {
            state.contents.remove(cellKey(x, y));
        }
    }

    /** The state of a level running this mechanic, or empty when it is not. */
    public static Optional<State> stateOf(LevelServer level) {
        return Optional.ofNullable(
                level.mechanicStateOrNull(PvzceIds.MECHANIC_SCARY_POTTER, State.class));
    }

    /**
     * Stands one round's pots up.
     *
     * <p>Cells are drawn from the level's own random source and shuffled, so the lawn is a
     * different arrangement every attempt - which is the original's own reading of the level, and
     * the reason the pots are not part of the {@code scene} block.
     */
    private static void layOutRound(LevelServer level, ScaryPotterData data, State state) {
        ScaryPotterData.Round round = data.rounds().get(state.round);
        List<long[]> cells = new ArrayList<>();
        for (int x = round.fromColumn(); x < level.width(); x++) {
            for (int y = 0; y < level.height(); y++) {
                if (level.sceneAt(x, y) == null || level.plantAt(x, y) != null) {
                    continue;
                }
                cells.add(new long[]{x, y});
            }
        }
        // A source of this round's own, derived from the level and the round number: the board
        // has to be the *same* board every time it is laid out, because the client is handed an
        // opening board built by a second, throwaway level (`LevelServer.openingBoard`) and a
        // restored run comes back to a round it may already have half-cleared. The level's own
        // `random()` is for the things that should differ between attempts (wave lanes, sun
        // drops); a pot layout that differed would be a picture of pots the server does not have.
        Collections.shuffle(cells, new java.util.Random(
                level.def().id().toString().hashCode() * 31L + state.round));

        // Which pots wear the leaf is decided first, so the choice is spread over the plant pots
        // rather than over the placement order (the original turns a random handful of the seed
        // pots green after laying them all out).
        int leafLeft = Math.max(0, round.leafCount());
        int index = 0;
        for (ScaryPotterData.Pot pot : round.pots()) {
            for (int placed = 0; placed < pot.count() && index < cells.size(); placed++, index++) {
                long[] cell = cells.get(index);
                int x = (int) cell[0];
                int y = (int) cell[1];
                boolean leaf = pot.isPlant() && leafLeft > 0;
                if (leaf) {
                    leafLeft--;
                }
                level.setScene(x, y, leaf ? PvzceIds.POT_LEAF : PvzceIds.POT_QUESTION);
                // The first round is laid out while the level is being built (no client yet, and
                // the opening snapshot carries the board); a later round appears mid-run, so the
                // cell has to be sent or the player would be looking at an empty lawn with pots
                // that are already there.
                level.sendSceneCell(x, y);
                state.contents.put(cellKey(x, y), new Contents(pot.kind(), pot.id()));
            }
        }
    }

    /**
     * Puts back any pot the board has lost under a record that still counts it.
     *
     * <p>The run's state and the lawn can disagree in exactly one direction: a cell that still
     * has contents but is no longer drawn as a pot. That is not a hypothetical - the vase tool
     * used to be able to overwrite a pot with an empty vase, which left the pot's contents in
     * this map with nothing on the lawn to break, so the round could never be finished.
     *
     * <p>So a lost pot is stood back up (the player is told), and one whose cell has since grown
     * a plant is written off instead - keeping the entry would make the level unclearable, and a
     * pot under a plant could never be reached anyway.
     */
    private static void recoverLostPots(LevelServer level, State state) {
        for (Map.Entry<Long, Contents> entry : new HashMap<>(state.contents).entrySet()) {
            int x = (int) (entry.getKey() >> 32);
            int y = (int) (long) entry.getKey();
            if (isPot(level, x, y)) {
                continue;
            }
            var element = level.sceneAt(x, y);
            // Bare ground, or the empty vase the old bug left behind in its place: both are
            // cells the run counted a pot in and the board does not. A vase the *player* filled
            // (`vase_full`) is theirs and is left alone.
            boolean recoverable = element != null
                    && (PvzceIds.GRASS.equals(element.id()) || PvzceIds.GROUND.equals(element.id())
                        || PvzceIds.VASE.equals(element.id()));
            if (recoverable && level.plantAt(x, y) == null) {
                // Back as a question pot: which pots were green was decided when the round was
                // laid out, and a pot that has to be put back is a repair, not a re-roll.
                level.setScene(x, y, PvzceIds.POT_QUESTION);
                level.sendSceneCell(x, y);
                continue;
            }
            state.contents.remove(entry.getKey());
        }
    }

    /** The same packing the level's vase map uses, so the two read alike. */
    private static long cellKey(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }
}
