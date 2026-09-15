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
        List<LevelHint> hints
) {
    public static final float DEFAULT_WAVE_INTERVAL_END_MULTIPLIER = 1F;
    /**
     * The bar size levels were written against before "unwritten means the backpack".
     *
     * <p>Nothing reads it any more - the codec's default is
     * {@link #UNSET_MAX_SEED_SLOTS} and the constructors leave the field unset - so it
     * survives only as the number the legacy-level tests describe old data with. A new
     * caller that wants six slots should write six.
     */
    public static final int LEGACY_DEFAULT_MAX_SEED_SLOTS = 6;
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
    }

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
                List.<TypedMechanic>of(), LevelDialogue.EMPTY, List.of());
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
                LevelDialogue.EMPTY, List.of());
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
                maxSeedSlots, rewards, unlock, List.of(), LevelDialogue.EMPTY, List.of());
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
                maxSeedSlots, rewards, unlock, mechanics, dialogue, List.of());
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
                    tail.unlock(), tail.mechanics(), tail.dialogue(), tail.hints())));

    public LevelTail tail() {
        return new LevelTail(music, initialEntities, maxSeedSlots, rewards, unlock, mechanics,
                dialogue, hints);
    }

    /** Grouped tail fields keep the outer codec inside DFU's 16-field limit. */
    public record LevelTail(LevelMusicDef music, List<InitialEntityDef> initialEntities, int maxSeedSlots,
                            LevelRewards rewards, LevelUnlock unlock, List<TypedMechanic> mechanics,
                            LevelDialogue dialogue, List<LevelHint> hints) {
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
                                .forGetter(LevelTail::hints)
                ).apply(i, LevelTail::new));

        public LevelTail {
            mechanics = mechanics == null ? List.of() : List.copyOf(mechanics);
            dialogue = dialogue == null ? LevelDialogue.EMPTY : dialogue;
            hints = hints == null ? List.of() : List.copyOf(hints);
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
