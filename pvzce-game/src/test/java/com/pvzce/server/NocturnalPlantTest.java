package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.ProjectileRef;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.capability.plant.NocturnalCapability;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.EntitySpawnS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ProjectileEntity;
import com.pvzce.server.entity.ResourceDropEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mushroom: asleep in daylight, awake at night, woken for good by a coffee bean - and
 * the short reach of its spore.
 *
 * <p>Both halves are new content behaviour rather than level data, so they are pinned end to
 * end against the shipped 1-4 board: a plant that sleeps but still shoots, or a spore that
 * flies the whole lane, would look exactly like this one until someone played it.
 */
class NocturnalPlantTest {
    private static final Identifier PLANT_TEAM = PvzceIds.PLANT_TEAM;
    private static final Identifier ZOMBIE_TEAM = PvzceIds.ZOMBIE_TEAM;
    private static final Identifier BASIC_ZOMBIE = PvzceIds.id("basic_zombie");
    private static final Identifier PUFF_SHROOM = PvzceIds.id("puff_shroom");
    private static final Identifier COFFEE_BEAN = PvzceIds.id("coffee_bean");
    private static final Identifier SUNFLOWER = PvzceIds.id("sunflower");
    private static final Identifier PUFF = PvzceIds.id("puff");

    private static LevelDef day;
    private static LevelDef night;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        LevelDef source = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_4"));
        assertNotNull(source, "the shipped 1-4 must load");
        // No sky sun: this class counts what a plant does, and a sun landing on the board
        // mid-assertion (1-4 drops one every ~10s) would be a second thing to explain.
        day = withRules(source, Map.of(PvzceIds.RULE_DAY_LENGTH, 0, PvzceIds.RULE_NIGHT_LENGTH, -1,
                PvzceIds.RULE_SUN_SPAWN_CHANCE, 0));
        // One tick of day, then ten minutes of night: the clock reads "night" after a single
        // step, which is what a night level is.
        night = withRules(source, Map.of(PvzceIds.RULE_DAY_LENGTH, 1, PvzceIds.RULE_NIGHT_LENGTH, 36_000,
                PvzceIds.RULE_SUN_SPAWN_CHANCE, 0));
    }

    /** The shipped 1-4 with different day/night lengths. */
    private static LevelDef withRules(LevelDef source, Map<Identifier, Integer> overrides) {
        Map<Identifier, com.google.gson.JsonElement> rules = new java.util.LinkedHashMap<>(source.rules());
        // JsonPrimitive of an Integer is a number, which is what the rule codecs read; the
        // float rule (sun spawn chance) gets its own primitive.
        overrides.forEach((id, value) -> rules.put(id, PvzceIds.RULE_SUN_SPAWN_CHANCE.equals(id)
                ? new com.google.gson.JsonPrimitive(value.floatValue())
                : new com.google.gson.JsonPrimitive(value)));
        return new LevelDef(source.id(), source.name(), source.description(), source.width(),
                source.height(), source.scene(), source.teams(), source.winTeam(), rules,
                source.envVars(), List.of(), source.waveIntervalEndMultiplier(), source.slots(),
                source.unlockResources(), source.initialSun(), source.music(),
                List.of(), LevelDef.UNSET_MAX_SEED_SLOTS, source.rewards(), source.unlock(),
                List.of(), source.dialogue(), List.of());
    }

    private static final class CapturingBridge implements LevelServer.ServerBridge {
        final List<PvzcePacket> packets = new ArrayList<>();

        @Override
        public void send(PvzcePacket packet) {
            packets.add(packet);
        }
    }

    private static PlantEntity plant(LevelServer level, Identifier plantId, int x, int y) {
        PlantDef def = BuiltInRegistries.PLANTS.get(plantId);
        assertNotNull(def, plantId + " must be registered");
        return level.spawnPlant(def, level.team(PLANT_TEAM), x, y);
    }

    private static void tick(LevelServer level, CapturingBridge bridge, int ticks) {
        for (int i = 0; i < ticks; i++) {
            level.tick(bridge);
            level.flushPending(bridge);
        }
    }

    /** How many shots this run has fired, counted from the packets the client would see. */
    private static long shotsFired(CapturingBridge bridge) {
        return bridge.packets.stream()
                .filter(packet -> packet instanceof EntitySpawnS2C spawn
                        && "projectile".equals(spawn.entityKind()))
                .count();
    }

    private static void addZombie(LevelServer level, CapturingBridge bridge, float x, int row) {
        level.spawnZombie(BASIC_ZOMBIE, level.team(ZOMBIE_TEAM), x, row);
        level.flushPending(bridge);
    }

    @Test
    void aSleepingMushroomDoesNotActUntilItIsWoken() {
        LevelServer level = new LevelServer(day);
        CapturingBridge bridge = new CapturingBridge();
        PlantEntity shroom = plant(level, PUFF_SHROOM, 0, 2);
        level.flushPending(bridge);

        assertFalse(level.isNight(), "the day level is day");
        assertTrue(shroom.isAsleep(level), "a mushroom sleeps in daylight");
        addZombie(level, bridge, 2.0F, 2);
        tick(level, bridge, 300);
        assertEquals(0, shotsFired(bridge), "a sleeping plant must not shoot");
        assertEquals(EntityAnimations.SLEEP, shroom.animation(), "and it says that it is asleep");
    }

    @Test
    void theSameMushroomFiresAtNight() {
        LevelServer level = new LevelServer(night);
        CapturingBridge bridge = new CapturingBridge();
        level.setDayTicks(5);
        PlantEntity shroom = plant(level, PUFF_SHROOM, 0, 2);
        level.flushPending(bridge);

        assertTrue(level.isNight(), "the clock reads night");
        assertFalse(shroom.isAsleep(level), "so the mushroom is awake");
        addZombie(level, bridge, 2.0F, 2);
        tick(level, bridge, 200);
        assertTrue(shotsFired(bridge) > 0, "an awake mushroom shoots what walks into its reach");
        assertEquals(EntityAnimations.SHOOT, shroom.animation(), "and plays its shooting clip");
    }

    @Test
    void aCoffeeBeanWakesItForGood() {
        LevelServer level = new LevelServer(day);
        CapturingBridge bridge = new CapturingBridge();
        PlantEntity shroom = plant(level, PUFF_SHROOM, 0, 2);
        level.flushPending(bridge);
        assertTrue(shroom.isAsleep(level));

        plant(level, COFFEE_BEAN, 0, 2);
        level.flushPending(bridge);
        assertFalse(shroom.isAsleep(level), "the coffee bean wakes the plant under it");
        assertEquals(1, level.plantsAt(0, 2).size(), "the bean itself is consumed");
        assertTrue(level.plantsAt(0, 2).get(0).defId().equals(PUFF_SHROOM));

        addZombie(level, bridge, 2.0F, 2);
        tick(level, bridge, 200);
        assertTrue(shotsFired(bridge) > 0, "a woken mushroom shoots in daylight");

        // The coffee bean sleeps nowhere: a second one has nothing to wake, and a replay of
        // the level must not put the mushroom back to sleep.
        LevelServer restored = new LevelServer(day);
        restored.restore(level.save());
        PlantEntity restoredShroom = restored.entities().stream()
                .filter(entity -> entity instanceof PlantEntity p && p.defId().equals(PUFF_SHROOM))
                .map(PlantEntity.class::cast)
                .findFirst().orElseThrow();
        assertTrue(restoredShroom.capability(NocturnalCapability.class).isAwake(),
                "being woken is part of the save");
        assertFalse(restoredShroom.isAsleep(restored), "so a resumed run does not re-sleep it");
    }

    /**
     * The coffee bean is not an instant-sun button.
     *
     * <p>It used to fan out into every capability's {@code boost()} - planting one on a
     * sunflower produced a sun on the spot. The original's bean does exactly one thing, and
     * "the plant below was not asleep" has to mean "nothing happens" or the card is free
     * value in every level.
     */
    @Test
    void aCoffeeBeanIsWastedOnAPlantThatDoesNotSleep() {
        LevelServer level = new LevelServer(day);
        CapturingBridge bridge = new CapturingBridge();
        plant(level, SUNFLOWER, 3, 2);
        level.flushPending(bridge);
        long sunsBefore = sunDrops(level);

        plant(level, COFFEE_BEAN, 3, 2);
        level.flushPending(bridge);
        tick(level, bridge, 5);
        assertEquals(sunsBefore, sunDrops(level), "a woken plant stays woken: no instant sun");
    }

    private static long sunDrops(LevelServer level) {
        return level.entities().stream()
                .filter(entity -> entity instanceof ResourceDropEntity drop
                        && drop.defId().equals(PvzceIds.SUN))
                .count();
    }

    /** A sleeping plant still answers "wake me" once, and only once. */
    @Test
    void wakingReportsWhetherItDidAnything() {
        LevelServer level = new LevelServer(day);
        PlantEntity shroom = plant(level, PUFF_SHROOM, 0, 2);
        level.flushPending(new CapturingBridge());
        assertTrue(shroom.wake(), "the first waking changes the plant");
        assertFalse(shroom.wake(), "the second has nothing left to do");
    }

    /** The other mushroom in the pack sleeps too, so the rule is a plant's, not a level's. */
    @Test
    void theDoomShroomSleepsInDaylightAsWell() {
        LevelServer level = new LevelServer(day);
        PlantEntity doom = plant(level, PvzceIds.id("doom_shroom"), 4, 2);
        level.flushPending(new CapturingBridge());
        assertTrue(doom.isAsleep(level));
        assertNotNull(doom.capability(NocturnalCapability.class), "it declares the capability");
    }

    /** Every plant that declares {@code nocturnal} answers the interface, not just the two ids. */
    @Test
    void theCapabilityIsWhatMakesAPlantNocturnal() {
        LevelServer level = new LevelServer(day);
        PlantEntity shroom = plant(level, PUFF_SHROOM, 0, 2);
        NocturnalCapability nocturnal = shroom.capability(NocturnalCapability.class);
        assertNotNull(nocturnal);
        assertTrue(nocturnal.asleep(shroom, level), "asleep by the level's clock");
        assertInstanceOf(PlantCapability.class, nocturnal);
    }

    // ------------------------------------------------------------------
    // Reach
    // ------------------------------------------------------------------

    /**
     * The spore dies where the plant's reach ends.
     *
     * <p>Fired with no target at all, so nothing else can remove it: the projectile has to
     * expire on its own, three cells from the muzzle.
     */
    @Test
    void aSporeOnlyFliesAsFarAsItsShotAllows() {
        LevelServer level = new LevelServer(night);
        CapturingBridge bridge = new CapturingBridge();
        level.setDayTicks(5);
        PlantEntity shroom = plant(level, PUFF_SHROOM, 0, 2);
        level.flushPending(bridge);

        ProjectileRef shot = new ProjectileRef(PUFF, 20, 1, 0, false, 0, 3F);
        level.spawnProjectile(shot, 0.6F, 2.0F, shroom);
        level.flushPending(bridge);
        ProjectileEntity spore = level.entities().stream()
                .filter(ProjectileEntity.class::isInstance)
                .map(ProjectileEntity.class::cast)
                .findFirst().orElseThrow();

        float furthest = spore.cellX();
        for (int i = 0; i < 400 && !spore.isRemoved(); i++) {
            level.tick(bridge);
            furthest = Math.max(furthest, spore.cellX());
        }
        assertTrue(spore.isRemoved(), "the spore must die on its own");
        assertTrue(furthest < 4.0F, "three cells of reach, not the whole lane: " + furthest);
    }

    /** The same number decides when the plant fires: nothing out of reach is worth a spore. */
    @Test
    void aShortRangedPlantDoesNotFireAtWhatItCannotReach() {
        LevelServer level = new LevelServer(night);
        CapturingBridge bridge = new CapturingBridge();
        level.setDayTicks(5);
        plant(level, PUFF_SHROOM, 0, 2);
        level.flushPending(bridge);

        addZombie(level, bridge, 7.0F, 2);
        tick(level, bridge, 300);
        assertEquals(0, shotsFired(bridge), "a zombie seven cells away is out of range");

        level.zombiesInRow(2).forEach(zombie -> zombie.setCellX(2.0F));
        tick(level, bridge, 120);
        assertTrue(shotsFired(bridge) > 0, "and walking into range is what makes it fire");
    }

    /** The long-ranged shooters are untouched: an unlimited shot still crosses the board. */
    @Test
    void anUnlimitedShotStillCrossesTheBoard() {
        LevelServer level = new LevelServer(night);
        CapturingBridge bridge = new CapturingBridge();
        level.setDayTicks(5);
        PlantEntity pea = plant(level, PvzceIds.id("pea_shooter"), 0, 2);
        level.flushPending(bridge);

        level.spawnProjectile(new ProjectileRef(PvzceIds.id("pea"), 20, 1), 0.6F, 2.0F, pea);
        level.flushPending(bridge);
        ProjectileEntity shot = level.entities().stream()
                .filter(ProjectileEntity.class::isInstance)
                .map(ProjectileEntity.class::cast)
                .findFirst().orElseThrow();
        for (int i = 0; i < 400 && !shot.isRemoved(); i++) {
            level.tick(bridge);
        }
        assertTrue(shot.isRemoved(), "a pea leaves the board at the far edge");
        assertTrue(shot.cellX() > level.width(), "not at three cells: " + shot.cellX());
    }

    /** The zombie the spore does reach takes the 20 damage the pack declares. */
    @Test
    void theSporeDamagesWhatItReaches() {
        LevelServer level = new LevelServer(night);
        CapturingBridge bridge = new CapturingBridge();
        level.setDayTicks(5);
        plant(level, PUFF_SHROOM, 0, 2);
        level.flushPending(bridge);
        addZombie(level, bridge, 2.0F, 2);
        ZombieEntity zombie = level.zombiesInRow(2).get(0);

        tick(level, bridge, 200);
        assertTrue(zombie.health() < 200 || zombie.isDying(),
                "20 damage per spore has to land: " + zombie.health());
    }

    /** Spawn packets are the only proof the client ever gets that a shot happened. */
    @Test
    void shotsAreSpawnedThroughTheNormalEntityPath() {
        LevelServer level = new LevelServer(night);
        CapturingBridge bridge = new CapturingBridge();
        level.setDayTicks(5);
        plant(level, PUFF_SHROOM, 0, 2);
        level.flushPending(bridge);
        addZombie(level, bridge, 2.0F, 2);
        tick(level, bridge, 200);
        assertTrue(bridge.packets.stream().anyMatch(packet -> packet instanceof EntitySpawnS2C spawn
                        && "projectile".equals(spawn.entityKind())),
                "the spore is an entity like any other shot");
    }

    /** {@code LevelAccess} answers the clock for content, not just for this test. */
    @Test
    void theLevelExposesItsClock() {
        LevelAccess access = new LevelServer(night);
        assertFalse(access.isNight(), "the level starts in its one-tick day");
        ((LevelServer) access).setDayTicks(2);
        assertTrue(access.isNight(), "and rolls into night");
    }
}
