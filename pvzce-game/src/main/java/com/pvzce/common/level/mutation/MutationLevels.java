package com.pvzce.common.level.mutation;

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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The mutation levels, defined in code.
 *
 * <p>Four levels - one per difficulty tier - and they are built here rather than written as JSON
 * because almost everything about them is code already: the mutation catalogue, the endless wave
 * generator, and the fourteen-card, ten-buff bar they all share. A JSON file for each tier would hold
 * four lines that differ (an id, a name, a description and one rule) around a scene block that is
 * identical, and the real content would still be in {@code common.level.mutation}.
 *
 * <p>What they are: the original's Survival Endless on the day pool, with the mutation system
 * switched on. Six rows, water in the middle two, mowers on the land rows and pool cleaners in the
 * water; fourteen card slots and ten buff slots; no victory condition a player will ever reach,
 * because the waves do not run out - each round is generated, and the next one is heavier (see
 * {@code pvzce:mutation_endless}).
 *
 * <p>Registration happens from {@code BuiltInRegistries.bootstrap()} like every other code-defined
 * entry, so the levels exist before any data pack is read and a pack that ships its own levels sits
 * beside them.
 */
public final class MutationLevels {
    /** The theme these levels live under: the same {@code yard} the shipped levels use. */
    private static final String THEME = "yard";
    /**
     * The category they share with the plain endless level: one "生存无尽" tab.
     *
     * <p>They used to live under a category of their own ({@code pvzce:endless}), which put the
     * two endless modes on two different tabs of the same theme - and "which endless do I feel
     * like" is one question, so it is one page. The level ids changed with it, which is a
     * deliberate break: a save that recorded {@code yard/endless/mutation_normal} as cleared
     * does not match {@code yard/survival/mutation_normal}.
     */
    private static final String CATEGORY = "survival";

    /** The board of the day pool: nine columns, six rows, water in the middle two. */
    private static final int WIDTH = 9;
    private static final int HEIGHT = 6;
    private static final List<Integer> LAND_ROWS = List.of(0, 1, 4, 5);
    private static final List<Integer> WATER_ROWS = List.of(2, 3);

    /**
     * Fourteen cards and ten buffs: the fixed bar this whole mode is played with.
     *
     * <p>Two more than {@link PvzceConstants#MAX_SEED_SLOTS}, and deliberately its own number
     * rather than a raise of that ceiling: the backpack's 12 is a progression figure that the
     * shop and {@code /profile slots} hand out, while this is the mode's own shape - a run whose
     * lawn keeps rewriting itself needs a wider bar to answer with. Raising the shared constant
     * would have widened the backpack, the level editor's card-pool limit and the plain survival
     * endless level along with it.
     */
    private static final int SEED_SLOTS = 14;
    private static final int BUFF_SLOTS = 10;

    /** Where the sun comes from, in ticks: the pool levels' own numbers. */
    private static final int SUN_INTERVAL_MIN = 480;
    private static final int SUN_INTERVAL_MAX = 720;
    private static final int SUN_INITIAL_TICKS = 300;

    /** What a win pays, and what a repeat does. */
    private static final int FIRST_CLEAR_COINS = 500;
    private static final int REPEAT_COINS = 150;

    private static final List<Identifier> ALL = List.of(
            id("mutation_easy"),
            id("mutation_normal"),
            id("mutation_hard"),
            id("mutation_hell"));

    private MutationLevels() {
    }

    /** The four level ids, in tier order. Read by the tests and by the category's page. */
    public static List<Identifier> levelIds() {
        return ALL;
    }

    /** Registers the four levels; the category belongs to {@code EndlessLevels}. */
    public static void bootstrap() {
        for (MutationDifficulty tier : MutationDifficulty.values()) {
            Identifier levelId = id("mutation_" + tier.tierName());
            BuiltInRegistries.registerStatic(BuiltInRegistries.LEVELS, levelId.toString(),
                    build(levelId, tier));
        }
    }

    /** One level, for one tier. */
    private static LevelDef build(Identifier levelId, MutationDifficulty tier) {
        return new LevelDef(
                levelId,
                "变异·" + tierName(tier),
                description(tier),
                WIDTH, HEIGHT,
                scene(),
                teams(),
                PvzceIds.PLANT_TEAM,
                rules(tier),
                Map.<Identifier, EnvValue>of(),
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
                // The lawn is hidden because the backdrop already draws it: the terrain still
                // exists for the simulation (it decides where a Lily Pad may go), but painting
                // it as well would put a second lawn on top of the first.
                List.of(PvzceIds.GRASS.toString()),
                false,
                new LevelDef.LevelBuffPlan(List.of(LevelDef.LevelBuffPlan.PLAYER_CHOICE),
                        BUFF_SLOTS));
    }

    /**
     * The board: the day pool's own terrain.
     *
     * <p>Grass on four rows, water on the middle two, and the grass is hidden because the backdrop
     * already has it - the same arrangement the shipped pool levels use, and the terrain still has
     * to be painted for the simulation (it is what decides where a Lily Pad may go).
     */
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
     * The rules, which are the only thing that differs between the four levels.
     *
     * <p>One rule, in fact: the difficulty tier. Everything else - the sun, the grace period, the
     * multipliers - is the same on all four, because they are the mode's own numbers rather than a
     * tier's. The day-pool clock is written explicitly ({@code day_length 0}, {@code night_length
     * -1}) so that a mutation's "it is night now" has something well-defined to restore.
     */
    private static Map<Identifier, JsonElement> rules(MutationDifficulty tier) {
        Map<Identifier, JsonElement> rules = new LinkedHashMap<>();
        rules.put(PvzceIds.RULE_DAY_LENGTH, new JsonPrimitive(0));
        rules.put(PvzceIds.RULE_NIGHT_LENGTH, new JsonPrimitive(-1));
        rules.put(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN, new JsonPrimitive(SUN_INTERVAL_MIN));
        rules.put(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX, new JsonPrimitive(SUN_INTERVAL_MAX));
        rules.put(PvzceIds.RULE_SUN_SPAWN_INITIAL_TICKS, new JsonPrimitive(SUN_INITIAL_TICKS));
        rules.put(PvzceIds.RULE_MUTATION_DIFFICULTY, new JsonPrimitive(tier.tierName()));
        rules.put(PvzceIds.RULE_MUTATION_INITIAL_TICKS,
                new JsonPrimitive(PvzceConstants.MUTATION_INITIAL_TICKS));
        rules.put(PvzceIds.RULE_MUTATION_INTERVAL_MULTIPLIER,
                new JsonPrimitive(PvzceConstants.MUTATION_INTERVAL_MULTIPLIER));
        // The first wave arrives before the first mutation, which is the whole point of the grace
        // period: a player who has not planted anything yet cannot lose anything to a mutation.
        rules.put(PvzceIds.id("level_pause_on_single_player"), new JsonPrimitive(true));
        return rules;
    }

    /** The mechanics every tier declares. */
    private static List<TypedMechanic> mechanics() {
        List<TypedMechanic> mechanics = new ArrayList<>();
        // The endless block names its schedule rather than being an empty marker: which curve
        // this mode grows on is the one thing about its waves the level still gets to say.
        mechanics.add(new TypedMechanic(PvzceIds.MECHANIC_ENDLESS,
                new com.pvzce.common.level.mechanic.EndlessMechanic.Data(
                        PvzceIds.ENDLESS_SCHEDULE_MUTATION)));
        mechanics.add(new TypedMechanic(PvzceIds.MECHANIC_MUTATION, MechanicData.Empty.INSTANCE));
        mechanics.add(new TypedMechanic(PvzceIds.MECHANIC_MOWER, poolMowers()));
        // A deck, written out rather than left implicit: the level's cards come from the player's
        // own picks, and "which card source" is the question a mutation overrides - so the
        // declaration is what a mutation has to beat rather than something it falls back to.
        mechanics.add(new TypedMechanic(PvzceIds.MECHANIC_DECK, MechanicData.Empty.INSTANCE));
        return List.copyOf(mechanics);
    }

    /**
     * Mowers: the ordinary machine on the four land rows, a pool cleaner in the two water ones.
     *
     * <p>The same arrangement 3-1 ships, in code. A pool row with a lawn mower would be a lawn mower
     * driving on water.
     */
    private static MowerData poolMowers() {
        // Through the record's own factory rather than building the kinds here: see
        // `MowerData.poolRig` for why constructing the nested type from outside is a trap.
        return MowerData.poolRig(LAND_ROWS, WATER_ROWS, PvzceIds.id("pool_cleaner"),
                Optional.of(PvzceIds.id("sfx/ambient/pool_cleaner")));
    }

    /**
     * Rewards: coins, and only coins.
     *
     * <p>No unlock and no plant, because there is nothing left to unlock for a player who can reach
     * an endless level - the mode is where the plants they own get used, not where new ones come
     * from. The first clear pays more, so a player who has never survived a tier has a reason to.
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

    /** The one hint, shown when the level starts. */
    private static LevelHint onStartHint() {
        return new LevelHint(LevelHint.Trigger.ON_START, Optional.empty(),
                "变异会不断出现并改变这一关的规则，注意右侧的变异列表",
                LevelHint.PERSISTENT);
    }

    private static String tierName(MutationDifficulty tier) {
        return switch (tier) {
            case EASY -> "简单";
            case NORMAL -> "中等";
            case HARD -> "困难";
            case HELL -> "地狱";
        };
    }

    private static String description(MutationDifficulty tier) {
        return "庭院无尽·变异（" + tierName(tier) + "）：泳池白天，僵尸不会停，"
                + "而这一关每 " + Math.round(tier.intervalTicks() / 60F) + " 秒会变异一次，"
                + "同时最多存在 " + tier.maxConcurrent() + " 个变异，"
                + "数值变异的倍率是 " + tier.rollMultiplier() + " 倍。"
                + "变异会改规则、改卡槽、改场景、改僵尸——撑得越久，改得越狠。";
    }

    private static Identifier id(String path) {
        return Identifier.of(Identifier.DEFAULT_NAMESPACE, THEME + "/" + CATEGORY + "/" + path);
    }
}
