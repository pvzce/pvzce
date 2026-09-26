package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.core.SeedOptions;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.common.nbt.NbtIo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which cards a run starts with.
 *
 * <p>One question with three sources - the level's own fixed cards, the bar the player picked, and
 * the bar a save was created with - and the rules for combining them are subtle enough that they
 * were the middle third of {@code PvzceServer.createLevel}: a picked card is ignored when the
 * level already pinned it, a level that deals its own cards (a conveyor belt) takes no selection
 * at all, and continuing a save restores the exact bar that run had.
 *
 * <p>The pool is the same one the client was shown ({@link SeedOptions#cardPool}), and the bar
 * size is the same resolved number ({@link LevelDef#effectiveMaxSeedSlots}), so "the cards this
 * accepts" cannot be a longer or shorter row than "the cards the chooser offered". That agreement
 * is the whole reason these are one class rather than four helpers.
 */
public final class SeedSelection {
    private static final Logger LOGGER = LoggerFactory.getLogger("PVZCE/Seeds");

    private SeedSelection() {
    }

    /**
     * The bar this run starts with.
     *
     * <p>Order matters and is the order of the decision: a save being continued wins over the
     * request (the request was made before anything knew a save existed), a request wins over the
     * default, and a level that deals its own cards gets nothing because its card source fills
     * the bar itself.
     *
     * @param requested  the client's card bar, or null to use the level's own cards
     * @param saveDir    where the run save lives, for the "carry the saved bar over" case
     * @param loadSave   true when {@code saveDir} holds a run that is being resumed
     * @param selfDealt  true when the level's card source deals its own cards
     */
    public static List<Identifier> plan(LevelDef def, PlayerProfile profile, List<Identifier> requested,
                                        Path saveDir, boolean loadSave, boolean selfDealt) {
        if (selfDealt) {
            return List.of();
        }
        // A level that never shows the card screen has no way for a choice to be expressed, so an
        // empty request from it means "nobody was asked" rather than "I picked nothing" - and the
        // bar is then the same one the chooser's own default would have produced. Without this the
        // vase level, which enters straight into the run, started with a bar holding nothing but
        // its own fixed cards while the sun it hands out went uncollectable.
        if (!def.seedScreen() && (requested == null || requested.isEmpty())) {
            requested = null;
        }
        List<Identifier> seeds = requested == null ? null : sanitize(def, requested, profile);
        if (loadSave) {
            // Continuing a save restores the exact card bar the player had.
            List<Identifier> savedSeeds = readSaved(saveDir);
            if (savedSeeds != null) {
                seeds = sanitize(def, savedSeeds, profile);
            }
        }
        return seeds == null ? defaultFor(def, profile) : seeds;
    }

    /**
     * Keeps the cards a request may keep: the level's fixed cards first, then as many picked cards
     * as fit, in the order they were picked.
     *
     * <p>Fixed cards are never filtered - a level may hand over a card the player has not unlocked,
     * which is exactly how the first level works before sunflower exists - while a pick naming a
     * card the player does not own is dropped, because the chooser never offered it.
     */
    public static List<Identifier> sanitize(LevelDef def, List<Identifier> requested,
                                            PlayerProfile profile) {
        List<Identifier> pool = SeedOptions.cardPool(def, profile == null ? null : profile::ownsCard);
        // The same resolved bar size the client was shown, so "the cards this method accepts"
        // cannot be a longer or shorter row than "the cards the chooser offered".
        LevelDef.SeedPlan plan = def.seedPlan(pool, effectiveSlots(def, profile));
        List<Identifier> result = new ArrayList<>();
        Set<Identifier> seen = new HashSet<>();
        for (Identifier locked : plan.lockedSlots()) {
            if (seen.add(locked)) {
                result.add(locked);
            }
        }
        Set<Identifier> pickable = new HashSet<>(plan.pickableSlots());
        for (Identifier seed : requested) {
            // The bound is checked before adding, not after: a level whose own cards already fill
            // the bar has no room to pick, and the old shape appended the first pick and only then
            // noticed the bar was full - one card past the count the chooser drew against.
            if (result.size() >= plan.maxSlots()) {
                break;
            }
            if (seed == null || !pickable.contains(seed) || !seen.add(seed)) {
                continue;
            }
            result.add(seed);
        }
        return List.copyOf(result);
    }

    /**
     * The bar a level starts with when nobody made a choice - the "next level" button rather than
     * the seed chooser.
     *
     * <p>Fills the free slots from the backpack, so a level that pins nothing hands the player
     * everything they have unlocked instead of an empty bar. A level whose own cards already fill
     * the bar is unaffected.
     */
    public static List<Identifier> defaultFor(LevelDef def, PlayerProfile profile) {
        return def.defaultSeedSelection(SeedOptions.cardPool(def, profile == null ? null : profile::ownsCard),
                effectiveSlots(def, profile));
    }

    /**
     * The bar size a level gets for this player: its own {@code max_seed_slots} when it declares
     * one, otherwise the backpack's.
     *
     * <p>One helper because both entry points above and the level's own {@code SeedContext} have to
     * agree - a selection sanitised against a different length than the chooser drew is exactly the
     * bug this rule was written to avoid.
     */
    public static int effectiveSlots(LevelDef def, PlayerProfile profile) {
        return def.effectiveMaxSeedSlots(profile == null
                ? PvzceConstants.DEFAULT_SEED_SLOTS : profile.seedSlots());
    }

    /** The card bar a running save was created with, or {@code null} when there is none. */
    public static List<Identifier> readSaved(Path saveDir) {
        Path saveFile = saveDir.resolve("level.dat");
        if (!Files.isRegularFile(saveFile)) {
            return null;
        }
        try {
            ListTag slots = NbtIo.readCompressed(saveFile).getList("Slots");
            List<Identifier> seeds = new ArrayList<>();
            for (int i = 0; i < slots.size(); i++) {
                Identifier seed = Identifier.tryParse(slots.getCompound(i).getString("def"));
                if (seed != null) {
                    seeds.add(seed);
                }
            }
            return List.copyOf(seeds);
        } catch (Throwable t) {
            LOGGER.warn("Failed to read the saved seed selection from " + saveFile, t);
            return null;
        }
    }
}
