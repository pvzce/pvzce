package com.pvzce.server;

import com.pvzce.api.content.LevelBelt;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.LevelRewards;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.SlotDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.api.util.LevelGrouping;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.capability.plant.NocturnalCapability;
import com.pvzce.common.capability.plant.ShooterCapability;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Day area's second half: 1-6 to 1-10.
 *
 * <p>What is pinned here is what the levels were asked to be - the original's reward ladder,
 * a card bar the player fills themselves, a sun card that can actually collect sun, the
 * conveyor finale with its one-minute day - rather than their wave tables, which are balance
 * data and belong to the same tuning pass as every other level's.
 */
class DayAreaLevelsTest {
    private static final List<String> ADVENTURE =
            List.of("1_6", "1_7", "1_8", "1_9", "1_10");

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static LevelDef level(String name) {
        LevelDef def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/" + name));
        assertNotNull(def, name + " must be part of the built-in adventure");
        return def;
    }

    /**
     * The original's ladder, shifted by the one level this project gave the potato mine
     * earlier: 1-6 the snow pea, 1-7 the chomper, 1-8 the repeater - and 1-9, which in the
     * original hands over nothing at all, pays the standard money bag.
     */
    @Test
    void theRewardLadderMatchesTheOriginal() {
        assertEquals("pvzce:snow_pea", firstUnlock(level("1_6")));
        assertEquals("pvzce:chomper", firstUnlock(level("1_7")));
        assertEquals("pvzce:repeater", firstUnlock(level("1_8")));

        LevelDef oneNine = level("1_9");
        assertEquals(LevelRewards.DEFAULT, oneNine.rewards(),
                "1-9 declares no rewards block: it pays the default money bag");
        assertTrue(oneNine.rewards().firstClear().isEmpty(), "and unlocks nothing");

        assertEquals("pvzce:puff_shroom", firstUnlock(level("1_10")),
                "the finale hands over the mushroom the night levels are built around");
        // The mushroom is a real card, not an id that unlocks nothing.
        assertNotNull(BuiltInRegistries.PLANTS.get(PvzceIds.id("puff_shroom")));
        assertTrue(BuiltInRegistries.SLOT_TYPES.containsKey(PvzceIds.id("puff_shroom")),
                "an unlock needs a card to land on");
    }

    private static String firstUnlock(LevelDef def) {
        return def.rewards().firstClear().stream()
                .filter(LevelRewards.Reward::isUnlock)
                .map(reward -> reward.id().orElseThrow().toString())
                .findFirst()
                .orElseThrow(() -> new AssertionError(def.id() + " grants no card"));
    }

    /** Each level opens when the one before it is cleared. */
    @Test
    void theChainRunsFromOneFiveToOneTen() {
        for (int i = 0; i < ADVENTURE.size(); i++) {
            LevelDef def = level(ADVENTURE.get(i));
            String expected = i == 0 ? "1_5" : ADVENTURE.get(i - 1);
            assertEquals(List.of("pvzce:yard/adventure/" + expected),
                    def.unlock().requires().stream()
                            .map(requirement -> requirement.id().orElseThrow().toString())
                            .toList(),
                    def.id() + " opens after " + expected);
        }
    }

    /**
     * No built-in deck and no slot cap: the player brings their own cards, up to whatever
     * their backpack holds.
     */
    @Test
    void thePlayerPicksTheirOwnCards() {
        for (String name : ADVENTURE.subList(0, 4)) {
            LevelDef def = level(name);
            assertTrue(def.slots().isEmpty(), def.id() + " pins no cards");
            assertFalse(def.declaresMaxSeedSlots(), def.id() + " does not cap the card bar");
            assertEquals(PvzceConstants.DEFAULT_SEED_SLOTS,
                    def.effectiveMaxSeedSlots(PvzceConstants.DEFAULT_SEED_SLOTS),
                    def.id() + " follows the backpack");
            assertTrue(def.unlockResources().getOrDefault(PvzceIds.SUN, false),
                    def.id() + " must unlock sun, or a chosen sun card could not collect it");
        }
    }

    /** Sun is collected through its card, so the data alone is only half the contract. */
    @Test
    void aChosenSunCardCanCollectSunOnTheShippedBoard() {
        LevelServer level = new LevelServer(level("1_6"));
        assertTrue(level.team(PvzceIds.PLANT_TEAM).canCollect(PvzceIds.SUN),
                "the team has sun unlocked");
        assertTrue(BuiltInRegistries.SLOT_TYPES.get(PvzceIds.SUN).kind() == SlotDef.Kind.RESOURCE,
                "and the card the player can pick is the resource card");
        assertTrue(level.canPlacePlant(BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter")), 4, 2),
                "the whole five-lane lawn is plantable");
    }

    /**
     * 1-10 is the conveyor finale: no deck, no sun, and the original's seven plants on the
     * belt.
     */
    @Test
    void theFinaleIsABeltLevelOverTheWholeLawn() {
        LevelDef def = level("1_10");
        assertTrue(def.slots().isEmpty(), "the belt is the card bar");
        LevelBelt belt = LevelMechanics.dataOf(def, PvzceIds.MECHANIC_CONVEYOR, LevelBelt.class)
                .orElseThrow(() -> new AssertionError("1-10 must declare a conveyor"));
        assertEquals(List.of("pea_shooter", "wall_nut", "snow_pea", "repeater", "chomper",
                        "potato_mine", "cherry_bomb"),
                belt.cards().stream().map(card -> card.card().path()).toList(),
                "the original's belt hands out the Day area's plants");
        assertTrue(belt.producesCards(), "and it can actually deliver them");
        assertEquals(0, def.initialSun(), "a belt level charges no sun");
        assertEquals(0F, def.rules().get(PvzceIds.RULE_SUN_SPAWN_CHANCE).getAsFloat(),
                "and nothing falls from the sky that could not be spent");

        // No placement zone: the original's 1-10 is an ordinary lawn.
        assertFalse(LevelMechanics.has(def, PvzceIds.MECHANIC_PLACEMENT_ZONE));
        // Mowers are the default, so 1-10 must not declare "none": a finale without them
        // would be unwinnable for anyone who leaks one zombie.
        LevelServer level = new LevelServer(def);
        assertEquals(level.height(), level.readyMowerCount(), "one mower per row");
    }

    /**
     * One minute of day, then night for the rest of the run.
     *
     * <p>The original's last Day level is the one that turns dark; the numbers are the ones
     * the level was specified with, so a typo in the rule (seconds instead of ticks, or a
     * night that is shorter than the day) fails here rather than in a playthrough.
     */
    @Test
    void theFinaleTurnsFromDayToNightAfterAMinute() {
        LevelDef def = level("1_10");
        assertEquals(60 * PvzceConstants.TICKS_PER_SECOND,
                def.rules().get(PvzceIds.RULE_DAY_LENGTH).getAsInt(), "one minute of daylight");
        assertEquals(100 * 60 * PvzceConstants.TICKS_PER_SECOND,
                def.rules().get(PvzceIds.RULE_NIGHT_LENGTH).getAsInt(), "a hundred minutes of night");

        LevelServer level = new LevelServer(def);
        assertFalse(level.isNight(), "the level opens in daylight");
        level.setDayTicks(3599);
        assertFalse(level.isNight(), "and stays there for the first minute");
        level.setDayTicks(3600);
        assertTrue(level.isNight(), "then the sun goes down");
        level.setDayTicks(3600 + 360_000 - 1);
        assertTrue(level.isNight(), "for the rest of any run this level can have");
    }

    /**
     * The level list reads 1-1 ... 1-10.
     *
     * <p>Ids sort as text by default, which puts {@code 1_10} between {@code 1_1} and
     * {@code 1_2}; the list is what a player reads, so the numbering wins.
     */
    @Test
    void levelIdsSortTheWayPeopleNumberThem() {
        assertTrue(LevelGrouping.compareIds("pvzce:yard/adventure/1_2",
                "pvzce:yard/adventure/1_10") < 0);
        assertTrue(LevelGrouping.compareIds("pvzce:yard/adventure/1_9",
                "pvzce:yard/adventure/1_10") < 0);
        assertEquals(0, LevelGrouping.compareIds("a/2_3", "a/2_3"));
        // Anything that is not a number keeps plain text order, so the rule is not a
        // free-for-all: only digit runs are compared by magnitude.
        assertTrue(LevelGrouping.compareIds("a/alpha", "a/beta") < 0);
        assertTrue(LevelGrouping.compareIds("a/1_2", "a/1_2b") < 0, "a prefix sorts first");

        List<String> adventure = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.LEVELS.keySet()) {
            if (id.path().startsWith("yard/adventure/") && !id.path().contains("demo")
                    && !id.path().contains("combat")) {
                adventure.add(id.path());
            }
        }
        adventure.sort(LevelGrouping.idOrder());
        assertEquals(List.of("yard/adventure/1_1", "yard/adventure/1_2", "yard/adventure/1_3",
                        "yard/adventure/1_4", "yard/adventure/1_5", "yard/adventure/1_6",
                        "yard/adventure/1_7", "yard/adventure/1_8", "yard/adventure/1_9",
                        "yard/adventure/1_10", "yard/adventure/2_1", "yard/adventure/2_2",
                        "yard/adventure/2_3", "yard/adventure/2_4", "yard/adventure/2_5"),
                adventure, "the list reads as a numbered list: 1-10 before 2-1, then 2-2 onwards");
    }

    /** The mushroom itself: free, short-ranged, and nocturnal. */
    @Test
    void theMushroomIsFreeShortRangedAndNocturnal() {
        PlantDef puff = BuiltInRegistries.PLANTS.get(PvzceIds.id("puff_shroom"));
        assertEquals(0, puff.cost().resources().getOrDefault(PvzceIds.SUN, 0), "it costs no sun");
        assertTrue(puff.resolvedCapabilities().stream()
                        .anyMatch(entry -> entry.value() instanceof NocturnalCapability),
                "it sleeps in daylight");
        ShooterCapability shooter = puff.capability(ShooterCapability.class).orElseThrow();
        assertEquals(20, shooter.shots().get(0).damage());
        assertEquals(3F, shooter.shots().get(0).range(), 0.0001F,
                "the spore reaches three cells, not the whole lane");
        assertFalse(shooter.shots().get(0).hasUnlimitedRange());

        // The range is a shot property, so every other shooter keeps its unlimited one.
        ShooterCapability pea = BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter"))
                .capability(ShooterCapability.class).orElseThrow();
        assertTrue(pea.shots().get(0).hasUnlimitedRange(), "a pea still crosses the board");
    }
}
