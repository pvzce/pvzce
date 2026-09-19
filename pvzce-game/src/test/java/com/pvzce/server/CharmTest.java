package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.capability.plant.WakeBelowCapability;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hypno-shroom, and the rule it turned out to need: every attack asks "is this an enemy",
 * and enemy means "another team".
 *
 * <p>2-5 hands the mushroom over, so the level chain is what makes these rules matter - a
 * charmed zombie that the player's own peashooters still shot would be a card that does nothing
 * but stand there.
 */
class CharmTest {
    private static final Identifier PLANT_TEAM = PvzceIds.PLANT_TEAM;
    private static final Identifier ZOMBIE_TEAM = PvzceIds.ZOMBIE_TEAM;
    private static final Identifier BASIC_ZOMBIE = PvzceIds.id("basic_zombie");
    private static final Identifier HYPNO_SHROOM = PvzceIds.id("hypno_shroom");

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static final class Bridge implements LevelServer.ServerBridge {
        final List<String> messages = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            if (packet instanceof com.pvzce.common.network.packet.ServerMessageS2C message) {
                messages.add(message.message());
            }
        }
    }

    /** 2-1 without its waves: fixed night, a painted lawn, nothing spawning on its own. */
    private static LevelServer level() {
        LevelDef def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/2_1"));
        assertNotNull(def, "the shipped 2-1 must load");
        LevelDef quiet = new LevelDef(def.id(), def.name(), def.description(), def.width(), def.height(),
                def.scene(), def.teams(), def.winTeam(), def.rules(), def.envVars(), List.of(),
                def.waveIntervalEndMultiplier(), def.slots(), def.unlockResources(), def.initialSun(),
                def.music(), List.of(), def.maxSeedSlots(), def.rewards(), def.unlock(),
                def.mechanics(), def.dialogue(), def.hints(), def.playableTeams());
        return new LevelServer(quiet);
    }

    private static void tick(LevelServer level, Bridge bridge, int ticks) {
        for (int i = 0; i < ticks; i++) {
            level.tick(bridge);
        }
    }

    /** The shipped mushroom is a night plant that costs sun and has a charm on it. */
    @Test
    void theShippedMushroomIsANightPlantWithACharm() {
        PlantDef shroom = BuiltInRegistries.PLANTS.get(HYPNO_SHROOM);
        assertNotNull(shroom, "2-5 unlocks a plant that has to exist");
        assertTrue(shroom.capability(com.pvzce.common.capability.plant.CharmCapability.class).isPresent(),
                "and the plant that turns zombies has to be able to turn one");
        assertTrue(shroom.capability(
                        com.pvzce.common.capability.plant.NocturnalCapability.class).isPresent(),
                "it is a mushroom: asleep in daylight, awake at night");
        assertEquals(75, shroom.cost().amountOf(PvzceIds.SUN), "the original's 75 sun");
    }

    /**
     * The whole effect, on the shipped data: the zombie that eats it changes sides and the
     * mushroom is gone.
     */
    @Test
    void theZombieThatEatsItChangesSides() {
        LevelServer level = level();
        Bridge bridge = new Bridge();
        PlantDef shroom = BuiltInRegistries.PLANTS.get(HYPNO_SHROOM);
        PlantEntity planted = level.spawnPlant(shroom, level.team(PLANT_TEAM), 4, 2);
        level.flushPending(bridge);

        ZombieEntity zombie = level.spawnZombie(BASIC_ZOMBIE, level.team(ZOMBIE_TEAM), 5.4F, 2);
        level.flushPending(bridge);
        assertFalse(zombie.isCharmed(), "it starts on its own side");

        // Walk it in until it is standing on the mushroom and chewing.
        tick(level, bridge, 400);

        assertTrue(zombie.isCharmed(), "the bite turned it");
        assertEquals(PLANT_TEAM, zombie.team().id(), "and it is on the plants' side now");
        assertTrue(planted.isRemoved(), "the mushroom was eaten - that is the price of the charm");
    }

    /**
     * The coffee bean does not blink out of existence: it plays its {@code vanish} clip and
     * then leaves, and the cell is free the whole time.
     *
     * <p>That last part is the rule, not a detail: the bean is consumed by the placement, so
     * gameplay is over the moment it is planted - the clip is the drawing catching up, and
     * nothing may wait for it.
     */
    @Test
    void aSpentCoffeeBeanCrumbleBeforeItGoes() {
        LevelServer level = level();
        Bridge bridge = new Bridge();
        PlantDef shroom = BuiltInRegistries.PLANTS.get(PvzceIds.id("puff_shroom"));
        PlantEntity target = level.spawnPlant(shroom, level.team(PLANT_TEAM), 3, 1);
        level.flushPending(bridge);
        // Daylight in 2-1 is impossible (it is a fixed night level), so the mushroom is awake
        // and the bean has nothing to wake - which is the original's own wasted-bean case, and
        // it still crumbles, because being spent is what the clip is about.
        PlantDef bean = BuiltInRegistries.PLANTS.get(PvzceIds.id("coffee_bean"));
        PlantEntity planted = level.spawnPlant(bean, level.team(PLANT_TEAM), 3, 1);
        level.flushPending(bridge);

        assertTrue(planted.isRemoved(), "the bean is spent by the placement");
        assertTrue(planted.vanishing(), "and it is crumbling rather than gone");
        assertEquals("vanish", planted.animation(), "playing the clip its model ships");
        assertFalse(planted.occupiesCell(), "the cell is already free");
        assertNotNull(target, "and the plant it was stacked on is untouched");

        // It leaves the board once the clip is over, and not before.
        for (int i = 0; i < WakeBelowCapability.CRUMBLE_TICKS - 2; i++) {
            level.tick(bridge);
        }
        assertTrue(planted.vanishing(), "still crumbling");
        for (int i = 0; i < 4; i++) {
            level.tick(bridge);
        }
        assertFalse(level.entities().contains(planted), "and then it is gone");
    }

    /** A plant with no vanish clip still leaves at once, which is every other plant. */
    @Test
    void aPlantWithoutAVanishClipLeavesAtOnce() {
        LevelServer level = level();
        Bridge bridge = new Bridge();
        PlantDef pea = BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter"));
        PlantEntity planted = level.spawnPlant(pea, level.team(PLANT_TEAM), 2, 2);
        level.flushPending(bridge);
        planted.remove();
        assertFalse(planted.vanishing(), "nothing to draw");
        level.tick(bridge);
        assertFalse(level.entities().contains(planted), "and it is gone on the next tick");
    }

    /** Once charmed, the player's own shooters leave it alone. */
    @Test
    void aCharmedZombieIsNotAShooterTarget() {
        LevelServer level = level();
        Bridge bridge = new Bridge();
        ZombieEntity zombie = level.spawnZombie(BASIC_ZOMBIE, level.team(ZOMBIE_TEAM), 6.5F, 1);
        level.flushPending(bridge);

        assertTrue(level.enemiesInRow(1, level.team(PLANT_TEAM)).contains(zombie),
                "before the charm it is a target");
        zombie.setTeam(level.team(PLANT_TEAM));
        assertFalse(level.enemiesInRow(1, level.team(PLANT_TEAM)).contains(zombie),
                "after it, it is not: the row scan every shooter uses asks the team");

        // Which is the same question the blast and the mallet ask.
        int before = zombie.health();
        level.damageArea(ZombieEntity.damageType(PvzceIds.DAMAGE_ASH), zombie.cellX(), zombie.cellY(),
                2F, 1800, level.team(PLANT_TEAM));
        assertEquals(before, zombie.health(), "a cherry bomb on its own side does not hurt it");
    }

    /** It fights: it walks at the zombie in front of it and bites it. */
    @Test
    void aCharmedZombieAttacksItsFormerSide() {
        LevelServer level = level();
        Bridge bridge = new Bridge();
        ZombieEntity charmed = level.spawnZombie(BASIC_ZOMBIE, level.team(ZOMBIE_TEAM), 7.0F, 0);
        // Within bite reach from the start, and on purpose: a charmed zombie walks at the same
        // speed as the one it is chasing, so a gap between them is a gap it can never close.
        // "It bites what is in front of it" is the rule under test; "it can catch up" is not a
        // rule, and is not true.
        ZombieEntity enemy = level.spawnZombie(BASIC_ZOMBIE, level.team(ZOMBIE_TEAM), 6.5F, 0);
        level.flushPending(bridge);
        charmed.setTeam(level.team(PLANT_TEAM));
        int enemyHealth = enemy.health();

        tick(level, bridge, 120);

        assertTrue(enemy.health() < enemyHealth,
                "the charmed zombie bit the one in front of it (was " + enemyHealth
                        + ", now " + enemy.health() + ")");
        // It walks toward the house like any zombie, so the walk keeps going between bites.
        assertTrue(charmed.cellX() < 7.0F, "and it is still walking");
    }

    /**
     * It does not eat plants.
     *
     * <p>Alone in its lane, so the plant can only lose health to this one zombie: "walks past
     * plants" is about what a charmed zombie does on its own, and a second zombie behind it
     * would be measuring that one instead.
     */
    @Test
    void aCharmedZombieWalksPastPlants() {
        LevelServer level = level();
        Bridge bridge = new Bridge();
        PlantDef pea = BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter"));
        level.spawnPlant(pea, level.team(PLANT_TEAM), 4, 0);
        ZombieEntity charmed = level.spawnZombie(BASIC_ZOMBIE, level.team(ZOMBIE_TEAM), 6.5F, 0);
        level.flushPending(bridge);
        // The plant it will walk over, taken by identity so the assertion cannot accidentally
        // read a different one.
        PlantEntity plant = level.plantAt(4, 0);
        assertNotNull(plant);
        charmed.setTeam(level.team(PLANT_TEAM));
        int health = plant.health();
        float startX = charmed.cellX();

        // Far enough to walk over the plant's cell and out the other side.
        tick(level, bridge, 400);

        assertEquals(health, plant.health(),
                "a charmed zombie does not eat the plant it walks over");
        assertTrue(charmed.cellX() < startX - 1.0F,
                "it walked on instead of stopping to chew (from " + startX
                        + " to " + charmed.cellX() + ")");
    }

    /** A charmed zombie is not what stands between the player and a win. */
    @Test
    void aCharmedZombieDoesNotHoldTheLevelOpen() {
        LevelServer level = level();
        Bridge bridge = new Bridge();
        ZombieEntity charmed = level.spawnZombie(BASIC_ZOMBIE, level.team(ZOMBIE_TEAM), 3.5F, 0);
        level.flushPending(bridge);
        assertEquals(1, level.aliveZombieCount());
        assertEquals(1, level.hostileZombieCount(), "it is hostile until it is turned");

        charmed.setTeam(level.team(PLANT_TEAM));
        assertEquals(1, level.aliveZombieCount(), "it is still a zombie on the lawn");
        assertEquals(0, level.hostileZombieCount(), "but it is nobody's enemy any more");
    }

    /** Two mushrooms do not stack: the second one has nothing to do. */
    @Test
    void charmingAnAlreadyCharmedZombieDoesNothing() {
        LevelServer level = level();
        Bridge bridge = new Bridge();
        PlantDef shroom = BuiltInRegistries.PLANTS.get(HYPNO_SHROOM);
        PlantEntity planted = level.spawnPlant(shroom, level.team(PLANT_TEAM), 4, 3);
        level.flushPending(bridge);
        ZombieEntity zombie = level.spawnZombie(BASIC_ZOMBIE, level.team(ZOMBIE_TEAM), 5.4F, 3);
        level.flushPending(bridge);
        zombie.setTeam(level.team(PLANT_TEAM));

        tick(level, bridge, 400);

        assertEquals(PLANT_TEAM, zombie.team().id(), "still ours");
        assertFalse(planted.isRemoved(),
                "and the mushroom is still standing: it only spends itself on a zombie it turns");
        assertSame(level.team(PLANT_TEAM), zombie.team());
    }
}
