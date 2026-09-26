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
    /**
     * A torchwood lighting a pea as it goes through.
     *
     * <p>The original's own ignite, and the sound this project had a file for and no caller: it
     * was wired to {@link #EFFECT_BONK} instead, so the player heard the mallet's knock when a
     * pea caught fire and reasonably read it as a hit.
     */
    public static final Identifier PLANT_FIREPEA = id("sfx/plant/firepea");
    /**
     * A bowled nut hitting a zombie.
     *
     * <p>Two ids because a nut caroming through a crowd hits about every 100 ms and the client
     * folds a repeat of the <em>same</em> event inside 130 ms - alternating them is what keeps a
     * five-zombie chain from sounding like two hits. The nut used to play {@link #EFFECT_BONK},
     * which is the mallet's own sound and belongs to the tool that swings it.
     */
    public static final Identifier PROJECTILE_BOWLING_IMPACT = id("sfx/projectile/bowlingimpact");
    public static final Identifier PROJECTILE_BOWLING_IMPACT_ALT = id("sfx/projectile/bowlingimpact2");

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
    /** The dancing zombie's call: the original's own sting for the backup dancers. */
    public static final Identifier ZOMBIE_DANCER = id("sfx/zombie/dancer");
    /** The pogo stick: the boing of a bounce, and the bonk of one that meets a tall-nut. */
    public static final Identifier ZOMBIE_POGO = id("sfx/zombie/pogo_zombie");
    /**
     * The jack-in-the-box's crank and its lid coming up.
     *
     * <p>Two events because the zombie makes two noises and they mean different things: the
     * crank is the 110 ticks the player has to answer it in, and the surprise is the blast.
     */
    public static final Identifier ZOMBIE_JACK_IN_THE_BOX = id("sfx/zombie/jackinthebox");
    public static final Identifier ZOMBIE_JACK_SURPRISE = id("sfx/zombie/jack_surprise");

    public static final Identifier EFFECT_EXPLOSION = id("sfx/effect/explosion");
    public static final Identifier EFFECT_BITE = id("sfx/effect/bite");
    public static final Identifier EFFECT_SHOVEL = id("sfx/effect/shovel");
    /** The mower starting up; the only sound it makes, played once per row. */
    public static final Identifier EFFECT_LAWNMOWER = id("sfx/effect/lawnmower");
    public static final Identifier EFFECT_DIRT_RISE = id("sfx/effect/dirt_rise");
    public static final Identifier EFFECT_BONK = id("sfx/effect/bonk");
    /** The ice-shroom's freeze: the original's whole-lawn "frozen" sting. */
    public static final Identifier EFFECT_FROZEN = id("sfx/effect/frozen");
    /**
     * One pour of the watering can.
     *
     * <p>The event existed in the pack from the start - it is the original's own watering sound -
     * and had no caller until 3-4 handed the can over.
     */
    public static final Identifier EFFECT_WATERING = id("sfx/effect/watering");

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
    /**
     * The coin cue, named by the coin resources' {@code pickup_sound} rather than played from
     * code; the constant exists so a test can compare against the name instead of the string.
     */
    public static final Identifier UI_COIN = id("sfx/ui/coin");
    /** The original's coin-shower cue, used by the award page's money bag. */
    public static final Identifier UI_MONEY_FALLS = id("sfx/ui/moneyfalls");

    /** The background track of an ordinary day level; a level's music timeline defaults to it. */
    public static final Identifier MUSIC_GRASSWALK = id("music/grasswalk");
    /**
     * The two end-of-level stingers, played by the music controller as the run ends.
     *
     * <p>They are music rather than {@code sfx/ui/*}: the original ships one file per jingle and
     * this project used to declare each of them twice, as a {@code music/} event and as an
     * {@code sfx/ui/} one. Only the {@code music/} pair was ever played, so the aliases are gone
     * and these names say which of the two spellings survived.
     */
    public static final Identifier MUSIC_WIN = id("music/win");
    public static final Identifier MUSIC_LOSE = id("music/lose");

    public static Identifier id(String path) {
        return Identifier.withDefaultNamespace(path);
    }

    private PvzceSounds() {
    }
}
