package com.pvzce.common.core;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.network.packet.SeedOption;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one rule for a level's card bar: the cards the level lists are pinned, and the slots
 * it left over are the player's to fill.
 *
 * <p>There is deliberately no per-card "fixed" flag to test - the position in the card list
 * is what says it. These tests pin the consequences: a level that fills the bar hands out a
 * fixed deck, a level that does not leaves room, and the declared slot count is raised
 * rather than allowed to drop the level's own cards.
 */
class SeedPlanTest {
    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static Identifier id(String raw) {
        return Identifier.parse(raw);
    }

    private static LevelDef level(List<Identifier> cards, int maxSlots) {
        return new LevelDef(id("pvzce:seed_test"), "测试", "", 9, 5, Map.of(), List.of(),
                id("pvzce:plant_team"), Map.of(), Map.of(), List.of(), 1F, cards,
                Map.of(), PvzceConstants.INITIAL_SUN, LevelDef.LevelMusicDef.DEFAULT, List.of(), maxSlots);
    }

    private static List<String> names(List<Identifier> ids) {
        return ids.stream().map(Identifier::toString).toList();
    }

    @Test
    void theDeclaredSlotCountIsRaisedToFitTheLevelsOwnCards() {
        LevelDef def = level(List.of(id("pvzce:pea_shooter"), id("pvzce:sunflower"), id("pvzce:sun")), 2);
        assertEquals(3, def.maxSeedSlots(),
                "a level that lists three cards cannot declare two slots and still grant them");
    }

    @Test
    void everyCardTheLevelListsIsPinned() {
        LevelDef def = level(List.of(id("pvzce:pea_shooter"), id("pvzce:sunflower")), 6);
        LevelDef.SeedPlan plan = def.seedPlan(SeedOptions.allCards());

        assertEquals(List.of("pvzce:pea_shooter", "pvzce:sunflower"), names(plan.lockedSlots()));
        assertFalse(plan.pickableSlots().contains(id("pvzce:pea_shooter")),
                "a pinned card must not also be offered as a choice");
        assertFalse(plan.isFullyFixed(), "two cards in six slots leaves room to choose");
    }

    @Test
    void aLevelThatFilesEverySlotIsAFixedDeck() {
        LevelDef def = level(List.of(id("pvzce:pea_shooter"), id("pvzce:sunflower")), 2);
        LevelDef.SeedPlan plan = def.seedPlan(SeedOptions.allCards());

        assertTrue(plan.isFullyFixed(), "no slots left over means no choice");
        assertEquals(2, plan.lockedSlots().size());
        assertTrue(plan.pickableSlots().stream()
                        .noneMatch(card -> plan.lockedSlots().contains(card)),
                "the two lists must be disjoint");
    }

    @Test
    void thePickableListIsEveryOtherCardTheGameOffers() {
        LevelDef def = level(List.of(id("pvzce:pea_shooter")), 6);
        LevelDef.SeedPlan plan = def.seedPlan(SeedOptions.allCards());

        assertTrue(plan.pickableSlots().contains(id("pvzce:wall_nut")));
        assertTrue(plan.pickableSlots().contains(id("pvzce:sun")));
        assertFalse(plan.pickableSlots().contains(id("pvzce:pea_shooter")));
    }

    @Test
    void theDefaultBarIsTheLevelsCardsThenPicks() {
        LevelDef def = level(List.of(id("pvzce:pea_shooter"), id("pvzce:sunflower")), 3);
        List<String> bar = names(def.defaultSeedSelection(SeedOptions.allCards()));

        assertEquals(3, bar.size());
        assertEquals("pvzce:pea_shooter", bar.get(0));
        assertEquals("pvzce:sunflower", bar.get(1));
        assertFalse(List.of("pvzce:pea_shooter", "pvzce:sunflower").contains(bar.get(2)),
                "the third slot is a pick, not one of the level's cards");
    }

    @Test
    void aFixedDeckFillsTheBarExactly() {
        LevelDef def = level(List.of(id("pvzce:pea_shooter"), id("pvzce:sunflower")), 2);
        assertEquals(List.of("pvzce:pea_shooter", "pvzce:sunflower"),
                names(def.defaultSeedSelection(SeedOptions.allCards())));
    }

    @Test
    void duplicateCardsInTheLevelArePinnedOnce() {
        LevelDef def = level(List.of(id("pvzce:sun"), id("pvzce:sun"), id("pvzce:pea_shooter")), 6);
        LevelDef.SeedPlan plan = def.seedPlan(SeedOptions.allCards());
        assertEquals(List.of("pvzce:sun", "pvzce:pea_shooter"), names(plan.lockedSlots()));
    }

    @Test
    void aLegacyLevelIsRaisedToFitItsOwnPool() {
        // Every level authored before the rule lists its whole pool in slots and leaves
        // max_seed_slots at the default. Applying "a card the level chose is fixed" to it
        // raises the count to the pool size - a fixed deck - which is the behaviour that
        // was asked for. Fewer cards than the default is the other case: the bar is raised
        // only when it is too small, so the free slots stay free.
        List<Identifier> thirteen = List.of(
                id("pvzce:pea_shooter"), id("pvzce:sunflower"), id("pvzce:wall_nut"),
                id("pvzce:kernel_pult"), id("pvzce:cherry_bomb"), id("pvzce:chomper"),
                id("pvzce:lily_pad"), id("pvzce:flower_pot"), id("pvzce:coffee_bean"),
                id("pvzce:marigold"), id("pvzce:potato_mine"), id("pvzce:sun"), id("pvzce:shovel"));

        // A level that declares six slots while listing more cards than that.
        int declared = 6;
        LevelDef crowded = level(thirteen, declared);
        assertEquals(13, crowded.maxSeedSlots(), "the count is raised to fit the pool");
        assertTrue(crowded.seedPlan(SeedOptions.allCards()).isFullyFixed(),
                "so a level whose own cards overflow the bar hands out a fixed deck");

        LevelDef small = level(thirteen.subList(0, 4), declared);
        assertEquals(declared, small.maxSeedSlots(),
                "four cards already fit in six slots, so the count is left alone");
        assertFalse(small.seedPlan(SeedOptions.allCards()).isFullyFixed(),
                "and the two free slots stay the player's");
    }

    @Test
    void theChooserIsOfferedEveryCardSoItCanFillTheFreeSlots() {
        LevelDef def = level(List.of(id("pvzce:pea_shooter")), 6);
        List<String> offered = SeedOptions.forLevel(def).stream().map(SeedOption::slotId).toList();

        assertTrue(offered.contains("pvzce:pea_shooter"),
                "the chooser needs the pinned card too: it is what it renders as already chosen");
        assertTrue(offered.contains("pvzce:sun"));
        assertEquals(names(SeedOptions.allCards()), offered);
    }

    @Test
    void aLevelWithNoSlotCountIsSizedByTheBackpack() {
        // The rule the profile's card-slot count exists for: saying nothing in the file is
        // a statement about the player, not a hidden 6.
        LevelDef def = level(List.of(id("pvzce:pea_shooter"), id("pvzce:sun")),
                LevelDef.UNSET_MAX_SEED_SLOTS);
        assertFalse(def.declaresMaxSeedSlots());
        assertEquals(PvzceConstants.DEFAULT_SEED_SLOTS, def.effectiveMaxSeedSlots(
                PvzceConstants.DEFAULT_SEED_SLOTS), "an ordinary backpack gives eight slots");
        assertEquals(10, def.effectiveMaxSeedSlots(10), "an upgraded one gives ten");
        assertEquals(2, def.effectiveMaxSeedSlots(1),
                "but its own cards still fit: a level never hands out a bar shorter than its cards");
    }

    @Test
    void aDeclaredSlotCountIgnoresTheBackpack() {
        // 1-1 fixes two slots on purpose; an upgraded backpack must not turn it into a
        // different level.
        LevelDef def = level(List.of(id("pvzce:pea_shooter"), id("pvzce:sun")), 2);
        assertTrue(def.declaresMaxSeedSlots());
        assertEquals(2, def.effectiveMaxSeedSlots(10));
        assertEquals(2, def.effectiveMaxSeedSlots(PvzceConstants.DEFAULT_SEED_SLOTS));
    }

    @Test
    void thePlanAndTheDefaultBarAgreeWithTheResolvedCount() {
        LevelDef def = level(List.of(id("pvzce:pea_shooter")), LevelDef.UNSET_MAX_SEED_SLOTS);
        int slots = def.effectiveMaxSeedSlots(9);

        assertEquals(9, def.seedPlan(SeedOptions.allCards(), slots).maxSlots());
        assertEquals(9, def.defaultSeedSelection(SeedOptions.allCards(), slots).size(),
                "the bar a level starts with is exactly as long as the resolved count");
    }

}
