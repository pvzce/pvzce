package com.pvzce.server;

import com.pvzce.api.content.LevelBelt;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.PlacementZone;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.capability.plant.BowlCapability;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.EffectEventS2C;
import com.pvzce.common.network.packet.EntitySpawnS2C;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.packet.SlotInfo;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.PvzceEntity;
import com.pvzce.server.entity.ResourceDropEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.ConveyorBelt;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mini-game machinery behind 1-5: the conveyor belt, the restricted plantable area
 * and the bowling Wall-nut - plus the potato mine bug they were found next to.
 */
class MiniGameTest {
    private static final Identifier PLANT_TEAM = Identifier.withDefaultNamespace("plant_team");
    private static final Identifier ZOMBIE_TEAM = Identifier.withDefaultNamespace("zombie_team");
    private static final Identifier BASIC_ZOMBIE = Identifier.withDefaultNamespace("basic_zombie");
    private static final Identifier BOWLING_NUT = Identifier.withDefaultNamespace("bowling_nut");
    private static final Identifier POTATO_MINE = Identifier.withDefaultNamespace("potato_mine");

    private static LevelDef oneFive;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        oneFive = BuiltInRegistries.LEVELS.get(Identifier.withDefaultNamespace("yard/adventure/1_5"));
        assertNotNull(oneFive, "the shipped 1-5 must load");
    }

    /**
     * The reported bug: a zombie walked onto a potato mine, the mine exploded, and the
     * zombie kept walking.
     *
     * <p>The mine triggered on {@code trigger_range} (0.6) and damaged within
     * {@code radius} (0.55). A zombie moves 0.003 cells per tick, so the first tick inside
     * the trigger zone leaves it at ~0.599 - outside the blast. End to end, so the numbers
     * that matter are the shipped ones.
     */
    @Test
    void potatoMineKillsTheZombieThatTripsIt() {
        LevelServer level = ordinaryLevel();
        CapturingBridge bridge = new CapturingBridge();
        PlantDef mine = BuiltInRegistries.PLANTS.get(POTATO_MINE);
        PlantEntity planted = level.spawnPlant(mine, level.team(PLANT_TEAM), 3, 2);
        level.flushPending(bridge);
        // Arm it first: a mine still growing is just a plant, and the zombie would eat it.
        tick(level, bridge, 901);

        level.spawnZombie(BASIC_ZOMBIE, level.team(ZOMBIE_TEAM), 6.0F, 2);
        level.flushPending(bridge);
        tick(level, bridge, 1200);

        assertEquals(0, level.aliveZombieCount(), "the zombie that stepped on the mine must die");
        assertTrue(planted.isRemoved(), "the mine is spent");
    }

    /** A level with no waves, so a test drives the simulation instead of the wave clock. */
    private static LevelServer ordinaryLevel() {
        LevelDef demo = BuiltInRegistries.LEVELS.get(Identifier.withDefaultNamespace("yard/adventure/demo_level"));
        LevelDef noWaves = new LevelDef(
                demo.id(), demo.name(), demo.description(), demo.width(), demo.height(),
                demo.scene(), demo.teams(), demo.winTeam(), demo.rules(), demo.envVars(),
                List.of(), demo.waveIntervalEndMultiplier(), demo.slots(), demo.unlockResources(),
                demo.initialSun(), LevelDef.LevelMusicDef.DEFAULT, List.of());
        return new LevelServer(noWaves);
    }

    /** 1-5's own definition with a belt, minus the waves: the belt is what is under test. */
    private static LevelServer beltLevel() {
        return new LevelServer(beltLevel(oneFive, oneFive.height()));
    }

    /** As above, on a board of {@code rows} rows (a short board makes lane picks predictable). */
    private static LevelServer beltLevel(int rows) {
        return new LevelServer(beltLevel(oneFive, rows));
    }

    private static LevelDef beltLevel(LevelDef source, int rows) {
        List<String> grass = new ArrayList<>();
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < source.width(); x++) {
                grass.add(x + "," + y);
            }
        }
        return new LevelDef(source.id(), source.name(), source.description(), source.width(), rows,
                Map.of(Identifier.withDefaultNamespace("grass"), grass), source.teams(), source.winTeam(),
                source.rules(), source.envVars(), List.of(), source.waveIntervalEndMultiplier(),
                List.of(), source.unlockResources(), 0, LevelDef.LevelMusicDef.DEFAULT, List.of(),
                6, com.pvzce.api.content.LevelRewards.NONE, source.unlock(),
                Optional.of(new LevelBelt(300, 6, 2,
                        List.of(new LevelBelt.BeltCard(BOWLING_NUT, 1)))),
                new PlacementZone(0, 3, 0, Integer.MAX_VALUE),
                com.pvzce.api.content.LevelDialogue.EMPTY);
    }

    private static void tick(LevelServer level, CapturingBridge bridge, int ticks) {
        for (int i = 0; i < ticks && level.gameState().equals(GameStateS2C.RUNNING); i++) {
            level.tick(bridge);
        }
    }

    @Test
    void theShippedLevelIsABeltLevelOnTheLeftFourColumns() {
        assertTrue(oneFive.hasConveyor(), "1-5 is a conveyor level");
        assertTrue(oneFive.slots().isEmpty(), "its card bar is the belt, not a deck");
        assertTrue(oneFive.placementZone().contains(3, 0));
        assertFalse(oneFive.placementZone().contains(4, 0), "the red line is at column 4");
        assertTrue(oneFive.rewards().firstClear().stream()
                        .anyMatch(reward -> reward.id()
                                .filter(POTATO_MINE::equals).isPresent()),
                "clearing 1-5 hands over the potato mine");
    }

    /**
     * The shipped 1-5 throws the original's horde, and it is a level that can actually be
     * finished.
     *
     * <p>Driven with a perfect player (everything that reaches the lawn is removed), so the
     * run is about the wave file and not about how good the nut is: how many zombies the
     * level sends, and that the last wave really does end the level instead of leaving it
     * running forever.
     */
    @Test
    void theShippedWavesThrowAWholeHordeAndTheLevelCanBeWon() {
        LevelServer level = new LevelServer(oneFive);
        CapturingBridge bridge = new CapturingBridge();
        for (int i = 0; i < 30_000 && level.gameState().equals(GameStateS2C.RUNNING); i++) {
            level.tick(bridge);
            for (int row = 0; row < level.height(); row++) {
                for (ZombieEntity zombie : level.zombiesInRow(row)) {
                    zombie.remove();
                }
            }
        }
        int spawned = 0;
        for (PvzcePacket packet : bridge.packets) {
            if (packet instanceof EntitySpawnS2C spawn
                    && "zombie".equals(spawn.entityKind())) {
                spawned++;
            }
        }
        assertTrue(spawned >= 200, "a wall-nut bowling level is a horde, not a handful; got " + spawned);
        assertEquals(GameStateS2C.WON, level.gameState(), "the last wave has to end the level");
    }

    @Test
    void beltDeliversCardsUntilItIsFullAndSpendsThem() {
        LevelServer level = beltLevel();
        CapturingBridge bridge = new CapturingBridge();
        ConveyorBelt belt = level.conveyorBelt();
        assertNotNull(belt);
        assertEquals(2, belt.cards().size(), "the level starts with two cards waiting");

        tick(level, bridge, 300);
        assertEquals(3, belt.cards().size(), "one delivery per interval");

        tick(level, bridge, 300 * 8);
        assertEquals(6, belt.cards().size(), "a full belt stops delivering");

        int card = level.slotInfos().get(0).index();
        assertEquals(SlotInfo.NO_PRICE, level.slotInfos().get(0).costSun(), "belt cards have no price");
        int sun = level.team(PLANT_TEAM).resourcesOf(PvzceIds.SUN);
        assertTrue(level.placePlant(bridge, card, 0, 2), "a free card can be planted");
        assertEquals(sun, level.team(PLANT_TEAM).resourcesOf(PvzceIds.SUN),
                "a belt card costs no sun");
        assertEquals(5, belt.cards().size(), "planting spends the card off the belt");
        assertFalse(level.placePlant(bridge, card, 1, 2), "a spent card cannot be spent twice");

        tick(level, bridge, 300);
        assertEquals(6, belt.cards().size(), "and the belt refills");
    }

    @Test
    void thePlacementZoneIsEnforcedByTheOnePlacementCheck() {
        LevelServer level = beltLevel();
        PlantDef nut = BuiltInRegistries.PLANTS.get(BOWLING_NUT);
        assertTrue(level.canPlacePlant(nut, 3, 0));
        assertFalse(level.canPlacePlant(nut, 4, 0), "outside the red line");
        assertFalse(level.canPlacePlant(nut, 8, 4), "and everywhere to its right");
    }

    /** Leaving and resuming a belt level must not re-roll the cards it was holding. */
    @Test
    void theBeltIsPartOfTheSave() {
        LevelServer level = beltLevel();
        CapturingBridge bridge = new CapturingBridge();
        tick(level, bridge, 300);
        assertTrue(level.placePlant(bridge, level.slotInfos().get(0).index(), 0, 2));
        int cards = level.conveyorBelt().cards().size();

        LevelServer restored = beltLevel();
        restored.restore(level.save());

        assertEquals(cards, restored.conveyorBelt().cards().size());
        assertEquals(level.slotInfos().size(), restored.slotInfos().size(),
                "the card bar is rebuilt from the restored belt");
    }

    @Test
    void aBowledNutRollsForwardAndKillsWhatItHits() {
        LevelServer level = beltLevel();
        CapturingBridge bridge = new CapturingBridge();
        ZombieEntity zombie = spawnZombie(level, bridge, 5.0F, 2);
        int card = level.slotInfos().get(0).index();
        assertTrue(level.placePlant(bridge, card, 0, 2));

        PlantEntity nut = level.entities().stream()
                .filter(entity -> entity instanceof PlantEntity plant && plant.defId().equals(BOWLING_NUT))
                .map(PlantEntity.class::cast)
                .findFirst().orElseThrow();
        // A nut is not furniture: a zombie must not be able to stop and eat the thing that
        // is rolling through its cell.
        assertFalse(nut.occupiesCell());
        assertNull(level.plantAt(0, 2), "the cell it was planted in is already empty");

        // Every tick of the way, including the one it connects on: a bowling ball is knocked
        // into the next lane by a hit, never back toward the house.
        BowlCapability bowl = nut.capability(BowlCapability.class);
        float lastX = nut.cellX();
        for (int i = 0; i < 200 && bowl.hits() == 0; i++) {
            level.tick(bridge);
            assertTrue(nut.cellX() >= lastX, "a bowling nut never rolls backwards");
            lastX = nut.cellX();
        }
        assertTrue(zombie.isRemoved(), "650 damage kills a 200-health zombie");
        assertEquals(1, bowl.hits());

        float xAtHit = nut.cellX();
        tick(level, bridge, 10);
        assertTrue(nut.cellX() > xAtHit, "and it keeps going forward after the hit");
        assertNotEquals(0F, bowl.laneDrift(), "the hit knocked it into another lane");
    }

    /**
     * The hit ladder the mini-game is built on: one nut per ordinary zombie, two for a
     * conehead, three for a buckethead.
     *
     * <p>This is where the armor model shows through: a piece absorbs until it breaks and the
     * breaking hit does not carry into the body, so the damage has to sit in a window
     * (see {@link BowlCapability#DEFAULT_DAMAGE}) rather than being freely chosen.
     */
    @Test
    void theBowlingNutBreaksArmorInTheOriginalsHitCounts() {
        LevelServer level = beltLevel();
        CapturingBridge bridge = new CapturingBridge();
        assertEquals(1, hitsToKill(level, bridge, "basic_zombie"));
        assertEquals(2, hitsToKill(level, bridge, "conehead_zombie"));
        assertEquals(3, hitsToKill(level, bridge, "buckethead_zombie"));
    }

    private static int hitsToKill(LevelServer level, CapturingBridge bridge, String zombieId) {
        Identifier id = Identifier.withDefaultNamespace(zombieId);
        level.spawnZombie(id, level.team(ZOMBIE_TEAM), 5.0F, 2);
        level.flushPending(bridge);
        ZombieEntity zombie = level.zombiesInRow(2).stream()
                .filter(z -> z.defId().equals(id))
                .reduce((a, b) -> b)
                .orElseThrow();
        int hits = 0;
        while (!zombie.isRemoved() && hits < 12) {
            zombie.damageImpact(BowlCapability.DEFAULT_DAMAGE, level);
            hits++;
        }
        return hits;
    }

    /**
     * A hit knocks the nut into the next lane, where it carries on forward and can hit again.
     *
     * <p>Run on a two-row board, where the bias is not a coin flip: a nut in the low row is
     * always knocked toward the high one, so the second zombie - waiting one row up and
     * further along - is hit deterministically.
     */
    @Test
    void aHitChangesLaneAndPaysMoreForTheSecondZombie() {
        LevelServer level = beltLevel(2);
        CapturingBridge bridge = new CapturingBridge();
        ZombieEntity first = spawnZombie(level, bridge, 4.5F, 0);
        ZombieEntity second = spawnZombie(level, bridge, 5.5F, 1);

        int card = level.slotInfos().get(0).index();
        assertTrue(level.placePlant(bridge, card, 0, 0));
        tick(level, bridge, 600);

        assertTrue(first.isRemoved());
        assertTrue(second.isRemoved(), "the lane change carried it into the next row");
        int coins = 0;
        for (var entity : level.entities()) {
            if (entity instanceof ResourceDropEntity) {
                coins++;
            }
        }
        assertEquals(1, coins, "the second zombie a nut hits drops one silver coin");
    }

    /**
     * The pickup flash belongs to the resource: a sun pops, a coin does not.
     *
     * <p>End to end through the collect path, because that is where the two used to share one
     * particle: the sun's flash was sized for a sun, and drawing it for a coin washed half the
     * lawn in yellow every time one was picked up.
     */
    @Test
    void aCollectedSunSparklesAndACollectedCoinDoesNot() {
        LevelServer level = ordinaryLevel();
        CapturingBridge bridge = new CapturingBridge();
        level.spawnResource(PvzceIds.SUN, 25, 5, 2, level.team(PLANT_TEAM));
        level.flushPending(bridge);
        int sun = lastDropId(level, PvzceIds.SUN);
        assertTrue(level.collectResource(bridge, sun), "the level's sun card collects sun");
        assertEquals("pvzce:lantern_shine", lastEffect(level, bridge));

        level.spawnResource(PvzceIds.COIN_SILVER, 10, 4, 2, level.team(PLANT_TEAM));
        level.flushPending(bridge);
        int coin = lastDropId(level, PvzceIds.COIN_SILVER);
        assertTrue(level.collectResource(bridge, coin), "currency needs no card");
        assertEquals("", lastEffect(level, bridge), "a coin must not flash");
    }

    private static int lastDropId(LevelServer level, Identifier resourceId) {
        return level.entities().stream()
                .filter(entity -> entity instanceof ResourceDropEntity drop
                        && drop.defId().equals(resourceId) && !drop.isRemoved())
                .mapToInt(PvzceEntity::id)
                .max()
                .orElseThrow();
    }

    /** The particle id of the most recent effect packet. */
    private static String lastEffect(LevelServer level, CapturingBridge bridge) {
        for (int i = bridge.packets.size() - 1; i >= 0; i--) {
            if (bridge.packets.get(i) instanceof EffectEventS2C effect) {
                return effect.particle();
            }
        }
        return null;
    }

    private static ZombieEntity spawnZombie(LevelServer level, CapturingBridge bridge, float x, int row) {
        level.spawnZombie(BASIC_ZOMBIE, level.team(ZOMBIE_TEAM), x, row);
        level.flushPending(bridge);
        return level.zombiesInRow(row).stream()
                .filter(z -> z.defId().equals(BASIC_ZOMBIE))
                .reduce((a, b) -> b)
                .orElseThrow();
    }

    private static final class CapturingBridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
        }
    }
}
