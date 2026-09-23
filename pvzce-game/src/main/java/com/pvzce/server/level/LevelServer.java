package com.pvzce.server.level;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.LevelRewards;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.ProjectileDef;
import com.pvzce.api.content.ProjectileRef;
import com.pvzce.api.content.ResourceDef;
import com.pvzce.api.content.SceneElementDef;
import com.pvzce.api.content.SlotDef;
import com.pvzce.api.content.TeamDef;
import com.pvzce.api.content.ToolData;
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
import com.pvzce.common.level.CardCooldown;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.level.mechanic.ToolMechanic;
import com.pvzce.common.level.SceneGrid;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.IntTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.common.nbt.StringTag;
import com.pvzce.common.nbt.Tag;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.EffectEventS2C;
import com.pvzce.common.network.packet.CarrySyncS2C;
import com.pvzce.common.network.packet.EntityDespawnS2C;
import com.pvzce.common.network.packet.GameStateS2C;
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
import com.pvzce.server.SeedSelection;
import com.pvzce.common.core.Slot;
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
public final class LevelServer implements LevelAccess, WaveDirector.Host {
    private static final Logger LOGGER = LoggerFactory.getLogger("PVZCE/Level");

    private final LevelDef def;
    private final SceneGrid<SceneElementDef> scene;
    /**
     * The card and buff pools the client was shown, resolved once by the server.
     *
     * <p>Not final because the run's own buff list is written back into it once the level has
     * resolved it (see the constructor): the same record answers "what may be chosen" and "what
     * was chosen", which is what lets {@link #payloadFor} describe both.
     */
    private SeedContext seedContext;
    private final Map<Identifier, Team> teams = new HashMap<>();
    private final List<PvzceEntity> entities = new ArrayList<>();
    private final List<PvzceEntity> pendingAdd = new ArrayList<>();
    private final List<PvzceEntity> pendingRemove = new ArrayList<>();
    private final WaveDirector waves;
    /**
     * Entities that joined the level while no bridge was set, and still owe the client a
     * spawn packet.
     *
     * <p>Not saved: the client is sent a full state whenever it enters a level, so an entity
     * waiting here across a save is covered by that instead.
     */
    private final List<PvzceEntity> awaitSpawnPacket = new ArrayList<>();
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
    /**
     * The level buffs this run is actually played with, with locked, pickable and saved lists
     * already resolved into the one answer.
     *
     * <p>Resolved once, at construction, and then treated as read-only until
     * {@link #restore} replaces it from the save: every consumer asks
     * {@link #activeBuffs()} rather than re-resolving the definition, so "which buffs are on"
     * has exactly one answer per level instance.
     */
    private List<com.pvzce.api.content.LevelBuff> activeBuffs = List.of();
    /**
     * Ticks each resource drop has been waiting for the auto-pickup buff, keyed by entity id.
     *
     * <p>A buff that collected drops on the tick they spawned would make them invisible - the
     * spawn packet and the despawn packet would be in the same batch, so the sun a sunflower just
     * produced would simply never appear. The delay is the whole point: the player sees it land,
     * and then it is picked up.
     *
     * <p>Keyed by id rather than held on the drop, and pruned every tick, so a drop that is
     * clicked, eaten or despawned takes its timer with it.
     */
    private final Map<Integer, Integer> autoCollectTimers = new HashMap<>();
    /** Per-mechanic run state; see {@link #mechanicState}. */
    private final Map<Identifier, Object> mechanicState = new HashMap<>();
    private ServerBridge bridge;

    private int tickCount;
    private int nextMusicCueIndex;
    /**
     * The cue that is playing right now, or {@code null} when nothing is.
     *
     * <p>Kept so a client that joins or resumes in the middle of a run can be told what it
     * missed: the cues are events, and an event that fired before this client was listening is
     * one it never hears - without this, continuing a save played the client's default track
     * (grasswalk) over a night level until the *next* cue happened to come round, which for a
     * level whose only cue is at tick zero is never.
     *
     * <p>Restored by {@link #restore}, which replays the cue list up to the saved index rather
     * than storing a second copy of it in the save.
     */
    private LevelDef.MusicCue currentMusicCue;
    /**
     * Whether a due wave is being held back for an opening wave's field to clear, and for how
     * much longer. See {@link #openingWaveStillOnTheField()}.
     */
    /** True while the wave that is due is waiting on that gate; keeps its banner up. */
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
    /**
     * Wave indices whose <em>warning</em> has already called out.
     *
     * <p>The huge-wave sound belongs to the warning, not to the arrival: in the original
     * Dave's line plays while the red text is fading in, and the two are one beat. The
     * warning is a window of ticks rather than an event, so the transition into it is what
     * fires the sound, and this set keeps a restored save - which re-enters the same window
     * - from calling out twice.
     */
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
     * {@link SeedSelection#sanitize} accepts cannot drift apart.
     */
    public LevelServer(LevelDef def, List<Identifier> selectedSlots, SeedContext seedContext) {
        this(def, selectedSlots, seedContext, null);
    }

    /**
     * Creates a level with the chooser pool the client will be shown, and the buffs the player
     * chose before it started.
     *
     * @param selectedBuffs the buffs the client asked for, or {@code null} when nobody chose -
     *                      which means the world's auto list decides (see
     *                      {@code LevelBuffSelection.plan})
     */
    public LevelServer(LevelDef def, List<Identifier> selectedSlots, SeedContext seedContext,
                       List<Identifier> selectedBuffs) {
        this.def = def;
        this.seedContext = seedContext == null ? SeedContext.all(def) : seedContext;
        this.activeBuffs = com.pvzce.server.LevelBuffSelection.resolve(
                com.pvzce.server.LevelBuffSelection.plan(def, this.seedContext.buffSlots(),
                        selectedBuffs, this.seedContext.autoBuffs(), this.seedContext.ownsBuff()));
        // Written back rather than re-resolved by the payload: "what this run plays with" is one
        // answer, and the packet that tells the client has to be that same answer.
        this.seedContext = this.seedContext.withActiveBuffs(
                com.pvzce.server.LevelBuffSelection.resolveIds(this.activeBuffs));
        // The rules first: the wave director reads one of them (`zombie_spawn_speed_multiplier`)
        // while it works out its first wave's delay, and it is constructed with `this` as its
        // host - so a rule read out of order is a null dereference in the constructor rather
        // than a missing value somewhere later.
        this.rules = new GameRules(def.rules());
        this.waves = new WaveDirector(this, def.waves(), def.waveIntervalEndMultiplier());
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
        this.envVars = new LevelEnvVars(def.envVars());

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
        problems.addAll(LevelValidator.validateBuffs(def));
        if (!problems.isEmpty()) {
            LOGGER.warn("Level {} has {} problem(s):", def.id(), problems.size());
            for (String problem : problems) {
                LOGGER.warn("  - {}", problem);
            }
        }
        // Notes are not defects: a fixed deck is exactly what the first level is. They
        // are reported separately so that "has N problem(s)" keeps meaning something.
        String note = LevelValidator.describeFixedDeck(def);
        if (note != null) {
            LOGGER.info("Level {} note: {}", def.id(), note);
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

    @Override
    public List<ZombieEntity> enemiesInRow(int row, Team team) {
        return entities.stream()
                .filter(e -> e instanceof ZombieEntity z && z.isAlive() && z.gridY() == row
                        && isEnemyOf(z.team(), team))
                .map(e -> (ZombieEntity) e)
                .toList();
    }

    /**
     * Whether one side may be hit by another.
     *
     * <p>"A different team", which is what makes a charmed zombie work without any code about
     * charming: it moved to the plants' team, so every existing targeting rule - the shooters'
     * row scan, a projectile's hit test, the area damage of a blast, the mallet - already treats
     * the zombies beside it as its enemies and the plants as its friends. The alternative, a
     * per-entity "hostile to" flag, would have had to be consulted at every one of those places.
     *
     * <p><strong>An unknown side filters nothing.</strong> A missing team on either end means
     * "this hit is not a side-versus-side action" - a blast with no source, an entity spawned
     * outside a level's team table - and it lands on whatever it covers, which is what every
     * such call meant before this rule existed. Reading an absent side as "nobody's enemy" would
     * instead make those hits silently stop working.
     */
    public static boolean isEnemyOf(Team other, Team self) {
        if (other == null || self == null) {
            return true;
        }
        return !other.id().equals(self.id());
    }

    public int plantCount() {
        return (int) entities.stream().filter(e -> e instanceof PlantEntity p && !p.isRemoved()).count();
    }

    public long aliveZombieCount() {
        return entities.stream().filter(e -> e instanceof ZombieEntity z && z.isAlive()).count();
    }

    /**
     * How many zombies are still on the other side.
     *
     * <p>What the win check asks, and the difference from {@link #aliveZombieCount} is the
     * whole point: a charmed zombie walks toward the house on the plants' side, so a level
     * whose last hostile zombie has died is won even while one of its own is still crossing
     * the lawn. Counting them together would leave that level unwinnable until the charmed one
     * happened to leave the board.
     */
    public long hostileZombieCount() {
        Team plants = teams.get(PvzceIds.PLANT_TEAM);
        return entities.stream()
                .filter(e -> e instanceof ZombieEntity z && z.isAlive() && isEnemyOf(z.team(), plants))
                .count();
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
        ProjectileRef shot = scaledShot(ref, source);
        ProjectileDef projectileDef = BuiltInRegistries.PROJECTILES.get(shot.projectile());
        if (projectileDef == null) {
            return;
        }
        addEntity(new ProjectileEntity(projectileDef, shot, source.team(), x, y, source.height()));
    }

    @Override
    public void spawnArcProjectile(ProjectileRef ref, float x, float y, PlantEntity source, ZombieEntity target) {
        ProjectileRef shot = scaledShot(ref, source);
        ProjectileDef projectileDef = BuiltInRegistries.PROJECTILES.get(shot.projectile());
        if (projectileDef == null) {
            return;
        }
        SceneElementDef base = sceneAt(target.gridX(), target.gridY());
        target.setHeight(base == null ? 0F : base.heightAt(target.cellX(), width()));
        addEntity(new ProjectileEntity(projectileDef, shot, source.team(), x, y, source.height(), target));
    }

    /**
     * The shot a plant actually fires, after the level's buffs have had their say.
     *
     * <p>Applied here, at the one place a projectile is born, rather than inside the plant's
     * shooting capability: the capability and the projectile both read
     * {@link ProjectileRef#range}, so scaling it once on the way in is what keeps "the plant
     * thinks a zombie is in range" and "the shot reaches it" the same statement. A multiplier on
     * an unlimited range is still unlimited - the whole board cannot get longer - which is why
     * this only rewrites finite ranges and otherwise hands back the original reference.
     */
    private ProjectileRef scaledShot(ProjectileRef ref, PlantEntity source) {
        // The same helper the shooters aim with - see PlantShots.scaled. Two implementations of
        // this rule is what let the aiming half keep reading the unscaled number.
        return com.pvzce.common.capability.plant.PlantShots.scaled(ref, source, this);
    }

    @Override
    public void spawnResource(Identifier resourceId, int amount, float x, float y, Team team) {
        spawnResource(resourceId, amount, x, y, team, null,
                com.pvzce.common.network.packet.EntitySpawnS2C.DEFAULT_SCALE);
    }

    @Override
    public void spawnProducedResource(Identifier resourceId, int amount, float x, float y, Team team) {
        spawnProducedResource(resourceId, amount, x, y, team,
                com.pvzce.common.network.packet.EntitySpawnS2C.DEFAULT_SCALE);
    }

    @Override
    public void spawnProducedResource(Identifier resourceId, int amount, float x, float y, Team team,
                                      float scale) {
        spawnResource(resourceId, amount, x, y, team, ResourceDef.DropMotion.RISE, scale);
    }

    private void spawnResource(Identifier resourceId, int amount, float x, float y, Team team,
                               ResourceDef.DropMotion motion) {
        spawnResource(resourceId, amount, x, y, team, motion,
                com.pvzce.common.network.packet.EntitySpawnS2C.DEFAULT_SCALE);
    }

    private void spawnResource(Identifier resourceId, int amount, float x, float y, Team team,
                               ResourceDef.DropMotion motion, float scale) {
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
                amount, motion, driftX, scale));
    }

    /**
     * Puts one zombie on the board for the wave director, which does not pick teams: a wave
     * always arrives on the zombie side.
     */
    @Override
    public ZombieEntity spawnZombie(Identifier zombieId, float x, int row) {
        return spawnZombie(zombieId, zombieTeam(), x, row);
    }

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
                var resource = BuiltInRegistries.RESOURCES.get(id);
                if (resource == null) {
                    yield false;
                }
                // One unit of whatever was named, at the value its own definition declares:
                // a spawned diamond is worth 1000, not the sun's 25.
                spawnResource(id, resource.defaultValue(), x, y, teams.get(PvzceIds.PLANT_TEAM));
                yield true;
            }
            default -> false;
        };
    }

    @Override
    public void damageArea(com.pvzce.api.content.DamageTypeDef type, float centerX, float centerY, float radius, int damage,
                           Team sourceTeam, boolean square) {
        // No multiplier here: ZombieEntity.damage applies it once, at the one entry
        // point every hit goes through. A second copy made a rule that already means
        // "how hard plants hit" depend on which damage path happened to run.
        //
        // A square footprint measured in cells says "this many cells to either side", and a
        // cell is one wide either way from wherever the blast was centred: the plant stands
        // at the centre of its own cell, so its own cell spans 0.5 in every direction and
        // the next one another 1.0. The half-cell term is what makes a radius of 1 reach the
        // neighbouring cell's far edge (and stop there) instead of stopping half a cell
        // short of the zombie standing in it.
        float limit = square ? radius + 0.5F : radius;
        for (PvzceEntity entity : new ArrayList<>(entities)) {
            if (!(entity instanceof ZombieEntity zombie) || !zombie.isAlive()) {
                continue;
            }
            // The source's enemies only. A blast is not a friendly-fire event: a charmed zombie
            // standing next to the cherry bomb that went off under it is on the plants' side,
            // and a melon landing on its own lane must not kill it.
            if (!isEnemyOf(zombie.team(), sourceTeam)) {
                continue;
            }
            if (Math.abs(zombie.cellX() - centerX) <= limit && Math.abs(zombie.cellY() - centerY) <= limit) {
                zombie.damage(damage, type, this);
            }
        }
    }

    @Override
    public void damageRow(com.pvzce.api.content.DamageTypeDef type, int row, int damage, Team sourceTeam) {
        for (PvzceEntity entity : new ArrayList<>(entities)) {
            if (!(entity instanceof ZombieEntity zombie) || !zombie.isAlive()) {
                continue;
            }
            // The zombie's own row, not its position: a zombie straddling a row boundary is
            // in one row or the other and the row it reports is the one it walks in. And the
            // source's enemies only, for the same reason the area blast asks.
            if (zombie.gridY() == row && isEnemyOf(zombie.team(), sourceTeam)) {
                zombie.damage(damage, type, this);
            }
        }
    }

    @Override
    public void leaveCraters(float centerX, float centerY, float radius, boolean square) {
        // The same footprint the blast itself used (see damageArea): a square measured in cells
        // reaches half a cell further than its number says, which is what makes a radius of 1
        // cover the neighbouring cells rather than stopping at their edge.
        float limit = square ? radius + 0.5F : radius;
        for (int x = 0; x < width(); x++) {
            for (int y = 0; y < height(); y++) {
                if (Math.abs(x + 0.5F - centerX) > limit || Math.abs(y + 0.5F - centerY) > limit) {
                    continue;
                }
                if (!isBareGround(x, y)) {
                    continue;
                }
                setScene(x, y, PvzceIds.CRATER);
                // A hole that has just been made starts its recovery now rather than on the
                // clock of whatever crater used to be in this cell.
                craterTimers.remove(y * width() + x);
                sendSceneCell(x, y);
            }
        }
    }

    /**
     * True when a cell is bare ground a blast can leave a hole in.
     *
     * <p>Lawn and bare dirt only. A roof has its own crater art that this does not draw yet, and
     * a pool would need the water variant, so carving those cells into a lawn crater would be a
     * worse answer than leaving them alone.
     */
    private boolean isBareGround(int x, int y) {
        SceneElementDef element = scene.get(x, y);
        if (element == null) {
            return false;
        }
        String surface = element.surfaceClass();
        return PvzceIds.SURFACE_GRASS.equals(surface) || PvzceIds.SURFACE_GROUND.equals(surface);
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
    public record SeedContext(List<SeedOption> pool, List<String> lockedSlotIds, int maxSeedSlots,
                              List<SeedOption> buffPool, int maxBuffSlots, int buffSlots,
                              List<Identifier> autoBuffs, List<Identifier> activeBuffs,
                              java.util.function.Predicate<Identifier> ownsBuff) {
        public SeedContext {
            pool = List.copyOf(pool);
            lockedSlotIds = List.copyOf(lockedSlotIds);
            buffPool = List.copyOf(buffPool);
            autoBuffs = List.copyOf(autoBuffs);
            activeBuffs = List.copyOf(activeBuffs);
            // ``null`` means "everything is owned", which is what a caller with no backpack wants.
            ownsBuff = ownsBuff == null ? buff -> true : ownsBuff;
        }

        /** The card half alone; a context built before buffs existed has none of them. */
        public SeedContext(List<SeedOption> pool, List<String> lockedSlotIds, int maxSeedSlots) {
            this(pool, lockedSlotIds, maxSeedSlots, List.of(), 0,
                    PvzceConstants.DEFAULT_BUFF_SLOTS, List.of(), List.of(), null);
        }

        /** The same context with the run's buffs filled in, after the caller resolved them. */
        public SeedContext withActiveBuffs(List<Identifier> buffs) {
            return new SeedContext(pool, lockedSlotIds, maxSeedSlots, buffPool, maxBuffSlots,
                    buffSlots, autoBuffs, buffs, ownsBuff);
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
                    def.effectiveMaxSeedSlots(PvzceConstants.DEFAULT_SEED_SLOTS),
                    com.pvzce.server.LevelBuffSelection.chooserPool(def),
                    def.effectiveMaxBuffSlots(PvzceConstants.DEFAULT_BUFF_SLOTS),
                    PvzceConstants.DEFAULT_BUFF_SLOTS, List.of(), List.of(), null);
        }

        /** The pool a player with this backpack may actually pick from. */
        public static SeedContext forProfile(LevelDef def, com.pvzce.server.PlayerProfile profile) {
            java.util.function.Predicate<Identifier> owns =
                    profile == null ? null : profile::ownsCard;
            java.util.function.Predicate<Identifier> buffOwns =
                    profile == null ? null : profile::ownsBuff;
            int slots = def.effectiveMaxSeedSlots(profile == null
                    ? PvzceConstants.DEFAULT_SEED_SLOTS : profile.seedSlots());
            int buffSlots = profile == null
                    ? PvzceConstants.DEFAULT_BUFF_SLOTS : profile.buffSlots();
            return new SeedContext(SeedOptions.forLevel(def, owns), SeedOptions.lockedSlotIds(def),
                    slots, com.pvzce.server.LevelBuffSelection.chooserPool(def, buffOwns),
                    def.effectiveMaxBuffSlots(buffSlots), buffSlots,
                    profile == null ? List.of() : profile.autoBuffs(), List.of(), buffOwns);
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

    @Override
    public void flushPending() {
        flushPending(bridge);
    }

    public void flushPending(ServerBridge bridge) {
        for (PvzceEntity entity : pendingAdd) {
            entities.add(entity);
            if (bridge != null) {
                bridge.send(entity.spawnPacket());
            } else {
                // Nothing to send it down, but the entity exists now: it joins the level and
                // waits for the next flush that does have a bridge. Dropping the packet
                // instead is how `/spawn plant` came to answer "已生成" and draw nothing -
                // the plant was in the level, and the client had never been told.
                awaitSpawnPacket.add(entity);
            }
        }
        pendingAdd.clear();
        if (bridge == null) {
            pendingRemove.clear();
            return;
        }
        for (PvzceEntity entity : awaitSpawnPacket) {
            bridge.send(entity.spawnPacket());
        }
        awaitSpawnPacket.clear();
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
            waves.tick();
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
            tickAutoCollect();

            for (PvzceEntity entity : new ArrayList<>(entities)) {
                if (!entity.isRemoved()) {
                    continue;
                }
                // A plant being consumed stays until its clip is over: `isRemoved()` is what
                // "out of the game" means everywhere else, and the drawing has one half-second
                // to catch up with it (see PlantEntity.vanishing).
                if (entity instanceof PlantEntity plant && plant.vanishing()) {
                    continue;
                }
                pendingRemove.add(entity);
            }
            flushPending(bridge);

            syncSlots(bridge);
        } finally {
            this.bridge = previous;
        }
        checkEnd(bridge);
    }

    /**
     * Ticks one entity class in a stable order and streams the results.
     *
     * <p>A removed plant is still ticked when it is playing its vanish clip, which is the one
     * thing that outlives the removal flag: the flag means "out of the game", the clip means
     * "and the drawing has not caught up yet", and something has to count the latter down.
     * Everything else that is removed is skipped, exactly as before.
     */
    private <T extends PvzceEntity> void tickEntities(Class<T> type, ServerBridge bridge) {
        for (PvzceEntity entity : new ArrayList<>(entities)) {
            if (!type.isInstance(entity)) {
                continue;
            }
            if (entity.isRemoved() && !(entity instanceof PlantEntity plant && plant.vanishing())) {
                continue;
            }
            type.cast(entity).tick(this);
        }
        flushPending(bridge);
    }

    private void syncSlots(ServerBridge bridge) {
        if (plantPlayer == null || cardSource == null) {
            return;
        }
        cardSource.tick(this, bridge, tickCount);
        // The third-tick cadence is a bandwidth budget, not a promise that a state change may
        // wait: an animation set and cleared inside one tick was published only when the clock
        // happened to be on a multiple of three. A shot is exactly that - see
        // Entity#setAnimation - so a board on which anything moved state is published this
        // tick, and one on which nothing did keeps the slow cadence.
        boolean stateChanged = false;
        for (PvzceEntity entity : entities) {
            if (entity.animationDirty()) {
                stateChanged = true;
                break;
            }
        }
        if (entitySyncPending || stateChanged || tickCount % 3 == 0) {
            entitySyncPending = false;
            for (PvzceEntity entity : entities) {
                // Cleared for every entity, not only the ones that changed: the whole board
                // just went out in this packet batch, so every pending state has been told.
                entity.clearAnimationDirty();
                bridge.send(entity.updatePacket());
            }
        }
    }

    /** Where this level's cards come from; never null while a plant player exists. */
    public com.pvzce.server.level.cardsource.CardSource cardSource() {
        return cardSource;
    }

    /** The buffs this run is played with, locked and chosen already resolved. */
    public List<com.pvzce.api.content.LevelBuff> activeBuffs() {
        return activeBuffs;
    }

    /**
     * How much longer a shot from this plant flies, or 1 when nothing applies.
     *
     * <p>Asked per shot rather than baked into the plant at planting time, so the answer is the
     * same on the tick the buff is switched on as it is for a plant that has been standing there
     * for a minute - and so the plant's targeting search and the projectile's expiry, which are
     * the same number in {@link com.pvzce.api.content.ProjectileRef#range}, cannot be told two
     * different things.
     */
    public float sporeRangeMultiplier(PlantEntity plant) {
        if (activeBuffs.isEmpty() || plant == null) {
            return 1F;
        }
        if (!com.pvzce.common.buff.LevelBuffs.isSporeShooter(plant.def())) {
            return 1F;
        }
        return com.pvzce.common.buff.LevelBuffs.sporeRangeMultiplier(activeBuffs);
    }

    /**
     * Picks up resource drops on their own when a buff says so.
     *
     * <p>Runs after the drop entities have ticked, so a drop that is still falling has already
     * moved and its collect animation starts from where the player last saw it.
     */
    private void tickAutoCollect() {
        if (activeBuffs.isEmpty() || !com.pvzce.common.buff.LevelBuffs.autoCollects(activeBuffs)) {
            if (!autoCollectTimers.isEmpty()) {
                autoCollectTimers.clear();
            }
            return;
        }
        // One pass collects, the other prunes, and the pruning set is filled by the first: a
        // drop that is gone takes its timer with it, so the map is exactly the drops still
        // waiting rather than a growing list of everything that ever spawned.
        java.util.Set<Integer> gone = new java.util.HashSet<>();
        for (PvzceEntity entity : new ArrayList<>(entities)) {
            if (!(entity instanceof ResourceDropEntity drop)) {
                continue;
            }
            if (drop.isRemoved() || drop.collected()) {
                gone.add(drop.id());
                continue;
            }
            boolean ripe = autoCollectTimers.computeIfAbsent(drop.id(), id -> tickCount)
                    + AUTO_COLLECT_DELAY_TICKS <= tickCount;
            if (ripe) {
                // The drop is not removed here - the collect path marks it and the next
                // flushPending takes it off the field - so the id stays out of ``gone``.
                collectResourceInternal(bridge, drop.id(), true, true);
            }
        }
        if (!gone.isEmpty()) {
            autoCollectTimers.keySet().removeAll(gone);
        }
    }

    /**
     * How long a drop lies there before the auto-pickup buff takes it.
     *
     * <p>A quarter of a second: long enough that the spawn packet and the collect packet are not
     * in the same frame - which would make the sun a sunflower just produced never appear at all -
     * and short enough that it still reads as "picked up", not "left lying around".
     */
    public static final int AUTO_COLLECT_DELAY_TICKS = 15;

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
                LevelMechanics.payloads(def),
                // The backdrop travels as its texture id rather than as a field the client
                // looks up in its own copy of the level file: the board it draws has to be the
                // one this server is running, even for a level that client has never seen.
                def.background().map(Identifier::toString).orElse(""),
                def.hiddenSceneElements(), def.disableShaders(),
                // The buffs the chooser may offer and the resolved cap, resolved here for the
                // same reason the card pool is: the client is told what it may pick rather than
                // working it out from its own copy of the level file.
                seeds.buffPool(), seeds.maxBuffSlots(),
                seeds.activeBuffs().stream().map(Identifier::toString).toList());
    }

    private void processMusicCues(ServerBridge bridge) {
        List<LevelDef.MusicCue> cues = def.music().cues().stream()
                .sorted(Comparator.comparingInt(LevelDef.MusicCue::atTick))
                .toList();
        while (nextMusicCueIndex < cues.size() && tickCount >= cues.get(nextMusicCueIndex).atTick()) {
            LevelDef.MusicCue cue = cues.get(nextMusicCueIndex++);
            // A cue with no event, or one that stops the track, leaves nothing playing.
            currentMusicCue = cue.stop() || cue.event().isEmpty() ? null : cue;
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
            // The move the glove started never finished, and it is over now: this is where its
            // recharge belongs (see useToolInternal - a glove that is holding something has not
            // been charged yet). Without this the card would be free forever after a mis-click.
            chargeGloveForFinishedMove();
        }
    }

    /**
     * Starts the glove's recharge for a move that ended without a second click.
     *
     * <p>The abandoned-carry path is the one place a move can end without the player closing it,
     * so the charge that was deferred on the lift has to be paid here instead. A no-op when the
     * bar holds no glove, which is every level that was not given one.
     */
    private void chargeGloveForFinishedMove() {
        if (plantPlayer == null) {
            return;
        }
        for (Slot slot : plantPlayer.slots()) {
            if (isGlove(slot)) {
                slot.startCooldown(effectiveCooldownTicks(slot));
                if (bridge != null) {
                    bridge.send(new SlotSyncS2C(toSlotInfo(slot)));
                }
                return;
            }
        }
    }

    /**
     * True while the zombie with this id is still standing.
     *
     * <p>A corpse does not count: the death clip is what the player watches, and pacing the
     * next zombie on "the body has finished falling over" would add six seconds to every
     * opening wave for no reason.
     */
    @Override
    public boolean zombieAlive(int id) {
        for (PvzceEntity entity : entities) {
            if (entity.id() == id && entity instanceof ZombieEntity zombie) {
                return zombie.isAlive();
            }
        }
        return false;
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
        return waves.currentWave();
    }

    public int totalWaves() {
        return waves.totalWaves();
    }

    /**
     * True when every wave has been released and no release queue is left.
     *
     * <p>The same question {@code checkEnd} asks before it may call a level won, and public
     * because a mechanic that produces zombies on its own clock has to know when the level has
     * stopped sending waves: a grave that keeps raising one every second and a half never lets
     * the field fall to zero, so a level whose only mouths are its graves could not be finished
     * at all. See {@code GraveSpawnerMechanic}.
     */
    public boolean wavesReleased() {
        return waves.allWavesReleased();
    }

    public boolean waveWarningActive() {
        return waves.waveWarningActive();
    }

    public boolean waveWarningFinal() {
        return waves.waveWarningFinal();
    }

    public boolean finalWaveActive() {
        return waves.finalWaveActive();
    }

    private void syncWaveAndTime(ServerBridge bridge) {
        if (waves.consumeDirty() || tickCount % 10 == 0) {
            bridge.send(waves.progressPacket());
        }
        if (tickCount % 60 == 0) {
            bridge.send(timeOfDayPacket());
        }
    }


    /**
     * How long a crater spends filling back in, in ticks.
     *
     * <p>The last second of {@code crater_recovery}: the hole swaps to the original's own
     * "filling in" art for that long, which is what makes the lawn coming back read as the
     * ground settling rather than as a tile blinking out.
     */
    public static final int CRATER_FADE_TICKS = 60;

    private void tickScene() {
        int recovery = rules.getInt(PvzceIds.RULE_CRATER_RECOVERY);
        // A recovery shorter than the fade would spend its whole life fading, so the fade is
        // clamped to the recovery rather than allowed to outlast it.
        int fadeFrom = Math.max(0, recovery - Math.min(CRATER_FADE_TICKS, recovery));
        for (int x = 0; x < width(); x++) {
            for (int y = 0; y < height(); y++) {
                SceneElementDef element = scene.get(x, y);
                if (element == null) {
                    continue;
                }
                if (!PvzceIds.SURFACE_CRATER.equals(element.surfaceClass())) {
                    continue;
                }
                int key = y * width() + x;
                int ticks = craterTimers.merge(key, 1, Integer::sum);
                if (ticks >= recovery) {
                    craterTimers.remove(key);
                    setScene(x, y, PvzceIds.GRASS);
                    sendSceneCell(x, y);
                } else if (ticks >= fadeFrom && PvzceIds.CRATER.equals(element.id())) {
                    // The crater, not the fading crater: a cell that is already filling in has
                    // nothing left to change to.
                    setScene(x, y, PvzceIds.CRATER_FADING);
                    sendSceneCell(x, y);
                }
            }
        }
    }

    /**
     * Opens every grave on the lawn at the last wave.
     *
     * <p>The night levels' farewell: the tombstones the player has been planting around all
     * level give up one zombie each, as in the original. It is the final wave only, and the
     * level's {@code graves_spawn_night} rule turns it off - a level whose graves are pure
     * scenery says so with {@code false}.
     *
     * <p>The zombie is the weakest one this wave already contains, so the graves cannot raise
     * the difficulty past what the level asked for: a wave of Bucketheads gets a grave full of
     * Bucketheads, and one of ordinary zombies gets ordinary ones. Nothing is added out of
     * thin air, and a wave with no entries at all opens nothing.
     */
    @Override
    public float zombieSpawnSpeedMultiplier() {
        return rules().getFloat(PvzceIds.RULE_ZOMBIE_SPAWN_SPEED_MULTIPLIER);
    }

    @Override
    public void riseGraveZombies(WaveDef wave) {
        if (!clock.isNight(rules) || !rules.getBoolean(PvzceIds.RULE_GRAVES_SPAWN_NIGHT)) {
            return;
        }
        Identifier weakest = weakestZombieOf(wave);
        if (weakest == null) {
            return;
        }
        for (SceneGrid.Cell<SceneElementDef> cell : graveCells()) {
            raiseZombieFromGrave(weakest, cell.x(), cell.y());
        }
    }

    /**
     * Raises one zombie out of the grave in a cell.
     *
     * <p>The one implementation of "a gravestone gives up its dead", shared by the last-wave
     * opening every night level gets and by the {@code grave_spawner} mechanic's steady trickle:
     * the climb, the arm, the dirt and the forced sync are one thing and must not be written
     * twice. Returns the zombie, or {@code null} when the id names nothing registered.
     */
    public ZombieEntity raiseZombieFromGrave(Identifier zombieId, int x, int y) {
        if (zombieId == null) {
            return null;
        }
        float cellX = x + 0.5F;
        // The zombie exists from this tick and climbs for a second: it is a state on
        // the zombie rather than a delayed spawn, so the client can draw it *under*
        // the lawn and raise it (and a save taken mid-climb resumes mid-climb).
        ZombieEntity riser = spawnZombie(zombieId, zombieTeam(), cellX, y);
        if (riser == null) {
            return null;
        }
        // How long the climb takes is the level's business: a mini-game whose whole lawn opens
        // at once wants it over with, and one that opens a stone at a time wants the beat.
        riser.beginRise(rules.getInt(PvzceIds.RULE_ZOMBIE_RISE_TICKS));
        requestEntitySync();
        // The dirt the grave gives up, for the whole climb. The arm that comes out with it is
        // the client's: it is a drawing of the climb, timed by the height published above, so
        // it cannot drift out of step with the body it belongs to.
        emitEffect(PvzceParticles.DIRT_BIG.toString(), cellX, y + 0.5F, PvzceSounds.EFFECT_DIRT_RISE);
        return riser;
    }

    /** Every cell on the board holding a gravestone, in x-then-y order. */
    public List<SceneGrid.Cell<SceneElementDef>> graveCells() {
        List<SceneGrid.Cell<SceneElementDef>> graves = new ArrayList<>();
        for (int x = 0; x < width(); x++) {
            for (int y = 0; y < height(); y++) {
                SceneElementDef element = scene.get(x, y);
                if (element != null && PvzceIds.SURFACE_GRAVE.equals(element.surfaceClass())) {
                    graves.add(new SceneGrid.Cell<>(x, y, element));
                }
            }
        }
        return graves;
    }

    /** True when this cell holds a gravestone. */
    public boolean isGrave(int x, int y) {
        SceneElementDef element = sceneAt(x, y);
        return element != null && PvzceIds.SURFACE_GRAVE.equals(element.surfaceClass());
    }

    /**
     * Replaces a gravestone with the grass it was standing on, and tells the client.
     *
     * <p>The grave buster's whole effect. Uniform grass rather than "the element that was
     * underneath": the scene grid has no such memory, and every level that ships graves puts
     * them on grass.
     */
    public boolean clearGrave(int x, int y) {
        if (!isGrave(x, y)) {
            return false;
        }
        setScene(x, y, PvzceIds.GRASS);
        sendSceneCell(x, y);
        return true;
    }

    /**
     * Raises a gravestone in a cell, if the cell is free ground.
     *
     * <p>Used by the {@code grave_spawner} mechanic. Refused on a cell that already has a
     * gravestone (nothing to do) or a plant (a tombstone may not be dropped on the player's
     * lawn mid-level) - the caller picks another cell rather than this one being replaced.
     */
    public boolean placeGrave(Identifier graveElement, int x, int y) {
        if (!inBounds(x, y) || sceneAt(x, y) == null || plantAt(x, y) != null) {
            return false;
        }
        SceneElementDef element = BuiltInRegistries.SCENE_ELEMENTS.get(graveElement);
        if (element == null || !PvzceIds.SURFACE_GRAVE.equals(element.surfaceClass())) {
            return false;
        }
        scene.set(x, y, element);
        sendSceneCell(x, y);
        // The dirt it pushes aside. The stone itself comes up out of the lawn on the client -
        // a cell that changes during play is animated there (see `SceneRises`) - so this is
        // the half of the event the simulation owns: the spray and the sound.
        emitEffect(PvzceParticles.DIRT_BIG.toString(), x + 0.5F, y + 0.5F,
                PvzceSounds.EFFECT_DIRT_RISE);
        return true;
    }

    /**
     * The cheapest zombie in a wave, by the health its definition declares.
     *
     * <p>"Weakest" is read from the content rather than from a hardcoded id: which zombie a
     * grave holds is a property of the wave the level wrote, and a level that ships its own
     * zombies gets its own answer. {@code null} for a wave whose entries name nothing that is
     * registered.
     */
    private Identifier weakestZombieOf(WaveDef wave) {
        Identifier weakest = null;
        int lowest = Integer.MAX_VALUE;
        for (WaveDef.Entry entry : wave.entries()) {
            ZombieDef def = BuiltInRegistries.ZOMBIES.get(entry.id());
            if (def == null) {
                continue;
            }
            if (def.health() < lowest) {
                lowest = def.health();
                weakest = entry.id();
            }
        }
        return weakest;
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
                && waves.allWavesReleased()
                && hostileZombieCount() == 0) {
            markEnd(teams.get(PvzceIds.PLANT_TEAM));
        }

        if (!gameState.equals(GameStateS2C.RUNNING) && !gameEndPacketSent) {
            gameEndPacketSent = true;
            Team winnerTeam = teams.get(winner);
            LOGGER.info("Game over, winner={}", winner);
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
        // Sun first, and independently of the coin roll: a level that pays sun for kills wants
        // it whether or not it also pays coins, and a `return` on the coin roll below would
        // otherwise swallow every sun a level with no coin drops was owed.
        dropZombieSun(zombie);
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

    /**
     * Rolls the level's "a dying zombie leaves sun" chance and drops what it pays where it fell.
     *
     * <p>The heal for a level with no sun economy of its own: 2-5's graves and its mallet mean
     * the player never plants a producer, and without this the only sun in the level is the 50
     * it starts with. Each sun is worth {@code sun_value}, the same number the sky and a
     * sunflower pay, so a dropped sun is worth what every other sun is worth.
     *
     * <p>A roll pays <code>zombie_sun_drop_count</code> suns rather than one, scattered over the
     * cell the zombie fell in and its neighbours: a level that pays sun for kills pays it in the
     * currency the player is looking for (a click each), and the several-at-once shape is what
     * makes a kill worth crossing the lawn for. The chance is meant to be read against that
     * count - 3% of kills paying three suns is about the same income as 9% paying one, spread
     * over fewer, better moments.
     *
     * <p>They land where the zombie died rather than falling in from above: a sun is dropped
     * <em>by</em> the kill, and a drop that arrives from the sky reads as the level's own
     * weather. {@link ResourceDef.DropMotion#LANDED} is what says so, and it is the same motion
     * a coin uses for the same reason.
     */
    private void dropZombieSun(ZombieEntity zombie) {
        float chance = rules.getFloat(PvzceIds.RULE_ZOMBIE_SUN_DROP_CHANCE);
        if (chance <= 0F || random.nextFloat() >= chance) {
            return;
        }
        Team plantTeam = teams.get(PvzceIds.PLANT_TEAM);
        if (plantTeam == null || BuiltInRegistries.RESOURCES.get(PvzceIds.SUN) == null) {
            return;
        }
        int value = rules.getInt(PvzceIds.RULE_SUN_VALUE);
        int count = Math.max(1, rules.getInt(PvzceIds.RULE_ZOMBIE_SUN_DROP_COUNT));
        int cellX = (int) Math.floor(zombie.cellX());
        int cellY = (int) Math.floor(zombie.cellY());
        for (int i = 0; i < count; i++) {
            // One cell of scatter, clamped to the board: suns stacked in exactly one cell are
            // several sprites on one pixel, and a drop outside the lawn cannot be clicked.
            int x = Math.max(0, Math.min(width() - 1, cellX + random.nextInt(3) - 1));
            int y = Math.max(0, Math.min(height() - 1, cellY + random.nextInt(3) - 1));
            spawnResource(PvzceIds.SUN, value, x, y, plantTeam,
                    ResourceDef.DropMotion.LANDED);
        }
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
        waves.clearOnLevelEnd();
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
        LOGGER.debug("Planted {} at ({},{}) count={}", slot.defId(), x, y, plantCount());
        cardSource.afterSpend(this, bridge, slot);
        return !plant.isRemoved() || plant.consumesOnPlace();
    }

    public boolean collectResource(ServerBridge bridge, int entityId) {
        return withBridge(bridge, () -> collectResourceInternal(bridge, entityId, false, false));
    }

    /**
     * Collects one drop for the plant player.
     *
     * <p>The two flags are what the auto-pickup buff needed. {@code silent} drops the chat line:
     * one "&lt;+25 阳光&gt;" per pickup is exactly right when the player clicked it and is spam
     * when a buff is picking up ten a second. {@code auto} keeps the level's collection rules -
     * {@code collectible}, the resource being unlocked here, and the matching card being in the
     * bar - but <em>does not answer</em> when they refuse: a buff that announced "没有对应资源卡"
     * every second would bury the board it is trying to help with. The sound, the sparkle and the
     * fly-to-bank animation are unchanged, because those are what tell the player their sun
     * arrived.
     */
    private boolean collectResourceInternal(ServerBridge bridge, int entityId, boolean silent,
                                            boolean auto) {
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
                    if (!auto) {
                        bridge.send(new ServerMessageS2C("该资源在本关未解锁。"));
                    }
                    return false;
                }
                if (!plantPlayer.hasResourceCard(drop.defId())) {
                    if (!auto) {
                        bridge.send(new ServerMessageS2C("没有对应资源卡，无法收集。"));
                    }
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
            // At the drop's own position, height included: a sun is collected wherever it is
            // - halfway down its fall, or a hand's width above the lawn after rising out of a
            // sunflower - and a sparkle on the grass under it reads as a glow belonging to the
            // lawn rather than to the sun.
            emitEffect(drop.def().pickupEffect().map(Identifier::toString).orElse(""),
                    drop.cellX(), drop.cellY() + Math.max(0F, drop.height()), drop.def().pickupSound());
            // The visual fly-to-bank animation is client-side only, but it still
            // needs the server-confirmed drop position and icon.
            bridge.send(new ResourceCollectS2C(drop.id(), drop.defId().toString(), drop.amount(),
                    drop.cellX(), drop.cellY(), drop.height(),
                    drop.def().icon() == null ? "" : drop.def().icon().toString()));
            bridge.send(new EntityDespawnS2C(drop.id()));
            bridge.send(new ResourceDeltaS2C(plantPlayer.team().id().toString(), drop.defId().toString(),
                    plantPlayer.team().resourcesOf(drop.defId())));
            String label = drop.defId().path().equals("sun") ? "阳光" : drop.defId().toString();
            if (!silent) {
                bridge.send(new ServerMessageS2C("+" + drop.amount() + " " + label));
            }
            return true;
        }
        return false;
    }

    public boolean useTool(ServerBridge bridge, int slotIndex, int x, int y) {
        return withBridge(bridge, () -> useToolInternal(bridge, slotIndex, x, y));
    }

    /**
     * Uses a tool the level grants rather than one the player holds, as its own action.
     *
     * <p>The {@code pvzce:tool} mechanic's default tool: Whack-a-Zombie's mallet, which is not a
     * card - it takes no slot, prints no price and has no recharge bar, so there is nothing on
     * the bar to charge and nothing to sync. What it shares with the card path is the part that
     * matters: the same {@link #applyToolEffect}, so a hammer is a hammer whichever way it was
     * picked up.
     *
     * <p>The price is the level's own number ({@link ToolMechanic#sunCost}) and is charged here,
     * because a default tool has no card whose price the ordinary path would have collected.
     */
    public boolean useGrantedTool(ServerBridge bridge, ToolData granted, int x, int y) {
        return withBridge(bridge, () -> useGrantedToolInternal(bridge, granted, x, y));
    }

    private boolean useGrantedToolInternal(ServerBridge bridge, ToolData granted, int x, int y) {
        if (!gameState.equals(GameStateS2C.RUNNING) || !humanTeamId.equals(PvzceIds.PLANT_TEAM)
                || plantPlayer == null || granted == null) {
            return false;
        }
        ToolDef tool = ToolMechanic.defOf(granted);
        if (tool == null) {
            return false;
        }
        if (!inBounds(x, y)) {
            return false;
        }
        int cost = ToolMechanic.sunCost(granted);
        // The refusal comes before the effect, so a use the player cannot afford does not
        // happen and then get taken back.
        if (cost > 0 && plantPlayer.team().resourcesOf(PvzceIds.SUN) < cost) {
            bridge.send(new ServerMessageS2C("阳光不足！"));
            return false;
        }
        if (!applyToolEffect(tool, x, y)) {
            return false;
        }
        if (cost > 0) {
            plantPlayer.team().consume(PvzceIds.SUN, cost);
            bridge.send(new ResourceDeltaS2C(plantPlayer.team().id().toString(), PvzceIds.SUN.toString(),
                    plantPlayer.team().resourcesOf(PvzceIds.SUN)));
        }
        return true;
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
        if (carriedPlantId >= 0 && slot != null && !isGlove(slot)) {
            abandonCarry();
        }
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
        // A tool whose definition prices a use (the hammer's 50 sun) charges it here, from the
        // same definition the card bar prints its price from - so the number on the card and the
        // number the server takes cannot drift. A refused effect still costs nothing, in either
        // resource.
        int cost = tool.useCost().amountOf(PvzceIds.SUN);
        boolean payForUse = !finishingMove && cost > 0;
        if (payForUse && plantPlayer.team().resourcesOf(PvzceIds.SUN) < cost) {
            bridge.send(new ServerMessageS2C("阳光不足！"));
            bridge.send(new SlotSyncS2C(toSlotInfo(slot)));
            return false;
        }
        if (!applyToolEffect(tool, x, y)) {
            // The click was understood and refused (an empty cell, a plant in the way).
            // Spending a use on it would cost the player a charge for nothing, and would
            // leave the glove unusable for the case that comes next.
            bridge.send(new SlotSyncS2C(toSlotInfo(slot)));
            return false;
        }
        if (payForUse) {
            plantPlayer.team().consume(PvzceIds.SUN, cost);
            bridge.send(new ResourceDeltaS2C(plantPlayer.team().id().toString(), PvzceIds.SUN.toString(),
                    plantPlayer.team().resourcesOf(PvzceIds.SUN)));
        }
        if (!finishingMove) {
            slot.consumeUse();
            // A glove that is now holding a plant starts its recharge when the plant is put
            // down, not when it is picked up. The move is not finished until then, and a card
            // that recharges while its own second click is still owed is a card the player
            // cannot tell the state of: the bar greys it out mid-move.
            if (!awaitingDrop(slot)) {
                slot.startCooldown(effectiveCooldownTicks(slot));
            }
        } else if (carriedPlantId < 0) {
            // That was the drop: the move is over, so this is where its recharge belongs.
            slot.startCooldown(effectiveCooldownTicks(slot));
        }
        bridge.send(new SlotSyncS2C(toSlotInfo(slot)));
        return true;
    }

    /** True when this card has just started a move and is still holding the plant. */
    private boolean awaitingDrop(Slot slot) {
        return carriedPlantId >= 0 && isGlove(slot);
    }

    /**
     * Forgets a carry that no longer makes sense.
     *
     * <p>Called when the player reaches for something else: picking a card, using another tool
     * or leaving the level ends the move, and a carry that survived it would put the plant
     * down somewhere the player was no longer thinking about.
     */
    private void abandonCarry() {
        if (carriedPlantId >= 0) {
            clearCarry();
        }
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
                // What one swing is worth, and whether armour absorbs it, are the tool's own
                // numbers (see ToolDef.damage / damage_type) - not this method's. They used to
                // live here as a 100000-point `pvzce:mower` blow, which made the mallet a lawn
                // mower: everything on the lawn died in one hit, and a Buckethead was worth no
                // more than a bare zombie. The default is one normal zombie's health, and the
                // shipped hammer declares `pvzce:impact` so a cone or a bucket still costs a
                // swing of its own - which is what the original's two- and three-hit mallet
                // means in a build where one blow lands on one layer.
                //
                // `false` when nothing was under it, so a swing at empty grass follows the same
                // rule every other refused click does: no sun, no cooldown, no bang.
                // Around the point that was clicked, in world cells - not "the zombies in the
                // clicked cell". The player aims at a zombie; making them also land inside its
                // cell is a second target they cannot see, and with 2-5's speed multiplier the
                // one they aimed at has usually stepped out of it. `tool.range` is the reach.
                float hitX = x + 0.5F;
                float hitY = y + 0.5F;
                float reach = tool.range();
                boolean hitSomething = false;
                for (PvzceEntity entity : new ArrayList<>(entities)) {
                    if (!(entity instanceof ZombieEntity zombie) || !zombie.isAlive()) {
                        continue;
                    }
                    if (!isEnemyOf(zombie.team(), plantPlayer.team())) {
                        continue;
                    }
                    // Measured in world cells on both axes, so a click between two rows reaches
                    // whichever zombie is actually under the cursor.
                    float dx = zombie.cellX() - hitX;
                    float dy = zombie.cellY() - hitY;
                    if (Math.abs(dy) > reach || Math.abs(dx) > reach * ZOMBIE_HALF_WIDTH_FACTOR) {
                        continue;
                    }
                    zombie.damage(tool.damage(), toolTypeFor(tool), this);
                    // The blow's sound only. It used to throw the hit spark as well, and the
                    // spark is a 25-star burst drawn where the cursor is standing - at which
                    // point the player reads it as a halo around the mallet rather than as a
                    // hit. The hit's own picture is the mallet's swing, which the client plays
                    // on the click (see InGameScreen#swingDefaultToolCursor); what the server
                    // still owes the player is the confirmation that it landed, and that is the
                    // bonk.
                    emitEffect("", zombie.cellX(), zombie.cellY(), PvzceSounds.EFFECT_BONK);
                    hitSomething = true;
                }
                yield hitSomething;
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
        send(new CarrySyncS2C(plant.def().id().toString()));
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
        boolean wasCarrying = carriedPlantId >= 0;
        carriedPlantId = -1;
        carryTimeoutTicks = 0;
        if (wasCarrying) {
            // The client has to be told even here: a carry that expired (or whose plant was
            // eaten) is one the cursor must stop drawing.
            send(new CarrySyncS2C(""));
            // The move is over one way or another, so the glove's recharge starts now. Without
            // this an expired carry left the card ready *and* holding nothing, and the next
            // click lifted a plant without ever paying for the one before it.
            Slot slot = gloveSlot();
            if (slot != null) {
                slot.startCooldown(effectiveCooldownTicks(slot));
                send(new SlotSyncS2C(toSlotInfo(slot)));
            }
        }
    }

    /** The glove among this level's cards, or {@code null} when the bar has none. */
    private Slot gloveSlot() {
        if (cardSource == null) {
            return null;
        }
        for (Slot slot : cardSource.slots()) {
            if (slot.kind() == Slot.Kind.TOOL && isGlove(slot)) {
                return slot;
            }
        }
        return null;
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
        // The bar draws the recharge as "how much of this card's cooldown is left", so the
        // divisor travels with the remainder rather than being re-derived per card on the
        // client - which is how a sweep used to fill only its last 300 ticks whatever the
        // card's real cooldown was, and how a level with a multiplier would have drawn a
        // full bar as three-quarters full.
        return new SlotInfo(slot.index(), slot.defId().toString(), slot.kind().json(), price,
                slot.cooldownLeft(), effectiveCooldownTicks(slot), slot.usesLeft(), available);
    }

    /**
     * How wide a zombie counts as, as a fraction of the swing's reach, on the x axis.
     *
     * <p>A zombie is drawn taller than it is wide - the art is a standing figure - so a circular
     * reach around the cursor hits things above and below the aim that the player was not
     * pointing at. Narrowing x keeps the hit box about as wide as a zombie looks.
     */
    private static final float ZOMBIE_HALF_WIDTH_FACTOR = 1.15F;

    /**
     * The damage type one swing of a tool lands as.
     *
     * <p>Resolved on this side because the answer is a registry lookup through
     * {@link ZombieEntity#damageType}, which lives in the server layer; {@code ToolDef} stays a
     * plain data record in {@code api} and only carries the id. An unregistered id falls back
     * to {@code pvzce:projectile} for the same reason a projectile's does: a typo must not
     * become a hit that ignores armour.
     */
    public com.pvzce.api.content.DamageTypeDef toolTypeFor(ToolDef tool) {
        return ZombieEntity.damageType(tool.damageType());
    }

    /**
     * The ticks this card waits before it can be used again, level multiplier included.
     *
     * <p>The one place the level's {@code pvzce:seed_cooldown_multiplier} is applied, read
     * from the live rules rather than baked into the bar when it was dealt: {@code /gamerule}
     * changes the cooldowns of the cards already in the player's hand, and the card bar's
     * recharge is drawn against the same number the charge used.
     */
    public int effectiveCooldownTicks(Slot slot) {
        if (slot == null) {
            return 0;
        }
        return CardCooldown.effective(slot.cooldownTicks(),
                rules.getFloat(PvzceIds.RULE_SEED_COOLDOWN_MULTIPLIER));
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
        LevelPayload payload = payloadFor(def, seedContext);
        bridge.send(new LevelInitS2C(def.id().toString(), slotInfos(), waves.waveTypes(), payload,
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
        // What is playing, for a client that arrived after the cue did: a resumed run, or a
        // second player joining. Sent after the init packet so the client has a level to attach
        // the track to, and harmless when the track is the one it already started - the music
        // controller ignores a start of the track that is already playing.
        if (currentMusicCue != null) {
            bridge.send(new MusicEventS2C(currentMusicCue.track(),
                    currentMusicCue.event().map(Identifier::toString).orElse(""),
                    currentMusicCue.loop(), false,
                    Math.max(0F, Math.min(1F, currentMusicCue.volume())),
                    Math.max(0F, currentMusicCue.fadeSeconds())));
        }
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
        return waves.progressPacket();
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
        awaitSpawnPacket.clear();
        pendingRemove.clear();
        entities.clear();
        waves.clearQueues();
        craterTimers.clear();
    }

    // ------------------------------------------------------------------
    // Save / restore
    // ------------------------------------------------------------------

    private static final String KEY_ENTITIES = "Entities";
    private static final String KEY_KIND = "Kind";

    public CompoundTag save() {
        CompoundTag root = new CompoundTag();
        root.putString("LevelId", def.id().toString());
        root.putString("GameState", gameState);
        if (winner != null) {
            root.putString("Winner", winner.toString());
        }
        root.putInt("Tick", tickCount);
        root.putLong("DayTicks", clock.dayTicks());
        root.putInt("NextMusicCueIndex", nextMusicCueIndex);
        waves.save(root);


        // Teams, cards, resources and every entity live in one tag; the server no
        // longer writes a second copy of the same data or a per-team side file.
        root.put("Teams", saveTeams());
        root.put("Slots", saveSlots());
        // The buffs this run is played with, by id, so continuing a save continues the same
        // rules. Written through the selection class because it is also what reads them back;
        // the two halves of one file format do not get to live in two places.
        com.pvzce.server.LevelBuffSelection.writeSaved(root, com.pvzce.server.LevelBuffSelection
                .resolveIds(activeBuffs));
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
        // Before anything else that a buff could change: a restored run plays by the rules it
        // was saved under, not by whatever the caller resolved a moment ago. A save that
        // predates buffs has no list, and then the caller's resolution stands.
        List<Identifier> savedBuffs = com.pvzce.server.LevelBuffSelection.readSavedTag(root);
        if (savedBuffs != null) {
            activeBuffs = com.pvzce.server.LevelBuffSelection.resolve(savedBuffs);
        }
        autoCollectTimers.clear();
        clock.setDayTicks(root.getLong("DayTicks"));
        waves.restore(root);
        nextMusicCueIndex = Math.max(0, root.getInt("NextMusicCueIndex"));
        // Replayed rather than saved: the cue that is playing is "the last one that fired", which
        // the index already says. Storing the cue itself would be a second copy of the level's
        // own music block, and the two could disagree after a data pack edit.
        currentMusicCue = null;
        List<LevelDef.MusicCue> cues = def.music().cues().stream()
                .sorted(Comparator.comparingInt(LevelDef.MusicCue::atTick))
                .toList();
        for (int i = 0; i < Math.min(nextMusicCueIndex, cues.size()); i++) {
            LevelDef.MusicCue cue = cues.get(i);
            currentMusicCue = cue.stop() || cue.event().isEmpty() ? null : cue;
        }
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
        restoreEntities(root.getList(KEY_ENTITIES));
        waves.refreshWarning();
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
