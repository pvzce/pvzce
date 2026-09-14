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
    public static final float SUN_SPAWN_CHANCE = 0.001F;
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
    /** The wallet has no ceiling; the only bound that matters is the 32-bit field it lives in. */
    public static final int COIN_LIMIT = Integer.MAX_VALUE;
    public static final int SAVE_DATA_VERSION = 2;

    private PvzceConstants() {
    }
}
