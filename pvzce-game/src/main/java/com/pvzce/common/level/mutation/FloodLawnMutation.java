package com.pvzce.common.level.mutation;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.common.nbt.StringTag;
import com.pvzce.common.nbt.Tag;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The middle of the lawn becomes water: "水淹草坪".
 *
 * <p>The board rewrite, and the mutation that says what a rewritten board means: the rows are water
 * for everything that reads terrain - planting rules, the zombies' own lane choice, the pool's
 * swimming - and the zombies that were walking there when it happened start swimming or drown.
 * That is the mutation: not a decoration, a change of what the lawn <em>is</em>.
 *
 * <p>The two rows are the board's middle ones, as a fraction: a level is not always nine by six, and
 * "the middle two" is what a player reads off a pool board.
 *
 * <p><b>The cells it changed are remembered, one by one.</b> Restoring the water to "whatever was
 * there" is not a constant - a level may have ice, craters or graves on those rows - so the mutation
 * records the element each cell had and puts exactly those back. The <em>saved</em> scene carries the
 * water already (it is part of the level's own scene block), so {@code applyFromSave} only rebuilds
 * the record it needs for the eviction.
 */
final class FloodLawnMutation implements Mutation, MutationManager.SaveHandle {
    /** How many rows flood. */
    private static final int FLOODED_ROWS = 2;
    /** How many rows of a pool board are already water, and are therefore not candidates. */
    private static final int WATER_ROWS = 2;

    @Override
    public Identifier id() {
        return PvzceIds.MUTATION_FLOOD_LAWN;
    }

    /**
     * Which rows this board floods: the two dry rows nearest its middle.
     *
     * <p><b>Nearest the middle rather than "the middle two".</b> On a yard board those are the same
     * thing, and on a <em>pool</em> board - which is where every shipped mutation level is played -
     * the middle two rows are the water already, so a mutation that flooded them would be a
     * mutation that did nothing at all. The two dry rows beside the pool are what "the water is
     * spreading" means there.
     *
     * @param alreadyWater which rows are water before this mutation runs
     */
    static List<Integer> floodedRows(int height, java.util.function.IntPredicate alreadyWater) {
        List<Integer> candidates = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            if (alreadyWater == null || !alreadyWater.test(y)) {
                candidates.add(y);
            }
        }
        float centre = (height - 1) / 2F;
        candidates.sort(java.util.Comparator.comparingDouble(
                (Integer y) -> Math.abs(y - centre)));
        List<Integer> rows = new ArrayList<>(FLOODED_ROWS);
        for (int i = 0; i < FLOODED_ROWS && i < candidates.size(); i++) {
            rows.add(candidates.get(i));
        }
        java.util.Collections.sort(rows);
        return List.copyOf(rows);
    }

    @Override
    public Object apply(LevelServer level, Mutation.Roll roll) {
        Map<Long, Identifier> before = new LinkedHashMap<>();
        List<Integer> rows = floodedRows(level.height(), level::rowIsWater);
        for (int x = 0; x < level.width(); x++) {
            for (int y : rows) {
                Identifier here = level.sceneIdAt(x, y);
                if (here != null && !PvzceIds.FLOOD_WATER.equals(here)) {
                    before.put(key(x, y), here);
                }
                level.setScene(x, y, PvzceIds.FLOOD_WATER);
                level.sendSceneCell(x, y);
            }
        }
        return new Applied(before, rows);
    }

    /**
     * On a restore the water is already in the save's scene block, so only the record is rebuilt.
     *
     * <p>That record is what an eviction puts back, and it has to be rebuilt from the <em>level's own
     * declaration</em> rather than read out of the save: the saved cells are water, and putting water
     * back where water is would leave the lawn flooded forever. The rows are the same rows, and what
     * they were before is what the level says they are.
     */
    @Override
    public Object applyFromSave(LevelServer level, Mutation.Roll roll) {
        List<Integer> rows = floodedRows(level.height(), level::rowIsWater);
        Map<Long, Identifier> before = new LinkedHashMap<>();
        for (int x = 0; x < level.width(); x++) {
            for (int y : rows) {
                Identifier declared = declaredScene(level, x, y);
                if (declared != null && !PvzceIds.FLOOD_WATER.equals(declared)) {
                    before.put(key(x, y), declared);
                }
            }
        }
        return new Applied(before, rows);
    }

    /**
     * What this cell is when the board is not flooded: the level's own scene, or the level's default.
     *
     * <p>Read from the definition rather than from the live grid, because the live grid is the
     * flooded one by the time a restore asks. A level that painted something on those rows keeps it;
     * a level that did not gets grass back.
     */
    private static Identifier declaredScene(LevelServer level, int x, int y) {
        // `lookup` is the one answer to "what does the file say this cell is", and it is the same
        // call the level's own construction makes - including its last-declaration-wins rule.
        Identifier declared = com.pvzce.common.core.SceneCells.lookup(
                level.def().scene(), x, y);
        return declared != null ? declared : PvzceIds.GRASS;
    }

    @Override
    public void revert(LevelServer level, Mutation.Roll roll, Object state) {
        if (!(state instanceof Applied applied)) {
            return;
        }
        for (int x = 0; x < level.width(); x++) {
            for (int y : applied.rows()) {
                // Whatever the level's own rule says this cell is, which for the rows the mutation
                // flooded is the recorded element and for anything painted since is what it is.
                Identifier restored = applied.before().get(key(x, y));
                level.setScene(x, y, restored == null ? declaredScene(level, x, y) : restored);
                level.sendSceneCell(x, y);
            }
        }
    }

    @Override
    public CompoundTag saveState() {
        CompoundTag tag = new CompoundTag();
        if (savingState == null) {
            return tag;
        }
        ListTag cells = new ListTag();
        for (Map.Entry<Long, Identifier> entry : savingState.before().entrySet()) {
            CompoundTag cell = new CompoundTag();
            cell.putInt("x", (int) (entry.getKey() >> 32));
            cell.putInt("y", (int) (long) entry.getKey());
            cell.putString("element", entry.getValue().toString());
            cells.add(cell);
        }
        tag.put("Before", cells);
        return tag;
    }

    /**
     * Reads back what the cells were.
     *
     * <p>Unused by {@link #applyFromSave}, which rebuilds the record from the level's own file - the
     * save is read here only so a future change of mind has the original in hand, and so the block
     * round-trips rather than being silently dropped.
     */
    @Override
    public void loadState(CompoundTag tag) {
        this.pendingBefore = new LinkedHashMap<>();
        if (tag == null) {
            return;
        }
        for (Tag element : tag.getList("Before").values()) {
            if (!(element instanceof CompoundTag cell)) {
                continue;
            }
            Identifier id = Identifier.tryParse(cell.getString("element"));
            if (id != null) {
                pendingBefore.put(key(cell.getInt("x"), cell.getInt("y")), id);
            }
        }
    }

    @Override
    public java.util.Optional<String> announcement(LevelServer level, Mutation.Roll roll,
                                                  Object state) {
        return java.util.Optional.of("水淹草坪：中间两行变成了水池");
    }

    private static long key(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    /** The cells as they were, and which rows were flooded. */
    private record Applied(Map<Long, Identifier> before, List<Integer> rows) {
        private Applied {
            before = Map.copyOf(before);
            rows = List.copyOf(rows);
        }
    }

    /** The state as it stands, set by the manager around {@link #saveState}. */
    private Applied savingState;
    /** What {@link #loadState} read; see that method for why nothing acts on it. */
    private Map<Long, Identifier> pendingBefore = new LinkedHashMap<>();

    @Override
    public void savingState(Object state) {
        this.savingState = state instanceof Applied applied ? applied : null;
    }
}
