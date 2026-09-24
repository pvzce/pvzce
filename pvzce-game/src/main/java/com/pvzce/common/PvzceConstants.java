package com.pvzce.common;

/**
 * Numerical constants shared by the simulation and the client.
 *
 * <p>The namespace lives on {@link com.pvzce.api.util.Identifier#DEFAULT_NAMESPACE}
 * and the game version on {@code com.pvzce.launcher.PvzceVersions}; this class used
 * to declare its own copies of both, which were dead and looked authoritative.
 */
public final class PvzceConstants {
    public static final int TICKS_PER_SECOND = 60;
    public static final long NANOS_PER_TICK = 16_666_666L;

    public static final int DEFAULT_GRID_WIDTH = 9;
    public static final int DEFAULT_GRID_HEIGHT = 5;

    public static final int PLANT_CARD_COOLDOWN_TICKS = 300;
    public static final int PEA_SHOOTER_COST = 100;
    public static final int INITIAL_SUN = 150;
    public static final int SUN_VALUE = 25;
    /**
     * What the sky does between two suns, in ticks, when a level does not say.
     *
     * <p>The defaults for {@code pvzce:sun_spawn_interval_min} / {@code _max}: eight to twelve
     * seconds. The pair replaces the per-tick {@code sun_spawn_chance} this used to be, because
     * an independent roll every tick has no memory - a player could wait half a minute and then
     * collect three suns inside two seconds, and neither is a thing the level asked for.
     *
     * <p>Ten seconds is the number the old chance was tuned to ({@code 0.0017} is one sun per
     * 588 ticks); writing the range down instead of the rate is what makes the spread a
     * decision rather than an accident.
     */
    public static final int SUN_SPAWN_INTERVAL_MIN = 8 * TICKS_PER_SECOND;
    public static final int SUN_SPAWN_INTERVAL_MAX = 12 * TICKS_PER_SECOND;
    /**
     * How long the first sun of a level takes.
     *
     * <p>Separate from the steady interval because the opening is the one moment the sky is
     * allowed to be prompt: the player's first producer costs 50 sun and a fresh level starts
     * with just enough, so a first sun that arrives after the full interval is a first sun the
     * player has already stopped waiting for.
     */
    public static final int SUN_SPAWN_INITIAL_TICKS = 5 * TICKS_PER_SECOND;
    /**
     * How long a zombie takes to climb out of a grave, in ticks, when a level does not say.
     *
     * <p>The default for the {@code pvzce:zombie_rise_ticks} rule. It lives here rather than
     * beside the code that climbs because the rule registry is in {@code common/core} and the
     * entity is not - and the number has to be readable by both.
     */
    public static final int ZOMBIE_RISE_TICKS = 60;
    /**
     * How far below its cell a zombie starts when it climbs out of a grave, in cells.
     *
     * <p>Shared rather than owned by the server because it is the unit the climb is
     * <em>published</em> in: the server moves the riser's height from
     * {@code -ZOMBIE_RISE_DEPTH_CELLS} to zero, and the client turns that number back into
     * "how much of the body is still underground" to work out where the lawn surface has to
     * cut it. Two copies of this number would put the cut at the wrong height, which shows up
     * as a zombie that pops rather than rises.
     */
    public static final float ZOMBIE_RISE_DEPTH_CELLS = 1.15F;
    /**
     * How many cards a fresh backpack holds, and the ceiling an upgrade may reach.
     *
     * <p>A level that does not declare {@code max_seed_slots} uses the backpack's number
     * (see {@code LevelDef.effectiveMaxSeedSlots}), so this is what an ordinary level hands
     * the player. The ceiling is shared with the level editor's own field limit
     * ({@code CardPoolEditorDialog}) because both end up on the same card bar.
     */
    public static final int DEFAULT_SEED_SLOTS = 8;
    public static final int MAX_SEED_SLOTS = 12;
    /**
     * How many level buffs a fresh backpack may switch on at once, and the ceiling.
     *
     * <p>Exactly the same arrangement as {@link #DEFAULT_SEED_SLOTS}, one system over: a level
     * that does not declare {@code buffs.max_slots} is sized by the backpack's number (see
     * {@code LevelDef.effectiveMaxBuffSlots}). The two are separate counts because they are
     * separate choices - a level may hand out eight cards and no buffs, or two cards and five
     * buffs.
     */
    public static final int DEFAULT_BUFF_SLOTS = 5;
    public static final int MAX_BUFF_SLOTS = 12;
    /** The wallet has no ceiling; the only bound that matters is the 32-bit field it lives in. */
    public static final int COIN_LIMIT = Integer.MAX_VALUE;

    private PvzceConstants() {
    }
}
