package com.pvzce.server;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ice-boom shroom: cold that stacks into ice, and a payoff shot on a target the ice holds.
 *
 * <p>The two halves are separate rules and both are watched here, because either one alone still
 * looks like a working plant: a shooter whose bolts only slow is a worse snow pea, and a shatter
 * bonus with no way to reach the frozen state is a card that never pays off. The numbers pinned
 * are the ones a player feels - how many bolts until the ice, and what the shot on the ice is
 * worth - not the field names they are stored in.
 */
class ShatterShroomTest {
    private static final Identifier PLANT_TEAM = PvzceIds.PLANT_TEAM;
    private static final Identifier ZOMBIE_TEAM = PvzceIds.ZOMBIE_TEAM;
    private static final Identifier SHROOM = Identifier.withDefaultNamespace("iceboom_shroom");
    private static final Identifier BASIC = Identifier.withDefaultNamespace("basic_zombie");

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static LevelServer lawn() {
        return new LevelServer(com.pvzce.testutil.TestLevels.copy(
                        BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_4")))
                .waves(List.of()).build());
    }

    private static PlantEntity place(LevelServer level, String id, int x, int y) {
        PlantDef def = BuiltInRegistries.PLANTS.get(Identifier.withDefaultNamespace(id));
        assertNotNull(def, id + " has to be registered");
        PlantEntity plant = level.spawnPlant(def, level.team(PLANT_TEAM), x, y);
        level.flushPending(packet -> { });
        assertNotNull(plant, id + " has to be plantable at " + x + "," + y);
        return plant;
    }

    private static void tick(LevelServer level, int ticks) {
        for (int i = 0; i < ticks; i++) {
            level.tick(packet -> { });
        }
    }

    /** It is real content: registered, carded, and with an almanac number of its own. */
    @Test
    void itIsContentWithACardOfItsOwn() {
        PlantDef def = BuiltInRegistries.PLANTS.get(SHROOM);
        assertNotNull(def, "the plant has to be registered");
        assertTrue(SlotResolver.resolve(SHROOM).isPresent(),
                "and it has to have a card, or the player can never plant it");
        assertTrue(def.order() > 0, "and an almanac number: " + def.order());
        java.util.Set<Integer> taken = new java.util.HashSet<>();
        for (Identifier id : BuiltInRegistries.PLANTS.keySet()) {
            PlantDef other = BuiltInRegistries.PLANTS.get(id);
            if (other != null && other != def) {
                assertFalse(taken.contains(def.order()) && other.order() == def.order(),
                        "two plants share almanac order " + def.order());
                taken.add(other.order());
            }
        }
    }

    /**
     * Bolts stack into ice: three land, the third holds the zombie, and the tally starts over.
     *
     * <p>The zombie is a Buckethead on purpose. Its 200 body health is behind 1100 of bucket, so
     * it survives the five bolts this takes with room to spare - a basic zombie would be dead
     * before the third bolt landed, and the test would pass or fail on the damage rather than on
     * the rule it is about.
     */
    @Test
    void theThirdBoltFreezesAZombieSolid() {
        LevelServer level = lawn();
        place(level, "iceboom_shroom", 1, 2);
        ZombieEntity zombie = level.spawnZombie(
                Identifier.withDefaultNamespace("buckethead_zombie"), level.team(ZOMBIE_TEAM), 6F, 2);
        assertNotNull(zombie);
        level.flushPending(packet -> { });
        assertFalse(zombie.frozen(), "nothing is frozen before the first bolt lands");

        int landed = 0;
        for (int i = 0; i < 600 && landed < 3; i++) {
            int before = zombie.health() + zombie.armorHealth();
            level.tick(packet -> { });
            if (zombie.health() + zombie.armorHealth() < before) {
                landed++;
            }
        }
        assertEquals(3, landed, "three bolts have to land inside ten seconds of fire");
        assertTrue(zombie.frozen(),
                "and the third one has to leave it held, was " + zombie.health() + " health");
    }

    /**
     * The payoff: a bolt that lands on the ice is worth three bolts, and never before the ice.
     *
     * <p>Read off the hit-by-hit damage of a real defence, which is the only place the rule
     * reaches the player. What is asserted is the <em>shape</em> and not one frozen list of
     * numbers: the three bolts that build the ice are ordinary hits, the first bonus is exactly
     * three bolts, and the tally then starts over - so a bolt is never worth 60 twice in a row
     * without another three cold ones in between. Pinning the whole sequence would make this
     * test fail whenever a projectile's flight time lands one tick differently, which is not a
     * rule about the plant.
     */
    @Test
    void aBoltOnTheIceIsWorthThreeBolts() {
        LevelServer level = lawn();
        place(level, "iceboom_shroom", 1, 2);
        ZombieEntity zombie = level.spawnZombie(
                Identifier.withDefaultNamespace("buckethead_zombie"), level.team(ZOMBIE_TEAM), 6F, 2);
        assertNotNull(zombie);
        level.flushPending(packet -> { });

        java.util.List<Integer> hits = new java.util.ArrayList<>();
        int health = zombie.health() + zombie.armorHealth();
        for (int i = 0; i < 600; i++) {
            level.tick(packet -> { });
            int now = zombie.health() + zombie.armorHealth();
            if (now < health) {
                hits.add(health - now);
                health = now;
            }
        }

        assertTrue(hits.size() >= 4, "the run has to reach the payoff shot: " + hits);
        for (int i = 0; i < 3; i++) {
            assertEquals(20, hits.get(i),
                    "bolt " + (i + 1) + " is an ordinary hit; the cadence is " + hits);
        }
        assertEquals(60, hits.get(3),
                "the bolt after the third one finds the ice and is worth three: " + hits);
        assertTrue(zombie.isAlive(), "the fixture has to survive being shattered");
    }

    /**
     * Without the ice the same plant is an ordinary shooter: 20 a bolt, no bonus.
     *
     * <p>The control for the test above. A shatter rule keyed off the wrong state (off "this plant
     * fired", say) would pay out on the first bolt and both tests would still look plausible; this
     * one fails in that case, because a warm zombie takes exactly a pea's worth.
     */
    @Test
    void aBoltOnAWarmZombieIsJustABolt() {
        LevelServer level = lawn();
        place(level, "iceboom_shroom", 1, 2);
        ZombieEntity zombie = level.spawnZombie(
                Identifier.withDefaultNamespace("buckethead_zombie"), level.team(ZOMBIE_TEAM), 6F, 2);
        assertNotNull(zombie);
        level.flushPending(packet -> { });
        int before = zombie.health() + zombie.armorHealth();
        assertFalse(zombie.frozen(), "the fixture starts warm");

        int landed = 0;
        for (int i = 0; i < 200 && landed < 1; i++) {
            level.tick(packet -> { });
            if (zombie.health() + zombie.armorHealth() < before) {
                landed++;
                assertEquals(20, before - zombie.health() - zombie.armorHealth(),
                        "one warm bolt is worth one bolt and nothing more");
            }
        }
        assertEquals(1, landed, "the first bolt has to actually land");
        assertFalse(zombie.frozen(), "and one bolt is not ice");
    }

    /**
     * The tally survives a save, which is what the player was promised by the three visible
     * hit sparks they already paid for.
     *
     * <p>On the zombie rather than on the shot precisely so this holds; the counter this plant
     * was first written with could not have (a projectile dies on the hit that lands it).
     */
    @Test
    void theStackIsInTheZombiesOwnSave() {
        LevelServer level = lawn();
        place(level, "iceboom_shroom", 1, 2);
        ZombieEntity zombie = level.spawnZombie(
                Identifier.withDefaultNamespace("buckethead_zombie"), level.team(ZOMBIE_TEAM), 6F, 2);
        assertNotNull(zombie);
        level.flushPending(packet -> { });

        int before = zombie.health() + zombie.armorHealth();
        int landed = 0;
        for (int i = 0; i < 200 && landed < 1; i++) {
            level.tick(packet -> { });
            if (zombie.health() + zombie.armorHealth() < before) {
                landed++;
            }
        }
        assertEquals(1, landed, "one bolt, one stack");
        int stack = zombie.buildup(PvzceIds.ICEBOOM_CHILL);
        assertEquals(1, stack, "the zombie has to be carrying the stack, was " + stack);

        com.pvzce.common.nbt.CompoundTag saved = zombie.saveState();
        ZombieEntity reloaded = level.spawnZombie(
                Identifier.withDefaultNamespace("buckethead_zombie"), level.team(ZOMBIE_TEAM), 6F, 3);
        assertNotNull(reloaded);
        level.flushPending(packet -> { });
        reloaded.restoreState(saved);
        assertEquals(stack, reloaded.buildup(PvzceIds.ICEBOOM_CHILL),
                "and it comes back with the save");
        assertEquals(0, reloaded.buildup(Identifier.withDefaultNamespace("something_else")),
                "while a key nobody wrote stays empty");
    }
}
