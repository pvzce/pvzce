package com.pvzce.common.level.mutation;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.Slot;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.PvzcePlayer;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.List;

/**
 * One plant card is swapped for a random plant, over and over: "卡槽轮盘".
 *
 * <p>The difference from {@link SlotReplaceMutation}, which is the same sentence with the opposite
 * clock: that one rolls once and freezes, so the player can look at the bar and plan. This one keeps
 * going, which makes the bar something to keep re-reading - the mutation for a level whose point is
 * that a plan does not survive.
 *
 * <p>The two are therefore mutually exclusive, and the rule between them is the catalogue's general
 * one: <b>the later one wins</b> ({@link Mutation#suppressedBy}, which holds for any pair of bar
 * mutations). Arriving together and both rewriting is the one outcome a player could not read.
 *
 * <p>Which card changes is rolled at each swap, and the replacement comes from the player's own
 * backpack - a mutation may hand out a card the player did not pick, but never one they have not
 * unlocked. The card that was there may come back: the rule is "random", not "different".
 *
 * <p>A {@link RewriteBarMutation} for the same reason as the replacement: the level rebuilds its bar
 * whenever the bar's owner changes (a belt arriving or leaving, a round re-pick, a restore), and
 * that rebuild knows nothing about this mutation, so it has to be able to lay its work back on.
 */
final class SlotRouletteMutation implements Mutation, RewriteBarMutation, MutationManager.SaveHandle {
    /** The gap between two swaps, before the tier's multiplier. */
    private static final int SWAP_INTERVAL_TICKS = 1500;

    @Override
    public Identifier id() {
        return PvzceIds.MUTATION_SLOT_ROULETTE;
    }

    @Override
    public void rewriteBar(LevelServer level, Object state) {
        if (!(state instanceof Applied applied) || applied.cards().isEmpty()) {
            return;
        }
        PvzcePlayer player = level.plantPlayer();
        if (player != null) {
            player.replaceSlots(MutationCards.slotsOf(applied.cards()));
        }
    }

    @Override
    public Object apply(LevelServer level, Mutation.Roll roll) {
        PvzcePlayer player = level.plantPlayer();
        if (player == null) {
            return null;
        }
        // The bar as it stands, kept so an eviction can hand it back: over a long run the roulette
        // has swapped a dozen times, and "undo the mutation" has to mean the bar the player chose
        // rather than the one it happened to be showing.
        List<Identifier> before = MutationCards.idsOf(player);
        return new Applied(before, before,
                level.mutations().difficulty().scaledInterval(SWAP_INTERVAL_TICKS));
    }

    /**
     * On a restore the bar is rebuilt from the cards it was showing, and the clock comes back.
     *
     * <p>Nothing is re-rolled: a resume has to hand the player the bar they were looking at, and the
     * swaps it already made are in the save.
     */
    @Override
    public Object applyFromSave(LevelServer level, Mutation.Roll roll) {
        Applied saved = pendingState;
        pendingState = null;
        if (saved == null) {
            // A save from a build that did not record the roulette: the mutation keeps the bar the
            // level restored and starts its clock over, which is the old behaviour.
            return apply(level, roll);
        }
        PvzcePlayer player = level.plantPlayer();
        if (player != null && !saved.cards().isEmpty()) {
            player.replaceSlots(MutationCards.slotsOf(saved.cards()));
        }
        return saved;
    }

    @Override
    public void tick(LevelServer level, Mutation.Roll roll, Object state) {
        if (!(state instanceof Applied applied)) {
            return;
        }
        if (--applied.ticksUntilSwap > 0) {
            return;
        }
        applied.ticksUntilSwap = applied.interval();
        PvzcePlayer player = level.plantPlayer();
        if (player == null) {
            return;
        }
        List<Identifier> candidates = ownedPlants(level);
        if (candidates.isEmpty()) {
            return;
        }
        List<Slot> slots = new ArrayList<>(player.slots());
        List<Integer> plantSlots = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).kind() == Slot.Kind.PLANT) {
                plantSlots.add(i);
            }
        }
        if (plantSlots.isEmpty()) {
            return;
        }
        int target = plantSlots.get(level.random().nextInt(plantSlots.size()));
        Identifier card = candidates.get(level.random().nextInt(candidates.size()));
        List<Identifier> cards = new ArrayList<>(applied.cards());
        if (target >= cards.size()) {
            // The bar changed shape under the mutation (a card was handed over by a vase, a belt
            // rebuilt it): the recorded list is the one a save and an eviction use, so it is
            // extended rather than indexing out of bounds.
            cards = MutationCards.idsOf(player);
        }
        cards.set(target, card);
        applied.cards = List.copyOf(cards);
        applied.swaps++;
        player.replaceSlots(MutationCards.slotsOf(applied.cards()));
    }

    @Override
    public void revert(LevelServer level, Mutation.Roll roll, Object state) {
        PvzcePlayer player = level.plantPlayer();
        if (player == null || !(state instanceof Applied applied)) {
            return;
        }
        player.replaceSlots(applied.before().isEmpty()
                ? MutationCards.slotsOf(applied.cards())
                : MutationCards.slotsOf(applied.before()));
    }

    @Override
    public CompoundTag saveState() {
        CompoundTag tag = new CompoundTag();
        if (savingState != null) {
            MutationCards.save(tag, savingState.cards);
            tag.putInt("Interval", savingState.interval);
            tag.putInt("TicksUntilSwap", savingState.ticksUntilSwap);
            tag.putInt("Swaps", savingState.swaps);
        }
        return tag;
    }

    @Override
    public void loadState(CompoundTag tag) {
        if (tag == null || !tag.contains("Interval")) {
            this.pendingState = null;
            return;
        }
        Applied applied = new Applied(MutationCards.load(tag), List.of(), tag.getInt("Interval"));
        applied.ticksUntilSwap = Math.max(1, tag.getInt("TicksUntilSwap"));
        applied.swaps = Math.max(0, tag.getInt("Swaps"));
        this.pendingState = applied;
    }

    @Override
    public java.util.Optional<String> announcement(LevelServer level, Mutation.Roll roll,
                                                  Object state) {
        return java.util.Optional.of("卡槽轮盘：卡槽会不断换成别的植物");
    }

    /** The plant cards the player owns, which is the whole pool a swap may pick from. */
    private static List<Identifier> ownedPlants(LevelServer level) {
        List<Identifier> owned = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.PLANTS.keySet()) {
            if (level.ownedCards().test(id)) {
                SlotResolver.ResolvedCard resolved = SlotResolver.resolve(id).orElse(null);
                if (resolved != null && resolved.kind() == Slot.Kind.PLANT) {
                    owned.add(id);
                }
            }
        }
        return owned;
    }

    /** The state as it stands, set by the manager around {@link #saveState}. */
    private Applied savingState;
    /** What {@link #loadState} read, waiting for {@link #applyFromSave} to consume it. */
    private Applied pendingState;

    @Override
    public void savingState(Object state) {
        this.savingState = state instanceof Applied applied ? applied : null;
    }

    /**
     * What this activation remembers.
     *
     * @param cards          the bar as it stands now, which a save carries and a rebuild re-lays
     * @param before         the bar the player chose, which an eviction puts back
     * @param interval       the gap between swaps, already scaled by the tier
     */
    private static final class Applied {
        private List<Identifier> cards;
        private final List<Identifier> before;
        private final int interval;
        private int ticksUntilSwap;
        private int swaps;

        private Applied(List<Identifier> cards, List<Identifier> before, int interval) {
            this.cards = List.copyOf(cards);
            this.before = List.copyOf(before);
            this.interval = Math.max(1, interval);
            this.ticksUntilSwap = this.interval;
        }

        List<Identifier> cards() {
            return cards;
        }

        List<Identifier> before() {
            return before;
        }

        int interval() {
            return interval;
        }
    }
}
