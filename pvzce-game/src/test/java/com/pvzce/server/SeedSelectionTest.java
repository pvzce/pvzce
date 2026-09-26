package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.common.nbt.NbtIo;
import com.pvzce.common.nbt.StringTag;
import com.pvzce.common.tag.TestContent;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which cards a run starts with.
 *
 * <p>Three sources answer one question - the level's fixed cards, the bar the player picked, and
 * the bar a save was created with - and the rules for combining them used to be the middle third of
 * {@code PvzceServer.createLevel}, reachable only by starting a level on a live server. These are
 * the rules themselves: fixed cards are never filtered, a pick the player does not own is dropped,
 * a level that deals its own cards gets nothing, and a continued save wins over the request.
 */
class SeedSelectionTest {
    private static final Identifier PEA = Identifier.withDefaultNamespace("pea_shooter");
    private static final Identifier SUNFLOWER = Identifier.withDefaultNamespace("sunflower");
    private static final Identifier SUN = Identifier.withDefaultNamespace("sun");
    private static final Identifier SHOVEL = Identifier.withDefaultNamespace("shovel");

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static LevelDef level(List<Identifier> fixedCards, int maxSlots) {
        return new LevelDef(Identifier.withDefaultNamespace("seed_selection_test"), "测试", "",
                9, 5, Map.of(), List.of(), Identifier.withDefaultNamespace("plant_team"),
                Map.of(), Map.of(), List.of(), 1F, fixedCards,
                Map.of(), PvzceConstants.INITIAL_SUN, LevelDef.LevelMusicDef.DEFAULT, List.of(), maxSlots);
    }

    /** A backpack that owns exactly these cards; {@code starter()} owns two, so it is rebuilt. */
    private static PlayerProfile profileOwning(Identifier... cards) {
        PlayerProfile profile = PlayerProfile.load(new CompoundTag());
        for (Identifier card : cards) {
            profile.unlock(card);
        }
        return profile;
    }

    private static List<String> names(List<Identifier> ids) {
        return ids.stream().map(Identifier::toString).toList();
    }

    /** The level's own cards come first and are never dropped, even when the player owns none. */
    @Test
    void fixedCardsSurviveAPickThatDoesNotNameThem() {
        LevelDef def = level(List.of(PEA, SUN), 3);
        List<Identifier> bar = SeedSelection.sanitize(def, List.of(SHOVEL), profileOwning(SHOVEL));
        assertEquals(List.of(PEA, SUN, SHOVEL), bar);
    }

    /** A pick the player does not own is not in the pool the chooser drew, so it is dropped. */
    @Test
    void aPickThePlayerDoesNotOwnIsDropped() {
        LevelDef def = level(List.of(PEA, SUN), 3);
        List<Identifier> bar = SeedSelection.sanitize(def, List.of(SUNFLOWER), profileOwning());
        assertEquals(List.of(PEA, SUN), bar, "the level's own cards are the whole bar");
    }

    @Test
    void aPickThatNamesAFixedCardIsNotAddedTwice() {
        LevelDef def = level(List.of(PEA, SUN), 4);
        List<Identifier> bar = SeedSelection.sanitize(def, List.of(PEA, PEA, SHOVEL), profileOwning(SHOVEL));
        assertEquals(List.of(PEA, SUN, SHOVEL), bar);
    }

    @Test
    void theBarStopsAtTheResolvedSlotCount() {
        LevelDef def = level(List.of(PEA), 2);
        List<Identifier> bar = SeedSelection.sanitize(def,
                List.of(SHOVEL, SUNFLOWER, SUN), profileOwning(SHOVEL, SUNFLOWER, SUN));
        assertEquals(2, bar.size(), "one fixed card plus one pick fills a two-slot bar");
        assertEquals(PEA, bar.get(0));
    }

    /** A level whose own cards fill the bar leaves no room to pick, and that is not an error. */
    @Test
    void aFullLevelDeckIsHandedOverWhole() {
        LevelDef def = level(List.of(PEA, SUN), 2);
        assertEquals(List.of(PEA, SUN),
                SeedSelection.sanitize(def, List.of(SHOVEL), profileOwning(SHOVEL)));
    }

    /**
     * A level that never shows the card screen gets its default bar, not an empty one.
     *
     * <p>The vase level is the case: it enters straight into the run ({@code seed_screen: false}),
     * so the request that arrives with it is empty - there was no screen to make a choice on.
     * Read as "the player picked nothing" the bar came out as the fixed cards alone, which is a
     * run whose sun card was missing while the level handed out sun. Read as "nobody was asked" it
     * comes out as the same bar the chooser's own default would have produced.
     */
    @Test
    void aLevelWithNoCardScreenTreatsAnEmptyRequestAsNoChoice(@TempDir Path gameDir) {
        PlayerProfile profile = profileOwning(PEA, SUN, SHOVEL);
        LevelDef chooser = level(List.of(PEA), 3);
        LevelDef straightIn = TestLevels.copy(chooser).seedScreen(false).build();

        assertEquals(List.of(PEA), SeedSelection.plan(chooser, profile, List.of(), gameDir,
                        false, false),
                "a level with a chooser and an empty request keeps only what it fixed");
        List<Identifier> bar = SeedSelection.plan(straightIn, profile, List.of(), gameDir,
                false, false);
        assertEquals(3, bar.size(), "a level with no chooser gets the default bar: " + names(bar));
        assertEquals(PEA, bar.get(0), "its own card first, as always");
    }

    /** A conveyor level deals its own cards: any selection it was sent is thrown away. */
    @Test
    void aSelfDealtLevelTakesNoSelection(@TempDir Path gameDir) {
        LevelDef def = level(List.of(PEA, SUN), 4);
        List<Identifier> bar = SeedSelection.plan(def, profileOwning(SHOVEL), List.of(SHOVEL),
                gameDir, false, true);
        assertTrue(bar.isEmpty(), "the card source fills the bar itself");
    }

    /** No request and no save: the backpack fills the free slots. */
    @Test
    void withoutARequestTheBackpackFillsTheFreeSlots(@TempDir Path gameDir) {
        LevelDef def = level(List.of(PEA, SUN), 3);
        List<Identifier> bar = SeedSelection.plan(def, profileOwning(SHOVEL), null,
                gameDir, false, false);
        assertTrue(bar.contains(PEA) && bar.contains(SUN), "the level's cards are always there");
        assertTrue(bar.contains(SHOVEL), "and the backpack has one card to add");
    }

    /**
     * Continuing a save wins over the request: the request was made before anything knew a save
     * existed, and the run's own bar is what it must come back with.
     */
    @Test
    void aContinuedSaveKeepsItsOwnBar(@TempDir Path gameDir) throws Exception {
        Path saveDir = gameDir.resolve("levels/whatever");
        java.nio.file.Files.createDirectories(saveDir);
        CompoundTag save = new CompoundTag();
        ListTag slots = new ListTag();
        CompoundTag card = new CompoundTag();
        card.putString("def", SUNFLOWER.toString());
        slots.add(card);
        save.put("Slots", slots);
        NbtIo.writeCompressed(save, saveDir.resolve("level.dat"));

        LevelDef def = level(List.of(PEA, SUN), 4);
        List<Identifier> bar = SeedSelection.plan(def, profileOwning(SHOVEL, SUNFLOWER),
                List.of(SHOVEL), saveDir, true, false);
        assertTrue(bar.contains(SUNFLOWER), "the saved bar comes back, got " + names(bar));
        assertFalse(bar.contains(SHOVEL), "and the request made before the prompt does not");
    }

    /** A save with no card bar at all leaves the request (or the default) in charge. */
    @Test
    void aSaveWithoutABarFallsBackToTheRequest(@TempDir Path gameDir) {
        LevelDef def = level(List.of(PEA, SUN), 3);
        List<Identifier> bar = SeedSelection.plan(def, profileOwning(SHOVEL), List.of(SHOVEL),
                gameDir, true, false);
        assertTrue(bar.contains(SHOVEL), "nothing was saved, so the pick stands");
    }

    /** The bar size is the level's declaration, or the backpack's when it declares none. */
    @Test
    void theBarSizeFollowsTheLevelThenTheBackpack() {
        PlayerProfile profile = profileOwning();
        profile.setSeedSlots(10);
        assertEquals(3, SeedSelection.effectiveSlots(level(List.of(PEA), 3), profile));
        assertEquals(10, SeedSelection.effectiveSlots(level(List.of(PEA), -1), profile));
    }
}
