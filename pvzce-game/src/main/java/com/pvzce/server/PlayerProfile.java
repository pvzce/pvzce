package com.pvzce.server;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
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

    private final Set<Identifier> unlocked = new LinkedHashSet<>();
    /**
     * Levels bought outright with coins, kept apart from {@link #unlocked} because the
     * two answer different questions: the card set is the backpack, this is progression.
     * A purchase is permanent, which is the whole point of paying for it.
     */
    private final Set<Identifier> unlockedLevels = new LinkedHashSet<>();
    private int coins;
    private boolean unlockAll;

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

    /** Adds coins, clamped to {@link PvzceConstants#COIN_CAP}; returns the amount actually added. */
    public int grantCoins(int amount) {
        if (amount <= 0) {
            return 0;
        }
        int before = coins;
        coins = (int) Math.min(PvzceConstants.COIN_CAP, (long) coins + amount);
        return coins - before;
    }

    public void setCoins(int amount) {
        coins = Math.max(0, Math.min(PvzceConstants.COIN_CAP, amount));
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

    /** Switches the sandbox flag on; returns true when this changed the profile. */
    public boolean unlockAll() {
        if (unlockAll) {
            return false;
        }
        unlockAll = true;
        return true;
    }

    /**
     * True when the player may put this card in a bar.
     *
     * <p>Accepts both the slot id and the content it grants, because a level may
     * name a plant directly instead of its slot ({@link SlotResolver} resolves
     * both) and "I own the peashooter" must not depend on which spelling a level
     * happened to use.
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

    public CompoundTag save() {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", PvzceConstants.SAVE_DATA_VERSION);
        root.putInt("Coins", coins);
        // Stored numerically: CompoundTag has no boolean getter, and its numeric
        // getters are deliberately cross-type lenient.
        root.putByte(KEY_UNLOCK_ALL, (byte) (unlockAll ? 1 : 0));
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
        profile.setCoins(root.contains("Coins") ? root.getInt("Coins") : 0);
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
