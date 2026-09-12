package com.pvzce.common.resource;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReloadTest {
    @Test
    void dataPackReloadPicksUpChanges() throws Exception {
        BuiltInRegistries.bootstrap();
        Path gameDir = Files.createTempDirectory("pvzce-reload");
        Path pack = gameDir.resolve("datapacks/testpack");
        Files.createDirectories(pack.resolve("data/test/plants"));

        Path plantFile = pack.resolve("data/test/plants/peashooter.json");
        Files.writeString(plantFile, """
                {"id":"test:peashooter","cost":{"resources":{"pvzce:sun":125},"cooldown":300},"health":400,"attack_interval":45,"shots":[],
                 "animation":"test:pea_whole","animations":{"walk":"test:pea_walk"}}
                """);

        PvzceResourceManager resources = new PvzceResourceManager(Thread.currentThread().getContextClassLoader());
        resources.init(gameDir);
        PvzceDataLoader loader = new PvzceDataLoader();
        PvzceDataLoader.LoadResult first = loader.load(resources, BuiltInRegistries.ACCESS);
        assertTrue(first.errors().isEmpty(), first.errors().toString());
        PlantDef plant = BuiltInRegistries.PLANTS.get(Identifier.of("test", "peashooter"));
        assertEquals(400, plant.health());
        assertEquals("test:pea_walk", plant.animations().resolve("walk").orElseThrow().toString());
        assertEquals("test:pea_whole", plant.animations().resolve("idle").orElseThrow().toString());

        Files.writeString(plantFile, """
                {"id":"test:peashooter","cost":{"resources":{"pvzce:sun":125},"cooldown":300},"health":777,"attack_interval":45,"shots":[]}
                """);
        resources.reload();
        PvzceDataLoader.LoadResult second = loader.load(resources, BuiltInRegistries.ACCESS);
        assertTrue(second.errors().isEmpty(), second.errors().toString());
        assertEquals(777, BuiltInRegistries.PLANTS.get(Identifier.of("test", "peashooter")).health());
    }
}
