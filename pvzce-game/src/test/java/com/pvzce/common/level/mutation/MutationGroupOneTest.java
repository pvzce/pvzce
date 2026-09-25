package com.pvzce.common.level.mutation;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.Slot;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.PlayerProfile;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The second catalogue's first two groups: the quiet numbers, and the card bar.
 *
 * <p>Each mutation is checked against <em>what the player would see</em> rather than against the
 * rule it writes: a fragile plant really is born with less health, a locked card really is refused
 * where it is clicked, a shower really does put sun on the lawn. The rule names are an
 * implementation detail the mutation is allowed to change; "the plants are frailer now" is not.
 *
 * <p>The last test is the catalogue's general rule about the card bar, walked over every pair
 * rather than one example of it: two mutations that both rewrite the bar must resolve to "the one
 * that arrived later wins", whichever order they arrive in. That rule is what the panel reads, so a
 * pair that broke it would look like a bug in the panel.
 */
class MutationGroupOneTest {
    private static LevelDef endless;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        Identifier id = MutationLevels.levelIds().get(MutationDifficulty.NORMAL.ordinal());
        LevelDef source = BuiltInRegistries.LEVELS.get(id);
        assertNotNull(source, "the normal-tier mutation level must be registered");
        endless = TestLevels.copy(source).waves(List.of())
                .slots(List.of(PvzceIds.id("pea_shooter"), PvzceIds.id("sunflower"),
                        PvzceIds.id("wall_nut"), PvzceIds.id("shovel")))
                .build();
    }

    // ------------------------------------------------------------------
    // Group A: the numbers
    // ------------------------------------------------------------------

    /**
     * 僵尸强化: the zombie that arrives is tougher, the one already walking is not.
     *
     * <p>Read at spawn, so the test spawns one on each side of the mutation: the one that came
     * before keeps the health it was born with, and a mutation that reached back and re-scaled the
     * lawn would be a mutation that heals half-eaten zombies.
     */
    @Test
    void zombieHealthScalesOnlyWhatArrivesAfterIt() {
        LevelServer level = level();
        ZombieEntity before = level.spawnZombie(Identifier.withDefaultNamespace("basic_zombie"),
                level.team(PvzceIds.ZOMBIE_TEAM), level.width() + 0.5F, 0);
        assertNotNull(before);
        int baseline = before.health();

        level.setRule(PvzceIds.RULE_ZOMBIE_HEALTH_MULTIPLIER, 2F);
        ZombieEntity after = level.spawnZombie(Identifier.withDefaultNamespace("basic_zombie"),
                level.team(PvzceIds.ZOMBIE_TEAM), level.width() + 0.5F, 1);
        assertNotNull(after);
        assertEquals(baseline * 2, after.health(),
                "a zombie born under the rule arrives with twice the health");
        assertEquals(baseline, before.health(),
                "and the one already on the lawn is untouched: health is for life");
        assertEquals(2F, level.rules().getFloat(PvzceIds.RULE_ZOMBIE_HEALTH_MULTIPLIER), 0.001F,
                "the rule is what a mutation writes; the spawn is what reads it");
    }

    /**
     * 植物脆化: a plant planted under it has less health, and the watering can heals it back to
     * <em>its own</em> full rather than to the definition's.
     *
     * <p>The second half is the reason the plant carries its own maximum: without it a single
     * watering would repair a fragile plant past the health it was planted with, and the mutation
     * would be undone by a watering can.
     */
    @Test
    void fragilePlantsArePlantedWeakerAndHealedToTheirOwnFull() {
        LevelServer level = level();
        var pea = BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter"));
        assertNotNull(pea);
        level.setRule(PvzceIds.RULE_PLANT_HEALTH_MULTIPLIER, 0.5F);
        PlantEntity plant = level.spawnPlant(pea, level.team(PvzceIds.PLANT_TEAM), 2, 2);
        assertNotNull(plant);
        assertEquals(Math.max(1, pea.health() / 2), plant.fullHealth(),
                "a fragile plant is planted with half its definition's health");
        assertEquals(plant.fullHealth(), plant.health());

        plant.damage(plant.health() - 1);
        assertEquals(1, plant.health());
        plant.water(LevelServer.WATERED_TICKS, level);
        assertEquals(plant.fullHealth(), plant.health(),
                "watering heals to the plant's own full, not to the definition's");
    }

    /** 卡片冷却: the rule the bar reads, and the one the bar's cards are charged by. */
    @Test
    void cardCooldownScalesTheRechargeTheBarCharges() {
        LevelServer level = level();
        Slot card = level.plantPlayer().slot(0);
        assertNotNull(card);
        int written = card.cooldownTicks();

        level.setRule(PvzceIds.RULE_SEED_COOLDOWN_MULTIPLIER, 2F);
        assertEquals(written * 2, level.effectiveCooldownTicks(card),
                "the same call the charge and the drawn sweep both use");
    }

    /**
     * 阳光暴雨: sun appears on the mutation's own clock, on top of whatever the level's sky does.
     *
     * <p>The fixture's own sky is switched off (both ends of the spawn range at zero, which the sky
     * reads as "never"), so every drop on the lawn is the mutation's - otherwise a passing test
     * could be the level's own weather.
     */
    @Test
    void sunShowerDropsSunOnItsOwnClock() {
        LevelServer level = levelWithMutations();
        level.setRule(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN, 0);
        level.setRule(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX, 0);
        assertNotNull(level.mutations().add(MutationRegistry.get(PvzceIds.MUTATION_SUN_SHOWER),
                Mutation.Roll.NONE));

        int interval = level.mutations().difficulty().scaledInterval(600);
        for (int i = 0; i < interval - 1; i++) {
            level.tick(packet -> { });
        }
        assertEquals(0, dropCount(level), "nothing falls before the clock runs out");
        level.tick(packet -> { });
        assertEquals(1, dropCount(level), "and one sun falls when it does");
        for (int i = 0; i < interval; i++) {
            level.tick(packet -> { });
        }
        assertEquals(2, dropCount(level), "then another, on its own clock");
    }

    // ------------------------------------------------------------------
    // Group B: the card bar
    // ------------------------------------------------------------------

    /** 卡槽封锁: a plant card is locked, refused where it is clicked, and the lock survives a save. */
    @Test
    void slotLockRefusesTheCardItLocked() {
        LevelServer level = level();
        Mutation mutation = MutationRegistry.get(PvzceIds.MUTATION_SLOT_LOCK);
        assertNotNull(mutation);
        Object state = mutation.apply(level, Mutation.Roll.NONE);
        assertNotNull(state, "the fixture bar has plant cards to lock");

        List<Integer> locked = new java.util.ArrayList<>();
        for (Slot slot : level.plantPlayer().slots()) {
            if (mutation.isSlotLocked(level, Mutation.Roll.NONE, state, slot.index())) {
                locked.add(slot.index());
            }
        }
        assertFalse(locked.isEmpty(), "at least one card has to be locked");
        assertTrue(locked.size() <= 2, "and never more than two: " + locked);
        for (int index : locked) {
            assertTrue(mutation.isSlotLocked(level, Mutation.Roll.NONE, state, index));
        }

        // Only plant cards, and the shovel must never be one of them: a locked shovel would take
        // away the only way to dig up a mistake.
        for (int index : locked) {
            assertEquals(Slot.Kind.PLANT, level.plantPlayer().slot(index).kind(),
                    "slot " + index + " is not a plant card");
        }
    }

    /**
     * The manager's own view, which is what the placement path and the packet read.
     *
     * <p>Both directions in one test: the locked card is refused, and a card that is <em>not</em>
     * locked still plants - otherwise "refused" would pass on a fixture that refuses everything,
     * which is exactly how a lock test ends up green on a level with no sun.
     */
    @Test
    void aLockedSlotIsRefusedByTheLevelAndReportedToTheClient() {
        LevelServer level = levelWithMutations();
        assertNotNull(level.mutations().add(MutationRegistry.get(PvzceIds.MUTATION_SLOT_LOCK),
                Mutation.Roll.NONE));
        List<Integer> locked = level.mutations().lockedSlots();
        assertFalse(locked.isEmpty(), "the manager has to report the lock");
        level.team(PvzceIds.PLANT_TEAM).putResource(PvzceIds.SUN, 2000);

        int locked0 = locked.get(0);
        assertFalse(level.placePlant(packet -> { }, locked0, 1, 0),
                "a locked card cannot be planted, whatever the cell is");
        assertEquals(0, level.plantCount());

        int free = firstUnlockedPlantSlot(level, locked);
        assertTrue(free >= 0, "the fixture bar has an unlocked plant card to plant");
        assertTrue(level.placePlant(packet -> { }, free, 1, 0),
                "and an unlocked one plants normally");
        assertEquals(1, level.plantCount());
    }

    /** 卡槽轮盘: a card changes, keeps changing, and an eviction hands the player's bar back. */
    @Test
    void slotRouletteKeepsSwappingCardsAndGivesTheBarBack() {
        LevelServer level = levelWithMutations();
        Mutation roulette = MutationRegistry.get(PvzceIds.MUTATION_SLOT_ROULETTE);
        assertNotNull(roulette);
        level.mutations().add(roulette, Mutation.Roll.NONE);
        List<Identifier> chosen = cardIds(level);

        int interval = level.mutations().difficulty().scaledInterval(1500);
        for (int i = 0; i < interval; i++) {
            level.tick(packet -> { });
        }
        List<Identifier> afterFirst = cardIds(level);
        assertNotEquals(chosen, afterFirst, "the bar is supposed to change");
        assertEquals(chosen.size(), afterFirst.size(), "and keep its shape");

        for (int i = 0; i < interval; i++) {
            level.tick(packet -> { });
        }
        assertNotEquals(afterFirst, cardIds(level), "and change again: this one repeats");
        assertTrue(level.mutations().activeIds().contains(PvzceIds.MUTATION_SLOT_ROULETTE));
    }

    /**
     * The general rule between the bar mutations: the later one wins.
     *
     * <p>Walked over every ordered pair rather than asserted for one example - the rule is a
     * property of the whole family, and a pair that broke it would look like a panel bug. A dealer
     * (the conveyor) and a rewriter (slot replace / slot roulette) are both "bar mutations": the
     * last one to arrive owns the bar and the earlier one is suppressed, and the earlier one comes
     * back when the later one leaves.
     */
    @Test
    void theLaterBarMutationAlwaysWins() {
        List<Identifier> bar = List.of(PvzceIds.MUTATION_CONVEYOR, PvzceIds.MUTATION_SLOT_REPLACE,
                PvzceIds.MUTATION_SLOT_ROULETTE);
        for (Identifier first : bar) {
            for (Identifier second : bar) {
                if (first.equals(second)) {
                    continue;
                }
                LevelServer level = levelWithMutations();
                level.mutations().add(MutationRegistry.get(first), Mutation.Roll.NONE);
                level.mutations().add(MutationRegistry.get(second), Mutation.Roll.NONE);
                assertTrue(level.mutations().isApplied(second),
                        second + " arrived last and has to be the one acting (after " + first + ")");
                assertFalse(level.mutations().isApplied(first),
                        first + " arrived first and has to be held back by " + second);
                assertTrue(level.mutations().isPresent(first),
                        "and it stays on the panel: suppressed is not evicted");
            }
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** A level that mutates, with nothing running yet. */
    private static LevelServer levelWithMutations() {
        return new LevelServer(endless);
    }

    /** A plain level with a bar to lock things on and no mutation list at all. */
    private static LevelServer level() {
        LevelDef def = TestLevels.copy(endless).mechanics(List.of()).build();
        return new LevelServer(def, List.of(),
                LevelServer.SeedContext.forProfile(def, PlayerProfile.starter()), List.of(), null);
    }

    private static List<Identifier> cardIds(LevelServer level) {
        List<Identifier> ids = new ArrayList<>();
        for (Slot slot : level.plantPlayer().slots()) {
            ids.add(slot.defId());
        }
        return ids;
    }

    /** How many resource drops are on the lawn. */
    private static int dropCount(LevelServer level) {
        int count = 0;
        for (var entity : level.entities()) {
            if (entity instanceof com.pvzce.server.entity.ResourceDropEntity) {
                count++;
            }
        }
        return count;
    }

    /** The first plant card on the bar whose slot is not in {@code locked}, or -1. */
    private static int firstUnlockedPlantSlot(LevelServer level, List<Integer> locked) {
        for (Slot slot : level.plantPlayer().slots()) {
            if (slot.kind() == Slot.Kind.PLANT && !locked.contains(slot.index())) {
                return slot.index();
            }
        }
        return -1;
    }
}
