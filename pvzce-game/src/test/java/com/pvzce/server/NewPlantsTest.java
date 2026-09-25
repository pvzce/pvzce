package com.pvzce.server;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.PlantPlacement;
import com.pvzce.common.tag.PvzceTags;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ProjectileEntity;
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
 * The nine plants of world 3's second half and world 4, and the six capabilities behind them.
 *
 * <p>Each one is a rule that only exists once: a zombie walks over the spikeweed, a pea gets
 * hotter in the torchwood, a shell eats the bites meant for the plant inside it, a lamp carves a
 * hole in the fog, a gust clears the sky, a magnet takes a bucket off. A plant whose behaviour is
 * only visible in a screenshot is a plant nobody can tell is broken, so each of the six gets a
 * test that watches the simulation rather than the picture.
 */
class NewPlantsTest {
    private static final Identifier PLANT_TEAM = PvzceIds.PLANT_TEAM;
    private static final Identifier ZOMBIE_TEAM = PvzceIds.ZOMBIE_TEAM;
    private static final Identifier BASIC = Identifier.withDefaultNamespace("basic_zombie");

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static LevelServer lawn() {
        return new LevelServer(com.pvzce.testutil.TestLevels.copy(
                        BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_1")))
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

    /** All nine are registered, have a card, and an almanac order of their own. */
    @Test
    void allNineAreContent() {
        String[] ids = {"spikeweed", "torchwood", "tall_nut", "sea_shroom", "plantern",
                "blover", "starfruit", "pumpkin", "magnet_shroom"};
        java.util.Set<Integer> orders = new java.util.HashSet<>();
        for (String id : ids) {
            PlantDef def = BuiltInRegistries.PLANTS.get(Identifier.withDefaultNamespace(id));
            assertNotNull(def, id + " has to be registered");
            assertTrue(com.pvzce.common.core.SlotResolver.resolve(
                            Identifier.withDefaultNamespace(id)).isPresent(),
                    id + " has to have a card, or the player can never plant it");
            assertTrue(orders.add(def.order()),
                    id + " shares an almanac order with another plant");
        }
    }

    /**
     * A zombie walks over the spikeweed instead of stopping to eat it, and is hurt on the way.
     *
     * <p>The two halves of the plant are two different rules: the damage is the capability's, and
     * "do not stop" is the {@code #c:walk_over} tag read by {@code biteTargetAt}. A spikeweed that
     * only had the first would be eaten by the first zombie that stepped on it.
     */
    @Test
    void aZombieWalksOverTheSpikeweedAndIsHurt() {
        LevelServer level = lawn();
        PlantEntity weed = place(level, "spikeweed", 4, 2);
        ZombieEntity zombie = level.spawnZombie(BASIC, level.team(ZOMBIE_TEAM), 4.5F, 2);
        assertNotNull(zombie);
        level.flushPending(packet -> { });
        int full = zombie.health();

        tick(level, 120);

        assertFalse(weed.isRemoved(), "a spikeweed is not food, so it is still there");
        assertTrue(zombie.health() < full,
                "and the zombie was hurt walking over it: " + zombie.health() + " of " + full);
    }

    /** The tall-nut cannot be vaulted; an ordinary wall-nut can. */
    @Test
    void aPoleVaulterCannotClearTheTallNut() {
        LevelServer level = lawn();
        place(level, "tall_nut", 4, 1);
        ZombieEntity vaulter = level.spawnZombie(
                Identifier.withDefaultNamespace("pole_vaulter_zombie"),
                level.team(ZOMBIE_TEAM), 4.4F, 1);
        assertNotNull(vaulter);
        level.flushPending(packet -> { });

        tick(level, 120);
        assertTrue(vaulter.cellX() > 4.0F,
                "a vaulter that met a tall-nut stops in front of it, was at " + vaulter.cellX());
    }

    /** A torchwood doubles a pea that crosses it, and only once. */
    @Test
    void aPeaThroughATorchwoodBurns() {
        LevelServer level = lawn();
        place(level, "torchwood", 2, 2);
        // A real source: the projectile scales its range and its damage by the plant that fired
        // it, so a shot with no owner is not a thing this engine can make.
        PlantEntity source = place(level, "pea_shooter", 1, 2);
        level.spawnProjectile(
                new com.pvzce.api.content.ProjectileRef(
                        Identifier.withDefaultNamespace("pea"), 20, 1, 0, false, 1,
                        com.pvzce.api.content.ProjectileRef.UNLIMITED_RANGE, 0),
                2.1F, 2.5F, source);
        level.flushPending(packet -> { });
        ProjectileEntity pea = null;
        for (var entity : level.entities()) {
            if (entity instanceof ProjectileEntity shot) {
                pea = shot;
                break;
            }
        }
        assertNotNull(pea, "the shot has to exist");
        int before = pea.damage();

        tick(level, 6);
        assertTrue(pea.torched(), "the shot has to have been lit");
        assertEquals(before * 2, pea.damage(), "and be worth twice as much");
        assertFalse(pea.torch(2, null), "and only once, however many torchwoods it crosses");
        assertEquals(before * 2, pea.damage());
    }

    /** A pumpkin takes the bites meant for the plant inside it. */
    @Test
    void thePumpkinTakesTheBitesForThePlantInside() {
        LevelServer level = lawn();
        PlantEntity pea = place(level, "pea_shooter", 4, 2);
        PlantEntity shell = place(level, "pumpkin", 4, 2);
        assertEquals(4, pea.gridX(), "both live in the same cell");
        assertEquals(4, shell.gridX());
        ZombieEntity zombie = level.spawnZombie(BASIC, level.team(ZOMBIE_TEAM), 4.4F, 2);
        assertNotNull(zombie);
        level.flushPending(packet -> { });
        int peaFull = pea.health();

        tick(level, 400);

        assertTrue(shell.health() < 4000, "the shell is what got eaten: " + shell.health());
        assertEquals(peaFull, pea.health(), "and the plant inside is untouched");
    }

    /** A lamp lifts the fog in a circle around itself. */
    @Test
    void thePlanternLightsTheFog() {
        LevelDefHolder holder = fogLevel();
        LevelServer level = holder.level();
        float dark = com.pvzce.common.level.mechanic.FogMechanic.alphaAt(
                level.fogData(), List.of(), 6.5F, 2.5F);
        assertTrue(dark > 0.2F, "the fixture has to actually be foggy there, was " + dark);

        PlantEntity lamp = place(level, "plantern", 6, 2);
        float lit = com.pvzce.common.level.mechanic.FogMechanic.alphaAt(
                level.fogData(), com.pvzce.common.level.mechanic.FogMechanic.lampsOf(level),
                6.5F, 2.5F);
        assertTrue(lit < dark, "the lamp has to make it clearer: " + lit + " against " + dark);
        assertEquals(1, level.fogReveals().size(), "and there is one lamp");

        lamp.remove();
        level.flushPending(packet -> { });
        // The hook runs on the plant's next tick, which is how an eaten plant tells its
        // capabilities it is gone - the removal itself is not the notification.
        tick(level, 2);
        assertTrue(level.fogReveals().isEmpty(), "and an eaten lamp takes its light with it");
    }

    /** One gust takes everything in the air off the board. */
    @Test
    void theBloverClearsTheSky() {
        LevelServer level = lawn();
        place(level, "blover", 4, 2);
        ZombieEntity balloon = level.spawnZombie(
                Identifier.withDefaultNamespace("balloon_zombie"),
                level.team(ZOMBIE_TEAM), 6F, 2);
        ZombieEntity walker = level.spawnZombie(BASIC, level.team(ZOMBIE_TEAM), 6F, 3);
        assertNotNull(balloon);
        assertNotNull(walker);
        level.flushPending(packet -> { });

        tick(level, 120);
        assertTrue(balloon.isRemoved(), "the flier is gone");
        assertFalse(walker.isRemoved(), "and the walker is not: a blover is not a mower");
    }

    /** The magnet takes the bucket, and not the zombie wearing it. */
    @Test
    void theMagnetTakesTheArmourAndNotTheZombie() {
        LevelServer level = lawn();
        place(level, "magnet_shroom", 2, 2);
        ZombieEntity bucket = level.spawnZombie(
                Identifier.withDefaultNamespace("buckethead_zombie"),
                level.team(ZOMBIE_TEAM), 3F, 2);
        assertNotNull(bucket);
        level.flushPending(packet -> { });
        assertTrue(bucket.hasArmor(), "the fixture has to actually be wearing something");

        tick(level, 60);

        assertFalse(bucket.hasArmor(), "the bucket is off");
        assertTrue(bucket.isAlive(), "and the zombie is fine without it");
    }

    /** The sea-shroom grows in water and nowhere else. */
    @Test
    void theSeaShroomIsWaterOnly() {
        PlantDef shroom = BuiltInRegistries.PLANTS.get(
                Identifier.withDefaultNamespace("sea_shroom"));
        assertNotNull(shroom);
        assertTrue(PlantPlacement.is(shroom, PvzceTags.WATER_PLANT),
                "it has to carry #c:water_plant, or the pool would refuse it and a lawn would take it");
    }

    /** A board with fog on it, for the plantern. */
    private record LevelDefHolder(LevelServer level) {
    }

    private static LevelDefHolder fogLevel() {
        var base = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_1"));
        java.util.List<com.pvzce.api.content.mechanic.TypedMechanic> mechanics =
                new java.util.ArrayList<com.pvzce.api.content.mechanic.TypedMechanic>(
                        base.mechanics());
        mechanics.add(com.pvzce.api.content.mechanic.TypedMechanic.of(
                PvzceIds.MECHANIC_FOG, new com.pvzce.api.content.FogData(4F, 8F, 0.94F)));
        var def = com.pvzce.testutil.TestLevels.copy(base).waves(List.of())
                .mechanics(mechanics).build();
        return new LevelDefHolder(new LevelServer(def));
    }
}
