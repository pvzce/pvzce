package com.pvzce.server;

import com.pvzce.api.content.LevelBuff;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.buff.LevelBuffs;
import com.pvzce.common.core.SeedOptions;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.common.nbt.NbtIo;
import com.pvzce.common.nbt.StringTag;
import com.pvzce.common.nbt.Tag;
import com.pvzce.common.network.packet.SeedOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which level buffs a run starts with.
 *
 * <p>The buff twin of {@link SeedSelection}, and the same shape: the level's own buffs, the
 * buffs the player asked for, and the buffs a save was created with, combined by one rule that
 * both the chooser and the server read.
 *
 * <p>Three facts make the rule what it is:
 *
 * <ul>
 *   <li>A level's own buffs are never filtered and never dropped. Like the level's fixed cards,
 *       they may name something the player's backpack has never heard of - that is the point of
 *       a level being able to pin one.</li>
 *   <li>The cap is the <em>resolved</em> count ({@link LevelDef#effectiveMaxBuffSlots}), the same
 *       number the payload carried to the client, so "the buffs this accepts" cannot be a longer
 *       or shorter row than "the buffs the chooser offered".</li>
 *   <li>Order is the decision. Locked first, then the request in the order it was made, and the
 *       bound is checked <em>before</em> a buff is added - a full bar must not gain one more.</li>
 * </ul>
 */
public final class LevelBuffSelection {
    private static final Logger LOGGER = LoggerFactory.getLogger("PVZCE/Buffs");

    private LevelBuffSelection() {
    }

    /**
     * The buffs a run starts with.
     *
     * <p>Order of the decision mirrors {@link SeedSelection#plan}: continuing a save restores
     * exactly what that run had, a request beats the world's auto list, and a request of
     * {@code null} means "nobody chose" - which is the "next level" button rather than the
     * chooser.
     *
     * @param requested the client's buff list, or null when nobody chose
     * @param autoBuffs the world's own auto list, used only when nothing was requested
     */
    public static List<Identifier> plan(LevelDef def, int profileBuffSlots, List<Identifier> requested,
                                        List<Identifier> autoBuffs) {
        return plan(def, profileBuffSlots, requested, autoBuffs, null);
    }

    /** As above, with the backpack the picks are checked against. */
    public static List<Identifier> plan(LevelDef def, int profileBuffSlots, List<Identifier> requested,
                                        List<Identifier> autoBuffs,
                                        java.util.function.Predicate<Identifier> owns) {
        return requested == null
                ? defaultFor(def, profileBuffSlots, autoBuffs, owns)
                : sanitize(def, profileBuffSlots, requested, owns);
    }

    /**
     * The buff list a level instance should be built with, given all three inputs.
     *
     * <p>The one caller is {@code PvzceServer.createLevel}, and the point of having it here is
     * that "continue a save" is a special case rather than a branch at the call site: when a run
     * is being resumed, the save's own list is the answer - <em>including</em> an empty one, and
     * including {@code null}, which means the save predates buffs and nobody was ever asked. In
     * that case the caller's list is not consulted at all, because the request was made before
     * anything knew the save existed.
     *
     * @param requested    what the client asked for, or {@code null} when nobody chose
     * @param loadSave     true when a saved run is about to be restored over this list
     */
    public static List<Identifier> planForRun(LevelDef def, int profileBuffSlots,
                                              List<Identifier> requested, List<Identifier> autoBuffs,
                                              Path saveDir, boolean loadSave) {
        return planForRun(def, profileBuffSlots, requested, autoBuffs, saveDir, loadSave, null);
    }

    /**
     * As above, with the backpack. The gate applies to a restored list too: a buff this player
     * has not been given is not one a save may keep switched on.
     */
    public static List<Identifier> planForRun(LevelDef def, int profileBuffSlots,
                                              List<Identifier> requested, List<Identifier> autoBuffs,
                                              Path saveDir, boolean loadSave,
                                              java.util.function.Predicate<Identifier> owns) {
        if (loadSave) {
            List<Identifier> saved = readSaved(saveDir);
            if (saved != null) {
                return sanitize(def, profileBuffSlots, saved, owns);
            }
            // The save predates buffs: nobody ever chose, so the world's preference decides.
            return defaultFor(def, profileBuffSlots, autoBuffs, owns);
        }
        return plan(def, profileBuffSlots, requested, autoBuffs, owns);
    }

    /**
     * Keeps the buffs a request may keep: the level's own first, then as many picks as fit, in
     * the order they were requested.
     *
     * <p>A pick naming a buff this build does not have is dropped rather than kept as a dead id:
     * nothing could ever act on it, and a bar entry that does nothing is worse than no entry.
     */
    public static List<Identifier> sanitize(LevelDef def, int profileBuffSlots,
                                            List<Identifier> requested) {
        return sanitize(def, profileBuffSlots, requested, null);
    }

    /** As above, with the backpack: a buff this player has not been given is not pickable. */
    public static List<Identifier> sanitize(LevelDef def, int profileBuffSlots,
                                            List<Identifier> requested,
                                            java.util.function.Predicate<Identifier> owns) {
        // The chooser pool is the wire shape; the picks are ids, so the comparison is by id.
        // A locked entry is listed but not pickable - that is the whole difference between the
        // pool the server sends and the set this accepts, and it is why the marker has to be read
        // here rather than only drawn by the client.
        Set<Identifier> pickable = new HashSet<>();
        for (SeedOption option : chooserPool(def, owns)) {
            if (option.costSun() == SeedOptions.LOCKED_OPTION) {
                continue;
            }
            Identifier id = Identifier.tryParse(option.slotId());
            if (id != null) {
                pickable.add(id);
            }
        }
        int maxSlots = def.effectiveMaxBuffSlots(profileBuffSlots);
        List<Identifier> result = new ArrayList<>();
        Set<Identifier> seen = new HashSet<>();
        for (Identifier locked : def.buffPlan().fixedBuffs()) {
            // A locked buff is kept even when it is not registered: the level asked for it, and
            // LevelValidator has already reported the typo.
            if (result.size() >= maxSlots) {
                break;
            }
            if (seen.add(locked)) {
                result.add(locked);
            }
        }
        for (Identifier buff : requested == null ? List.<Identifier>of() : requested) {
            if (result.size() >= maxSlots) {
                break;
            }
            if (buff == null || !pickable.contains(buff) || !seen.add(buff)) {
                continue;
            }
            result.add(buff);
        }
        return List.copyOf(result);
    }

    /**
     * The buffs a run starts with when nobody made a choice: the level's own, then the world's
     * auto list as far as it fits.
     *
     * <p>The auto list is a preference, not a promise - a buff the level does not offer is
     * skipped, and when the bar fills up the rest of the list is simply not reached. A level that
     * locks the same buff already has it, because locked buffs are added first.
     */
    public static List<Identifier> defaultFor(LevelDef def, int profileBuffSlots,
                                              List<Identifier> autoBuffs) {
        return defaultFor(def, profileBuffSlots, autoBuffs, null);
    }

    /** As above, with the backpack. */
    public static List<Identifier> defaultFor(LevelDef def, int profileBuffSlots,
                                              List<Identifier> autoBuffs,
                                              java.util.function.Predicate<Identifier> owns) {
        List<Identifier> picks = new ArrayList<>();
        for (Identifier buff : autoBuffs == null ? List.<Identifier>of() : autoBuffs) {
            if (buff != null && !picks.contains(buff)) {
                picks.add(buff);
            }
        }
        return sanitize(def, profileBuffSlots, picks, owns);
    }

    /**
     * The buffs the chooser may offer this level's player: every registered buff that this level
     * is willing to offer.
     *
     * <p>Empty for a level that does not list {@code pvzce:player_choice} - a level that never
     * heard of buffs runs without a buff page, exactly as it did before this system existed. The
     * level's own fixed buffs are <em>not</em> in here: they are already on, and a chooser that
     * offered them as choices would be offering a switch the player does not have.
     */
    public static List<SeedOption> chooserPool(LevelDef def) {
        return chooserPool(def, null);
    }

    /**
     * The chooser pool, marked with what this player may actually switch on.
     *
     * <p>A buff the player has not been given yet is still <em>listed</em> - a padlocked card is
     * how the game already says "there is something here you have not earned" - but it carries
     * {@link SeedOptions#LOCKED_OPTION} in its price field, and {@link #sanitize} will not accept
     * it. That is the whole gate: a buff arrives as a reward from a level (see
     * {@code LevelRewards.Reward#buff}), and until then it is a thing the player can see and not
     * use.
     *
     * <p>{@code owns} is the player's backpack: {@code null} means "everything is owned", which is
     * what the tests, the editor and a sandbox world want. The level's own fixed buffs are never in
     * here - they are already on, and a chooser that offered them as choices would be offering a
     * switch the player does not have.
     */
    public static List<SeedOption> chooserPool(LevelDef def, java.util.function.Predicate<Identifier> owns) {
        if (!def.offersBuffChoice()) {
            return List.of();
        }
        List<SeedOption> pool = new ArrayList<>();
        for (Identifier buff : com.pvzce.common.core.BuiltInRegistries.LEVEL_BUFFS.keySet()) {
            if (!def.buffPlan().fixedBuffs().contains(buff)) {
                pool.add(SeedOptions.buffOption(buff, owns == null || owns.test(buff)));
            }
        }
        return List.copyOf(pool);
    }

    /** The registered buffs an id list names, in order; unknown ids are dropped. */
    public static List<LevelBuff> resolve(List<Identifier> ids) {
        List<LevelBuff> resolved = new ArrayList<>();
        for (Identifier id : ids == null ? List.<Identifier>of() : ids) {
            LevelBuff buff = LevelBuffs.get(id);
            if (buff != null) {
                resolved.add(buff);
            }
        }
        return List.copyOf(resolved);
    }

    /**
     * The ids of resolved buffs, in order - the inverse of {@link #resolve}.
     *
     * <p>Used when a level writes its own state: the level holds {@link LevelBuff}s and the file
     * holds ids, and the conversion belongs next to the other half of it rather than in
     * {@code LevelServer}.
     */
    public static List<Identifier> resolveIds(List<LevelBuff> buffs) {
        List<Identifier> ids = new ArrayList<>();
        for (LevelBuff buff : buffs == null ? List.<LevelBuff>of() : buffs) {
            Identifier id = LevelBuffs.idOf(buff);
            if (id != null) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    /**
     * Replaces the world's auto list with what the player actually went in with.
     *
     * <p>The list is written from the run's <em>resolved</em> buffs rather than from the request,
     * so a buff the level refused never becomes a preference, and a level that pins one adds it
     * to the list for the levels that do not.
     */
    public static void rememberAutoBuffs(PlayerProfile profile, List<Identifier> resolved) {
        if (profile == null) {
            return;
        }
        profile.setAutoBuffs(resolved);
    }

    /** The buffs a running save was created with, or {@code null} when there is none. */
    public static List<Identifier> readSaved(Path saveDir) {
        Path saveFile = saveDir.resolve("level.dat");
        if (!Files.isRegularFile(saveFile)) {
            return null;
        }
        try {
            return readSavedTag(NbtIo.readCompressed(saveFile));
        } catch (Throwable t) {
            LOGGER.warn("Failed to read the saved buff selection from " + saveFile, t);
            return null;
        }
    }

    /**
     * The buff ids in a save tag, or {@code null} when the tag has no buff list at all.
     *
     * <p>The {@code null} is the whole point and must not be collapsed into an empty list: a save
     * written before buffs existed has no such key, and that means "nobody was ever asked", which
     * is a different answer from "this run deliberately has none". Conflating them would let the
     * world's auto list come on over a player who had turned it off.
     */
    public static List<Identifier> readSavedTag(CompoundTag root) {
        if (root == null || !root.contains("Buffs")) {
            return null;
        }
        ListTag buffs = root.getList("Buffs");
        List<Identifier> ids = new ArrayList<>();
        for (int i = 0; i < buffs.size(); i++) {
            CompoundTag entry = buffs.getCompound(i);
            Identifier id = entry == null ? null : Identifier.tryParse(entry.getString("buff"));
            if (id != null) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    /** Writes {@code buffs} into a level save's root tag, in the shape {@link #readSaved} reads. */
    public static void writeSaved(CompoundTag root, List<Identifier> buffs) {
        ListTag list = new ListTag();
        for (Identifier buff : buffs == null ? List.<Identifier>of() : buffs) {
            CompoundTag entry = new CompoundTag();
            entry.putString("buff", buff.toString());
            list.add(entry);
        }
        root.put("Buffs", list);
    }
}
