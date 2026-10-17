package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
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
        assertTrue(level.plantPlayer().slot(slot).ready(), "original zombie cards have no recharge");
        assertTrue(level.placeZombie(bridge, slot, 6, 1), "the same card can immediately be played again");
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

    @Test
    void allFiveBrainsAreRequiredAndDuplicateLanesDoNotScoreAgain() {
        LevelServer level = new LevelServer(puzzle(1));
        CapturingBridge bridge = new CapturingBridge();
        for (int row = 0; row < 4; row++) {
            level.spawnZombie(PvzceIds.id("basic_zombie"), level.team(ZOMBIE_TEAM), -0.41F, row);
        }
        tick(level, bridge, 71);
        assertEquals("running", level.gameState(), "four brains do not win the puzzle");
        level.spawnZombie(PvzceIds.id("basic_zombie"), level.team(ZOMBIE_TEAM), -0.41F, 0);
        tick(level, bridge, 71);
        assertEquals("running", level.gameState(), "an eaten lane cannot replace the fifth brain");
        level.spawnZombie(PvzceIds.id("basic_zombie"), level.team(ZOMBIE_TEAM), -0.41F, 4);
        tick(level, bridge, 71);
        assertEquals("won", level.gameState());
        assertEquals(ZOMBIE_TEAM, level.winner());
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

    private static LevelDef endless() {
        return BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/puzzle/i_zombie_endless"));
    }

    @Test
    void endlessContinuesPastFiveBoardsAndCarriesSunWithoutAHandout() {
        LevelServer level = new LevelServer(endless());
        CapturingBridge bridge = new CapturingBridge();
        for (int round = 0; round < 6; round++) {
            assertEquals(25, countPlants(level));
            assertTrue(level.entities().stream().filter(e -> e instanceof PlantEntity)
                    .allMatch(e -> e.gridX() < 5));
            // Clear objectives while leaving the plants in place: brains, not garden emptiness,
            // decide when a board ends. Walkers enter through the actual entity tick path.
            for (int row = 0; row < 5; row++)
                level.spawnZombie(PvzceIds.id("basic_zombie"), level.team(ZOMBIE_TEAM), -0.41F, row);
            tick(level, bridge, 71);
            level.spawnProducedResource(PvzceIds.SUN, 25, 7, 2, level.team(ZOMBIE_TEAM));
            level.flushPending(bridge);
            tick(level, bridge, 90);
            assertTrue(level.entities().stream().anyMatch(e -> e instanceof com.pvzce.server.entity.ResourceDropEntity),
                    "loose sun survives the board change");
            assertEquals("running", level.gameState());
            assertEquals(150, level.team(ZOMBIE_TEAM).resourcesOf(PvzceIds.SUN));
        }
        var save = level.save().getCompound("BrainGarden");
        assertEquals(6, save.getInt("Round"));
        assertEquals(List.of(50, 75, 75, 125, 125, 125, 150, 175, 350),
                level.plantPlayer().slots().stream().map(Slot::costSun).toList(),
                "the original puzzle prices are independent of other modes' card prices");
    }

    @Test
    void theSixPuzzlesUseOriginalMusicBudgetsCardsAndNoMowers() {
        List<List<String>> cards = List.of(
                List.of("basic_zombie", "buckethead_zombie", "football_zombie"),
                List.of("basic_zombie", "door_zombie", "buckethead_zombie"),
                List.of("basic_zombie", "buckethead_zombie", "miner_zombie"),
                List.of("basic_zombie", "buckethead_zombie", "i_zombie_ladder"),
                List.of("basic_zombie", "buckethead_zombie", "bungee_zombie", "balloon_zombie"));
        for (int number = 1; number <= 6; number++) {
            int columns = number == 6 ? 5 : 4;
            LevelDef def = number == 6 ? endless() : puzzle(number);
            LevelServer level = new LevelServer(def);
            CapturingBridge bridge = new CapturingBridge();
            tick(level, bridge, 1);
            assertEquals(150, def.initialSun());
            assertTrue(bridge.packets.stream().anyMatch(p -> p instanceof com.pvzce.common.network.packet.MusicEventS2C music
                    && music.event().equals("pvzce:music/cerebrawl")));
            if (number <= 5) assertEquals(cards.get(number - 1).stream().map(PvzceIds::id).toList(), def.slots());
            assertEquals(number == 6 ? 25 : 20, countPlants(level));
            var rig = level.mechanicStateOrNull(PvzceIds.MECHANIC_MOWER,
                    com.pvzce.common.level.mechanic.MowerMechanic.Rig.class);
            assertEquals(0, rig.readyCount());
            assertTrue(level.entities().stream().filter(e -> e instanceof PlantEntity)
                    .allMatch(e -> e.gridX() < columns));
        }
    }

    @Test
    void refusedPlacementsDoNotChargeAndBungeesUseTheOppositeSide() {
        LevelServer level = new LevelServer(puzzle(5));
        CapturingBridge bridge = new CapturingBridge();
        int normal = slotOf(level, "basic_zombie"), bungee = slotOf(level, "bungee_zombie");
        assertFalse(level.placeZombie(bridge, normal, 3, 2));
        assertFalse(level.placeZombie(bridge, bungee, 4, 2));
        assertEquals(150, level.team(ZOMBIE_TEAM).resourcesOf(PvzceIds.SUN));
        assertTrue(level.plantPlayer().slot(normal).ready());
        assertTrue(level.plantPlayer().slot(bungee).ready());
        assertTrue(level.placeZombie(bridge, bungee, 3, 2));
        level.flushPending(bridge);
        tick(level, bridge, 1);
        ZombieEntity thief = (ZombieEntity) level.entities().stream().filter(e -> e instanceof ZombieEntity).findFirst().orElseThrow();
        assertEquals(3, thief.gridX(), "the player chooses the theft column");
    }

    @Test
    void sunflowersPayForDamageAndTheirSunCanBeCollectedByTheZombiePlayer() {
        LevelServer level = new LevelServer(puzzle(1));
        CapturingBridge bridge = new CapturingBridge();
        tick(level, bridge, 2000);
        assertTrue(level.entities().stream().noneMatch(e -> e instanceof com.pvzce.server.entity.ResourceDropEntity),
                "cardboard sunflowers must not produce free income while the player waits");
        PlantEntity flower = (PlantEntity) level.entities().stream().filter(e -> e instanceof PlantEntity
                && e.defId().equals(PvzceIds.id("sunflower"))).findFirst().orElseThrow();
        flower.damageFrom(100);
        level.flushPending(bridge);
        List<com.pvzce.server.entity.ResourceDropEntity> drops = level.entities().stream()
                .filter(e -> e instanceof com.pvzce.server.entity.ResourceDropEntity)
                .map(e -> (com.pvzce.server.entity.ResourceDropEntity) e).toList();
        assertEquals(50, drops.stream().mapToInt(com.pvzce.server.entity.ResourceDropEntity::amount).sum());
        for (var drop : drops) assertTrue(level.collectResource(bridge, drop.id()));
        assertEquals(200, level.team(ZOMBIE_TEAM).resourcesOf(PvzceIds.SUN));
        flower.damageFrom(1000);
        level.flushPending(bridge);
        for (var entity : level.entities())
            if (entity instanceof com.pvzce.server.entity.ResourceDropEntity drop && !drop.isRemoved())
                assertTrue(level.collectResource(bridge, drop.id()));
        assertEquals(350, level.team(ZOMBIE_TEAM).resourcesOf(PvzceIds.SUN), "a full flower yields 200 sun");
    }

    @Test
    void uncollectedSunPreventsPrematureFailure() {
        LevelServer level = new LevelServer(com.pvzce.testutil.TestLevels.copy(puzzle(1)).initialSun(0).build());
        CapturingBridge bridge = new CapturingBridge();
        PlantEntity flower = (PlantEntity) level.entities().stream().filter(e -> e instanceof PlantEntity
                && e.defId().equals(PvzceIds.id("sunflower"))).findFirst().orElseThrow();
        flower.damageFrom(100);
        level.flushPending(bridge);
        tick(level, bridge, 1);
        assertEquals("running", level.gameState(), "the 50 sun on the lawn can still buy a zombie");
    }

    @Test
    void savedBrainsAndPartialChewingResumeWithoutDuplicatingTheGarden() {
        LevelServer level = new LevelServer(endless());
        CapturingBridge bridge = new CapturingBridge();
        level.spawnZombie(PvzceIds.id("basic_zombie"), level.team(ZOMBIE_TEAM), -0.41F, 0);
        tick(level, bridge, 71);
        level.spawnZombie(PvzceIds.id("basic_zombie"), level.team(ZOMBIE_TEAM), -0.41F, 1);
        tick(level, bridge, 30);
        LevelServer resumed = new LevelServer(endless());
        resumed.restore(level.save());
        assertEquals(25, countPlants(resumed));
        assertEquals(1, resumed.save().getCompound("BrainGarden").getInt("EatenRows"));
        tick(resumed, bridge, 40);
        assertEquals(3, resumed.save().getCompound("BrainGarden").getInt("EatenRows"));
        assertEquals("running", resumed.gameState());
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
