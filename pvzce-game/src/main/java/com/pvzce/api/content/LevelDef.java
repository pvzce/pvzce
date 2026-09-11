package com.pvzce.api.content;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A level definition loaded from {@code data/<ns>/pvzce/levels/<id>.json}. */
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
        int maxSeedSlots
) {
    public static final float DEFAULT_WAVE_INTERVAL_END_MULTIPLIER = 1F;
    public static final int DEFAULT_MAX_SEED_SLOTS = 6;

    /** Backwards-compatible constructor for callers/tests written before seed selection existed. */
    public LevelDef(Identifier id, String name, String description, int width, int height,
                    Map<Identifier, List<String>> scene, List<TeamDef> teams, Identifier winTeam,
                    Map<Identifier, JsonElement> rules, Map<Identifier, EnvValue> envVars,
                    List<WaveDef> waves, float waveIntervalEndMultiplier, List<Identifier> slots,
                    Map<Identifier, Boolean> unlockResources, int initialSun,
                    LevelMusicDef music, List<InitialEntityDef> initialEntities) {
        this(id, name, description, width, height, scene, teams, winTeam, rules, envVars, waves,
                waveIntervalEndMultiplier, slots, unlockResources, initialSun, music, initialEntities,
                DEFAULT_MAX_SEED_SLOTS);
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
                    tail.music(), tail.initialEntities(), tail.maxSeedSlots())));

    public LevelTail tail() {
        return new LevelTail(music, initialEntities, maxSeedSlots);
    }

    /** Grouped tail fields keep the outer codec inside DFU's 16-field limit. */
    public record LevelTail(LevelMusicDef music, List<InitialEntityDef> initialEntities, int maxSeedSlots) {
        public static final com.mojang.serialization.MapCodec<LevelTail> MAP_CODEC =
                RecordCodecBuilder.mapCodec(i -> i.group(
                        LevelMusicDef.CODEC.optionalFieldOf("music", LevelMusicDef.DEFAULT).forGetter(LevelTail::music),
                        InitialEntityDef.CODEC.listOf().optionalFieldOf("initial_entities", List.of())
                                .forGetter(LevelTail::initialEntities),
                        Codec.INT.optionalFieldOf("max_seed_slots", DEFAULT_MAX_SEED_SLOTS)
                                .forGetter(LevelTail::maxSeedSlots)
                ).apply(i, LevelTail::new));
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
