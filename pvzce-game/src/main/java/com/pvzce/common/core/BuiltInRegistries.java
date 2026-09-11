package com.pvzce.common.core;

import com.google.gson.JsonElement;
import com.pvzce.api.content.EnvVarType;
import com.pvzce.api.content.GameRuleType;
import com.pvzce.api.content.JsonCodecs;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.LiquidDef;
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

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    public static final Registry<LevelDef> LEVELS = ACCESS.newRegistry(PvzceRegistries.LEVELS);
    public static final Registry<com.pvzce.api.content.capability.CapabilityType<PlantCapability>>
            PLANT_CAPABILITIES = ACCESS.newRegistry(PvzceRegistries.PLANT_CAPABILITIES);
    public static final Registry<com.pvzce.api.content.capability.CapabilityType<
            com.pvzce.api.content.capability.ZombieCapability>>
            ZOMBIE_CAPABILITIES = ACCESS.newRegistry(PvzceRegistries.ZOMBIE_CAPABILITIES);
    public static final Registry<com.pvzce.api.content.capability.CapabilityType<
            com.pvzce.api.content.capability.ProjectileCapability>>
            PROJECTILE_CAPABILITIES = ACCESS.newRegistry(PvzceRegistries.PROJECTILE_CAPABILITIES);

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
    }

    private static void registerPlants() {
        registerStatic(PLANTS, "pvzce:pea_shooter", new PlantDef(
                PvzceIds.id("pea_shooter"),
                new ResourceCost(Map.of(PvzceIds.SUN, PvzceConstants.PEA_SHOOTER_COST),
                        PvzceConstants.PLANT_CARD_COOLDOWN_TICKS),
                300,
                PlacementDef.PLANTABLE,
                List.of(new TypedCapability<PlantCapability>(PlantCapabilities.SHOOTER.id(),
                        new ShooterCapability(ShooterCapability.DEFAULT_INTERVAL,
                                List.of(new ProjectileRef(PvzceIds.id("pea"), 20, 1)), Optional.empty(), 0))),
                Optional.empty(),
                PlantDef.PlantSounds.EMPTY,
                com.pvzce.api.content.AnimationBindings.EMPTY));
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
                com.pvzce.api.content.AnimationBindings.EMPTY));
    }

    private static void registerProjectiles() {
        registerStatic(PROJECTILES, "pvzce:pea", new ProjectileDef(
                PvzceIds.id("pea"),
                ProjectileDef.LAYER_GROUND,
                List.of(),
                Optional.of(PvzceIds.id("linear")),
                ProjectileDef.ProjectileSounds.EMPTY,
                com.pvzce.api.content.AnimationBindings.EMPTY));
    }

    private static void registerResources() {
        registerStatic(RESOURCES, "pvzce:sun", new ResourceDef(
                PvzceIds.SUN,
                PvzceConstants.SUN_VALUE,
                true,
                Identifier.withDefaultNamespace("textures/resource/sun"),
                PvzceIds.id("sun_fall"),
                9990,
                false));
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
     * <p>These literals MUST match {@code data/pvzce/pvzce/liquids/water.json} - the
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
        // Kept in sync with data/pvzce/pvzce/scene_elements/*.json by hand; the pack
        // entry wins whenever it loads.
        registerStatic(SCENE_ELEMENTS, "pvzce:grass", new SceneElementDef(
                PvzceIds.GRASS, PvzceIds.SURFACE_GRASS, List.of(PvzceIds.FEET_PLANTABLE, "grave"), 0F));
        registerStatic(SCENE_ELEMENTS, "pvzce:ground", new SceneElementDef(
                PvzceIds.GROUND, PvzceIds.SURFACE_GROUND,
                List.of(PvzceIds.FEET_PLANTABLE, "flower_pot", "grave"), 0F));
        registerStatic(SCENE_ELEMENTS, "pvzce:water", new SceneElementDef(
                PvzceIds.WATER, PvzceIds.SURFACE_WATER, List.of(PvzceIds.FEET_LILY), 0F,
                Optional.of(PvzceIds.WATER)));
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
        registerRule(PvzceIds.RULE_ZOMBIE_DAMAGE_MULTIPLIER, new GameRuleType.FloatRule(1F, 0F, 100F));
        registerRule(PvzceIds.RULE_ZOMBIE_SPEED_MULTIPLIER, new GameRuleType.FloatRule(1F, 0F, 100F));
        registerRule(PvzceIds.RULE_PLANT_DAMAGE_MULTIPLIER, new GameRuleType.FloatRule(1F, 0F, 100F));
        registerRule(PvzceIds.id("max_players_per_team"), new GameRuleType.IntRule(8, 1, 64));
        registerRule(PvzceIds.RULE_GRAVES_SPAWN_NIGHT, new GameRuleType.BooleanRule(true));
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
        registerSound(PvzceSounds.UI_WIN, "胜利");
        registerSound(PvzceSounds.UI_LOSE, "失败");
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
     * The one registration entry point for built-in content: it registers and then
     * notifies {@link RegistryEntryAddedCallback}. Use this - not
     * {@link Registry#register} - so listeners see statically registered content.
     */
    public static <T> T registerStatic(Registry<T> registry, String id, T value) {
        return Registry.register(registry, Identifier.parse(id), value);
    }
}
