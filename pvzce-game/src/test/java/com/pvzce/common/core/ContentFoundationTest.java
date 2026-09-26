package com.pvzce.common.core;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.pvzce.api.content.AnimationBindings;
import com.pvzce.api.content.GameRuleType;
import com.pvzce.api.content.ArmorDef;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.LevelRewards;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.ProjectileDef;
import com.pvzce.api.content.SceneElementDef;
import com.pvzce.api.content.SlotDef;
import com.pvzce.api.content.SoundEventDef;
import com.pvzce.api.content.ZombieDef;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.capability.zombie.ArmorCapability;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.tag.PvzceTags;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

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
    private static LevelDef level2;
    private static LevelDef level3;

    @BeforeAll
    static void loadData() throws Exception {
        // Content and convention tags together: the placement rules read tags,
        // so a data-only load would leave every cell unplantable.
        TestContent.loadBuiltInContentAndTags();

        demo = BuiltInRegistries.LEVELS.get(Identifier.withDefaultNamespace("yard/adventure/demo_level"));
        level1 = BuiltInRegistries.LEVELS.get(Identifier.withDefaultNamespace("yard/adventure/1_1"));
        level2 = BuiltInRegistries.LEVELS.get(Identifier.withDefaultNamespace("yard/adventure/1_2"));
        level3 = BuiltInRegistries.LEVELS.get(Identifier.withDefaultNamespace("yard/adventure/1_3"));
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
        // Water no longer lists "lily" by name: terrain rules live in the tag files
        // under data/c/tags/scene_element/, so what is asserted here is that the tag
        // reached the registry. PlantPlacementTest covers the resulting matrix.
        assertTrue(PvzceTags.SCENE_ELEMENTS.contains(
                        PvzceTags.SCENE_WATER, Identifier.withDefaultNamespace("water")),
                "#c:water must tag pvzce:water");
    }

    @Test
    void levelDefsCarryPhaseTwoFields() {
        assertEquals(150, demo.initialSun());
        // This level declares no max_seed_slots, so it follows the backpack; the effective
        // count is still raised to the number of cards the level lists, because a level
        // cannot ask for 13 cards and hand the player a shorter bar.
        assertFalse(demo.declaresMaxSeedSlots(), "demo_level declares no slot count");
        assertEquals(13, demo.effectiveMaxSeedSlots(
                com.pvzce.common.PvzceConstants.DEFAULT_SEED_SLOTS));
        assertEquals("pvzce:boolean", demo.envVars().get(Identifier.withDefaultNamespace("demo_flag")).type().toString());
        assertEquals(13, demo.slots().size());
        // 1-1 is the original's opening level: one lane, one plant card plus the sun
        // card, and the level fixes both so the player has nothing to choose.
        assertEquals(1, level1.height());
        assertEquals(150, level1.initialSun());
        assertEquals(2, level1.slots().size());
        assertEquals(2, level1.maxSeedSlots());
        // The original's own count for its first level, and its own opening delay.
        assertEquals(4, level1.waves().size());
        assertEquals(1.0F, level1.waveIntervalEndMultiplier(), 0.0001F);
        assertEquals("small", level1.waves().get(0).type().name().toLowerCase());
        assertEquals(1800, level1.waves().get(0).delay());
        assertEquals(1, level1.waves().get(0).entries().get(0).count());
        assertEquals("final", level1.waves().get(3).type().name().toLowerCase());
        assertEquals(2, level1.waves().get(3).entries().get(0).count());
        assertEquals(4, demo.waves().size());
        assertTrue(level1.previewZombieIds().contains("pvzce:basic_zombie"));
        assertTrue(demo.previewZombieIds().contains("pvzce:basic_zombie"));
    }

    /**
     * The three adventure levels are a chain: each one's first clear hands over the card
     * the next one is designed around (sunflower for the longer fights, cherry bomb for
     * the first huge wave, wall-nut for the coneheads).
     */
    @Test
    void theFirstThreeLevelsFormAnUnlockChain() {
        assertEquals("pvzce:sunflower", firstUnlock(level1), "1-1 hands over the sunflower");
        assertEquals("pvzce:cherry_bomb", firstUnlock(level2), "1-2 hands over the cherry bomb");
        assertEquals("pvzce:wall_nut", firstUnlock(level3), "1-3 hands over the wall-nut");
    }

    @Test
    void theAdventureLevelsOpenUpTheLawnOneStepAtATime() {
        // The original's ladder: one lane, then three from 1-2, and the last two only
        // arrive at 1-4.
        assertEquals(1, level1.height());
        assertEquals(3, level2.height());
        assertEquals(3, level3.height());
        // 1-1 is the original's tutorial and the only level that starts with 150 sun; every
        // other level starts with 50.
        assertEquals(150, level1.initialSun(), "1-1 is the tutorial's richer start");
        for (LevelDef level : List.of(level2, level3)) {
            assertEquals(50, level.initialSun(), level.id() + " starts with the original's 50 sun");
        }

        // The first seven levels deal their own cards, in the original's order
        // (`ChooseSeedsOnCurrentLevel` has no chooser before level 8), so the deck *is* what
        // the player owns: the peashooter at 1-1, sunflower at 1-2 (awarded by 1-1), cherry
        // bomb at 1-3, and the sun card always.
        assertEquals(List.of("pvzce:pea_shooter", "pvzce:sun"), cardIds(level1));
        assertEquals(List.of("pvzce:pea_shooter", "pvzce:sunflower", "pvzce:sun"), cardIds(level2));
        assertEquals(List.of("pvzce:pea_shooter", "pvzce:sunflower", "pvzce:cherry_bomb",
                "pvzce:sun"), cardIds(level3));
        for (LevelDef level : List.of(level1, level2, level3)) {
            assertEquals(9, level.width(), level.id() + " keeps the nine columns");
            assertTrue(level.rewards().hasCoinDrops(), level.id().toString());
            assertEquals("pvzce:coin_silver", level.rewards().coinDrop().toString());
        }
        assertEquals(2, level1.maxSeedSlots());
        assertEquals(4, level2.maxSeedSlots());
        assertEquals(5, level3.maxSeedSlots());
    }

    private static List<String> cardIds(LevelDef def) {
        return def.slots().stream().map(Identifier::toString).toList();
    }

    @Test
    void theNewZombiesAreIntroducedWhenTheOriginalsAre() {
        // 1-2 is where the Flag Zombie appears and 1-3 where the Conehead does, in wave
        // order - which is also the order the seed chooser's preview walks them in.
        assertTrue(level1.previewZombieIds().contains("pvzce:basic_zombie"));
        assertEquals(List.of("pvzce:basic_zombie", "pvzce:flag_zombie"), level2.previewZombieIds());
        assertEquals(List.of("pvzce:basic_zombie", "pvzce:conehead_zombie", "pvzce:flag_zombie"),
                level3.previewZombieIds());
        assertNotNull(BuiltInRegistries.ZOMBIES.get(Identifier.withDefaultNamespace("flag_zombie")));
        assertNotNull(BuiltInRegistries.ZOMBIES.get(Identifier.withDefaultNamespace("conehead_zombie")));
    }

    /**
     * Two content contracts the rest of the game assumes.
     *
     * <p>Sun is collected through its card, so a level that does not list it would start with
     * no way to collect sun at all; and 1-4's first clear is what hands over the glove, which
     * is why that level is the one the unlock tests reach for.
     */
    @Test
    void sunNeedsItsCardAndTheGloveComesFromOneFour() {
        assertFalse(BuiltInRegistries.RESOURCES.get(PvzceIds.SUN).collectibleWithoutCard(),
                "sun is collected through its card");
        for (LevelDef level : List.of(level1, level2, level3)) {
            assertTrue(level.slots().contains(PvzceIds.SUN), level.id() + " must list the sun card");
        }

        LevelDef gated = BuiltInRegistries.LEVELS.get(Identifier.withDefaultNamespace("yard/adventure/1_4"));
        assertNotNull(gated);
        Identifier glove = Identifier.parse("pvzce:glove");
        assertTrue(gated.rewards().firstClear().stream()
                        .anyMatch(reward -> reward.id().filter(glove::equals).isPresent()),
                "clearing 1-4 hands over the glove");
    }

    @Test
    void theConeheadWearsATrafficConeWorthTheOriginalsNumber() {
        ZombieDef conehead = BuiltInRegistries.ZOMBIES.get(Identifier.withDefaultNamespace("conehead_zombie"));
        assertEquals(200, conehead.health(), "a conehead is a normal zombie underneath");
        ArmorDef cone = conehead.capability(ArmorCapability.class).orElseThrow().armor().get(0);
        // The original's ladder: cone 370, bucket 1100.
        assertEquals(370, cone.durability());
        assertEquals(ArmorDef.TOP, cone.position());
        ZombieDef buckethead = BuiltInRegistries.ZOMBIES.get(Identifier.withDefaultNamespace("buckethead_zombie"));
        assertEquals(1100, buckethead.capability(ArmorCapability.class).orElseThrow()
                .armor().get(0).durability());
    }

    @Test
    void theFlagZombieLeadsTheHugeWaveWithoutArmor() {
        ZombieDef flag = BuiltInRegistries.ZOMBIES.get(Identifier.withDefaultNamespace("flag_zombie"));
        assertEquals(200, flag.health(), "a flag zombie is a normal zombie with a flag");
        assertTrue(flag.capability(ArmorCapability.class).isEmpty(), "the flag is not a helmet");
        // Same health, same speed as the crowd it leads: what a flag zombie announces is
        // the huge wave, and its stats are deliberately not a second difficulty knob.
        ZombieDef basic = BuiltInRegistries.ZOMBIES.get(Identifier.withDefaultNamespace("basic_zombie"));
        assertEquals(basic.moveSpeed(), flag.moveSpeed(), 0.0001F);
    }

    private static String firstUnlock(LevelDef level) {
        return level.rewards().firstClear().stream()
                .filter(LevelRewards.Reward::isUnlock)
                .map(reward -> reward.id().orElseThrow().toString())
                .findFirst().orElseThrow(() -> new AssertionError(level.id() + " grants no card"));
    }

    @Test
    void firstClearUnlocksSunflowerAndReplaysPayCoins() {
        LevelRewards rewards = level1.rewards();
        assertEquals(1, rewards.firstClear().size(), "1-1 grants exactly one card on a first clear");
        LevelRewards.Reward unlock = rewards.firstClear().get(0);
        assertTrue(unlock.isUnlock());
        assertEquals("pvzce:sunflower", unlock.id().orElseThrow().toString());
        assertEquals(1, rewards.repeat().size(), "a replay pays a coin stipend instead");
        assertTrue(rewards.repeat().get(0).isCoins());
        assertEquals(100, rewards.repeat().get(0).amount());
        assertTrue(rewards.hasCoinDrops());
        assertEquals(0.25F, rewards.coinDropChance(), 0.0001F);
        assertEquals(1, rewards.coinDropAmount());
    }

    @Test
    void aLevelWithoutARewardsBlockGetsTheDocumentedDefaults() {
        // The codec default is the standard stipend, and first clears pay nothing
        // extra until a level says so.
        assertEquals(LevelRewards.DEFAULT_REPEAT, demo.rewards().repeat());
        assertTrue(demo.rewards().firstClear().isEmpty());
        assertEquals(LevelRewards.DEFAULT_COIN_DROP_CHANCE, demo.rewards().coinDropChance(), 0.0001F);
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
        GameRuleType<Integer> sunInterval = (GameRuleType<Integer>) BuiltInRegistries.GAME_RULES
                .get(Identifier.withDefaultNamespace("sun_spawn_interval_min"));
        assertNotNull(sunInterval);
        assertEquals(0, sunInterval.clamp(-5));
        assertEquals(36000, sunInterval.clamp(999_999));

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
                Map.of("walk", Identifier.of("test", "walk_override")),
                Optional.empty());

        assertEquals("walk_override", bindings.resolve("walk").orElseThrow().path());
        assertEquals("whole", bindings.resolve("idle").orElseThrow().path());
        assertTrue(AnimationBindings.EMPTY.resolve("idle").isEmpty());
    }

    @Test
    void animationDirIsOptionalAndFallsBackToTheIdPath() {
        // No declared directory: the animation file mirrors the content id, which is
        // the convention every mod gets without writing a single extra field.
        AnimationBindings defaulted = AnimationBindings.EMPTY;
        assertEquals("pvzce:pea_shooter",
                defaulted.fileId(Identifier.of("pvzce", "pea_shooter")).toString());
        // A nested content id keeps its own path, so grouping never collides.
        assertEquals("pvzce:upgrades/pea",
                defaulted.fileId(Identifier.of("pvzce", "upgrades/pea")).toString());

        // A declared directory replaces the path and keeps only the leaf name, which
        // is what lets the shipped art group by kind while the ids stay stable.
        AnimationBindings grouped = new AnimationBindings(Optional.empty(), Map.of(),
                Optional.of("/plant/attacker/"));
        assertEquals("pvzce:plant/attacker/pea_shooter",
                grouped.fileId(Identifier.of("pvzce", "pea_shooter")).toString());
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

    /**
     * Every card's declared icon is a file that exists.
     *
     * <p>The hammer's pointed at {@code textures/entities/tool/hammer/hammer}, which was never
     * generated - that directory holds the three parts the model is built from - so its card
     * drew the missing-texture checkerboard. Nothing else complains about a card icon that
     * does not resolve: the slot loads, the card is playable, and only the picture is wrong.
     */
    @Test
    void everyCardIconResolvesToAFile() throws Exception {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        for (SlotDef slot : BuiltInRegistries.SLOT_TYPES) {
            Identifier icon = slot.icon().orElse(null);
            if (icon == null) {
                continue;
            }
            String path = "assets/" + icon.toPath() + ".png";
            try (var in = loader.getResourceAsStream(path)) {
                assertNotNull(in, slot.id() + "'s icon is missing: " + path);
            }
        }
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

    /**
     * An adventure level plays one track from start to finish.
     *
     * <p>The "battle" cue used to fire at 3300 ticks in all four of them. The original's
     * adventure levels do not switch tracks mid-level - the battle theme is the level
     * editor's tool for scripting a finale, and {@code combat_test} still uses it.
     */
    @Test
    void levelMusicCuesAreLoaded() {
        assertEquals("pvzce:music/grasswalk", level1.music().cues().get(0).event().orElseThrow().toString());
        assertEquals("background", level1.music().cues().get(0).track());
        assertTrue(level1.music().cues().get(0).loop());
        // One cue, looping, for the whole level. It used to be followed by a stop at 3300
        // and a battle track; dropping only the battle track left the level silent from
        // the halfway mark, which is the bug this pins.
        assertEquals(1, level1.music().cues().size(),
                "an adventure level plays one track from start to finish: " + level1.music().cues());
        assertFalse(level1.music().cues().get(0).stop(), "and never stops itself");
        assertEquals("pvzce:music/grasswalk",
                demo.music().cues().get(0).event().orElseThrow().toString());
    }

    /** The cue is still available to a level that wants it. */
    @Test
    void aLevelCanStillScriptABattleTheme() {
        var combat = BuiltInRegistries.LEVELS.get(
                Identifier.withDefaultNamespace("yard/adventure/combat_test"));
        assertTrue(combat.music().cues().stream().anyMatch(cue -> "battle".equals(cue.track())),
                "combat_test exists to exercise the cue");
    }
}
