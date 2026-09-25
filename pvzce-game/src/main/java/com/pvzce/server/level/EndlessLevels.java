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

    /** Where the sun comes from, in ticks: the pool levels' own numbers. */
    private static final int SUN_INTERVAL_MIN = 480;
    private static final int SUN_INTERVAL_MAX = 720;
    private static final int SUN_INITIAL_TICKS = 300;

    /** What a win pays, and what a repeat does. An endless run never wins, so only the repeat half lands. */
    private static final int FIRST_CLEAR_COINS = 300;
    private static final int REPEAT_COINS = 120;

    /** The one level this class defines. */
    private static final Identifier ENDLESS_POOL =
            Identifier.of(Identifier.DEFAULT_NAMESPACE, THEME + "/" + CATEGORY + "/endless_pool");

    private EndlessLevels() {
    }

    /** The level's id, for the tests and for the category's page. */
    public static Identifier levelId() {
        return ENDLESS_POOL;
    }

    /** Registers the category and the level; called from {@code BuiltInRegistries.bootstrap()}. */
    public static void bootstrap() {
        BuiltInRegistries.registerStatic(BuiltInRegistries.LEVEL_CATEGORIES,
                PvzceIds.CATEGORY_SURVIVAL.toString(),
                new LevelCategoryDef(PvzceIds.CATEGORY_SURVIVAL, 3, true));
        BuiltInRegistries.registerStatic(BuiltInRegistries.LEVELS, ENDLESS_POOL.toString(), build());
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
                        BUFF_SLOTS));
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
