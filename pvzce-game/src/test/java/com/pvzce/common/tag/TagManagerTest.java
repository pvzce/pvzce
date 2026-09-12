package com.pvzce.common.tag;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TagManagerTest {
    @Test
    void builtInSunProducerTagLoadsAndBindsToPlantRegistry() throws Exception {
        var resources = TestContent.loadBuiltInContent();
        TagManager.LoadResult result = PvzceTags.MANAGER.reload(resources, BuiltInRegistries.ACCESS);
        assertTrue(result.errors().isEmpty(), result.errors().toString());
        assertTrue(PvzceTags.contains(PvzceTags.SUN_PRODUCERS, Identifier.withDefaultNamespace("sunflower")));
        assertTrue(PvzceTags.contains(PvzceTags.SUN_PRODUCERS, Identifier.withDefaultNamespace("marigold")));
        assertTrue(BuiltInRegistries.PLANTS.getTag(PvzceTags.SUN_PRODUCERS).isPresent());
    }
}
