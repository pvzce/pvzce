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
    /**
     * The level buffs this world has been given.
     *
     * <p>Carried for the shop, which has to answer "do I already own the sun shovel" - and for the
     * seed chooser's buff page, where an unbought buff is padlocked. It is a separate set from
     * {@link #unlocked} for the server's reason: a buff is not a card, and a world that owns the
     * card {@code pvzce:sun_shovel} owns it because it owns the <em>buff</em> of that name.
     */
    private final Set<Identifier> unlockedBuffs = new LinkedHashSet<>();
    private int coins;
    private int seedSlots = com.pvzce.common.PvzceConstants.DEFAULT_SEED_SLOTS;
    private int buffSlots = com.pvzce.common.PvzceConstants.DEFAULT_BUFF_SLOTS;
    /**
     * The buffs this world switches on by itself, in the order the server last stored them.
     *
     * <p>Read-only here for the same reason everything else in this class is: the chooser shows
     * them pre-selected, and what the player does with that goes back through the server by
     * starting a level - there is no "save preferences" packet, because a run's buffs are the
     * preference.
     */
    private final List<Identifier> autoBuffs = new java.util.ArrayList<>();
    private boolean unlockAll;
    /**
     * The world's difficulty tier, as the server last reported it.
     *
     * <p>Read-only here like everything else: the settings page sends the choice and the level list
     * draws the badge, and neither of them applies anything - the server folds the tier into the
     * level's rules, so a client that disagreed could only be wrong.
     */
    private com.pvzce.common.level.Difficulty difficulty =
            com.pvzce.common.level.Difficulty.DEFAULT;

    /** Applies a server snapshot; unparsable ids are dropped rather than kept as junk. */
    public void apply(int coins, List<String> unlockedIds, boolean unlockAll) {
        apply(coins, unlockedIds, unlockAll, com.pvzce.common.PvzceConstants.DEFAULT_SEED_SLOTS);
    }

    /** Applies a server snapshot including the backpack's card-slot count. */
    public void apply(int coins, List<String> unlockedIds, boolean unlockAll, int seedSlots) {
        apply(coins, unlockedIds, unlockAll, seedSlots,
                com.pvzce.common.PvzceConstants.DEFAULT_BUFF_SLOTS, List.of());
    }

    /** Applies a server snapshot including the buff half of the backpack. */
    public void apply(int coins, List<String> unlockedIds, boolean unlockAll, int seedSlots,
                      int buffSlots, List<String> autoBuffIds) {
        apply(coins, unlockedIds, unlockAll, seedSlots, buffSlots, autoBuffIds, List.of());
    }

    /**
     * Applies a server snapshot including the buffs the world has been given.
     *
     * <p>The five-argument overload above stopped at the auto-enabled list, which is a different
     * fact: "which buffs are switched on by default" is a preference, and "which buffs this world
     * has been handed" is what the padlocks and the shop read.
     */
    public void apply(int coins, List<String> unlockedIds, boolean unlockAll, int seedSlots,
                      int buffSlots, List<String> autoBuffIds, List<String> unlockedBuffIds) {
        apply(coins, unlockedIds, unlockAll, seedSlots, buffSlots, autoBuffIds, unlockedBuffIds,
                com.pvzce.common.level.Difficulty.DEFAULT.key());
    }

    /** Applies a server snapshot including the world's difficulty tier. */
    public void apply(int coins, List<String> unlockedIds, boolean unlockAll, int seedSlots,
                      int buffSlots, List<String> autoBuffIds, List<String> unlockedBuffIds,
                      String difficulty) {
        this.difficulty = com.pvzce.common.level.Difficulty.parse(difficulty);
        this.coins = Math.max(0, coins);
        this.unlockAll = unlockAll;
        this.seedSlots = Math.max(1, seedSlots);
        this.buffSlots = Math.max(1, buffSlots);
        autoBuffs.clear();
        if (autoBuffIds != null) {
            for (String raw : autoBuffIds) {
                Identifier id = Identifier.tryParse(raw);
                if (id != null) {
                    autoBuffs.add(id);
                }
            }
        }
        unlocked.clear();
        if (unlockedIds != null) {
            for (String raw : unlockedIds) {
                Identifier id = Identifier.tryParse(raw);
                if (id != null) {
                    unlocked.add(id);
                }
            }
        }
        unlockedBuffs.clear();
        if (unlockedBuffIds != null) {
            for (String raw : unlockedBuffIds) {
                Identifier id = Identifier.tryParse(raw);
                if (id != null) {
                    unlockedBuffs.add(id);
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

    /**
     * How many level buffs this backpack may switch on.
     *
     * <p>The number the chooser sizes its buff row against when the level declares no
     * {@code max_buff_slots}; the level's own count still wins, and it arrives already resolved
     * in the payload.
     */
    public int buffSlots() {
        return buffSlots;
    }

    /** The world's auto-enabled buffs, in the order the server has them. */
    public List<Identifier> autoBuffs() {
        return List.copyOf(autoBuffs);
    }

    public List<String> autoBuffIds() {
        return autoBuffs.stream().map(Identifier::toString).toList();
    }

    /** The world's difficulty tier; the original's until the server says otherwise. */
    public com.pvzce.common.level.Difficulty difficulty() {
        return difficulty;
    }

    public boolean unlockAll() {
        return unlockAll;
    }

    /** The explicitly unlocked ids; empty for a sandbox world, which owns everything. */
    public Set<Identifier> unlocked() {
        return Set.copyOf(unlocked);
    }

    /** True when this card is available to the player; the rule is {@link SlotResolver#owns}. */
    public boolean ownsCard(Identifier card) {
        return SlotResolver.owns(unlocked, unlockAll, card);
    }

    /** The level buffs this world has been given; empty for a sandbox world that owns everything. */
    public Set<Identifier> unlockedBuffs() {
        return Set.copyOf(unlockedBuffs);
    }

    /** True when this buff is available; a sandbox world owns every buff. */
    public boolean ownsBuff(Identifier buff) {
        return unlockAll || unlockedBuffs.contains(buff);
    }
}
