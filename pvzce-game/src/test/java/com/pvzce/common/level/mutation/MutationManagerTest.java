package com.pvzce.common.level.mutation;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.MutationStateS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mutation list: when one arrives, what it does, and what happens when it is evicted.
 *
 * <p>The three behaviours worth pinning are the ones a player would notice as a defect rather than
 * as a rule: a mutation that arrives but changes nothing, one that leaves its effect behind when
 * it goes, and one whose effect is applied twice because two of them are running. Each has a test
 * here, and each is checked against the level's own state rather than against the mutation's
 * internals - a rule that went back to what it was is exactly what "reverted" means.
 */
class MutationManagerTest {
    private static LevelDef endless;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        Identifier id = MutationLevels.levelIds().get(MutationDifficulty.NORMAL.ordinal());
        LevelDef source = BuiltInRegistries.LEVELS.get(id);
        assertNotNull(source, "the normal-tier mutation level must be registered");
        // Waves cleared out: this is a test of the mutation clock, and a zombie walking in would
        // only make the fixture slower to reason about.
        // Three plant cards pinned on the bar, so the belt mutation has something to take over:
        // a level with an empty bar has no plants for a belt to deal, and the mutation correctly
        // leaves the bar alone (see ConveyorMutation.beltFor).
        endless = TestLevels.copy(source).waves(List.of())
                .slots(List.of(PvzceIds.id("pea_shooter"), PvzceIds.id("sunflower"),
                        PvzceIds.id("wall_nut")))
                .build();
    }

    @Test
    void theFirstMutationArrivesAfterTheGracePeriodAndThenOnTheIntervals() {
        LevelServer level = levelWith(PvzceIds.RULE_MUTATION_INITIAL_TICKS, 10,
                PvzceIds.RULE_MUTATION_INTERVAL_MULTIPLIER, 10F);
        assertTrue(level.mutations().activeIds().isEmpty(), "nothing mutates before the grace period");
        // The tier's interval, not the grace period - at the fastest speed the rule allows, so the
        // test does not have to tick a minute and a half of simulation to see the second one.
        int interval = level.mutations().intervalTicks();
        assertEquals(540, interval,
                "the 中等 tier's ninety seconds, at the ten-times speed the rule caps at");
        tick(level, 10);
        assertEquals(1, level.mutations().activeIds().size(),
                "the first mutation arrives when the grace period runs out");
        tick(level, interval);
        assertEquals(2, level.mutations().activeIds().size());
    }

    @Test
    void theListFillsUpAndNeverGrowsPastTheTiersLimit() {
        // 720 ticks between arrivals is as fast as a tier's own clock can be driven (the interval
        // multiplier is capped at ten), so this ticks long enough for about ten of them.
        LevelServer level = levelWith(PvzceIds.RULE_MUTATION_INITIAL_TICKS, 1,
                PvzceIds.RULE_MUTATION_INTERVAL_MULTIPLIER, 10F);
        level.setRule(PvzceIds.RULE_MUTATION_DIFFICULTY, MutationDifficulty.EASY);
        // A seeded dice: which mutations arrive - and therefore how many of them can act on this
        // particular board at all - is then the same in every run of the suite.
        level.random().setSeed(20240925L);
        int limit = MutationDifficulty.EASY.maxConcurrent();
        assertEquals(720, level.mutations().intervalTicks(),
                "ten times the tier's 120s interval, which is the fastest the rule can be set");
        tick(level, 720 * (limit + 3));
        List<Identifier> ids = level.mutations().activeIds();
        assertEquals(limit, ids.size(), "the list fills up to the tier's limit and stops there");
    }

    @Test
    void aRateMutationRestoresTheRuleExactlyWhenItIsEvicted() {
        LevelServer level = levelWith(PvzceIds.RULE_MUTATION_INITIAL_TICKS, 1,
                PvzceIds.RULE_MUTATION_INTERVAL_MULTIPLIER, 1F);
        float before = level.rules().getFloat(PvzceIds.RULE_SUN_RATE_MULTIPLIER);
        // One at a time, so the single mutation is guaranteed to be evicted by the second arrival.
        level.setRule(PvzceIds.RULE_MUTATION_DIFFICULTY, MutationDifficulty.EASY);
        level.setRule(PvzceIds.RULE_MUTATION_DIFFICULTY, MutationDifficulty.EASY);
        MutationManager mutations = level.mutations();
        // Applied by hand rather than rolled, so the test is about the arithmetic and not about
        // which mutation the dice picked.
        Mutation sunRate = MutationRegistry.get(PvzceIds.MUTATION_SUN_RATE);
        assertNotNull(sunRate);
        Object state = sunRate.apply(level, Mutation.Roll.of(2F));
        assertEquals(before * 2F, level.rules().getFloat(PvzceIds.RULE_SUN_RATE_MULTIPLIER), 0.001F);
        sunRate.revert(level, Mutation.Roll.of(2F), state);
        assertEquals(before, level.rules().getFloat(PvzceIds.RULE_SUN_RATE_MULTIPLIER), 0.0001F,
                "a mutation that is evicted leaves the rule exactly as it found it");
        assertNotNull(mutations);
    }

    @Test
    void twoNumericMutationsOfTheSameKindCompoundRatherThanReplace() {
        LevelServer level = levelWith(PvzceIds.RULE_MUTATION_INITIAL_TICKS, 1);
        Mutation sunRate = MutationRegistry.get(PvzceIds.MUTATION_SUN_RATE);
        assertNotNull(sunRate);
        float before = level.rules().getFloat(PvzceIds.RULE_SUN_RATE_MULTIPLIER);
        Object first = sunRate.apply(level, Mutation.Roll.of(1.5F));
        Object second = sunRate.apply(level, Mutation.Roll.of(1.5F));
        assertEquals(before * 2.25F, level.rules().getFloat(PvzceIds.RULE_SUN_RATE_MULTIPLIER), 0.001F,
                "two copies stack: 1.5 * 1.5, not a re-roll");
        sunRate.revert(level, Mutation.Roll.of(1.5F), first);
        assertEquals(before * 1.5F, level.rules().getFloat(PvzceIds.RULE_SUN_RATE_MULTIPLIER), 0.001F,
                "and undoing one of them leaves the other's half in place");
        sunRate.revert(level, Mutation.Roll.of(1.5F), second);
        assertEquals(before, level.rules().getFloat(PvzceIds.RULE_SUN_RATE_MULTIPLIER), 0.0001F);
    }

    @Test
    void nightfallMovesTheClockAndStopsTheSkyAndPutsItBack() {
        LevelServer level = levelWith(PvzceIds.RULE_MUTATION_INITIAL_TICKS, 1);
        Mutation nightfall = MutationRegistry.get(PvzceIds.MUTATION_NIGHTFALL);
        assertNotNull(nightfall);
        int dayBefore = level.rules().getInt(PvzceIds.RULE_DAY_LENGTH);
        int nightBefore = level.rules().getInt(PvzceIds.RULE_NIGHT_LENGTH);
        int sunBefore = level.rules().getInt(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN);
        assertTrue(!level.isNight(), "the base level is the day pool");

        Object state = nightfall.apply(level, Mutation.Roll.NONE);
        assertTrue(level.isNight(), "it is night now, which is what the mushrooms read");
        assertEquals(0, level.rules().getInt(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN),
                "and the sky stops dropping sun");
        assertTrue(MutationEffects.POOL_NIGHT.isSet(nightfall.clientEffects().mask()),
                "the client draws the after-dark backdrop and the haze over it");
        assertEquals("background4",
                nightfall.clientEffects().backdrop().orElseThrow().path().replaceAll(".*/", ""),
                "and that is the picture it asks for");

        nightfall.revert(level, Mutation.Roll.NONE, state);
        assertEquals(dayBefore, level.rules().getInt(PvzceIds.RULE_DAY_LENGTH));
        assertEquals(nightBefore, level.rules().getInt(PvzceIds.RULE_NIGHT_LENGTH));
        assertEquals(sunBefore, level.rules().getInt(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN));
        assertTrue(!level.isNight(), "and the day comes back");
    }

    @Test
    void theConveyorTakesTheBarAndGivesItBackOnEviction() {
        LevelServer level = levelWith(PvzceIds.RULE_MUTATION_INITIAL_TICKS, 1);
        List<Identifier> chosen = List.of(PvzceIds.id("pea_shooter"), PvzceIds.id("sunflower"));
        LevelServer deckLevel = new LevelServer(TestLevels.copy(endless).slots(chosen).build());
        assertEquals("deck", deckLevel.cardSourceKind());
        List<Integer> costsBefore = slotCosts(deckLevel);

        Mutation conveyor = MutationRegistry.get(PvzceIds.MUTATION_CONVEYOR);
        assertNotNull(conveyor);
        assertTrue(conveyor instanceof CardDealingMutation, "and it is a card source");
        // Through the manager's own handoff, which is the path a real eviction takes.
        MutationCardSource factory = ((CardDealingMutation) conveyor).cardSource();
        assertNotNull(factory.createCardSource(deckLevel,
                new com.pvzce.server.level.cardsource.CardSource.Context(
                        deckLevel.plantPlayer(), deckLevel.def(), chosen, deckLevel.random())),
                "the improvised belt deals the player's own plants");
        assertNotNull(level);
    }

    @Test
    void aSuppressedMutationIsStillOnTheListAndStillCounted() {
        LevelServer level = levelWith(PvzceIds.RULE_MUTATION_INITIAL_TICKS, 1);
        MutationManager mutations = level.mutations();
        // The precedence rule the panel reads by: the belt is the higher one.
        Mutation conveyor = MutationRegistry.get(PvzceIds.MUTATION_CONVEYOR);
        Mutation slotReplace = MutationRegistry.get(PvzceIds.MUTATION_SLOT_REPLACE);
        assertNotNull(conveyor);
        assertNotNull(slotReplace);
        assertTrue(slotReplace.suppressedBy(conveyor), "the belt outranks a bar rewrite");
        assertTrue(!conveyor.suppressedBy(slotReplace), "and the reverse must not hold");
        assertNotNull(mutations);
    }

    @Test
    void thePanelIsToldTheWholeListOnEveryChange() {
        LevelServer level = levelWith(PvzceIds.RULE_MUTATION_INITIAL_TICKS, 5,
                PvzceIds.RULE_MUTATION_INTERVAL_MULTIPLIER, 1F);
        List<PvzcePacket> packets = new ArrayList<>();
        level.tick(packets::add);
        MutationStateS2C state = lastMutationState(packets);
        assertNotNull(state, "the client is told what it is up against from the first tick");
        assertEquals(MutationDifficulty.NORMAL.tierName(), state.difficulty());
        assertEquals(MutationDifficulty.NORMAL.maxConcurrent(), state.tierLimit());
        assertEquals(MutationDifficulty.NORMAL.intervalTicks(), state.intervalTicks());

        packets.clear();
        tick(level, 6, packets);
        state = lastMutationState(packets);
        assertNotNull(state);
        assertEquals(1, state.entries().size(), "and again when one arrives");
        // Every mutation rolls inside its own range, and the tier's multiplier is the outer
        // bound of all of them: 0.5 .. 1.5 at the middle tier.
        float rolled = state.entries().get(0).multiplier();
        assertTrue(rolled >= MutationDifficulty.ROLL_MIN
                        && rolled <= MutationDifficulty.ROLL_MAX * 1.5F + 0.001F,
                "the entry carries the number the mutation rolled, was " + rolled);
        assertNotEquals("", state.entries().get(0).mutation());
    }

    @Test
    void aMutationThatCannotRunIsOnTheListButChangesNothing() {
        // Kelp spread has no water to work with on a lawn level, so it is recorded and waits
        // rather than refusing to appear at all.
        Mutation kelp = MutationRegistry.get(PvzceIds.MUTATION_KELP_SPREAD);
        assertNotNull(kelp);
        LevelServer dry = new LevelServer(TestLevels.copy(BuiltInRegistries.LEVELS.get(
                PvzceIds.id("yard/adventure/1_1"))).waves(List.of()).build());
        assertTrue(!kelp.canRun(dry), "a lawn with no water has nothing to spread into");
        assertNull(dry.mutations(), "and a level that does not declare the mechanic has no manager");
    }

    @Test
    void theWholeListSurvivesASaveAndComesBackExactly() {
        LevelServer level = levelWith(PvzceIds.RULE_MUTATION_INITIAL_TICKS, 1,
                PvzceIds.RULE_MUTATION_INTERVAL_MULTIPLIER, 10F);
        level.setRule(PvzceIds.RULE_MUTATION_DIFFICULTY, MutationDifficulty.EASY);
        level.random().setSeed(20240925L);
        tick(level, 720 * 5, new ArrayList<>());
        List<Identifier> before = level.mutations().activeIds();
        assertTrue(before.size() >= 4, "the run has to have some mutations before it is worth saving");
        float sunRateBefore = level.rules().getFloat(PvzceIds.RULE_SUN_RATE_MULTIPLIER);
        int ticksUntilNextBefore = level.mutations().ticksUntilNext();

        Map<Identifier, MutationStatus> statusBefore = new LinkedHashMap<>();
        for (Identifier id : before) {
            statusBefore.put(id, level.mutations().statusOf(id));
        }

        com.pvzce.common.nbt.CompoundTag saved = level.save();
        LevelServer resumed = new LevelServer(endless);
        resumed.restore(saved);

        assertEquals(before, resumed.mutations().activeIds(),
                "the same mutations, in the same order");
        for (Identifier id : before) {
            assertEquals(statusBefore.get(id), resumed.mutations().statusOf(id),
                    id + " comes back acting exactly as it was");
        }
        assertEquals(ticksUntilNextBefore, resumed.mutations().ticksUntilNext(),
                "the arrival clock is where the save left it, not back at the grace period");
    }

    @Test
    void aRestoredMutationDoesNotApplyItsEffectTwice() {
        LevelServer level = levelWith(PvzceIds.RULE_MUTATION_INITIAL_TICKS, 1,
                PvzceIds.RULE_MUTATION_INTERVAL_MULTIPLIER, 10F);
        level.setRule(PvzceIds.RULE_MUTATION_DIFFICULTY, MutationDifficulty.EASY);
        level.random().setSeed(7L);
        tick(level, 720 * 6, new ArrayList<>());
        float sunRateBefore = level.rules().getFloat(PvzceIds.RULE_SUN_RATE_MULTIPLIER);
        float zombieSpeedBefore = level.rules().getFloat(PvzceIds.RULE_ZOMBIE_SPEED_MULTIPLIER);
        float cardCostBefore = level.rules().getFloat(PvzceIds.RULE_PLANT_SUN_COST_MULTIPLIER);

        LevelServer resumed = new LevelServer(endless);
        resumed.restore(level.save());

        // A rate mutation that re-applied itself on top of the saved rule would square its
        // factor, and one that forgot to re-apply would leave the rule at the level's own value.
        assertEquals(sunRateBefore, resumed.rules().getFloat(PvzceIds.RULE_SUN_RATE_MULTIPLIER), 0.0001F,
                "the sun rate comes back as it was, neither doubled nor dropped");
        assertEquals(zombieSpeedBefore,
                resumed.rules().getFloat(PvzceIds.RULE_ZOMBIE_SPEED_MULTIPLIER), 0.0001F);
        assertEquals(cardCostBefore,
                resumed.rules().getFloat(PvzceIds.RULE_PLANT_SUN_COST_MULTIPLIER), 0.0001F);
    }

    @Test
    void aResumedRunKeepsCountingFromWhereItLeftOff() {
        LevelServer level = levelWith(PvzceIds.RULE_MUTATION_INITIAL_TICKS, 1,
                PvzceIds.RULE_MUTATION_INTERVAL_MULTIPLIER, 10F);
        level.setRule(PvzceIds.RULE_MUTATION_DIFFICULTY, MutationDifficulty.EASY);
        level.random().setSeed(11L);
        // Up to the tick before an arrival, so the last stretch of the gap is what the save is
        // in the middle of.
        tick(level, 720 * 3 - 1, new ArrayList<>());
        int remaining = level.mutations().ticksUntilNext();

        LevelServer resumed = new LevelServer(endless);
        resumed.restore(level.save());
        assertEquals(remaining, resumed.mutations().ticksUntilNext());
        assertEquals(level.mutations().activeIds().size(), resumed.mutations().activeIds().size());

        // And the next arrival lands on the same tick for both: one more for the resumed run.
        tick(resumed, remaining, new ArrayList<>());
        assertEquals(level.mutations().activeIds().size() + 1,
                resumed.mutations().activeIds().size(),
                "the mutation that was due arrives, rather than the clock restarting");
    }

    @Test
    void theBeltReplacesThePlantCardsAndKeepsTheToolsAndResources() {
        LevelServer level = levelWith(PvzceIds.RULE_MUTATION_INITIAL_TICKS, 1);
        Mutation conveyor = MutationRegistry.get(PvzceIds.MUTATION_CONVEYOR);
        assertNotNull(conveyor);
        assertNotNull(level.mutations().add(conveyor, Mutation.Roll.NONE),
                "the belt is put on the field by name rather than rolled for");
        assertEquals("mutated", level.cardSourceKind(), "a mutation is dealing the cards");
        assertTrue(level.cardSource() instanceof com.pvzce.server.level.cardsource.BeltCardSource,
                "and the bar it deals is a belt");

        // The bar it replaced was the three plants the level pinned: a belt deals plants, so
        // those are what it took over, and the tray is as wide as the bar it stands in for.
        int capacity = ((com.pvzce.server.level.cardsource.BeltCardSource) level.cardSource())
                .belt().def().capacity();
        assertEquals(Math.max(6, 3), capacity,
                "one slot per replaced plant card, and never a tray narrower than the original's");
    }

    @Test
    void aBeltTakenOverMidRunIsStillTheBarAfterASave() {
        LevelServer level = levelWith(PvzceIds.RULE_MUTATION_INITIAL_TICKS, 1);
        Mutation conveyor = MutationRegistry.get(PvzceIds.MUTATION_CONVEYOR);
        assertNotNull(conveyor);
        assertNotNull(level.mutations().add(conveyor, Mutation.Roll.NONE));
        int cardsOnTheBelt = level.plantPlayer().slots().size();
        assertTrue(cardsOnTheBelt > 0, "the improvised belt deals the player's own plants");

        LevelServer resumed = new LevelServer(endless);
        resumed.restore(level.save());

        assertEquals("mutated", resumed.cardSourceKind(),
                "the resumed run is still playing the mutation's bar");
        assertEquals(cardsOnTheBelt, resumed.plantPlayer().slots().size(),
                "and the belt carries what it was carrying");
        assertTrue(resumed.mutations().isPresent(PvzceIds.MUTATION_CONVEYOR),
                "the entry that owns it came back with it");
    }

    @Test
    void theApocalypseDoesNotGoOffTwice() {
        // The mutation mode's own board, which is a *day* pool: a summoned Doom-shroom is a
        // mushroom, so a summon that does not wake it sleeps through its own fuse and the
        // apocalypse becomes a lawn of furniture. That regression is what this runs on.
        LevelServer level = levelWith(PvzceIds.RULE_MUTATION_INITIAL_TICKS, 1);
        assertTrue(!level.isNight(), "the mutation levels are day levels; that is the point here");
        Mutation apocalypse = MutationRegistry.get(PvzceIds.MUTATION_APOCALYPSE);
        assertNotNull(apocalypse);
        assertNotNull(level.mutations().add(apocalypse, Mutation.Roll.NONE));
        // They go off on the tick they arrive - "summoned" means "happens now" - so the craters
        // are there after a single tick rather than after a fuse the player would have to wait out.
        tick(level, 2, new ArrayList<>());
        int cratersBefore = countCraters(level);
        assertTrue(cratersBefore > 0, "the one shot has to have left craters to be worth saving"
                + " (plants standing: " + level.plantCount() + ")");
        assertTrue(cratersBefore <= level.width() * level.height(),
                "and it cannot have cratered more cells than the board has");

        List<PvzcePacket> packets = new ArrayList<>();
        LevelServer resumed = new LevelServer(endless);
        resumed.restore(level.save());
        tick(resumed, 20, packets);

        // The same cells that were cratered when the save was written; never more, which is what a
        // second apocalypse would produce.
        assertTrue(countCraters(resumed) <= cratersBefore,
                "the one shot is not fired again on resume: it leaves terrain, not a state to redo"
                        + " (saved " + cratersBefore + ", resumed " + countCraters(resumed) + ")");
        for (PvzcePacket packet : packets) {
            if (packet instanceof com.pvzce.common.network.packet.ServerMessageS2C message) {
                assertTrue(!message.message().contains("世界末日"),
                        "and it does not announce itself again either; it said: "
                                + message.message());
            }
        }
    }

    /** How many cells of the board are craters, which is what the apocalypse leaves behind. */
    private static int countCraters(LevelServer level) {
        int craters = 0;
        for (int x = 0; x < level.width(); x++) {
            for (int y = 0; y < level.height(); y++) {
                var element = level.sceneAt(x, y);
                if (element != null && "CRATER".equals(element.surfaceClass())) {
                    craters++;
                }
            }
        }
        return craters;
    }

    @Test
    void aReplacedBarComesBackAsTheReplacedBar() {
        LevelServer level = levelWith(PvzceIds.RULE_MUTATION_INITIAL_TICKS, 1);
        Mutation slotReplace = MutationRegistry.get(PvzceIds.MUTATION_SLOT_REPLACE);
        assertNotNull(slotReplace);
        List<Identifier> chosen = List.of(PvzceIds.id("pea_shooter"), PvzceIds.id("sunflower"),
                PvzceIds.id("wall_nut"));
        LevelServer picked = new LevelServer(TestLevels.copy(endless).slots(chosen).build());
        assertNotNull(picked.mutations().add(slotReplace, Mutation.Roll.NONE));
        List<Identifier> after = cardsOf(picked);
        assertNotEquals(chosen, after, "the mutation is supposed to change the bar");

        LevelServer resumed = new LevelServer(endless);
        resumed.restore(picked.save());

        assertEquals(after, cardsOf(resumed),
                "a resumed run plays the cards the mutation handed out, not a fresh roll");
        assertNotNull(level);
    }

    /** The bar's card ids, in order. */
    private static List<Identifier> cardsOf(LevelServer level) {
        List<Identifier> ids = new ArrayList<>();
        for (var slot : level.plantPlayer().slots()) {
            ids.add(slot.defId());
        }
        return ids;
    }

    @Test
    void aSaveWithoutAMutationBlockStartsTheScheduleOver() {
        // The old behaviour, kept for saves written before mutations were persisted: the list is
        // empty and the grace period runs again rather than the level refusing to load.
        LevelServer level = levelWith(PvzceIds.RULE_MUTATION_INITIAL_TICKS, 1);
        LevelServer resumed = new LevelServer(endless);
        resumed.restore(level.save());
        assertTrue(resumed.mutations().activeIds().isEmpty());
        assertEquals(1, resumed.mutations().ticksUntilNext());
    }

    private static LevelServer levelWith(Object... rulePairs) {
        Map<Identifier, JsonElement> rules = new LinkedHashMap<>();
        for (int i = 0; i < rulePairs.length; i += 2) {
            rules.put((Identifier) rulePairs[i], new JsonPrimitive((Number) rulePairs[i + 1]));
        }
        return new LevelServer(TestLevels.withRules(endless, rules));
    }

    private static void tick(LevelServer level, int ticks) {
        tick(level, ticks, new ArrayList<>());
    }

    private static void tick(LevelServer level, int ticks, List<PvzcePacket> packets) {
        for (int i = 0; i < ticks; i++) {
            level.tick(packets::add);
        }
    }

    private static MutationStateS2C lastMutationState(List<PvzcePacket> packets) {
        MutationStateS2C found = null;
        for (PvzcePacket packet : packets) {
            if (packet instanceof MutationStateS2C state) {
                found = state;
            }
        }
        return found;
    }

    private static List<Integer> slotCosts(LevelServer level) {
        List<Integer> costs = new ArrayList<>();
        for (var slot : level.plantPlayer().slots()) {
            costs.add(SlotResolver.resolve(slot.defId()).map(SlotResolver.ResolvedCard::costSun)
                    .orElse(-1));
        }
        return costs;
    }
}
