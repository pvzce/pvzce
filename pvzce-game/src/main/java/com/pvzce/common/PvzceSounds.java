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
    public static final Identifier PLANT_THROW = id("sfx/plant/throw");
    public static final Identifier PLANT_PLANT = id("sfx/plant/plant");
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
    public static final Identifier EFFECT_DIRT_RISE = id("sfx/effect/dirt_rise");
    public static final Identifier EFFECT_BONK = id("sfx/effect/bonk");

    public static final Identifier AMBIENT_READY_SET_PLANT = id("sfx/ambient/readysetplant");
    public static final Identifier AMBIENT_HUGE_WAVE = id("sfx/ambient/hugewave");
    public static final Identifier EFFECT_AWOOGA = id("sfx/effect/awooga");

    public static final Identifier UI_COLLECT = id("sfx/ui/collect");
    public static final Identifier UI_CLICK = id("sfx/ui/click");
    public static final Identifier UI_TAP = id("sfx/ui/tap");
    public static final Identifier UI_POINTS = id("sfx/ui/points");
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
