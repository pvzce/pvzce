package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.MowerData;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.WaveDef;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.capability.zombie.BobsledCapability;
import com.pvzce.common.capability.zombie.JackInTheBoxCapability;
import com.pvzce.common.capability.zombie.VaultCapability;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three zombies added together because each is one rule and nothing else.
 *
 * <p>The pogo bounces over plants for ever and is answered only by a tall-nut; the
 * jack-in-the-box walks a fixed distance, opens its box and kills what is around it; the bobsled
 * team is four bodies on one 300-point sled, on ice only, that walks like four ordinary zombies
 * once the sled is gone. None of them is covered by the tests the ordinary bodies have, because
 * none of them is an ordinary body.
 *
 * <p>Every distance here is asserted as a <em>place on the lane</em> rather than as a tick count:
 * the fuse is a distance and so is a bounce, and a test that counted ticks would be testing its
 * own arithmetic instead of the rule.
 */
class BobsledJackPogoTest {
    private static final Identifier PLANT_TEAM = PvzceIds.PLANT_TEAM;
    private static final Identifier ZOMBIE_TEAM = PvzceIds.ZOMBIE_TEAM;
    private static final Identifier BOBSLED = PvzceIds.id("bobsled_zombie");
    private static final Identifier JACK_IN_THE_BOX = PvzceIds.id("jack_in_the_box_zombie");
    private static final Identifier POGO = PvzceIds.id("pogo_zombie");
    private static final int COLUMNS = 9;
    private static final int ROWS = 5;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    /** The three are content, with the capability that makes each of them what it is. */
    @Test
    void allThreeAreContent() {
        assertEquals(200, BuiltInRegistries.ZOMBIES.get(BOBSLED).health());
        assertEquals(0.23F, BuiltInRegistries.ZOMBIES.get(BOBSLED).moveSpeed(), 0.0001F);
        assertEquals(200, BuiltInRegistries.ZOMBIES.get(JACK_IN_THE_BOX).health());
        assertEquals(0.12F, BuiltInRegistries.ZOMBIES.get(JACK_IN_THE_BOX).moveSpeed(), 0.0001F);
        assertEquals(200, BuiltInRegistries.ZOMBIES.get(POGO).health());
        assertEquals(0.23F, BuiltInRegistries.ZOMBIES.get(POGO).moveSpeed(), 0.0001F);

        BobsledCapability sled = BuiltInRegistries.ZOMBIES.get(BOBSLED)
                .capability(BobsledCapability.class).orElseThrow();
        assertEquals(3, sled.riders());
        assertEquals(300, sled.sledHealthTotal());
        assertEquals(0.55F, sled.slideSpeed(), 0.0001F);

        JackInTheBoxCapability box = BuiltInRegistries.ZOMBIES.get(JACK_IN_THE_BOX)
                .capability(JackInTheBoxCapability.class).orElseThrow();
        assertEquals(110, box.popTicks());
        assertEquals(1.1F, box.plantRadius(), 0.0001F);
        assertEquals(1.4F, box.zombieRadius(), 0.0001F);

        // The pogo's is the vault capability under its own name: one motion, so that the airborne
        // window and the tall-nut rule cannot drift between the two zombies that use it.
        VaultCapability bounce = BuiltInRegistries.ZOMBIES.get(POGO)
                .capability(VaultCapability.class).orElseThrow();
        assertTrue(bounce.bounces(), "the pogo's vault repeats");
        assertEquals(VaultCapability.DEFAULT_BOUNCE_TICKS, bounce.jumpTicks());
    }

    /**
     * A pogo clears the plant in front of it, lands past it, and clears the next one too.
     *
     * <p>Both plants have to still be <em>alive</em>: the whole point of the bounce is that it goes
     * over instead of stopping to eat, and a pogo that chewed its way down the lane would satisfy
     * "it got past them" while being an ordinary zombie with a stick drawn on it.
     */
    @Test
    void thePogoClearsPlantsInsteadOfEatingThem() {
        LevelServer level = lawn(List.of());
        PlantEntity first = place(level, "wall_nut", 5, 2);
        PlantEntity second = place(level, "wall_nut", 2, 2);
        ZombieEntity pogo = spawn(level, POGO, 8.5F, 2);

        int guard = 0;
        while (pogo.cellX() > 1.75F && guard++ < 6_000) {
            tick(level, 1);
        }

        assertTrue(pogo.isAlive(), "the pogo has to survive its own lane");
        assertFalse(pogo.isDying());
        assertFalse(first.isRemoved(), "the first plant was cleared, not eaten");
        assertFalse(second.isRemoved(), "and so was the second");
        assertEquals(4000, first.health(), "a cleared plant is not bitten on the way past");
        // The landing spot is the rule, not a side effect: clearance (0.75) past the plant it
        // was aiming at (the second wall-nut sits at 2.5), which is the original's
        // `(mX - plantX + 60) / 80` written in cells.
        assertEquals(1.75F, pogo.cellX(), 0.02F,
                "it lands exactly past the plant it cleared (x=" + pogo.cellX() + ")");
        VaultCapability bounce = pogo.capability(VaultCapability.class);
        assertNotNull(bounce);
        assertFalse(bounce.hasJumped(), "the stick is still in one piece");
    }

    /** A tall-nut is the one plant a pogo cannot clear: the stick snaps and it walks from there. */
    @Test
    void aTallNutBreaksThePogoStick() {
        LevelServer level = lawn(List.of());
        PlantEntity tallNut = place(level, "tall_nut", 5, 2);
        ZombieEntity pogo = spawn(level, POGO, 8.5F, 2);

        int guard = 0;
        while (!pogo.capability(VaultCapability.class).hasJumped() && guard++ < 6_000) {
            tick(level, 1);
        }

        VaultCapability bounce = pogo.capability(VaultCapability.class);
        assertTrue(bounce.hasJumped(), "the tall-nut has to break the stick");
        assertTrue(pogo.isAlive());
        assertFalse(level.zombiesInRow(2).isEmpty(), "it is still standing in the lane");

        // The stick snaps as it starts the bounce, which is a little short of the nut: what is
        // left is an ordinary walker, and an ordinary walker eats what is in front of it.
        tick(level, 400);
        assertTrue(tallNut.health() < 8000,
                "and the pogo eats it from then on, like any other zombie");
        assertTrue(tallNut.isRemoved() || tallNut.health() < 8000);

        // "An ordinary walking zombie" is a speed, not a pose: the slide is gone with the sled...
        // er, the stick, and what is left is the definition's own pace.
        assertEquals(1F, bounce.speedMultiplier(pogo), 0.0001F);
    }

    /**
     * The jack-in-the-box kills what is near it when its fuse runs out, and nothing else.
     *
     * <p>The fuse is a distance, so where it goes off is a place on the lane: it starts at 8.5
     * with the default 6.5-cell fuse, which is x=2.0 - the assertion below is that number, which
     * is what "the fuse is a distance and not a clock" means. The plant in the cell beyond that
     * (1) is inside the 1.1-cell plant radius and the one at the far end (0) is outside it, and
     * neither is ever in the zombie's own cell, so eating cannot be what kills them.
     */
    @Test
    void theJackInTheBoxExplodesAfterItsFuse() {
        LevelServer level = lawn(List.of());
        // Wall-nuts rather than shooters: a peashooter would have killed the zombie
        // long before its fuse ran out, and this test is about the fuse.
        PlantEntity near = place(level, "wall_nut", 1, 2);
        PlantEntity far = place(level, "wall_nut", 0, 2);
        ZombieEntity box = spawn(level, JACK_IN_THE_BOX, 8.5F, 2);
        JackInTheBoxCapability fuse = box.capability(JackInTheBoxCapability.class);
        assertNotNull(fuse, "the jack-in-the-box has to carry its own capability");

        int guard = 0;
        while (!fuse.hasExploded() && guard++ < 8_000) {
            tick(level, 1);
        }

        assertTrue(fuse.hasExploded(), "the fuse has to run out");
        assertEquals(6.5F, 8.5F - box.cellX(), 0.01F,
                "and it runs out after the distance it was given, not after a time");
        assertTrue(near.isRemoved(), "the plant inside the blast is gone");
        assertFalse(far.isRemoved(), "the one outside it is not");
        assertEquals(4000, far.health(), "and it is untouched rather than merely alive");
        assertFalse(box.isAlive(), "the zombie dies of its own blast");
        assertTrue(box.selfDestructed(), "and it dies unpaid: the player did not kill this one");
    }

    /**
     * A jack-in-the-box a pot releases goes off on the spot, and leaves nothing behind.
     *
     * <p>Its fuse is the ground it has covered, and a zombie that comes out of a vase in the middle
     * of the board has covered none - so the pot's jack is a trap. It used to open the box and blast
     * 110 ticks later, and the user's report was that the blast never came: the plants around the
     * pot killed it inside that window. The report also asked for the body to go with it, which is
     * the second half of this test.
     */
    @Test
    void theBoxAPotReleasedGoesOffOnTheSpot() {
        LevelServer level = lawn(List.of());
        // A plant beside it, so "it exploded" is visible in the world and not only in the flag.
        PlantEntity beside = place(level, "wall_nut", 4, 2);
        ZombieEntity released = spawn(level, JACK_IN_THE_BOX, 4.5F, 2);
        JackInTheBoxCapability fuse = released.capability(JackInTheBoxCapability.class);
        assertNotNull(fuse);
        assertFalse(fuse.hasExploded(), "a freshly spawned one has not gone off");
        assertFalse(fuse.isPopping(), "and is still walking");

        released.onReleased(level);
        level.flushPending(packet -> { });

        assertTrue(fuse.hasExploded(), "the pot's one is a trap: it goes off where it appears");
        assertTrue(beside.isRemoved(), "and takes what was standing beside it");
        assertTrue(released.isRemoved(),
                "and leaves no corpse: the blast is the whole funeral (the user: 爆炸后还会原地留下"
                        + "一个小丑僵尸的动画，要过几秒才会消失)");
    }

    /**
     * The box opens before it goes off, and the zombie stands still while it does.
     *
     * <p>The 110 ticks are the player's last chance to answer it, which only exists if the blast's
     * centre stops moving: a body still walking during them would land its blast somewhere the
     * player had already read as safe.
     */
    @Test
    void theBoxOpensBeforeTheBlast() {
        LevelServer level = lawn(List.of());
        ZombieEntity box = spawn(level, JACK_IN_THE_BOX, 8.5F, 2);
        JackInTheBoxCapability fuse = box.capability(JackInTheBoxCapability.class);

        int guard = 0;
        while (!fuse.isPopping() && guard++ < 8_000) {
            tick(level, 1);
        }
        assertTrue(fuse.isPopping(), "the lid has to come up before the blast");
        assertEquals(com.pvzce.api.entity.EntityAnimations.POP, box.animation(),
                "and opening the box is the state it publishes while it does");

        float x = box.cellX();
        tick(level, 50);
        assertEquals(x, box.cellX(), 0.0001F, "a jack-in-the-box with the lid up stands still");
        assertTrue(box.isAlive(), "and is still shootable for the whole wind-up");
    }

    /**
     * A pogo that reaches the house loses the level like any other zombie.
     *
     * <p>A bounce takes every tick over, and "a zombie reached the house" is reported by the walk
     * loop - so a pogo that bounced at the door for ever would be a level that cannot end. The
     * board here has no mowers, so nothing else can stop it.
     */
    @Test
    void aPogoThatReachesTheHouseStillLosesTheLevel() {
        LevelServer level = lawnWithNoMowers(List.of());
        ZombieEntity pogo = spawn(level, POGO, 1.0F, 2);

        int guard = 0;
        while (GameStateS2C.RUNNING.equals(level.gameState())
                && guard++ < 6_000) {
            tick(level, 1);
        }

        assertTrue(pogo.isAlive(), "nothing on this board can kill it - it is the house that ends it");
        assertEquals(GameStateS2C.LOST, level.gameState(),
                "a zombie that walks into the house loses the level, bouncing or not");
    }

    /** One wave entry, four bodies: a lead and three riders, in a line, in one lane. */
    @Test
    void aBobsledEntrySpawnsALeadAndThreeRiders() {
        LevelServer level = lawn(waves(BOBSLED));
        iceRow(level, 3);
        settle(level);

        List<ZombieEntity> sled = zombiesIn(level, 3);
        assertEquals(4, sled.size(), "one entry is four zombies");
        long leads = sled.stream()
                .map(zombie -> zombie.capability(BobsledCapability.class))
                .filter(capability -> capability != null && !capability.isRider())
                .count();
        assertEquals(1, leads, "exactly one of them is the lead");

        sled.sort(java.util.Comparator.comparingDouble(ZombieEntity::cellX));
        for (int i = 1; i < sled.size(); i++) {
            float gap = sled.get(i).cellX() - sled.get(i - 1).cellX();
            assertTrue(gap > 0.4F && gap < 0.9F,
                    "the four are lined up a position apart (gap " + gap + ")");
        }
    }

    /**
     * A sled is only dealt into a lane that has ice.
     *
     * <p>The entry names no lane, so the wave director shuffles the board - and the one row with
     * ice on it is the only answer it is allowed to give.
     */
    @Test
    void aBobsledIsDealtIntoAnIcedLane() {
        LevelServer level = lawn(waves(BOBSLED));
        iceRow(level, 3);
        settle(level);

        assertEquals(4, zombiesIn(level, 3).size(), "the sled arrives on the iced lane");
        for (int row = 0; row < ROWS; row++) {
            if (row != 3) {
                assertTrue(zombiesIn(level, row).isEmpty(),
                        "and no lane without ice gets a sled (row " + row + ")");
            }
        }
    }

    /** No ice anywhere on the board: the entry is worth four ordinary zombies, not nothing. */
    @Test
    void aWaveWithNoIceGetsFourOrdinaryZombies() {
        LevelServer level = lawn(waves(BOBSLED));
        settle(level);

        List<ZombieEntity> spawned = level.entities().stream()
                .filter(ZombieEntity.class::isInstance)
                .map(ZombieEntity.class::cast)
                .toList();
        assertEquals(4, spawned.size(), "the entry is still worth four zombies");
        for (ZombieEntity zombie : spawned) {
            assertEquals(PvzceIds.id("basic_zombie"), zombie.defId(),
                    "and they are ordinary ones, because there is nowhere to sled");
            assertFalse(zombie.def().capability(BobsledCapability.class).isPresent());
        }
    }

    /**
     * When the sled breaks, all four walk.
     *
     * <p>The sled is what is holding the team together, so the crash is read off all four bodies:
     * the lead whose sled it was, and the three riders that have been following it.
     */
    @Test
    void whenTheSledBreaksAllFourWalkLikeOrdinaryZombies() {
        LevelServer level = lawn(List.of());
        iceRow(level, 3);
        ZombieEntity lead = spawn(level, BOBSLED, 8.5F, 3);
        tick(level, 3);

        List<ZombieEntity> sled = zombiesIn(level, 3);
        assertEquals(4, sled.size(), "the lead calls its three riders");
        BobsledCapability machine = lead.capability(BobsledCapability.class);
        assertNotNull(machine);
        assertEquals(0.55F / 0.23F, machine.speedMultiplier(lead), 0.001F,
                "while the sled holds, the team slides");

        lead.damageImpact(BobsledCapability.DEFAULT_SLED_HEALTH, level);
        tick(level, 2);

        assertTrue(machine.hasCrashed(), "300 points of sled is all there is");
        for (ZombieEntity zombie : zombiesIn(level, 3)) {
            BobsledCapability capability = zombie.capability(BobsledCapability.class);
            assertNotNull(capability);
            assertTrue(capability.hasCrashed(), "every rider crashes with the sled");
            // The original hands each of them a fresh random speed: within a tenth either way.
            assertEquals(1F, capability.speedMultiplier(zombie), 0.11F,
                    "and each walks at its own ordinary pace");
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * A five-row lawn of painted grass.
     *
     * <p>Painted rather than borrowed from 1-1: that level is a single row, and `sceneAt` answers
     * null for a cell nobody painted - which is where the ice has to go.
     */
    private static LevelServer lawn(List<WaveDef> waves) {
        Map<Identifier, List<String>> scene = new LinkedHashMap<>();
        List<String> grass = new ArrayList<>();
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLUMNS; x++) {
                grass.add(x + "," + y);
            }
        }
        scene.put(PvzceIds.GRASS, grass);
        LevelDef def = TestLevels.copy(BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_1")))
                .width(COLUMNS).height(ROWS).scene(scene).waves(waves).build();
        return new LevelServer(def);
    }

    /**
     * The same lawn with no mowers.
     *
     * <p>Nothing else would let a zombie reach the house: a parked mower flattens it a fraction of
     * a cell earlier, which is what {@code MowerTest} is for.
     */
    private static LevelServer lawnWithNoMowers(List<WaveDef> waves) {
        Map<Identifier, List<String>> scene = new LinkedHashMap<>();
        List<String> grass = new ArrayList<>();
        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLUMNS; x++) {
                grass.add(x + "," + y);
            }
        }
        scene.put(PvzceIds.GRASS, grass);
        LevelDef def = TestLevels.copy(BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_1")))
                .width(COLUMNS).height(ROWS).scene(scene).waves(waves)
                .mechanics(List.of(TypedMechanic.of(
                        PvzceIds.MECHANIC_MOWER, new MowerData(Optional.of(List.of())))))
                .build();
        return new LevelServer(def);
    }

    /** One wave, one zombie, arriving at once - the three tests below are about what it becomes. */
    private static List<WaveDef> waves(Identifier zombie) {
        return List.of(new WaveDef(WaveDef.WaveType.SMALL, 1, 0,
                List.of(new WaveDef.Entry(zombie, 1)), 15, Optional.of(0)));
    }

    /** The zamboni's trail, painted directly: what the bobsled's lane rule reads. */
    private static void iceRow(LevelServer level, int row) {
        for (int x = 0; x < COLUMNS; x++) {
            level.setScene(x, row, PvzceIds.ICE);
        }
    }

    /** Ticks until the wave has released and everything it spawned is on the board. */
    private static void settle(LevelServer level) {
        for (int i = 0; i < 20; i++) {
            level.tick(packet -> { });
        }
    }

    private static void tick(LevelServer level, int ticks) {
        for (int i = 0; i < ticks; i++) {
            level.tick(packet -> { });
        }
    }

    private static ZombieEntity spawn(LevelServer level, Identifier id, float x, int row) {
        ZombieEntity zombie = level.spawnZombie(id, level.team(ZOMBIE_TEAM), x, row);
        level.flushPending(packet -> { });
        assertNotNull(zombie, id + " has to be spawnable");
        return zombie;
    }

    private static List<ZombieEntity> zombiesIn(LevelServer level, int row) {
        return new ArrayList<>(level.zombiesInRow(row));
    }

    private static PlantEntity place(LevelServer level, String id, int x, int y) {
        PlantDef def = BuiltInRegistries.PLANTS.get(Identifier.withDefaultNamespace(id));
        assertNotNull(def, id + " has to be registered");
        PlantEntity plant = level.spawnPlant(def, level.team(PLANT_TEAM), x, y);
        level.flushPending(packet -> { });
        assertNotNull(plant);
        return plant;
    }
}
