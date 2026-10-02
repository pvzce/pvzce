package com.pvzce.common;

/**
 * Numerical constants shared by the simulation and the client.
 *
 * <p>The namespace lives on {@link com.pvzce.api.util.Identifier#DEFAULT_NAMESPACE}
 * and the game version on {@code com.pvzce.launcher.PvzceVersions}; this class used
 * to declare its own copies of both, which were dead and looked authoritative.
 */
public final class PvzceConstants {
    /** Fertilizer speeds one plant up for twenty seconds; the tool recharges in forty-five. */
    public static final float FERTILIZER_ACTION_SPEED = 1.5F;
    public static final int FERTILIZER_DURATION_TICKS = 1200;
    public static final float BUTTER_BUFF_CHANCE = 0.40F;
    /** Original roof: five sloped columns, rising 20 pixels per column in an 85-pixel row. */
    public static final float ROOF_SLOPE_COLUMNS = 5F;
    public static final float ROOF_HEIGHT = 100F / 85F;
    public static final int UMBRELLA_BLOCK_TICKS = 45;
    public static final int LADDER_PLACE_TICKS = 100;
    public static final int CATAPULT_AMMO = 20;
    public static final int CATAPULT_INTERVAL_TICKS = 300;
    public static final int CATAPULT_SHOOT_TICKS = 60;
    public static final int CATAPULT_BALL_DAMAGE = 75;
    public static final float CATAPULT_STOP_X = 7.125F;
    /** Seed packets descend across the lawn at this many cells per second. */
    public static final float SEED_RAIN_FALL_SPEED = 0.8F;
    public static final int PORTAL_WARNING_TICKS = 300;
    public static final float PORTAL_EXIT_OFFSET = 0.51F;
    public static final int PORTAL_IMMUNITY_TICKS = 30;
    public static final java.util.List<Integer> WHACK_DOUBLE_CHANCE = java.util.List.of(0, 30, 10, 10, 15, 18);
    public static final java.util.List<Integer> WHACK_TRIPLE_CHANCE = java.util.List.of(0, 0, 0, 0, 10, 13);
    public static final java.util.List<Integer> WHACK_BUCKET_CHANCE = java.util.List.of(0, 0, 0, 10, 15, 15);
    public static final java.util.List<Integer> WHACK_CONE_CHANCE = java.util.List.of(0, 0, 30, 30, 30, 30);
    public static final int WHACK_INITIAL_INTERVAL = 100;
    public static final int WHACK_FINAL_INTERVAL = 30;
    public static final int TICKS_PER_SECOND = 60;
    public static final int CACTUS_DAMAGE = 20;
    public static final int CACTUS_SHOT_INTERVAL_TICKS = 90;
    public static final int CACTUS_RISE_TICKS = 75;
    public static final int CACTUS_LOWER_TICKS = 65;
    public static final int MAGNET_RECOVERY_TICKS = 900;
    public static final int MAGNET_PULL_TICKS = 130;
    public static final int BLOVER_LINGER_TICKS = 30;
    public static final int DIGGER_AXE_PAUSE_TICKS = 90;
    public static final int BLOVER_FOG_CLEAR_TICKS = 2400;
    public static final int BLOVER_FOG_RETURN_TICKS = 180;
    public static final int DIGGER_RISE_TICKS = 78;
    public static final int DIGGER_LAND_TICKS = 18;
    public static final int DIGGER_DIZZY_TICKS = 210;
    public static final int BALLOON_FALL_TICKS = 140;
    public static final float BALLOON_AIR_SPEED = 0.47F;
    public static final float BALLOON_GROUND_SPEED = 0.23F;

    public static final long NANOS_PER_TICK = 16_666_666L;

    public static final int DEFAULT_GRID_WIDTH = 9;
    public static final int DEFAULT_GRID_HEIGHT = 5;

    public static final int PLANT_CARD_COOLDOWN_TICKS = 300;
    public static final int ECHO_INTERVAL_TICKS = 180;
    public static final int ECHO_DAMAGE = 40;
    public static final int ECHO_LINKED_DAMAGE = 60;
    public static final int ECHO_RELAY_TICKS = 8;
    public static final int RESONANCE_INTERVAL_TICKS = 720;
    public static final int ECHO_CHARGE_VOLLEYS = 3;
    public static final int ECHO_RESONANCE_TICKS = 360;
    public static final float ECHO_RESONANCE_BASE_RATE = 1.5F;
    public static final float ECHO_RESONANCE_RATE_PER_LILY = 0.25F;
    public static final float ECHO_RESONANCE_RATE = 3F;
    public static final float RESONANCE_PLANT_RATE = ECHO_RESONANCE_RATE;
    public static final float RESONANCE_ZOMBIE_SPEED = 1.35F;
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
     * How long the zamboni's ice trail lasts before it melts, in ticks.
     *
     * <p>The default for the {@code pvzce:ice_melt} rule, and the original's own number: half a
     * minute. Kept here rather than in the zamboni because the ice is terrain the level owns by
     * the time it exists - the zombie that made it may already be dead, and the cells it left
     * melt on the level's clock, not on the machine's.
     */
    public static final int ICE_MELT_TICKS = 30 * TICKS_PER_SECOND;
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
    /**
     * How long a mutation level waits for its first mutation, in ticks.
     *
     * <p>Half a minute: the player has to have planted something before the lawn starts
     * rewriting itself, and at 地狱 the tier's own interval is thirty seconds - far too short to
     * double as a grace period. The default for {@code pvzce:mutation_initial_ticks}.
     */
    public static final int MUTATION_INITIAL_TICKS = 30 * TICKS_PER_SECOND;
    /** The default for {@code pvzce:mutation_interval_multiplier}: the tier's interval as written. */
    public static final float MUTATION_INTERVAL_MULTIPLIER = 1F;
    /** The wallet has no ceiling; the only bound that matters is the 32-bit field it lives in. */
    public static final int COIN_LIMIT = Integer.MAX_VALUE;

    /**
     * How many suns one of the vase level's sun pots pays out: three, 75 sun.
     *
     * <p>The user's own number, and the original's: a pot that holds sun drops a bundle of three
     * rather than a single sun. It is not a rounding detail - the first round of 4-5 holds two
     * sun pots, and three suns each is exactly the 150 the level's one plant (a cherry bomb)
     * costs, which is what makes the round's economy add up.
     *
     * <p>A pot's contents say <em>which</em> resource it holds ({@code kind: "sun"}); how much of
     * it comes out is this constant, shared by the mechanic that breaks the pot and the client
     * that draws the bundle.
     */
    public static final int SCARY_POT_SUN_DROPS = 3;
    /**
     * How far apart the suns of one bundle sit, in cells, either side of the pot's own cell.
     *
     * <p>Three suns dropped at one point stack into what looks like a single sun until they are
     * collected one at a time. The gap has to be read against the size of a sun: it is drawn
     * {@code 0.8} cells wide plus the sun's own {@code render_scale} of {@code 1.2}, so nearly a
     * whole cell - which is why "slightly apart" is 0.42 and not 0.1.
     */
    public static final float SCARY_POT_SUN_SPREAD = 0.42F;
    /**
     * How long a seed packet a broken container dropped lies on the lawn, in ticks.
     *
     * <p>Twenty seconds, then it is gone. The packet is a plant the player was given and has not
     * picked up yet, so the clock is what makes "break the pot" and "use the plant" two
     * decisions rather than one - and the flash in the last
     * {@link #CARD_DROP_FLASH_TICKS} is what says the second one is running out.
     */
    public static final int CARD_DROP_LIFETIME_TICKS = 20 * TICKS_PER_SECOND;
    /** How long before a seed packet expires it starts flashing, in ticks. */
    public static final int CARD_DROP_FLASH_TICKS = 5 * TICKS_PER_SECOND;

    // ------------------------------------------------------------------
    // The rhythm levels' energy bar
    //
    // The mode's second economy, beside the sun a PERFECT note drops: every judgement is worth
    // points, everything bleeds five a second, and what the bar buys is firepower - the plants
    // double their bullets while it is high and triple them while it is higher. The numbers live
    // here rather than in `RhythmChartData` because they are the *mode's* rules and not a chart's
    // tuning: all four tiers play by these, the server runs them and the client draws the two
    // thresholds, so both sides have to read the same constants.
    // ------------------------------------------------------------------

    /** What a PERFECT note is worth. */
    public static final int ENERGY_PERFECT = 100;
    /** What a GOOD note is worth. */
    public static final int ENERGY_GOOD = 50;
    /** What a FAIR note - the widest window, the one that only just counted - is worth. */
    public static final int ENERGY_FAIR = 20;
    /**
     * What a note nobody played costs.
     *
     * <p>Negative, and the only entry of the four that is: the bar is the run's pressure, and a
     * chart whose misses were free would be a chart with no reason to press anything.
     */
    public static final int ENERGY_MISS = -10;
    /**
     * How much the bar bleeds, per second, whatever the player is doing.
     *
     * <p>Five a second is three hundred a minute against a hundred per PERFECT, so the bar is
     * something that has to be <em>kept</em> rather than something earned once. The drain is
     * fractional per tick (see {@code RhythmMechanic}) so a second really is five points and not
     * five rounded somewhere.
     */
    public static final int ENERGY_DRAIN_PER_SECOND = 5;
    /**
     * The bar's ceiling: a full bar, and the point past which a PERFECT pays nothing.
     *
     * <p>Above both gates rather than equal to the lower one, because a ceiling that sat exactly
     * on a gate would make the gate above it unreachable: with the bar capped at 6000 the 12000
     * gate was a number no run could ever cross. Fifteen thousand is the second gate plus a
     * quarter: enough slack that a player who has earned the tripling keeps it through a bad bar
     * of notes, and still a number the HUD can draw a tick on without the ticks colliding.
     */
    public static final int ENERGY_MAX = 15000;
    /**
     * The bar's first gate: at or above this, every plant fires twice the bullets per attack.
     *
     * <p>Read as "at or above" rather than "above", and with no hysteresis: the bar crosses the
     * line and the firepower changes on that tick. A gate that remembered being open would make
     * the number on the bar stop meaning what it says.
     */
    public static final int ENERGY_DOUBLE_AT = 6000;
    /** The second gate: at or above this, three times the bullets. */
    public static final int ENERGY_TRIPLE_AT = 12000;

    /**
     * The consecutive-PERFECT counts that each set the lawn alight, in order.
     *
     * <p>Four of them, and they are the user's own numbers: the first is reachable inside a good
     * chart's first half, the last is a near-flawless run. Past the last entry the streak keeps
     * paying every {@link #PERFECT_STREAK_STEP} - see {@code RhythmMechanic.streakReward} - so a
     * run that good does not stop being rewarded for it.
     */
    public static final int[] PERFECT_STREAK_MILESTONES = {30, 50, 80, 100};
    /** How often the streak pays again once it is past the last named milestone. */
    public static final int PERFECT_STREAK_STEP = 20;

    private PvzceConstants() {
    }
}
