package com.pvzce.server;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.Slot;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.common.nbt.StringTag;
import com.pvzce.common.nbt.Tag;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A world's persistent player record: the coin wallet and the unlocked cards.
 *
 * <p>Everything that used to be "the player" was per level: {@link Team} holds
 * the resources of one run and {@link Slot} the card bar of one run, and both
 * die with the level. This class is the only thing that outlives a run, stored as
 * {@code saves/<world>/profile.dat}.
 *
 * <p>Unlocks cover <em>plant and tool cards</em>; resource cards (sun) are never
 * gated, because a level without the sun card could not be played at all. The
 * distinction lives in {@link SlotResolver#requiresUnlock}, which is the single
 * answer to "does the backpack have anything to say about this card".
 *
 * <p>{@link #unlockAll} is a flag rather than a materialised set so a sandbox
 * world also gains cards added by later content; the starter profile is
 * {@link #starter()}.
 */
public final class PlayerProfile {
    /** NBT key holding the unlock-everything flag; not a card set. */
    private static final String KEY_UNLOCK_ALL = "UnlockAll";
    /** NBT key holding the world's difficulty tier. */
    private static final String KEY_DIFFICULTY = "Difficulty";

    private final Set<Identifier> unlocked = new LinkedHashSet<>();
    /**
     * Levels bought outright with coins, kept apart from {@link #unlocked} because the
     * two answer different questions: the card set is the backpack, this is progression.
     * A purchase is permanent, which is the whole point of paying for it.
     */
    private final Set<Identifier> unlockedLevels = new LinkedHashSet<>();
    /**
     * The level buffs this backpack has been given, by the levels that hand them out.
     *
     * <p>A third set rather than a corner of {@link #unlocked}: a card id and a buff id can look
     * alike ({@code pvzce:sunflower} against {@code pvzce:auto_collect}), and a level that meant to
     * hand over a plant must not quietly hand over a rule instead. Nothing here is about
     * progression through the level list - it is "which rules may I switch on".
     */
    private final Set<Identifier> unlockedBuffs = new LinkedHashSet<>();
    private int coins;
    /**
     * How many cards the player's bar holds when a level does not say.
     *
     * <p>Shop upgrades raise this later; {@link #addSeedSlots} is that door, and it is
     * deliberately the only way to change the number so a future purchase, command or
     * reward goes through one clamp.
     */
    private int seedSlots = PvzceConstants.DEFAULT_SEED_SLOTS;
    /**
     * How many level buffs this backpack may switch on, when a level does not say.
     *
     * <p>Separate from {@link #seedSlots} because the two are separate choices: a level may hand
     * out eight cards and no buffs, or two cards and five buffs.
     */
    private int buffSlots = PvzceConstants.DEFAULT_BUFF_SLOTS;
    /**
     * The buffs this world switches on by itself, in the order the player last chose them.
     *
     * <p>One list per world rather than one per level - see {@link #autoBuffs()}. Stored as
     * ids, not as a bitmask over the registry: a mod's buff that is not loaded today must
     * survive a save/load round trip.
     */
    private final List<Identifier> autoBuffs = new java.util.ArrayList<>();
    private boolean unlockAll;
    /**
     * How hard this world plays, from {@code common.level.Difficulty}.
     *
     * <p>A fact about the world rather than about the client, so it is stored here with the wallet
     * and the backpack: a save that moved to another machine must play the way its owner left it,
     * and a modified client must not be able to hand itself an easier game.
     */
    private com.pvzce.common.level.Difficulty difficulty =
            com.pvzce.common.level.Difficulty.DEFAULT;

    private PlayerProfile() {
    }

    /**
     * What a brand-new world starts with: one plant and one tool.
     *
     * <p>The first level only ever offers {@code pvzce:pea_shooter}, and the
     * shovel exists so a misplaced plant can be dug up again; everything else is
     * earned. See {@code PvzceIds.STARTER_PLANT} / {@code STARTER_TOOL}.
     */
    public static PlayerProfile starter() {
        PlayerProfile profile = new PlayerProfile();
        profile.unlocked.add(PvzceIds.STARTER_PLANT);
        profile.unlocked.add(PvzceIds.STARTER_TOOL);
        return profile;
    }

    /** A sandbox profile: every card, present and future. */
    public static PlayerProfile unlockEverything() {
        PlayerProfile profile = new PlayerProfile();
        profile.unlockAll = true;
        return profile;
    }

    public int coins() {
        return coins;
    }

    /**
     * Adds coins; returns the amount actually added.
     *
     * <p>There is no wallet cap. The field is the ceiling, and only so that a long-lived
     * world cannot silently wrap around into a negative balance: what the player collected
     * is always what the player keeps.
     */
    public int grantCoins(int amount) {
        if (amount <= 0) {
            return 0;
        }
        int before = coins;
        coins = (int) Math.min(PvzceConstants.COIN_LIMIT, (long) coins + amount);
        return coins - before;
    }

    public void setCoins(int amount) {
        coins = Math.max(0, Math.min(PvzceConstants.COIN_LIMIT, amount));
    }

    /** How many cards this backpack gives a level that declares no slot count. */
    public int seedSlots() {
        return seedSlots;
    }

    public void setSeedSlots(int slots) {
        seedSlots = Math.max(1, Math.min(PvzceConstants.MAX_SEED_SLOTS, slots));
    }

    /**
     * The upgrade hook: buys (or grants) {@code extra} more card slots.
     *
     * <p>Returns the number actually granted, so a caller can charge for what it got rather
     * than for what it asked for. Nothing calls this yet - the shop that would is not
     * built - but the clamp, the field and the wire field all exist, which is the whole of
     * "leave the interface ready".
     */
    public int addSeedSlots(int extra) {
        if (extra <= 0) {
            return 0;
        }
        int before = seedSlots;
        setSeedSlots(seedSlots + extra);
        return seedSlots - before;
    }

    /** How many level buffs this backpack may switch on when a level declares no count. */
    public int buffSlots() {
        return buffSlots;
    }

    public void setBuffSlots(int slots) {
        buffSlots = Math.max(1, Math.min(PvzceConstants.MAX_BUFF_SLOTS, slots));
    }

    /** The buff twin of {@link #addSeedSlots}; nothing calls it yet either. */
    public int addBuffSlots(int extra) {
        if (extra <= 0) {
            return 0;
        }
        int before = buffSlots;
        setBuffSlots(buffSlots + extra);
        return buffSlots - before;
    }

    /**
     * The buffs this world switches on by itself, in the order they were last chosen.
     *
     * <p>World-scoped and deliberately not per level: a player who wants automatic pickup wants
     * it everywhere, and re-ticking the same box on twenty levels is not a choice, it is
     * paperwork. The list is rewritten to whatever the last run actually started with (see
     * {@code PvzceServer.createLevel}), so it always means "the buffs I last went in with".
     */
    public List<Identifier> autoBuffs() {
        return List.copyOf(autoBuffs);
    }

    public List<String> autoBuffIds() {
        return autoBuffs.stream().map(Identifier::toString).toList();
    }

    /**
     * Replaces the auto list, dropping unparsable entries and keeping the order given.
     *
     * <p>An empty list is a legal value - "stop picking anything for me" - and is not the same
     * as "no preference". Nothing here filters by what is registered: a buff may be added by a
     * mod that is not loaded today, and forgetting it because it could not be resolved would
     * make the setting depend on the mod list.
     */
    public void setAutoBuffs(List<Identifier> buffs) {
        autoBuffs.clear();
        if (buffs != null) {
            for (Identifier buff : buffs) {
                if (buff != null) {
                    autoBuffs.add(buff);
                }
            }
        }
    }

    /** True when every card is unlocked by the sandbox flag rather than by name. */
    public boolean unlocksEverything() {
        return unlockAll;
    }

    /** The explicitly unlocked card ids; empty when {@link #unlocksEverything()}. */
    public Set<Identifier> unlocked() {
        return Collections.unmodifiableSet(unlocked);
    }

    public List<String> unlockedIds() {
        return unlocked.stream().map(Identifier::toString).toList();
    }

    /** Unlocks one card; returns true when this changed the profile. */
    public boolean unlock(Identifier card) {
        return card != null && unlocked.add(card);
    }

    /** The levels bought with coins in this world. */
    public Set<Identifier> unlockedLevels() {
        return Collections.unmodifiableSet(unlockedLevels);
    }

    public List<String> unlockedLevelIds() {
        return unlockedLevels.stream().map(Identifier::toString).toList();
    }

    /** True when this level was bought, so its requirements no longer matter. */
    public boolean ownsLevel(Identifier levelId) {
        return levelId != null && unlockedLevels.contains(levelId);
    }

    /** Records a level purchase; returns true when this changed the profile. */
    public boolean unlockLevel(Identifier levelId) {
        return levelId != null && unlockedLevels.add(levelId);
    }

    /** How hard this world plays. Never null; the original's own difficulty by default. */
    public com.pvzce.common.level.Difficulty difficulty() {
        return difficulty;
    }

    /** Sets the tier; returns true when this changed the profile. */
    public boolean setDifficulty(com.pvzce.common.level.Difficulty tier) {
        if (tier == null || tier == difficulty) {
            return false;
        }
        difficulty = tier;
        return true;
    }

    /** Switches the sandbox flag on; returns true when this changed the profile. */
    public boolean unlockAll() {
        if (unlockAll) {
            return false;
        }
        unlockAll = true;
        return true;
    }

    /** True when the player may put this card in a bar; the rule is {@link SlotResolver#owns}. */
    public boolean ownsCard(Identifier card) {
        return SlotResolver.owns(unlocked, unlockAll, card);
    }

    /**
     * True when this world owns the rake, so every level lays one down for it.
     *
     * <p>The rake is a shop purchase rather than a card, so it is asked about by name - but it is
     * still something a world <em>owns</em>, and the sandbox flag means "owns everything". Asking
     * the raw set instead made a sandbox world the one world where a rake could never be seen.
     */
    public boolean ownsRake() {
        return unlockAll || unlocked.contains(com.pvzce.common.PvzceIds.RAKE);
    }

    /** The buffs levels have handed this player. */
    public Set<Identifier> unlockedBuffs() {
        return Collections.unmodifiableSet(unlockedBuffs);
    }

    public List<String> unlockedBuffIds() {
        return unlockedBuffs.stream().map(Identifier::toString).toList();
    }

    /**
     * True when the player may switch this buff on.
     *
     * <p>A sandbox world owns every buff, present and future, exactly as it owns every card - the
     * same flag answers both. A buff nobody hands out (a mod's, before that mod's level is
     * cleared) is simply not owned, which is the point of the gate.
     */
    public boolean ownsBuff(Identifier buff) {
        return buff != null && (unlockAll || unlockedBuffs.contains(buff));
    }

    /**
     * Records a granted buff; returns true when this changed the profile.
     *
     * <p>Idempotent, which is what lets a reward be paid on any clear rather than only on the
     * first one (see {@code RewardSettlement}).
     */
    public boolean unlockBuff(Identifier buff) {
        return buff != null && unlockedBuffs.add(buff);
    }

    public CompoundTag save() {
        CompoundTag root = new CompoundTag();
        root.putInt("Coins", coins);
        root.putInt("SeedSlots", seedSlots);
        root.putInt("BuffSlots", buffSlots);
        // Stored numerically: CompoundTag has no boolean getter, and its numeric
        // getters are deliberately cross-type lenient.
        root.putByte(KEY_UNLOCK_ALL, (byte) (unlockAll ? 1 : 0));
        root.putString(KEY_DIFFICULTY, difficulty.key());
        ListTag list = new ListTag();
        for (Identifier id : unlocked) {
            list.add(new StringTag(id.toString()));
        }
        root.put("Unlocked", list);
        // A separate key rather than reusing Unlocked: a card id and a level id can look
        // alike, and mixing them would let a level purchase grant a card.
        ListTag levels = new ListTag();
        for (Identifier id : unlockedLevels) {
            levels.add(new StringTag(id.toString()));
        }
        root.put("UnlockedLevels", levels);
        // Its own key again: the three sets are three different bags, and reading a buff as a
        // card (or the other way round) is the failure this separation exists to prevent.
        ListTag buffs = new ListTag();
        for (Identifier id : unlockedBuffs) {
            buffs.add(new StringTag(id.toString()));
        }
        root.put("UnlockedBuffs", buffs);
        // Its own key, and a list of ids rather than a count: see ``autoBuffs``.
        ListTag auto = new ListTag();
        for (Identifier id : autoBuffs) {
            auto.add(new StringTag(id.toString()));
        }
        root.put("AutoBuffs", auto);
        return root;
    }

    /**
     * Reads a profile, falling back to {@link #starter()} for a missing or
     * unreadable record - an old world must keep working, and "no file" has to
     * mean the same thing as "the file says nothing".
     */
    public static PlayerProfile load(CompoundTag root) {
        PlayerProfile profile = new PlayerProfile();
        if (root == null) {
            return starter();
        }
        profile.unlockAll = root.getInt(KEY_UNLOCK_ALL) != 0;
        // A record written before the tiers existed has no key, and "no key" means the original:
        // a world that predates the setting must not come back on a harder tier than it was played.
        profile.difficulty = root.contains(KEY_DIFFICULTY)
                ? com.pvzce.common.level.Difficulty.parse(root.getString(KEY_DIFFICULTY))
                : com.pvzce.common.level.Difficulty.DEFAULT;
        profile.setCoins(root.contains("Coins") ? root.getInt("Coins") : 0);
        // A record written before card slots existed has no key, and "no key" has to mean
        // the default: the field is what an ordinary level reads to size the player's bar.
        profile.setSeedSlots(root.contains("SeedSlots")
                ? root.getInt("SeedSlots") : PvzceConstants.DEFAULT_SEED_SLOTS);
        // Same rule for buff slots: a record written before buffs existed means the default,
        // not zero, because zero would hand every old world a buff bar with no room in it.
        profile.setBuffSlots(root.contains("BuffSlots")
                ? root.getInt("BuffSlots") : PvzceConstants.DEFAULT_BUFF_SLOTS);
        for (Tag entry : root.getList("UnlockedBuffs").values()) {
            if (entry instanceof StringTag text) {
                Identifier id = Identifier.tryParse(text.value());
                if (id != null) {
                    profile.unlockedBuffs.add(id);
                }
            }
        }
        for (Tag entry : root.getList("AutoBuffs").values()) {
            if (entry instanceof StringTag text) {
                Identifier id = Identifier.tryParse(text.value());
                if (id != null) {
                    profile.autoBuffs.add(id);
                }
            }
        }
        for (Tag entry : root.getList("Unlocked").values()) {
            if (entry instanceof StringTag text) {
                Identifier id = Identifier.tryParse(text.value());
                if (id != null) {
                    profile.unlocked.add(id);
                }
            }
        }
        for (Tag entry : root.getList("UnlockedLevels").values()) {
            if (entry instanceof StringTag text) {
                Identifier id = Identifier.tryParse(text.value());
                if (id != null) {
                    profile.unlockedLevels.add(id);
                }
            }
        }
        if (!profile.unlockAll && profile.unlocked.isEmpty()) {
            // A record that exists but names no card is treated as a fresh
            // profile rather than as "nothing is unlocked": otherwise a world
            // whose profile.dat got truncated becomes unplayable.
            return starter();
        }
        return profile;
    }
}
