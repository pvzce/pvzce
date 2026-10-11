package com.pvzce.server;

import com.pvzce.api.entity.EntityKind;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.PlantPlacement;
import com.pvzce.common.level.mechanic.RandomPlantsMechanic;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.CardDropEntity;
import com.pvzce.server.entity.ResourceDropEntity;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regressions for payload save/impact and guaranteed, collectible sunlight. */
class RandomPlantsTest {
    @BeforeAll static void load() throws Exception { TestContent.loadBuiltInContentAndTags(); }

    @Test void poolSpecialCardsCanActuallyBePlantedAndRandomCarriersHostPlants() {
        var def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/minigame/random_plants_belt_3"));
        LevelServer ordinary = new LevelServer(BuiltInRegistries.LEVELS.get(
                PvzceIds.id("yard/minigame/random_plants_belt_2")));
        for (String name : List.of("gatling_pea", "twin_sunflower", "gloom_shroom", "cattail",
                "winter_melon", "gold_magnet", "spikerock", "cob_cannon", "grave_buster")) {
            var plant = BuiltInRegistries.PLANTS.get(PvzceIds.id(name));
            assertFalse(ordinary.canPlacePlant(plant, 3, 0), name + " retains its normal restriction elsewhere");
            LevelServer pool = new LevelServer(def);
            pool.spawnCardDrop(plant.id(), 3, 0);
            pool.flushPending();
            var card = pool.entities().stream().filter(e -> e instanceof CardDropEntity)
                    .findFirst().orElseThrow();
            assertTrue(pool.pickUpCardDrop(packet -> {}, card.id()));
            assertTrue(pool.plantHeldCard(packet -> {}, 3, 0), name + " must plant without its prerequisite");
        }
        LevelServer pool = new LevelServer(def);
        var original = BuiltInRegistries.PLANTS.get(PvzceIds.id("sunflower"));
        assertFalse(PlantPlacement.isCarrier(original));
        assertTrue(pool.canPlacePlant(original, 3, 2));
        pool.spawnPlant(original, pool.plantPlayer().team(), 3, 2);
        var pea = BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter"));
        assertTrue(pool.canPlacePlant(pea, 3, 2), "the shuffled carrier must support another plant");
        var hosted = pool.spawnPlant(pea, pool.plantPlayer().team(), 3, 2);
        float height = hosted.height();
        LevelServer resumed = new LevelServer(def);
        resumed.restore(pool.save());
        resumed.flushPending();
        assertEquals(2, resumed.plantsAt(3, 2).size());
        assertEquals(height, resumed.plantsAt(3, 2).stream().filter(p -> p.defId().equals(pea.id()))
                .findFirst().orElseThrow().height());
        assertFalse(pool.canPlacePlant(BuiltInRegistries.PLANTS.get(PvzceIds.id("cob_cannon")), 8, 0));
    }

    @Test void aPeaThatRolledBlowAwayStillProducesRealSunAfterResume() {
        var def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/minigame/random_plants_belt_3"));
        LevelServer level = new LevelServer(def);
        level.spawnPlant(BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter")),
                level.plantPlayer().team(), 0, 0);
        level.flushPending();
        for (int tick = 0; tick < 200; tick++) level.tick(packet -> {});
        LevelServer resumed = new LevelServer(def);
        resumed.restore(level.save());
        resumed.flushPending();
        int sun = 0;
        int firstDrop = -1;
        for (int tick = 200; tick < 1400; tick++) {
            resumed.tick(packet -> {});
            for (var entity : resumed.entities()) {
                if (entity instanceof ResourceDropEntity drop && drop.randomPayload()
                        && drop.defId().equals(PvzceIds.SUN)) {
                    if (firstDrop < 0) firstDrop = tick + 1;
                    sun += drop.amount();
                    resumed.collectResource(packet -> {}, drop.id());
                }
            }
        }
        assertEquals(300, firstDrop, "the rolled gust must not consume the guaranteed sun producer");
        assertEquals(50, sun, "the first and steady drops must both be collectible sunlight");
    }

    @Test void aFiredSunResumesItsPositionValueTeamAndVelocity() {
        var def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/minigame/random_plants_belt_1"));
        LevelServer level = new LevelServer(def);
        PlantEntity source = new PlantEntity(BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter")),
                level.plantPlayer().team(), 0, 2);
        var sun = (ResourceDropEntity) level.spawnRandomPayload(
                new RandomPlantsMechanic.Payload(EntityKind.RESOURCE, PvzceIds.SUN),
                source, 1.5F, 2.5F, 0F, 20, 25, 4F, 1F, 0F);
        sun.tickLaunch(level);
        level.flushPending();
        float x = sun.cellX();
        LevelServer resumed = new LevelServer(def);
        resumed.restore(level.save());
        resumed.flushPending();
        var restored = resumed.entities().stream().filter(e -> e instanceof ResourceDropEntity)
                .map(e -> (ResourceDropEntity) e).findFirst().orElseThrow();
        assertEquals(x, restored.cellX());
        assertEquals(25, restored.amount());
        assertEquals(source.team().id(), restored.team().id());
        resumed.tick(packet -> {});
        assertTrue(restored.cellX() > x, "the payload must keep flying after resume");
    }

    @Test void aLobPayloadWithLaunchVelocityCanStillHitAtMuzzleHeight() {
        var def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/minigame/random_plants_belt_1"));
        LevelServer level = new LevelServer(def);
        PlantEntity source = new PlantEntity(BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter")),
                level.plantPlayer().team(), 0, 2);
        var enemy = level.spawnZombie(PvzceIds.id("basic_zombie"), level.team(PvzceIds.ZOMBIE_TEAM), 3F, 2);
        int health = enemy.health();
        level.spawnRandomPayload(new RandomPlantsMechanic.Payload(EntityKind.PROJECTILE, PvzceIds.id("melon")),
                source, 1.5F, 2.5F, 0.55F, 40, 1, 4F, 1F, 0F);
        level.flushPending();
        for (int tick = 0; tick < 35; tick++) level.tick(packet -> {});
        assertTrue(enemy.health() < health, "a straight-launched melon must not wait for an arc landing it never makes");
    }
}
