package com.pvzce.common.level.mechanic;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.mechanic.MechanicData;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.network.packet.LevelPayload;
import com.pvzce.server.level.LevelServer;
import com.pvzce.server.level.cardsource.CardSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Registration, lookup and typed dispatch for level mechanics.
 *
 * <p>Mirrors {@code PlantCapabilities} one layer down: the built-in mechanics are
 * constants here, {@link #CODEC} is the single polymorphic codec a level's
 * {@code "mechanics"} list is decoded with, and {@link #bootstrap()} registers them into
 * {@link BuiltInRegistries#LEVEL_MECHANICS}. A mod adds its own mechanic by registering
 * a {@link LevelMechanic} against that registry before the level data is loaded, and can
 * then name it from JSON immediately.
 *
 * <p>The dispatch helpers exist because a mechanic's data is typed
 * ({@code LevelMechanic<LevelBelt>}) while a level holds the erased list
 * ({@code List<TypedMechanic>}). Each helper pairs the two back up with a single
 * unchecked cast, so callers - the level server, the validator, the level list - never
 * write one themselves.
 */
public final class LevelMechanics {
    /** Returns the first explicitly declared block of a registered mechanic type. */
    public static <D extends MechanicData> D data(LevelDef def, Identifier id, Class<D> type) {
        for (TypedMechanic block : def.mechanics()) {
            if (block.type().equals(id) && type.isInstance(block.value())) {
                return type.cast(block.value());
            }
        }
        return null;
    }

    /** Every authored music event, including phases, can be decoded while the level loads. */
    public static List<LevelDef.MusicCue> musicCues(LevelDef def) {
        List<LevelDef.MusicCue> cues = new ArrayList<>(def.music().cues());
        StagePlan stages = data(def, PvzceIds.MECHANIC_STAGES, StagePlan.class);
        if (stages != null) {
            stages.phases().forEach(phase -> cues.addAll(phase.music()));
        }
        return List.copyOf(cues);
    }

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("PVZCE/Mechanics");
    public static final DeckMechanic DECK = new DeckMechanic();
    public static final ConveyorMechanic CONVEYOR = new ConveyorMechanic();
    public static final PlacementZoneMechanic PLACEMENT_ZONE = new PlacementZoneMechanic();
    public static final MowerMechanic MOWER = new MowerMechanic();
    /** Build first, then start the waves. */
    public static final PreparationMechanic PREPARATION = new PreparationMechanic();
    /** Pairs of portals a ground zombie travels between. */
    public static final PortalMechanic PORTAL = new PortalMechanic();
    public static final ResonanceMechanic RESONANCE = new ResonanceMechanic();
    /** Seed packets the level drops onto the lawn on a clock. */
    public static final SeedRainMechanic SEED_RAIN = new SeedRainMechanic();
    /** I, Zombie's board: the enemy's garden, round by round. */
    public static final PlantGardenMechanic PLANT_GARDEN = new PlantGardenMechanic();
    /** The rhythm chart, played. */
    public static final RhythmMechanic RHYTHM = new RhythmMechanic();
    public static final ToolMechanic TOOL = new ToolMechanic();
    public static final FogMechanic FOG = new FogMechanic();
    public static final StormMechanic STORM = new StormMechanic();
    public static final WeatherMechanic WEATHER = new WeatherMechanic();
    public static final RakeMechanic RAKE = new RakeMechanic();
    public static final VaseFieldMechanic VASE_FIELD = new VaseFieldMechanic();
    public static final GraveSpawnerMechanic GRAVE_SPAWNER = new GraveSpawnerMechanic();
    public static final GraveFieldMechanic GRAVE_FIELD = new GraveFieldMechanic();
    public static final ScaryPotterMechanic SCARY_POTTER = new ScaryPotterMechanic();
    public static final WavePacingMechanic WAVE_PACING = new WavePacingMechanic();
    public static final EndlessMechanic ENDLESS = new EndlessMechanic();
    /** 对战: a human against a Jev-driven opponent, with the mode's economy and decks. */
    public static final VersusMechanic VERSUS = new VersusMechanic();
    /** Where the zombie side may put its zombies down; the mirror of {@link #PLACEMENT_ZONE}. */
    public static final ZombieZoneMechanic ZOMBIE_ZONE = new ZombieZoneMechanic();
    /** The mutation system's marker; the catalogue lives in {@code common.level.mutation}. */
    public static final com.pvzce.common.level.mutation.MutationMechanic MUTATION =
            new com.pvzce.common.level.mutation.MutationMechanic();

    public static final Codec<TypedMechanic> CODEC = codec();
    public static final Codec<List<TypedMechanic>> LIST_CODEC = CODEC.listOf();

    /** Registers every built-in mechanic; called from {@code BuiltInRegistries.bootstrap()}. */
    public static void bootstrap() {
        register(PvzceIds.MECHANIC_SURFACE_LINKS, new SurfaceLinksMechanic());
        register(PvzceIds.MECHANIC_STAGES, new StagesMechanic());
        register(PvzceIds.MECHANIC_OUTPOSTS, new OutpostsMechanic());
        register(PvzceIds.MECHANIC_DECK, DECK);
        register(PvzceIds.MECHANIC_CONVEYOR, CONVEYOR);
        register(PvzceIds.MECHANIC_PLACEMENT_ZONE, PLACEMENT_ZONE);
        register(PvzceIds.MECHANIC_MOWER, MOWER);
        register(PvzceIds.MECHANIC_TOOL, TOOL);
        register(PvzceIds.MECHANIC_FOG, FOG);
        register(PvzceIds.MECHANIC_STORM, STORM);
        register(PvzceIds.MECHANIC_WEATHER, WEATHER);
        register(PvzceIds.MECHANIC_RAKE, RAKE);
        register(PvzceIds.MECHANIC_VASE_FIELD, VASE_FIELD);
        register(PvzceIds.MECHANIC_GRAVE_SPAWNER, GRAVE_SPAWNER);
        register(PvzceIds.MECHANIC_GRAVE_FIELD, GRAVE_FIELD);
        register(PvzceIds.MECHANIC_SCARY_POTTER, SCARY_POTTER);
        register(PvzceIds.MECHANIC_WAVE_PACING, WAVE_PACING);
        register(PvzceIds.MECHANIC_ENDLESS, ENDLESS);
        register(PvzceIds.MECHANIC_PREPARATION, PREPARATION);
        register(PvzceIds.MECHANIC_PORTAL, PORTAL);
        register(PvzceIds.MECHANIC_RESONANCE, RESONANCE);
        register(PvzceIds.MECHANIC_SEED_RAIN, SEED_RAIN);
        register(PvzceIds.MECHANIC_PLANT_GARDEN, PLANT_GARDEN);
        register(PvzceIds.MECHANIC_RHYTHM, RHYTHM);
        register(PvzceIds.MECHANIC_MUTATION, MUTATION);
        register(PvzceIds.MECHANIC_VERSUS, VERSUS);
        register(PvzceIds.MECHANIC_ZOMBIE_ZONE, ZOMBIE_ZONE);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void register(Identifier id, LevelMechanic<?> mechanic) {
        BuiltInRegistries.registerStatic((com.pvzce.api.registry.Registry) BuiltInRegistries.LEVEL_MECHANICS,
                id.toString(), mechanic);
    }

    public static LevelMechanic<?> get(Identifier id) {
        return BuiltInRegistries.LEVEL_MECHANICS.get(id);
    }

    /**
     * The fog a level declares, or {@code null} when it declares none.
     *
     * <p>Static rather than an instance call because the question is asked of a definition: the
     * level list, the renderer's registration and {@code LevelServer.fogData} all want to know
     * "does this level have fog" without a running level.
     */
    public static com.pvzce.api.content.FogData fogData(
            com.pvzce.api.content.LevelDef def) {
        if (def == null) {
            return null;
        }
        for (com.pvzce.api.content.mechanic.TypedMechanic mechanic : effective(def)) {
            if (mechanic.type().equals(PvzceIds.MECHANIC_FOG)
                    && mechanic.value() instanceof com.pvzce.api.content.FogData fog) {
                return fog;
            }
        }
        return null;
    }

    /**
     * The storm a level declares, or {@code null} when it declares none.
     *
     * <p>The fog helper's twin, and asked of a definition for the same reason: the level list, the
     * renderer's registration and any test want to know "is this board a storm" without a running
     * level.
     */
    public static com.pvzce.api.content.StormData stormData(
            com.pvzce.api.content.LevelDef def) {
        if (def == null) {
            return null;
        }
        for (com.pvzce.api.content.mechanic.TypedMechanic mechanic : effective(def)) {
            if (mechanic.type().equals(PvzceIds.MECHANIC_STORM)
                    && mechanic.value() instanceof com.pvzce.api.content.StormData storm) {
                return storm;
            }
        }
        return null;
    }

    /** True when the registered mechanic with that id decides where the cards come from. */
    public static boolean isCardSource(Identifier id) {
        LevelMechanic<?> mechanic = get(id);
        return mechanic != null && mechanic.cardSource();
    }

    /**
     * Every zombie this level can send, in first-appearance order, for its preview.
     *
     * <p>The wave table first - that is what a level "sends at you" in the ordinary case - and then
     * whatever its mechanics bring on their own: 4-5's vases and the night levels' graves put
     * zombies on the lawn without a wave naming them, and a preview built from the wave table alone
     * showed an empty list for exactly those levels. De-duplicated, because a zombie the waves send
     * <em>and</em> a pot holds is one line on the screen.
     *
     * <p>Empty for a level whose zombies are generated at run time (the endless rounds, a rhythm
     * chart): the caller falls back to the opening bar of the schedule, which is the honest preview
     * of a run whose later waves do not exist yet.
     */
    public static List<String> previewZombieIds(LevelDef def) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>(def.previewZombieIds());
        for (TypedMechanic typed : effective(def)) {
            LevelMechanic<?> mechanic = get(typed.type());
            if (mechanic == null) {
                continue;
            }
            for (Identifier zombie : previewZombieIdsOf(mechanic, def, typed)) {
                ids.add(zombie.toString());
            }
        }
        return List.copyOf(ids);
    }

    @SuppressWarnings("unchecked")
    private static <D extends MechanicData> List<Identifier> previewZombieIdsOf(
            LevelMechanic<D> mechanic, LevelDef def, TypedMechanic typed) {
        return mechanic.previewZombieIds(def, (D) typed.value());
    }

    /** True when the level declares that mechanic. The read API for a level's mechanics. */
    public static boolean has(LevelDef def, Identifier mechanicId) {
        for (TypedMechanic typed : def.mechanics()) {
            if (typed.is(mechanicId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when this <em>list</em> of mechanics holds that one.
     *
     * <p>The live-list form of {@link #has(LevelDef, Identifier)}: a level's effective list is the
     * definition's plus whatever a mutation installed at runtime ({@code LevelServer.installMechanic}),
     * and "does this level have fog" has to be answerable from the list the level is actually
     * running.
     */
    public static boolean has(List<TypedMechanic> mechanics, Identifier mechanicId) {
        for (TypedMechanic typed : mechanics) {
            if (typed.is(mechanicId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The data of a mechanic this level runs with, when it is of the expected type.
     *
     * <p>The typed half of {@code def.mechanics()}: callers name the mechanic they care about
     * and get its block, instead of scanning an erased list and casting.
     *
     * <p>Answers for the <em>effective</em> list rather than the declared one, because "which
     * rows have mowers" is a question about the level and not about its file: a level that
     * never mentions mowers still has one per row, and a caller that could not see that would
     * conclude it has none. For a declared mechanic the two lists agree.
     */
    public static <D extends MechanicData> Optional<D> dataOf(LevelDef def, Identifier mechanicId,
                                                              Class<D> type) {
        for (TypedMechanic typed : effective(def)) {
            if (typed.is(mechanicId) && type.isInstance(typed.value())) {
                return Optional.of(type.cast(typed.value()));
            }
        }
        return Optional.empty();
    }

    /**
     * The level's declared card sources.
     *
     * <p>Which mechanics are card sources is a registry question, so it is answered here
     * rather than on {@link LevelDef}: the level record holds a list of mechanics and knows
     * nothing about what any of them means.
     */
    public static List<TypedMechanic> declaredCardSources(LevelDef def) {
        List<TypedMechanic> sources = new ArrayList<>();
        for (TypedMechanic typed : def.mechanics()) {
            if (isCardSource(typed.type())) {
                sources.add(typed);
            }
        }
        return List.copyOf(sources);
    }

    /** The polymorphic codec for {@code "mechanics": [...]}. */
    public static Codec<TypedMechanic> codec() {
        return Identifier.CODEC.partialDispatch("type",
                typed -> DataResult.success(typed.type()),
                id -> {
                    LevelMechanic<?> mechanic = get(id);
                    if (mechanic == null) {
                        return DataResult.error(() -> "Unknown level mechanic: " + id);
                    }
                    return DataResult.success(typedBlock(mechanic, id));
                });
    }

    /**
     * The mechanics a level actually runs with: what it declared, plus the implicit ones.
     *
     * <p>Two mechanics are implicit, and for the same reason - their absence in the file is a
     * statement rather than a gap:
     *
     * <ul>
     *   <li>the <b>deck</b>, when the level declared no card source at all: "the ordinary card
     *       bar" must not need a block that only says "the normal rules apply";</li>
     *   <li>the <b>mowers</b>, when the level did not declare them: every ordinary level has
     *       one mower per row, because that is what the lawn is, and levels written before
     *       mowers existed gained them without being touched;</li>
     *   <li>the <b>wave pacing</b>, when the level did not declare it: a level whose lawn the
     *       player has cleared should not make them wait out a countdown written for a slower
     *       player, and that is not a mode a level opts into. See
     *       {@link WavePacingMechanic#DEFAULT_PACING}.</li>
     * </ul>
     *
     * <p>This is the one place the defaults are decided, so the server, the validator, the
     * client payload and the editor cannot disagree about what an ordinary level runs with.
     */
    public static List<TypedMechanic> effective(LevelDef def) {
        List<TypedMechanic> mechanics = new ArrayList<>(def.mechanics());
        if (declaredCardSources(def).isEmpty()) {
            mechanics.add(new TypedMechanic(PvzceIds.MECHANIC_DECK, MechanicData.Empty.INSTANCE));
        }
        if (!has(def, PvzceIds.MECHANIC_MOWER)) {
            mechanics.add(new TypedMechanic(PvzceIds.MECHANIC_MOWER,
                    com.pvzce.api.content.MowerData.EVERY_ROW));
        }
        if (!has(def, PvzceIds.MECHANIC_WAVE_PACING)) {
            mechanics.add(new TypedMechanic(PvzceIds.MECHANIC_WAVE_PACING,
                    WavePacingMechanic.DEFAULT_PACING));
        }
        return List.copyOf(mechanics);
    }

    /** The declared card source, or the deck when the level declared none. */
    public static TypedMechanic cardSource(LevelDef def) {
        List<TypedMechanic> sources = declaredCardSources(def);
        return sources.isEmpty()
                ? new TypedMechanic(PvzceIds.MECHANIC_DECK, MechanicData.Empty.INSTANCE)
                : sources.get(0);
    }

    /**
     * True when the level's card source deals the cards itself: no chooser, no prices.
     *
     * <p>Asked before a level instance exists (the level list decides which screen to open,
     * and level creation decides whether a seed selection is worth sanitising), which is why
     * it is answered from the mechanic rather than from a running {@code CardSource}.
     */
    public static boolean dealsItsOwnCards(LevelDef def) {
        LevelMechanic<?> mechanic = get(cardSource(def).type());
        return mechanic != null && mechanic.dealsItsOwnCards();
    }

    /**
     * Builds the level's card source from its card-source mechanic.
     *
     * <p>A mechanic that claims to be a card source but returns nothing would leave the
     * level without a card bar, which is a mistake in that mechanic rather than in the
     * level, so it fails loudly instead of quietly falling back to a deck.
     */
    public static CardSource createCardSource(
            LevelDef def, CardSource.Context context) {
        TypedMechanic source = cardSource(def);
        LevelMechanic<?> mechanic = get(source.type());
        if (mechanic == null) {
            throw new IllegalStateException("Unknown card source mechanic " + source.type());
        }
        CardSource created = buildCardSource(mechanic, source, context);
        if (created == null) {
            throw new IllegalStateException("Card source mechanic " + source.type()
                    + " did not build a card source");
        }
        return created;
    }

    /**
     * The level's effective mechanics as wire payloads: one JSON block each, encoded with
     * the mechanic's own codec.
     *
     * <p>The implicit deck is included, so the client sees the same list the server runs
     * with - including the entry that says "the ordinary rules apply" for a level whose file
     * says nothing at all.
     *
     * <p>This is the <em>file's</em> answer. A running level has one more source of mechanics than
     * the file does - the player's own, added by {@code LevelServer.withPlayerMechanics} - so the
     * packet that starts a run goes through {@link #payloads(List)} with the list the simulation is
     * actually running. Sending this one instead was why a bought rake killed a zombie nobody could
     * see: the mechanic ran on the server and the client was never told it existed.
     */
    public static List<LevelPayload.MechanicPayload> payloads(LevelDef def) {
        return payloads(effective(def));
    }

    /** As above, from a list a caller has already resolved (the level instance's own). */
    public static List<LevelPayload.MechanicPayload> payloads(List<TypedMechanic> resolved) {
        List<LevelPayload.MechanicPayload> payloads = new ArrayList<>();
        for (TypedMechanic typed : resolved) {
            LevelMechanic<?> mechanic = get(typed.type());
            if (mechanic == null) {
                continue;
            }
            String block = encodeBlock(mechanic, typed);
            if (block == null) {
                // Decoding reports its own failures at the caller (ClientLevel.applyMechanics);
                // this is the other half, and without it the mechanic simply vanishes from the
                // client's HUD with nothing to point at.
                LOGGER.warn("Mechanic {} could not be encoded; the client will not receive it",
                        typed.type());
                continue;
            }
            payloads.add(new LevelPayload.MechanicPayload(typed.type(), block));
        }
        return List.copyOf(payloads);
    }

    /**
     * Decodes one wire block back into a mechanic's data.
     *
     * <p>Returns empty for an unknown mechanic or a block that does not decode: a client
     * that cannot draw a mechanic should still be able to play the level, and the failure
     * is reported once by the caller rather than taking the level down.
     */
    public static Optional<MechanicData> decodeBlock(Identifier type, String block) {
        LevelMechanic<?> mechanic = get(type);
        if (mechanic == null) {
            return Optional.empty();
        }
        return decodeBlock(mechanic, block);
    }

    @SuppressWarnings("unchecked")
    private static <D extends MechanicData> Optional<MechanicData> decodeBlock(
            LevelMechanic<D> mechanic, String block) {
        try {
            return mechanic.codec().codec()
                    .parse(JsonOps.INSTANCE,
                            JsonParser.parseString(block))
                    .result()
                    .map(data -> (MechanicData) data);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static <D extends MechanicData> String encodeBlock(LevelMechanic<D> mechanic, TypedMechanic typed) {
        try {
            return mechanic.codec().codec()
                    .encodeStart(JsonOps.INSTANCE, (D) typed.value())
                    .result()
                    .map(Object::toString)
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static <D extends MechanicData> CardSource buildCardSource(
            LevelMechanic<D> mechanic, TypedMechanic typed,
            CardSource.Context context) {
        return mechanic.createCardSource(context, (D) typed.value());
    }

    /**
     * Runs every effective mechanic's own validation, in declaration order.
     *
     * <p>Also the one rule no single mechanic can see: a level must not declare two card
     * sources, because the card bar can only come from one of them. What each card source
     * needs - cards, no unknown ids, no belt plus a deck - is that mechanic's own business.
     */
    public static List<String> validate(LevelDef def) {
        List<String> errors = new ArrayList<>();
        List<TypedMechanic> sources = declaredCardSources(def);
        if (sources.size() > 1) {
            List<String> ids = new ArrayList<>();
            for (TypedMechanic source : sources) {
                ids.add(source.type().toString());
            }
            errors.add("This level declares " + sources.size() + " card sources (" + String.join(", ", ids)
                    + "): the card bar can only come from one of them");
        }
        for (TypedMechanic typed : effective(def)) {
            LevelMechanic<?> mechanic = get(typed.type());
            if (mechanic == null) {
                errors.add("Unknown mechanic '" + typed.type() + "'");
                continue;
            }
            errors.addAll(validateOne(mechanic, def, typed));
        }
        return errors;
    }

    /**
     * One mechanic's own validation, asked about a block that is not in a level file.
     *
     * <p>{@link #validate(LevelDef)} is what a level runs; this is the same check reachable with a
     * chart or a fog or a belt in hand, which is how a test pins a rule the shipped data happens to
     * satisfy - "a chart may not mix rows and columns" is only observable if something can hand the
     * validator a chart that does.
     */
    @SuppressWarnings("unchecked")
    public static <D extends MechanicData> List<String> validate(LevelDef def, Identifier type,
                                                                 D data) {
        LevelMechanic<?> mechanic = get(type);
        if (mechanic == null) {
            return List.of("Unknown mechanic '" + type + "'");
        }
        return ((LevelMechanic<D>) mechanic).validate(def, data);
    }

    /** Collects one mechanic's run state into the level save. */
    public static void collectSave(TypedMechanic typed, LevelServer level,
                                   CompoundTag root) {
        LevelMechanic<?> mechanic = get(typed.type());
        if (mechanic != null) {
            collectSaveOne(mechanic, typed, level, root);
        }
    }

    /** Reads one mechanic's run state back out of the level save. */
    public static void applySave(TypedMechanic typed, LevelServer level,
                                 CompoundTag root) {
        LevelMechanic<?> mechanic = get(typed.type());
        if (mechanic != null) {
            applySaveOne(mechanic, typed, level, root);
        }
    }

    /** Runs one mechanic's construction hook. */
    public static void onLevelCreated(TypedMechanic typed, LevelServer level) {
        LevelMechanic<?> mechanic = get(typed.type());
        if (mechanic != null) {
            createdOne(mechanic, typed, level);
        }
    }

    /** Runs one mechanic's per-tick hook. */
    public static void tick(TypedMechanic typed, LevelServer level) {
        LevelMechanic<?> mechanic = get(typed.type());
        if (mechanic != null) {
            tickOne(mechanic, typed, level);
        }
    }

    /** A veto on planting from every effective mechanic, in declaration order. */
    public static boolean canPlacePlant(List<TypedMechanic> mechanics,
                                        LevelServer level,
                                        PlantDef plant, int x, int y) {
        return canPlacePlant(mechanics, level, plant, x, y, com.pvzce.common.level.SceneBoard.DEFAULT_SURFACE);
    }

    public static boolean canPlacePlant(List<TypedMechanic> mechanics, LevelServer level,
                                        PlantDef plant, int x, int y, String surface) {
        for (TypedMechanic typed : mechanics) {
            LevelMechanic<?> mechanic = get(typed.type());
            if (mechanic != null && !allows(mechanic, typed, level, plant, x, y, surface)) {
                return false;
            }
        }
        return true;
    }

    /** A veto on spending a zombie card, from every effective mechanic, in declaration order. */
    public static boolean canPlaceZombie(List<TypedMechanic> mechanics,
                                         LevelServer level, int x, int y) {
        for (TypedMechanic typed : mechanics) {
            LevelMechanic<?> mechanic = get(typed.type());
            if (mechanic != null && !allowsZombie(mechanic, typed, level, x, y)) {
                return false;
            }
        }
        return true;
    }

    /** Tells every effective mechanic that a plant was eaten. */
    public static void onPlantConsumed(List<TypedMechanic> mechanics, LevelServer level,
                                       com.pvzce.server.Team eater,
                                       com.pvzce.server.entity.PlantEntity plant) {
        for (TypedMechanic typed : mechanics) {
            LevelMechanic<?> mechanic = get(typed.type());
            if (mechanic != null) {
                consumedOne(mechanic, typed, level, eater, plant);
            }
        }
    }

    /** Tells every effective mechanic that a resource was picked up. */
    public static void onResourceCollected(List<TypedMechanic> mechanics, LevelServer level,
                                           com.pvzce.server.Team team, Identifier resource,
                                           int amount) {
        for (TypedMechanic typed : mechanics) {
            LevelMechanic<?> mechanic = get(typed.type());
            if (mechanic != null) {
                collectedOne(mechanic, typed, level, team, resource, amount);
            }
        }
    }

    /**
     * A placement-area block of a running level's own mechanic list, or the whole board.
     *
     * <p>Asked by the server for "which columns may this side use", which is a question about a
     * level that is already running (a mutation may have installed a zone mid-run). Both callers
     * name the mechanic they mean, so the plantable area and the zombie area cannot be confused for
     * one another by a helper that guessed.
     */
    public static com.pvzce.api.content.PlacementZone zoneOf(List<TypedMechanic> mechanics,
                                                             Identifier mechanicId) {
        for (TypedMechanic typed : mechanics) {
            if (typed.is(mechanicId)
                    && typed.value() instanceof com.pvzce.api.content.PlacementZone zone) {
                return zone;
            }
        }
        return com.pvzce.api.content.PlacementZone.FULL;
    }

    /** The versus block a level declares, or empty for every level that is not a versus level. */
    public static java.util.Optional<com.pvzce.api.content.VersusData> versusData(
            LevelDef def) {
        return dataOf(def, PvzceIds.MECHANIC_VERSUS, com.pvzce.api.content.VersusData.class);
    }

    /** The versus block of a running level's own mechanic list. */
    public static java.util.Optional<com.pvzce.api.content.VersusData> versusData(
            List<TypedMechanic> mechanics) {
        for (TypedMechanic typed : mechanics) {
            if (typed.is(PvzceIds.MECHANIC_VERSUS)
                    && typed.value() instanceof com.pvzce.api.content.VersusData data) {
                return java.util.Optional.of(data);
            }
        }
        return java.util.Optional.empty();
    }

    // ------------------------------------------------------------------
    // Erased-to-typed bridges. One unchecked cast each, in one file.
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static <D extends MechanicData> float zombieSpeedMultiplier(TypedMechanic typed,
            LevelServer level, com.pvzce.server.entity.ZombieEntity zombie) {
        LevelMechanic<D> mechanic = (LevelMechanic<D>) get(typed.type());
        return mechanic == null ? 1F : mechanic.zombieSpeedMultiplier(level, (D) typed.value(), zombie);
    }

    @SuppressWarnings("unchecked")
    public static <D extends MechanicData> void sendState(TypedMechanic typed, LevelServer level,
                                                         LevelServer.ServerBridge bridge) {
        LevelMechanic<D> mechanic = (LevelMechanic<D>) get(typed.type());
        if (mechanic != null) {
            mechanic.sendState(level, (D) typed.value(), bridge);
        }
    }

    @SuppressWarnings("unchecked")
    public static <D extends MechanicData> void onWaveChanged(TypedMechanic typed, LevelServer level) {
        LevelMechanic<D> mechanic = (LevelMechanic<D>) get(typed.type());
        if (mechanic != null) mechanic.onWaveChanged(level, (D) typed.value());
    }

    @SuppressWarnings("unchecked")
    private static <D extends MechanicData> MapCodec<TypedMechanic> typedBlock(
            LevelMechanic<D> mechanic, Identifier id) {
        return mechanic.codec().xmap(data -> new TypedMechanic(id, data),
                typed -> cast(typed.value()));
    }

    @SuppressWarnings("unchecked")
    private static <D extends MechanicData> List<String> validateOne(
            LevelMechanic<D> mechanic, LevelDef def, TypedMechanic typed) {
        return mechanic.validate(def, (D) typed.value());
    }

    @SuppressWarnings("unchecked")
    private static <D extends MechanicData> void collectSaveOne(
            LevelMechanic<D> mechanic, TypedMechanic typed, LevelServer level,
            CompoundTag root) {
        mechanic.collectSave(level, (D) typed.value(), root);
    }

    @SuppressWarnings("unchecked")
    private static <D extends MechanicData> void applySaveOne(
            LevelMechanic<D> mechanic, TypedMechanic typed, LevelServer level,
            CompoundTag root) {
        mechanic.applySave(level, (D) typed.value(), root);
    }

    @SuppressWarnings("unchecked")
    private static <D extends MechanicData> void createdOne(
            LevelMechanic<D> mechanic, TypedMechanic typed, LevelServer level) {
        mechanic.onLevelCreated(level, (D) typed.value());
    }

    @SuppressWarnings("unchecked")
    private static <D extends MechanicData> void tickOne(
            LevelMechanic<D> mechanic, TypedMechanic typed, LevelServer level) {
        mechanic.tick(level, (D) typed.value());
    }

    @SuppressWarnings("unchecked")
    private static <D extends MechanicData> boolean allows(LevelMechanic<D> mechanic, TypedMechanic typed,
                                                           LevelServer level,
                                                           PlantDef plant, int x, int y, String surface) {
        return mechanic.canPlacePlant(level, (D) typed.value(), plant, x, y, surface);
    }

    @SuppressWarnings("unchecked")
    private static <D extends MechanicData> boolean allowsZombie(LevelMechanic<D> mechanic,
            TypedMechanic typed, LevelServer level, int x, int y) {
        return mechanic.canPlaceZombie(level, (D) typed.value(), x, y);
    }

    @SuppressWarnings("unchecked")
    private static <D extends MechanicData> void consumedOne(LevelMechanic<D> mechanic,
            TypedMechanic typed, LevelServer level, com.pvzce.server.Team eater,
            com.pvzce.server.entity.PlantEntity plant) {
        mechanic.onPlantConsumed(level, (D) typed.value(), eater, plant);
    }

    @SuppressWarnings("unchecked")
    private static <D extends MechanicData> void collectedOne(LevelMechanic<D> mechanic,
            TypedMechanic typed, LevelServer level, com.pvzce.server.Team team, Identifier resource,
            int amount) {
        mechanic.onResourceCollected(level, (D) typed.value(), team, resource, amount);
    }

    @SuppressWarnings("unchecked")
    private static <D extends MechanicData> D cast(MechanicData value) {
        return (D) value;
    }

    /** The unknown-card check the deck and belt mechanics share. */
    static List<String> unknownCards(List<Identifier> cards, String what) {
        List<String> errors = new ArrayList<>();
        for (Identifier card : cards) {
            if (card != null && SlotResolver.resolve(card).isEmpty()) {
                errors.add("Unknown " + what + " '" + card
                        + "': no slot, plant, tool or resource with that id");
            }
        }
        return errors;
    }

    private LevelMechanics() {
    }
}
