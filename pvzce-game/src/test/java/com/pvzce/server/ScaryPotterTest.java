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
import com.pvzce.common.network.packet.HeldCardS2C;
import com.pvzce.common.network.packet.ServerMessageS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.CardDropEntity;
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
     * A sun pot pays the player a bundle of three, in the cell it stood in.
     *
     * <p>The level's whole economy: no sky, no producers, one 150-sun card. A pot that broke into
     * nothing would leave the level's only card unplayable, which is what it was before the pots
     * held anything but plants and zombies - and one sun per pot (the first shape of this) left
     * round one 50 sun short of the bomb it hands out. Three suns is the user's own number and the
     * original's.
     */
    @Test
    void breakingASunPotDropsItsBundleOfSuns() {
        LevelServer level = new LevelServer(level());
        Bridge bridge = new Bridge();
        List<int[]> sunPots = new ArrayList<>();
        for (int[] cell : pots(level)) {
            ScaryPotterMechanic.Contents contents =
                    ScaryPotterMechanic.contentsAt(level, cell[0], cell[1]);
            if (contents != null && contents.isResource()) {
                sunPots.add(cell);
            }
        }
        assertEquals(2, sunPots.size(), "round one's two sun pots are the level's whole economy");
        for (int[] cell : sunPots) {
            assertTrue(swingAt(level, bridge, cell[0], cell[1]), "the swing opens the pot");
        }
        level.flushPending(bridge);

        List<ResourceDropEntity> suns = new ArrayList<>();
        for (var entity : level.entities()) {
            if (entity instanceof ResourceDropEntity drop && PvzceIds.SUN.equals(drop.def().id())) {
                suns.add(drop);
            }
        }
        int perPot = com.pvzce.common.PvzceConstants.SCARY_POT_SUN_DROPS;
        assertEquals(perPot * sunPots.size(), suns.size(), "a sun pot drops a bundle, not one sun");
        int total = 0;
        for (ResourceDropEntity drop : suns) {
            assertEquals(25, drop.amount(), "each worth what the resource says one sun is worth");
            total += drop.amount();
        }
        assertEquals(150, total, "75 a pot: round one's two are exactly a cherry bomb");

        for (int[] pot : sunPots) {
            List<ResourceDropEntity> bundle = new ArrayList<>();
            for (ResourceDropEntity drop : suns) {
                // A bundle at the board's right-hand edge is shifted inward by the spread, so one
                // sun of it stands in the neighbouring cell. Nothing else moves it.
                if (drop.gridY() == pot[1] && Math.abs(drop.gridX() - pot[0]) <= 1) {
                    bundle.add(drop);
                }
            }
            assertEquals(perPot, bundle.size(), "the pot at " + pot[0] + "," + pot[1] + " dropped "
                    + bundle.size() + " suns");
            float min = Float.MAX_VALUE;
            float max = -Float.MAX_VALUE;
            for (ResourceDropEntity drop : bundle) {
                min = Math.min(min, drop.cellX());
                max = Math.max(max, drop.cellX());
            }
            // Spread, not stacked: three suns at one point look like one sun until they are
            // collected one at a time, which is what the first version of this did.
            assertEquals(2 * com.pvzce.common.PvzceConstants.SCARY_POT_SUN_SPREAD, max - min, 0.001F,
                    "the bundle is spread rather than stacked on one point");
        }
        // A pot that is not against the right-hand edge keeps its whole bundle in its own cell -
        // the cell the player was looking at when they swung at it.
        for (int[] pot : sunPots) {
            if (pot[0] >= level.width() - 1) {
                continue;
            }
            for (ResourceDropEntity drop : suns) {
                if (drop.gridY() == pot[1] && Math.abs(drop.gridX() - pot[0]) <= 1) {
                    assertEquals(pot[0], drop.gridX(),
                            "a bundle that fits stays in the pot's own cell");
                }
            }
        }
    }

    /**
     * A plant pot drops a seed packet where it stood, and the packet is the plant.
     *
     * <p>The player's own words for what a pot should do: "在原地掉落一个植物卡片，可以捡起来种植".
     * It used to go straight onto the bar, which made a pot of a plant the player already had look
     * like a pot that dropped nothing at all.
     */
    @Test
    void breakingAPlantPotDropsASeedPacket() {
        LevelServer level = new LevelServer(level());
        Bridge bridge = new Bridge();
        int[] plantPot = null;
        Identifier card = null;
        for (int[] cell : pots(level)) {
            ScaryPotterMechanic.Contents contents =
                    ScaryPotterMechanic.contentsAt(level, cell[0], cell[1]);
            if (contents != null && contents.isPlant()) {
                plantPot = cell;
                card = contents.id();
                break;
            }
        }
        assertNotNull(plantPot, "round one has to hold some plants");
        int slotsBefore = level.plantPlayer().slots().size();

        assertTrue(swingAt(level, bridge, plantPot[0], plantPot[1]), "the swing opens the pot");
        level.flushPending(bridge);
        assertEquals(slotsBefore, level.plantPlayer().slots().size(),
                "no card appears on the bar: the packet on the lawn is the card");

        CardDropEntity packet = cardDropAt(level, plantPot[0], plantPot[1]);
        assertNotNull(packet, "the plant is lying where the pot stood");
        assertEquals(card, packet.card());
        assertNull(ScaryPotterMechanic.contentsAt(level, plantPot[0], plantPot[1]),
                "and the pot is gone from the mechanic's bookkeeping");

        // Picking it up puts the plant in the player's hand, and planting it is free: the pot was
        // the payment.
        int sunBefore = level.team(PLANT_TEAM).resourcesOf(PvzceIds.SUN);
        assertTrue(level.pickUpCardDrop(bridge::send, packet.id()), "the packet is clickable");
        assertEquals(card, level.heldCard(), "and the card is in the player's hand now");
        assertTrue(level.plantHeldCard(bridge::send, 0, 0), "the next click plants it");
        assertEquals(1, level.plantCount());
        assertEquals(sunBefore, level.team(PLANT_TEAM).resourcesOf(PvzceIds.SUN),
                "planting what a pot handed over costs no sun");
        assertNull(level.heldCard(), "and the hand is empty again");
    }

    /**
     * A packet nobody picks up is gone after twenty seconds, and flashes before it goes.
     *
     * <p>Which is the whole reason the packet has a clock: the plant is the player's from the
     * moment the pot breaks, and a lawn that keeps every unclaimed packet forever is a lawn with
     * no reason to watch it.
     */
    @Test
    void anUnclaimedPacketExpires() {
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
        assertNotNull(plantPot);
        assertTrue(swingAt(level, bridge, plantPot[0], plantPot[1]));
        level.flushPending(bridge);
        CardDropEntity packet = cardDropAt(level, plantPot[0], plantPot[1]);
        assertNotNull(packet);

        int lifetime = com.pvzce.common.PvzceConstants.CARD_DROP_LIFETIME_TICKS;
        assertEquals(lifetime, packet.ticksLeft(), "a fresh packet has its whole life ahead of it");
        for (int tick = 0; tick < lifetime - com.pvzce.common.PvzceConstants.CARD_DROP_FLASH_TICKS;
                tick++) {
            packet.tick(level);
        }
        assertFalse(packet.isRemoved(), "it is still there at 15 seconds");
        assertTrue(packet.ticksLeft() <= com.pvzce.common.PvzceConstants.CARD_DROP_FLASH_TICKS,
                ".. and it is inside the flash window, so the client can show it is going");
        for (int tick = 0; tick < com.pvzce.common.PvzceConstants.CARD_DROP_FLASH_TICKS; tick++) {
            packet.tick(level);
        }
        assertTrue(packet.isRemoved(), "and at twenty seconds it is gone");
    }

    /**
     * A packet in hand does not expire, and a second one cannot be picked up.
     *
     * <p>The clock is the time the player has to notice the packet where it fell, not a deadline on
     * using a plant they are already holding - and one hand holds one plant.
     */
    @Test
    void aHeldPacketIsOffTheClockAndOneAtATime() {
        LevelServer level = new LevelServer(level());
        Bridge bridge = new Bridge();
        List<int[]> plantPots = new ArrayList<>();
        for (int[] cell : pots(level)) {
            ScaryPotterMechanic.Contents contents =
                    ScaryPotterMechanic.contentsAt(level, cell[0], cell[1]);
            if (contents != null && contents.isPlant()) {
                plantPots.add(cell);
            }
        }
        assertTrue(plantPots.size() >= 2, "two plant pots, so a second packet exists");
        for (int[] cell : plantPots.subList(0, 2)) {
            assertTrue(swingAt(level, bridge, cell[0], cell[1]));
        }
        level.flushPending(bridge);
        CardDropEntity first = cardDropAt(level, plantPots.get(0)[0], plantPots.get(0)[1]);
        CardDropEntity second = cardDropAt(level, plantPots.get(1)[0], plantPots.get(1)[1]);
        assertNotNull(first);
        assertNotNull(second);

        assertTrue(level.pickUpCardDrop(bridge::send, first.id()));
        int left = first.ticksLeft();
        for (int tick = 0; tick < com.pvzce.common.PvzceConstants.CARD_DROP_LIFETIME_TICKS; tick++) {
            first.tick(level);
        }
        assertFalse(first.isRemoved(), "the packet in hand is off the clock");
        assertEquals(left, first.ticksLeft(), "and it has not lost any of its time either");
        assertFalse(level.pickUpCardDrop(bridge::send, second.id()),
                "one hand holds one plant");
        assertTrue(bridge.messages().stream().anyMatch(line -> line.contains("手上")),
                "and the refusal says so: " + bridge.messages());

        // Right-click puts it back where it fell, with the time it had left.
        assertTrue(level.releaseHeldCard(bridge::send));
        assertNull(level.heldCard());
        assertEquals(left, second.ticksLeft(), "the other packet never moved");
        assertEquals(left, first.ticksLeft(), "and the released one keeps its time");
        assertFalse(first.held());
        assertEquals(plantPots.get(0)[0], first.gridX(), "back in the cell it fell in");
    }

    /**
     * Every pot of a round hands over something, and the round after a sweep is complete.
     *
     * <p>The full run: all three rounds, every pot broken, and the plants the pots handed over
     * planted in the way - which is exactly the board that used to make the next round's pots
     * silently disappear (round two came out with no zombie pots at all). The lawn is swept at the
     * round boundary, so every round stands up the count it declares.
     */
    @Test
    void everyRoundStandsUpItsWholeBoardEvenWithPlantsInTheWay() {
        LevelDef def = level();
        LevelServer level = new LevelServer(def);
        Bridge bridge = new Bridge();
        ScaryPotterData data = data(def);

        for (int round = 0; round < data.rounds().size(); round++) {
            List<int[]> standing = pots(level);
            assertEquals(data.rounds().get(round).potCount(), standing.size(),
                    "round " + (round + 1) + " stands up every pot it declares");
            int[] pot = standing.get(0);
            assertTrue(swingAt(level, bridge, pot[0], pot[1]));
            level.flushPending(bridge);
            ScaryPotterMechanic.Contents first =
                    ScaryPotterMechanic.contentsAt(level, pot[0], pot[1]);
            // Whatever the first pot held, the round goes on: break the rest, kill what came out.
            for (int index = 1; index < standing.size(); index++) {
                int[] cell = standing.get(index);
                assertTrue(swingAt(level, bridge, cell[0], cell[1]),
                        "the swing opens the pot at " + cell[0] + "," + cell[1]);
                level.flushPending(bridge);
            }
            for (var entity : new ArrayList<>(level.entities())) {
                if (entity instanceof ZombieEntity zombie && zombie.isAlive()) {
                    zombie.damage(zombie.health() + zombie.armor() + 1,
                            ZombieEntity.damageType(PvzceIds.DAMAGE_ASH), level);
                }
            }
            level.flushPending(bridge);
            // What the round handed over is planted on the lawn, in the next round's own columns:
            // the board a player who uses their plants would have.
            for (int x = 0; x < level.width(); x++) {
                for (int y = 0; y < level.height(); y++) {
                    if (level.plantAt(x, y) == null) {
                        level.spawnPlant(
                                BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter")),
                                level.plantPlayer().team(), x, y);
                        break;
                    }
                }
            }
            first = null;
            level.tick(bridge);
        }
        assertTrue(bridge.messages().stream().anyMatch(line -> line.contains("场地已清理")),
                "the sweep is announced, or plants vanishing reads as a bug: " + bridge.messages());
        assertTrue(ScaryPotterMechanic.stateOf(level).orElseThrow().cleared(),
                "and three clean rounds is the level won");
    }

    /**
     * The sweep between rounds takes the seed packets with it - the one in hand included.
     *
     * <p>What a round hand-out is worth is measured against a player who starts the round with
     * nothing: leaving last round's unspent plants lying on the lawn (or worse, in their hand) is a
     * stockpile, not a clean field.
     */
    @Test
    void theSweepBetweenRoundsTakesTheSeedPacketsToo() {
        LevelServer level = new LevelServer(level());
        Bridge bridge = new Bridge();
        List<int[]> plantPots = new ArrayList<>();
        for (int[] cell : pots(level)) {
            ScaryPotterMechanic.Contents contents =
                    ScaryPotterMechanic.contentsAt(level, cell[0], cell[1]);
            if (contents != null && contents.isPlant()) {
                plantPots.add(cell);
            }
        }
        assertTrue(plantPots.size() >= 2, "round one hands out more than one plant");
        // One packet picked up and carried, one left lying on the lawn.
        assertTrue(swingAt(level, bridge, plantPots.get(0)[0], plantPots.get(0)[1]));
        assertTrue(swingAt(level, bridge, plantPots.get(1)[0], plantPots.get(1)[1]));
        level.flushPending(bridge);
        CardDropEntity lying = cardDropAt(level, plantPots.get(1)[0], plantPots.get(1)[1]);
        assertNotNull(lying);
        assertTrue(level.pickUpCardDrop(bridge::send, lying.id()));
        assertEquals(lying.card(), level.heldCard(), "the plant is in the player's hand");

        LevelServer.LawnSweep sweep = level.clearLawn();
        level.flushPending(bridge);
        assertEquals(2, sweep.packets(), "both packets went with the sweep, the held one included");
        assertNull(level.heldCard(), "and the hand is empty afterwards");
        assertNull(cardDropAt(level, plantPots.get(1)[0], plantPots.get(1)[1]),
                "nothing is left lying on the lawn");
        assertTrue(lying.isRemoved());
        assertTrue(bridge.packets.stream()
                        .anyMatch(packet -> packet instanceof HeldCardS2C held && !held.holding()),
                "and the client is told the hand is empty, or it keeps drawing the ghost");
    }

    /** The bowl of every round's board: as many zombies as plants, and no kind missing. */
    @Test
    void everyRoundFacesThePlayerWithAtLeastAsManyZombiesAsPlants() {
        ScaryPotterData data = data(level());
        for (int index = 0; index < data.rounds().size(); index++) {
            ScaryPotterData.Round round = data.rounds().get(index);
            int plants = 0;
            int zombies = 0;
            for (ScaryPotterData.Pot pot : round.pots()) {
                if (pot.isPlant()) {
                    plants += pot.count();
                } else if (!pot.isResource()) {
                    zombies += pot.count();
                }
            }
            assertTrue(zombies >= plants, "round " + (index + 1) + " hands out " + plants
                    + " plants against " + zombies + " zombies, which is a round the player wins"
                    + " by planting whatever they are given");
        }
    }

    /** The seed packet lying in a cell, or {@code null} when there is none. */
    private static CardDropEntity cardDropAt(LevelServer level, int x, int y) {
        for (var entity : level.entities()) {
            if (entity instanceof CardDropEntity drop && !drop.isRemoved()
                    && drop.gridX() == x && drop.gridY() == y) {
                return drop;
            }
        }
        return null;
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
