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
    public static final String HIT = "hit";
    public static final String ANGRY = "angry";
    public static final String DEATH = "death";
    public static final String FALL = "fall";
    public static final String FLY = "fly";
    public static final String DIG = "dig";
    public static final String DIG_EXIT = "dig_exit";
    public static final String JUMP = "jump";
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
