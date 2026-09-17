package com.pvzce.api.entity;

/**
 * Animation state names published by the server and played by the client.
 * These strings are a wire contract and are also the keys used by
 * {@code AttackAnimations} / {@code AnimationBindings} overrides in entity JSON.
 */
public final class EntityAnimations {
    public static final String IDLE = "idle";
    public static final String WALK = "walk";
    public static final String EAT = "eat";
    public static final String SHOOT = "shoot";
    public static final String PRODUCE = "produce";
    public static final String CHEW = "chew";
    public static final String EXPLODE = "explode";
    public static final String GROW = "grow";
    public static final String ARMED = "armed";
    /**
     * The pose an armed mine holds while it waits.
     *
     * <p>{@link #ARMED} is the *emergence* - a one-shot that ends on this - and the two are
     * separate states because a mine waits for a zombie for up to fifteen seconds: asking for
     * the emergence again on every tick restarts it every time its clip hands over, which is
     * a potato mine that pops out of the ground over and over.
     */
    public static final String ARMED_LOOP = "armed_loop";
    public static final String HIT = "hit";
    public static final String ANGRY = "angry";
    public static final String DEATH = "death";
    public static final String FALL = "fall";
    public static final String FLY = "fly";
    public static final String DIG = "dig";
    public static final String DIG_EXIT = "dig_exit";
    public static final String JUMP = "jump";
    /**
     * A zombie walking while it still carries what it walks with.
     *
     * <p>The pole vaulter's approach: the original draws it jogging with the pole held out
     * ({@code anim_run}), then vaulting, then walking empty-handed. Without this it walked
     * its *post-vault* clip from the start, pole already gone.
     */
    public static final String RUN = "run";
    public static final String HAMMER = "hammer";
    public static final String LANDED = "landed";
    /** A plant that is moving under its own power (bowling Wall-nut). */
    public static final String ROLL = "roll";
    /**
     * A nocturnal plant asleep in daylight (Puff-shroom, Doom-shroom).
     *
     * <p>Like every other state this is a wire string and a clip name: the art has to
     * define a {@code sleep} clip, or the client silently falls back to {@code idle} and
     * a sleeping mushroom looks exactly like a wide-awake one.
     */
    public static final String SLEEP = "sleep";

    private EntityAnimations() {
    }
}
