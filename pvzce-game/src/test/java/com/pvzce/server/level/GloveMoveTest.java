package com.pvzce.server.level;

import com.google.gson.JsonElement;
import com.pvzce.api.content.EnvValue;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.LevelRewards;
import com.pvzce.api.content.LevelUnlock;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.TeamDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.ServerMessageS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The glove: picking a plant up and putting it down somewhere else.
 *
 * <p>It used to call {@code plant.boost()} - the glove was wired to the energy bean's
 * effect and moved nothing at all, so "can't actually move plants" was literally true.
 * A move is two clicks, and the plant must survive it intact: same definition, same
 * position by cell, same identity as far as the player is concerned.
 */
class GloveMoveTest {
    private static final Identifier PLANT_TEAM = PvzceIds.PLANT_TEAM;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static final class Bridge implements LevelServer.ServerBridge {
        final List<String> messages = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            if (packet instanceof ServerMessageS2C message) {
                messages.add(message.message());
            }
        }

        String last() {
            return messages.isEmpty() ? "" : messages.get(messages.size() - 1);
        }
    }

    private static LevelDef level() {
        return new LevelDef(Identifier.withDefaultNamespace("glove_test"), "手套", "",
                5, 3,
                Map.of(),
                List.of(new TeamDef(PLANT_TEAM, "植物方", "survive_waves"),
                        new TeamDef(PvzceIds.ZOMBIE_TEAM, "僵尸方", "plant_side_lost")),
                PLANT_TEAM, Map.<Identifier, JsonElement>of(), Map.<Identifier, EnvValue>of(),
                List.of(), 1F,
                List.of(PvzceIds.id("glove"), PvzceIds.id("pea_shooter"), PvzceIds.SUN),
                Map.of(), 1000,
                new LevelDef.LevelMusicDef(List.of()), List.of(), 6,
                LevelRewards.NONE, LevelUnlock.NONE);
    }

    /** The slot index of the glove in this level's bar. */
    private static int gloveSlot(LevelServer level) {
        return level.plantPlayer().slots().stream()
                .filter(slot -> slot.defId().equals(PvzceIds.id("glove")))
                .map(slot -> slot.index())
                .findFirst().orElseThrow();
    }

    @Test
    void onePickUpAndOneDropMoveThePlant() {
        LevelServer server = new LevelServer(level());
        Bridge bridge = new Bridge();
        PlantDef pea = BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter"));
        PlantEntity plant = server.spawnPlant(pea, server.team(PLANT_TEAM), 0, 1);
        assertEquals(1, server.plantCount());

        int glove = gloveSlot(server);
        assertTrue(server.useTool(bridge, glove, 0, 1), "the first click lifts the plant");
        assertNull(server.plantAt(2, 2), "nothing is at the destination yet");

        assertTrue(server.useTool(bridge, glove, 2, 2), "the second click puts it down");
        assertNull(server.plantAt(0, 1), "the plant left its old cell");
        PlantEntity moved = server.plantAt(2, 2);
        assertNotNull(moved, "and arrived at the new one");
        assertEquals(1, server.plantCount(), "moving a plant must not duplicate it");
        assertEquals(pea.id(), moved.def().id(), "and it is still the same plant");
        assertFalse(moved.isRemoved());
    }

    /** A move may not cost the plant its state: a damaged plant stays damaged. */
    @Test
    void aMovedPlantKeepsItsHealth() {
        LevelServer server = new LevelServer(level());
        Bridge bridge = new Bridge();
        PlantDef pea = BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter"));
        PlantEntity plant = server.spawnPlant(pea, server.team(PLANT_TEAM), 0, 0);
        plant.damage(120);
        int health = plant.health();
        assertTrue(health < pea.health(), "the plant has taken damage to carry across");

        int glove = gloveSlot(server);
        server.useTool(bridge, glove, 0, 0);
        server.useTool(bridge, glove, 3, 2);

        PlantEntity moved = server.plantAt(3, 2);
        assertNotNull(moved);
        assertEquals(health, moved.health(), "a moved plant is the same plant");
    }

    /** Picking up nothing, or dropping onto an occupied cell, is refused with a reason. */
    @Test
    void emptyPicksAndBadDropsAreRefused() {
        LevelServer server = new LevelServer(level());
        Bridge bridge = new Bridge();
        PlantDef pea = BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter"));
        server.spawnPlant(pea, server.team(PLANT_TEAM), 0, 0);
        server.spawnPlant(pea, server.team(PLANT_TEAM), 4, 2);

        int glove = gloveSlot(server);
        assertFalse(server.useTool(bridge, glove, 3, 1), "there is nothing to pick up there");
        assertTrue(bridge.last().contains("没有植物"), bridge.last());

        assertTrue(server.useTool(bridge, glove, 0, 0), "a plant to lift");
        assertFalse(server.useTool(bridge, glove, 4, 2), "and a plant already in the way");
        assertTrue(bridge.last().contains("不能放"), bridge.last());
        assertNotNull(server.plantAt(0, 0), "a refused drop must leave it where it was");
        assertEquals(2, server.plantCount());

        // The refused drop did not end the move: the same carry can be put down elsewhere.
        assertTrue(server.useTool(bridge, glove, 3, 1), "the plant is still in hand");
        assertNotNull(server.plantAt(3, 1));
        assertNull(server.plantAt(0, 0));
    }

    /**
     * An abandoned carry puts the plant back.
     *
     * <p>Nothing is removed while the glove is holding something, so letting the carry
     * time out is simply forgetting about it - which is why "put it back" cannot lose a
     * plant even if the timeout fires at the worst moment.
     */
    @Test
    void anAbandonedCarryPutsThePlantBack() {
        LevelServer server = new LevelServer(level());
        Bridge bridge = new Bridge();
        PlantDef pea = BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter"));
        server.spawnPlant(pea, server.team(PLANT_TEAM), 1, 1);

        int glove = gloveSlot(server);
        server.useTool(bridge, glove, 1, 1);
        assertTrue(bridge.last().contains("已拿起"), bridge.last());

        for (int i = 0; i < LevelServer.CARRY_TIMEOUT_TICKS + 2; i++) {
            server.tick(bridge);
        }

        assertNotNull(server.plantAt(1, 1), "the plant is still there after the carry expires");
        assertEquals(1, server.plantCount());
    }
}
