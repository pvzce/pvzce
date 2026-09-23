package com.pvzce.server;

import com.pvzce.api.content.LevelBuff;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.ProjectileRef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.buff.BuiltInBuffs;
import com.pvzce.common.buff.LevelBuffs;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.NbtIo;
import com.pvzce.common.network.packet.SeedOption;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which level buffs a run starts with, and what the two built-ins actually do.
 *
 * <p>The buff twin of {@code SeedSelectionTest}, over the same three sources - the level's fixed
 * buffs, the player's picks, and a save - plus the two behaviours a buff is worth having for. The
 * behaviours are checked through {@code LevelServer} rather than against the enum, because "the
 * range really got longer" is the claim, and a multiplier nobody applied is not.
 */
class LevelBuffSelectionTest {
    private static final Identifier AUTO = PvzceIds("auto_collect");
    private static final Identifier RANGE = PvzceIds("mushroom_range");
    private static final Identifier PLAYER_CHOICE = LevelDef.LevelBuffPlan.PLAYER_CHOICE;
    private static final Identifier UNKNOWN = Identifier.withDefaultNamespace("not_a_buff");
    /** The side a human plays in every level unless it says otherwise. */
    private static final Identifier AUTO_TEAM = Identifier.withDefaultNamespace("plant_team");

    private static Identifier PvzceIds(String path) {
        return Identifier.withDefaultNamespace(path);
    }

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    /** A 9x5 level whose buff block is the two arguments; no cards, so nothing else interferes. */
    private static LevelDef level(List<Identifier> buffs, int maxBuffSlots) {
        return com.pvzce.testutil.TestLevels.copy(PLAYABLE).buffs(
                new LevelDef.LevelBuffPlan(buffs, maxBuffSlots)).build();
    }

    /** The shape every case here edits: an ordinary level with nothing pinned. */
    private static final LevelDef PLAYABLE = plainLevel();

    private static LevelDef plainLevel() {
        return com.pvzce.testutil.TestLevels.copy(new LevelDef(
                Identifier.withDefaultNamespace("buff_test"), "测试", "", 9, 5, Map.of(),
                List.of(), Identifier.withDefaultNamespace("plant_team"), Map.of(), Map.of(),
                List.of(), 1F, List.of(), Map.of(), PvzceConstants.INITIAL_SUN,
                LevelDef.LevelMusicDef.DEFAULT, List.of())).build();
    }

    private static List<String> names(List<Identifier> ids) {
        return ids.stream().map(Identifier::toString).toList();
    }

    // ------------------------------------------------------------------
    // The plan
    // ------------------------------------------------------------------

    /** A level that says nothing about buffs offers none and runs with none. */
    @Test
    void aLevelThatNeverMentionsBuffsOffersNothing() {
        LevelDef def = level(List.of(), LevelDef.LevelBuffPlan.UNSET_MAX_BUFF_SLOTS);
        assertTrue(LevelBuffSelection.chooserPool(def).isEmpty(), "no page for a level that opted out");
        assertEquals(List.of(), LevelBuffSelection.sanitize(def, 5, List.of(RANGE)));
        assertFalse(def.offersBuffChoice());
    }

    /**
     * A buff the player has not been given is listed but cannot be switched on.
     *
     * <p>The gate the whole reward chain rests on: 1-9 and 2-9 hand the two built-ins over, and
     * until then they are a padlocked entry in the pool rather than a choice.
     */
    @Test
    void aBuffThePlayerWasNotGivenIsOfferedAndRefused() {
        LevelDef def = level(List.of(PLAYER_CHOICE), 5);
        java.util.function.Predicate<Identifier> ownsNothing = buff -> false;
        java.util.function.Predicate<Identifier> ownsRange = RANGE::equals;

        List<SeedOption> pool = LevelBuffSelection.chooserPool(def, ownsNothing);
        assertEquals(names(List.of(AUTO, RANGE)), pool.stream().map(SeedOption::slotId).toList(),
                "both are still listed, so the player can see what exists");
        assertTrue(pool.stream().allMatch(option ->
                        option.costSun() == com.pvzce.common.core.SeedOptions.LOCKED_OPTION),
                "and both are marked locked");

        assertEquals(List.of(), names(LevelBuffSelection.sanitize(def, 5, List.of(AUTO, RANGE), ownsNothing)),
                "a locked buff cannot be submitted");
        assertEquals(names(List.of(RANGE)),
                names(LevelBuffSelection.sanitize(def, 5, List.of(AUTO, RANGE), ownsRange)),
                "the one that was handed over can");
        assertEquals(List.of(), names(LevelBuffSelection.defaultFor(def, 5, List.of(AUTO), ownsNothing)),
                "and the world's auto list cannot switch on what the player does not have");
        assertFalse(LevelBuffSelection.chooserPool(def, ownsNothing).stream()
                        .anyMatch(option -> option.costSun() != com.pvzce.common.core.SeedOptions.LOCKED_OPTION),
                "nothing is unlocked for a player who has been given nothing");
    }

    /** A sandbox world owns every buff, so nothing is ever padlocked there. */
    @Test
    void aSandboxOwnsEveryBuff() {
        PlayerProfile sandbox = PlayerProfile.unlockEverything();
        assertTrue(sandbox.ownsBuff(AUTO));
        assertTrue(sandbox.ownsBuff(RANGE));
        LevelDef def = level(List.of(PLAYER_CHOICE), 5);
        assertTrue(LevelBuffSelection.chooserPool(def, sandbox::ownsBuff).stream()
                .allMatch(option -> option.costSun() != com.pvzce.common.core.SeedOptions.LOCKED_OPTION));
        assertEquals(names(List.of(AUTO, RANGE)),
                names(LevelBuffSelection.sanitize(def, 5, List.of(AUTO, RANGE), sandbox::ownsBuff)));
    }

    /** A locked buff that the level itself pins is still on: the level's word wins. */
    @Test
    void aFixedBuffNeedsNoUnlock() {
        LevelDef def = level(List.of(RANGE), 3);
        assertEquals(names(List.of(RANGE)),
                names(LevelBuffSelection.sanitize(def, 3, List.of(), buff -> false)));
    }

    /** The buff unlocks survive a save/load round trip, in their own key. */
    @Test
    void buffUnlocksArePersistedApartFromCards() {
        PlayerProfile profile = PlayerProfile.load(new CompoundTag());
        assertTrue(profile.unlockBuff(RANGE));
        assertFalse(profile.unlockBuff(RANGE), "granting twice is a no-op");
        CompoundTag saved = profile.save();
        assertTrue(saved.contains("UnlockedBuffs"), "its own key, not Unlocked");

        PlayerProfile loaded = PlayerProfile.load(saved);
        assertTrue(loaded.ownsBuff(RANGE));
        assertFalse(loaded.ownsBuff(AUTO), "and only what was granted");
        assertTrue(loaded.unlockBuff(AUTO), "the set is still writable after a round trip");
        assertTrue(PlayerProfile.load(loaded.save()).ownsBuff(AUTO));
    }

    /** {@code pvzce:player_choice} is the marker that opens the page; it is not itself a buff. */
    @Test
    void theMarkerOpensThePageWithoutBecomingABuff() {
        LevelDef def = level(List.of(PLAYER_CHOICE), 3);
        assertTrue(def.offersBuffChoice());
        List<SeedOption> pool = LevelBuffSelection.chooserPool(def);
        assertEquals(names(List.of(AUTO, RANGE)), pool.stream().map(SeedOption::slotId).toList());
        assertTrue(def.buffPlan().fixedBuffs().isEmpty(), "the marker is not a fixed buff");
    }

    /** The level's own buffs are never filtered, and the player cannot drop them. */
    @Test
    void fixedBuffsAreAlwaysOn() {
        LevelDef def = level(List.of(AUTO, PLAYER_CHOICE), 4);
        assertEquals(names(List.of(AUTO, RANGE)),
                names(LevelBuffSelection.sanitize(def, 5, List.of(RANGE))));
        assertEquals(names(List.of(AUTO)),
                names(LevelBuffSelection.sanitize(def, 5, List.of())),
                "a fixed buff is on even when nothing was picked");
        assertFalse(LevelBuffSelection.chooserPool(def).stream()
                        .anyMatch(option -> option.slotId().equals(AUTO.toString())),
                "an already-on buff is not offered as a choice");
    }

    /** The cap is the resolved count, and the bound is checked before a buff is added. */
    @Test
    void theBarNeverGainsOneMoreThanItHolds() {
        LevelDef def = level(List.of(PLAYER_CHOICE), 1);
        assertEquals(names(List.of(AUTO)),
                names(LevelBuffSelection.sanitize(def, 5, List.of(AUTO, RANGE))));
    }

    /** A fixed buff the level names but nobody has ever heard of is kept, not silently lost. */
    @Test
    void anUnknownFixedBuffSurvivesSanitising() {
        LevelDef def = level(List.of(UNKNOWN), 3);
        assertEquals(names(List.of(UNKNOWN)),
                names(LevelBuffSelection.sanitize(def, 5, List.of())));
        assertFalse(LevelBuffSelection.chooserPool(def).stream()
                .anyMatch(option -> option.slotId().equals(UNKNOWN.toString())));
    }

    /** A pick naming a buff this build does not have is dropped: nothing could act on it. */
    @Test
    void anUnknownPickIsDropped() {
        LevelDef def = level(List.of(PLAYER_CHOICE), 5);
        assertEquals(names(List.of(RANGE)),
                names(LevelBuffSelection.sanitize(def, 5, List.of(UNKNOWN, RANGE))));
    }

    /** Unwritten count follows the backpack; a written one is the level's own answer. */
    @Test
    void theCountFollowsTheBackpackUnlessTheLevelSaysOtherwise() {
        LevelDef follows = level(List.of(PLAYER_CHOICE), LevelDef.LevelBuffPlan.UNSET_MAX_BUFF_SLOTS);
        assertEquals(2, follows.effectiveMaxBuffSlots(2));
        assertEquals(7, follows.effectiveMaxBuffSlots(7));

        LevelDef fixed = level(List.of(PLAYER_CHOICE), 1);
        assertEquals(1, fixed.effectiveMaxBuffSlots(9), "the level's number wins");
    }

    /** The auto list fills what fits and skips what does not; a locked buff is not duplicated. */
    @Test
    void theAutoListFillsTheRoomThatIsLeft() {
        LevelDef def = level(List.of(AUTO, PLAYER_CHOICE), 2);
        assertEquals(names(List.of(AUTO, RANGE)),
                names(LevelBuffSelection.defaultFor(def, 5, List.of(AUTO, RANGE))));
        assertEquals(names(List.of(AUTO)),
                names(LevelBuffSelection.defaultFor(def, 5, List.of(UNKNOWN))),
                "a stale preference is skipped rather than taking a slot");
    }

    /** Continuing a save plays by the rules that save was started under. */
    @Test
    void aSaveWinsOverBothTheRequestAndTheAutoList(@TempDir Path dir) throws Exception {
        CompoundTag root = new CompoundTag();
        LevelBuffSelection.writeSaved(root, List.of(RANGE));
        NbtIo.writeCompressed(root, dir.resolve("level.dat"));

        assertEquals(names(List.of(RANGE)),
                names(LevelBuffSelection.readSaved(dir)));
        LevelDef def = level(List.of(AUTO, PLAYER_CHOICE), 4);
        assertEquals(names(List.of(AUTO, RANGE)),
                names(LevelBuffSelection.planForRun(def, 5, List.of(AUTO), List.of(AUTO), dir, true)),
                "the level's own buff is still on, and the save's choice is restored");
    }

    /**
     * A save written before buffs existed says nothing, and that is not the same as "none chosen".
     *
     * <p>Conflating the two would let a world's auto list switch itself on over a player who had
     * turned it off - and would make "no buffs" impossible to express in a save at all.
     */
    @Test
    void aSaveWithNoBuffRecordIsNotAnEmptyChoice(@TempDir Path dir) throws Exception {
        NbtIo.writeCompressed(new CompoundTag(), dir.resolve("level.dat"));
        assertNull(LevelBuffSelection.readSaved(dir));

        LevelDef def = level(List.of(PLAYER_CHOICE), 4);
        assertEquals(names(List.of(AUTO)),
                names(LevelBuffSelection.planForRun(def, 4, null, List.of(AUTO), dir, true)),
                "nobody was ever asked, so the world's preference decides");

        CompoundTag chosen = new CompoundTag();
        LevelBuffSelection.writeSaved(chosen, List.of());
        NbtIo.writeCompressed(chosen, dir.resolve("level.dat"));
        assertEquals(List.of(), LevelBuffSelection.readSaved(dir),
                "an empty list is a real answer");
        assertEquals(List.of(),
                names(LevelBuffSelection.planForRun(def, 4, null, List.of(AUTO), dir, true)));
    }

    /** "The buffs I last went in with": whatever the run resolved is what is remembered. */
    @Test
    void theWorldRemembersWhatTheRunActuallyUsed() {
        LevelDef def = level(List.of(AUTO, PLAYER_CHOICE), 2);
        PlayerProfile profile = PlayerProfile.load(new CompoundTag());
        List<Identifier> resolved = LevelBuffSelection.sanitize(def, profile.buffSlots(),
                List.of(RANGE, UNKNOWN));
        LevelBuffSelection.rememberAutoBuffs(profile, resolved);
        assertEquals(names(List.of(AUTO, RANGE)), profile.autoBuffIds());
    }

    /** A run's buffs are part of the save: continuing it plays by the same rules. */
    @Test
    void theSaveCarriesTheRunsBuffs() {
        LevelDef def = level(List.of(PLAYER_CHOICE), 4);
        LevelServer started = new LevelServer(def, List.of(), LevelServer.SeedContext.all(def),
                List.of(RANGE));
        CompoundTag save = started.save();
        assertEquals(names(List.of(RANGE)), names(LevelBuffSelection.readSavedTag(save)));

        // A fresh instance built with different buffs, then restored: the file wins.
        LevelServer resumed = new LevelServer(def, List.of(), LevelServer.SeedContext.all(def),
                List.of(AUTO));
        assertEquals(names(List.of(AUTO)),
                names(LevelBuffSelection.resolveIds(resumed.activeBuffs())));
        resumed.restore(save);
        assertEquals(names(List.of(RANGE)),
                names(LevelBuffSelection.resolveIds(resumed.activeBuffs())),
                "a resumed run keeps the buffs it was started with");
    }

    // ------------------------------------------------------------------
    // The behaviours
    // ------------------------------------------------------------------

    /** Which plants the spore buff is for: mushrooms that actually fire, and nothing else. */
    @Test
    void onlySporeShootingMushroomsCount() {
        assertTrue(LevelBuffs.isSporeShooter(plant("puff_shroom")));
        assertTrue(LevelBuffs.isSporeShooter(plant("scaredy_shroom")), "it fires the same spore");
        assertFalse(LevelBuffs.isSporeShooter(plant("fume_shroom")),
                "a cloud is not a shot with a range");
        assertFalse(LevelBuffs.isSporeShooter(plant("sun_shroom")), "no shooter at all");
        assertFalse(LevelBuffs.isSporeShooter(plant("pea_shooter")), "not a mushroom");
    }

    /** The multiplier composes, and the no-buff answer is exactly 1. */
    @Test
    void multipliersComposeFromOne() {
        assertEquals(1F, LevelBuffs.sporeRangeMultiplier(List.of()));
        assertEquals(BuiltInBuffs.MUSHROOM_RANGE_FACTOR,
                LevelBuffs.sporeRangeMultiplier(List.of(BuiltInBuffs.MUSHROOM_RANGE)));
        List<LevelBuff> twice = List.of(BuiltInBuffs.MUSHROOM_RANGE, BuiltInBuffs.MUSHROOM_RANGE);
        assertEquals(BuiltInBuffs.MUSHROOM_RANGE_FACTOR * BuiltInBuffs.MUSHROOM_RANGE_FACTOR,
                LevelBuffs.sporeRangeMultiplier(twice), 0.0001F);
    }

    /** A finite range scales; the whole board does not get longer. */
    @Test
    void anUnlimitedRangeStaysUnlimited() {
        ProjectileRef shortShot = new ProjectileRef(Identifier.withDefaultNamespace("puff"), 20, 1,
                0, false, 0, 3F);
        assertEquals(4.5F, shortShot.scaledRange(1.5F).range(), 0.0001F);

        ProjectileRef wholeBoard = new ProjectileRef(Identifier.withDefaultNamespace("pea"), 20, 1);
        assertTrue(wholeBoard.hasUnlimitedRange());
        assertEquals(0F, wholeBoard.scaledRange(1.5F).range());
        assertTrue(wholeBoard.scaledRange(1.5F).covers(0F, 8F));
        assertSame(shortShot, shortShot.scaledRange(1F), "a multiplier of 1 copies nothing");
        assertSame(wholeBoard, wholeBoard.scaledRange(1.5F),
                "an unlimited range is handed back untouched, not rebuilt");
    }

    /** The buff reaches the simulation: a puff-shroom's spore flies half again as far. */
    @Test
    void theRangeBuffLengthensAPuffShroomsSpore() {
        LevelDef def = level(List.of(RANGE, PLAYER_CHOICE), 3);
        LevelServer level = new LevelServer(def, List.of(), LevelServer.SeedContext.all(def),
                List.of(RANGE));
        assertEquals(names(List.of(RANGE)),
                names(LevelBuffSelection.resolveIds(level.activeBuffs())));

        com.pvzce.server.entity.PlantEntity shroom =
                level.spawnPlant(plant("puff_shroom"), level.team(AUTO_TEAM), 1, 1);
        assertEquals(1.5F, level.sporeRangeMultiplier(shroom), 0.0001F);
    }

    /** Without the buff the same plant is untouched, and a pea shooter never scales. */
    @Test
    void theMultiplierIsOneWithoutTheBuffAndForOtherPlants() {
        LevelDef def = level(List.of(AUTO, PLAYER_CHOICE), 3);
        LevelServer level = new LevelServer(def, List.of(), LevelServer.SeedContext.all(def),
                List.of(AUTO));
        com.pvzce.server.entity.PlantEntity shroom =
                level.spawnPlant(plant("puff_shroom"), level.team(AUTO_TEAM), 1, 1);
        assertEquals(1F, level.sporeRangeMultiplier(shroom));

        LevelDef ranged = level(List.of(RANGE, PLAYER_CHOICE), 3);
        LevelServer rangedLevel = new LevelServer(ranged, List.of(), LevelServer.SeedContext.all(ranged),
                List.of(RANGE));
        com.pvzce.server.entity.PlantEntity pea =
                rangedLevel.spawnPlant(plant("pea_shooter"), rangedLevel.team(AUTO_TEAM), 1, 1);
        assertEquals(1F, rangedLevel.sporeRangeMultiplier(pea), "the buff is for mushrooms");
    }

    private static com.pvzce.api.content.PlantDef plant(String path) {
        return BuiltInRegistries.PLANTS.get(Identifier.withDefaultNamespace(path));
    }
}
