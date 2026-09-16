package com.pvzce.server.level;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.LevelRewards;
import com.pvzce.api.content.PlacementDef;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.ProjectileDef;
import com.pvzce.api.content.ProjectileRef;
import com.pvzce.api.content.ResourceDef;
import com.pvzce.api.content.SceneElementDef;
import com.pvzce.api.content.SlotDef;
import com.pvzce.api.content.TeamDef;
import com.pvzce.api.content.ToolDef;
import com.pvzce.api.content.WaveDef;
import com.pvzce.api.content.ZombieDef;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.entity.Entity;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.PlantPlacement;
import com.pvzce.common.core.PlantPlacement.PlantLayer;
import com.pvzce.common.core.SceneCells;
import com.pvzce.common.core.SeedOptions;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.level.DayNightCycle;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.level.SceneGrid;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.IntTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.common.nbt.StringTag;
import com.pvzce.common.nbt.Tag;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.EffectEventS2C;
import com.pvzce.common.network.packet.EntityDespawnS2C;
import com.pvzce.common.network.packet.EntityUpdateS2C;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.PacketRegistry;
import com.pvzce.common.network.PvzcePackets;
import com.pvzce.common.network.packet.LevelInitS2C;
import com.pvzce.common.network.packet.LevelPayload;
import com.pvzce.common.network.packet.MusicEventS2C;
import com.pvzce.common.network.packet.ResourceCollectS2C;
import com.pvzce.common.network.packet.ResourceDeltaS2C;
import com.pvzce.common.network.packet.SceneSyncS2C;
import com.pvzce.common.network.packet.ServerMessageS2C;
import com.pvzce.common.network.packet.SeedOption;
import com.pvzce.common.network.packet.SlotInfo;
import com.pvzce.common.network.packet.SlotSyncS2C;
import com.pvzce.common.network.packet.TimeOfDayS2C;
import com.pvzce.common.network.packet.WaveProgressS2C;
import com.pvzce.common.tag.PvzceTags;
import com.pvzce.server.PvzcePlayer;
import com.pvzce.server.Slot;
import com.pvzce.server.Team;
import com.pvzce.server.ai.PlantAIPlayer;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ProjectileEntity;
import com.pvzce.server.entity.PvzceEntity;
import com.pvzce.server.entity.ResourceDropEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.env.LevelEnvVars;
import com.pvzce.server.gamerule.GameRules;
import com.pvzce.server.gamerule.PvzceClock;
import com.pvzce.common.PvzceParticles;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * The authoritative level simulation.
 *
 * <p>Everything that puts an entity on the field goes through one of the
 * {@code spawn*} methods below, and every {@code spawn*} shares the same
 * bookkeeping (grid bounds, pending-add queue, spawn packet). Before this, plants
 * were created in five different places - player placement, {@code /spawn}, the
 * plant AI, the level's initial entities and save restore - each with its own
 * idea of stacking height, carrier offset and {@code onPlaced} handling, which is
 * exactly why a restored plant behaved differently from a freshly planted one.
 */
public final class LevelServer implements LevelAccess {
    public static final int DEFAULT_COLUMNS = PvzceConstants.DEFAULT_GRID_WIDTH;
    public static final int DEFAULT_ROWS = PvzceConstants.DEFAULT_GRID_HEIGHT;

    private final LevelDef def;
    private final List<WaveDef> waves;
    private final SceneGrid<SceneElementDef> scene;
    private final SeedContext seedContext;
    private final Map<Identifier, Team> teams = new HashMap<>();
    private final List<PvzceEntity> entities = new ArrayList<>();
    private final List<PvzceEntity> pendingAdd = new ArrayList<>();
    private final List<PvzceEntity> pendingRemove = new ArrayList<>();
    private final List<PendingWaveSpawn> pendingWaveSpawns = new ArrayList<>();
    private final Map<Integer, Integer> craterTimers = new HashMap<>();
    private final Random random = new Random();
    private final PvzcePlayer plantPlayer;
    private final GameRules rules;
    private final LevelEnvVars envVars;
    private final PvzceClock clock = new PvzceClock();
    private final PlantAIPlayer plantAi = new PlantAIPlayer();
    /**
     * Where this level's cards come from: the ordinary deck, a conveyor belt, or whatever
     * a registered mechanic deals. Never null while there is a plant player.
     *
     * <p>This replaced a nullable {@code belt} field plus five {@code belt != null} branches;
     * the branches are now the implementation's own behaviour.
     */
    private final com.pvzce.server.level.cardsource.CardSource cardSource;
    /**
     * This level's mechanics, with the implicit deck already resolved.
     *
     * <p>Resolved once because {@code effective()} has to look each declared id up in the
     * registry and materialise the implicit deck; the tick loop asks for it every tick, and
     * the answer cannot change for a level instance (content is frozen before any level is
     * built).
     */
    private final List<TypedMechanic> mechanics;
    /** Per-mechanic run state; see {@link #mechanicState}. */
    private final Map<Identifier, Object> mechanicState = new HashMap<>();
    private ServerBridge bridge;

    private int tickCount;
    private int nextWaveIndex;
    private int nextMusicCueIndex;
    private int waveIntervalTicks;
    private int nextWaveDelayTicks;
    private float waveProgress;
    private boolean waveWarningActive;
    private boolean waveWarningFinal;
    /**
     * Whether a due wave is being held back for an opening wave's field to clear, and for how
     * much longer. See {@link #openingWaveStillOnTheField()}.
     */
    private boolean openingGateArmed;
    private int openingGateTicks;
    private int openingGateHoldTicks;
    /** True while the wave that is due is waiting on that gate; keeps its banner up. */
    private boolean waveArrivalHeld;
    private String gameState = GameStateS2C.RUNNING;
    private Identifier winner;
    private Identifier humanTeamId = PvzceIds.PLANT_TEAM;
    /**
     * Where the most recent zombie died, in cells, or NaN when none has.
     *
     * <p>The end-of-level reward lands there: the original drops the seed packet or money
     * bag on the spot the fight finished, which is a better answer to "where should this
     * appear" than the middle of the lawn.
     */
    private float lastKillX = Float.NaN;
    private float lastKillY = Float.NaN;
    private boolean gameEndPacketSent;
    private boolean waveDirty = true;
    /**
     * Set when something about an entity changed that the client must hear about now.
     *
     * <p>Entity state is streamed every third tick, which is invisible for a walk cycle but
     * not for a *transition the level may never tick again after*: a zombie that dies on a
     * non-sync tick is followed by {@code checkEnd} ending the level in the same tick, so
     * the update carrying its death animation would never be sent and the client would be
     * left with a live-looking zombie standing on a finished board.
     *
     * <p>Same story for a plant that detonates: its {@code explode} state has to go out on
     * the tick it happens rather than on whichever third tick comes next, or the client
     * hears about the blast only as a despawn. {@link #requestEntitySync} is how a
     * capability asks for that, since it has no access to this field.
     */
    private boolean entitySyncPending;
    /**
     * Wave indices whose arrival has already been announced.
     *
     * <p>{@code triggerWave} is the only emitter, and it advances the index, so in a clean
     * run each wave announces once by construction. This makes it a guarantee instead:
     * a save restored from the middle of a wave, a replayed tick, or a future caller that
     * triggers a wave directly cannot make the siren play twice - which is the one way
     * "the last wave's sound" could loop, since the sound itself does not.
     */
    private final java.util.Set<Integer> announcedWaves = new java.util.HashSet<>();
    /**
     * Wave indices whose <em>warning</em> has already called out.
     *
     * <p>The huge-wave sound belongs to the warning, not to the arrival: in the original
     * Dave's line plays while the red text is fading in, and the two are one beat. The
     * warning is a window of ticks rather than an event, so the transition into it is what
     * fires the sound, and this set keeps a restored save - which re-enters the same window
     * - from calling out twice.
     */
    private final java.util.Set<Integer> announcedWarnings = new java.util.HashSet<>();
    /**
     * The plant the glove is holding, or {@code -1}.
     *
     * <p>A move is two clicks, so the level has to remember the first one. Kept as an
     * entity id rather than a cell: the cell could be filled by something else between
     * the clicks, and the id cannot.
     */
    private int carriedPlantId = -1;
    /** Ticks left before an abandoned carry is put back where it came from. */
    private int carryTimeoutTicks;
    /**
     * How long an unfinished move waits before it is abandoned (15s at 60tps).
     *
     * <p>Nothing is removed during a carry, so giving up simply drops the reference and
     * the plant is where it always was.
     */
    public static final int CARRY_TIMEOUT_TICKS = 900;

    /**
     * What one swing of the hammer tool is worth, and under which damage type.
     *
     * <p>The tool is a mower in the player's hand - it removes what it hits rather than
     * wearing it down - so it lands as {@code pvzce:mower}, the type that ignores armour.
     * The amount is more than any zombie has, so the value is documentation rather than a
     * number anyone should tune.
     */
    private static final int HAMMER_TOOL_DAMAGE = 100_000;

    public LevelServer(LevelDef def) {
        this(def, def.slots());
    }

    /** Creates a level whose plant player starts with the supplied seed selection, in order. */
    public LevelServer(LevelDef def, List<Identifier> selectedSlots) {
        this(def, selectedSlots, SeedContext.all(def));
    }

    /**
     * Creates a level with the chooser pool the client will be shown.
     *
     * <p>The pool is an input rather than something this class derives, because
     * deriving it would mean reading the player's backpack, and a level does not
     * know which world - let alone which profile - it was started from. The
     * server resolves it once and hands it in; {@link #sendFullState} then only
     * replays what it was given, so the cards the client sees and the cards
     * {@code PvzceServer.sanitizeSeedSelection} accepts cannot drift apart.
     */
    public LevelServer(LevelDef def, List<Identifier> selectedSlots, SeedContext seedContext) {
        this.def = def;
        this.seedContext = seedContext == null ? SeedContext.all(def) : seedContext;
        this.waves = normalizeWaves(def.waves());
        this.scene = SceneGrid.create(def.width(), def.height(), defaultSceneElement());
        for (SceneGrid.Cell<Identifier> cell : SceneCells.parse(def.scene(), def.width(), def.height())) {
            SceneElementDef element = BuiltInRegistries.SCENE_ELEMENTS.get(cell.value());
            if (element != null) {
                scene.set(cell.x(), cell.y(), element);
            }
        }

        for (TeamDef teamDef : def.teams()) {
            teams.put(teamDef.id(), new Team(teamDef.id(), teamDef.name()));
        }
        this.rules = new GameRules(def.rules());
        this.envVars = new LevelEnvVars(def.envVars());
        this.nextWaveDelayTicks = waves.isEmpty() ? -1 : effectiveWaveDelay(0);

        this.mechanics = LevelMechanics.effective(def);
        Team plantTeam = teams.get(PvzceIds.PLANT_TEAM);
        // The level owns the bar; the card source fills it. A self-dealt level (a conveyor
        // belt) ignores the seed selection, which is why the selection is still passed in:
        // the source decides what to do with it, not this constructor.
        this.plantPlayer = plantTeam != null ? new PvzcePlayer(plantTeam) : null;
        this.cardSource = this.plantPlayer != null
                ? LevelMechanics.createCardSource(def, new com.pvzce.server.level.cardsource.CardSource.Context(
                        this.plantPlayer, def, selectedSlotsOrDef(selectedSlots), random))
                : null;
        if (plantTeam != null) {
            plantTeam.putResource(PvzceIds.SUN, def.initialSun());
            for (Identifier slotId : def.slots()) {
                SlotDef slotDef = BuiltInRegistries.SLOT_TYPES.get(slotId);
                if (slotDef != null && slotDef.kind() == SlotDef.Kind.RESOURCE) {
                    plantTeam.unlockResource(slotDef.content());
                }
            }
            def.unlockResources().forEach((resource, unlocked) -> {
                if (unlocked) {
                    plantTeam.unlockResource(resource);
                }
            });
        }
        // Initial entities use the same placement path as everything else.
        for (var init : def.initialEntities()) {
            spawnInitialEntity(init);
        }
        // Last: a mechanic that needs to look at the finished board may do so here.
        for (TypedMechanic typed : mechanics) {
            LevelMechanics.onLevelCreated(typed, this);
        }
        reportLevelProblems();
    }

    /**
     * Reports every inconsistency in a level definition through one path.
     *
     * <p>Level data used to be validated by four different policies: a bad rule
     * value threw out of the constructor, an unknown rule was dropped silently, a
     * bad env var returned the caller's fallback, and an unknown entity id left a
     * half-built level behind. All of them are collected here so an author sees one
     * list instead of debugging a level that half-loaded.
     */
    private void reportLevelProblems() {
        List<String> problems = new java.util.ArrayList<>();
        problems.addAll(LevelValidator.validatePlacementTags());
        problems.addAll(GameRules.validate(def.rules()));
        problems.addAll(LevelValidator.validateEnvVars(def.envVars()));
        problems.addAll(LevelMechanics.validate(def));
        problems.addAll(LevelValidator.validateRewards(def));
        problems.addAll(LevelValidator.validateUnlock(def));
        problems.addAll(LevelValidator.validateUnlockCycles());
        problems.addAll(LevelValidator.validateScene(def));
        problems.addAll(LevelValidator.validateInitialEntities(def));
        problems.addAll(LevelValidator.validateDialogue(def));
        problems.addAll(LevelValidator.validateHints(def));
        if (!problems.isEmpty()) {
            System.err.println("[PVZCE] Level " + def.id() + " has " + problems.size() + " problem(s):");
            for (String problem : problems) {
                System.err.println("[PVZCE]   - " + problem);
            }
        }
        // Notes are not defects: a fixed deck is exactly what the first level is. They
        // are reported separately so that "has N problem(s)" keeps meaning something.
        String note = LevelValidator.describeFixedDeck(def);
        if (note != null) {
            System.err.println("[PVZCE] Level " + def.id() + " note: " + note);
        }
    }

    private List<Identifier> selectedSlotsOrDef(List<Identifier> selectedSlots) {
        List<Identifier> cards = selectedSlots == null ? def.slots() : selectedSlots;
        return cards == null ? List.of() : cards;
    }

    private static SceneElementDef defaultSceneElement() {
        SceneElementDef grass = BuiltInRegistries.SCENE_ELEMENTS.get(PvzceIds.GRASS);
        return grass != null ? grass : BuiltInRegistries.SCENE_ELEMENTS.get(PvzceIds.GROUND);
    }

    private void spawnInitialEntity(com.pvzce.api.content.InitialEntityDef init) {
        Team plantTeam = teams.get(PvzceIds.PLANT_TEAM);
        Team zombieTeam = zombieTeam();
        if (init.kind().startsWith("p")) {
            PlantDef plantDef = BuiltInRegistries.PLANTS.get(init.id());
            if (plantDef != null && inBounds(init.x(), init.y())) {
                spawnPlant(plantDef, plantTeam, init.x(), init.y());
            }
        } else if (init.kind().startsWith("z")) {
            spawnZombie(init.id(), zombieTeam, init.x() + 0.5F, init.y());
        }
    }

    /**
     * Cross-field normalization: a final wave may only be the last one, and the
     * last wave is always treated as final even when the data omits the type.
     */
    private static List<WaveDef> normalizeWaves(List<WaveDef> configured) {
        if (configured.isEmpty()) {
            return List.of();
        }
        List<WaveDef> normalized = new ArrayList<>(configured.size());
        int last = configured.size() - 1;
        for (int i = 0; i <= last; i++) {
            WaveDef wave = configured.get(i);
            WaveDef.WaveType type = wave.type();
            if (i < last && type == WaveDef.WaveType.FINAL) {
                System.out.println("[PVZCE] Wave " + (i + 1) + " is marked final but is not last; treating it as huge.");
                type = WaveDef.WaveType.HUGE;
            } else if (i == last && type != WaveDef.WaveType.FINAL) {
                type = WaveDef.WaveType.FINAL;
            }
            normalized.add(type == wave.type() ? wave : wave.asType(type));
        }
        return List.copyOf(normalized);
    }

    /**
     * Effective delay of a wave after applying the level's linear interval curve.
     * The first wave uses the configured delay as-is; the last wave uses
     * {@code wave_interval_end_multiplier}.
     */
    private int effectiveWaveDelay(int waveIndex) {
        WaveDef wave = waves.get(waveIndex);
        int total = waves.size();
        float configuredMultiplier = def.waveIntervalEndMultiplier();
        float endMultiplier = Float.isFinite(configuredMultiplier) ? configuredMultiplier : 1F;
        endMultiplier = Math.max(0.05F, Math.min(10F, endMultiplier));
        float progress = total <= 1 ? 0F : waveIndex / (float) (total - 1);
        float multiplier = 1F + (endMultiplier - 1F) * progress;
        return Math.max(1, Math.round(wave.delay() * multiplier));
    }

    public LevelDef def() {
        return def;
    }

    public LevelEnvVars envVars() {
        return envVars;
    }

    public PvzceClock clock() {
        return clock;
    }

    public PvzcePlayer plantPlayer() {
        return plantPlayer;
    }

    public Team team(Identifier id) {
        return teams.get(id);
    }

    public Identifier humanTeamId() {
        return humanTeamId;
    }

    public void setHumanTeam(Identifier humanTeamId) {
        if (teams.containsKey(humanTeamId)) {
            this.humanTeamId = humanTeamId;
        }
    }

    public String gameState() {
        return gameState;
    }

    /** Where the last zombie died, or NaN when none has; the level's reward lands there. */
    public float lastKillX() {
        return lastKillX;
    }

    public float lastKillY() {
        return lastKillY;
    }

    public Identifier winner() {
        return winner;
    }

    public void setDayTicks(long dayTicks) {
        clock.setDayTicks(dayTicks);
    }

    public void addDayTicks(long amount) {
        clock.addDayTicks(amount);
    }

    /** The one and only builder for the time-of-day packet. */
    public TimeOfDayS2C timeOfDayPacket() {
        return new TimeOfDayS2C((int) clock.dayTicks(), clock.dayLength(rules), clock.nightLength(rules));
    }

    // ------------------------------------------------------------------
    // LevelAccess
    // ------------------------------------------------------------------

    @Override
    public int width() {
        return def.width();
    }

    @Override
    public int height() {
        return def.height();
    }

    @Override
    public int tickCount() {
        return tickCount;
    }

    @Override
    public Random random() {
        return random;
    }

    @Override
    public GameRules rules() {
        return rules;
    }

    @Override
    public boolean isNight() {
        return clock.isNight(rules);
    }

    @Override
    public SceneElementDef sceneAt(int x, int y) {
        return scene.get(x, y);
    }

    public void setScene(int x, int y, Identifier elementId) {
        SceneElementDef element = BuiltInRegistries.SCENE_ELEMENTS.get(elementId);
        if (element != null) {
            scene.set(x, y, element);
        }
    }

    @Override
    public List<PlantEntity> plantsAt(int column, int row) {
        return entities.stream()
                .filter(e -> e instanceof PlantEntity p && !p.isRemoved() && p.gridX() == column && p.gridY() == row)
                .map(e -> (PlantEntity) e)
                .toList();
    }

    /**
     * The cell's stack, as the tag-driven placement rules see it.
     *
     * <p>Built fresh per query rather than cached: a cell holds at most a handful
     * of plants, and a cache would have to be invalidated on every spawn, removal
     * and scene write - three places that already disagree often enough.
     */
    private final PlantPlacement.Ctx placementContext = new PlantPlacement.Ctx() {
        @Override
        public PlantPlacement.Terrain terrain(int x, int y) {
            SceneElementDef element = sceneAt(x, y);
            return element == null
                    ? PlantPlacement.Terrain.NONE
                    : new PlantPlacement.Terrain(element, element.heightAt(x + 0.5F, width()));
        }

        @Override
        public List<PlantPlacement.PlantLayer> plants(int x, int y) {
            List<PlantLayer> layers = new ArrayList<>();
            for (PlantEntity plant : plantsAt(x, y)) {
                layers.add(new PlantLayer(plant.def(), plant.id()));
            }
            // Bottom-to-top, the order PlantPlacement documents: a caller that zips
            // this list with plantsAt() must get the same order.
            layers.sort(PlantPlacement.bottomFirst());
            return layers;
        }
    };

    /** Plants in a cell ordered bottom-to-top by the tag-driven stacking rules. */
    public List<PlantEntity> plantsBottomFirst(int column, int row) {
        return plantsAt(column, row).stream()
                .sorted(Comparator.comparingInt((PlantEntity p) -> PlantPlacement.layerIndex(p.def()))
                        .thenComparingInt(PlantEntity::id))
                .toList();
    }

    /**
     * The topmost plant in a cell (the one zombies bite and the shovel removes first).
     *
     * <p>Plants that do not occupy their cell are skipped: a bowling Wall-nut is a plant
     * that is leaving one, and a zombie that stopped to eat it - or a shovel that removed
     * it - would be interacting with a cell it is only passing through. The plant is still
     * a plant for rendering, health and its own ticking; it is simply not part of the
     * board's furniture while it rolls.
     */
    @Override
    public PlantEntity plantAt(int column, int row) {
        List<PlantEntity> plants = plantsBottomFirst(column, row);
        for (int i = plants.size() - 1; i >= 0; i--) {
            if (plants.get(i).occupiesCell()) {
                return plants.get(i);
            }
        }
        return null;
    }

    /**
     * Whether {@code def} may be planted at a cell.
     *
     * <p>The rules themselves live in {@link PlantPlacement}: this method only
     * adapts the level to them, so a unit test can exercise the whole matrix
     * without a running level. It used to be a five-branch switch over the
     * {@code feet} string plus a handful of hard-coded element ids
     * ({@code "flower_pot"}, {@code SURFACE_WATER}, ...), which is why a mod could
     * not add terrain or a carrier without a code change.
     *
     * <p>The level's own mechanics get a veto first, because a plantable area is not a
     * property of the plant or of the tile: it is the level saying "this mini-game is
     * played on the left half of the lawn". Everything the <em>player</em> plants goes
     * through here - {@code placePlantInternal}, the glove and the plant AI - so a
     * restricted level cannot be talked past. The two authoring paths that deliberately do
     * not consult it are {@code /spawn plant} and a level's own {@code initial_entities}:
     * both are the level (or its author) placing things, not someone playing it.
     */
    public boolean canPlacePlant(PlantDef def, int x, int y) {
        return inBounds(x, y)
                && LevelMechanics.canPlacePlant(mechanics, this, def, x, y)
                && PlantPlacement.canPlace(def, placementContext, x, y);
    }

    /** True when this plant occupies the carrier layer (flower pot, lily pad). */
    public static boolean isCarrier(PlantEntity plant) {
        return plant != null && PlantPlacement.isCarrier(plant.def());
    }

    public List<PvzceEntity> entities() {
        return List.copyOf(entities);
    }

    @Override
    public List<ZombieEntity> zombiesInRow(int row) {
        return entities.stream()
                .filter(e -> e instanceof ZombieEntity z && z.isAlive() && z.gridY() == row)
                .map(e -> (ZombieEntity) e)
                .toList();
    }

    public int plantCount() {
        return (int) entities.stream().filter(e -> e instanceof PlantEntity p && !p.isRemoved()).count();
    }

    public long aliveZombieCount() {
        return entities.stream().filter(e -> e instanceof ZombieEntity z && z.isAlive()).count();
    }

    public boolean inBounds(int x, int y) {
        return x >= 0 && x < width() && y >= 0 && y < height();
    }

    // ------------------------------------------------------------------
    // Spawning - every entity enters the field through these methods
    // ------------------------------------------------------------------

    @Override
    public void addEntity(Entity entity) {
        if (entity instanceof PvzceEntity serverEntity) {
            addEntity(serverEntity);
        }
    }

    public void addEntity(PvzceEntity entity) {
        entity.setGridBounds(width(), height());
        pendingAdd.add(entity);
    }

    /**
     * Places a plant, applying carrier offset, stacking height and the
     * {@code onPlaced} hook exactly once. Every placement path - player, AI,
     * command, level JSON and save restore - goes through here.
     */
    public PlantEntity spawnPlant(PlantDef def, Team team, int x, int y) {
        PlantEntity plant = new PlantEntity(def, team, x, y);
        plant.setGridBounds(width(), height());
        if (plantsAt(x, y).stream().anyMatch(LevelServer::isCarrier)) {
            plant.setCellX(plant.cellX() + PlantPlacement.CARRIER_X_OFFSET);
        }
        plant.setHeight(PlantPlacement.placementHeight(placementContext, x, y));
        addEntity(plant);
        flushPending();
        plant.onPlaced(this);
        flushPending();
        if (!plant.isRemoved()) {
            emitEffect(PvzceParticles.DIRT_SMALL.toString(), x + 0.5F, y + 0.5F, placementSound(def, x, y));
        }
        return plant;
    }

    private Identifier placementSound(PlantDef def, int x, int y) {
        SceneElementDef base = sceneAt(x, y);
        Identifier fallback = base != null && PlantPlacement.terrainTagged(
                PlantPlacement.Terrain.of(base), PvzceTags.SCENE_WATER)
                ? PvzceSounds.PLANT_PLANT_WATER
                : PvzceSounds.PLANT_PLANT;
        return def.sounds().place().orElse(fallback);
    }

    @Override
    public void spawnProjectile(ProjectileRef ref, float x, float y, PlantEntity source) {
        ProjectileDef projectileDef = BuiltInRegistries.PROJECTILES.get(ref.projectile());
        if (projectileDef == null) {
            return;
        }
        addEntity(new ProjectileEntity(projectileDef, ref, source.team(), x, y, source.height()));
    }

    @Override
    public void spawnArcProjectile(ProjectileRef ref, float x, float y, PlantEntity source, ZombieEntity target) {
        ProjectileDef projectileDef = BuiltInRegistries.PROJECTILES.get(ref.projectile());
        if (projectileDef == null) {
            return;
        }
        SceneElementDef base = sceneAt(target.gridX(), target.gridY());
        target.setHeight(base == null ? 0F : base.heightAt(target.cellX(), width()));
        addEntity(new ProjectileEntity(projectileDef, ref, source.team(), x, y, source.height(), target));
    }

    @Override
    public void spawnResource(Identifier resourceId, int amount, float x, float y, Team team) {
        spawnResource(resourceId, amount, x, y, team, null);
    }

    @Override
    public void spawnProducedResource(Identifier resourceId, int amount, float x, float y, Team team) {
        spawnResource(resourceId, amount, x, y, team, ResourceDef.DropMotion.RISE);
    }

    private void spawnResource(Identifier resourceId, int amount, float x, float y, Team team,
                               ResourceDef.DropMotion motion) {
        ResourceDef resource = BuiltInRegistries.RESOURCES.get(resourceId);
        if (resource == null) {
            return;
        }
        // The sideways throw of a popping drop is rolled here, from the level's own Random:
        // entities do not own dice (see LevelAccess#random), and a drop that drew its own
        // would ignore the level's seed and re-roll itself on every restore.
        float scatter = Math.max(0F, resource.riseScatter());
        float driftX = scatter <= 0F ? 0F : (random.nextFloat() * 2F - 1F) * scatter;
        addEntity(new ResourceDropEntity(resource, team, (int) Math.floor(x), (int) Math.floor(y),
                amount, motion, driftX));
    }

    @Override
    public ZombieEntity spawnZombie(Identifier zombieId, Team team, float x, int row) {
        ZombieDef def = BuiltInRegistries.ZOMBIES.get(zombieId);
        if (def == null) {
            return null;
        }
        ZombieEntity zombie = new ZombieEntity(def, team, x, row);
        addEntity(zombie);
        emitEffect("", x, row + 0.5F, def.sounds().spawn().orElse(PvzceSounds.ZOMBIE_GROAN));
        return zombie;
    }

    /** Debug spawn used by /spawn and /summon. */
    public boolean spawnEntity(String kind, Identifier id, int x, int y) {
        if (!inBounds(x, y)) {
            return false;
        }
        return switch (kind.toLowerCase(Locale.ROOT)) {
            case "plant", "p" -> {
                PlantDef def = BuiltInRegistries.PLANTS.get(id);
                if (def == null) {
                    yield false;
                }
                spawnPlant(def, teams.get(PvzceIds.PLANT_TEAM), x, y);
                yield true;
            }
            case "zombie", "z" -> {
                if (!BuiltInRegistries.ZOMBIES.containsKey(id)) {
                    yield false;
                }
                spawnZombie(id, zombieTeam(), x + 0.5F, y);
                yield true;
            }
            case "projectile", "bullet" -> {
                ProjectileDef def = BuiltInRegistries.PROJECTILES.get(id);
                if (def == null) {
                    yield false;
                }
                addEntity(new ProjectileEntity(def, null, teams.get(PvzceIds.PLANT_TEAM),
                        x + 0.5F, y + 0.5F, sceneAt(x, y) == null ? 0F : sceneAt(x, y).heightAt(x + 0.5F, width())));
                yield true;
            }
            case "resource", "drop", "sun" -> {
                if (BuiltInRegistries.RESOURCES.get(id) == null) {
                    yield false;
                }
                spawnResource(id, 25, x, y, teams.get(PvzceIds.PLANT_TEAM));
                yield true;
            }
            default -> false;
        };
    }

    @Override
    public void damageArea(com.pvzce.api.content.DamageTypeDef type, float centerX, float centerY, float radius, int damage,
                           Team sourceTeam) {
        // No multiplier here: ZombieEntity.damage applies it once, at the one entry
        // point every hit goes through. A second copy made a rule that already means
        // "how hard plants hit" depend on which damage path happened to run.
        for (PvzceEntity entity : new ArrayList<>(entities)) {
            if (!(entity instanceof ZombieEntity zombie) || !zombie.isAlive()) {
                continue;
            }
            if (Math.abs(zombie.cellX() - centerX) <= radius && Math.abs(zombie.cellY() - centerY) <= radius) {
                zombie.damage(damage, type, this);
            }
        }
    }

    @Override
    public void emitEffect(String particle, float x, float y, Identifier sound) {
        emitEffect(particle, x, y, sound, 1F, 1F);
    }

    public void emitEffect(String particle, float x, float y, Identifier sound, float volume, float pitch) {
        emitEffect(particle, x, y, sound, volume, pitch, "", 0F);
    }

    /**
     * The one place an effect packet is built.
     *
     * <p>Every caller funnels through here so a presentation event cannot be sent
     * with the ripple field set on one path and forgotten on another - the packet
     * has seven fields and they are easy to transpose.
     */
    public void emitEffect(String particle, float x, float y, Identifier sound,
                           float volume, float pitch, String ripple, float rippleStrength) {
        if (bridge == null) {
            return;
        }
        bridge.send(new EffectEventS2C(particle, x, y, sound == null ? "" : sound.toString(),
                volume, pitch, ripple == null ? "" : ripple, rippleStrength));
    }

    @Override
    public void emitRipple(Identifier liquid, float x, float y, float strength) {
        if (liquid == null || strength <= 0F) {
            return;
        }
        emitEffect("", x, y, null, 1F, 1F, liquid.toString(), Math.min(1F, strength));
    }

    @Override
    public void emitRippleAt(int cellX, int cellY, float strength) {
        SceneElementDef element = sceneAt(cellX, cellY);
        if (element == null || element.liquid().isEmpty()) {
            return;
        }
        emitRipple(element.liquid().get(), cellX + 0.5F, cellY + 0.5F, strength);
    }

    public void sendSceneCell(int x, int y) {
        if (bridge == null) {
            return;
        }
        SceneElementDef element = sceneAt(x, y);
        if (element != null) {
            bridge.send(SceneSyncS2C.of(x, y, element.id().toString()));
        }
    }

    public void sendMessage(String message) {
        if (bridge != null) {
            bridge.send(new ServerMessageS2C(message));
        }
    }

    /**
     * Asks for the next entity sync to happen now instead of on the third tick.
     *
     * <p>For a state a capability just put an entity into, where the tick it happened on
     * is the tick the client has to hear about it - a plant detonating is the case this
     * exists for (see {@link #entitySyncPending}). Idempotent, and harmless outside a
     * tick: the flag is read by {@code syncSlots}, which only runs inside one.
     */
    public void requestEntitySync() {
        entitySyncPending = true;
    }

    /**
     * Sends one packet to this level's client, or does nothing outside a tick.
     *
     * <p>The door a mechanic needs: its tick hook runs inside {@link #tick}, where the bridge
     * is set, and it has no other way to reach the client. Anything sent here is dropped
     * before the first tick rather than queued, which is the same thing {@code emitEffect}
     * already does.
     */
    public void send(PvzcePacket packet) {
        if (bridge != null) {
            bridge.send(packet);
        }
    }

    /**
     * A mechanic's own per-level state.
     *
     * <p>Registered mechanics are shared registry entries - one instance serves every level -
     * so a mechanic that keeps a run's state (which mowers are spent, where a belt is up to)
     * cannot keep it in a field. It asks the level for a slot addressed by its own id
     * instead, and this class never learns what is in it.
     *
     * <p>{@code create} runs at most once per level instance; every later caller gets the
     * object the first one made.
     */
    @SuppressWarnings("unchecked")
    public <T> T mechanicState(Identifier mechanicId, java.util.function.Supplier<T> create) {
        return (T) mechanicState.computeIfAbsent(mechanicId, id -> create.get());
    }

    /**
     * Lawn mowers still parked in their row, which a win pays out as coins.
     *
     * <p>Reads the mower mechanic's own rig, so "how many are left" has one answer; a level
     * with no mowers (Wall-nut Bowling) and a level that never created the rig both report
     * zero. A rolling mower counts as used: it is on the lawn, not in its row.
     */
    public int readyMowerCount() {
        Object state = mechanicState.get(PvzceIds.MECHANIC_MOWER);
        return state instanceof com.pvzce.common.level.mechanic.MowerMechanic.Rig rig
                ? rig.readyCount() : 0;
    }

    /**
     * Sends one row's parked lawn mower early, at the player's request.
     *
     * <p>Answers whether it went, which is also the whole of the permission check: the row
     * must have a mower and that mower must still be parked. Nothing else is consulted, and
     * in particular the board does not have to be under attack - sending a mower ahead of a
     * wave is a legitimate (and irreversible) decision, which is what makes the long press
     * worth having.
     *
     * <p>Refused while the level is not running, so a packet that arrives between the last
     * zombie dying and the payout cannot quietly spend a mower the reward has already
     * counted as surviving.
     */
    public boolean releaseMower(int row) {
        if (!gameState.equals(GameStateS2C.RUNNING)) {
            return false;
        }
        Object state = mechanicState.get(PvzceIds.MECHANIC_MOWER);
        return state instanceof com.pvzce.common.level.mechanic.MowerMechanic.Rig rig
                && rig.release(row);
    }

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    public interface ServerBridge {
        void send(PvzcePacket packet);
    }

    /**
     * The card pool one level instance offers its client, resolved once by the
     * server.
     *
     * <p>{@code pool} is what the chooser may show and {@code lockedSlotIds} is
     * what the level pinned; the difference between them is the player's choice.
     * Both travel into {@code LevelPayload} so the chooser and the in-game bar
     * describe the same deck. The locked ids are the wire spelling, not
     * {@code Identifier}s, because that is the only form they are ever used in.
     *
     * <p>{@code maxSeedSlots} is the <em>resolved</em> bar size
     * ({@link LevelDef#effectiveMaxSeedSlots}): a level that declares its own count keeps
     * it, a level that declares none borrows the backpack's. Resolved here because this
     * record is built where the profile is known, and both the packet and the seed plan
     * read it back from the one place.
     */
    public record SeedContext(List<SeedOption> pool, List<String> lockedSlotIds, int maxSeedSlots) {
        public SeedContext {
            pool = List.copyOf(pool);
            lockedSlotIds = List.copyOf(lockedSlotIds);
        }

        /**
         * Everything is owned: the pool is every registered card.
         *
         * <p>Used by tests, by the plant AI and by any caller that has no profile
         * to filter with, so "no backpack" behaves exactly like it did before the
         * backpack existed - with the default backpack's bar size.
         */
        public static SeedContext all(LevelDef def) {
            return new SeedContext(SeedOptions.forLevel(def), SeedOptions.lockedSlotIds(def),
                    def.effectiveMaxSeedSlots(PvzceConstants.DEFAULT_SEED_SLOTS));
        }

        /** The pool a player with this backpack may actually pick from. */
        public static SeedContext forProfile(LevelDef def, com.pvzce.server.PlayerProfile profile) {
            java.util.function.Predicate<Identifier> owns =
                    profile == null ? null : profile::owns;
            int slots = def.effectiveMaxSeedSlots(profile == null
                    ? PvzceConstants.DEFAULT_SEED_SLOTS : profile.seedSlots());
            return new SeedContext(SeedOptions.forLevel(def, owns), SeedOptions.lockedSlotIds(def), slots);
        }
    }

    /** Runs an action with an ambient bridge and always restores the previous one. */
    private <T> T withBridge(ServerBridge bridge, java.util.function.Supplier<T> action) {
        ServerBridge previous = this.bridge;
        this.bridge = bridge;
        try {
            return action.get();
        } finally {
            this.bridge = previous;
        }
    }

    private void flushPending() {
        flushPending(bridge);
    }

    public void flushPending(ServerBridge bridge) {
        for (PvzceEntity entity : pendingAdd) {
            entities.add(entity);
            if (bridge != null) {
                bridge.send(entity.spawnPacket());
            }
        }
        pendingAdd.clear();
        if (bridge == null) {
            pendingRemove.clear();
            return;
        }
        for (PvzceEntity entity : pendingRemove) {
            entities.remove(entity);
            bridge.send(new EntityDespawnS2C(entity.id()));
        }
        pendingRemove.clear();
    }

    public void tick(ServerBridge bridge) {
        if (!gameState.equals(GameStateS2C.RUNNING)) {
            return;
        }
        ServerBridge previous = this.bridge;
        this.bridge = bridge;
        try {
            tickCount++;
            clock.tick();

            processMusicCues(bridge);
            tickCarry();
            // Before the entities move: a mechanic that changes the board (spawning,
            // removing, restricting) acts on the state the previous tick left behind
            // rather than on one that is halfway through moving.
            for (TypedMechanic typed : mechanics) {
                LevelMechanics.tick(typed, this);
            }
            tickWaves(bridge);
            syncWaveAndTime(bridge);
            maybeSpawnSun();
            tickScene();
            flushPending(bridge);

            tickEntities(PlantEntity.class, bridge);
            if (envVars.getBoolean(PvzceIds.ENV_PLANT_AI, false)) {
                plantAi.tick(this);
                flushPending(bridge);
            }
            tickEntities(ZombieEntity.class, bridge);
            tickEntities(ProjectileEntity.class, bridge);
            tickEntities(ResourceDropEntity.class, bridge);

            for (PvzceEntity entity : new ArrayList<>(entities)) {
                if (entity.isRemoved()) {
                    pendingRemove.add(entity);
                }
            }
            flushPending(bridge);

            syncSlots(bridge);
        } finally {
            this.bridge = previous;
        }
        checkEnd(bridge);
    }

    /** Ticks one entity class in a stable order and streams the results. */
    private <T extends PvzceEntity> void tickEntities(Class<T> type, ServerBridge bridge) {
        for (PvzceEntity entity : new ArrayList<>(entities)) {
            if (type.isInstance(entity)) {
                type.cast(entity).tick(this);
            }
        }
        flushPending(bridge);
    }

    private void syncSlots(ServerBridge bridge) {
        if (plantPlayer == null || cardSource == null) {
            return;
        }
        cardSource.tick(this, bridge, tickCount);
        if (entitySyncPending || tickCount % 3 == 0) {
            entitySyncPending = false;
            for (PvzceEntity entity : entities) {
                bridge.send(entity.updatePacket());
            }
        }
    }

    /** Where this level's cards come from; never null while a plant player exists. */
    public com.pvzce.server.level.cardsource.CardSource cardSource() {
        return cardSource;
    }

    /**
     * The board description a client is given, for the level list and for the level init.
     *
     * <p>One builder because both callers describe the same level: the list hands it to the
     * seed chooser's preview and the init builds the running board from it, and a level that
     * says "I have a belt and the left four columns are plantable" in one of them and not
     * the other would show a screen contradicting the one that follows it.
     */
    public static LevelPayload payloadFor(LevelDef def, SeedContext seeds) {
        // The resolved bar size, not the raw field: a level with no max_seed_slots of its
        // own is sized by the backpack, and the payload is where the client learns which.
        return new LevelPayload(def.width(), def.height(), seeds.pool(), seeds.maxSeedSlots(),
                def.previewZombieIds(), SceneCells.forLevel(def), seeds.lockedSlotIds(),
                LevelMechanics.payloads(def));
    }

    private void processMusicCues(ServerBridge bridge) {
        List<LevelDef.MusicCue> cues = def.music().cues().stream()
                .sorted(Comparator.comparingInt(LevelDef.MusicCue::atTick))
                .toList();
        while (nextMusicCueIndex < cues.size() && tickCount >= cues.get(nextMusicCueIndex).atTick()) {
            LevelDef.MusicCue cue = cues.get(nextMusicCueIndex++);
            bridge.send(new MusicEventS2C(
                    cue.track(),
                    cue.event().map(Identifier::toString).orElse(""),
                    cue.loop(),
                    cue.stop() || cue.event().isEmpty(),
                    Math.max(0F, Math.min(1F, cue.volume())),
                    Math.max(0F, cue.fadeSeconds())));
        }
    }

    /**
     * Puts an abandoned carry back where it came from.
     *
     * <p>Re-spawning it in place is enough: the plant never left its cell in the world's
     * eyes until the drop, so "put it back" is just "stop carrying it". Nothing is
     * removed, so nothing can be lost.
     */
    private void tickCarry() {
        if (carriedPlantId < 0) {
            return;
        }
        if (--carryTimeoutTicks <= 0 || plantById(carriedPlantId) == null) {
            clearCarry();
        }
    }

    /**
     * Whether a wave is still releasing the zombies it queued.
     *
     * <p>True while any queue still holds zombies: the wave is on the field but not yet
     * fully announced, and the next wave's countdown has to wait for it. See
     * {@link #tickWaves}.
     */
    private boolean waveStillReleasing() {
        for (PendingWaveSpawn queue : pendingWaveSpawns) {
            if (!queue.zombies.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void tickWaves(ServerBridge bridge) {
        if (nextWaveIndex < waves.size()) {
            // A wave's delay is the gap *between waves*, so it starts once the previous
            // wave has finished releasing, not the moment it was triggered. Counting
            // from the trigger let two waves' release queues run at once, and zombies
            // from different waves then arrived a few seconds apart instead of on the
            // pacing their own wave asked for - the "they all come out together" of a
            // level whose first waves are authored ten seconds apart.
            //
            // The counter is frozen rather than held at its target so the warning window
            // stays a property of the gap the author wrote: the warning shows for
            // `warning_ticks` before the wave arrives, and the arrival is this countdown
            // reaching `nextWaveDelayTicks`.
            boolean firstWave = nextWaveIndex == 0;
            if (firstWave || !waveStillReleasing()) {
                waveIntervalTicks = Math.min(waveIntervalTicks + 1, nextWaveDelayTicks);
            }
            // Due, and then held only if an opening wave is still on the field: the clock runs
            // while the player fights, so clearing the field early never costs the gap the
            // level asked for - it only ever costs the *arrival* of a wave that is already due.
            boolean due = waveIntervalTicks >= nextWaveDelayTicks;
            waveArrivalHeld = due && openingWaveStillOnTheField();
            if (due && !waveArrivalHeld) {
                triggerWave(waves.get(nextWaveIndex), nextWaveIndex);
            } else {
                updateWaveWarning();
            }
        } else {
            waveProgress = 1F;
            waveWarningActive = false;
            waveWarningFinal = false;
            waveArrivalHeld = false;
        }
        spawnPendingWaveZombies();
    }

    /**
     * Whether a due wave has to wait for an opening wave's zombies to be gone.
     *
     * <p>The other half of {@link com.pvzce.api.content.WaveDef#holdUntilDead(int)}: the
     * zombies of an opening wave come one at a time, and the wave after them does not walk
     * into the back of the last one. Only the *arrival* waits - the delay has already run,
     * so a player who clears the field before the wave is due sees exactly the pacing the
     * level asked for, and one who is still fighting gets the wave as soon as the field is
     * clear. The wait is capped like the per-zombie one: a player who is losing the opening
     * should meet the second wave late, not never.
     *
     * <p>The counter is the number of ticks already spent held, and the flag is cleared once
     * the wait is over (either way), so a level only ever pays for this once per opening wave.
     *
     * @return true while a due wave must be held back
     */
    private boolean openingWaveStillOnTheField() {
        if (!openingGateArmed) {
            return false;
        }
        if (aliveZombieCount() == 0 || openingGateTicks >= openingGateHoldTicks) {
            openingGateArmed = false;
            return false;
        }
        openingGateTicks++;
        return true;
    }

    private void triggerWave(WaveDef wave, int waveIndex) {
        nextWaveIndex++;
        waveIntervalTicks = 0;
        waveProgress = 0F;
        waveWarningActive = false;
        waveWarningFinal = false;
        waveArrivalHeld = false;
        waveDirty = true;

        int holdTicks = wave.holdUntilDead(waveIndex);
        // An opening wave hands its pacing to the player, and the wave after it waits for
        // the field to be clear. Recorded here, where the wave is known, because by the time
        // the wait matters the queue is gone.
        openingGateArmed = holdTicks > 0;
        openingGateTicks = 0;
        openingGateHoldTicks = holdTicks;

        List<Identifier> zombies = expandEntries(wave.entries());
        Collections.shuffle(zombies, random);
        pendingWaveSpawns.add(new PendingWaveSpawn(zombies, shuffledRows(), wave.spawnInterval(),
                holdTicks));

        int announcedIndex = waveIndex;
        boolean firstAnnouncement = announcedWaves.add(announcedIndex);
        // The huge-wave call belongs to the warning (see announceWaveWarning); a wave whose
        // data asks for no warning window would otherwise arrive in silence, so it is played
        // here instead. Never both: the warning marks the index as called out.
        if (firstAnnouncement && wave.isHuge() && !announcedWarnings.contains(announcedIndex)) {
            emitEffect("", width() / 2F, height() / 2F, PvzceSounds.AMBIENT_HUGE_WAVE, 1F, 1F);
        }
        if (firstAnnouncement && wave.type() == WaveDef.WaveType.FINAL) {
            emitEffect("", width() / 2F, height() / 2F, PvzceSounds.EFFECT_AWOOGA, 1F, 1F);
        }

        nextWaveDelayTicks = nextWaveIndex < waves.size() ? effectiveWaveDelay(nextWaveIndex) : -1;
    }

    private void updateWaveWarning() {
        if (nextWaveIndex >= waves.size()) {
            waveProgress = 1F;
            waveWarningActive = false;
            waveWarningFinal = false;
            return;
        }
        WaveDef next = waves.get(nextWaveIndex);
        int remaining = nextWaveDelayTicks - waveIntervalTicks;
        int warningTicks = Math.max(0, next.warningTicks());
        // The wave has to be counting down, not still releasing: ``tickWaves`` freezes the
        // counter while the previous wave's zombies are coming out, so ``remaining`` no
        // longer runs negative there - but the release window still sits *before* the
        // countdown starts, and a banner that went up during it would be announcing a wave
        // the player has already been told about.
        // ``nextWaveDelayTicks`` is -1 once the last wave has been released - that is the
        // "no more waves" sentinel, not a countdown of minus one. Testing it for
        // positivity is the whole fix: without it ``remaining`` was negative, the
        // second half of the range check is trivially true for a negative number, and the
        // banner stayed lit from the final wave until the level ended. That is the
        // "a huge wave is coming" that never stops.
        boolean countingDown = nextWaveDelayTicks > 0
                && remaining > 0 && remaining <= nextWaveDelayTicks;
        // A held arrival keeps its banner: the countdown has finished because the field is
        // still occupied, and a wave that is due is exactly what the banner is warning about.
        // Without this it went out at the due tick and the wave then arrived in silence, up to
        // the gate's cap later.
        boolean active = next.isHuge() && warningTicks > 0
                && (waveArrivalHeld || (countingDown && remaining <= warningTicks));
        boolean finalWarning = active && next.type() == WaveDef.WaveType.FINAL;
        if (active && !waveWarningActive) {
            announceWaveWarning(nextWaveIndex);
        }
        if (active != waveWarningActive || finalWarning != waveWarningFinal) {
            waveDirty = true;
        }
        waveWarningActive = active;
        waveWarningFinal = finalWarning;
        waveProgress = nextWaveDelayTicks <= 0
                ? 1F
                : Math.max(0F, Math.min(1F, waveIntervalTicks / (float) nextWaveDelayTicks));
    }

    /**
     * Calls out a huge wave as its warning opens, which is when the original does it.
     *
     * <p>The red "a huge wave is approaching" text and Dave's line of the same words are
     * one beat on screen. Playing the sound at the wave's <em>arrival</em> instead - which
     * is what this used to do - put it six seconds after the text had faded, so the
     * announcement was read and then heard about a wave the player had already met.
     *
     * <p>Once per wave index, like {@link #triggerWave}'s own announcements: a save
     * restored inside the warning window re-enters it, and a window is a range of ticks
     * rather than an event, so "first tick of the window" is the transition that fires it.
     */
    private void announceWaveWarning(int waveIndex) {
        if (announcedWarnings.add(waveIndex)) {
            emitEffect("", width() / 2F, height() / 2F, PvzceSounds.AMBIENT_HUGE_WAVE, 1F, 1F);
        }
    }

    private void spawnPendingWaveZombies() {
        if (pendingWaveSpawns.isEmpty()) {
            return;
        }
        Team zombieTeam = zombieTeam();
        Iterator<PendingWaveSpawn> iterator = pendingWaveSpawns.iterator();
        while (iterator.hasNext()) {
            PendingWaveSpawn queue = iterator.next();
            if (queue.zombies.isEmpty()) {
                iterator.remove();
                continue;
            }
            if (queue.ticksUntilNext > 0) {
                // A gated queue is waiting for the zombie it released: the next one is due the
                // moment that zombie dies. Either way the wait ends with the counter at zero,
                // and the spawn below happens on the following tick - the same one-tick shape
                // an interval has always had, so a cap of 1200 reads as "about twenty seconds"
                // exactly like an interval of 1200 would.
                boolean previousDied = queue.waitsForPrevious()
                        && queue.gateZombieId >= 0 && !zombieAlive(queue.gateZombieId);
                if (previousDied) {
                    queue.ticksUntilNext = 0;
                } else {
                    queue.ticksUntilNext--;
                    continue;
                }
            }
            Identifier zombieId = queue.zombies.poll();
            int row = queue.rows.get(queue.rowIndex++ % queue.rows.size());
            ZombieEntity spawned = spawnZombie(zombieId, zombieTeam, width() + 0.6F, row);
            queue.gateZombieId = queue.waitsForPrevious() && spawned != null ? spawned.id() : -1;
            queue.ticksUntilNext = queue.waitsForPrevious() ? queue.holdTicks : queue.intervalTicks;
            if (queue.zombies.isEmpty()) {
                iterator.remove();
            }
        }
        flushPending();
    }

    /**
     * True while the zombie with this id is still standing.
     *
     * <p>A corpse does not count: the death clip is what the player watches, and pacing the
     * next zombie on "the body has finished falling over" would add six seconds to every
     * opening wave for no reason.
     */
    private boolean zombieAlive(int id) {
        for (PvzceEntity entity : entities) {
            if (entity.id() == id && entity instanceof ZombieEntity zombie) {
                return zombie.isAlive();
            }
        }
        return false;
    }

    private static List<Identifier> expandEntries(List<WaveDef.Entry> entries) {
        List<Identifier> zombies = new ArrayList<>();
        for (WaveDef.Entry entry : entries) {
            int count = Math.max(0, entry.count());
            for (int i = 0; i < count; i++) {
                zombies.add(entry.id());
            }
        }
        return zombies;
    }

    private List<Integer> shuffledRows() {
        List<Integer> rows = new ArrayList<>();
        for (int y = 0; y < height(); y++) {
            rows.add(y);
        }
        Collections.shuffle(rows, random);
        return rows;
    }

    private Team zombieTeam() {
        Team zombieTeam = teams.get(PvzceIds.ZOMBIE_TEAM);
        if (zombieTeam == null) {
            zombieTeam = new Team(PvzceIds.ZOMBIE_TEAM, "僵尸方");
            teams.put(zombieTeam.id(), zombieTeam);
        }
        return zombieTeam;
    }

    public int currentWave() {
        return Math.min(nextWaveIndex, waves.size());
    }

    public int totalWaves() {
        return waves.size();
    }

    public boolean waveWarningActive() {
        return waveWarningActive;
    }

    public boolean waveWarningFinal() {
        return waveWarningFinal;
    }

    public boolean finalWaveActive() {
        return waveWarningActive && waveWarningFinal;
    }

    private void syncWaveAndTime(ServerBridge bridge) {
        if (waveDirty || tickCount % 10 == 0) {
            bridge.send(new WaveProgressS2C(currentWave(), totalWaves(), waveProgress,
                    waveWarningActive, waveWarningFinal));
            waveDirty = false;
        }
        if (tickCount % 60 == 0) {
            bridge.send(timeOfDayPacket());
        }
    }

    /**
     * One wave's zombies, trickling out at that wave's own pace.
     *
     * <p>The interval belongs to the wave rather than to the level: an easy level's early
     * waves should take ten seconds between zombies and its last wave three, which is a
     * property of the wave, not of the file.
     *
     * <p>{@code holdTicks} replaces that interval for a wave that paces itself by the
     * player's kills: the next zombie is due the moment the one this queue released dies,
     * and at the latest {@code holdTicks} after it was released. {@code gateZombieId} is
     * that zombie - the queue's own handle on it, not its position, because by the time the
     * answer matters it may already have been removed from the level.
     */
    private static final class PendingWaveSpawn {
        private final ArrayDeque<Identifier> zombies;
        private final List<Integer> rows;
        private final int intervalTicks;
        /** Ticks to wait for the previously released zombie, or 0 to use the interval. */
        private final int holdTicks;
        private int rowIndex;
        private int ticksUntilNext;
        /** The zombie this queue is waiting for, or -1 when it is not waiting for one. */
        private int gateZombieId = -1;

        private PendingWaveSpawn(List<Identifier> zombies, List<Integer> rows, int intervalTicks,
                                 int holdTicks) {
            this.zombies = new ArrayDeque<>(zombies);
            this.rows = rows;
            this.intervalTicks = Math.max(1, intervalTicks);
            this.holdTicks = Math.max(0, holdTicks);
        }

        /** True when this queue waits for the zombie it just released. */
        private boolean waitsForPrevious() {
            return holdTicks > 0;
        }
    }

    private void tickScene() {
        int recovery = rules.getInt(PvzceIds.RULE_CRATER_RECOVERY);
        for (int x = 0; x < width(); x++) {
            for (int y = 0; y < height(); y++) {
                SceneElementDef element = scene.get(x, y);
                if (element == null) {
                    continue;
                }
                if (PvzceIds.SURFACE_CRATER.equals(element.surfaceClass())) {
                    int key = y * width() + x;
                    int ticks = craterTimers.merge(key, 1, Integer::sum);
                    if (ticks >= recovery) {
                        craterTimers.remove(key);
                        setScene(x, y, PvzceIds.GRASS);
                        sendSceneCell(x, y);
                    }
                } else if (PvzceIds.SURFACE_GRAVE.equals(element.surfaceClass())
                        && clock.isNight(rules)
                        && rules.getBoolean(PvzceIds.RULE_GRAVES_SPAWN_NIGHT)
                        && random.nextInt(900) == 0) {
                    spawnZombie(PvzceIds.id("basic_zombie"), zombieTeam(), x + 0.5F, y);
                }
            }
        }
    }

    private void maybeSpawnSun() {
        if (random.nextFloat() >= rules.getFloat(PvzceIds.RULE_SUN_SPAWN_CHANCE)) {
            return;
        }
        ResourceDef sun = BuiltInRegistries.RESOURCES.get(PvzceIds.SUN);
        Team plantTeam = teams.get(PvzceIds.PLANT_TEAM);
        if (sun == null || plantTeam == null) {
            return;
        }
        addEntity(new ResourceDropEntity(sun, plantTeam, random.nextInt(width()), random.nextInt(height()),
                rules.getInt(PvzceIds.RULE_SUN_VALUE)));
    }

    private void checkEnd(ServerBridge bridge) {
        if (gameState.equals(GameStateS2C.RUNNING)
                && !waves.isEmpty()
                && nextWaveIndex >= waves.size()
                && pendingWaveSpawns.isEmpty()
                && aliveZombieCount() == 0) {
            markEnd(teams.get(PvzceIds.PLANT_TEAM));
        }

        if (!gameState.equals(GameStateS2C.RUNNING) && !gameEndPacketSent) {
            gameEndPacketSent = true;
            Team winnerTeam = teams.get(winner);
            System.out.println("[PVZCE] Game over, winner=" + winner);
            bridge.send(new GameStateS2C(gameState, winner != null ? winner.toString() : ""));
            if (winnerTeam != null) {
                bridge.send(new ServerMessageS2C(winnerTeam.name() + " 获胜！"));
            }
        }
    }

    @Override
    public void zombieReachedLeft(ZombieEntity zombie) {
        markEnd(teams.get(PvzceIds.ZOMBIE_TEAM));
    }

    /**
     * Rolls the level's coin drop for a dead zombie.
     *
     * <p>Coins land where the zombie fell so the player can click them like sun;
     * the amount and the chance come from {@code rewards} in the level data, and a
     * level that declares neither drops nothing. The roll is skipped once the game
     * is over, so the last zombie of a won wave cannot shower the player with
     * coins that the end-of-level payout has already read.
     */
    @Override
    public void zombieDied(ZombieEntity zombie) {
        if (!gameState.equals(GameStateS2C.RUNNING)) {
            return;
        }
        // The death animation has to reach the client even if the level ends this tick;
        // see entitySyncPending.
        entitySyncPending = true;
        // Recorded before the drop roll: a zombie that drops nothing still died here, and
        // this is where the level's reward will land.
        lastKillX = zombie.cellX();
        lastKillY = zombie.cellY();
        LevelRewards rewards = def.rewards();
        if (!rewards.hasCoinDrops() || random.nextFloat() >= rewards.coinDropChance()) {
            return;
        }
        Team plantTeam = teams.get(PvzceIds.PLANT_TEAM);
        if (plantTeam == null) {
            return;
        }
        // The drop's amount is the denomination's own worth, so what the team collects
        // already reads in coins and the bank does not have to know the ladder.
        ResourceDef drop = BuiltInRegistries.RESOURCES.get(rewards.coinDrop());
        int worth = drop == null
                ? rewards.coinDropAmount()
                : drop.defaultValue() * rewards.coinDropAmount();
        spawnResource(drop == null ? rewards.coinDrop() : drop.id(), worth,
                zombie.cellX(), zombie.cellY(), plantTeam);
    }

    @Override
    public void dropCoin(float x, float y, int count) {
        if (count <= 0 || !gameState.equals(GameStateS2C.RUNNING)) {
            return;
        }
        Team plantTeam = teams.get(PvzceIds.PLANT_TEAM);
        LevelRewards rewards = def.rewards();
        ResourceDef drop = BuiltInRegistries.RESOURCES.get(rewards.coinDrop());
        if (plantTeam == null || drop == null) {
            return;
        }
        // One entity per coin, scattered inside the cell: the original pays combos in
        // individual coins, and a stack of them reads as "that ricochet was worth more"
        // in a way a single bigger number does not.
        int worth = Math.max(1, drop.defaultValue() * Math.max(1, rewards.coinDropAmount()));
        for (int i = 0; i < count; i++) {
            float dx = x + (random.nextFloat() - 0.5F) * 0.5F;
            float dy = y + (random.nextFloat() - 0.5F) * 0.35F;
            spawnResource(drop.id(), worth, dx, dy, plantTeam);
        }
    }

    private void markEnd(Team winnerTeam) {
        if (!gameState.equals(GameStateS2C.RUNNING) || winnerTeam == null) {
            return;
        }
        gameState = GameStateS2C.WON;
        winner = winnerTeam.id();
        // The level stops ticking, so anything that only gets cleared by a tick has to be
        // cleared here. The wave warning was the one that mattered: a win that lands
        // *during* the final warning left the banner lit on the client for good, because
        // the wave that would have turned it off is the one whose zombies just died.
        waveWarningActive = false;
        waveWarningFinal = false;
        waveProgress = 1F;
        waveDirty = true;
    }

    // ------------------------------------------------------------------
    // Player actions
    // ------------------------------------------------------------------

    public boolean placePlant(ServerBridge bridge, int slotIndex, int x, int y) {
        return withBridge(bridge, () -> placePlantInternal(bridge, slotIndex, x, y));
    }

    private boolean placePlantInternal(ServerBridge bridge, int slotIndex, int x, int y) {
        if (!gameState.equals(GameStateS2C.RUNNING)) {
            bridge.send(new ServerMessageS2C("游戏已经结束。"));
            return false;
        }
        if (!humanTeamId.equals(PvzceIds.PLANT_TEAM)) {
            bridge.send(new ServerMessageS2C("当前控制的是僵尸方，僵尸方由 AI 指挥。"));
            return false;
        }
        if (plantPlayer == null) {
            return false;
        }
        Slot slot = plantPlayer.slot(slotIndex);
        if (slot == null || slot.kind() != Slot.Kind.PLANT) {
            bridge.send(new ServerMessageS2C("无效的卡槽。"));
            return false;
        }
        if (!slot.ready()) {
            bridge.send(new ServerMessageS2C("卡片冷却中。"));
            return false;
        }
        if (!inBounds(x, y)) {
            bridge.send(new ServerMessageS2C("不能在草坪外种植。"));
            return false;
        }
        PlantDef plantDef = BuiltInRegistries.PLANTS.get(slot.defId());
        if (plantDef == null) {
            bridge.send(new ServerMessageS2C("未知植物 " + slot.defId()));
            return false;
        }
        if (!canPlacePlant(plantDef, x, y)) {
            bridge.send(new ServerMessageS2C("该格不能种植。"));
            return false;
        }
        // What a card costs - sun and a cooldown, or nothing at all because the level
        // handed it over - is the card source's business. This method only knows that a
        // card was asked for and that a plant has to appear if the payment went through.
        if (cardSource == null || !cardSource.spend(this, bridge, slot, plantDef)) {
            return false;
        }
        PlantEntity plant = spawnPlant(plantDef, plantPlayer.team(), x, y);
        System.out.println("[PVZCE] Planted " + slot.defId() + " at (" + x + "," + y + ") count=" + plantCount());
        cardSource.afterSpend(this, bridge, slot);
        return !plant.isRemoved() || plant.consumesOnPlace();
    }

    public boolean collectResource(ServerBridge bridge, int entityId) {
        return withBridge(bridge, () -> collectResourceInternal(bridge, entityId));
    }

    private boolean collectResourceInternal(ServerBridge bridge, int entityId) {
        if (!gameState.equals(GameStateS2C.RUNNING) || !humanTeamId.equals(PvzceIds.PLANT_TEAM) || plantPlayer == null) {
            return false;
        }
        for (PvzceEntity entity : entities) {
            if (entity.id() != entityId || !(entity instanceof ResourceDropEntity drop) || drop.isRemoved()) {
                continue;
            }
            if (!drop.def().collectible()) {
                return false;
            }
            if (!drop.def().collectibleWithoutCard()) {
                if (!plantPlayer.team().canCollect(drop.defId())) {
                    bridge.send(new ServerMessageS2C("该资源在本关未解锁。"));
                    return false;
                }
                if (!plantPlayer.hasResourceCard(drop.defId())) {
                    bridge.send(new ServerMessageS2C("没有对应资源卡，无法收集。"));
                    return false;
                }
            }
            drop.markCollected();
            plantPlayer.team().addResource(drop.defId(), drop.amount());
            // The sparkle belongs to the resource, not to "collecting": a sun is the thing the
            // whole game is played with and gets a flash to match, while a coin is small change
            // and would wash the lawn in light. A resource that names no effect gets none -
            // the sound and the fly-to-bank animation are the feedback. The sound is per
            // resource for the same reason: currency rings, the sun chimes.
            emitEffect(drop.def().pickupEffect().map(Identifier::toString).orElse(""),
                    drop.cellX(), drop.cellY(), drop.def().pickupSound());
            // The visual fly-to-bank animation is client-side only, but it still
            // needs the server-confirmed drop position and icon.
            bridge.send(new ResourceCollectS2C(drop.id(), drop.defId().toString(), drop.amount(),
                    drop.cellX(), drop.cellY(), drop.height(),
                    drop.def().icon() == null ? "" : drop.def().icon().toString()));
            bridge.send(new EntityDespawnS2C(drop.id()));
            bridge.send(new ResourceDeltaS2C(plantPlayer.team().id().toString(), drop.defId().toString(),
                    plantPlayer.team().resourcesOf(drop.defId())));
            String label = drop.defId().path().equals("sun") ? "阳光" : drop.defId().toString();
            bridge.send(new ServerMessageS2C("+" + drop.amount() + " " + label));
            return true;
        }
        return false;
    }

    public boolean useTool(ServerBridge bridge, int slotIndex, int x, int y) {
        return withBridge(bridge, () -> useToolInternal(bridge, slotIndex, x, y));
    }

    private boolean useToolInternal(ServerBridge bridge, int slotIndex, int x, int y) {
        if (!gameState.equals(GameStateS2C.RUNNING)) {
            return false;
        }
        if (!humanTeamId.equals(PvzceIds.PLANT_TEAM)) {
            bridge.send(new ServerMessageS2C("僵尸方没有工具卡。"));
            return false;
        }
        if (plantPlayer == null) {
            return false;
        }
        Slot slot = plantPlayer.slot(slotIndex);
        if (slot == null || slot.kind() != Slot.Kind.TOOL) {
            bridge.send(new ServerMessageS2C("不是工具卡。"));
            return false;
        }
        // A glove that is already holding something ignores its own cooldown: the second
        // click is the other half of the move the first one started, and its cooldown
        // began then. Charging a cooldown for the drop as well would also consume two
        // uses for one move.
        boolean finishingMove = carriedPlantId >= 0 && isGlove(slot);
        if (!slot.ready() && !finishingMove) {
            bridge.send(new ServerMessageS2C("工具冷却中。"));
            return false;
        }
        ToolDef tool = BuiltInRegistries.TOOLS.get(slot.defId());
        if (tool == null) {
            bridge.send(new ServerMessageS2C("未知工具 " + slot.defId()));
            return false;
        }
        if (!inBounds(x, y)) {
            return false;
        }
        if (!applyToolEffect(tool, x, y)) {
            // The click was understood and refused (an empty cell, a plant in the way).
            // Spending a use on it would cost the player a charge for nothing, and would
            // leave the glove unusable for the case that comes next.
            bridge.send(new SlotSyncS2C(toSlotInfo(slot)));
            return false;
        }
        if (!finishingMove) {
            slot.startCooldown(tool.cooldownTicks());
            slot.consumeUse();
        }
        bridge.send(new SlotSyncS2C(toSlotInfo(slot)));
        return true;
    }

    /**
     * Applies a tool's effect to a cell.
     *
     * @return false when the click was refused, so the caller can leave the card ready
     *         instead of charging a cooldown and a use for nothing
     */
    private boolean applyToolEffect(ToolDef tool, int x, int y) {
        return switch (tool.effect()) {
            // PVZ original: one shovel click removes exactly one plant, always the
            // topmost layer of the target cell.
            case "pvzce:shovel" -> {
                PlantEntity plant = plantAt(x, y);
                if (plant != null) {
                    plant.remove();
                    flushPending();
                    emitEffect(PvzceParticles.DIRT_SMALL.toString(), x + 0.5F, y + 0.5F, PvzceSounds.EFFECT_SHOVEL);
                }
                yield true;
            }
            case "pvzce:glove" -> movePlant(x, y);
            case "pvzce:hammer" -> {
                for (ZombieEntity zombie : zombiesInRow(y)) {
                    if (zombie.isAlive() && Math.abs(zombie.cellX() - (x + 0.5F)) < 0.8F) {
                        zombie.damage(HAMMER_TOOL_DAMAGE, ZombieEntity.damageType(PvzceIds.DAMAGE_MOWER), this);
                        emitEffect(PvzceParticles.HIT_SPARK.toString(), zombie.cellX(), zombie.cellY(), PvzceSounds.EFFECT_BONK);
                    }
                }
                yield true;
            }
            // An effect this build does not implement: refused, so no use is spent.
            default -> false;
        };
    }

    /**
     * One click of the glove: lift, or drop.
     *
     * <p>Two clicks, as the original does - the first lifts the plant off its cell and the
     * second puts it down. A carry that is never finished times out and the plant goes
     * back where it came from, so a mis-click cannot delete it.
     *
     * <p>The plant is re-spawned rather than teleported: {@code spawnPlant} is the one
     * path that applies the carrier offset, the stacking height and the {@code onPlaced}
     * hook, and its state is carried across so a moved lily pad is still a carrier and a
     * moved potato mine does not re-arm.
     */
    private boolean movePlant(int x, int y) {
        if (carriedPlantId >= 0) {
            PlantEntity carried = plantById(carriedPlantId);
            if (carried == null || carried.isRemoved()) {
                clearCarry();
                return false;
            }
            PlantDef def = carried.def();
            if (!canPlacePlant(def, x, y)) {
                bridge.send(new ServerMessageS2C("不能放在这里。"));
                return false;
            }
            // Captured before the old entity goes away: ``saveState`` reads the live
            // object, and a removed one no longer has anything to read.
            CompoundTag state = carried.saveState();
            Team team = carried.team();
            carried.remove();
            flushPending();
            PlantEntity moved = spawnPlant(def, team, x, y);
            if (moved != null && !moved.isRemoved()) {
                moved.restoreStateWithoutPosition(state);
            }
            clearCarry();
            emitEffect(PvzceParticles.DIRT_SMALL.toString(), x + 0.5F, y + 0.5F, PvzceSounds.PLANT_PLANT);
            return true;
        }
        PlantEntity plant = plantAt(x, y);
        if (plant == null) {
            // The original treats a click on grass as picking up nothing at all: the glove
            // stays in hand, ready for the cell the player meant. Not an error, and not a
            // use spent either.
            bridge.send(new ServerMessageS2C("这一格没有植物。"));
            return false;
        }
        carriedPlantId = plant.id();
        carryTimeoutTicks = CARRY_TIMEOUT_TICKS;
        emitEffect(PvzceParticles.LANTERN_SHINE.toString(), x + 0.5F, y + 0.5F, PvzceSounds.UI_TAP);
        bridge.send(new ServerMessageS2C("已拿起 " + plant.def().id() + "，再点一次放下。"));
        return true;
    }

    /** True when this slot is the glove, by its tool effect rather than its id. */
    private static boolean isGlove(Slot slot) {
        ToolDef tool = BuiltInRegistries.TOOLS.get(slot.defId());
        return tool != null && "pvzce:glove".equals(tool.effect());
    }

    private void clearCarry() {
        carriedPlantId = -1;
        carryTimeoutTicks = 0;
    }

    /** The plant with this entity id, or {@code null}; the glove only ever moves plants. */
    private PlantEntity plantById(int id) {
        for (PvzceEntity entity : entities) {
            if (entity.id() == id && entity instanceof PlantEntity plant) {
                return plant;
            }
        }
        return null;
    }

    public SlotInfo toSlotInfo(Slot slot) {
        // A belt card has no price at all, which is not the same as costing zero: the HUD
        // draws the number it is given, and "0" would read as "this cost something and you
        // paid it". SlotInfo.NO_PRICE is that distinction on the wire.
        int price = cardSource != null && cardSource.dealsItsOwnCards()
                ? SlotInfo.NO_PRICE : slot.costSun();
        boolean available = slot.kind() == Slot.Kind.RESOURCE
                || (slot.ready() && plantPlayer != null
                        && plantPlayer.team().resourcesOf(PvzceIds.SUN) >= Math.max(0, price));
        return new SlotInfo(slot.index(), slot.defId().toString(), slot.kind().json(), price,
                slot.cooldownLeft(), slot.usesLeft(), available);
    }

    public List<SlotInfo> slotInfos() {
        List<SlotInfo> result = new ArrayList<>();
        if (plantPlayer != null) {
            for (Slot slot : plantPlayer.slots()) {
                result.add(toSlotInfo(slot));
            }
        }
        return result;
    }

    public void sendFullState(ServerBridge bridge) {
        Team plantTeam = plantPlayer != null ? plantPlayer.team() : null;
        List<String> waveTypes = waves.stream()
                .map(wave -> wave.type().name().toLowerCase(Locale.ROOT))
                .toList();
        LevelPayload payload = payloadFor(def, seedContext);
        bridge.send(new LevelInitS2C(def.id().toString(), slotInfos(), waveTypes, payload,
                humanTeamId.toString(), teamName(humanTeamId), PvzcePackets.PROTOCOL_VERSION));
        withBridge(bridge, () -> {
            emitEffect("", width() / 2F, height() / 2F, PvzceSounds.AMBIENT_READY_SET_PLANT, 1F, 1F);
            return null;
        });
        List<SceneSyncS2C.Cell> cells = new ArrayList<>();
        for (int x = 0; x < width(); x++) {
            for (int y = 0; y < height(); y++) {
                SceneElementDef element = scene.get(x, y);
                if (element != null) {
                    cells.add(new SceneSyncS2C.Cell(x, y, element.id().toString()));
                }
            }
        }
        bridge.send(new SceneSyncS2C(cells));
        bridge.send(waveProgressPacket());
        bridge.send(timeOfDayPacket());
        if (plantTeam != null) {
            for (Identifier resource : plantTeam.resourceIds()) {
                bridge.send(new ResourceDeltaS2C(plantTeam.id().toString(), resource.toString(),
                        plantTeam.resourcesOf(resource)));
            }
        }
        for (PvzceEntity entity : entities) {
            bridge.send(entity.spawnPacket());
        }
        if (!gameState.equals(GameStateS2C.RUNNING)) {
            bridge.send(new GameStateS2C(gameState, winner != null ? winner.toString() : ""));
        }
    }

    private String teamName(Identifier teamId) {
        Team team = teams.get(teamId);
        return team == null ? "" : team.name();
    }

    public WaveProgressS2C waveProgressPacket() {
        return new WaveProgressS2C(currentWave(), totalWaves(), waveProgress, waveWarningActive, waveWarningFinal);
    }

    /**
     * Detaches this level instance before the server replaces it. Unlike
     * {@code save()}, this does not write state; it only makes sure the old
     * instance can no longer emit packets or be ticked if a stray reference
     * survives the restart.
     */
    public void shutdown() {
        bridge = null;
        gameState = "closed";
        pendingAdd.clear();
        pendingRemove.clear();
        entities.clear();
        pendingWaveSpawns.clear();
        craterTimers.clear();
    }

    // ------------------------------------------------------------------
    // Save / restore
    // ------------------------------------------------------------------

    private static final String KEY_ENTITIES = "Entities";
    private static final String KEY_KIND = "Kind";

    public CompoundTag save() {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", PvzceConstants.SAVE_DATA_VERSION);
        root.putString("LevelId", def.id().toString());
        root.putString("GameState", gameState);
        if (winner != null) {
            root.putString("Winner", winner.toString());
        }
        root.putInt("Tick", tickCount);
        root.putInt("NextWaveIndex", nextWaveIndex);
        root.putInt("WaveIntervalTicks", waveIntervalTicks);
        root.putLong("DayTicks", clock.dayTicks());
        root.putInt("NextMusicCueIndex", nextMusicCueIndex);
        root.putInt("OpeningGateTicks", openingGateTicks);
        root.putInt("OpeningGateHoldTicks", openingGateHoldTicks);
        if (openingGateArmed) {
            root.putByte("OpeningGateArmed", (byte) 1);
        }
        if (waveArrivalHeld) {
            root.putByte("WaveArrivalHeld", (byte) 1);
        }

        ListTag pendingWaves = new ListTag();
        for (PendingWaveSpawn queue : pendingWaveSpawns) {
            CompoundTag queueTag = new CompoundTag();
            ListTag zombies = new ListTag();
            for (Identifier zombieId : queue.zombies) {
                zombies.add(new StringTag(zombieId.toString()));
            }
            queueTag.put("Zombies", zombies);
            ListTag rows = new ListTag();
            for (int row : queue.rows) {
                rows.add(new IntTag(row));
            }
            queueTag.put("Rows", rows);
            queueTag.putInt("IntervalTicks", queue.intervalTicks);
            queueTag.putInt("RowIndex", queue.rowIndex);
            queueTag.putInt("TicksUntilNext", queue.ticksUntilNext);
            queueTag.putInt("HoldTicks", queue.holdTicks);
            // The zombie the gate is waiting for is deliberately not written: entity ids come
            // from a process-wide counter, so a restored id may name a different zombie (or
            // none at all). A resumed queue waits out its cap instead of trusting a number
            // that means nothing in this process.
            pendingWaves.add(queueTag);
        }
        root.put("PendingWaveSpawns", pendingWaves);

        // Teams, cards, resources and every entity live in one tag; the server no
        // longer writes a second copy of the same data or a per-team side file.
        root.put("Teams", saveTeams());
        root.put("Slots", saveSlots());
        root.put("Scene", saveScene());
        if (cardSource != null) {
            cardSource.save(root);
        }
        // A mechanic with state of its own - anything that is not the card bar - writes it
        // through its own hook, so the save file grows with the mechanic rather than here.
        for (TypedMechanic typed : mechanics) {
            LevelMechanics.collectSave(typed, this, root);
        }

        ListTag savedEntities = new ListTag();
        for (PvzceEntity entity : entities) {
            if (entity.isRemoved()) {
                continue;
            }
            CompoundTag entityTag = entity.saveState();
            entityTag.putString(KEY_KIND, entity.entityKind());
            savedEntities.add(entityTag);
        }
        root.put(KEY_ENTITIES, savedEntities);

        Team plantTeam = plantPlayer != null ? plantPlayer.team() : null;
        root.putInt("Sun", plantTeam == null ? 0 : plantTeam.resourcesOf(PvzceIds.SUN));
        root.putInt("PlantCount", plantCount());
        return root;
    }

    private CompoundTag saveTeams() {
        CompoundTag teamsTag = new CompoundTag();
        for (Map.Entry<Identifier, Team> entry : teams.entrySet()) {
            Team team = entry.getValue();
            CompoundTag teamTag = new CompoundTag();
            CompoundTag resources = new CompoundTag();
            team.resources().forEach((resource, amount) -> resources.putInt(resource.toString(), amount));
            teamTag.put("Resources", resources);
            ListTag unlocked = new ListTag();
            for (Identifier resource : team.unlockedResources()) {
                unlocked.add(new StringTag(resource.toString()));
            }
            teamTag.put("UnlockedResources", unlocked);
            teamsTag.put(entry.getKey().toString(), teamTag);
        }
        return teamsTag;
    }

    private ListTag saveSlots() {
        ListTag slots = new ListTag();
        if (plantPlayer == null) {
            return slots;
        }
        for (Slot slot : plantPlayer.slots()) {
            CompoundTag slotTag = new CompoundTag();
            slotTag.putInt("index", slot.index());
            slotTag.putString("def", slot.defId().toString());
            slotTag.putString("kind", slot.kind().name());
            slotTag.putInt("cost", slot.costSun());
            slotTag.putInt("cooldown", slot.cooldownLeft());
            slotTag.putInt("usesLeft", slot.usesLeft());
            slots.add(slotTag);
        }
        return slots;
    }

    private ListTag saveScene() {
        ListTag cells = new ListTag();
        for (int x = 0; x < width(); x++) {
            for (int y = 0; y < height(); y++) {
                SceneElementDef element = scene.get(x, y);
                if (element == null) {
                    continue;
                }
                CompoundTag cellTag = new CompoundTag();
                cellTag.putInt("x", x);
                cellTag.putInt("y", y);
                cellTag.putString("element", element.id().toString());
                cells.add(cellTag);
            }
        }
        return cells;
    }

    /**
     * Restores a running save: tick/time, wave meter, teams, cards, scene and
     * every entity snapshot. Only saves with {@code GameState=running} are
     * restored; finished games restart fresh.
     */
    public void restore(CompoundTag root) {
        if (!root.contains("GameState") || !GameStateS2C.RUNNING.equals(root.getString("GameState"))) {
            return;
        }
        clearEntitiesForRestore();
        tickCount = Math.max(0, root.getInt("Tick"));
        clock.setDayTicks(root.getLong("DayTicks"));
        nextWaveIndex = Math.max(0, Math.min(waves.size(), root.getInt("NextWaveIndex")));
        // Everything below the index has already walked in, so it has already announced
        // itself. Without this, resuming a save taken after the last wave started made
        // ``triggerWave`` see an empty ``announcedWaves`` and play the siren and the
        // huge-wave call a second time - the sound the player reported as looping, since it
        // lands while that wave's zombies are still coming in.
        announcedWaves.clear();
        for (int i = 0; i < nextWaveIndex; i++) {
            announcedWaves.add(i);
        }
        // The warning call-out is per wave too, but a wave below the index may still be
        // *inside* its warning window when the save was taken (the window ends when the
        // wave arrives, and arriving is what advances the index). Seeding it is therefore
        // wrong in the one case that matters - a save taken during the final warning would
        // resume in silence - and the client already refuses to repeat either announcement
        // within a level instance, so a resumed window calls out at most once more.
        announcedWarnings.clear();
        waveIntervalTicks = Math.max(0, root.getInt("WaveIntervalTicks"));
        openingGateArmed = root.getInt("OpeningGateArmed") != 0;
        waveArrivalHeld = root.getInt("WaveArrivalHeld") != 0;
        openingGateTicks = Math.max(0, root.getInt("OpeningGateTicks"));
        openingGateHoldTicks = Math.max(0, root.getInt("OpeningGateHoldTicks"));
        nextWaveDelayTicks = nextWaveIndex < waves.size() ? effectiveWaveDelay(nextWaveIndex) : -1;
        nextMusicCueIndex = Math.max(0, root.getInt("NextMusicCueIndex"));
        restoreScene(root.getList("Scene"));
        restoreTeams(root.getCompound("Teams"));
        // Before restoreSlots: a self-dealt bar is a projection of the source, so the source
        // has to be restored first or its cards would have nothing to be put back into.
        if (cardSource != null) {
            cardSource.restore(this, root);
        }
        for (TypedMechanic typed : mechanics) {
            LevelMechanics.applySave(typed, this, root);
        }
        restoreSlots(root.getList("Slots"));
        restorePendingWaves(root.getList("PendingWaveSpawns"));
        restoreEntities(root.getList(KEY_ENTITIES));
        updateWaveWarning();
        flushPending(null);
    }

    private void restoreScene(ListTag cells) {
        for (Tag element : cells.values()) {
            if (!(element instanceof CompoundTag cellTag)) {
                continue;
            }
            Identifier elementId = Identifier.tryParse(cellTag.getString("element"));
            SceneElementDef sceneElement = elementId == null ? null : BuiltInRegistries.SCENE_ELEMENTS.get(elementId);
            if (sceneElement != null) {
                scene.set(cellTag.getInt("x"), cellTag.getInt("y"), sceneElement);
            }
        }
    }

    private void restoreTeams(CompoundTag teamsTag) {
        for (Map.Entry<String, Tag> entry : teamsTag.entries().entrySet()) {
            Identifier teamId = Identifier.tryParse(entry.getKey());
            Team team = teamId == null ? null : teams.get(teamId);
            if (team == null || !(entry.getValue() instanceof CompoundTag teamTag)) {
                continue;
            }
            CompoundTag resources = teamTag.getCompound("Resources");
            for (Map.Entry<String, Tag> resource : resources.entries().entrySet()) {
                Identifier resourceId = Identifier.tryParse(resource.getKey());
                if (resourceId != null && resource.getValue() instanceof IntTag amount) {
                    team.putResource(resourceId, amount.value());
                }
            }
            for (Tag unlocked : teamTag.getList("UnlockedResources").values()) {
                if (unlocked instanceof StringTag stringTag) {
                    Identifier resourceId = Identifier.tryParse(stringTag.value());
                    if (resourceId != null) {
                        team.unlockResource(resourceId);
                    }
                }
            }
        }
    }

    private void restoreSlots(ListTag slots) {
        if (plantPlayer == null) {
            return;
        }
        for (Tag element : slots.values()) {
            if (!(element instanceof CompoundTag slotTag)) {
                continue;
            }
            Slot slot = plantPlayer.slot(slotTag.getInt("index"));
            if (slot != null) {
                slot.startCooldown(slotTag.getInt("cooldown"));
                slot.restoreUses(slotTag.getInt("usesLeft"));
            }
        }
    }

    private void restorePendingWaves(ListTag pending) {
        pendingWaveSpawns.clear();
        for (Tag element : pending.values()) {
            if (!(element instanceof CompoundTag queueTag)) {
                continue;
            }
            List<Identifier> zombieIds = new ArrayList<>();
            for (Tag tag : queueTag.getList("Zombies").values()) {
                if (tag instanceof StringTag stringTag) {
                    Identifier zombieId = Identifier.tryParse(stringTag.value());
                    if (zombieId != null) {
                        zombieIds.add(zombieId);
                    }
                }
            }
            List<Integer> rows = new ArrayList<>();
            for (Tag tag : queueTag.getList("Rows").values()) {
                if (tag instanceof IntTag intTag) {
                    rows.add(intTag.value());
                }
            }
            if (rows.isEmpty()) {
                continue;
            }
            // The interval is saved with the queue: a save taken mid-release has to
            // keep trickling at the wave's own pace, not at today's default. Same for the
            // death gate's cap - but not the zombie it was waiting for, whose id does not
            // survive a process (see the save side), so a resumed queue paces itself by the
            // cap until it releases a zombie it can follow again.
            PendingWaveSpawn queue = new PendingWaveSpawn(zombieIds, rows,
                    queueTag.getInt("IntervalTicks") > 0
                            ? queueTag.getInt("IntervalTicks")
                            : WaveDef.DEFAULT_SPAWN_INTERVAL_TICKS,
                    Math.max(0, queueTag.getInt("HoldTicks")));
            queue.rowIndex = Math.max(0, queueTag.getInt("RowIndex"));
            queue.ticksUntilNext = Math.max(0, queueTag.getInt("TicksUntilNext"));
            pendingWaveSpawns.add(queue);
        }
    }

    private void restoreEntities(ListTag saved) {
        for (Tag element : saved.values()) {
            if (!(element instanceof CompoundTag entityTag)) {
                continue;
            }
            PvzceEntity entity = createEntity(entityTag);
            if (entity == null) {
                continue;
            }
            entity.restoreState(entityTag);
            if (entity.health() > 0 && !entity.isRemoved()) {
                addEntity(entity);
            }
        }
    }

    /** Rebuilds an entity shell from its saved kind + definition id; the state follows. */
    private PvzceEntity createEntity(CompoundTag tag) {
        Identifier defId = Identifier.tryParse(tag.getString("id"));
        if (defId == null) {
            return null;
        }
        String kind = tag.getString(KEY_KIND);
        if (kind.isEmpty()) {
            // Legacy saves had separate plants/zombies lists, so the kind came from
            // which list the entry was in; keep accepting the id's registry instead.
            if (BuiltInRegistries.PLANTS.containsKey(defId)) {
                kind = com.pvzce.api.entity.EntityKind.PLANT;
            } else if (BuiltInRegistries.ZOMBIES.containsKey(defId)) {
                kind = com.pvzce.api.entity.EntityKind.ZOMBIE;
            }
        }
        return switch (kind) {
            case com.pvzce.api.entity.EntityKind.PLANT -> {
                PlantDef def = BuiltInRegistries.PLANTS.get(defId);
                yield def == null ? null : new PlantEntity(def, teams.get(PvzceIds.PLANT_TEAM),
                        (int) tag.getFloat("x"), (int) tag.getFloat("y"));
            }
            case com.pvzce.api.entity.EntityKind.ZOMBIE -> {
                ZombieDef def = BuiltInRegistries.ZOMBIES.get(defId);
                yield def == null ? null : new ZombieEntity(def, zombieTeam(), tag.getFloat("x"),
                        (int) Math.floor(tag.getFloat("y")));
            }
            case com.pvzce.api.entity.EntityKind.PROJECTILE -> {
                ProjectileDef def = BuiltInRegistries.PROJECTILES.get(defId);
                yield def == null ? null : new ProjectileEntity(def, null, teams.get(PvzceIds.PLANT_TEAM),
                        tag.getFloat("x"), tag.getFloat("y"), tag.getFloat("height"));
            }
            default -> null;
        };
    }

    /** Removes the default initial entities before a saved field snapshot is applied. */
    private void clearEntitiesForRestore() {
        entities.clear();
        pendingAdd.clear();
        pendingRemove.clear();
    }

}
