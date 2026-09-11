package com.pvzce.common;

import com.pvzce.api.util.Identifier;

/**
 * Built-in registry ids and registry-scoped names in one place.
 *
 * <p>Mirrors {@link PvzceSounds} for identifiers: {@code pvzce:sun},
 * {@code pvzce:plant_team}, the game rules referenced from entities, and the
 * scene surface classes. Anything referenced from more than one class belongs
 * here so the two copies cannot drift.
 */
public final class PvzceIds {
    public static final Identifier SUN = id("sun");
    public static final Identifier REDSTONE = id("redstone");
    public static final Identifier ENERGY_BEAN = id("energy_bean");

    public static final Identifier PLANT_TEAM = id("plant_team");
    public static final Identifier ZOMBIE_TEAM = id("zombie_team");

    public static final Identifier GRASS = id("grass");
    public static final Identifier GROUND = id("ground");

    /**
     * The built-in liquid. A scene element opts into liquid rendering by naming a
     * liquid id, and a ripple event names the liquid it disturbs, so this id is the
     * join between the scene layer, the renderer and the server-side event.
     */
    public static final Identifier WATER = id("water");

    public static final Identifier RULE_DAY_LENGTH = id("day_length");
    public static final Identifier RULE_NIGHT_LENGTH = id("night_length");
    public static final Identifier RULE_SUN_SPAWN_CHANCE = id("sun_spawn_chance");
    public static final Identifier RULE_SUN_VALUE = id("sun_value");
    public static final Identifier RULE_CRATER_RECOVERY = id("crater_recovery");
    public static final Identifier RULE_ZOMBIE_DAMAGE_MULTIPLIER = id("zombie_damage_multiplier");
    public static final Identifier RULE_ZOMBIE_SPEED_MULTIPLIER = id("zombie_speed_multiplier");
    public static final Identifier RULE_PLANT_DAMAGE_MULTIPLIER = id("plant_damage_multiplier");
    public static final Identifier RULE_GRAVES_SPAWN_NIGHT = id("graves_spawn_night");

    public static final Identifier ENV_PLANT_AI = id("plant_ai");

    /** Scene element surface classes (compare with {@link #GRASS}-style element ids). */
    public static final String SURFACE_GRASS = "GRASS";
    public static final String SURFACE_GROUND = "GROUND";
    public static final String SURFACE_WATER = "WATER";
    public static final String SURFACE_ROOF = "ROOF";
    public static final String SURFACE_ROOF_SLOPE = "ROOF_SLOPE";
    public static final String SURFACE_CRATER = "CRATER";
    public static final String SURFACE_GRAVE = "GRAVE";

    /** Placement feet values. */
    public static final String FEET_GROUND = "ground";
    public static final String FEET_LILY = "lily";
    public static final String FEET_PLANT = "plant";
    public static final String FEET_PLANTABLE = "plantable";

    public static Identifier id(String path) {
        return Identifier.withDefaultNamespace(path);
    }

    private PvzceIds() {
    }
}
