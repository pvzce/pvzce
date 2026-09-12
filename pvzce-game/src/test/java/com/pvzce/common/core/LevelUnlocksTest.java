package com.pvzce.common.core;

import com.mojang.serialization.JsonOps;
import com.google.gson.JsonParser;
import com.pvzce.api.content.LevelUnlock;
import com.pvzce.api.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The unlock rule: what a level asks for, and whether it is open yet.
 *
 * <p>Both halves fail silently in a running game - a condition that never becomes true
 * just leaves a level greyed out forever - so the evaluation and the JSON shape are
 * pinned here rather than eyeballed in the menu.
 */
class LevelUnlocksTest {
    private static Identifier id(String raw) {
        return Identifier.parse(raw);
    }

    private static final Identifier ONE_ONE = id("pvzce:yard/adventure/1_1");
    private static final Identifier ONE_TWO = id("pvzce:yard/adventure/1_2");
    private static final Identifier SUNFLOWER = id("pvzce:sunflower");

    private static LevelUnlocks.Context context(Set<Identifier> cleared, Set<Identifier> purchased,
                                               Identifier... owned) {
        Set<Identifier> cards = Set.of(owned);
        return new LevelUnlocks.Context(cleared, purchased, cards::contains, 0, false);
    }

    private static LevelUnlock requires(LevelUnlock.Requirement... requirements) {
        return new LevelUnlock(List.of(requirements), Optional.empty(), false);
    }

    @Test
    void aLevelWithNoUnlockBlockIsAlwaysPlayable() {
        // The whole point of the default: every level that existed before unlock conditions
        // must keep working, and an author who writes nothing gets no gate.
        assertTrue(LevelUnlocks.evaluate(ONE_TWO, null, LevelUnlocks.Context.empty()).unlocked());
        assertTrue(LevelUnlocks.evaluate(ONE_TWO, LevelUnlock.NONE, LevelUnlocks.Context.empty()).unlocked());
    }

    @Test
    void aPrerequisiteLevelHasToBeCleared() {
        LevelUnlock rule = requires(LevelUnlock.Requirement.level(ONE_ONE));

        LevelUnlocks.State locked = LevelUnlocks.evaluate(ONE_TWO, rule, LevelUnlocks.Context.empty());
        assertFalse(locked.unlocked());
        assertEquals("通关 1_1", locked.reason(), "the reason names what is missing");
        assertEquals(List.of(LevelUnlock.Requirement.level(ONE_ONE)), locked.unmet());

        LevelUnlocks.State open = LevelUnlocks.evaluate(ONE_TWO, rule, context(Set.of(ONE_ONE), Set.of()));
        assertTrue(open.unlocked());
        assertEquals("", open.reason());
    }

    @Test
    void aRequiredCardHasToBeOwned() {
        LevelUnlock rule = requires(LevelUnlock.Requirement.card(SUNFLOWER));
        assertFalse(LevelUnlocks.evaluate(ONE_TWO, rule, LevelUnlocks.Context.empty()).unlocked());
        assertTrue(LevelUnlocks.evaluate(ONE_TWO, rule, context(Set.of(), Set.of(), SUNFLOWER)).unlocked());
    }

    @Test
    void everyRequirementMustHoldNotJustOne() {
        LevelUnlock rule = requires(LevelUnlock.Requirement.level(ONE_ONE),
                LevelUnlock.Requirement.card(SUNFLOWER));

        // Level cleared but the card is still missing: the level stays shut.
        LevelUnlocks.State state = LevelUnlocks.evaluate(ONE_TWO, rule, context(Set.of(ONE_ONE), Set.of()));
        assertFalse(state.unlocked());
        assertEquals(List.of(LevelUnlock.Requirement.card(SUNFLOWER)), state.unmet(),
                "only the outstanding condition is reported");

        assertTrue(LevelUnlocks.evaluate(ONE_TWO, rule,
                context(Set.of(ONE_ONE), Set.of(), SUNFLOWER)).unlocked());
    }

    @Test
    void aCoinThresholdChecksTheWallet() {
        LevelUnlock rule = requires(LevelUnlock.Requirement.coins(500));
        LevelUnlocks.State poor = LevelUnlocks.evaluate(ONE_TWO, rule,
                new LevelUnlocks.Context(Set.of(), Set.of(), card -> false, 499, false));
        assertFalse(poor.unlocked());
        assertEquals("金币达到 500", poor.reason());

        assertTrue(LevelUnlocks.evaluate(ONE_TWO, rule,
                new LevelUnlocks.Context(Set.of(), Set.of(), card -> false, 500, false)).unlocked());
    }

    @Test
    void anUnknownRequirementTypeBlocksRatherThanPassing() {
        // Silently ignoring a condition the author wrote is worse than a stuck level: the
        // validator reports it, and until then the gate holds.
        LevelUnlock rule = requires(new LevelUnlock.Requirement("wat", Optional.empty(), 1));
        assertFalse(LevelUnlocks.evaluate(ONE_TWO, rule, LevelUnlocks.Context.empty()).unlocked());
        assertNotNull(LevelUnlocks.problemWith(rule.requires().get(0)));
    }

    @Test
    void aBoughtLevelIsOpenEvenWhenItsConditionsAreNotMet() {
        LevelUnlock rule = new LevelUnlock(
                List.of(LevelUnlock.Requirement.coins(9999)), Optional.of(500), false);
        LevelUnlocks.Context bought = context(Set.of(), Set.of(ONE_TWO));
        assertTrue(LevelUnlocks.evaluate(ONE_TWO, rule, bought).unlocked(),
                "a purchase is permanent, so the conditions stop mattering");
    }

    @Test
    void aPricedLevelIsBuyableOnlyWhenTheWalletCoversIt() {
        LevelUnlock rule = new LevelUnlock(List.of(LevelUnlock.Requirement.level(ONE_ONE)),
                Optional.of(500), false);
        LevelUnlocks.State poor = LevelUnlocks.evaluate(ONE_TWO, rule,
                new LevelUnlocks.Context(Set.of(), Set.of(), card -> false, 499, false));
        assertFalse(poor.buyable(), "499 coins cannot buy a 500 coin level");
        assertEquals(500, poor.cost());

        LevelUnlocks.State rich = LevelUnlocks.evaluate(ONE_TWO, rule,
                new LevelUnlocks.Context(Set.of(), Set.of(), card -> false, 500, false));
        assertTrue(rich.buyable());
        assertFalse(rich.unlocked(), "affording it is not the same as having it");
    }

    @Test
    void aNegativeOrZeroCostMeansNotForSale() {
        LevelUnlock free = new LevelUnlock(List.of(LevelUnlock.Requirement.level(ONE_ONE)),
                Optional.of(0), false);
        assertFalse(free.isBuyable(), "a zero cost is normalised away, not sold for nothing");
        assertFalse(LevelUnlocks.evaluate(ONE_TWO, free,
                new LevelUnlocks.Context(Set.of(), Set.of(), card -> false, 9999, false)).buyable());
    }

    @Test
    void aHiddenLevelIsOnlyHiddenUntilItOpens() {
        LevelUnlock rule = new LevelUnlock(List.of(LevelUnlock.Requirement.level(ONE_ONE)),
                Optional.empty(), true);
        assertTrue(LevelUnlocks.evaluate(ONE_TWO, rule, LevelUnlocks.Context.empty()).isHidden());
        assertFalse(LevelUnlocks.evaluate(ONE_TWO, rule, context(Set.of(ONE_ONE), Set.of())).isHidden(),
                "once open it is listed like any other level");
    }

    @Test
    void aSandboxWorldUnlocksEverythingWithoutReadingTheRules() {
        LevelUnlock rule = new LevelUnlock(List.of(LevelUnlock.Requirement.coins(1_000_000)),
                Optional.empty(), true);
        LevelUnlocks.State state = LevelUnlocks.evaluate(ONE_TWO, rule,
                new LevelUnlocks.Context(Set.of(), Set.of(), card -> false, 0, true));
        assertTrue(state.unlocked());
        assertFalse(state.isHidden());
    }

    @Test
    void circularChainsAreReported() {
        // Two levels each waiting for the other can never be entered, and nothing in the
        // running game would say why - so the loader has to name them.
        Map<Identifier, LevelUnlock> unlocks = new LinkedHashMap<>();
        unlocks.put(ONE_ONE, requires(LevelUnlock.Requirement.level(ONE_TWO)));
        unlocks.put(ONE_TWO, requires(LevelUnlock.Requirement.level(ONE_ONE)));
        List<String> problems = LevelUnlocks.findCycles(unlocks);
        assertEquals(2, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("circular"), problems.get(0));
    }

    @Test
    void aSelfReferenceIsACycleToo() {
        Map<Identifier, LevelUnlock> unlocks = Map.of(ONE_ONE,
                requires(LevelUnlock.Requirement.level(ONE_ONE)));
        assertEquals(1, LevelUnlocks.findCycles(unlocks).size());
    }

    @Test
    void aChainIsNotACycle() {
        Map<Identifier, LevelUnlock> unlocks = new LinkedHashMap<>();
        unlocks.put(ONE_TWO, requires(LevelUnlock.Requirement.level(ONE_ONE)));
        unlocks.put(id("pvzce:yard/adventure/1_3"),
                requires(LevelUnlock.Requirement.level(ONE_TWO)));
        assertEquals(List.of(), LevelUnlocks.findCycles(unlocks));
    }

    @Test
    void theBlockParsesInTheDocumentedShape() {
        LevelUnlock rule = LevelUnlock.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {
                  "requires": [
                    { "type": "level", "id": "pvzce:yard/adventure/1_1" },
                    { "type": "card",  "id": "pvzce:sunflower" },
                    { "type": "coins", "amount": 500 }
                  ],
                  "cost": 1000,
                  "hidden": true
                }
                """)).getOrThrow();

        assertEquals(3, rule.requires().size());
        assertTrue(rule.requires().get(0).isLevel());
        assertTrue(rule.requires().get(1).isCard());
        assertTrue(rule.requires().get(2).isCoins());
        assertEquals(500, rule.requires().get(2).amount());
        assertEquals(1000, rule.cost().orElseThrow());
        assertTrue(rule.hidden());
        assertFalse(rule.isOpen());

        // And it survives a round trip through the codec.
        var encoded = LevelUnlock.CODEC.encodeStart(JsonOps.INSTANCE, rule).getOrThrow();
        assertEquals(rule, LevelUnlock.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }

    @Test
    void aBlockWithNothingInItCountsAsOpen() {
        LevelUnlock rule = LevelUnlock.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("{}")).getOrThrow();
        assertTrue(rule.isOpen());
        assertTrue(LevelUnlocks.evaluate(ONE_TWO, rule, LevelUnlocks.Context.empty()).unlocked());
    }

    @Test
    void malformedRequirementsAreReported() {
        assertNotNull(LevelUnlocks.problemWith(null));
        assertNotNull(LevelUnlocks.problemWith(new LevelUnlock.Requirement("", Optional.empty(), 1)));
        assertNotNull(LevelUnlocks.problemWith(new LevelUnlock.Requirement("level", Optional.empty(), 1)),
                "a level requirement without an id can never be satisfied");
        assertNotNull(LevelUnlocks.problemWith(new LevelUnlock.Requirement("card", Optional.empty(), 1)));
        assertNotNull(LevelUnlocks.problemWith(new LevelUnlock.Requirement("coins", Optional.empty(), 0)),
                "asking for zero coins is not a condition");
        assertEquals(null, LevelUnlocks.problemWith(LevelUnlock.Requirement.level(ONE_ONE)));
        assertEquals(null, LevelUnlocks.problemWith(LevelUnlock.Requirement.coins(5)));
    }
}
