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
    /** The hole a blast leaves in bare ground. */
    public static final Identifier CRATER = id("crater");
    /** The same hole while it is filling back in, for the end of its recovery. */
    public static final Identifier CRATER_FADING = id("crater_fading");

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
    /**
     * How often a dying zombie leaves a sun behind, 0..1.
     *
     * <p>Separate from {@code sun_spawn_chance}, which is the sky: a level can have no sun fall
     * from above and still pay for kills, which is exactly what a level with no sun producers
     * and no sky needs. The original's Whack-a-Zombie is the case it exists for.
     */
    public static final Identifier RULE_ZOMBIE_SUN_DROP_CHANCE = id("zombie_sun_drop_chance");
    /**
     * How many suns one paying kill drops.
     *
     * <p>The other half of {@code zombie_sun_drop_chance}: three suns scattered around where the
     * zombie fell is a different offer from one, and a level with no producers and no sky pays
     * its player in exactly this currency. The chance is read against the count, so a level that
     * wants the same income in fewer, better moments lowers the chance and keeps the count.
     */
    public static final Identifier RULE_ZOMBIE_SUN_DROP_COUNT = id("zombie_sun_drop_count");
    public static final Identifier RULE_CRATER_RECOVERY = id("crater_recovery");
    public static final Identifier RULE_ZOMBIE_DAMAGE_MULTIPLIER = id("zombie_damage_multiplier");
    public static final Identifier RULE_ZOMBIE_SPEED_MULTIPLIER = id("zombie_speed_multiplier");
    public static final Identifier RULE_PLANT_DAMAGE_MULTIPLIER = id("plant_damage_multiplier");
    /**
     * How much faster than written this level's zombies arrive, as a multiplier on the rate.
     *
     * <p>Scales the level's whole spawn cadence - the gap between waves and the gap between
     * the zombies inside one wave - so {@code 2.0} plays the same wave table at twice the
     * speed: the same zombies, in half the time, with the relative pacing the author wrote.
     * Distinct from {@link #RULE_ZOMBIE_SPEED_MULTIPLIER}, which is how fast a zombie that is
     * already on the lawn walks; this one is how fast the next one shows up.
     *
     * <p>It exists for the levels whose pressure comes from somewhere else - a conveyor belt
     * hands out cards at its own fixed rate, so "the belt gives you this much and the horde
     * arrives this fast" is a knob the wave table alone cannot express without rewriting every
     * delay in it.
     */
    public static final Identifier RULE_ZOMBIE_SPAWN_SPEED_MULTIPLIER = id("zombie_spawn_speed_multiplier");
    /**
     * How long this level's cards take to recharge, as a multiple of the card's own cooldown.
     *
     * <p>The original's mini-games are where the card bar stops behaving like the adventure's:
     * Sleep Deprivation hands the player five cards and lets them come back three times as
     * fast. A multiplier rather than a per-card override because a level that wants faster
     * cards wants it for the bar it dealt, and because the card's own number is authored in
     * the plant/tool definition - a level is the wrong place to restate it.
     */
    public static final Identifier RULE_SEED_COOLDOWN_MULTIPLIER = id("seed_cooldown_multiplier");
    /**
     * Whether this level's graves give up their dead at the last wave.
     *
     * <p>The name is the original rule's; what it means changed from "roll for a zombie on
     * every grave every tick" to "every grave opens once, when the final wave arrives". The
     * per-tick roll fed a zombie every four seconds from the four graves a night level
     * ships, which is the 2-5 minigame and not what a level wants its scenery to do; the
     * last wave is where the original puts it.
     */
    public static final Identifier RULE_GRAVES_SPAWN_NIGHT = id("graves_spawn_night");
    /**
     * How long a zombie takes to climb out of a grave, in ticks.
     *
     * <p>A rule rather than a constant because it is pacing: a level whose graves open one
     * after another wants the climb to be a beat the player can see, and a level where the
     * whole lawn erupts at once wants it over with.
     */
    public static final Identifier RULE_ZOMBIE_RISE_TICKS = id("zombie_rise_ticks");

    public static final Identifier ENV_PLANT_AI = id("plant_ai");

    /**
     * Built-in damage types - the answer to "does armour absorb this".
     *
     * <p>{@code pvzce:ash} is the ash line's blast (cherry bomb, Jalapeno, Doom
     * Shroom, potato mine, Squash): it lands on the body, so the cone a pea has to
     * chew through does not save a Conehead from a cherry. {@code projectile} is an
     * ordinary shot and {@code impact} a hit with no projectile to describe (a
     * rolling bowling Wall-nut, a Gargantuar's fist); both let armour absorb first.
     * {@code splash} is the thrown-plant blast - a melon's area damage is authored
     * as a blast in the original too, which is why it shares the ash line's armour
     * rule rather than the shooter's. {@code mower} is the lawn mower and the hammer
     * tool: it does not wear what it hits down, it removes it.
     *
     * <p>{@code spray} is the fume-shroom's cloud, and the only type whose meaning is
     * a <em>slot</em> rather than a yes/no about armour: it goes past what is held in
     * front (a screen door, a newspaper) and is still absorbed by what is worn on the
     * head, which is why {@code DamageTypeDef} needs both flags to describe it.
     *
     * <p>The declarations live in {@code data/pvzce/damage_types/}; these ids exist
     * so code and data cannot drift, exactly as {@link PvzceSounds} does for sounds.
     */
    public static final Identifier DAMAGE_ASH = id("ash");
    public static final Identifier DAMAGE_IMPACT = id("impact");
    public static final Identifier DAMAGE_PROJECTILE = id("projectile");
    public static final Identifier DAMAGE_SPLASH = id("splash");
    public static final Identifier DAMAGE_SPRAY = id("spray");
    public static final Identifier DAMAGE_MOWER = id("mower");

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
    /**
     * Lawn mowers, one per row by default.
     *
     * <p>Unlike the other mechanics this one is <em>implicit</em>: every ordinary level has
     * mowers whether or not its file mentions them, because that is what the original does
     * and a level that lost them would be a different, unfairer level. A level that wants
     * to change them (Wall-nut Bowling has none) declares the mechanic and lists its rows.
     */
    public static final Identifier MECHANIC_MOWER = id("mower");
    /**
     * A tool the level hands the player on its own terms.
     *
     * <p>Not a tool <em>card</em>: the block can reprice or re-time a tool, and it can make one
     * the level's plain click ({@code "default": true}) - the original's mallet in
     * Whack-a-Zombie, which is the cursor rather than a seed packet. It is not a card source, so
     * a level declares it beside its deck.
     */
    public static final Identifier MECHANIC_TOOL = id("tool");
    /**
     * Graves that keep giving up their dead while the level runs.
     *
     * <p>Whack-a-Zombie's shape: the level's zombies come out of the gravestones, not off the
     * road, and the graves a player smashes come back. Distinct from the
     * {@code graves_spawn_night} rule, which is the other thing graves do - open once, at the
     * last wave - and which every night level gets by default.
     */
    public static final Identifier MECHANIC_GRAVE_SPAWNER = id("grave_spawner");
    /**
     * Gravestones scattered over part of the lawn when the level starts.
     *
     * <p>The other half of the original's night lawns. Every night level from 2-1 on opens with
     * tombstones standing in the half of the lawn furthest from the house, in a layout that is
     * different every time; they block planting, and the {@code graves_spawn_night} rule opens
     * whatever is still standing at the final wave. Where they stand is a property of the lawn
     * rather than of the level file, which is why it is a mechanic and not a list of cells in
     * {@code scene}: the file cannot say "seven of them, over there".
     */
    public static final Identifier MECHANIC_GRAVE_FIELD = id("grave_field");

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
