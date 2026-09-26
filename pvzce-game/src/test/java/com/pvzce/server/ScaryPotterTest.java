package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.ScaryPotterData;
import com.pvzce.api.content.ToolData;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.level.mechanic.ScaryPotterMechanic;
import com.pvzce.common.level.mechanic.ToolMechanic;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.packet.ServerMessageS2C;
import com.pvzce.common.tag.TestContent;
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

    private static ToolData mallet(LevelDef def) {
        return ToolMechanic.defaultTool(def)
                .orElseThrow(() -> new AssertionError("4-5's click has to be the hammer"));
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

        assertEquals(1, def.slots().size(), "the player is handed one card: a cherry bomb");
        assertEquals(0, def.initialSun(), "and no sun falls, so the pots are the whole economy");
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
        ToolData mallet = mallet(level.def());

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

        assertTrue(level.useGrantedTool(bridge, mallet, plantPot[0], plantPot[1]),
                "the mallet opens the pot");
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

        assertTrue(level.useGrantedTool(bridge, mallet(level.def()), zombiePot[0], zombiePot[1]),
                "the mallet opens the pot");
        level.flushPending(bridge);
        assertEquals(1, level.hostileZombieCount(),
                "and what was inside is now standing in that cell: " + zombie);
    }

    @Test
    void theRoundAdvancesWhenTheLawnIsEmptyAndTheLevelIsWonOnTheLastOne() {
        LevelDef def = level();
        LevelServer level = new LevelServer(def);
        Bridge bridge = new Bridge();
        ToolData mallet = mallet(def);
        ScaryPotterData data = data(def);

        for (int round = 0; round < data.rounds().size(); round++) {
            List<int[]> standing = pots(level);
            assertEquals(data.rounds().get(round).potCount(), standing.size(),
                    "round " + (round + 1) + " stands up its own pots");
            for (int[] cell : standing) {
                assertTrue(level.useGrantedTool(bridge, mallet, cell[0], cell[1]),
                        "the mallet opens the pot at " + cell[0] + "," + cell[1]);
                level.flushPending(bridge);
            }
            // Whatever came out has to die before the round can end - the original's own
            // condition is "every pot broken and no zombies left" - and a buckethead takes more
            // than one swing. The mallet is swung over the whole board (no ticks pass, so
            // nothing has walked anywhere) until the lawn is clear.
            for (int guard = 0; guard < 200 && level.hostileZombieCount() > 0; guard++) {
                for (int x = 0; x < level.width(); x++) {
                    for (int y = 0; y < level.height(); y++) {
                        level.useGrantedTool(bridge, mallet, x, y);
                    }
                }
                level.flushPending(bridge);
            }
            assertEquals(0, level.hostileZombieCount(), "the lawn is clear at the end of the round");
            level.tick(bridge);
        }

        assertTrue(ScaryPotterMechanic.stateOf(level).orElseThrow().cleared(),
                "three rounds in, the level's own condition is met");
        assertEquals(GameStateS2C.WON, level.gameState(),
                "and that is what wins it: this level has no wave to do it");
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
            level.useGrantedTool(bridge, mallet(def), cell[0], cell[1]);
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
