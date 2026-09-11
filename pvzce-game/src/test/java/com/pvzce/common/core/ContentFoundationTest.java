package com.pvzce.common.core;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.pvzce.api.content.AnimationBindings;
import com.pvzce.api.content.GameRuleType;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.ProjectileDef;
import com.pvzce.api.content.SceneElementDef;
import com.pvzce.api.content.SlotDef;
import com.pvzce.api.content.SoundEventDef;
import com.pvzce.api.content.ZombieDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.resource.PvzceDataLoader;
import com.pvzce.common.resource.PvzceResourceManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M1: the phase-2 registry surface and the built-in representative data pack. */
class ContentFoundationTest {
    private static LevelDef demo;
    private static LevelDef level1;

    @BeforeAll
    static void loadData() throws Exception {
        BuiltInRegistries.bootstrap();
        PvzceResourceManager resources = new PvzceResourceManager(Thread.currentThread().getContextClassLoader());
        resources.init(Path.of(System.getProperty("java.io.tmpdir"), "pvzce-foundation-test"));
        PvzceDataLoader.LoadResult result = new PvzceDataLoader().load(resources, BuiltInRegistries.ACCESS);
        assertTrue(result.errors().isEmpty(), result.errors().toString());

        demo = BuiltInRegistries.LEVELS.get(Identifier.withDefaultNamespace("demo_level"));
        level1 = BuiltInRegistries.LEVELS.get(Identifier.withDefaultNamespace("level_1"));
    }

    @Test
    void representativePlantsAreLoaded() {
        String[] ids = {"pea_shooter", "kernel_pult", "cherry_bomb", "chomper", "sunflower",
                "wall_nut", "lily_pad", "flower_pot", "coffee_bean", "marigold"};
        for (String id : ids) {
            PlantDef plant = BuiltInRegistries.PLANTS.get(Identifier.withDefaultNamespace(id));
            assertNotNull(plant, id);
            assertNotNull(plant.resolvedCapabilities(), id);
        }
        assertTrue(BuiltInRegistries.PLANTS
                .get(Identifier.withDefaultNamespace("kernel_pult"))
                .capability(com.pvzce.common.capability.plant.ThrowerCapability.class).isPresent());
        assertTrue(BuiltInRegistries.PLANTS
                .get(Identifier.withDefaultNamespace("sunflower"))
                .capability(com.pvzce.common.capability.plant.ProducerCapability.class).isPresent());
    }

    @Test
    void representativeZombiesAreLoaded() {
        String[] ids = {"basic_zombie", "buckethead_zombie", "door_zombie", "newspaper_zombie",
                "pole_vaulter_zombie", "balloon_zombie", "miner_zombie", "gargantuar", "zombie_boss"};
        for (String id : ids) {
            ZombieDef zombie = BuiltInRegistries.ZOMBIES.get(Identifier.withDefaultNamespace(id));
            assertNotNull(zombie, id);
        }
        assertTrue(BuiltInRegistries.ZOMBIES.get(Identifier.withDefaultNamespace("balloon_zombie"))
                .capability(com.pvzce.common.capability.zombie.FlyCapability.class).isPresent());
        assertEquals(1, BuiltInRegistries.ZOMBIES.get(Identifier.withDefaultNamespace("buckethead_zombie"))
                .capability(com.pvzce.common.capability.zombie.ArmorCapability.class)
                .orElseThrow().armor().size());
    }

    @Test
    void projectileAndSceneDefinitionsAreDataDriven() {
        ProjectileDef kernel = BuiltInRegistries.PROJECTILES.get(Identifier.withDefaultNamespace("kernel"));
        assertTrue(kernel.capability(com.pvzce.common.capability.projectile.ArcMotionCapability.class).isPresent(),
                "kernel must declare arc motion");
        assertEquals(ProjectileDef.LAYER_AIR, kernel.layer());

        SceneElementDef slope = BuiltInRegistries.SCENE_ELEMENTS.get(Identifier.withDefaultNamespace("roof_slope"));
        assertEquals(0.4F, slope.maxHeight(), 0.0001F);
        SceneElementDef water = BuiltInRegistries.SCENE_ELEMENTS.get(Identifier.withDefaultNamespace("water"));
        assertTrue(water.accepts("lily"));
    }

    @Test
    void levelDefsCarryPhaseTwoFields() {
        assertEquals(150, demo.initialSun());
        assertEquals(6, demo.maxSeedSlots());
        assertEquals(6, level1.maxSeedSlots());
        assertEquals("pvzce:boolean", demo.envVars().get(Identifier.withDefaultNamespace("demo_flag")).type().toString());
        assertEquals(13, demo.slots().size());
        assertEquals(5, level1.waves().size());
        assertEquals(0.8F, level1.waveIntervalEndMultiplier(), 0.0001F);
        assertEquals("small", level1.waves().get(0).type().name().toLowerCase());
        assertEquals(600, level1.waves().get(0).delay());
        assertEquals(2, level1.waves().get(0).entries().get(0).count());
        assertEquals("huge", level1.waves().get(2).type().name().toLowerCase());
        assertEquals("final", level1.waves().get(4).type().name().toLowerCase());
        assertEquals(4, demo.waves().size());
        assertTrue(level1.previewZombieIds().contains("pvzce:basic_zombie"));
        assertTrue(demo.previewZombieIds().contains("pvzce:basic_zombie"));
    }

    @Test
    void seedPoolAndLimitParseFromLevelJson() {
        LevelDef parsed = LevelDef.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"id":"test:seeded","max_seed_slots":3,
                 "slots":["pvzce:sun","pvzce:pea_shooter"]}
                """)).getOrThrow();
        assertEquals(3, parsed.maxSeedSlots());
        assertEquals(List.of(Identifier.withDefaultNamespace("sun"),
                Identifier.withDefaultNamespace("pea_shooter")), parsed.slots());
    }

    @Test
    @SuppressWarnings("unchecked")
    void gameRuleTypesAreRegisteredAndClamp() {
        GameRuleType<Float> chance = (GameRuleType<Float>) BuiltInRegistries.GAME_RULES
                .get(Identifier.withDefaultNamespace("sun_spawn_chance"));
        assertNotNull(chance);
        assertEquals(0F, chance.clamp(-5F), 0.0001F);
        assertEquals(1F, chance.clamp(5F), 0.0001F);

        GameRuleType<Integer> dayLength = (GameRuleType<Integer>) BuiltInRegistries.GAME_RULES
                .get(Identifier.withDefaultNamespace("day_length"));
        assertEquals(0, dayLength.defaultValue());
    }

    @Test
    void envVarAndToolRegistriesExist() {
        assertNotNull(BuiltInRegistries.ENV_VAR_TYPES.get(Identifier.withDefaultNamespace("json")));
        assertNotNull(BuiltInRegistries.TOOLS.get(Identifier.withDefaultNamespace("shovel")));
        assertNotNull(BuiltInRegistries.SOUND_EVENTS.get(Identifier.withDefaultNamespace("sfx/plant/shoot_pea")));
    }

    @Test
    void animationBindingsResolveStateOverrideFirst() {
        AnimationBindings bindings = new AnimationBindings(
                Optional.of(Identifier.of("test", "whole")),
                Map.of("walk", Identifier.of("test", "walk_override")));

        assertEquals("walk_override", bindings.resolve("walk").orElseThrow().path());
        assertEquals("whole", bindings.resolve("idle").orElseThrow().path());
        assertTrue(AnimationBindings.EMPTY.resolve("idle").isEmpty());
    }

    @Test
    void slotIconsAreConfigured() {
        SlotDef sunflower = BuiltInRegistries.SLOT_TYPES.get(Identifier.withDefaultNamespace("sunflower"));
        assertNotNull(sunflower);
        assertEquals("pvzce:textures/gui/cards/sunflower", sunflower.icon().orElseThrow().toString());

        SlotDef potatoMine = BuiltInRegistries.SLOT_TYPES.get(Identifier.withDefaultNamespace("potato_mine"));
        assertNotNull(potatoMine);
        assertEquals("pvzce:textures/gui/cards/potato_mine", potatoMine.icon().orElseThrow().toString());

        SlotDef sun = BuiltInRegistries.SLOT_TYPES.get(Identifier.withDefaultNamespace("sun"));
        assertNotNull(sun);
        assertEquals("pvzce:textures/gui/hud/sun_bank", sun.icon().orElseThrow().toString());
    }

    @Test
    void projectileAnimationOverridesParseFromJson() {
        ProjectileDef projectile = ProjectileDef.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                {"id":"test:pea","animation":"test:pea_whole","animations":{"impact":"test:pea_impact"}}
                """)).getOrThrow();

        assertEquals("test:pea_whole", projectile.animations().animation().orElseThrow().toString());
        assertEquals("test:pea_impact", projectile.animations().resolve("impact").orElseThrow().toString());
    }

    @Test
    void originalSoundAndMusicEventsAreRegistered() {
        SoundEventDef groan = BuiltInRegistries.SOUND_EVENTS.get(Identifier.withDefaultNamespace("sfx/zombie/groan"));
        assertNotNull(groan);
        assertNotNull(BuiltInRegistries.SOUND_EVENTS.get(Identifier.withDefaultNamespace("sfx/plant/cherrybomb")));
        assertNotNull(BuiltInRegistries.SOUND_EVENTS.get(Identifier.withDefaultNamespace("music/grasswalk")));
        assertNotNull(BuiltInRegistries.SOUND_EVENTS.get(Identifier.withDefaultNamespace("music/ultimate_battle")));
    }

    @Test
    void contentDefsCarryPerEntitySoundOverrides() {
        PlantDef cherry = BuiltInRegistries.PLANTS.get(Identifier.withDefaultNamespace("cherry_bomb"));
        assertTrue(cherry.sounds().explode().isPresent());
        assertEquals("pvzce:sfx/plant/cherrybomb", cherry.sounds().explode().get().toString());

        ZombieDef balloon = BuiltInRegistries.ZOMBIES.get(Identifier.withDefaultNamespace("balloon_zombie"));
        assertEquals("pvzce:sfx/zombie/ballooninflate", balloon.sounds().spawn().orElseThrow().toString());

        ProjectileDef kernel = BuiltInRegistries.PROJECTILES.get(Identifier.withDefaultNamespace("kernel"));
        assertEquals("pvzce:sfx/plant/kernelpult2", kernel.sounds().impact().orElseThrow().toString());
    }

    @Test
    void levelMusicCuesAreLoaded() {
        assertEquals("pvzce:music/grasswalk", level1.music().cues().get(0).event().orElseThrow().toString());
        assertEquals(4800, level1.music().cues().get(1).atTick());
        assertEquals("background", level1.music().cues().get(1).track());
        assertTrue(level1.music().cues().get(1).stop());
        assertEquals(4800, level1.music().cues().get(2).atTick());
        assertEquals("battle", level1.music().cues().get(2).track());
        assertEquals("pvzce:music/grasswalk",
                demo.music().cues().get(0).event().orElseThrow().toString());
    }
}
