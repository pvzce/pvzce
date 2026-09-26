package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.ScaryPotterData;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.level.mechanic.ScaryPotterMechanic;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.packet.ServerMessageS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.ResourceDropEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 4-5, the original's Scary Potter level: rounds of vases, and the only adventure level with no
 * waves at all.
 *
 * <p>Everything a player meets here is asserted, because every one of them is a different
 * mechanism wearing the same picture:
 *
 * <ul>
 *   <li>the pots are <em>scattered</em> over the right-hand columns when the round starts, so the
 *       board is not a fixed layout - what is fixed is the count, the columns and the contents;</li>
 *   <li>a hammer swing opens one, and what comes out is a card or a zombie depending on which
 *       kind of pot it was;</li>
 *   <li>the round is over when the lawn is empty of both, and the level is won on the last one -
 *       which is a win the wave director cannot report, because this level has no waves to
 *       release.</li>
 * </ul>
 */
class ScaryPotterTest {
    private static final Identifier PLANT_TEAM = PvzceIds.PLANT_TEAM;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static final class Bridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
        }

        List<String> messages() {
            List<String> lines = new ArrayList<>();
            for (PvzcePacket packet : packets) {
                if (packet instanceof ServerMessageS2C message) {
                    lines.add(message.message());
                }
            }
            return lines;
        }
    }

    private static LevelDef level() {
        LevelDef def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/4_5"));
        assertNotNull(def, "4-5 is the vase level");
        return def;
    }

    private static ScaryPotterData data(LevelDef def) {
        for (TypedMechanic mechanic : def.mechanics()) {
            if (mechanic.value() instanceof ScaryPotterData pots) {
                return pots;
            }
        }
        throw new AssertionError("4-5 must declare its pots");
    }

    /** The bar's own index for the vase tool, put there by this test. */
    private static int vaseSlot(LevelServer level) {
        for (var slot : level.plantPlayer().slots()) {
            if (slot.defId().equals(PvzceIds.VASE)) {
                return slot.index();
            }
        }
        var slots = new java.util.ArrayList<>(level.plantPlayer().slots());
        int index = 0;
        for (var slot : slots) {
            index = Math.max(index, slot.index() + 1);
        }
        slots.add(new com.pvzce.common.core.Slot(index,
                com.pvzce.common.core.SlotResolver.resolve(PvzceIds.VASE).orElseThrow().kind(),
                PvzceIds.VASE, 0, 0, com.pvzce.common.core.Slot.UNLIMITED_USES, 0));
        level.plantPlayer().replaceSlots(slots);
        return index;
    }

    /** Every cell a pot is standing in, right to left and top to bottom. */
    private static List<int[]> pots(LevelServer level) {
        List<int[]> found = new ArrayList<>();
        for (int x = level.width() - 1; x >= 0; x--) {
            for (int y = 0; y < level.height(); y++) {
                if (ScaryPotterMechanic.isPot(level, x, y)) {
                    found.add(new int[]{x, y});
                }
            }
        }
        return found;
    }

    /**
     * One swing at a cell: what a bare click on a pot sends.
     *
     * <p>The mallet used to be a tool this level granted ({@code pvzce:tool} with
     * {@code default: true}) and these tests drove {@code useGrantedTool}. It is not one any
     * more: the swing is the client's own animation over the clicked cell, so the request that
     * reaches the server names the cell and nothing else.
     */
    private static boolean swingAt(LevelServer level, Bridge bridge, int x, int y) {
        return level.smashContainer(bridge, x, y);
    }

    @Test
    void theVaseLevelIsThreeRoundsWithNoWavesAtAll() {
        LevelDef def = level();
        assertTrue(def.waves().isEmpty(), "nothing walks in on this level");
        ScaryPotterData pots = data(def);
        assertEquals(3, pots.rounds().size(), "the original's adventure vase level is three rounds");
        assertEquals(List.of(6, 5, 4), pots.rounds().stream()
                        .map(ScaryPotterData.Round::fromColumn).toList(),
                "each round reaches one column further towards the house");
        assertEquals(List.of(15, 20, 25), pots.rounds().stream()
                        .map(ScaryPotterData.Round::potCount).toList(),
                "and stands up more pots than the round before it");
        assertEquals(List.of(0, 2, 3), pots.rounds().stream()
                        .map(ScaryPotterData.Round::leafCount).toList(),
                "the original turns a few of each round's seed pots green");
        assertTrue(pots.validate(def.width(), def.height()).isEmpty(),
                "the block has to be well formed: " + pots.validate(def.width(), def.height()));

        // Two cards in the bar, and only one of them is a plant. The sun card is part of *every*
        // level's bar for the same reason it is part of this one: sun is not collectable without it
        // (`collectible_without_card: false`), so a level that hands out sun - and this one hands
        // out all of it - would be handing out dead drops and drawing no bank.
        assertEquals(List.of("pvzce:sun", "pvzce:cherry_bomb"),
                def.slots().stream().map(Identifier::toString).toList(),
                "the player is handed the sun card and one plant: a cherry bomb");
        assertEquals(0, def.initialSun(), "and no sun falls from the sky");
        int sunPots = 0;
        for (ScaryPotterData.Round round : pots.rounds()) {
            for (ScaryPotterData.Pot pot : round.pots()) {
                if (pot.isResource()) {
                    sunPots += pot.count();
                }
            }
        }
        assertTrue(sunPots > 0,
                "so the pots are the economy: some of them hold the sun the one plant is paid for"
                        + " with, and 150 of it is a cherry bomb");
        assertTrue(LevelMechanics.dataOf(def, PvzceIds.MECHANIC_MOWER,
                        com.pvzce.api.content.MowerData.class).orElseThrow()
                        .none(def.height()),
                "the original's Scary Potter has no mowers: a leak is not the way this level ends");
    }

    @Test
    void theFirstRoundStandsItsPotsUpWhereTheRoundSays() {
        LevelServer level = new LevelServer(level());
        List<int[]> standing = pots(level);
        assertEquals(15, standing.size(), "round one's pot count");
        for (int[] cell : standing) {
            assertTrue(cell[0] >= 6, "every pot of round one is in column six or further in, was "
                    + cell[0]);
            assertNotNull(ScaryPotterMechanic.contentsAt(level, cell[0], cell[1]),
                    "and has something written behind it");
        }
        int leaves = 0;
        for (int[] cell : standing) {
            if (PvzceIds.POT_LEAF.equals(level.sceneIdAt(cell[0], cell[1]))) {
                leaves++;
            }
        }
        assertEquals(0, leaves, "round one shows no leaf pots: every one of them is a question mark");
    }

    @Test
    void breakingAPlantPotHandsThePlayerItsCard() {
        LevelServer level = new LevelServer(level());
        Bridge bridge = new Bridge();
        int[] plantPot = null;
        for (int[] cell : pots(level)) {
            ScaryPotterMechanic.Contents contents =
                    ScaryPotterMechanic.contentsAt(level, cell[0], cell[1]);
            if (contents != null && contents.isPlant()) {
                plantPot = cell;
                break;
            }
        }
        assertNotNull(plantPot, "round one has to hold some plants");
        Identifier card = ScaryPotterMechanic.contentsAt(level, plantPot[0], plantPot[1]).id();

        assertTrue(swingAt(level, bridge, plantPot[0], plantPot[1]), "the swing opens the pot");
        assertFalse(ScaryPotterMechanic.isPot(level, plantPot[0], plantPot[1]),
                "and the cell is lawn again");
        assertNull(ScaryPotterMechanic.contentsAt(level, plantPot[0], plantPot[1]),
                "with nothing left behind it");
        assertNotNull(BuiltInRegistries.PLANTS.get(card), "the card it held exists: " + card);
        assertNotNull(level.cardSource(), "and this level has somewhere to put it");
        assertTrue(bridge.messages().stream().anyMatch(line -> line.contains("花瓶")),
                "the player is told a card came out: " + bridge.messages());
    }

    @Test
    void breakingAZombiePotPutsTheZombieOnTheLawn() {
        LevelDef def = level();
        LevelServer level = new LevelServer(def);
        Bridge bridge = new Bridge();

        int[] zombiePot = null;
        Identifier zombie = null;
        for (int[] cell : pots(level)) {
            ScaryPotterMechanic.Contents contents =
                    ScaryPotterMechanic.contentsAt(level, cell[0], cell[1]);
            if (contents != null && !contents.isPlant()) {
                zombiePot = cell;
                zombie = contents.id();
                break;
            }
        }
        assertNotNull(zombiePot, "round one has to hold some zombies");
        assertEquals(0, level.hostileZombieCount(), "and none of them are on the lawn yet");

        assertTrue(swingAt(level, bridge, zombiePot[0], zombiePot[1]), "the swing opens the pot");
        level.flushPending(bridge);
        assertEquals(1, level.hostileZombieCount(),
                "and what was inside is now standing in that cell: " + zombie);
    }

    @Test
    void theRoundAdvancesWhenTheLawnIsEmptyAndTheLevelIsWonOnTheLastOne() {
        LevelDef def = level();
        LevelServer level = new LevelServer(def);
        Bridge bridge = new Bridge();
        ScaryPotterData data = data(def);

        for (int round = 0; round < data.rounds().size(); round++) {
            List<int[]> standing = pots(level);
            assertEquals(data.rounds().get(round).potCount(), standing.size(),
                    "round " + (round + 1) + " stands up its own pots");
            for (int[] cell : standing) {
                assertTrue(swingAt(level, bridge, cell[0], cell[1]),
                        "the swing opens the pot at " + cell[0] + "," + cell[1]);
                level.flushPending(bridge);
            }
            // Whatever came out has to die before the round can end - the original's own
            // condition is "every pot broken and no zombies left". Killing them is the plants'
            // job in this level (there is no mallet in it any more), and what this test is about
            // is the round advancing rather than how a zombie dies, so they are killed outright.
            // Ash, because it ignores armour: an impact hit is eaten by a bucket before it
            // reaches the head, which is one swing per layer and not what this test is about.
            for (var entity : new ArrayList<>(level.entities())) {
                if (entity instanceof ZombieEntity zombie && zombie.isAlive()) {
                    zombie.damage(zombie.health() + zombie.armor() + 1,
                            ZombieEntity.damageType(PvzceIds.DAMAGE_ASH), level);
                }
            }
            level.flushPending(bridge);
            assertEquals(0, level.hostileZombieCount(), "the lawn is clear at the end of the round");
            level.tick(bridge);
        }

        assertTrue(ScaryPotterMechanic.stateOf(level).orElseThrow().cleared(),
                "three rounds in, the level's own condition is met");
        assertEquals(GameStateS2C.WON, level.gameState(),
                "and that is what wins it: this level has no wave to do it");
    }

    /** The vase level is not a card-picking level, and says so twice. */
    @Test
    void theVaseLevelHasNothingToChoose() {
        LevelDef def = level();
        assertEquals(2, def.maxSeedSlots(),
                "two slots, both filled by the level's own cards (the sun card and the bomb)");
        assertFalse(def.seedScreen(),
                "and the level says outright that the card screen is not a question it has, so"
                        + " entering it goes straight into the run");
        assertTrue(com.pvzce.common.core.SeedOptions.hasNothingToChoose(
                        com.pvzce.common.core.SeedOptions.forLevel(def),
                        def.effectiveMaxSeedSlots(com.pvzce.common.PvzceConstants.DEFAULT_SEED_SLOTS),
                        def.slots().stream().map(Identifier::toString).toList()),
                "so the seed screen has nothing to offer and stays a preview (the original's vase"
                        + " level hands the player one cherry bomb and nothing else)");
    }

    /**
     * The vase tool cannot bury a pot: bare ground only.
     *
     * <p>It used to ask about plants alone, so a vase click turned one of these pots into an
     * empty vase - the pot's contents stayed in the mechanic's bookkeeping with nothing on the
     * lawn to break, which is a level that can never be finished and a vase that is visibly
     * empty. (Same shape as a vase click burying a gravestone.)
     */
    @Test
    void theVaseToolCannotBuryAPot() {
        LevelDef def = level();
        LevelServer level = new LevelServer(def);
        Bridge bridge = new Bridge();
        int[] pot = pots(level).get(0);
        Identifier before = level.sceneIdAt(pot[0], pot[1]);
        assertTrue(ScaryPotterMechanic.contentsAt(level, pot[0], pot[1]) != null);
        // Through the tool path itself: the player carries the vase tool as a card and clicks
        // the pot with it.
        int slot = vaseSlot(level);
        assertTrue(slot >= 0, "the fixture bar has to hold the vase tool");
        level.plantPlayer().slot(slot).clearCooldown();
        assertFalse(level.useTool(bridge::send, slot, pot[0], pot[1]),
                "the click is refused - a pot is already standing there");
        assertTrue(bridge.messages().stream().anyMatch(line -> line.contains("已经有东西")),
                "and the player is told why: " + bridge.messages());
        assertFalse(PvzceIds.VASE.equals(level.sceneIdAt(pot[0], pot[1])),
                "and it must not have turned the pot into an empty vase");
        assertEquals(before, level.sceneIdAt(pot[0], pot[1]), "the pot is still the pot it was");
        assertTrue(ScaryPotterMechanic.contentsAt(level, pot[0], pot[1]) != null,
                "with what was inside it still there");
    }

    /**
     * A sun pot pays the player, in the cell it stood in.
     *
     * <p>The level's whole economy: no sky, no producers, one 150-sun card. A pot that broke into
     * nothing would leave the level's only card unplayable, which is what it was before the pots
     * held anything but plants and zombies.
     */
    @Test
    void breakingASunPotDropsItsResource() {
        LevelServer level = new LevelServer(level());
        Bridge bridge = new Bridge();
        int[] sunPot = null;
        for (int[] cell : pots(level)) {
            ScaryPotterMechanic.Contents contents =
                    ScaryPotterMechanic.contentsAt(level, cell[0], cell[1]);
            if (contents != null && contents.isResource()) {
                sunPot = cell;
                break;
            }
        }
        assertNotNull(sunPot, "the level hands out sun, so some pot has to hold one");

        assertTrue(swingAt(level, bridge, sunPot[0], sunPot[1]), "the swing opens the pot");
        level.flushPending(bridge);

        ResourceDropEntity drop = null;
        for (var entity : level.entities()) {
            if (entity instanceof ResourceDropEntity candidate
                    && PvzceIds.SUN.equals(candidate.def().id())) {
                drop = candidate;
                break;
            }
        }
        assertNotNull(drop, "a sun has to be lying there to be collected");
        assertEquals(25, drop.amount(), "worth what the resource says one sun is worth");
        assertEquals(sunPot[0], drop.gridX(), "and it lands in the cell the pot stood in");
        assertEquals(sunPot[1], drop.gridY());
    }

    /** A pot the board lost under a record that still counts it is stood back up. */
    @Test
    void aPotTheBoardLostComesBack() {
        LevelServer level = new LevelServer(level());
        Bridge bridge = new Bridge();
        // Exactly what the old vase-tool bug left behind: contents, and a cell that is not a pot.
        // Two cells, because the first repair ends with the pot broken (one pot, one test).
        List<int[]> standing = pots(level);
        for (int index = 0; index < 2; index++) {
            int[] pot = standing.get(index);
            Identifier leftover = index == 0 ? PvzceIds.GRASS : PvzceIds.VASE;
            level.setScene(pot[0], pot[1], leftover);
            assertFalse(ScaryPotterMechanic.isPot(level, pot[0], pot[1]));
            level.tick(bridge);
            assertTrue(ScaryPotterMechanic.isPot(level, pot[0], pot[1]),
                    "the run still counted it, so it has to be on the lawn to be broken (was "
                            + leftover + ")");
            assertTrue(swingAt(level, bridge, pot[0], pot[1]),
                    "and it can be broken again");
        }
    }

    /** The pots a run has left are the run's, not the level's: a save brings the same board back. */
    @Test
    void whatIsLeftOfThePotsSurvivesASave() {
        LevelDef def = level();
        LevelServer level = new LevelServer(def);
        Bridge bridge = new Bridge();
        List<int[]> standing = pots(level);
        // Open the first two, so the restored board has to differ from a fresh one.
        for (int index = 0; index < 2; index++) {
            int[] cell = standing.get(index);
            swingAt(level, bridge, cell[0], cell[1]);
        }
        assertEquals(13, pots(level).size());

        com.pvzce.common.nbt.CompoundTag saved = level.save();
        LevelServer restored = new LevelServer(TestLevels.copy(def).build());
        restored.restore(saved);

        assertEquals(13, pots(restored).size(), "the two broken pots stay broken");
        assertEquals(0, ScaryPotterMechanic.stateOf(restored).orElseThrow().round(),
                "and the run is still in its first round");
    }
}
