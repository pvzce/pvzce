package com.pvzce.server.level;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.pvzce.api.content.EnvValue;
import com.pvzce.api.content.InitialEntityDef;
import com.pvzce.api.content.LevelCategoryDef;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.LevelDialogue;
import com.pvzce.api.content.LevelHint;
import com.pvzce.api.content.LevelRewards;
import com.pvzce.api.content.LevelUnlock;
import com.pvzce.api.content.MowerData;
import com.pvzce.api.content.TeamDef;
import com.pvzce.api.content.WaveDef;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.EndlessMechanic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Survival Endless: the day pool, played in rounds, for as long as the player lasts.
 *
 * <p>The original's endless mode and nothing else - no mutations, no twists, one board and a
 * curve. It is defined in code for the same reason the mutation levels are (the board, the
 * schedule and the mowers are all code already, and a JSON file would hold four lines of it), and
 * it is a <em>separate level</em> from them rather than a mode of them because the two answer
 * different questions: this one is "how long can you hold the vanilla game", and the mutation
 * levels are "how long can you hold it while it rewrites itself".
 *
 * <p>What it shares with them is the round machinery: a fixed number of waves, then a pause while
 * the player picks their cards again, then a heavier round. The lawn - plants, sun, mowers, the
 * cooldowns - is kept across the boundary, which is what makes a run a run rather than a series
 * of levels.
 *
 * <p>Its own growth curve is {@code pvzce:pool_endless}: one tier of zombie unlocking at a time,
 * starting from the three the first adventure levels introduce.
 */
public final class EndlessLevels {
    /** The theme these levels live under: the same {@code yard} the shipped levels use. */
    private static final String THEME = "yard";
    /** The category they share: a new tab, "生存无尽". */
    private static final String CATEGORY = "survival";

    /** The board of the day pool: nine columns, six rows, water in the middle two. */
    private static final int WIDTH = 9;
    private static final int HEIGHT = 6;
    private static final List<Integer> LAND_ROWS = List.of(0, 1, 4, 5);
    private static final List<Integer> WATER_ROWS = List.of(2, 3);

    /** Twelve cards and ten buffs: the same fixed bar the mutation levels are played with. */
    private static final int SEED_SLOTS = PvzceConstants.MAX_SEED_SLOTS;
    private static final int BUFF_SLOTS = 10;
    /** The mutation levels' wider bar, for the two lawn levels that mutate. */
    private static final int MUTATION_SEED_SLOTS = 14;
    private static final int MUTATION_BUFF_SLOTS = 10;

    /** Where the sun comes from, in ticks: the pool levels' own numbers. */
    private static final int SUN_INTERVAL_MIN = 480;
    private static final int SUN_INTERVAL_MAX = 720;
    private static final int SUN_INITIAL_TICKS = 300;

    /** What a win pays, and what a repeat does. An endless run never wins, so only the repeat half lands. */
    private static final int FIRST_CLEAR_COINS = 300;
    private static final int REPEAT_COINS = 120;

    /**
     * The five levels this class defines: the day pool, and the four lawn ones.
     *
     * <p>The lawn levels are the same mode on a board with no water - "endless on the front lawn",
     * day and night, plain and mutating. They reuse the <em>same two schedules</em> as the pool
     * levels: an endless schedule's water entries simply never fire on a board with no water rows
     * ({@code EndlessWaves.composition} asks the level for its rows and gets none), so a third and
     * fourth curve would be two more copies of the same numbers. What the plan expected to need a
     * separate schedule for was already handled by the row split.
     */
    private static final Identifier ENDLESS_POOL = id("endless_pool");
    private static final Identifier ENDLESS_LAWN_DAY = id("endless_lawn_day");
    private static final Identifier ENDLESS_LAWN_NIGHT = id("endless_lawn_night");
    private static final Identifier MUTATION_LAWN_DAY = id("mutation_lawn_day");
    private static final Identifier MUTATION_LAWN_NIGHT = id("mutation_lawn_night");

    private static final List<Identifier> ALL = List.of(
            ENDLESS_POOL, ENDLESS_LAWN_DAY, ENDLESS_LAWN_NIGHT,
            MUTATION_LAWN_DAY, MUTATION_LAWN_NIGHT);

    /** The front lawn's board: nine columns, five rows, all grass. */
    private static final int LAWN_HEIGHT = 5;
    private static final List<Integer> LAWN_ROWS = List.of(0, 1, 2, 3, 4);

    /** The day lawn's sky, which is what the shipped 1-x levels use. */
    private static final int LAWN_SUN_INTERVAL_MIN = 420;
    private static final int LAWN_SUN_INTERVAL_MAX = 660;

    /** How many graves a night lawn opens with: 2-1's own number. */
    private static final int NIGHT_GRAVES = 7;

    private EndlessLevels() {
    }

    private static Identifier id(String path) {
        return Identifier.of(Identifier.DEFAULT_NAMESPACE, THEME + "/" + CATEGORY + "/" + path);
    }

    /** The day pool's id, for the tests and for the category's page. */
    public static Identifier levelId() {
        return ENDLESS_POOL;
    }

    /** Every endless level, in the order the page lists them. */
    public static List<Identifier> levelIds() {
        return ALL;
    }

    /** Registers the category and the five levels; called from {@code BuiltInRegistries.bootstrap()}. */
    public static void bootstrap() {
        BuiltInRegistries.registerStatic(BuiltInRegistries.LEVEL_CATEGORIES,
                PvzceIds.CATEGORY_SURVIVAL.toString(),
                new LevelCategoryDef(PvzceIds.CATEGORY_SURVIVAL, 3, true));
        BuiltInRegistries.registerStatic(BuiltInRegistries.LEVELS, ENDLESS_POOL.toString(), build());
        BuiltInRegistries.registerStatic(BuiltInRegistries.LEVELS, ENDLESS_LAWN_DAY.toString(),
                lawn(ENDLESS_LAWN_DAY, false, false));
        BuiltInRegistries.registerStatic(BuiltInRegistries.LEVELS, ENDLESS_LAWN_NIGHT.toString(),
                lawn(ENDLESS_LAWN_NIGHT, true, false));
        BuiltInRegistries.registerStatic(BuiltInRegistries.LEVELS, MUTATION_LAWN_DAY.toString(),
                lawn(MUTATION_LAWN_DAY, false, true));
        BuiltInRegistries.registerStatic(BuiltInRegistries.LEVELS, MUTATION_LAWN_NIGHT.toString(),
                lawn(MUTATION_LAWN_NIGHT, true, true));
    }

    /**
     * One of the four lawn levels.
     *
     * <p>A lawn board is the same level with the water taken out and the sky swapped: the same
     * schedule, the same round machinery, the same bar. Night brings the graves the shipped night
     * lawns open with and the night clock (which is also what wakes the mushrooms), and the two
     * mutation ones add the mutation mechanic with the mutation schedule.
     *
     * @param night    true for the night sky and its graves
     * @param mutating true to switch the mutation system on
     */
    private static LevelDef lawn(Identifier levelId, boolean night, boolean mutating) {
        String label = (mutating ? "变异·" : "") + "草坪无尽（" + (night ? "黑夜" : "白天") + "）";
        String description = night
                ? "前院草坪的夜晚无尽。没有水池，天上有墓碑，蘑菇在这里是醒着的——"
                        + "白天草坪上撑不住的东西，这里换个法子撑。"
                : "前院草坪的白天无尽。没有水池，也就没有水生僵尸：压力全在五条陆地上，"
                        + "而每一行只有一台割草机。";
        if (mutating) {
            description += "这一局里规则会自己改写。";
        }
        return new LevelDef(
                levelId,
                label,
                description,
                WIDTH, LAWN_HEIGHT,
                lawnScene(),
                teams(),
                PvzceIds.PLANT_TEAM,
                lawnRules(night, mutating),
                // The plant AI is on for the same reason the pool endless has it: an endless run is
                // the one mode long enough to be worth watching play itself.
                Map.of(PvzceIds.ENV_PLANT_AI, EnvValue.of("pvzce:boolean",
                        new JsonPrimitive(true))),
                List.<WaveDef>of(),
                1F,
                List.<Identifier>of(),
                Map.of(PvzceIds.SUN, true),
                50,
                LevelDef.LevelMusicDef.DEFAULT,
                List.<InitialEntityDef>of(),
                mutating ? MUTATION_SEED_SLOTS : SEED_SLOTS,
                rewards(),
                LevelUnlock.NONE,
                lawnMechanics(night, mutating),
                LevelDialogue.EMPTY,
                List.of(onStartHint()),
                List.of(PvzceIds.PLANT_TEAM),
                Optional.of(Identifier.withDefaultNamespace(
                        "textures/gui/screen/level/" + (night ? "background2" : "background1"))),
                List.of(PvzceIds.GRASS.toString()),
                false,
                new LevelDef.LevelBuffPlan(List.of(LevelDef.LevelBuffPlan.PLAYER_CHOICE),
                        mutating ? MUTATION_BUFF_SLOTS : BUFF_SLOTS),
                // The endless chooser is where the run's deck is decided: see LevelDef#seedScreen.
                true);
    }

    /** Forty-five grass cells: the front lawn has no water to paint. */
    private static Map<Identifier, List<String>> lawnScene() {
        Map<Identifier, List<String>> scene = new LinkedHashMap<>();
        List<String> grass = new ArrayList<>();
        for (int y = 0; y < LAWN_HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                grass.add(x + "," + y);
            }
        }
        scene.put(PvzceIds.GRASS, grass);
        return scene;
    }

    /** The lawn's rules: the day sky, or the night one with its graves. */
    private static Map<Identifier, JsonElement> lawnRules(boolean night, boolean mutating) {
        Map<Identifier, JsonElement> rules = new LinkedHashMap<>();
        rules.put(PvzceIds.RULE_DAY_LENGTH, new JsonPrimitive(0));
        // The night clock is the shipped night levels' own shape: one long night rather than a
        // cycle, because an endless run has no dawn to reach.
        rules.put(PvzceIds.RULE_NIGHT_LENGTH, new JsonPrimitive(night ? 360000 : -1));
        rules.put(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN,
                new JsonPrimitive(night ? 0 : LAWN_SUN_INTERVAL_MIN));
        rules.put(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX,
                new JsonPrimitive(night ? 0 : LAWN_SUN_INTERVAL_MAX));
        if (!night) {
            rules.put(PvzceIds.RULE_SUN_SPAWN_INITIAL_TICKS, new JsonPrimitive(SUN_INITIAL_TICKS));
        }
        if (mutating) {
            rules.put(PvzceIds.RULE_MUTATION_DIFFICULTY,
                    new JsonPrimitive(com.pvzce.common.level.mutation.MutationDifficulty.NORMAL
                            .tierName()));
            rules.put(PvzceIds.RULE_MUTATION_INITIAL_TICKS,
                    new JsonPrimitive(PvzceConstants.MUTATION_INITIAL_TICKS));
            rules.put(PvzceIds.RULE_MUTATION_INTERVAL_MULTIPLIER,
                    new JsonPrimitive(PvzceConstants.MUTATION_INTERVAL_MULTIPLIER));
        }
        rules.put(PvzceIds.id("level_pause_on_single_player"), new JsonPrimitive(true));
        return rules;
    }

    /** The lawn's mechanics: mowers on every row, the endless schedule, and the two switches. */
    private static List<TypedMechanic> lawnMechanics(boolean night, boolean mutating) {
        List<TypedMechanic> mechanics = new ArrayList<>();
        mechanics.add(new TypedMechanic(PvzceIds.MECHANIC_ENDLESS,
                new com.pvzce.common.level.mechanic.EndlessMechanic.Data(mutating
                        ? PvzceIds.ENDLESS_SCHEDULE_MUTATION : PvzceIds.ENDLESS_SCHEDULE_POOL)));
        if (mutating) {
            mechanics.add(new TypedMechanic(PvzceIds.MECHANIC_MUTATION,
                    com.pvzce.api.content.MutationData.RANDOM));
        }
        // Every row has an ordinary mower: `MowerData.EVERY_ROW` is what a level that says nothing
        // gets, and writing it out is what makes a lawn row with no mower a deliberate act.
        mechanics.add(new TypedMechanic(PvzceIds.MECHANIC_MOWER, MowerData.EVERY_ROW));
        mechanics.add(new TypedMechanic(PvzceIds.MECHANIC_DECK, MechanicData.Empty.INSTANCE));
        if (night) {
            // The graves the shipped night lawns open with. They are terrain, so a round boundary
            // keeps whatever the player has not cleared - which on a night lawn is the point.
            mechanics.add(new TypedMechanic(PvzceIds.MECHANIC_GRAVE_FIELD,
                    new com.pvzce.api.content.GraveFieldData(NIGHT_GRAVES,
                            com.pvzce.api.content.GraveFieldData.MIN_X_UNSET,
                            com.pvzce.api.content.GraveFieldData.MAX_X_UNSET,
                            com.pvzce.api.content.GraveFieldData.DEFAULT_REGION, List.of())));
        }
        return List.copyOf(mechanics);
    }

    private static LevelDef build() {
        return new LevelDef(
                ENDLESS_POOL,
                "生存无尽",
                "原版庭院无尽：泳池白天，僵尸不会停。每打完一轮（越往后越长）可以重选一次卡牌，"
                        + "草坪、阳光和割草机都保留——撑得越久，来的东西越狠。",
                WIDTH, HEIGHT,
                scene(),
                teams(),
                PvzceIds.PLANT_TEAM,
                rules(),
                // The plant AI is off by default everywhere, and this switches it on for this
                // level: an endless run is the one mode long enough to be worth watching play
                // itself, and it is what a screenshot or a soak test needs to get past round one
                // without a human at the mouse. It spends the team's own sun like any other
                // player, so a level that turns it on and gives nobody sun gets nothing.
                Map.of(PvzceIds.ENV_PLANT_AI, EnvValue.of("pvzce:boolean",
                        new JsonPrimitive(true))),
                List.<WaveDef>of(),
                1F,
                List.<Identifier>of(),
                Map.of(PvzceIds.SUN, true),
                50,
                LevelDef.LevelMusicDef.DEFAULT,
                List.<InitialEntityDef>of(),
                SEED_SLOTS,
                rewards(),
                LevelUnlock.NONE,
                mechanics(),
                LevelDialogue.EMPTY,
                List.of(onStartHint()),
                List.of(PvzceIds.PLANT_TEAM),
                Optional.of(Identifier.withDefaultNamespace(
                        "textures/gui/screen/level/background3")),
                // The lawn is hidden because the backdrop already draws it; the terrain still
                // exists for the simulation, and the generator reads it to find the water rows.
                List.of(PvzceIds.GRASS.toString()),
                false,
                new LevelDef.LevelBuffPlan(List.of(LevelDef.LevelBuffPlan.PLAYER_CHOICE),
                        BUFF_SLOTS),
                // The card screen is where a mutation run is decided, so both of these levels
                // want it: see LevelDef#seedScreen.
                true);
    }

    /** Grass on the four land rows, water on the middle two, the grass hidden behind the art. */
    private static Map<Identifier, List<String>> scene() {
        Map<Identifier, List<String>> scene = new LinkedHashMap<>();
        List<String> grass = new ArrayList<>();
        for (int y : LAND_ROWS) {
            for (int x = 0; x < WIDTH; x++) {
                grass.add(x + "," + y);
            }
        }
        List<String> water = new ArrayList<>();
        for (int y : WATER_ROWS) {
            for (int x = 0; x < WIDTH; x++) {
                water.add(x + "," + y);
            }
        }
        scene.put(PvzceIds.GRASS, grass);
        scene.put(PvzceIds.WATER, water);
        return scene;
    }

    private static List<TeamDef> teams() {
        return List.of(
                new TeamDef(PvzceIds.PLANT_TEAM, "植物方", "survive_waves"),
                new TeamDef(PvzceIds.ZOMBIE_TEAM, "僵尸方", "plant_side_lost"));
    }

    /**
     * The level's rules: the day-pool clock, the pool's sun numbers, and nothing about the waves.
     *
     * <p>Which zombies arrive and when is the schedule's business, not a rule's - the level's job
     * here is the board and the sky.
     */
    private static Map<Identifier, JsonElement> rules() {
        Map<Identifier, JsonElement> rules = new LinkedHashMap<>();
        rules.put(PvzceIds.RULE_DAY_LENGTH, new JsonPrimitive(0));
        rules.put(PvzceIds.RULE_NIGHT_LENGTH, new JsonPrimitive(-1));
        rules.put(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN, new JsonPrimitive(SUN_INTERVAL_MIN));
        rules.put(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX, new JsonPrimitive(SUN_INTERVAL_MAX));
        rules.put(PvzceIds.RULE_SUN_SPAWN_INITIAL_TICKS, new JsonPrimitive(SUN_INITIAL_TICKS));
        rules.put(PvzceIds.id("level_pause_on_single_player"), new JsonPrimitive(true));
        return rules;
    }

    /**
     * What this level runs with: endless waves, a deck the player picks, mowers.
     *
     * <p>Deliberately no {@code pvzce:mutation}: this is the mode without it.
     */
    private static List<TypedMechanic> mechanics() {
        List<TypedMechanic> mechanics = new ArrayList<>();
        mechanics.add(new TypedMechanic(PvzceIds.MECHANIC_ENDLESS,
                new EndlessMechanic.Data(PvzceIds.ENDLESS_SCHEDULE_POOL)));
        mechanics.add(new TypedMechanic(PvzceIds.MECHANIC_MOWER, poolMowers()));
        mechanics.add(new TypedMechanic(PvzceIds.MECHANIC_DECK, MechanicData.Empty.INSTANCE));
        return List.copyOf(mechanics);
    }

    /** A machine on the four land rows, a pool cleaner in the two water ones. */
    private static MowerData poolMowers() {
        return MowerData.poolRig(LAND_ROWS, WATER_ROWS, PvzceIds.id("pool_cleaner"),
                Optional.of(PvzceIds.id("sfx/ambient/pool_cleaner")));
    }

    /**
     * Coins, and no unlock.
     *
     * <p>An endless run cannot be won, so the first-clear half will never pay; it is declared
     * anyway because the level definition has the shape and a zero there would read as "this
     * level deliberately pays nothing". What does pay is the repeat half, on every run that ends
     * in defeat - which is every run.
     */
    private static LevelRewards rewards() {
        return new LevelRewards(
                List.of(new LevelRewards.Reward(LevelRewards.Reward.TYPE_COINS, Optional.empty(),
                        FIRST_CLEAR_COINS)),
                List.of(new LevelRewards.Reward(LevelRewards.Reward.TYPE_COINS, Optional.empty(),
                        REPEAT_COINS)),
                0.25F,
                PvzceIds.COIN_SILVER,
                1);
    }

    /**
     * The one hint, shown for the first few seconds.
     *
     * <p>Deliberately not {@link LevelHint#PERSISTENT}: a persistent hint is drawn in the grey box
     * at the bottom of the board, and the wave meter lives in the same band - a run whose mode
     * hint never left would be a run whose wave meter never showed. The mode's rules are worth one
     * read, not a permanent strip over the one gauge this level is played on.
     */
    private static LevelHint onStartHint() {
        return new LevelHint(LevelHint.Trigger.ON_START, Optional.empty(),
                "每打完一轮可以重选卡牌，草坪与阳光保留；撑得越久，僵尸的种类越多",
                LevelHint.DEFAULT_DURATION_TICKS);
    }
}
