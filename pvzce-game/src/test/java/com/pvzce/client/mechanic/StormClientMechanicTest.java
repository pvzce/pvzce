package com.pvzce.client.mechanic;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.StormData;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.level.mechanic.StormState;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.network.packet.LevelPayload;
import com.pvzce.common.tag.TestContent;
import com.pvzce.client.ClientLevel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The storm's client half: the block it reads, the overlay it builds, and the hiding test.
 *
 * <p>Reported symptom this exists to prevent: the storm's darkening never appeared because the
 * client had no overlay for the mechanic, and the symptom of that is not an error - it is a
 * shadowy board whose light never changes. Nothing on the client logs "I did not draw the storm",
 * so the wiring is what has to be asserted: the block decodes, the overlay is built, and the
 * darkness the renderer and the hiding test read comes from the state the server sent.
 */
class StormClientMechanicTest {
    /** The registered mechanic, asked for by id the way the packet listener asks for it. */
    private static final ClientMechanic MECHANIC;

    static {
        ClientMechanics.bootstrap();
        MECHANIC = ClientMechanics.get(PvzceIds.MECHANIC_STORM);
        assertNotNull(MECHANIC, "the storm has to be registered on the client");
    }

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    /** A client level holding the mechanics the server would send for a level. */
    private static ClientLevel levelOf(String path) {
        LevelDef def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/" + path));
        assertNotNull(def, path + " has to exist");
        ClientLevel level = new ClientLevel();
        level.init(def.id().toString(), def.width(), def.height(), List.of(), List.of(),
                List.of(), 6, List.of(), List.of(), "pvzce:plant_team", "植物方",
                LevelMechanics.payloads(def), def.background().orElse(null),
                def.hiddenSceneElements(), def.disableShaders());
        return level;
    }

    /** The block reaches the client and is what the mechanic reads. */
    @Test
    void theClientSeesTheStormTheServerSent() {
        ClientLevel level = levelOf("4_10");
        StormData data = StormClientMechanic.dataOf(level);
        assertNotNull(data, "the storm block has to survive the trip through the level payload");
        assertTrue(level.mechanicIds().contains(PvzceIds.MECHANIC_STORM));
        assertTrue(StormClientMechanic.dataOf(levelOf("4_9")) == null,
                "and a level without a storm says so by having none");
    }

    /** The overlay is built for a storm level and for nothing else. */
    @Test
    void theOverlayIsBuiltForAStormAndOnlyForAStorm() {
        assertNotNull(MECHANIC.createWorldOverlay(levelOf("4_10")),
                "a storm level needs the darkening drawn, so its overlay has to exist");
        assertNull(MECHANIC.createWorldOverlay(levelOf("4_9")),
                "an ordinary fog level must not pay for a feature it does not use");
    }

    /** What the renderer draws and what the board hides come from the synced cycle. */
    @Test
    void theDarknessFollowsTheServerState() {
        ClientLevel level = levelOf("4_10");
        StormData data = StormClientMechanic.dataOf(level);
        assertNotNull(data);
        assertNull(StormClientMechanic.stateOf(level),
                "before the first sync the client has no cycle, and the overlay holds off"
                        + " rather than guessing one");

        // The strike, as the server sends it on the tick it begins.
        MECHANIC.applySync(level, buffer(new StormState.Wire(3L, 1F)));
        assertEquals(0F, StormClientMechanic.darkness(level), 0.0001F,
                "a strike is full light");
        assertFalse(StormClientMechanic.hides(level), "and nothing is hidden");

        // The dim end of a strike: the light is what hides, not the level's ceiling.
        MECHANIC.applySync(level, buffer(new StormState.Wire(3L, 0F)));
        assertEquals(data.maxAlpha(), StormClientMechanic.darkness(level), 0.0001F,
                "and at the dim end of a strike the board is as dark as the level asked for");
        assertTrue(StormClientMechanic.hides(level),
                "which is when what is standing on it stops being drawn");
    }

    /** A strike thunders once, on the tick it begins - and not on the level's first state. */
    @Test
    void thunderLandsWithTheStrike() {
        ClientLevel level = levelOf("4_10");
        assertNull(level.effects().poll(), "nothing before the first state arrives");

        MECHANIC.applySync(level, buffer(new StormState.Wire(0L, 1F)));
        assertNull(level.effects().poll(),
                "the level's opening strike is pulse zero, but nobody has heard one yet - there"
                        + " was no previous state to strike from");

        for (int tick = 1; tick <= 10; tick++) {
            MECHANIC.applySync(level, buffer(new StormState.Wire(0L, 0.2F)));
        }
        assertNull(level.effects().poll(), "and the rest of the strike is silent");

        MECHANIC.applySync(level, buffer(new StormState.Wire(1L, 1F)));
        var effect = level.effects().poll();
        assertNotNull(effect, "the next strike is one clap of thunder");
        assertEquals(com.pvzce.common.PvzceSounds.EFFECT_THUNDER.toString(), effect.sound());
        assertTrue(effect.particle().isEmpty(),
                "and no particle: the strike's light is the overlay's own, not a spawned effect");
    }

    private static PacketByteBuf buffer(StormState.Wire state) {
        return MechanicSyncS2C.of(PvzceIds.MECHANIC_STORM, StormState.Wire.CODEC, state)
                .payloadBuffer();
    }
}
