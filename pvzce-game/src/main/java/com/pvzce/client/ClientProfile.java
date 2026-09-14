package com.pvzce.client;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.SlotResolver;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The client's read-only copy of the current world's profile.
 *
 * <p>Menus only: the server decides what a level may offer and filters the card
 * pool before it is sent, so nothing here is ever authoritative. It exists so the
 * level list can draw the coin counter and the backpack can grey out what is still
 * locked without inventing its own idea of what "unlocked" means - {@link #owns}
 * asks the same {@link SlotResolver#requiresUnlock} the server's
 * {@code PlayerProfile} does.
 */
public final class ClientProfile {
    private final Set<Identifier> unlocked = new LinkedHashSet<>();
    private int coins;
    private int seedSlots = com.pvzce.common.PvzceConstants.DEFAULT_SEED_SLOTS;
    private boolean unlockAll;

    /** Applies a server snapshot; unparsable ids are dropped rather than kept as junk. */
    public void apply(int coins, List<String> unlockedIds, boolean unlockAll) {
        apply(coins, unlockedIds, unlockAll, com.pvzce.common.PvzceConstants.DEFAULT_SEED_SLOTS);
    }

    /** Applies a server snapshot including the backpack's card-slot count. */
    public void apply(int coins, List<String> unlockedIds, boolean unlockAll, int seedSlots) {
        this.coins = Math.max(0, coins);
        this.unlockAll = unlockAll;
        this.seedSlots = Math.max(1, seedSlots);
        unlocked.clear();
        if (unlockedIds != null) {
            for (String raw : unlockedIds) {
                Identifier id = Identifier.tryParse(raw);
                if (id != null) {
                    unlocked.add(id);
                }
            }
        }
    }

    /** Forgets everything; used when the client drops a world's cached state. */
    public void reset() {
        apply(0, List.of(), false);
    }

    public int coins() {
        return coins;
    }

    /**
     * How many card slots this backpack holds.
     *
     * <p>Read-only here: the server owns the number, and a level that declares its own
     * {@code max_seed_slots} overrides it in the payload the chooser is built from.
     */
    public int seedSlots() {
        return seedSlots;
    }

    public boolean unlockAll() {
        return unlockAll;
    }

    /** The explicitly unlocked ids; empty for a sandbox world, which owns everything. */
    public Set<Identifier> unlocked() {
        return Set.copyOf(unlocked);
    }

    /**
     * True when this card is available to the player.
     *
     * <p>Mirrors the server rule exactly: resources are never gated, a sandbox
     * world owns everything, and a plant is owned when either its slot id or the
     * content that slot grants is unlocked.
     */
    public boolean owns(Identifier card) {
        if (card == null) {
            return false;
        }
        if (!SlotResolver.requiresUnlock(card) || unlockAll) {
            return true;
        }
        if (unlocked.contains(card)) {
            return true;
        }
        SlotResolver.ResolvedCard resolved = SlotResolver.resolve(card).orElse(null);
        return resolved != null && unlocked.contains(resolved.content());
    }
}
