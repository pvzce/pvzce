package com.pvzce.common.tag;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.resource.PvzceDataLoader;
import com.pvzce.common.resource.PvzceResourceManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TagManagerTest {
    @Test
    void builtInSunProducerTagLoadsAndBindsToPlantRegistry() throws Exception {
        BuiltInRegistries.bootstrap();
        Path gameDir = Files.createTempDirectory("pvzce-tags");
        PvzceResourceManager resources = new PvzceResourceManager(Thread.currentThread().getContextClassLoader());
        resources.init(gameDir);
        PvzceDataLoader dataLoader = new PvzceDataLoader();
        assertTrue(dataLoader.load(resources, BuiltInRegistries.ACCESS).errors().isEmpty());

        TagManager.LoadResult result = PvzceTags.MANAGER.reload(resources, BuiltInRegistries.ACCESS);
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertTrue(PvzceTags.contains(PvzceTags.SUN_PRODUCERS, Identifier.withDefaultNamespace("sunflower")));
        assertTrue(PvzceTags.contains(PvzceTags.SUN_PRODUCERS, Identifier.withDefaultNamespace("marigold")));
        assertTrue(BuiltInRegistries.PLANTS.getTag(PvzceTags.SUN_PRODUCERS).isPresent());
    }
}
