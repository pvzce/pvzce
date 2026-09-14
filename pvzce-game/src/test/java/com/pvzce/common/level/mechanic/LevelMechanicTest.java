package com.pvzce.common.level.mechanic;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.pvzce.api.content.LevelBelt;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlacementZone;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.packet.SlotInfo;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.level.LevelServer;
import com.pvzce.server.level.LevelValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The level mechanic registry: decoding, the implicit deck, and the problems a level's
 * {@code "mechanics"} list can have.
 *
 * <p>These are the contracts the mini-game machinery used to encode as named fields on
 * {@link LevelDef} ({@code belt}, {@code placementZone}) plus branches in the level server;
 * the point of the registry is that the same contracts now hold for a mechanic that did
 * not exist when this test was written.
 */
class LevelMechanicTest {
    private static LevelDef bowling;
    private static LevelDef ordinary;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        bowling = BuiltInRegistries.LEVELS.get(Identifier.withDefaultNamespace("yard/adventure/1_5"));
        ordinary = BuiltInRegistries.LEVELS.get(Identifier.withDefaultNamespace("yard/adventure/1_1"));
        assertNotNull(bowling, "the shipped 1-5 must load");
        assertNotNull(ordinary, "the shipped 1-1 must load");
    }

    @Test
    void theBowlingLevelDeclaresBothOfItsMechanics() {
        // Three blocks: the belt, the red line, and the mower rows - which is how a level
        // says "this one has no mowers" (the original's Wall-nut Bowling has none).
        assertEquals(3, bowling.mechanics().size(), "a belt, a red line and no mowers, as data");
        assertEquals(List.of(PvzceIds.MECHANIC_CONVEYOR, PvzceIds.MECHANIC_PLACEMENT_ZONE,
                        PvzceIds.MECHANIC_MOWER),
                List.of(bowling.mechanics().get(0).type(), bowling.mechanics().get(1).type(),
                        bowling.mechanics().get(2).type()));
        assertEquals(List.of(), LevelMechanics
                        .dataOf(bowling, PvzceIds.MECHANIC_MOWER, com.pvzce.api.content.MowerData.class)
                        .orElseThrow().rowsFor(bowling.height()),
                "and an explicitly empty row list means none at all");

        LevelBelt belt = LevelMechanics.dataOf(bowling, PvzceIds.MECHANIC_CONVEYOR, LevelBelt.class)
                .orElseThrow();
        assertEquals(150, belt.intervalTicks());
        assertEquals(1, belt.cards().size());
        assertEquals(PvzceIds.id("bowling_nut"), belt.cards().get(0).card());

        PlacementZone zone = LevelMechanics
                .dataOf(bowling, PvzceIds.MECHANIC_PLACEMENT_ZONE, PlacementZone.class).orElseThrow();
        assertTrue(zone.contains(3, 0));
        assertFalse(zone.contains(4, 0), "the red line is at column 4");
    }

    /**
     * An ordinary level carries no mechanics at all, and still has a card bar.
     *
     * <p>This is the "implicit deck" rule: the absence of a card source is not the absence
     * of cards, so every level written before mechanics existed keeps working, and the
     * decision is made in one place instead of at each call site.
     */
    @Test
    void anOrdinaryLevelFallsBackToTheDeck() {
        assertEquals(List.of(), ordinary.mechanics());
        assertEquals(List.of(), LevelMechanics.declaredCardSources(ordinary));
        assertFalse(LevelMechanics.has(ordinary, PvzceIds.MECHANIC_CONVEYOR));
        assertEquals(PvzceIds.MECHANIC_DECK, LevelMechanics.cardSource(ordinary).type());
        // Two implicit mechanics, not one: a level that says nothing gets the ordinary card
        // bar *and* the ordinary lawn mowers.
        assertEquals(List.of(PvzceIds.MECHANIC_DECK, PvzceIds.MECHANIC_MOWER),
                LevelMechanics.effective(ordinary).stream()
                        .map(com.pvzce.api.content.mechanic.TypedMechanic::type).toList());
        assertEquals(com.pvzce.api.content.MowerData.EVERY_ROW, LevelMechanics
                        .dataOf(ordinary, PvzceIds.MECHANIC_MOWER, com.pvzce.api.content.MowerData.class)
                        .orElseThrow(),
                "and \"every row\" is what its silence about mowers means");
        assertEquals(java.util.Optional.empty(), LevelMechanics.dataOf(
                        ordinary, PvzceIds.MECHANIC_PLACEMENT_ZONE, PlacementZone.class),
                "no zone means no mechanic, and the server reads that as the whole board");
        assertEquals(bowling.mechanics(), LevelMechanics.effective(bowling),
                "a level that declares the mower itself gets no implicit copy of it");
    }

    @Test
    void mechanicBlocksDecodeFromTheLevelJson() {
        LevelDef def = decode("""
                {"id":"pvzce:test/mechanic_level","width":9,"height":5,
                 "slots":[],
                 "mechanics":[
                   {"type":"pvzce:conveyor","interval_ticks":42,"capacity":3,
                    "cards":[{"id":"pvzce:bowling_nut","weight":2}]},
                   {"type":"pvzce:placement_zone","min_x":1,"max_x":2}]}
                """);
        assertEquals(2, def.mechanics().size());
        LevelBelt belt = LevelMechanics.dataOf(def, PvzceIds.MECHANIC_CONVEYOR, LevelBelt.class).orElseThrow();
        assertEquals(42, belt.intervalTicks());
        assertEquals(3, belt.capacity());
        assertEquals(2, belt.cards().get(0).weight());
        PlacementZone zone = LevelMechanics
                .dataOf(def, PvzceIds.MECHANIC_PLACEMENT_ZONE, PlacementZone.class).orElseThrow();
        assertEquals(1, zone.minX());
        assertEquals(2, zone.maxX());
        assertEquals(Integer.MAX_VALUE, zone.maxY(), "an omitted bound is the board edge");
    }

    @Test
    void theDeckCanBeDeclaredExplicitly() {
        LevelDef def = decode("""
                {"id":"pvzce:test/deck_level","width":9,"height":5,
                 "slots":["pvzce:pea_shooter"],
                 "mechanics":[{"type":"pvzce:deck"}]}
                """);
        assertInstanceOf(MechanicData.Empty.class, def.mechanics().get(0).value());
        assertEquals(1, LevelMechanics.declaredCardSources(def).size());
        assertEquals(PvzceIds.MECHANIC_DECK, LevelMechanics.cardSource(def).type());
    }

    @Test
    void anUnknownMechanicTypeIsARefusedLevelRatherThanASilentOne() {
        var result = LevelDef.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"id":"pvzce:test/unknown","slots":["pvzce:pea_shooter"],
                 "mechanics":[{"type":"pvzce:not_a_mechanic"}]}
                """));
        assertTrue(result.error().isPresent(), "an unregistered mechanic must not decode");
        assertTrue(result.error().orElseThrow().message().contains("Unknown level mechanic"),
                result.error().orElseThrow().message());
    }

    @Test
    void twoCardSourcesAreReportedOnce() {
        LevelDef def = decode("""
                {"id":"pvzce:test/two_sources","width":9,"height":5,
                 "slots":[],
                 "mechanics":[
                   {"type":"pvzce:deck"},
                   {"type":"pvzce:conveyor","cards":[{"id":"pvzce:bowling_nut"}]}]}
                """);
        List<String> errors = LevelMechanics.validate(def);
        assertTrue(errors.stream().anyMatch(error -> error.contains("2 card sources")), errors.toString());
        assertTrue(errors.stream().anyMatch(error -> error.contains("pvzce:deck")), errors.toString());
    }

    @Test
    void aBeltLevelThatAlsoListsItsOwnCardsIsReported() {
        LevelDef def = decode("""
                {"id":"pvzce:test/belt_and_cards","width":9,"height":5,
                 "slots":["pvzce:pea_shooter"],
                 "mechanics":[{"type":"pvzce:conveyor","cards":[{"id":"pvzce:bowling_nut"}]}]}
                """);
        List<String> errors = LevelMechanics.validate(def);
        assertTrue(errors.stream().anyMatch(error -> error.contains("never granted")), errors.toString());
    }

    @Test
    void anUnknownConveyorCardIsReported() {
        LevelDef def = decode("""
                {"id":"pvzce:test/bad_belt_card","width":9,"height":5,"slots":[],
                 "mechanics":[{"type":"pvzce:conveyor","cards":[{"id":"pvzce:nope"}]}]}
                """);
        List<String> errors = LevelMechanics.validate(def);
        assertTrue(errors.stream().anyMatch(error -> error.contains("Unknown conveyor card")), errors.toString());
    }

    @Test
    void anImpossiblePlantableAreaIsReportedByItsMechanic() {
        LevelDef def = decode("""
                {"id":"pvzce:test/bad_zone","width":9,"height":5,
                 "slots":["pvzce:pea_shooter"],
                 "mechanics":[{"type":"pvzce:placement_zone","min_x":20,"max_x":30}]}
                """);
        List<String> errors = LevelMechanics.validate(def);
        assertTrue(errors.stream().anyMatch(error -> error.contains("placement_zone")), errors.toString());
    }

    /**
     * The keys that used to be level fields.
     *
     * <p>They are not accepted any more, and they are not ignored silently either: a level
     * still written the old way loads as an ordinary level whose belt never arrives, so the
     * loader reports the block to write instead.
     */
    @Test
    void theOldTopLevelKeysAreReportedWithTheirNewHome() {
        var raw = JsonParser.parseString("""
                {"id":"pvzce:test/legacy","slots":[],
                 "conveyor":{"cards":[{"id":"pvzce:bowling_nut"}]},
                 "placement_zone":{"min_x":0,"max_x":3}}
                """).getAsJsonObject();
        List<String> errors = LevelValidator.validateLegacyKeys(raw);
        assertEquals(2, errors.size(), errors.toString());
        assertTrue(errors.get(0).contains("pvzce:conveyor"), errors.get(0));
        assertTrue(errors.get(1).contains("pvzce:placement_zone"), errors.get(1));
        assertEquals(List.of(), LevelValidator.validateLegacyKeys(JsonParser.parseString("{}").getAsJsonObject()));
    }

    @Test
    void theMechanicsListRoundTripsThroughItsCodec() {
        var encoded = LevelMechanics.LIST_CODEC.encodeStart(JsonOps.INSTANCE, bowling.mechanics())
                .getOrThrow();
        var decoded = LevelMechanics.LIST_CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(bowling.mechanics(), decoded);
    }

    @Test
    void everyRegisteredMechanicIsNamedByItsOwnId() {
        for (LevelMechanic<?> mechanic : BuiltInRegistries.LEVEL_MECHANICS) {
            assertNotNull(BuiltInRegistries.LEVEL_MECHANICS.getKey(mechanic),
                    "a registered mechanic must be reachable by id");
        }
        assertInstanceOf(ConveyorMechanic.class, LevelMechanics.get(PvzceIds.MECHANIC_CONVEYOR));
        assertInstanceOf(PlacementZoneMechanic.class, LevelMechanics.get(PvzceIds.MECHANIC_PLACEMENT_ZONE));
        assertInstanceOf(DeckMechanic.class, LevelMechanics.get(PvzceIds.MECHANIC_DECK));
        assertTrue(LevelMechanics.get(PvzceIds.MECHANIC_CONVEYOR).cardSource());
        assertFalse(LevelMechanics.get(PvzceIds.MECHANIC_PLACEMENT_ZONE).cardSource());
    }

    @Test
    void aMechanicDescribesItsOwnEditorFields() {
        assertFalse(LevelMechanics.get(PvzceIds.MECHANIC_CONVEYOR).editorFields().isEmpty(),
                "the belt had no editor UI for as long as it existed; its fields are declared now");
        assertEquals("interval_ticks",
                LevelMechanics.get(PvzceIds.MECHANIC_CONVEYOR).editorFields().get(0).path());
        assertEquals(4, LevelMechanics.get(PvzceIds.MECHANIC_PLACEMENT_ZONE).editorFields().size());
    }

    /**
     * The two shipped kinds of level build two different card sources, and the level server
     * has no idea which: it asks the mechanic and gets an object back.
     *
     * <p>This is the branch that used to be {@code belt != null} in five places.
     */
    @Test
    void theShippedLevelsBuildTheCardSourceTheirMechanicNames() {
        LevelServer bowlingLevel = new LevelServer(bowling);
        assertInstanceOf(com.pvzce.server.level.cardsource.BeltCardSource.class,
                bowlingLevel.cardSource());
        assertTrue(bowlingLevel.cardSource().dealsItsOwnCards());
        assertEquals("conveyor", bowlingLevel.cardSource().kind());
        // A belt card prints no price at all: the HUD reads NO_PRICE off the wire.
        assertTrue(bowlingLevel.slotInfos().stream().allMatch(SlotInfo::priceless));

        LevelServer ordinaryLevel = new LevelServer(ordinary);
        assertInstanceOf(com.pvzce.server.level.cardsource.DeckCardSource.class,
                ordinaryLevel.cardSource());
        assertFalse(ordinaryLevel.cardSource().dealsItsOwnCards());
        assertEquals("deck", ordinaryLevel.cardSource().kind());
        assertFalse(ordinaryLevel.slotInfos().isEmpty(), "an ordinary level still has a card bar");
        assertTrue(ordinaryLevel.slotInfos().stream().noneMatch(SlotInfo::priceless));
    }

    /**
     * The wire payload of a level carries every effective mechanic, and each block decodes
     * back to the data the level is actually running with.
     *
     * <p>An ordinary level includes the implicit deck: the client is told what the server
     * decided, rather than having to reach the same conclusion from the same silence.
     */
    @Test
    void theWirePayloadsCarryEveryEffectiveMechanicAndDecodeBack() {
        var bowlingPayloads = LevelMechanics.payloads(bowling);
        // The declared card source and the plantable area, plus the implicit mower: what the
        // client is told is what the server runs, whether or not the file mentions it.
        assertEquals(List.of(PvzceIds.MECHANIC_CONVEYOR, PvzceIds.MECHANIC_PLACEMENT_ZONE,
                        PvzceIds.MECHANIC_MOWER),
                bowlingPayloads.stream()
                        .map(com.pvzce.common.network.packet.LevelPayload.MechanicPayload::type).toList());
        assertEquals(LevelMechanics.dataOf(bowling, PvzceIds.MECHANIC_CONVEYOR, LevelBelt.class).orElseThrow(),
                LevelMechanics.decodeBlock(PvzceIds.MECHANIC_CONVEYOR, bowlingPayloads.get(0).block())
                        .orElseThrow());
        assertEquals(LevelMechanics.dataOf(bowling, PvzceIds.MECHANIC_PLACEMENT_ZONE, PlacementZone.class)
                        .orElseThrow(),
                LevelMechanics.decodeBlock(PvzceIds.MECHANIC_PLACEMENT_ZONE, bowlingPayloads.get(1).block())
                        .orElseThrow());

        var ordinaryPayloads = LevelMechanics.payloads(ordinary);
        assertEquals(List.of(PvzceIds.MECHANIC_DECK, PvzceIds.MECHANIC_MOWER),
                ordinaryPayloads.stream()
                        .map(com.pvzce.common.network.packet.LevelPayload.MechanicPayload::type).toList());
        assertInstanceOf(MechanicData.Empty.class, LevelMechanics
                .decodeBlock(PvzceIds.MECHANIC_DECK, ordinaryPayloads.get(0).block()).orElseThrow());
        // An unwritten "rows" has to survive the wire and still mean "every row", or an
        // ordinary level's client would draw no mowers at all.
        assertEquals(com.pvzce.api.content.MowerData.EVERY_ROW, LevelMechanics
                .decodeBlock(PvzceIds.MECHANIC_MOWER, ordinaryPayloads.get(1).block()).orElseThrow());

        assertEquals(java.util.Optional.empty(),
                LevelMechanics.decodeBlock(PvzceIds.MECHANIC_CONVEYOR, "not json at all"));
        assertEquals(java.util.Optional.empty(),
                LevelMechanics.decodeBlock(PvzceIds.id("nope"), "{}"));
    }

    private static LevelDef decode(String json) {
        return LevelDef.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }
}
