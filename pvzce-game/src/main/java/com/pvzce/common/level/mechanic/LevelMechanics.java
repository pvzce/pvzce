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
    public static final DeckMechanic DECK = new DeckMechanic();
    public static final ConveyorMechanic CONVEYOR = new ConveyorMechanic();
    public static final PlacementZoneMechanic PLACEMENT_ZONE = new PlacementZoneMechanic();
    public static final MowerMechanic MOWER = new MowerMechanic();
    public static final ToolMechanic TOOL = new ToolMechanic();
    public static final GraveSpawnerMechanic GRAVE_SPAWNER = new GraveSpawnerMechanic();

    public static final Codec<TypedMechanic> CODEC = codec();
    public static final Codec<List<TypedMechanic>> LIST_CODEC = CODEC.listOf();

    /** Registers every built-in mechanic; called from {@code BuiltInRegistries.bootstrap()}. */
    public static void bootstrap() {
        register(PvzceIds.MECHANIC_DECK, DECK);
        register(PvzceIds.MECHANIC_CONVEYOR, CONVEYOR);
        register(PvzceIds.MECHANIC_PLACEMENT_ZONE, PLACEMENT_ZONE);
        register(PvzceIds.MECHANIC_MOWER, MOWER);
        register(PvzceIds.MECHANIC_TOOL, TOOL);
        register(PvzceIds.MECHANIC_GRAVE_SPAWNER, GRAVE_SPAWNER);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void register(Identifier id, LevelMechanic<?> mechanic) {
        BuiltInRegistries.registerStatic((com.pvzce.api.registry.Registry) BuiltInRegistries.LEVEL_MECHANICS,
                id.toString(), mechanic);
    }

    public static LevelMechanic<?> get(Identifier id) {
        return BuiltInRegistries.LEVEL_MECHANICS.get(id);
    }

    /** True when the registered mechanic with that id decides where the cards come from. */
    public static boolean isCardSource(Identifier id) {
        LevelMechanic<?> mechanic = get(id);
        return mechanic != null && mechanic.cardSource();
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
     *       mowers existed gained them without being touched.</li>
     * </ul>
     *
     * <p>This is the one place both defaults are decided, so the server, the validator, the
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
     */
    public static List<LevelPayload.MechanicPayload> payloads(LevelDef def) {
        List<LevelPayload.MechanicPayload> payloads = new ArrayList<>();
        for (TypedMechanic typed : effective(def)) {
            LevelMechanic<?> mechanic = get(typed.type());
            if (mechanic == null) {
                continue;
            }
            String block = encodeBlock(mechanic, typed);
            if (block != null) {
                payloads.add(new LevelPayload.MechanicPayload(typed.type(), block));
            }
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
        for (TypedMechanic typed : mechanics) {
            LevelMechanic<?> mechanic = get(typed.type());
            if (mechanic != null && !allows(mechanic, typed, level, plant, x, y)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Erased-to-typed bridges. One unchecked cast each, in one file.
    // ------------------------------------------------------------------

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
                                                           PlantDef plant, int x, int y) {
        return mechanic.canPlacePlant(level, (D) typed.value(), plant, x, y);
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
