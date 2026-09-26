package com.pvzce.api.content;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.level.mechanic.LevelMechanics;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A level definition loaded from {@code data/<ns>/levels/<id>.json}. */
public record LevelDef(
        Identifier id,
        String name,
        String description,
        int width,
        int height,
        Map<Identifier, List<String>> scene,
        List<TeamDef> teams,
        Identifier winTeam,
        Map<Identifier, JsonElement> rules,
        Map<Identifier, EnvValue> envVars,
        List<WaveDef> waves,
        float waveIntervalEndMultiplier,
        List<Identifier> slots,
        Map<Identifier, Boolean> unlockResources,
        int initialSun,
        LevelMusicDef music,
        List<InitialEntityDef> initialEntities,
        int maxSeedSlots,
        LevelRewards rewards,
        LevelUnlock unlock,
        List<TypedMechanic> mechanics,
        LevelDialogue dialogue,
        List<LevelHint> hints,
        /**
         * Which of {@link #teams} a human may play, in the order they should be offered.
         *
         * <p>Empty means "all of them", which is what every level written before this field
         * meant and what a level that does not care still means: declaring {@code teams} is
         * already a statement about who is in the level, and a second list that had to repeat
         * it would only be a way for the two to disagree. An id that names no declared team is
         * dropped by {@link #playableTeamDefs()}.
         *
         * <p>This is the last component because it lives in {@link LevelTail}: the outer codec
         * is already at DFU's field limit, and the tail is where late additions go.
         */
        List<Identifier> playableTeams,
        /**
         * The backdrop this level is played on, or empty for the built-in yard.
         *
         * <p>A texture id rather than a name from a list: the original draws every stage -
         * day, night, pool, fog, roof, the boss arena - on the same 1400x600 canvas with the
         * board in the same place, so a backdrop is a picture and nothing else has to change
         * with it. See {@code LevelStage} for the geometry that does <em>not</em> move.
         */
        Optional<Identifier> background,
        /**
         * Scene elements this level does not draw.
         *
         * <p>Each entry is either an element id ({@code pvzce:grass}) or a tag written with a
         * leading {@code #} ({@code #pvzce:lawn}), and a cell holding a matching element is
         * left unpainted - the backdrop shows through instead. It is for a level whose own
         * backdrop already has the lawn in it: the terrain still has to be painted for the
         * simulation (that is what decides where a plant may go), but drawing it as well
         * would put a second lawn on top of the first.
         */
        List<String> hiddenSceneElements,
        /**
         * True when this level turns the shader effects off whatever the player's setting is.
         *
         * <p>Unwritten is false: a level follows the player's own {@code shaders_enabled}, which
         * is what every level written before this field means. A level says {@code true} when
         * its own look depends on not being re-lit - a stage whose backdrop is already painted
         * for the light it wants, or a board whose readability is the point.
         */
        boolean disableShaders,
        /**
         * The level buffs this level fixes, and how many it may run with in total.
         *
         * <p>Lives here rather than in {@link LevelTail} directly so a level's buff block is one
         * compact value in JSON ({@code "buffs": [...]} beside {@code "max_buff_slots": n}) while
         * the tail struct stays two fields short of DFU's limit.
         */
        LevelBuffPlan buffPlan,
        /**
         * False when this level starts without showing the card screen at all.
         *
         * <p>The card screen is two pages - the pool and the buffs - and for most levels it is
         * where a run is decided. A level whose deck is entirely its own has nothing on either
         * page: the player would be shown a lawn, a locked row and a button that says "start",
         * which is a screen with no question on it. {@code "seed_screen": false} is that level
         * saying so, and the entry flow then goes straight from the level list into the run.
         *
         * <p>Its own field rather than a rule derived from {@code max_seed_slots}, because "my
         * deck is fixed" and "do not show me the screen" are different statements: 1-1 fixes its
         * two cards and still wants its preview (that is where the tutorial's opening exchange
         * plays), and 2-5 fixes three of its players' six. Unwritten is true - every level
         * written before this field wants the screen.
         */
        boolean seedScreen
) {
    public static final float DEFAULT_WAVE_INTERVAL_END_MULTIPLIER = 1F;
    /**
     * {@code max_seed_slots} left unwritten: the level follows the player's backpack.
     *
     * <p>A number in the file is a statement about <em>this level</em> (1-1 fixes two
     * slots, 1-4 fixes eight); writing nothing is a statement about the player, and the
     * answer to that one is {@code PlayerProfile.seedSlots}. Before this, "unwritten" was
     * the literal 6, which silently overrode a backpack that had been upgraded - and made
     * the constant look like a default when it was really a cap.
     */
    public static final int UNSET_MAX_SEED_SLOTS = -1;

    /**
     * A level's own cards must fit in its bar, so the declared slot count is raised to the
     * number of cards when it is too small.
     *
     * <p>Nothing else is consistent: a level that fixes three cards while declaring two
     * slots could not grant its own design, and clamping would silently drop one of them.
     * Every level authored before this rule lists its whole card pool and leaves
     * {@code max_seed_slots} at the default, so they all become fixed decks - which is what
     * "a card the level chose is fixed" means when applied to data that already exists.
     *
     * <p>The raise happens against the <em>raw</em> value here and again in
     * {@link #effectiveMaxSeedSlots}, because the raw one may be
     * {@link #UNSET_MAX_SEED_SLOTS} and only the caller knows the backpack's number.
     */
    public LevelDef {
        maxSeedSlots = maxSeedSlots < 0 ? UNSET_MAX_SEED_SLOTS : Math.max(maxSeedSlots, slots.size());
        mechanics = mechanics == null ? List.of() : List.copyOf(mechanics);
        dialogue = dialogue == null ? LevelDialogue.EMPTY : dialogue;
        hints = hints == null ? List.of() : List.copyOf(hints);
        playableTeams = playableTeams == null ? List.of() : List.copyOf(playableTeams);
        background = background == null ? Optional.empty() : background;
        hiddenSceneElements = hiddenSceneElements == null ? List.of() : List.copyOf(hiddenSceneElements);
        buffPlan = buffPlan == null ? LevelBuffPlan.NONE : buffPlan;
    }

    /** The field's name in a level file, spelled once for the codec and the validator. */
    public static final String SEED_SCREEN_FIELD = "seed_screen";

    /** True when this level declares its own slot count rather than following the backpack. */
    public boolean declaresMaxSeedSlots() {
        return maxSeedSlots >= 0;
    }

    /**
     * The bar size this level actually gets for a player whose backpack holds
     * {@code profileSlots} cards.
     *
     * <p>One implementation, called by everything that needs the number: the seed chooser's
     * pool, the server's sanitising pass and both seed plans. A level that declared a count
     * keeps it; a level that declared none borrows the backpack's.
     */
    public int effectiveMaxSeedSlots(int profileSlots) {
        int slots = declaresMaxSeedSlots() ? maxSeedSlots : Math.max(1, profileSlots);
        return Math.max(slots, this.slots.size());
    }

    /** True when this level opens with a conversation the player has to click through. */
    public boolean hasDialogue() {
        return !dialogue.isEmpty();
    }

    /**
     * The buff count this level gets for a backpack holding {@code profileBuffSlots} buffs.
     *
     * <p>The buff twin of {@link #effectiveMaxSeedSlots}, and the same rule: a level that named
     * a number keeps it, a level that named none borrows the player's.
     */
    public int effectiveMaxBuffSlots(int profileBuffSlots) {
        return buffPlan.effectiveMaxBuffSlots(profileBuffSlots);
    }

    /** True when this level lets its player pick buffs of their own. */
    public boolean offersBuffChoice() {
        return buffPlan.offersPlayerChoice();
    }

    /** Backwards-compatible constructor for callers/tests written before seed selection existed. */
    public LevelDef(Identifier id, String name, String description, int width, int height,
                    Map<Identifier, List<String>> scene, List<TeamDef> teams, Identifier winTeam,
                    Map<Identifier, JsonElement> rules, Map<Identifier, EnvValue> envVars,
                    List<WaveDef> waves, float waveIntervalEndMultiplier, List<Identifier> slots,
                    Map<Identifier, Boolean> unlockResources, int initialSun,
                    LevelMusicDef music, List<InitialEntityDef> initialEntities) {
        this(id, name, description, width, height, scene, teams, winTeam, rules, envVars, waves,
                waveIntervalEndMultiplier, slots, unlockResources, initialSun, music, initialEntities,
                // No slot count in code means the same thing it means in JSON: follow the
                // backpack. A caller that wants a specific bar passes one.
                UNSET_MAX_SEED_SLOTS, LevelRewards.DEFAULT, LevelUnlock.NONE,
                List.<TypedMechanic>of(), LevelDialogue.EMPTY, List.of(), List.of(),
                Optional.empty(), List.of(), false, LevelBuffPlan.NONE, true);
    }

    /** As above, but with an explicit slot count and the standard rewards block. */
    public LevelDef(Identifier id, String name, String description, int width, int height,
                    Map<Identifier, List<String>> scene, List<TeamDef> teams, Identifier winTeam,
                    Map<Identifier, JsonElement> rules, Map<Identifier, EnvValue> envVars,
                    List<WaveDef> waves, float waveIntervalEndMultiplier, List<Identifier> slots,
                    Map<Identifier, Boolean> unlockResources, int initialSun,
                    LevelMusicDef music, List<InitialEntityDef> initialEntities, int maxSeedSlots) {
        this(id, name, description, width, height, scene, teams, winTeam, rules, envVars, waves,
                waveIntervalEndMultiplier, slots, unlockResources, initialSun, music, initialEntities,
                maxSeedSlots, LevelRewards.DEFAULT, LevelUnlock.NONE, List.of(),
                LevelDialogue.EMPTY, List.of(), List.of(), Optional.empty(), List.of(), false, LevelBuffPlan.NONE, true);
    }

    /**
     * The whole record minus its mechanics.
     *
     * <p>Kept for callers written before level mechanics existed: every mechanic is
     * opt-in, and none of them changes what an ordinary level means.
     */
    public LevelDef(Identifier id, String name, String description, int width, int height,
                    Map<Identifier, List<String>> scene, List<TeamDef> teams, Identifier winTeam,
                    Map<Identifier, JsonElement> rules, Map<Identifier, EnvValue> envVars,
                    List<WaveDef> waves, float waveIntervalEndMultiplier, List<Identifier> slots,
                    Map<Identifier, Boolean> unlockResources, int initialSun,
                    LevelMusicDef music, List<InitialEntityDef> initialEntities, int maxSeedSlots,
                    LevelRewards rewards, LevelUnlock unlock) {
        this(id, name, description, width, height, scene, teams, winTeam, rules, envVars, waves,
                waveIntervalEndMultiplier, slots, unlockResources, initialSun, music, initialEntities,
                maxSeedSlots, rewards, unlock, List.of(), LevelDialogue.EMPTY, List.of(), List.of(),
                Optional.empty(), List.of(), false, LevelBuffPlan.NONE, true);
    }

    /**
     * The whole record minus its hints.
     *
     * <p>Kept for callers written before the hint box existed. A level with no {@code hints}
     * shows none, which is what every level did before there was a way to write one - so the
     * old shape still means exactly what it used to.
     */
    public LevelDef(Identifier id, String name, String description, int width, int height,
                    Map<Identifier, List<String>> scene, List<TeamDef> teams, Identifier winTeam,
                    Map<Identifier, JsonElement> rules, Map<Identifier, EnvValue> envVars,
                    List<WaveDef> waves, float waveIntervalEndMultiplier, List<Identifier> slots,
                    Map<Identifier, Boolean> unlockResources, int initialSun,
                    LevelMusicDef music, List<InitialEntityDef> initialEntities, int maxSeedSlots,
                    LevelRewards rewards, LevelUnlock unlock, List<TypedMechanic> mechanics,
                    LevelDialogue dialogue) {
        this(id, name, description, width, height, scene, teams, winTeam, rules, envVars, waves,
                waveIntervalEndMultiplier, slots, unlockResources, initialSun, music, initialEntities,
                maxSeedSlots, rewards, unlock, mechanics, dialogue, List.of(), List.of(),
                Optional.empty(), List.of(), false, LevelBuffPlan.NONE, true);
    }

    /**
     * The whole record as it was before {@code playable_teams} existed.
     *
     * <p>Kept because every caller that spells out the record writes this argument list, and a
     * level that says nothing about who plays it means what all of them meant: everyone may.
     */
    public LevelDef(Identifier id, String name, String description, int width, int height,
                    Map<Identifier, List<String>> scene, List<TeamDef> teams, Identifier winTeam,
                    Map<Identifier, JsonElement> rules, Map<Identifier, EnvValue> envVars,
                    List<WaveDef> waves, float waveIntervalEndMultiplier, List<Identifier> slots,
                    Map<Identifier, Boolean> unlockResources, int initialSun,
                    LevelMusicDef music, List<InitialEntityDef> initialEntities, int maxSeedSlots,
                    LevelRewards rewards, LevelUnlock unlock, List<TypedMechanic> mechanics,
                    LevelDialogue dialogue, List<LevelHint> hints) {
        this(id, name, description, width, height, scene, teams, winTeam, rules, envVars, waves,
                waveIntervalEndMultiplier, slots, unlockResources, initialSun, music, initialEntities,
                maxSeedSlots, rewards, unlock, mechanics, dialogue, hints, List.of(),
                Optional.empty(), List.of(), false, LevelBuffPlan.NONE, true);
    }

    public static final Codec<LevelDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(LevelDef::id),
            Codec.STRING.optionalFieldOf("name", "").forGetter(LevelDef::name),
            Codec.STRING.optionalFieldOf("description", "").forGetter(LevelDef::description),
            Codec.INT.optionalFieldOf("width", PvzceConstants.DEFAULT_GRID_WIDTH).forGetter(LevelDef::width),
            Codec.INT.optionalFieldOf("height", PvzceConstants.DEFAULT_GRID_HEIGHT).forGetter(LevelDef::height),
            Codec.unboundedMap(Identifier.CODEC, Codec.STRING.listOf())
                    .optionalFieldOf("scene", Map.of()).forGetter(LevelDef::scene),
            TeamDef.CODEC.listOf().optionalFieldOf("teams", defaultTeams()).forGetter(LevelDef::teams),
            Identifier.CODEC.optionalFieldOf("win_team", Identifier.withDefaultNamespace("plant_team")).forGetter(LevelDef::winTeam),
            Codec.unboundedMap(Identifier.CODEC, JsonCodecs.RAW_JSON)
                    .optionalFieldOf("rules", Map.of()).forGetter(LevelDef::rules),
            Codec.unboundedMap(Identifier.CODEC, EnvValue.CODEC)
                    .optionalFieldOf("env_vars", Map.of()).forGetter(LevelDef::envVars),
            WaveDef.CODEC.listOf().optionalFieldOf("waves", List.of()).forGetter(LevelDef::waves),
            Codec.FLOAT.optionalFieldOf("wave_interval_end_multiplier", DEFAULT_WAVE_INTERVAL_END_MULTIPLIER)
                    .forGetter(LevelDef::waveIntervalEndMultiplier),
            Identifier.CODEC.listOf().optionalFieldOf("slots", defaultSlots()).forGetter(LevelDef::slots),
            Codec.unboundedMap(Identifier.CODEC, Codec.BOOL)
                    .optionalFieldOf("unlock_resources", Map.of()).forGetter(LevelDef::unlockResources),
            Codec.INT.optionalFieldOf("initial_sun", PvzceConstants.INITIAL_SUN).forGetter(LevelDef::initialSun),
            RecordCodecBuilder.of(LevelDef::tail, LevelTail.MAP_CODEC)
    ).apply(i, (id, name, description, width, height, scene, teams, winTeam, rules, envVars, waves,
            waveIntervalEndMultiplier, slots, unlockResources, initialSun, tail) ->
            new LevelDef(id, name, description, width, height, scene, teams, winTeam, rules, envVars, waves,
                    waveIntervalEndMultiplier, slots, unlockResources, initialSun,
                    tail.music(), tail.initialEntities(), tail.maxSeedSlots(), tail.rewards(),
                    tail.unlock(), tail.mechanics(), tail.dialogue(), tail.hints(),
                    tail.playableTeams(), tail.background(), tail.hiddenSceneElements(),
                    tail.disableShaders(), tail.buffPlan(), tail.seedScreen())));

    public LevelTail tail() {
        return new LevelTail(music, initialEntities, maxSeedSlots, rewards, unlock, mechanics,
                dialogue, hints, playableTeams, background, hiddenSceneElements, disableShaders,
                buffPlan, seedScreen);
    }

    /**
     * Which declared teams a human may play, in the order they should be offered.
     *
     * <p>An id that names no declared team is dropped here rather than at every call site: a
     * typo in {@code playable_teams} has to close a side, not open one that is not in the
     * level. Empty (the field unwritten) means every declared team, which is what levels
     * written before this field meant.
     */
    public List<TeamDef> playableTeamDefs() {
        if (playableTeams.isEmpty()) {
            return teams;
        }
        List<TeamDef> pickable = new ArrayList<>();
        for (Identifier id : playableTeams) {
            for (TeamDef team : teams) {
                if (team.id().equals(id)) {
                    pickable.add(team);
                    break;
                }
            }
        }
        return List.copyOf(pickable);
    }

    /**
     * True when picking a side is a real question for this level.
     *
     * <p>One playable team means the preparation screen has nothing to ask, so the entry flow
     * skips it - and a level that declares none at all keeps the screen, because a menu with
     * no choice in it is a better failure than silently starting a level as nobody.
     */
    public boolean offersTeamChoice() {
        return playableTeamDefs().size() != 1;
    }

    /** Grouped tail fields keep the outer codec inside DFU's 16-field limit. */
    public record LevelTail(LevelMusicDef music, List<InitialEntityDef> initialEntities, int maxSeedSlots,
                            LevelRewards rewards, LevelUnlock unlock, List<TypedMechanic> mechanics,
                            LevelDialogue dialogue, List<LevelHint> hints,
                            List<Identifier> playableTeams, Optional<Identifier> background,
                            List<String> hiddenSceneElements, boolean disableShaders,
                            LevelBuffPlan buffPlan, boolean seedScreen) {
        public static final com.mojang.serialization.MapCodec<LevelTail> MAP_CODEC =
                RecordCodecBuilder.mapCodec(i -> i.group(
                        LevelMusicDef.CODEC.optionalFieldOf("music", LevelMusicDef.DEFAULT).forGetter(LevelTail::music),
                        InitialEntityDef.CODEC.listOf().optionalFieldOf("initial_entities", List.of())
                                .forGetter(LevelTail::initialEntities),
                        Codec.INT.optionalFieldOf("max_seed_slots", UNSET_MAX_SEED_SLOTS)
                                .forGetter(LevelTail::maxSeedSlots),
                        LevelRewards.CODEC.optionalFieldOf("rewards", LevelRewards.DEFAULT)
                                .forGetter(LevelTail::rewards),
                        LevelUnlock.CODEC.optionalFieldOf("unlock", LevelUnlock.NONE)
                                .forGetter(LevelTail::unlock),
                        LevelMechanics.LIST_CODEC.optionalFieldOf("mechanics", List.of())
                                .forGetter(LevelTail::mechanics),
                        LevelDialogue.CODEC.optionalFieldOf("dialogue", LevelDialogue.EMPTY)
                                .forGetter(LevelTail::dialogue),
                        LevelHint.CODEC.listOf().optionalFieldOf("hints", List.of())
                                .forGetter(LevelTail::hints),
                        // Unwritten = every declared team plays, which is what every level
                        // written before this field meant.
                        Identifier.CODEC.listOf().optionalFieldOf("playable_teams", List.of())
                                .forGetter(LevelTail::playableTeams),
                        Identifier.CODEC.optionalFieldOf("background").forGetter(LevelTail::background),
                        Codec.STRING.listOf().optionalFieldOf("hidden_scene_elements", List.of())
                                .forGetter(LevelTail::hiddenSceneElements),
                        Codec.BOOL.optionalFieldOf("disable_shaders", false)
                                .forGetter(LevelTail::disableShaders),
                        // Written flat on purpose: a level's buff block is two ordinary fields
                        // beside ``slots`` and ``max_seed_slots``, not a nested object that only
                        // looks like one. See {@link LevelBuffPlan}.
                        RecordCodecBuilder.of(LevelTail::buffPlan, LevelBuffPlan.mapCodec()),
                        // Unwritten = the screen is shown, which is what every level written
                        // before it means. See {@link LevelDef#seedScreen}.
                        Codec.BOOL.optionalFieldOf(LevelDef.SEED_SCREEN_FIELD, true)
                                .forGetter(LevelTail::seedScreen)
                ).apply(i, LevelTail::new));

        public LevelTail {
            mechanics = mechanics == null ? List.of() : List.copyOf(mechanics);
            dialogue = dialogue == null ? LevelDialogue.EMPTY : dialogue;
            hints = hints == null ? List.of() : List.copyOf(hints);
            playableTeams = playableTeams == null ? List.of() : List.copyOf(playableTeams);
            background = background == null ? Optional.empty() : background;
            hiddenSceneElements = hiddenSceneElements == null
                    ? List.of() : List.copyOf(hiddenSceneElements);
            buffPlan = buffPlan == null ? LevelBuffPlan.NONE : buffPlan;
        }
    }

    /**
     * A level's buff contract: which buffs it hands out, and how many may be on at once.
     *
     * <p>The twin of {@link SeedPlan}, with the same two halves:
     *
     * <ul>
     *   <li>{@code buffs} are the level's own - they are switched on whether or not the player
     *       has ever seen them, and the player cannot switch them off. That is the same rule the
     *       level's fixed cards follow ({@code LevelDef.slots}), and for the same reason: a level
     *       that pins something is describing itself, not making a suggestion.</li>
     *   <li>{@code max_buff_slots} is how many buffs the bar holds in total. Unwritten means the
     *       level follows the player's backpack, exactly like {@code max_seed_slots} - see
     *       {@link #UNSET_MAX_BUFF_SLOTS}.</li>
     * </ul>
     *
     * <p>So {@code "buffs": ["pvzce:auto_collect"], "max_buff_slots": 3} - two flat fields, and a
     * level that only wants to switch one buff on writes one line and says nothing about counts.
     *
     * <p>That is also why this record has no codec of its own: the two fields are decoded by
     * {@code LevelTail}, one beside the other, exactly as they are written in the file.
     */
    public record LevelBuffPlan(List<Identifier> buffs, int maxBuffSlots) {
        /**
         * {@code max_buff_slots} left unwritten: the level follows the player's backpack.
         *
         * <p>The same sentinel and the same reasoning as {@link #UNSET_MAX_SEED_SLOTS}: writing a
         * number is a statement about this level, writing nothing is a statement about the
         * player, and a literal default here would silently cap an upgraded backpack.
         */
        public static final int UNSET_MAX_BUFF_SLOTS = -1;

        /** A level that says nothing about buffs: none fixed, none offered. */
        public static final LevelBuffPlan NONE = new LevelBuffPlan(List.of(), UNSET_MAX_BUFF_SLOTS);

        /**
         * The marker a level writes to say "and my player may pick some of their own".
         *
         * <p>Without it there would be no way to tell "a level written before buffs existed" from
         * "a level that deliberately runs with none", and every existing level would grow a buff
         * page offering a choice its author never made. A level opts in by listing this sentinel
         * among its {@code buffs}; it is dropped when the plan is resolved (see
         * {@code LevelBuffSelection}), so it never becomes an active buff and never needs an icon.
         */
        public static final Identifier PLAYER_CHOICE = Identifier.withDefaultNamespace("player_choice");

        /**
         * The two flat fields, as one optional group for {@code LevelTail}.
         *
         * <p>A {@code MapCodec} over the pair rather than over this record: the fields belong to
         * the level file, not to a nested object, so writing {@code "buffs": {...}} would be a
         * shape no author asked for. Both are optional and both default to {@link #NONE}.
         */
        public static com.mojang.serialization.MapCodec<LevelBuffPlan> mapCodec() {
            return RecordCodecBuilder.mapCodec(i -> i.group(
                    Identifier.CODEC.listOf().optionalFieldOf("buffs", List.of())
                            .forGetter(LevelBuffPlan::buffs),
                    Codec.INT.optionalFieldOf("max_buff_slots", UNSET_MAX_BUFF_SLOTS)
                            .forGetter(LevelBuffPlan::maxBuffSlots)
            ).apply(i, LevelBuffPlan::new));
        }

        public LevelBuffPlan {
            buffs = buffs == null ? List.of() : List.copyOf(buffs);
            maxBuffSlots = maxBuffSlots < 0 ? UNSET_MAX_BUFF_SLOTS : Math.max(maxBuffSlots, buffs.size());
        }

        /** True when the level offers its player a choice at all. */
        public boolean offersPlayerChoice() {
            return buffs.contains(PLAYER_CHOICE);
        }

        /** True when this level declares its own count rather than following the backpack. */
        public boolean declaresMaxBuffSlots() {
            return maxBuffSlots >= 0;
        }

        /**
         * The buff count this level actually gets for a player whose backpack holds
         * {@code profileSlots} buffs.
         *
         * <p>The one implementation, called by the payload the chooser is built from
         * ({@code SeedContext}) and by the server's sanitising pass, so the two can never
         * disagree about how many buffs fit.
         */
        public int effectiveMaxBuffSlots(int profileSlots) {
            int slots = declaresMaxBuffSlots() ? maxBuffSlots : Math.max(1, profileSlots);
            return Math.max(slots, fixedBuffs().size());
        }

        /** The level's own buffs, with the {@link #PLAYER_CHOICE} sentinel taken out. */
        public List<Identifier> fixedBuffs() {
            List<Identifier> fixed = new ArrayList<>(buffs.size());
            for (Identifier buff : buffs) {
                if (buff != null && !PLAYER_CHOICE.equals(buff) && !fixed.contains(buff)) {
                    fixed.add(buff);
                }
            }
            return List.copyOf(fixed);
        }
    }

    /** Data-driven music timeline; missing music defaults to a grasswalk loop. */
    public record LevelMusicDef(List<MusicCue> cues) {
        public static final LevelMusicDef DEFAULT = new LevelMusicDef(List.of(
                new MusicCue(0, "background", Optional.of(Identifier.withDefaultNamespace("music/grasswalk")),
                        true, false, 0.85F, 1F)));

        public static final Codec<LevelMusicDef> CODEC = RecordCodecBuilder.create(i -> i.group(
                MusicCue.CODEC.listOf().optionalFieldOf("cues", List.of()).forGetter(LevelMusicDef::cues)
        ).apply(i, LevelMusicDef::new));
    }

    /**
     * One timeline entry. {@code event} + {@code loop=false} is a one-shot;
     * {@code stop=true} (or a missing event) stops the track.
     */
    public record MusicCue(
            int atTick,
            String track,
            Optional<Identifier> event,
            boolean loop,
            boolean stop,
            float volume,
            float fadeSeconds
    ) {
        public static final Codec<MusicCue> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.optionalFieldOf("at_tick", 0).forGetter(MusicCue::atTick),
                Codec.STRING.optionalFieldOf("track", "background").forGetter(MusicCue::track),
                Identifier.CODEC.optionalFieldOf("event").forGetter(MusicCue::event),
                Codec.BOOL.optionalFieldOf("loop", true).forGetter(MusicCue::loop),
                Codec.BOOL.optionalFieldOf("stop", false).forGetter(MusicCue::stop),
                Codec.FLOAT.optionalFieldOf("volume", 1F).forGetter(MusicCue::volume),
                Codec.FLOAT.optionalFieldOf("fade_seconds", 1F).forGetter(MusicCue::fadeSeconds)
        ).apply(i, MusicCue::new));
    }

    public String displayName() {
        return name == null || name.isEmpty() ? id.path() : name;
    }

    /** Distinct zombie ids appearing in this level, in first-appearance wave order. */
    public List<String> previewZombieIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (WaveDef wave : waves) {
            for (WaveDef.Entry entry : wave.entries()) {
                ids.add(entry.id().toString());
            }
        }
        return List.copyOf(ids);
    }

    /**
     * The seed chooser's contract for this level: the cards the level fixed, the cards the
     * player may pick, and how many slots there are in total.
     *
     * <p>There is no per-card "fixed" flag. A card in {@code slots} is part of the level's
     * design and is pinned; the player fills the slots the level left over from the rest of
     * the card list. Fewer level cards than {@code max_seed_slots} is what creates room for
     * that choice at all - a level that fills every slot hands the player a fixed deck.
     */
    public record SeedPlan(List<Identifier> lockedSlots, List<Identifier> pickableSlots, int maxSlots) {
        public SeedPlan {
            lockedSlots = List.copyOf(lockedSlots);
            pickableSlots = List.copyOf(pickableSlots);
        }

        /** True when the level's own cards already fill every slot. */
        public boolean isFullyFixed() {
            return lockedSlots.size() >= maxSlots;
        }
    }

    /**
     * Builds the plan from the full card list, for the default backpack.
     *
     * <p>Takes that list as an argument rather than reading a registry, because what the
     * player may pick is "every card the game has, minus the level's own" and this record
     * deliberately knows nothing about registries (see {@code LevelValidator} for the
     * checks that do).
     *
     * <p>Callers that know the player's backpack - the server's sanitising pass, the level's
     * full state - use {@link #seedPlan(List, int)} with
     * {@link #effectiveMaxSeedSlots(int)}; this overload exists for the ones that do not
     * (the client's locked-slot list, the editor, tests) and answers with the default.
     */
    public SeedPlan seedPlan(List<Identifier> allCards) {
        return seedPlan(allCards, effectiveMaxSeedSlots(PvzceConstants.DEFAULT_SEED_SLOTS));
    }

    /** The plan for a bar of {@code maxSlots} cards. */
    public SeedPlan seedPlan(List<Identifier> allCards, int maxSlots) {
        List<Identifier> locked = new ArrayList<>();
        for (Identifier slot : slots) {
            if (slot != null && !locked.contains(slot) && locked.size() < maxSlots) {
                locked.add(slot);
            }
        }
        List<Identifier> pickable = new ArrayList<>();
        for (Identifier card : allCards) {
            if (card != null && !locked.contains(card) && !pickable.contains(card)) {
                pickable.add(card);
            }
        }
        return new SeedPlan(locked, pickable, maxSlots);
    }

    /**
     * The bar a player gets when they make no choices: the level's cards, then pickable
     * cards in registration order until the slots run out.
     */
    public List<Identifier> defaultSeedSelection(List<Identifier> allCards) {
        return defaultSeedSelection(allCards, effectiveMaxSeedSlots(PvzceConstants.DEFAULT_SEED_SLOTS));
    }

    /** As above, for a bar of {@code maxSlots} cards. */
    public List<Identifier> defaultSeedSelection(List<Identifier> allCards, int maxSlots) {
        SeedPlan plan = seedPlan(allCards, maxSlots);
        List<Identifier> bar = new ArrayList<>(plan.lockedSlots());
        for (Identifier card : plan.pickableSlots()) {
            if (bar.size() >= plan.maxSlots()) {
                break;
            }
            bar.add(card);
        }
        return List.copyOf(bar);
    }

    private static List<TeamDef> defaultTeams() {
        return List.of(
                new TeamDef(Identifier.withDefaultNamespace("plant_team"), "植物方", "survive_waves"),
                new TeamDef(Identifier.withDefaultNamespace("zombie_team"), "僵尸方", "plant_side_lost")
        );
    }

    private static List<Identifier> defaultSlots() {
        return List.of(Identifier.withDefaultNamespace("pea_shooter"), Identifier.withDefaultNamespace("sun"));
    }
}
