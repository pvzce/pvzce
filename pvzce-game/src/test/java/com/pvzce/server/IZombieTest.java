package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.Slot;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.PvzceEntity;
import com.pvzce.server.entity.ZombieEntity;
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
 * I, Zombie: the player is the zombies.
 *
 * <p>Four claims, and they are the four things that had to be turned around for the mode to exist
 * at all. The player is seated on the level's own side; a zombie card costs sun and puts a zombie
 * on the lawn; the plant side's own actions are refused; and <em>the win means the player won</em>,
 * which on this level is the opposite of "the plant team won".
 */
class IZombieTest {
    private static final Identifier PLANT_TEAM = PvzceIds.PLANT_TEAM;
    private static final Identifier ZOMBIE_TEAM = PvzceIds.ZOMBIE_TEAM;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static final class CapturingBridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();
        final List<String> messages = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
            if (packet instanceof com.pvzce.common.network.packet.ServerMessageS2C message) {
                messages.add(message.message());
            }
        }
    }

    private static LevelDef puzzle(int number) {
        LevelDef def = BuiltInRegistries.LEVELS.get(
                PvzceIds.id("yard/puzzle/i_zombie_" + number));
        assertNotNull(def, "i_zombie_" + number + " must load");
        return def;
    }

    /** The shipped level, with the mower taken off it so a walk to the house is a walk. */
    private static LevelServer withoutMowers(LevelDef def) {
        return new LevelServer(com.pvzce.testutil.TestLevels.copy(def)
                .mechanics(List.of(new TypedMechanic(PvzceIds.MECHANIC_MOWER,
                        new com.pvzce.api.content.MowerData(
                                java.util.Optional.of(List.of()), List.of()))))
                .build());
    }

    private static void tick(LevelServer level, CapturingBridge bridge, int ticks) {
        for (int i = 0; i < ticks; i++) {
            level.tick(bridge);
            level.flushPending(bridge);
        }
    }

    private static int slotOf(LevelServer level, String cardId) {
        Identifier card = Identifier.withDefaultNamespace(cardId);
        for (Slot slot : level.plantPlayer().slots()) {
            if (card.equals(slot.defId())) {
                return slot.index();
            }
        }
        throw new AssertionError(cardId + " is not on this level's bar");
    }

    private static int countZombies(LevelServer level, Identifier team) {
        int found = 0;
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof ZombieEntity zombie && !zombie.isRemoved() && zombie.isAlive()
                    && zombie.team() == level.team(team)) {
                found++;
            }
        }
        return found;
    }

    private static int countPlants(LevelServer level) {
        int found = 0;
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof PlantEntity plant && !plant.isRemoved()) {
                found++;
            }
        }
        return found;
    }

    /** The player is seated on the level's own team, which is what the whole mode rests on. */
    @Test
    void thePlayerPlaysTheZombieSideOfAPuzzleLevel() {
        for (int number = 1; number <= 5; number++) {
            LevelDef def = puzzle(number);
            LevelServer level = new LevelServer(def);
            assertEquals(ZOMBIE_TEAM, level.humanTeamId(),
                    "i_zombie_" + number + " is played from the zombie side");
            assertEquals(ZOMBIE_TEAM, def.winTeam(),
                    "and that is the side the level says wins");
            assertFalse(def.offersTeamChoice(), "there is one side to play, so nothing to choose");
        }
        // And an ordinary level is unaffected: the seating is the level's answer, not a new default.
        LevelDef yard = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_1"));
        assertEquals(PLANT_TEAM, new LevelServer(yard).humanTeamId(),
                "an adventure level still seats the player on the plant side");
    }

    /** A zombie card costs sun, and spends it on a zombie of the player's own side. */
    @Test
    void aZombieCardCostsSunAndPutsAZombieOnTheLawn() {
        LevelServer level = new LevelServer(puzzle(1));
        CapturingBridge bridge = new CapturingBridge();
        int before = level.team(ZOMBIE_TEAM).resourcesOf(PvzceIds.SUN);
        assertTrue(before > 0, "the level gives the player a budget: " + before);

        int slot = slotOf(level, "basic_zombie");
        assertEquals(50, level.plantPlayer().slot(slot).costSun(), "a basic zombie costs 50");

        assertTrue(level.placeZombie(bridge, slot, 6, 2), "the card is placed");
        level.flushPending(bridge);

        assertEquals(1, countZombies(level, ZOMBIE_TEAM), "there is a zombie on the lawn");
        assertEquals(before - 50, level.team(ZOMBIE_TEAM).resourcesOf(PvzceIds.SUN),
                "and it was paid for out of the budget");
        assertFalse(level.plantPlayer().slot(slot).ready(), "the card went on cooldown");
    }

    /** The other side's cards and actions are refused, because the player is not that side. */
    @Test
    void thePlantSidesOwnActionsAreRefusedOnAPuzzleLevel() {
        LevelServer level = new LevelServer(puzzle(1));
        CapturingBridge bridge = new CapturingBridge();
        // A zombie card may not be played as a plant, and vice versa.
        assertFalse(level.placePlant(bridge, slotOf(level, "basic_zombie"), 4, 2),
                "a zombie card is not a plant card");
        assertTrue(bridge.messages.stream().anyMatch(line -> line.contains("僵尸方")),
                "and the refusal says which side the player is on: " + bridge.messages);
    }

    /**
     * A zombie reaching the house wins <em>for the player</em>.
     *
     * <p>The level's own win team is the zombie team, so this is the case where "the plant side
     * lost" and "the player won" are the same event - and the one the old
     * {@code winner == plant team} rule would have reported as a defeat.
     */
    @Test
    void aZombieReachingTheHouseWinsForThePlayer() {
        LevelServer level = withoutMowers(puzzle(1));
        CapturingBridge bridge = new CapturingBridge();
        ZombieEntity walker = level.spawnZombie(PvzceIds.id("basic_zombie"),
                level.team(ZOMBIE_TEAM), 0.5F, 2);
        assertNotNull(walker);
        level.flushPending(bridge);

        // Far enough to walk from x=0.5 to the house (about 4 seconds) and then hold there for
        // the sixty ticks the reach-the-house check counts before it believes it.
        tick(level, bridge, 500);

        assertEquals(com.pvzce.common.network.packet.GameStateS2C.WON, level.gameState(),
                "the player - who is the zombie side - won");
        assertEquals(ZOMBIE_TEAM, level.winner(), "and the winner is the zombie team");
    }

    /** Out of zombies and out of money is the end of the run, and the player has lost it. */
    @Test
    void runningOutOfZombiesAndSunEndsTheRunAsALoss() {
        LevelDef def = puzzle(1);
        // No budget at all: the level starts with nothing alive and nothing affordable.
        LevelServer level = new LevelServer(com.pvzce.testutil.TestLevels.copy(def)
                .initialSun(0)
                .build());
        CapturingBridge bridge = new CapturingBridge();

        tick(level, bridge, 5);

        assertEquals(com.pvzce.common.network.packet.GameStateS2C.LOST, level.gameState(),
                "a player who can never place another zombie has lost");
        assertEquals(PLANT_TEAM, level.winner(), "which on this board is the plants' win");
    }

    /** The garden is the enemy's: it is on the plant side and it is already there at the start. */
    @Test
    void theShippedLevelsGardenIsPlantedOnThePlantSide() {
        LevelServer level = new LevelServer(puzzle(1));
        CapturingBridge bridge = new CapturingBridge();
        level.flushPending(bridge);

        assertTrue(countPlants(level) >= 5, "the garden is on the lawn: " + countPlants(level));
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof PlantEntity plant) {
                assertEquals(level.team(PLANT_TEAM), plant.team(),
                        "and every plant in it belongs to the plant side");
            }
        }
    }

    /** Eating the whole garden ends the round, and the endless level lays out the next one. */
    @Test
    void theEndlessGardenIsReplantedWhenTheLawnIsCleared() {
        LevelDef def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/puzzle/i_zombie_endless"));
        assertNotNull(def, "the endless puzzle level must load");
        LevelServer level = new LevelServer(def);
        CapturingBridge bridge = new CapturingBridge();
        level.flushPending(bridge);
        int first = countPlants(level);
        assertTrue(first > 0, "round one has a garden: " + first);

        // Eat it: `clearLawn` is what the round turn-over sweeps with, and removing the plants is
        // the state the mechanic is waiting for.
        for (PvzceEntity entity : new ArrayList<>(level.entities())) {
            if (entity instanceof PlantEntity plant) {
                plant.remove();
            }
        }
        tick(level, bridge, 200);

        assertTrue(countPlants(level) > 0,
                "the next round's garden is on the lawn: " + countPlants(level));
        assertEquals(com.pvzce.common.network.packet.GameStateS2C.RUNNING, level.gameState(),
                "and the run is still going");
    }

    /** The cards are real content: each resolves, and each is priced. */
    @Test
    void everyZombieCardResolvesAndIsPriced() {
        for (String id : List.of("basic_zombie", "conehead_zombie", "pole_vaulter_zombie",
                "buckethead_zombie", "football_zombie", "gargantuar")) {
            Identifier card = Identifier.withDefaultNamespace(id);
            SlotResolver.ResolvedCard resolved = SlotResolver.resolve(card).orElse(null);
            assertNotNull(resolved, id + " has a card");
            assertEquals(Slot.Kind.ZOMBIE, resolved.kind(), id + " is a zombie card");
            assertTrue(resolved.costSun() > 0, id + " costs sun");
            assertTrue(SlotResolver.requiresUnlock(card),
                    id + " is earned rather than always available (that is the backpack)");
            assertNotNull(BuiltInRegistries.ZOMBIES.get(card), id + " is a registered zombie");
        }
    }
}
