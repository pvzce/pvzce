package com.pvzce.common.level.mutation;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.core.Slot;
import com.pvzce.server.PvzcePlayer;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every plant card on the bar is replaced by another plant with the same abilities.
 *
 * <p>The player chose a mix - so many shooters, so many producers - and that mix survives; what
 * changes is which plant fills each slot. The new plant comes from the player's own backpack,
 * so a mutation can never hand out something they have not unlocked, and it may be the same
 * plant that was there (the rule is "random", not "different").
 *
 * <p>Rolled once, when it arrives, and then frozen: the whole point is that the player can see
 * what they now have and plan around it, and a bar that re-rolled itself would be a different
 * mutation wearing this one's name.
 *
 * <p>The previous bar goes into the state, which is what makes the eviction clean: whatever the
 * player's bar looks like when this mutation leaves, it is replaced by the one recorded here -
 * so a second replacement mutation that applied on top of this one is undone by its own state
 * rather than by this one guessing.
 *
 * <p>A {@link RewriteBarMutation}: it changes what is <em>on</em> the bar rather than which bar it
 * is. That is the other half of the card-bar takeover, and the two kinds compete on arrival order
 * alone - a belt that lands after this one hides its work, and this one landing after a belt takes
 * the bar back. See {@link Mutation#suppressedBy}.
 */
final class SlotReplaceMutation implements Mutation, RewriteBarMutation {
    @Override
    public Identifier id() {
        return PvzceIds.MUTATION_SLOT_REPLACE;
    }

    /**
     * Lays the substitution this activation rolled back onto whatever bar is standing now.
     *
     * <p>Needed because the level rebuilds its own bar from the player's chosen cards whenever the
     * bar's owner changes (a belt arriving or leaving, a round re-pick, a restore), and that
     * rebuild knows nothing about this mutation. Without this the rewrite would survive only until
     * the next rebuild - which is how a belt leaving the field used to hand the player their
     * original cards back with the mutation still on the panel.
     *
     * <p>Reads the <em>saved</em> card list, not a fresh roll: the whole point of this mutation is
     * that the player can see what they have and plan around it.
     */
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
        List<Slot> before = List.copyOf(player.slots());
        Map<List<Identifier>, List<Identifier>> groups =
                PlantAbilityGroups.ownedPlantsByAbility(level.ownedCards());
        List<Identifier> cards = new ArrayList<>(before.size());
        for (Slot slot : before) {
            cards.add(replacedCard(slot, groups, level));
        }
        player.replaceSlots(PvzcePlayer.deckSlots(cards));
        return new Applied(MutationCards.idsOf(player), before);
    }

    /**
     * On a restore the substitution is <em>not</em> rolled again.
     *
     * <p>It was rolled once and frozen precisely so the player could plan around it, and a resume
     * that re-rolled would hand them a different bar from the one they left - so the cards this
     * mutation handed out are saved with it (see {@link #saveState}) and the bar is simply
     * rebuilt from them.
     */
    @Override
    public Object applyFromSave(LevelServer level, Mutation.Roll roll) {
        List<Identifier> cards = pendingCards;
        pendingCards = List.of();
        if (cards.isEmpty()) {
            // A save from a build that did not record the bar: the mutation keeps the bar the
            // level restored. It stays on the list, and an eviction with nothing to put back
            // leaves the bar as it is rather than emptying it.
            return new Applied(cards, List.of());
        }
        // The bar is rebuilt rather than merely described. A level constructs its own bar before
        // anything is restored, and this mutation's bar is not that one - so a resume that only
        // wrote down which cards it had handed out would leave the player holding the deck they
        // chose, with the mutation still on the panel claiming otherwise.
        PvzcePlayer player = level.plantPlayer();
        if (player != null) {
            player.replaceSlots(MutationCards.slotsOf(cards));
        }
        return new Applied(cards, List.of());
    }

    /**
     * The bar as it stands when the save is written.
     *
     * <p>Read from the player rather than remembered from {@link #apply}: another mutation may
     * have replaced a card on top of this one since, and the bar the player is looking at is the
     * one a resume has to reproduce.
     */
    @Override
    public CompoundTag saveState() {
        CompoundTag tag = new CompoundTag();
        MutationCards.save(tag, MutationCards.idsOf(savingPlayer));
        return tag;
    }

    @Override
    public void loadState(CompoundTag tag) {
        // Held on the (shared) mutation instance only between `loadState` and `applyFromSave`,
        // which the manager calls back to back: see `Mutation.loadState`.
        this.pendingCards = MutationCards.load(tag);
    }

    /** The player whose bar {@link #saveState} should read; set by the manager around the call. */
    private com.pvzce.server.PvzcePlayer savingPlayer;
    /** What {@link #loadState} read, waiting for {@link #applyFromSave} to consume it. */
    private List<Identifier> pendingCards = List.of();

    /** How the manager hands this mutation the bar it should write down. */
    void savingPlayer(com.pvzce.server.PvzcePlayer player) {
        this.savingPlayer = player;
    }

    @Override
    public void revert(LevelServer level, Mutation.Roll roll, Object state) {
        PvzcePlayer player = level.plantPlayer();
        if (player == null || !(state instanceof Applied applied)) {
            return;
        }
        // The bar as it was before this mutation replaced it. A save written while it was running
        // carries that list, so a resumed run can still be handed its own cards back.
        player.replaceSlots(applied.before().isEmpty()
                ? MutationCards.slotsOf(applied.cards())
                : applied.before());
    }

    /**
     * One card's replacement, or the card itself when there is nothing to replace it with.
     *
     * <p>"Nothing to replace it with" is a real answer: the player may own exactly one plant with
     * those abilities, in which case the group is a group of one and the honest outcome is that
     * this card does not change. Substituting something from a neighbouring group would break the
     * one promise the mutation makes.
     */
    private static Identifier replacedCard(Slot slot, Map<List<Identifier>, List<Identifier>> groups,
                                           LevelServer level) {
        if (slot.kind() != Slot.Kind.PLANT) {
            // Tools and resource cards are not plants and have no abilities: a shovel slot stays a
            // shovel slot, which is also what keeps the mutation from taking away the only way to
            // dig up a mistake.
            return slot.defId();
        }
        List<Identifier> candidates = PlantAbilityGroups.replacementsFor(slot.defId(), groups);
        if (candidates.isEmpty()) {
            return slot.defId();
        }
        return candidates.get(level.random().nextInt(candidates.size()));
    }

    /**
     * What this activation remembers.
     *
     * @param cards  the bar it handed out, which is what a save carries
     * @param before the bar it replaced, which is what an eviction puts back
     */
    private record Applied(List<Identifier> cards, List<Slot> before) {
    }
}
