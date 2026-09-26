package com.pvzce.common.level.mutation;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.pvzce.api.content.EnvValue;
import com.pvzce.api.content.InitialEntityDef;
import com.pvzce.api.content.LevelCategoryDef;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.DialogueLine;
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

    /**
     * The tutorial's id.
     *
     * <p>Not one of the four tiers: it is a short, hand-scripted level that teaches what a mutation
     * <em>is</em>, on the same page as them because that is where a player who has just met one will
     * look. It is not endless either - it has six waves and a victory - which is the other half of
     * "this is a lesson, not a run".
     */
    private static final Identifier TUTORIAL = id("mutation_tutorial");

    private MutationLevels() {
    }

    /** The four level ids, in tier order, plus the tutorial. Read by the tests and by the page. */
    public static List<Identifier> levelIds() {
        List<Identifier> ids = new ArrayList<>(ALL);
        ids.add(TUTORIAL);
        return List.copyOf(ids);
    }

    /** The four tier ids alone, which is what the difficulty tests walk. */
    public static List<Identifier> tierIds() {
        return ALL;
    }

    /** The tutorial's id. */
    public static Identifier tutorialId() {
        return TUTORIAL;
    }

    /** Registers the five levels; the category belongs to {@code EndlessLevels}. */
    public static void bootstrap() {
        for (MutationDifficulty tier : MutationDifficulty.values()) {
            Identifier levelId = id("mutation_" + tier.tierName());
            BuiltInRegistries.registerStatic(BuiltInRegistries.LEVELS, levelId.toString(),
                    build(levelId, tier));
        }
        BuiltInRegistries.registerStatic(BuiltInRegistries.LEVELS, TUTORIAL.toString(), tutorial());
    }

    /**
     * The tutorial: six gentle waves on a day lawn, four cards, and four mutations on a script.
     *
     * <p>What it teaches is the shape of the system rather than any one mutation - a card-bar
     * rewrite, a number, a mini-game and a card dealer, in that order - and it teaches it by
     * <em>showing</em>: each staged mutation arrives with a line from 豌豆酱 that says which kind it
     * is. That is why the level pins a schedule and turns the dice off
     * ({@code random: false}): a lesson whose examples arrive in a random order and a random
     * strength is not a lesson.
     *
     * <p>Six small waves, all of them plain zombies, and no endless mechanic: the run has to end so
     * the player can go and meet the real thing.
     */
    private static LevelDef tutorial() {
        List<com.pvzce.api.content.MutationData.Planned> script = List.of(
                new com.pvzce.api.content.MutationData.Planned(
                        PvzceIds.MUTATION_SLOT_REPLACE, 900),
                new com.pvzce.api.content.MutationData.Planned(PvzceIds.MUTATION_SUN_RATE, 2400),
                new com.pvzce.api.content.MutationData.Planned(PvzceIds.MUTATION_BOWLING_NUT, 4200),
                new com.pvzce.api.content.MutationData.Planned(PvzceIds.MUTATION_CONVEYOR, 6000));
        return new LevelDef(
                TUTORIAL,
                "变异教程",
                "这一关不长：打完六波就赢。变异会在固定的时刻出现，豌豆酱会告诉你每一种叫什么——"
                        + "认全了再去变异无尽里碰上它们，就不慌了。",
                WIDTH, 5,
                tutorialScene(),
                teams(),
                PvzceIds.PLANT_TEAM,
                tutorialRules(),
                Map.<Identifier, EnvValue>of(),
                tutorialWaves(),
                1F,
                tutorialSlots(),
                Map.of(PvzceIds.SUN, true),
                150,
                LevelDef.LevelMusicDef.DEFAULT,
                List.<InitialEntityDef>of(),
                PvzceConstants.MAX_SEED_SLOTS,
                rewards(),
                // Open from the start: it is a lesson, not a reward, and a player who has just met
                // their first mutation should be able to go and read about it.
                LevelUnlock.NONE,
                tutorialMechanics(script),
                tutorialDialogue(),
                List.of(new LevelHint(LevelHint.Trigger.ON_START, Optional.empty(),
                        "豌豆酱会一路解说，跟着看就行", 600)),
                List.of(PvzceIds.PLANT_TEAM),
                Optional.of(Identifier.withDefaultNamespace(
                        "textures/gui/screen/level/background1")),
                List.of(PvzceIds.GRASS.toString()),
                false,
                new LevelDef.LevelBuffPlan(List.of(LevelDef.LevelBuffPlan.PLAYER_CHOICE),
                        BUFF_SLOTS),
                // The card screen is where a mutation run is decided, so both of these levels
                // want it: see LevelDef#seedScreen.
                true);
    }

    /** The tutorial's board: the front lawn, all grass. */
    private static Map<Identifier, List<String>> tutorialScene() {
        Map<Identifier, List<String>> scene = new LinkedHashMap<>();
        List<String> grass = new ArrayList<>();
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < WIDTH; x++) {
                grass.add(x + "," + y);
            }
        }
        scene.put(PvzceIds.GRASS, grass);
        return scene;
    }

    /** Four cards: enough to hold a lawn of plain zombies, few enough to read. */
    private static List<Identifier> tutorialSlots() {
        return List.of(PvzceIds.id("sun"), PvzceIds.id("pea_shooter"), PvzceIds.id("sunflower"),
                PvzceIds.id("wall_nut"), PvzceIds.id("cherry_bomb"), PvzceIds.id("shovel"));
    }

    /**
     * The day lawn's sky and the script's own clock.
     *
     * <p>The mutation rules are the middle tier's numbers even though nothing is rolled: the panel
     * reads the tier for its "next one in N seconds" line, and a level with no tier at all would
     * show the default one anyway - this way the level says what it means.
     */
    private static Map<Identifier, JsonElement> tutorialRules() {
        Map<Identifier, JsonElement> rules = new LinkedHashMap<>();
        rules.put(PvzceIds.RULE_DAY_LENGTH, new JsonPrimitive(0));
        rules.put(PvzceIds.RULE_NIGHT_LENGTH, new JsonPrimitive(-1));
        rules.put(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MIN, new JsonPrimitive(SUN_INTERVAL_MIN));
        rules.put(PvzceIds.RULE_SUN_SPAWN_INTERVAL_MAX, new JsonPrimitive(SUN_INTERVAL_MAX));
        rules.put(PvzceIds.RULE_SUN_SPAWN_INITIAL_TICKS, new JsonPrimitive(SUN_INITIAL_TICKS));
        rules.put(PvzceIds.RULE_MUTATION_DIFFICULTY,
                new JsonPrimitive(MutationDifficulty.NORMAL.tierName()));
        rules.put(PvzceIds.id("level_pause_on_single_player"), new JsonPrimitive(true));
        return rules;
    }

    /** Six small waves of plain zombies: pressure for the player to have to do something. */
    private static List<WaveDef> tutorialWaves() {
        List<WaveDef> waves = new ArrayList<>();
        List<Integer> rows = List.of(0, 1, 2, 3, 4);
        for (int i = 0; i < 6; i++) {
            boolean last = i == 5;
            List<WaveDef.Entry> entries = new ArrayList<>();
            if (last) {
                entries.add(new WaveDef.Entry(PvzceIds.id("flag_zombie"), 1, rows, 1F));
            }
            entries.add(new WaveDef.Entry(Identifier.withDefaultNamespace("basic_zombie"),
                    1 + i / 2, rows, 1F));
            if (i >= 3) {
                entries.add(new WaveDef.Entry(Identifier.withDefaultNamespace("conehead_zombie"),
                        1, rows, 1F));
            }
            // (type, delay, warning, entries, spawn interval): a slow trickle, so each line has
            // time to be read between two zombies.
            waves.add(WaveDef.declaringSpawnInterval(last ? WaveDef.WaveType.FINAL : WaveDef.WaveType.SMALL,
                    1500, last ? 180 : 0, entries, 300));
        }
        return List.copyOf(waves);
    }

    /**
     * The tutorial's words: two lines before it starts, and one for each staged mutation.
     *
     * <p>豌豆酱 is the guide the user asked for, and the voice is the one the shipped levels already
     * use for her - short sentences, 咱 for "I", and an exclamation when she gets excited. The timed
     * lines are aimed at the ticks the mutations fire on (900 / 2400 / 4200 / 6000, plus a little
     * slack so the banner of the mutation itself has shown first), which is the whole reason the
     * script and the dialogue are written in one place.
     */
    private static LevelDialogue tutorialDialogue() {
        Identifier pea = Identifier.withDefaultNamespace("pea_chan");
        List<DialogueLine> opening = List.of(
                new DialogueLine(pea, "happy",
                        "欢迎来到变异教程！这一关不长，咱带你认认变异是什么。", "", DialogueLine.Side.LEFT),
                new DialogueLine(pea, "smile",
                        "变异就是——打着打着，规则突然变了一下。别慌，咱陪你。", "",
                        DialogueLine.Side.LEFT));
        List<LevelDialogue.Timed> timed = List.of(
                timed(pea, 1000, "surprised",
                        "看卡槽！卡变了！这叫卡槽变异，你的卡会被换成同类型的别的植物。"),
                timed(pea, 2500, "tsundere",
                        "阳光变快了……或者变慢了。这种只改数字的，叫数值变异。"),
                timed(pea, 4300, "happy",
                        "坚果变保龄球了！这种把玩法整个换掉的，叫小游戏变异。"),
                timed(pea, 6100, "fierce",
                        "卡槽变传送带了！注意——铲子还在，工具不会被传送带吃掉。"));
        return new LevelDialogue(opening, com.pvzce.api.content.DialogueEffect.SLIDE,
                com.pvzce.api.content.DialogueEffect.SLIDE, timed);
    }

    /** One timed line from 豌豆酱. */
    private static LevelDialogue.Timed timed(Identifier pea, int atTick, String portrait,
                                             String text) {
        return new LevelDialogue.Timed(atTick, new DialogueLine(pea, portrait, text, "",
                DialogueLine.Side.LEFT));
    }

    /** The tutorial's mechanics: a scripted mutation block, mowers, and a deck. */
    private static List<TypedMechanic> tutorialMechanics(
            List<com.pvzce.api.content.MutationData.Planned> script) {
        List<TypedMechanic> mechanics = new ArrayList<>();
        // No endless mechanic at all: six waves and the level is won.
        mechanics.add(new TypedMechanic(PvzceIds.MECHANIC_MUTATION,
                new com.pvzce.api.content.MutationData(false, script)));
        mechanics.add(new TypedMechanic(PvzceIds.MECHANIC_MOWER, MowerData.EVERY_ROW));
        mechanics.add(new TypedMechanic(PvzceIds.MECHANIC_DECK, MechanicData.Empty.INSTANCE));
        return List.copyOf(mechanics);
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
                        BUFF_SLOTS),
                // The card screen is where a mutation run is decided, so both of these levels
                // want it: see LevelDef#seedScreen.
                true);
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
        mechanics.add(new TypedMechanic(PvzceIds.MECHANIC_MUTATION,
                com.pvzce.api.content.MutationData.RANDOM));
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
