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

    /**
     * The persistent currency, in the original's four denominations.
     *
     * <p>Unlike sun it outlives a run: whatever a level collected is banked into the
     * world's {@code profile.dat} when the level ends, win or lose. They are separate
     * resources rather than one resource with an amount because each is a different
     * <em>object</em> with its own sprite and its own worth - a single "coin" id could
     * not tell the player whether they just picked up 10 or 1000.
     *
     * <p>What one is worth is {@code ResourceDef.defaultValue} in
     * {@code data/pvzce/resources/}, and a drop is spawned with that value as its
     * amount, so a team's resource count for a denomination already reads in coins.
     */
    public static final Identifier COIN_SILVER = id("coin_silver");
    public static final Identifier COIN_GOLD = id("coin_gold");
    public static final Identifier DIAMOND = id("diamond");
    public static final Identifier MONEY_BAG = id("money_bag");

    /** Every denomination, in ascending worth; the HUD and the bank sum over this. */
    public static final java.util.List<Identifier> COIN_DENOMINATIONS =
            java.util.List.of(COIN_SILVER, COIN_GOLD, DIAMOND, MONEY_BAG);

    /** True when this resource is money rather than a level resource like sun. */
    public static boolean isCoin(Identifier resource) {
        return resource != null && COIN_DENOMINATIONS.contains(resource);
    }

    /** True when this wire id names money; tolerant of ids the client cannot parse. */
    public static boolean isCoin(String resourceId) {
        return isCoin(Identifier.tryParse(resourceId));
    }

    /** The plant a fresh profile starts with, and the first level's only plant card. */
    public static final Identifier STARTER_PLANT = id("pea_shooter");
    /** The one tool a fresh profile starts with, so a misplaced plant can be dug up. */
    public static final Identifier STARTER_TOOL = id("shovel");

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

    /**
     * Built-in level mechanics, the ids a level's {@code mechanics} list may name.
     *
     * <p>{@code deck} is the implicit default: a level that declares no card source gets
     * it, which is what keeps every ordinary level's JSON free of a block that only says
     * "the normal rules apply". {@code conveyor} and {@code placement_zone} are the two
     * mechanics Wall-nut Bowling is built from, and they are independent - a normal level
     * may restrict its plantable area without having a belt, and a belt level may use the
     * whole lawn.
     */
    public static final Identifier MECHANIC_DECK = id("deck");
    public static final Identifier MECHANIC_CONVEYOR = id("conveyor");
    public static final Identifier MECHANIC_PLACEMENT_ZONE = id("placement_zone");

    /** Scene element surface classes (compare with {@link #GRASS}-style element ids). */
    public static final String SURFACE_GRASS = "GRASS";
    public static final String SURFACE_GROUND = "GROUND";
    public static final String SURFACE_WATER = "WATER";
    public static final String SURFACE_ROOF = "ROOF";
    public static final String SURFACE_ROOF_SLOPE = "ROOF_SLOPE";
    public static final String SURFACE_CRATER = "CRATER";
    public static final String SURFACE_GRAVE = "GRAVE";

    /**
     * The placement "feet" strings are gone. What a plant may be planted on is
     * now {@code #c:*} tags ({@code #c:plantable}, {@code #c:water},
     * {@code #c:requires_ground}, ...) declared in
     * {@code data/c/tags/}; see {@link com.pvzce.common.tag.PvzceTags} and
     * {@link com.pvzce.common.core.PlantPlacement}.
     */

    public static Identifier id(String path) {
        return Identifier.withDefaultNamespace(path);
    }

    private PvzceIds() {
    }
}
