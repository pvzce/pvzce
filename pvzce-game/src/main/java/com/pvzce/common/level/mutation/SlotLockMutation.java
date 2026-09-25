package com.pvzce.common.level.mutation;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.Slot;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.common.nbt.Tag;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.List;

/**
 * One or two plant cards are locked for as long as the mutation stands: "卡槽封锁".
 *
 * <p>The bar is the player's plan, and this mutation takes a piece of it away without touching
 * what the piece <em>is</em>: the card is still drawn, still priced, still counting down - it simply
 * cannot be used. That is why the lock is a hook of its own rather than a card rewrite: a rewrite
 * hands the player a different plant, and this says "not this one, not now".
 *
 * <p><b>Which slots</b> is rolled once, from the plant cards on the bar at that moment, and kept:
 * a lock that moved every few seconds would be unplayable rather than hard, and the player's whole
 * counter-play is "plant around the hole". Only <em>plant</em> cards are ever picked, because a
 * locked shovel would take away the only way to fix a mistake, and a locked sun card would stop the
 * economy rather than redirect it.
 *
 * <p>Tools and resource cards are therefore never locked, and the count is 1 or 2: the mutation
 * rolls a number and takes that many <em>different</em> plant slots. If the bar has no plant card
 * at all - a level that deals only tools - it locks nothing and waits.
 */
final class SlotLockMutation implements Mutation, MutationManager.SaveHandle {
    /** The most plant cards one activation may take out of play. */
    private static final int MAX_LOCKED = 2;

    @Override
    public Identifier id() {
        return PvzceIds.MUTATION_SLOT_LOCK;
    }

    /**
     * The slots this activation locked, ascending, or empty when it locked nothing.
     *
     * <p>Pure: the manager asks this per index on both the refusal path and the state packet, so it
     * has to be cheap and side-effect free. Everything it needs is in the state it is handed.
     */
    @Override
    public boolean isSlotLocked(LevelServer level, Mutation.Roll roll, Object state, int slotIndex) {
        return state instanceof Applied applied && applied.locked().contains(slotIndex);
    }

    /**
     * Picks which cards are locked, without touching the bar.
     *
     * <p>Locking changes no state of the level's own - the refusal happens where planting is asked
     * for - so {@code apply} only decides the set. That also makes a restore trivial: the same
     * indices come back from the save rather than being rolled again, which is what keeps a resumed
     * run locked on the cards the player was looking at.
     *
     * <p>The count is <b>not</b> this mutation's roll. A roll's number is drawn on the panel as
     * "×1.4", which is the right word for a strength and the wrong one for "how many cards"; the
     * tier already scales how many mutations a level carries, so one or two is rolled here and the
     * panel stays readable.
     */
    @Override
    public Object apply(LevelServer level, Mutation.Roll roll) {
        return new Applied(lockedFrom(level));
    }

    @Override
    public Object applyFromSave(LevelServer level, Mutation.Roll roll) {
        List<Integer> locked = pendingLocked;
        pendingLocked = List.of();
        return new Applied(locked);
    }

    /**
     * The lock lives only in the state, so the shared instance needs the handle the manager sets
     * around {@link #saveState} - the same shape {@code SlotReplaceMutation} uses.
     */
    @Override
    public CompoundTag saveState() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (int index : savingLocked) {
            list.add(new com.pvzce.common.nbt.IntTag(index));
        }
        tag.put("Locked", list);

        return tag;
    }

    @Override
    public void loadState(CompoundTag tag) {
        List<Integer> locked = new ArrayList<>();
        if (tag != null) {
            for (Tag element : tag.getList("Locked").values()) {
                if (element instanceof com.pvzce.common.nbt.IntTag index) {
                    locked.add(index.value());
                }
            }
        }
        this.pendingLocked = List.copyOf(locked);
    }

    @Override
    public MutationEffects clientEffects() {
        // Nothing beyond the panel: the padlock is drawn from `lockedSlots`, which rides the state
        // packet every mutation already sends.
        return MutationEffects.NONE;
    }

    @Override
    public java.util.Optional<String> announcement(LevelServer level, Mutation.Roll roll,
                                                  Object state) {
        if (!(state instanceof Applied applied) || applied.locked().isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of("卡槽封锁：第 "
                + applied.locked().stream().map(index -> String.valueOf(index + 1))
                        .collect(java.util.stream.Collectors.joining("、"))
                + " 张卡被锁住了");
    }

    /** Which plant slots this activation takes, given the bar as it stands. */
    private static List<Integer> lockedFrom(LevelServer level) {
        if (level.plantPlayer() == null) {
            return List.of();
        }
        List<Integer> plants = new ArrayList<>();
        for (Slot slot : level.plantPlayer().slots()) {
            if (slot.kind() == Slot.Kind.PLANT) {
                plants.add(slot.index());
            }
        }
        if (plants.isEmpty()) {
            return List.of();
        }
        // One or two, never more than the bar can spare. Two is the ceiling for a reason a player
        // can feel: a bar with three of its cards locked is a bar the level cannot be played with.
        int wanted = Math.min(plants.size(), 1 + level.random().nextInt(MAX_LOCKED));
        List<Integer> locked = new ArrayList<>(wanted);
        while (locked.size() < wanted && !plants.isEmpty()) {
            locked.add(plants.remove(level.random().nextInt(plants.size())));
        }
        java.util.Collections.sort(locked);
        return List.copyOf(locked);
    }

    /** The slots this activation locked. */
    private record Applied(List<Integer> locked) {
    }

    /** The lock as it stands, set by the manager around {@link #saveState}. */
    private List<Integer> savingLocked = List.of();
    /** What {@link #loadState} read, waiting for {@link #applyFromSave} to consume it. */
    private List<Integer> pendingLocked = List.of();

    /** How the manager hands this mutation the state it should write down. */
    @Override
    public void savingState(Object state) {
        this.savingLocked = state instanceof Applied applied ? applied.locked() : null;
    }

}
