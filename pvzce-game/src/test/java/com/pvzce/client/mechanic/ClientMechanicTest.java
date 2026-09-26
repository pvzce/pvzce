package com.pvzce.client.mechanic;

import com.pvzce.api.content.LevelBelt;
import com.pvzce.api.content.PlacementZone;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientLevel;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.ConveyorMechanic;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.network.packet.SlotInfo;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client's half of the mechanic system: decoding what the server sent, and routing a
 * mechanic's state update to the code that knows what to do with it.
 *
 * <p>These cases exist because "the HUD asks a mechanic instead of a belt flag" is only
 * true if the mechanic's data actually arrives and a whole-bar update actually lands - the
 * two places that used to be {@code payload.isConveyor()} and {@code BeltSyncS2C}.
 */
class ClientMechanicTest {
    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        ClientMechanics.bootstrap();
    }

    private static List<com.pvzce.common.network.packet.LevelPayload.MechanicPayload> bowlingMechanics() {
        var def = BuiltInRegistries.LEVELS.get(Identifier.withDefaultNamespace("yard/adventure/1_5"));
        assertNotNull(def);
        return LevelMechanics.payloads(def);
    }

    private static ClientLevel levelWith(List<com.pvzce.common.network.packet.LevelPayload.MechanicPayload> mechanics) {
        ClientLevel level = new ClientLevel();
        level.init("pvzce:yard/adventure/1_5", 9, 5, List.of(), List.of("small"), List.of(), 6,
                List.of(), List.of(), "pvzce:plant_team", "植物方", mechanics);
        return level;
    }

    @Test
    void theServersMechanicsArriveAsDecodedData() {
        ClientLevel level = levelWith(bowlingMechanics());

        assertEquals(List.of(PvzceIds.MECHANIC_RAKE, PvzceIds.MECHANIC_CONVEYOR,
                        PvzceIds.MECHANIC_PLACEMENT_ZONE, PvzceIds.MECHANIC_MOWER,
                        PvzceIds.MECHANIC_WAVE_PACING),
                level.mechanicIds());
        assertTrue(level.hasMechanic(PvzceIds.MECHANIC_CONVEYOR));
        assertEquals(List.of(), level.mechanicData(PvzceIds.MECHANIC_MOWER,
                        com.pvzce.api.content.MowerData.class).rowsFor(level.height()),
                "Wall-nut Bowling declares its rows explicitly, and the list is empty");
        assertEquals(List.of(), level.mechanicData(PvzceIds.MECHANIC_RAKE,
                        com.pvzce.api.content.RakeData.class).rowsFor(level.height()),
                "and it declares its rake off the same way, so a bought rake never bowls for you");
        assertEquals(6, level.mechanicData(PvzceIds.MECHANIC_CONVEYOR, LevelBelt.class).capacity());
        assertTrue(level.placementZone().contains(3, 0));
        assertFalse(level.inPlacementZone(4, 0), "the red line is where the server says it is");
        assertNotNull(level.mechanicData(PvzceIds.MECHANIC_CONVEYOR, LevelBelt.class));
        assertNotNull(level.mechanicData(PvzceIds.MECHANIC_PLACEMENT_ZONE, PlacementZone.class));
    }

    @Test
    void anOrdinaryLevelHasTheImplicitDeckAndNoRestrictions() {
        var def = BuiltInRegistries.LEVELS.get(Identifier.withDefaultNamespace("yard/adventure/1_1"));
        ClientLevel level = levelWith(LevelMechanics.payloads(def));

        // What the server sent is the whole truth: the implicit deck and the implicit
        // mowers and the wave pacing, none of which the level's file mentions.
        assertEquals(List.of(PvzceIds.MECHANIC_DECK, PvzceIds.MECHANIC_MOWER,
                PvzceIds.MECHANIC_WAVE_PACING), level.mechanicIds());
        assertFalse(level.hasMechanic(PvzceIds.MECHANIC_CONVEYOR));
        assertEquals(com.pvzce.api.content.MowerData.EVERY_ROW,
                level.mechanicData(PvzceIds.MECHANIC_MOWER, com.pvzce.api.content.MowerData.class));
        assertEquals(PlacementZone.FULL, level.placementZone());
        assertTrue(level.inPlacementZone(8, 0));
        assertNull(ClientMechanics.cardBar(level, null), "the deck gets the ordinary seed row");
    }

    @Test
    void theBeltMechanicReplacesTheWholeBar() {
        ClientLevel level = levelWith(bowlingMechanics());
        assertTrue(ClientMechanics.cardBar(level, null) instanceof com.pvzce.client.gui.hud.cardbar.BeltCardBar,
                "the belt brings its own bar");

        // What BeltSyncS2C used to carry: the whole bar, so a spent card can disappear.
        ClientMechanics.applySync(level, MechanicSyncS2C.of(PvzceIds.MECHANIC_CONVEYOR,
                ConveyorMechanic.BarState.CODEC,
                new ConveyorMechanic.BarState(List.of(
                        new SlotInfo(7, "pvzce:bowling_nut", "plant", SlotInfo.NO_PRICE, 0, true)))));
        assertEquals(1, level.slots().size());
        assertEquals(7, level.slots().get(0).index(), "belt ids, not positions");

        ClientMechanics.applySync(level, MechanicSyncS2C.of(PvzceIds.MECHANIC_CONVEYOR,
                ConveyorMechanic.BarState.CODEC, new ConveyorMechanic.BarState(List.of())));
        assertEquals(List.of(), level.slots(), "an empty belt empties the bar");
    }

    /**
     * A mechanic this client cannot decode must not take the level down with it.
     *
     * <p>The realistic case is a server that runs a mod the client does not have: the board
     * is fully described by the rest of the payload, so the level plays and only the
     * mechanic's own HUD is missing.
     */
    @Test
    void anUnknownMechanicIsSkippedRatherThanFatal() {
        var unknown = new com.pvzce.common.network.packet.LevelPayload.MechanicPayload(
                Identifier.withDefaultNamespace("not_a_mechanic"), "{}");
        ClientLevel level = levelWith(List.of(unknown));

        assertEquals(List.of(), level.mechanicIds());
        assertFalse(level.hasMechanic(PvzceIds.MECHANIC_CONVEYOR));
    }

    /** The level's own card-source question, asked before a level instance exists. */
    @Test
    void theClientKnowsWhichLevelsDealTheirOwnCards() {
        assertTrue(ClientMechanics.dealsItsOwnCards("pvzce:yard/adventure/1_5"));
        assertFalse(ClientMechanics.dealsItsOwnCards("pvzce:yard/adventure/1_1"));
        assertFalse(ClientMechanics.dealsItsOwnCards("pvzce:not/a_level"));
        assertFalse(ClientMechanics.dealsItsOwnCards("not an id"));
    }
}
