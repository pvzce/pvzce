package com.pvzce.common.core;

import com.google.gson.JsonElement;
import com.pvzce.api.content.EnvVarType;
import com.pvzce.api.content.GameRuleType;
import com.pvzce.api.content.JsonCodecs;
import com.pvzce.api.content.LevelCategoryDef;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.LevelThemeDef;
import com.pvzce.api.content.LiquidDef;
import com.pvzce.api.content.ParticleDef;
import com.pvzce.api.content.PlacementDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.ProjectileDef;
import com.pvzce.api.content.ProjectileRef;
import com.pvzce.api.content.ResourceCost;
import com.pvzce.api.content.ResourceDef;
import com.pvzce.api.content.SceneElementDef;
import com.pvzce.api.content.SlotDef;
import com.pvzce.api.content.SoundEventDef;
import com.pvzce.api.content.ToolDef;
import com.pvzce.api.content.ZombieDef;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.content.capability.TypedCapability;
import com.pvzce.api.registry.Registry;
import com.pvzce.api.registry.RegistryAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.capability.PlantBehaviorPresets;
import com.pvzce.common.capability.PlantCapabilities;
import com.pvzce.common.capability.ProjectileCapabilities;
import com.pvzce.common.capability.ZombieCapabilities;
import com.pvzce.common.capability.plant.ShooterCapability;
import com.pvzce.common.level.CardCooldown;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Static registries, mirroring {@code net.minecraft.core.registries.BuiltInRegistries}.
 *
 * <p>The code-defined entries here are a bootstrap fallback only: they exist so the
 * game can start with no data pack at all. Where the shipped pack defines the same
 * id, the pack entry replaces the static one, so these literals must never be the
 * only definition of a value the simulation depends on. Anything that needs to be
 * identical in code and JSON is derived from the shared constants in
 * {@link PvzceConstants}, {@link PvzceIds} and {@link PvzceSounds} instead of being
 * written twice.
 */
public final class BuiltInRegistries {
    public static final RegistryAccess ACCESS = new RegistryAccess();

    public static final Registry<PlantDef> PLANTS = ACCESS.newRegistry(PvzceRegistries.PLANTS);
    public static final Registry<ZombieDef> ZOMBIES = ACCESS.newRegistry(PvzceRegistries.ZOMBIES);
    public static final Registry<ProjectileDef> PROJECTILES = ACCESS.newRegistry(PvzceRegistries.PROJECTILES);
    public static final Registry<ResourceDef> RESOURCES = ACCESS.newRegistry(PvzceRegistries.RESOURCES);
    public static final Registry<SlotDef> SLOT_TYPES = ACCESS.newRegistry(PvzceRegistries.SLOT_TYPES);
    public static final Registry<ToolDef> TOOLS = ACCESS.newRegistry(PvzceRegistries.TOOLS);
    public static final Registry<SceneElementDef> SCENE_ELEMENTS = ACCESS.newRegistry(PvzceRegistries.SCENE_ELEMENTS);
    public static final Registry<LiquidDef> LIQUIDS = ACCESS.newRegistry(PvzceRegistries.LIQUIDS);
    public static final Registry<GameRuleType<?>> GAME_RULES = ACCESS.newRegistry(PvzceRegistries.GAME_RULES);
    public static final Registry<EnvVarType<?>> ENV_VAR_TYPES = ACCESS.newRegistry(PvzceRegistries.ENV_VAR_TYPES);
    public static final Registry<SoundEventDef> SOUND_EVENTS = ACCESS.newRegistry(PvzceRegistries.SOUND_EVENTS);
    public static final Registry<ParticleDef> PARTICLES = ACCESS.newRegistry(PvzceRegistries.PARTICLES);
    public static final Registry<LevelDef> LEVELS = ACCESS.newRegistry(PvzceRegistries.LEVELS);
    public static final Registry<LevelThemeDef> LEVEL_THEMES = ACCESS.newRegistry(PvzceRegistries.LEVEL_THEMES);
    public static final Registry<com.pvzce.api.content.DialogueCharacterDef> DIALOGUE_CHARACTERS =
            ACCESS.newRegistry(PvzceRegistries.DIALOGUE_CHARACTERS);
    /**
     * Damage types: what a hit does to armour. Data-backed, so a pack can add its own
     * ({@code data/<ns>/damage_types/<name>.json}); the static entries below keep the
     * game playable with no pack at all, exactly like every other registry here.
     */
    public static final Registry<com.pvzce.api.content.DamageTypeDef> DAMAGE_TYPES =
            ACCESS.newRegistry(PvzceRegistries.DAMAGE_TYPES);
    public static final Registry<LevelCategoryDef> LEVEL_CATEGORIES =
            ACCESS.newRegistry(PvzceRegistries.LEVEL_CATEGORIES);
    public static final Registry<com.pvzce.api.content.capability.CapabilityType<PlantCapability>>
            PLANT_CAPABILITIES = ACCESS.newRegistry(PvzceRegistries.PLANT_CAPABILITIES);
    public static final Registry<com.pvzce.api.content.capability.CapabilityType<
            com.pvzce.api.content.capability.ZombieCapability>>
            ZOMBIE_CAPABILITIES = ACCESS.newRegistry(PvzceRegistries.ZOMBIE_CAPABILITIES);
    public static final Registry<com.pvzce.api.content.capability.CapabilityType<
            com.pvzce.api.content.capability.ProjectileCapability>>
            PROJECTILE_CAPABILITIES = ACCESS.newRegistry(PvzceRegistries.PROJECTILE_CAPABILITIES);
    /**
     * Level mechanics: the opt-in behaviour a level's {@code "mechanics"} list enables.
     *
     * <p>Registered before the level data is loaded (see {@link #bootstrap()}), because a
     * mechanic's codec is what decodes its block: an unregistered mechanic is an unknown
     * {@code type}, and the level that names it fails to load rather than silently losing
     * a rule.
     */
    public static final Registry<com.pvzce.common.level.mechanic.LevelMechanic<?>> LEVEL_MECHANICS =
            ACCESS.newRegistry(PvzceRegistries.LEVEL_MECHANICS);

    private static volatile boolean bootstrapped;

    /** Registers built-in code-defined skeleton entries; data packs may override most of them. */
    public static void bootstrap() {
        if (bootstrapped) {
            return;
        }
        bootstrapped = true;

        PlantCapabilities.bootstrap();
        ZombieCapabilities.bootstrap();
        ProjectileCapabilities.bootstrap();
        PlantBehaviorPresets.bootstrap();
        com.pvzce.api.content.ZombieBehaviorPresets.bootstrap();
        com.pvzce.api.content.ProjectileBehaviorPresets.bootstrap();
        com.pvzce.common.level.mechanic.LevelMechanics.bootstrap();

        registerPlants();
        registerZombies();
        registerProjectiles();
        registerResources();
        registerSlotsAndTools();
        registerLiquids();
        registerSceneElements();
        registerGameRules();
        registerEnvVarTypes();
        registerSounds();
        registerLevelGroups();
        registerDamageTypes();
    }

    private static void registerPlants() {
        registerStatic(PLANTS, "pvzce:pea_shooter", new PlantDef(
                PvzceIds.id("pea_shooter"),
                new ResourceCost(Map.of(PvzceIds.SUN, PvzceConstants.PEA_SHOOTER_COST),
                        PvzceConstants.PLANT_CARD_COOLDOWN_TICKS),
                300,
                PlacementDef.PLANTABLE_DEF,
                List.of(new TypedCapability<PlantCapability>(PlantCapabilities.SHOOTER.id(),
                        new ShooterCapability(ShooterCapability.DEFAULT_INTERVAL,
                                List.of(new ProjectileRef(PvzceIds.id("pea"), 20, 1)), Optional.empty(), 0))),
                Optional.empty(),
                PlantDef.PlantSounds.EMPTY,
                com.pvzce.api.content.AnimationBindings.EMPTY,
                Optional.empty()));
    }

    private static void registerZombies() {
        registerStatic(ZOMBIES, "pvzce:basic_zombie", new ZombieDef(
                PvzceIds.id("basic_zombie"),
                200,
                0.23F,
                100,
                60,
                false,
                List.of(),
                Optional.of(PvzceIds.id("basic")),
                ZombieDef.ZombieSounds.EMPTY,
                com.pvzce.api.content.AnimationBindings.EMPTY,
                Optional.empty()));
    }

    private static void registerProjectiles() {
        registerStatic(PROJECTILES, "pvzce:pea", new ProjectileDef(
                PvzceIds.id("pea"),
                ProjectileDef.LAYER_GROUND,
                List.of(),
                Optional.of(PvzceIds.id("linear")),
                ProjectileDef.ProjectileSounds.EMPTY,
                com.pvzce.api.content.AnimationBindings.EMPTY,
                Optional.empty()));
    }

    private static void registerResources() {
        // MUST match data/pvzce/resources/sun.json. Sun needs its card in the bar,
        // which is why every level lists the SunBank card in `slots` and why the bank HUD
        // appears only when the bar carries it.
        registerStatic(RESOURCES, "pvzce:sun", new ResourceDef(
                PvzceIds.SUN,
                PvzceConstants.SUN_VALUE,
                true,
                Identifier.withDefaultNamespace("textures/resource/sun"),
                PvzceIds.id("sun_fall"),
                9990,
                false));
        // MUST match data/pvzce/resources/{coin_silver,coin_gold,diamond,money_bag}.json.
        // Unlike sun they are collectible without a card: coins are currency, not a
        // card slot. The default value IS the denomination's worth.
        registerCoin(PvzceIds.COIN_SILVER, 10, "coin_silver");
        registerCoin(PvzceIds.COIN_GOLD, 50, "coin_gold");
        registerCoin(PvzceIds.DIAMOND, 1000, "diamond");
        registerCoin(PvzceIds.MONEY_BAG, 250, "money_bag");
    }

    private static void registerCoin(Identifier id, int worth, String path) {
        registerStatic(RESOURCES, id.toString(), new ResourceDef(
                id,
                worth,
                true,
                Identifier.withDefaultNamespace("textures/resource/" + path),
                id,
                // The wallet has no ceiling, so neither does a denomination's stack: what a
                // run collected is exactly what gets banked when it ends.
                PvzceConstants.COIN_LIMIT,
                true));
    }

    private static void registerSlotsAndTools() {
        registerStatic(SLOT_TYPES, "pvzce:pea_shooter", new SlotDef(
                PvzceIds.id("pea_shooter"),
                SlotDef.Kind.PLANT,
                PvzceIds.id("pea_shooter"),
                ResourceCost.FREE));
        registerStatic(SLOT_TYPES, "pvzce:sun", new SlotDef(
                PvzceIds.SUN,
                SlotDef.Kind.RESOURCE,
                PvzceIds.SUN,
                ResourceCost.FREE));

        registerStatic(TOOLS, "pvzce:shovel", new ToolDef(
                PvzceIds.id("shovel"),
                ResourceCost.FREE,
                0,
                List.of("plant"),
                "pvzce:shovel",
                -1));
    }

    /**
     * Liquids are registered before scene elements: an element names a liquid, so
     * the surface it refers to has to exist first even when only the bootstrap
     * fallback is in play.
     *
     * <p>These literals MUST match {@code data/pvzce/liquids/water.json} - the
     * pack entry replaces the static one whenever it loads, so a divergence means the
     * water looks different depending on whether a data pack was found.
     * {@code LiquidDefinitionTest.theBuiltInFallbackMatchesTheShippedData} compares
     * them field by field, and it caught the first divergence (the fallback still had
     * the pale palette and a four-times-wider foam band).
     */
    private static void registerLiquids() {
        registerStatic(LIQUIDS, "pvzce:water", builtInWater());
    }

    /**
     * The built-in water definition, as a value.
     *
     * <p>Separate from the registration so the drift test can compare these literals
     * against the shipped JSON - see the comment on {@link #registerLiquids()}.
     */
    public static LiquidDef builtInWater() {
        return new LiquidDef(
                PvzceIds.WATER,
                Optional.of(LiquidDef.DEFAULT_BASE_TEXTURE),
                LiquidDef.parseColor("#4FA8C8C8"),
                LiquidDef.parseColor("#1E6E8C"),
                0.68F,
                1.6F,
                LiquidDef.DEFAULT_BASE_SCALE,
                new LiquidDef.FoamStyle(LiquidDef.parseColor("#BFE0DE"), 0.04F),
                new LiquidDef.WaveShape(0.055F, 0.55F, 2F),
                0.55F,
                LiquidDef.parseColor("#9FC7E8"),
                0.35F,
                0.45F,
                72F,
                4);
    }

    private static void registerSceneElements() {
        // Kept in sync with data/pvzce/scene_elements/*.json by hand; the pack
        // entry wins whenever it loads.
        //
        // What may be planted on each element is not stated here at all - it is the
        // tags under data/c/tags/scene_element/ (see PlantPlacement). Terrain with no
        // tag simply accepts nothing, which is the safe default for a new element.
        registerStatic(SCENE_ELEMENTS, "pvzce:grass", new SceneElementDef(
                PvzceIds.GRASS, PvzceIds.SURFACE_GRASS, 0F));
        registerStatic(SCENE_ELEMENTS, "pvzce:ground", new SceneElementDef(
                PvzceIds.GROUND, PvzceIds.SURFACE_GROUND, 0F));
        registerStatic(SCENE_ELEMENTS, "pvzce:water", new SceneElementDef(
                PvzceIds.WATER, PvzceIds.SURFACE_WATER, 0F, Optional.of(PvzceIds.WATER)));
        registerStatic(SCENE_ELEMENTS, "pvzce:roof_flat", new SceneElementDef(
                PvzceIds.id("roof_flat"), PvzceIds.SURFACE_ROOF, 0F));
        registerStatic(SCENE_ELEMENTS, "pvzce:roof_slope", new SceneElementDef(
                PvzceIds.id("roof_slope"), PvzceIds.SURFACE_ROOF_SLOPE, 0.4F));
        registerStatic(SCENE_ELEMENTS, "pvzce:grave", new SceneElementDef(
                PvzceIds.id("grave"), PvzceIds.SURFACE_GRAVE, 0F));
        registerStatic(SCENE_ELEMENTS, "pvzce:crater", new SceneElementDef(
                PvzceIds.id("crater"), PvzceIds.SURFACE_CRATER, 0F));
        // The same terrain, drawn as the hole filling back in: what a crater becomes for the
        // last second of its recovery (see LevelServer.tickScene). A separate element rather
        // than a flag on the client, because which art a cell has is terrain and the server
        // is what decides terrain.
        registerStatic(SCENE_ELEMENTS, "pvzce:crater_fading", new SceneElementDef(
                PvzceIds.id("crater_fading"), PvzceIds.SURFACE_CRATER, 0F));
    }

    private static void registerGameRules() {
        // Defaults are sourced from the shared constants/ids so a rule default and
        // the constant it mirrors cannot drift apart.
        registerRule(PvzceIds.RULE_DAY_LENGTH, new GameRuleType.IntRule(0, 0, Integer.MAX_VALUE));
        registerRule(PvzceIds.RULE_NIGHT_LENGTH, new GameRuleType.IntRule(-1, -1, Integer.MAX_VALUE));
        registerRule(PvzceIds.RULE_SUN_SPAWN_CHANCE, new GameRuleType.FloatRule(
                PvzceConstants.SUN_SPAWN_CHANCE, 0F, 1F));
        registerRule(PvzceIds.RULE_CRATER_RECOVERY, new GameRuleType.IntRule(6000, 0, Integer.MAX_VALUE));
        registerRule(PvzceIds.RULE_SUN_VALUE, new GameRuleType.IntRule(
                PvzceConstants.SUN_VALUE, 1, 10000));
        registerRule(PvzceIds.RULE_ZOMBIE_SUN_DROP_CHANCE, new GameRuleType.FloatRule(0F, 0F, 1F));
        // Three, not one: a level that pays for kills pays in suns the player has to click, and
        // one sun per kill is invisible next to the sky's own rain. See the rule's own doc.
        registerRule(PvzceIds.RULE_ZOMBIE_SUN_DROP_COUNT, new GameRuleType.IntRule(3, 0, 99));
        registerRule(PvzceIds.RULE_ZOMBIE_DAMAGE_MULTIPLIER, new GameRuleType.FloatRule(1F, 0F, 100F));
        registerRule(PvzceIds.RULE_ZOMBIE_SPEED_MULTIPLIER, new GameRuleType.FloatRule(1F, 0F, 100F));
        registerRule(PvzceIds.RULE_PLANT_DAMAGE_MULTIPLIER, new GameRuleType.FloatRule(1F, 0F, 100F));
        // 1 = the wave table as written. Bigger is faster: the gap between waves and the gap
        // between the zombies inside one are both divided by it.
        registerRule(PvzceIds.RULE_ZOMBIE_SPAWN_SPEED_MULTIPLIER,
                new GameRuleType.FloatRule(1F, 0.1F, 20F));
        registerRule(PvzceIds.RULE_SEED_COOLDOWN_MULTIPLIER, new GameRuleType.FloatRule(
                CardCooldown.DEFAULT_MULTIPLIER, 0F, 5F));
        registerRule(PvzceIds.id("max_players_per_team"), new GameRuleType.IntRule(8, 1, 64));
        registerRule(PvzceIds.RULE_GRAVES_SPAWN_NIGHT, new GameRuleType.BooleanRule(true));
        registerRule(PvzceIds.RULE_ZOMBIE_RISE_TICKS, new GameRuleType.IntRule(
                PvzceConstants.ZOMBIE_RISE_TICKS, 1, 600));
        registerRule(PvzceIds.id("level_pause_on_single_player"), new GameRuleType.BooleanRule(true));
    }

    private static void registerEnvVarTypes() {
        registerEnv(PvzceIds.id("boolean"), new EnvVarType<>(
                PvzceIds.id("boolean"), com.mojang.serialization.Codec.BOOL, false));
        registerEnv(PvzceIds.id("int"), new EnvVarType<>(
                PvzceIds.id("int"), com.mojang.serialization.Codec.INT, 0));
        registerEnv(PvzceIds.id("float"), new EnvVarType<>(
                PvzceIds.id("float"), com.mojang.serialization.Codec.FLOAT, 0F));
        registerEnv(PvzceIds.id("string"), new EnvVarType<>(
                PvzceIds.id("string"), com.mojang.serialization.Codec.STRING, ""));
        registerEnv(PvzceIds.id("identifier"), new EnvVarType<>(
                PvzceIds.id("identifier"), Identifier.CODEC, PvzceIds.id("empty")));
        registerEnv(PvzceIds.id("json"), new EnvVarType<JsonElement>(
                PvzceIds.id("json"), JsonCodecs.RAW_JSON, com.google.gson.JsonNull.INSTANCE));
    }

    private static void registerSounds() {
        registerSound(PvzceSounds.PLANT_SHOOT_PEA, "豌豆射击");
        registerSound(PvzceSounds.PLANT_THROW, "投掷");
        registerSound(PvzceSounds.PROJECTILE_HIT, "命中");
        registerSound(PvzceSounds.PLANT_PLANT, "种植");
        registerSound(PvzceSounds.UI_COLLECT, "收集资源");
        registerSound(PvzceSounds.EFFECT_EXPLOSION, "爆炸");
        registerSound(PvzceSounds.EFFECT_BITE, "啃咬");
        registerSound(PvzceSounds.MUSIC_WIN, "胜利");
        registerSound(PvzceSounds.MUSIC_LOSE, "失败");
        registerSound(PvzceSounds.UI_CLICK, "点击");
        registerSound(PvzceSounds.MUSIC_GRASSWALK, "背景音乐");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerRule(Identifier id, GameRuleType<?> type) {
        registerStatic((Registry) GAME_RULES, id.toString(), type);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void registerEnv(Identifier id, EnvVarType<?> type) {
        registerStatic((Registry) ENV_VAR_TYPES, id.toString(), type);
    }

    private static void registerSound(Identifier id, String subtitle) {
        registerStatic(SOUND_EVENTS, id.toString(), new SoundEventDef(id, subtitle));
    }

    /**
     * The built-in theme/category pair, mirroring
     * {@code data/pvzce/{level_themes,level_categories}/*.json}.
     *
     * <p>Kept as a bootstrap fallback for the same reason as the other built-ins: with no
     * data pack at all the level select screen still has to have somewhere to put the
     * levels, and the unclassified bucket alone would hide the whole structure. The pack
     * entry replaces the static one whenever it loads.
     */
    private static void registerLevelGroups() {
        registerStatic(LEVEL_THEMES, "pvzce:yard", new LevelThemeDef(PvzceIds.id("yard"), 0));
        registerStatic(LEVEL_CATEGORIES, "pvzce:adventure", new LevelCategoryDef(PvzceIds.id("adventure"), 0));
    }

    /**
     * The built-in damage types, mirroring {@code data/pvzce/damage_types/*.json}.
     *
     * <p>Armour is the only thing the flag decides: {@code ignores_armor} is what
     * separates the ash line (and a lawn mower) from a pea. Which of a zombie's
     * pieces a hit meets first stays with the caller - a shot's layer picks a slot,
     * an impact tries front before top - because that is a question about the hit,
     * not about what kind of damage it is.
     */
    private static void registerDamageTypes() {
        registerStatic(DAMAGE_TYPES, "pvzce:ash",
                new com.pvzce.api.content.DamageTypeDef(PvzceIds.DAMAGE_ASH, true));
        registerStatic(DAMAGE_TYPES, "pvzce:splash",
                new com.pvzce.api.content.DamageTypeDef(PvzceIds.DAMAGE_SPLASH, true));
        registerStatic(DAMAGE_TYPES, "pvzce:mower",
                new com.pvzce.api.content.DamageTypeDef(PvzceIds.DAMAGE_MOWER, true));
        registerStatic(DAMAGE_TYPES, "pvzce:projectile",
                new com.pvzce.api.content.DamageTypeDef(PvzceIds.DAMAGE_PROJECTILE, false));
        registerStatic(DAMAGE_TYPES, "pvzce:impact",
                new com.pvzce.api.content.DamageTypeDef(PvzceIds.DAMAGE_IMPACT, false));
    }

    /**
     * The one registration entry point for built-in content: it registers and then
     * notifies {@link RegistryEntryAddedCallback}. Use this - not
     * {@link Registry#register} - so listeners see statically registered content.
     */
    public static <T> T registerStatic(Registry<T> registry, String id, T value) {
        return Registry.register(registry, Identifier.parse(id), value);
    }
}
