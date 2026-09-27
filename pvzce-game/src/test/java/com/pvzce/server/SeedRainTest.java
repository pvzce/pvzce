package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.SeedRainData;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.CardDropEntity;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.PvzceEntity;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * It's Raining Seeds: the level's cards arrive as packets on the lawn, and they are free.
 *
 * <p>Two claims, and the second is the one the mini-game rests on. The first is that rain is a
 * clock: nothing before the declared delay, then one packet per interval, never two in one cell.
 * The second is that a packet picked up costs nothing - no sun, no cooldown - which is not this
 * mechanic's code at all but {@code LevelServer.plantHeldCard}'s, and is asserted here because
 * this is the level whose whole economy is that one fact. A version of it that charged for the
 * plant would leave the player with a lawn full of packets and no way to pay for them.
 */
class SeedRainTest {
    private static final Identifier PLANT_TEAM = PvzceIds.PLANT_TEAM;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static final class CapturingBridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
        }
    }

    /** The shipped mini-game's board, with the waves taken out so nothing walks in on the test. */
    private static LevelServer shipped() {
        LevelDef def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/minigame/raining_seeds"));
        assertNotNull(def, "raining_seeds must load");
        return new LevelServer(com.pvzce.testutil.TestLevels.copy(def).waves(List.of()).build());
    }

    /** A board whose rain is the one block given, so a test can pin the clock itself. */
    private static LevelServer withRain(SeedRainData rain) {
        return withRain(rain, 9, 5);
    }

    /**
     * The same board cut down to a given size.
     *
     * <p>"A packet never lands on a packet" only shows up once the lawn runs out of room, and on a
     * nine-by-five board that takes forty-five packets. Two cells and a fast clock reach the
     * interesting case in a second of game time.
     */
    private static LevelServer withRain(SeedRainData rain, int width, int height) {
        LevelDef def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/demo_level"));
        assertNotNull(def, "demo_level must load");
        List<String> cells = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                cells.add(x + "," + y);
            }
        }
        return new LevelServer(com.pvzce.testutil.TestLevels.copy(def)
                .width(width)
                .height(height)
                .scene(java.util.Map.of(PvzceIds.GRASS, cells))
                .waves(List.of())
                .mechanics(List.of(new TypedMechanic(PvzceIds.MECHANIC_SEED_RAIN, rain)))
                .build());
    }

    private static List<CardDropEntity> drops(LevelServer level) {
        List<CardDropEntity> found = new ArrayList<>();
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof CardDropEntity drop && !drop.isRemoved()) {
                found.add(drop);
            }
        }
        return found;
    }

    private static void tick(LevelServer level, CapturingBridge bridge, int ticks) {
        for (int i = 0; i < ticks; i++) {
            level.tick(bridge);
            level.flushPending(bridge);
        }
    }

    private static SeedRainData.Card card(String id, int weight, int maxCount) {
        return new SeedRainData.Card(Identifier.withDefaultNamespace(id), weight, maxCount);
    }

    @Test
    void nothingFallsBeforeTheDeclaredStartDelay() {
        LevelServer level = withRain(new SeedRainData(60, 120,
                List.of(card("pea_shooter", 1, -1))));
        CapturingBridge bridge = new CapturingBridge();

        tick(level, bridge, 119);
        assertTrue(drops(level).isEmpty(), "the lawn is left alone for the declared delay");

        tick(level, bridge, 1);
        assertEquals(1, drops(level).size(), "and then the first packet falls");
    }

    @Test
    void onePacketFallsPerInterval() {
        LevelServer level = withRain(new SeedRainData(60, 0,
                List.of(card("pea_shooter", 1, -1))));
        CapturingBridge bridge = new CapturingBridge();

        tick(level, bridge, 1);
        assertEquals(1, drops(level).size(), "the first packet falls on the first tick");

        tick(level, bridge, 59);
        assertEquals(1, drops(level).size(), "and the next one waits its turn");

        tick(level, bridge, 1);
        assertEquals(2, drops(level).size(), "one interval later there are two");
    }

    /** Two packets in one cell are one packet: the click takes the top one and hides the other. */
    @Test
    void aPacketNeverLandsOnAnotherPacket() {
        LevelServer level = withRain(new SeedRainData(30, 0,
                List.of(card("pea_shooter", 1, -1))), 2, 1);
        CapturingBridge bridge = new CapturingBridge();

        // Two cells and ten draws: the rain has to start skipping rather than stacking, and it
        // still has to keep raining rather than give up on the first full board.
        tick(level, bridge, 300);

        List<CardDropEntity> found = drops(level);
        long distinct = found.stream()
                .map(drop -> drop.gridX() + "," + drop.gridY())
                .distinct()
                .count();
        assertEquals(found.size(), distinct,
                "every packet has a cell to itself: " + found.size() + " packets, "
                        + distinct + " cells");
        assertEquals(2, found.size(), "both cells of the little lawn are taken");
    }

    /** A card at its cap leaves the pool, and a pool where every card is capped stops raining. */
    @Test
    void aCappedPoolStopsRainingWhenItRunsOut() {
        LevelServer level = withRain(new SeedRainData(30, 0,
                List.of(card("pea_shooter", 1, 1))));
        CapturingBridge bridge = new CapturingBridge();

        tick(level, bridge, 300);

        assertEquals(1, drops(level).size(), "only the one capped packet ever fell");
    }

    /**
     * The mini-game's whole economy: a packet is planted for nothing.
     *
     * <p>The shipped level starts the player at zero sun and never drops any, so a version of
     * {@code plantHeldCard} that charged would leave every packet unplantable - and the level
     * would be unwinnable rather than merely hard.
     */
    @Test
    void aFallenPacketIsPickedUpAndPlantedWithoutSpendingSun() {
        LevelServer level = shipped();
        CapturingBridge bridge = new CapturingBridge();
        assertEquals(0, level.team(PLANT_TEAM).resourcesOf(PvzceIds.SUN),
                "the level gives the player no sun at all");

        tick(level, bridge, 600);
        List<CardDropEntity> found = drops(level);
        assertTrue(!found.isEmpty(), "the shipped level's rain produced a packet");
        CardDropEntity packet = found.get(0);

        assertTrue(level.pickUpCardDrop(bridge, packet.id()), "the packet is picked up");
        level.flushPending(bridge);
        assertEquals(packet.card(), level.heldCard(), "and the card is in the player's hand");

        int cell = firstFreeCell(level);
        assertTrue(level.plantHeldCard(bridge, cell / 100, cell % 100),
                "the held card is planted at " + (cell / 100) + "," + (cell % 100));
        level.flushPending(bridge);

        assertEquals(0, level.team(PLANT_TEAM).resourcesOf(PvzceIds.SUN),
                "and it cost nothing: the wallet is still empty");
        assertTrue(planted(level, packet.card()), "the plant is on the lawn");
    }

    /** The first cell with nothing in it, packed as x * 100 + y so the test reads as one value. */
    private static int firstFreeCell(LevelServer level) {
        for (int y = 0; y < level.height(); y++) {
            for (int x = 0; x < level.width(); x++) {
                if (level.plantAt(x, y) == null) {
                    return x * 100 + y;
                }
            }
        }
        throw new AssertionError("the lawn is full");
    }

    private static boolean planted(LevelServer level, Identifier plantId) {
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof PlantEntity plant && plant.def().id().equals(plantId)) {
                return true;
            }
        }
        return false;
    }

    /** A card id nothing knows is an authoring mistake, and the validator says so. */
    @Test
    void anUnknownCardIsReported() {
        SeedRainData rain = new SeedRainData(60, 0,
                List.of(card("no_such_plant", 1, SeedRainData.Card.UNLIMITED)));
        List<String> errors = new com.pvzce.common.level.mechanic.SeedRainMechanic()
                .validate(BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/demo_level")), rain);
        assertTrue(errors.stream().anyMatch(message -> message.contains("no_such_plant")),
                "the unknown card is named: " + errors);
    }

    /** The shipped level's own rain: what it drops, and how often, is the level file's answer. */
    @Test
    void theShippedLevelRainsItsOwnPool() {
        LevelServer level = shipped();
        CapturingBridge bridge = new CapturingBridge();

        tick(level, bridge, 600);

        List<CardDropEntity> found = drops(level);
        assertTrue(found.size() >= 2, "two packets in ten seconds: " + found.size());
        for (CardDropEntity packet : found) {
            assertTrue(com.pvzce.common.core.SlotResolver.resolve(packet.card()).isPresent(),
                    "every packet holds a card the game can plant: " + packet.card());
        }
    }
}
