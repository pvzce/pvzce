package com.pvzce.common.core;

import com.pvzce.api.content.DialogueCharacterDef;
import com.pvzce.api.content.EnvVarType;
import com.pvzce.api.content.GameRuleType;
import com.pvzce.api.content.LevelCategoryDef;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.LevelThemeDef;
import com.pvzce.api.content.LiquidDef;
import com.pvzce.api.content.ParticleDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.ProjectileDef;
import com.pvzce.api.content.ResourceDef;
import com.pvzce.api.content.SceneElementDef;
import com.pvzce.api.content.SlotDef;
import com.pvzce.api.content.SoundEventDef;
import com.pvzce.api.content.ToolDef;
import com.pvzce.api.content.ZombieDef;
import com.pvzce.api.content.capability.CapabilityType;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.content.capability.ProjectileCapability;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.registry.Registry;
import com.pvzce.api.registry.ResourceKey;
import com.pvzce.api.util.Identifier;

/**
 * PVZCE registry keys. Mirrors {@code net.minecraft.core.registries.Registries}.
 *
 * <p>The id (singular, e.g. {@code pvzce:plant}) is what tags and code use; the
 * content directory spelling (plural, e.g. {@code plants}) lives in
 * {@code PvzceDataLoader.CONTENT_REGISTRIES} so there is exactly one place that
 * decides which folder belongs to which registry.
 */
public final class PvzceRegistries {
    private static final Identifier ROOT = Identifier.withDefaultNamespace("root");

    public static final ResourceKey<Registry<PlantDef>> PLANTS = key("plant");
    public static final ResourceKey<Registry<ZombieDef>> ZOMBIES = key("zombie");
    public static final ResourceKey<Registry<ProjectileDef>> PROJECTILES = key("projectile");
    public static final ResourceKey<Registry<ResourceDef>> RESOURCES = key("resource");
    public static final ResourceKey<Registry<SlotDef>> SLOT_TYPES = key("slot");
    public static final ResourceKey<Registry<ToolDef>> TOOLS = key("tool");
    public static final ResourceKey<Registry<SceneElementDef>> SCENE_ELEMENTS = key("scene_element");
    public static final ResourceKey<Registry<LiquidDef>> LIQUIDS = key("liquid");
    public static final ResourceKey<Registry<GameRuleType<?>>> GAME_RULES = key("game_rule");
    public static final ResourceKey<Registry<EnvVarType<?>>> ENV_VAR_TYPES = key("env_var_type");
    public static final ResourceKey<Registry<SoundEventDef>> SOUND_EVENTS = key("sound_event");
    public static final ResourceKey<Registry<ParticleDef>> PARTICLES = key("particle");
    public static final ResourceKey<Registry<LevelDef>> LEVELS = key("level");
    public static final ResourceKey<Registry<LevelThemeDef>> LEVEL_THEMES = key("level_theme");
    public static final ResourceKey<Registry<LevelCategoryDef>> LEVEL_CATEGORIES = key("level_category");
    public static final ResourceKey<Registry<DialogueCharacterDef>> DIALOGUE_CHARACTERS =
            key("dialogue_character");
    public static final ResourceKey<Registry<CapabilityType<PlantCapability>>> PLANT_CAPABILITIES =
            key("plant_capability");
    public static final ResourceKey<Registry<CapabilityType<ZombieCapability>>> ZOMBIE_CAPABILITIES =
            key("zombie_capability");
    public static final ResourceKey<Registry<CapabilityType<ProjectileCapability>>> PROJECTILE_CAPABILITIES =
            key("projectile_capability");
    /**
     * Registered level mechanics, keyed by the id a level's {@code "mechanics"} list names.
     *
     * <p>Code-registered rather than data-driven (like the capability registries above):
     * a mechanic is behaviour, and its JSON block is decoded by the mechanic itself.
     */
    public static final ResourceKey<Registry<com.pvzce.common.level.mechanic.LevelMechanic<?>>>
            LEVEL_MECHANICS = key("level_mechanic");

    /**
     * Every data-driven registry, keyed by the name the command layer uses.
     *
     * <p>The command layer used to keep three more hand-typed copies of this list
     * (the {@code /pvzce registry list} categories, the identifier argument's
     * suggestion categories and the tag-registry categories), spelled differently
     * and already inconsistent - {@code /spawn} could not complete {@code scene}
     * element ids and the tag categories were a fourth list. Everything now reads
     * this table.
     */
    public static java.util.Map<String, ResourceKey<? extends Registry<?>>> byCategory() {
        java.util.Map<String, ResourceKey<? extends Registry<?>>> map = new java.util.LinkedHashMap<>();
        map.put("plant", PLANTS);
        map.put("zombie", ZOMBIES);
        map.put("projectile", PROJECTILES);
        map.put("resource", RESOURCES);
        map.put("slot", SLOT_TYPES);
        map.put("tool", TOOLS);
        map.put("scene_element", SCENE_ELEMENTS);
        map.put("liquid", LIQUIDS);
        map.put("game_rule", GAME_RULES);
        map.put("env_var_type", ENV_VAR_TYPES);
        map.put("sound_event", SOUND_EVENTS);
        map.put("particle", PARTICLES);
        map.put("level", LEVELS);
        map.put("level_theme", LEVEL_THEMES);
        map.put("level_category", LEVEL_CATEGORIES);
        map.put("dialogue_character", DIALOGUE_CHARACTERS);
        // Capability types are code-registered rather than data-driven, but they are
        // still registries: listing them is how a mod author checks what a data file
        // may reference, and including them keeps this table a complete index.
        map.put("plant_capability", PLANT_CAPABILITIES);
        map.put("zombie_capability", ZOMBIE_CAPABILITIES);
        map.put("projectile_capability", PROJECTILE_CAPABILITIES);
        map.put("level_mechanic", LEVEL_MECHANICS);
        return java.util.Map.copyOf(map);
    }

    /** Accepted aliases for the category names above (and the content directory spellings). */
    public static java.util.Map<String, String> categoryAliases() {
        return java.util.Map.ofEntries(
                java.util.Map.entry("plants", "plant"),
                java.util.Map.entry("zombies", "zombie"),
                java.util.Map.entry("projectiles", "projectile"),
                java.util.Map.entry("resources", "resource"),
                java.util.Map.entry("slots", "slot"),
                java.util.Map.entry("tools", "tool"),
                java.util.Map.entry("scene", "scene_element"),
                java.util.Map.entry("scene_elements", "scene_element"),
                java.util.Map.entry("liquids", "liquid"),
                java.util.Map.entry("game_rules", "game_rule"),
                java.util.Map.entry("env_var_types", "env_var_type"),
                java.util.Map.entry("sound_events", "sound_event"),
                java.util.Map.entry("levels", "level"),
                java.util.Map.entry("level_themes", "level_theme"),
                java.util.Map.entry("level_categories", "level_category"),
                java.util.Map.entry("dialogue_characters", "dialogue_character"),
                java.util.Map.entry("plant_capabilities", "plant_capability"),
                java.util.Map.entry("zombie_capabilities", "zombie_capability"),
                java.util.Map.entry("projectile_capabilities", "projectile_capability"),
                java.util.Map.entry("level_mechanics", "level_mechanic"));
    }

    /** Resolves a user-supplied category name (with aliases) to its canonical id. */
    public static String canonicalCategory(String name) {
        if (name == null) {
            return "";
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return categoryAliases().getOrDefault(lower, lower);
    }

    private static <T> ResourceKey<Registry<T>> key(String path) {
        return ResourceKey.create(ROOT, Identifier.withDefaultNamespace(path));
    }

    private PvzceRegistries() {
    }
}
