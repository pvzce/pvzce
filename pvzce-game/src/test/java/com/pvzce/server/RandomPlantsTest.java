package com.pvzce.server;

import com.pvzce.api.entity.EntityKind;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.RandomPlantsMechanic;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ResourceDropEntity;
import com.pvzce.server.level.LevelServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression: the ordinary drop-discard path also discarded the randomized sun being fired. */
class RandomPlantsTest {
    @BeforeAll static void load() throws Exception { TestContent.loadBuiltInContentAndTags(); }

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
