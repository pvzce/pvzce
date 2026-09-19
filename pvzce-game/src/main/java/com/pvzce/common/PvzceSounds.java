package com.pvzce.common;

import com.pvzce.api.util.Identifier;

/**
 * Every built-in default sound event id in one place.
 *
 * <p>These used to be {@code Identifier.withDefaultNamespace("sfx/...")} literals
 * scattered across entities, the level and the command layer, which meant a typo
 * in one copy silently fell back to "no sound" (the sound engine only logs an
 * unknown event). Content JSON may still override any of them through the
 * {@code sounds} block of a definition.
 */
public final class PvzceSounds {
    public static final Identifier PLANT_SHOOT_PEA = id("sfx/plant/shoot_pea");
    /**
     * The hypno-shroom turning a zombie: the original's own "floop".
     *
     * <p>Named for the plant rather than for "charm" because the sound belongs to the mushroom,
     * the way {@code PLANT_SQUASH_HMM} belongs to the squash - a mod's own charming plant would
     * declare its own event instead of inheriting this one.
     */
    public static final Identifier PLANT_HYPNO_FLOOP = id("sfx/plant/floop");
    public static final Identifier PLANT_THROW = id("sfx/plant/throw");
    public static final Identifier PLANT_PLANT = id("sfx/plant/plant");
    /** A plant that grows into a bigger form (the sun-shroom). */
    public static final Identifier PLANT_GROW = id("sfx/plant/plantgrow");
    public static final Identifier PLANT_PLANT_WATER = id("sfx/plant/plant_water");
    public static final Identifier PLANT_WAKEUP = id("sfx/plant/wakeup");

    public static final Identifier PROJECTILE_HIT = id("sfx/projectile/hit");

    public static final Identifier ZOMBIE_GROAN = id("sfx/zombie/groan");
    public static final Identifier ZOMBIE_SHIELD_HIT = id("sfx/zombie/shieldhit");
    public static final Identifier ZOMBIE_LIMBS_POP = id("sfx/zombie/limbs_pop");
    public static final Identifier ZOMBIE_SPLASH = id("sfx/zombie/zombiesplash");
    public static final Identifier ZOMBIE_POLEVAULT = id("sfx/zombie/polevault");
    public static final Identifier ZOMBIE_DIGGER = id("sfx/zombie/digger_zombie");
    public static final Identifier ZOMBIE_GARGANTUAR_THUMP = id("sfx/zombie/gargantuar_thump");
    public static final Identifier ZOMBIE_BALLOON_POP = id("sfx/zombie/balloon_pop");
    public static final Identifier ZOMBIE_BOSS_BOULDER = id("sfx/zombie/bossboulderattack");
    public static final Identifier ZOMBIE_IMP = id("sfx/zombie/imp");

    public static final Identifier EFFECT_EXPLOSION = id("sfx/effect/explosion");
    public static final Identifier EFFECT_BITE = id("sfx/effect/bite");
    public static final Identifier EFFECT_SHOVEL = id("sfx/effect/shovel");
    /** The mower starting up; the only sound it makes, played once per row. */
    public static final Identifier EFFECT_LAWNMOWER = id("sfx/effect/lawnmower");
    public static final Identifier EFFECT_DIRT_RISE = id("sfx/effect/dirt_rise");
    public static final Identifier EFFECT_BONK = id("sfx/effect/bonk");

    public static final Identifier AMBIENT_READY_SET_PLANT = id("sfx/ambient/readysetplant");
    public static final Identifier AMBIENT_HUGE_WAVE = id("sfx/ambient/hugewave");
    public static final Identifier EFFECT_AWOOGA = id("sfx/effect/awooga");

    public static final Identifier UI_COLLECT = id("sfx/ui/collect");
    public static final Identifier UI_CLICK = id("sfx/ui/click");
    public static final Identifier UI_TAP = id("sfx/ui/tap");
    /**
     * Picking a seed packet up.
     *
     * <p>The original's cue, played by the in-game card bar and the seed chooser alike:
     * they are the same gesture on the same object, and the chooser already used it.
     */
    public static final Identifier UI_SEEDLIFT = id("sfx/ui/seedlift");
    public static final Identifier UI_POINTS = id("sfx/ui/points");
    /**
     * The original's refusal: a card that is cooling down, too expensive or spent.
     *
     * <p>Played by the card bar when the player clicks one, so "no" is heard at the click
     * instead of arriving as a rejected placement two actions later.
     */
    public static final Identifier UI_BUZZER = id("sfx/ui/buzzer");
    public static final Identifier UI_COIN = id("sfx/ui/coin");
    /** The original's coin-shower cue, used by the award page's money bag. */
    public static final Identifier UI_MONEY_FALLS = id("sfx/ui/moneyfalls");
    public static final Identifier UI_WIN = id("sfx/ui/win");
    public static final Identifier UI_LOSE = id("sfx/ui/lose");

    public static final Identifier MUSIC_GRASSWALK = id("music/grasswalk");

    public static Identifier id(String path) {
        return Identifier.withDefaultNamespace(path);
    }

    private PvzceSounds() {
    }
}
