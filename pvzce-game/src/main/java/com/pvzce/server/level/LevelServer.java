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
import com.pvzce.common.level.mechanic.RakeMechanic;
import com.pvzce.common.level.mechanic.ScaryPotterMechanic;
import com.pvzce.common.level.mechanic.WavePacingMechanic;
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
import com.pvzce.common.network.packet.HeldCardS2C;
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
     * True when this level generates its waves round after round instead of owning a table.
     *
     * <p>Read from the {@code pvzce:endless} mechanic, and the switch three things hang off:
     * where a wave comes from (see {@link #waveAt}), whether the level can ever be won (it
     * cannot - an endless run ends only when a zombie reaches the house), and whether the
     * round's end pauses the level for a card selection.
     */
    private final boolean rounds;
    /**
     * The chart this level's zombies are generated from, or {@code null} when it has no zombie
     * clock.
     *
     * <p>Non-null means "the song is the spawn table": the waves come from {@code RhythmWaves} on
     * the chart's own clock, the level writes no {@code waves}, and the player's way to win is the
     * end of the track rather than the end of a list.
     */
    private final com.pvzce.api.content.RhythmChartData rhythmChart;
    /** The schedule those waves grow on, or {@code null} on an ordinary level. */
    private final com.pvzce.api.content.EndlessScheduleDef endlessSchedule;
    /**
     * How long the level waits for a card choice before continuing with the bar it has.
     *
     * <p>A minute is far longer than the choice takes, and it exists only so that a client that
     * never answers - a crash, a closed window, a test harness that does not know the packet -
     * cannot freeze a run forever. The run continuing on its old cards is a worse round than the
     * player would have picked, and a much better outcome than a level that stops.
     */
    static final int ROUND_CLEAR_TIMEOUT_TICKS = 60 * PvzceConstants.TICKS_PER_SECOND;
    /**
     * How long the open round boundary has been waiting for an answer, in ticks.
     *
     * <p>Counted here rather than read off {@link #tickCount}, which the boundary deliberately
     * stops advancing: the run's own clock should not charge the player for the time they spend
     * choosing their cards, and a wait measured on a frozen clock never expires.
     */
    private int roundClearWaitedTicks;
    /**
     * Entities that joined the level while no bridge was set, and still owe the client a
     * spawn packet.
     *
     * <p>Not saved: the client is sent a full state whenever it enters a level, so an entity
     * waiting here across a save is covered by that instead.
     */
    private final List<PvzceEntity> awaitSpawnPacket = new ArrayList<>();
    private final Map<Integer, Integer> craterTimers = new HashMap<>();
    /**
     * How long each frozen cell has been frozen, keyed by cell.
     *
     * <p>The ice trail's own clock, read by {@link #tickScene}: a cell freezes when the zamboni
     * drives over it and melts on its own {@code ice_melt} count from there, so a trail disappears
     * from the end the machine started at. Not saved, exactly like {@link #craterTimers} - a
     * resumed run gets a fresh clock on the ice it restored, which is the same forgiveness a
     * resumed run gives a crater.
     */
    private final Map<Integer, Integer> iceTimers = new HashMap<>();
    private final Random random = new Random();

    /**
     * Reseeds the level's dice.
     *
     * <p>For tests: everything a run rolls - the mutation catalogue, a wave's rows, a coin
     * scatter - comes from this one source, so a seeded level is a reproducible run. "Which
     * mutations a save round-trips" is a question about the save format, and a test that asked it
     * with a time-seeded generator was really asserting that this particular run happened to roll
     * a mutation that touches a rule.
     *
     * <p>Not for gameplay: nothing in the game reseeds a running level.
     */
    public void seedRandom(long seed) {
        random.setSeed(seed);
    }
    private final PvzcePlayer plantPlayer;
    /**
     * The opponent's card bar, or {@code null} on every level whose second side has no deck.
     *
     * <p>Server-only: the client is told what the opponent *did* (the versus state's last card), not
     * what it holds in what state. The bar exists so that "a card costs sun and then recharges" is one
     * rule with one implementation, rather than a rule the human's clicks obey and the AI's decisions
     * do not.
     */
    private final PvzcePlayer opponentPlayer;
    private final GameRules rules;
    /**
     * How hard this world plays.
     *
     * <p>Not a rule of its own: the tier is written <em>into</em> four of the level's rules as an
     * extra multiplier (see {@link #applyDifficulty}), so nothing in the simulation has to know
     * this exists. It lives here because a save has to be able to divide it back out again.
     */
    private com.pvzce.common.level.Difficulty difficulty = com.pvzce.common.level.Difficulty.DEFAULT;
    /**
     * When the sky drops its next sun; see {@link SunDropClock}.
     *
     * <p>Reset from the rules at construction and restored from the save on resume, so a
     * continued run keeps the gap it was in rather than handing out a sun on the first tick.
     */
    private final SunDropClock sunDropClock = new SunDropClock(random);
    private final LevelEnvVars envVars;
    private final PvzceClock clock = new PvzceClock();
    private final PlantAIPlayer plantAi = new PlantAIPlayer();
    /**
     * The versus mode's opponent, built the first time a level asks for it.
     *
     * <p>Lazy because it owns a worker thread and an HTTP client, and every ordinary level would
     * pay for both: only a level running {@code pvzce:versus} ever asks, and it asks on its first
     * tick.
     */
    private com.pvzce.server.ai.JevBrain jevBrain;
    /**
     * Where Jev is for this run, or {@link com.pvzce.common.jev.AiSettings#NONE}.
     *
     * <p>Set by the server from the player's own settings when a level is created (and again if
     * they change them), never read from a save: a credential is not part of a run, and a save
     * that carried one would be a key on disk under the player's world directory.
     */
    private com.pvzce.common.jev.AiSettings jevSettings = com.pvzce.common.jev.AiSettings.NONE;
    /** The commander tier's endpoint; see {@link #setCommanderSettings}. */
    private com.pvzce.common.jev.AiSettings commanderSettings = com.pvzce.common.jev.AiSettings.NONE;
    /** See {@link #setCommanderTakesOverAtTheDoor}; off unless the player asked for it. */
    private boolean commanderTakesOverAtTheDoor;
    /**
     * Where this level's cards come from: the ordinary deck, a conveyor belt, or whatever
     * a registered mechanic deals. Never null while there is a plant player.
     *
     * <p>This replaced a nullable {@code belt} field plus five {@code belt != null} branches;
     * the branches are now the implementation's own behaviour.
     */
    private com.pvzce.server.level.cardsource.CardSource cardSource;
    /**
     * The mutations this level runs with, or {@code null} for an ordinary level.
     *
     * <p>Owned here rather than by the mechanic that declares them, because the list is per-run
     * state other parts of the level have to read - planting asks it, the placement veto asks it,
     * and the card-bar handoff is decided by it. The mechanic only says "this level mutates".
     */
    private final com.pvzce.common.level.mutation.MutationManager mutations;
    /**
     * The cards the player chose, as one immutable list.
     *
     * <p>Kept for the card-bar handoff: handing the bar back after a mutation's belt ends needs
     * "what the player picked" to still exist, and by then the bar holds the belt's cards rather
     * than the player's.
     *
     * <p>Not final because an endless run replaces it between rounds: the card chooser opens
     * again at every round boundary, and this is the one field that decision writes (see
     * {@link #reselectCards}). What it is <em>not</em> is a per-round reset of anything else -
     * the lawn, the sun and the mowers carry over by design.
     */
    private List<Identifier> selectedCards;
    /**
     * Which cards the player owns, for the mutations that hand out other ones.
     *
     * <p>Answered "everything" when nobody said, which is what a test harness and the editor's
     * preview want: a level built without a profile has no backpack to consult, and refusing
     * every replacement would make the mutation look broken rather than unowned.
     */
    private final java.util.function.Predicate<Identifier> ownsCard;
    /**
     * Tools a mutation granted this run, on top of the ones the level's own blocks declare.
     *
     * <p>Separate from the mechanic list because the mechanics are resolved once and cannot change:
     * this is the runtime half of the same statement, and {@code ToolMechanic.granted} is the one
     * place the two are read together.
     */
    private final List<com.pvzce.api.content.ToolData> grantedTools = new ArrayList<>();
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
     * The mechanics actually in force, after the implicit ones and the player's rake.
     *
     * <p>Exposed because it is the resolved answer rather than the file's: a test asking "did this
     * world get its rake" and a future UI asking "what is this level doing" both want the list the
     * simulation is running, not the one the level declared.
     */
    public List<TypedMechanic> mechanics() {
        return mechanics;
    }
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
    /** See {@link #setBothSidesCollect}; true on a versus run, false on everything else. */
    private boolean bothSidesCollect;
    /**
     * Zombies killed this run, for the end-of-level summary.
     *
     * <p>Counted here rather than asked of the wave director: the director keeps per-wave counts
     * for its gates and clears its history as waves retire, so "how many did I kill" is not a
     * question it can answer. Survives a save like the tick count does, because a resumed endless
     * run's total is the run's, not the session's.
     */
    private int zombieKills;
    /** Per-mechanic run state; see {@link #mechanicState}. */
    private final Map<Identifier, Object> mechanicState = new HashMap<>();
    /** A fog a mutation installed, or {@code null} while the level's own answer stands. */
    private com.pvzce.api.content.FogData fogOverride;
    private int fogBlownUntil;
    private ServerBridge bridge;
    /**
     * The last bridge this level was driven with, which is where an unprompted packet goes.
     *
     * <p>{@link #bridge} is the <em>ambient</em> one: it is set while a player action or a command
     * is being handled and cleared afterwards, which is what makes "send this to whoever asked"
     * safe. But a mutation also acts on its own clock - it floods rows, rolls fog in, spawns a raid -
     * and those sends have nobody's action to ride on. They used to be dropped in silence, which is
     * how a flooded lawn stayed green on screen while the server's own state said water.
     *
     * <p>Kept as "the last one seen" rather than as a stored connection so a level stays a plain
     * simulation object: the tick loop hands its bridge in every tick, so the reference is refreshed
     * continuously and a headless level (a test, the seed chooser's preview) simply has none.
     */
    private ServerBridge outbound;

    private int tickCount;
    /**
     * Which of the level's music cues have already fired, as a bit per cue in the list's own
     * order.
     *
     * <p>A mask rather than a cursor because cues have two clocks (see
     * {@code MusicCue.Trigger}): a run has a level-start timeline and a waves-start one, and one
     * cursor over a single sorted list would let a late level-start cue hold back a waves-start
     * cue that is already due. "Has this one fired" is the question the dispatch actually asks,
     * so that is what is stored.
     */
    private long musicCuesFired;
    /**
     * The level tick the waves began on, or {@code -1} while the preparation phase is still up.
     *
     * <p>The base a {@code WAVES_START} cue counts from, and the reason a rhythm level's song and
     * its chart can start together: both are anchored to the tick the player pressed 开始 rather
     * than to a time the level file had to guess.
     */
    private int wavesStartTick = -1;
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
    /**
     * The vase the glove is holding, as the packed cell it came from, or {@code -1}.
     *
     * <p>A vase is a scene element rather than an entity, so a carried one has nothing to hold on
     * to: the cell it stood in is cleared when it is lifted and written again when it is put down,
     * and this pair is what remembers where to put it back if the move is abandoned. The card
     * inside travels with it, or a vase could be used to launder one out of existence.
     */
    private long carriedVaseFrom = -1L;
    /** What was inside the vase in the glove's hand, or {@code null} when it was empty. */
    private Identifier carriedVaseCard;
    /** Ticks left before an abandoned carry is put back where it came from. */
    private int carryTimeoutTicks;
    /**
     * The seed packet the player is carrying, or {@code -1}.
     *
     * <p>The second thing a plant can be "in hand" as, and a different thing from the glove's
     * carry above: a packet is an entity of its own ({@link com.pvzce.server.entity.CardDropEntity}),
     * so the hand is the id of the packet the player picked up and the card is read back from it -
     * one fact, one place. It is planted for free, because the container that dropped it is what
     * paid for the plant (see {@link #plantHeldCard}).
     */
    private int heldCardDropId = -1;
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

    /** Seeds before initialization, including the conveyor's opening cards and grave placement. */
    public LevelServer(LevelDef def, long randomSeed) {
        this(def, def.slots(), randomSeed);
    }

    public LevelServer(LevelDef def, List<Identifier> selectedSlots, long randomSeed) {
        this(def, selectedSlots, SeedContext.all(def), null, null, randomSeed);
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
        this(def, selectedSlots, seedContext, selectedBuffs, null);
    }

    /**
     * Creates a level that knows which cards the player owns.
     *
     * <p>The backpack is a fourth input for the same reason the seed pool is a third: a level
     * does not know which profile it was started from. The one thing that needs it is a mutation
     * that replaces cards - "pick another plant the player has" is unanswerable without it - and
     * the default constructor's answer is "everything is owned", which is what the tests and the
     * editor's preview want.
     *
     * @param ownsCard the player's backpack, or {@code null} for "every card"
     */
    public LevelServer(LevelDef def, List<Identifier> selectedSlots, SeedContext seedContext,
                       List<Identifier> selectedBuffs, java.util.function.Predicate<Identifier> ownsCard) {
        // Cast because the sixth argument is otherwise ambiguous between "no side chosen" and "no
        // fixed random seed", and the two nulls mean genuinely different things.
        this(def, selectedSlots, seedContext, selectedBuffs, ownsCard, (Identifier) null);
    }

    /**
     * Creates a level for a player who has said which side they are on.
     *
     * <p>A level with more than one playable side is a real question ({@code
     * LevelDef.playableTeams}), and the answer has to reach the constructor rather than be applied
     * afterwards: the side decides the seat, the card bar and the win condition, and a level built
     * for the plant side and then re-seated would have to rebuild all three. {@code null} means
     * "nobody was asked", which is the level's own first playable side.
     *
     * @param humanTeam the side the player chose, or {@code null} for the level's default
     */
    public LevelServer(LevelDef def, List<Identifier> selectedSlots, SeedContext seedContext,
                       List<Identifier> selectedBuffs,
                       java.util.function.Predicate<Identifier> ownsCard, Identifier humanTeam) {
        this(def, selectedSlots, seedContext, selectedBuffs, ownsCard, null, humanTeam);
    }

    /**
     * Creates a level with a fixed random seed; see the constructor below for the team.
     *
     * <p>Package-private because the seed is a test's business: a run's own randomness comes from
     * the level, and the only caller that wants to pin it is a test that has to see the same board
     * twice.
     */
    LevelServer(LevelDef def, List<Identifier> selectedSlots, SeedContext seedContext,
                List<Identifier> selectedBuffs, java.util.function.Predicate<Identifier> ownsCard,
                long randomSeed, Identifier humanTeam) {
        this(def, selectedSlots, seedContext, selectedBuffs, ownsCard, Long.valueOf(randomSeed),
                humanTeam);
    }

    private LevelServer(LevelDef def, List<Identifier> selectedSlots, SeedContext seedContext,
                        List<Identifier> selectedBuffs, java.util.function.Predicate<Identifier> ownsCard,
                        Long randomSeed) {
        this(def, selectedSlots, seedContext, selectedBuffs, ownsCard, randomSeed, null);
    }

    private LevelServer(LevelDef def, List<Identifier> selectedSlots, SeedContext seedContext,
                        List<Identifier> selectedBuffs, java.util.function.Predicate<Identifier> ownsCard,
                        Long randomSeed, Identifier requestedTeam) {
        if (randomSeed != null) random.setSeed(randomSeed);
        this.def = def;
        this.ownsCard = ownsCard == null ? card -> true : ownsCard;
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
        // Before the sun clock and the wave director read anything: both of them read a rule the
        // tier scales, and a first wave paced by the unwound value would arrive on the original's
        // schedule for one wave.
        this.difficulty = seedContext == null
                ? com.pvzce.common.level.Difficulty.DEFAULT : seedContext.difficulty();
        applyDifficulty();
        this.sunDropClock.reset(this.rules);
        // Two ways a level's waves can be generated, and they share everything below: the endless
        // levels ask their schedule for round N, and a rhythm level asks the same generator for the
        // round its song is at (see `RhythmWaves`). A level with neither reads its own table.
        this.rhythmChart = com.pvzce.common.level.mechanic.RhythmMechanic.spawningChart(def);
        // A rhythm level's schedule is the mode's own rather than one the level names: the chart
        // carries the coefficient, and the roster is shared by all four tiers.
        this.endlessSchedule = rhythmChart != null
                ? BuiltInRegistries.ENDLESS_SCHEDULES.get(PvzceIds.ENDLESS_SCHEDULE_RHYTHM)
                : com.pvzce.common.level.mechanic.EndlessMechanic.scheduleOf(def);
        this.rounds = com.pvzce.common.level.mechanic.EndlessMechanic.generatesWaves(def)
                || rhythmChart != null;
        this.waves = new WaveDirector(this, def.waves(), def.waveIntervalEndMultiplier(),
                WavePacingMechanic.of(def), rounds);
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

        // Mutable on purpose: `installMechanic` lets a mutation add one mid-run (the fog it rolls
        // in is the level's own `pvzce:fog`), and a `List.copyOf` here is what that used to trip on.
        this.mechanics = new java.util.ArrayList<>(
                withPlayerMechanics(LevelMechanics.effective(def)));
        Team plantTeam = teams.get(PvzceIds.PLANT_TEAM);
        // Which side the player is on is the level's answer (see `LevelDef.humanTeam`): every
        // level before I, Zombie says the plant team, and a level that lists the zombie team
        // instead is played from the other end. Seated here rather than per packet so the seat,
        // the card bar and the win condition are all the same fact.
        // The player's own answer when there is one, otherwise the level's first playable side.
        // A team the level does not declare is treated as no answer rather than trusted: it would
        // otherwise seat the player on a side that has no team object at all.
        Identifier requested = requestedTeam != null && teams.containsKey(requestedTeam)
                ? requestedTeam : null;
        Identifier humanTeam = requested != null ? requested : def.humanTeam();
        this.humanTeamId = teams.containsKey(humanTeam) ? humanTeam : PvzceIds.PLANT_TEAM;
        Team human = teams.get(this.humanTeamId);
        // The level owns the bar; the card source fills it. A self-dealt level (a conveyor
        // belt) ignores the seed selection, which is why the selection is still passed in:
        // the source decides what to do with it, not this constructor.
        //
        // `plantPlayer` is the *human's* player, which on an ordinary level is the plant side and
        // on I, Zombie is the zombie side. The name is the older fact; the field is who the bar
        // belongs to.
        this.plantPlayer = human != null ? new PvzcePlayer(human) : null;
        this.selectedCards = List.copyOf(selectedSlotsOrDef(selectedSlots));
        this.cardSource = this.plantPlayer != null
                ? LevelMechanics.createCardSource(def, new com.pvzce.server.level.cardsource.CardSource.Context(
                        this.plantPlayer, def, this.selectedCards, random))
                : null;
        // The opponent's own bar, if this level has one: the versus mode hands the AI a deck, and a
        // deck without a bar is a deck without cooldowns - which is exactly how the opponent came to
        // ignore them. Built here beside the human's so both bars are made the same way, and ticked
        // beside it so both recharge at the same rate.
        this.opponentPlayer = buildOpponentBar(def);
        this.mutations = LevelMechanics.has(def, PvzceIds.MECHANIC_MUTATION) && plantTeam != null
                ? new com.pvzce.common.level.mutation.MutationManager(this)
                : null;
        if (human != null) {
            // The budget goes to whoever is playing, not to the plant side: on I, Zombie the sun
            // is what the player spends on zombies, and a level that gave it to the plants would
            // give the player nothing to play with.
            human.putResource(PvzceIds.SUN, def.initialSun());
            for (Identifier slotId : def.slots()) {
                SlotDef slotDef = BuiltInRegistries.SLOT_TYPES.get(slotId);
                if (slotDef != null && slotDef.kind() == SlotDef.Kind.RESOURCE) {
                    human.unlockResource(slotDef.content());
                }
            }
            def.unlockResources().forEach((resource, unlocked) -> {
                if (unlocked) {
                    human.unlockResource(resource);
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
        if (mutations != null) {
            mutations.start();
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
        problems.addAll(LevelValidator.validateWaves(def));
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

    /**
     * The bar this run starts with, when the caller did not resolve one.
     *
     * <p>Two fallbacks, in order. The level's own {@code slots} is the older one and still the
     * ordinary case. The newer one is the versus mode: a versus level's {@code slots} is
     * deliberately empty - its two decks live in the mode's block, because which of them is the
     * player's depends on the side they chose - and a caller that built the level without going
     * through {@code SeedSelection.plan} (a test, the editor's preview, a future entry point) would
     * otherwise hand the player an empty card bar. Reading it here as well as there costs one
     * lookup and makes the level self-sufficient: a versus level always has a bar.
     */
    private List<Identifier> selectedSlotsOrDef(List<Identifier> selectedSlots) {
        List<Identifier> cards = selectedSlots == null ? def.slots() : selectedSlots;
        if (cards == null || cards.isEmpty()) {
            com.pvzce.api.content.VersusData versus =
                    com.pvzce.common.level.mechanic.LevelMechanics.versusData(def).orElse(null);
            if (versus != null) {
                cards = versus.cardsFor(humanTeamId);
            }
        }
        return cards == null ? List.of() : cards;
    }

    /**
     * The wave this level holds at a position in its run.
     *
     * <p>The director's one question about where waves come from, and the whole of what
     * "endless" means to it: an ordinary level answers out of the table it wrote, and an
     * endless one asks its schedule and generates the wave on the spot. Nothing is expanded
     * ahead of time, so the length of an endless run is not bounded by anything a packet or a
     * save file could carry.
     *
     * @param round the round, one-based
     * @param index the wave inside that round, zero-based
     * @return the wave, or {@code null} when the position does not exist
     */
    @Override
    public com.pvzce.api.content.WaveDef waveAt(int round, int index) {
        if (index < 0) {
            return null;
        }
        if (!rounds) {
            // A level with a table has exactly one round, so the round number is a formality:
            // any other value is a caller mistake, and answering it with a wave would hide one.
            return round == 1 && index < def.waves().size() ? def.waves().get(index) : null;
        }
        if (endlessSchedule == null) {
            return null;
        }
        if (index >= wavesInRound(round)) {
            return null;
        }
        com.pvzce.common.level.endless.EndlessWaves.Rows rows = endlessRows();
        if (rhythmChart != null) {
            // The round the caller names is the director's own count of waves sent; what the wave
            // is made of comes from where the song has got to. See `RhythmWaves`.
            return com.pvzce.common.level.rhythm.RhythmWaves.wave(endlessSchedule, rhythmChart,
                    com.pvzce.common.level.mechanic.RhythmMechanic.chartTick(this), index,
                    rows.land(), rows.water(), random);
        }
        return com.pvzce.common.level.endless.EndlessWaves.wave(endlessSchedule, round, index,
                rows.land(), rows.water(), random);
    }

    @Override
    public int wavesInRound(int round) {
        if (!rounds || endlessSchedule == null) {
            return round == 1 ? def.waves().size() : 0;
        }
        if (rhythmChart != null) {
            // A song's waves are not counted out to the player and the director only needs "is
            // there another one"; the rollover is silent (see `tickRoundClear`).
            return com.pvzce.common.level.rhythm.RhythmWaves.WAVES_PER_ROUND;
        }
        return com.pvzce.common.level.endless.EndlessRamp.wavesInRound(endlessSchedule, round);
    }

    /**
     * True when this level's waves should not be counted on screen: no meter, no "wave 3 of 9".
     *
     * <p>A song's zombies are not a list the player is working through - they are the weather for
     * the three minutes they are playing - and a meter with a denominator would invite them to
     * read the end of the track as "nine waves to go". The huge-wave warning still travels: it is
     * not a count, it is "something big is coming".
     */
    public boolean showsWaveCount() {
        return rhythmChart == null;
    }

    /**
     * The board's rows split into "walkers" and "floaters".
     *
     * <p>From the level's own scene rather than from a constant: the day pool has water in its
     * middle two rows, a front-lawn endless has none, and the same generator serves both. The
     * water rows are the ones whose scene element is the water one - the same test the rest of
     * the simulation uses to decide whether a plant may go there.
     *
     * <p>Computed on demand rather than cached: a mutation can turn a lawn row into water under a
     * running level (and back again), so a cached answer would be a stale one. The whole board is a
     * few dozen cells, and this is asked once per wave and once per zombie spawn.
     */
    private com.pvzce.common.level.endless.EndlessWaves.Rows endlessRows() {
        return resolveEndlessRows();
    }

    /** True when any cell of {@code row} is water - what makes a row one of "the water rows". */
    public boolean rowIsWater(int row) {
        if (scene == null) {
            return false;
        }
        for (int x = 0; x < def.width(); x++) {
            SceneElementDef element = scene.get(x, row);
            // The surface class, not the id: "is this row water" is a question about what the
            // terrain *is*, and a mutation that floods a lawn with its own water element
            // (`pvzce:flood_water`, which has per-cell art where the pool's has a liquid pass) is
            // water for every rule that reads this - planting, spawning and swimming alike.
            if (element != null && PvzceIds.SURFACE_WATER.equals(element.surfaceClass())) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when any cell of {@code row} is the zamboni's ice - what makes a row one a bobsled
     * team may be dealt into.
     *
     * <p>Asked of the terrain, like {@link #rowIsWater}, because that is where the ice is: the
     * zamboni's trail is a scene element it writes as it drives, so a lane iced halfway through
     * a level becomes sleddable the moment the first cell of it freezes. Water does not count -
     * a bobsled on the pool is a bobsled in the water - and neither does anything else a pack
     * may paint, which is why this is the element's own id rather than a surface class.
     */
    public boolean rowHasIce(int row) {
        if (scene == null) {
            return false;
        }
        for (int x = 0; x < def.width(); x++) {
            SceneElementDef element = scene.get(x, row);
            if (element != null && PvzceIds.ICE.equals(element.id())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The lane the run's opening zombie has to arrive in, or -1.
     *
     * <p>The rake's lane, while that rake is still lying there. It is one of the original's small
     * guarantees - the rake is laid in the lane the first zombie comes down, so a bought rake is
     * never a purchase that silently did nothing - and it is answered from the rig rather than
     * derived from the level file because which lane the rake landed in is decided at level
     * creation, from the level's own dice.
     *
     * <p>The caller still checks that the zombie can use the lane; this only answers where the rake
     * is.
     */
    @Override
    public int forcedOpeningLane(Identifier zombieId) {
        for (TypedMechanic mechanic : mechanics) {
            if (PvzceIds.MECHANIC_RAKE.equals(mechanic.type())
                    && mechanic.value() instanceof com.pvzce.api.content.RakeData rake) {
                return RakeMechanic.rig(this, rake).armedLane();
            }
        }
        return -1;
    }

    /** The rows a zombie that cannot swim may arrive in; every row when the board has no water. */
    public List<Integer> landRows() {
        List<Integer> land = new ArrayList<>();
        for (int y = 0; y < def.height(); y++) {
            if (!rowIsWater(y)) {
                land.add(y);
            }
        }
        if (land.isEmpty()) {
            // A board that is water from edge to edge: there is no land lane to send a walker to,
            // so the caller gets the whole board and the zombie drowns. That is the honest outcome
            // for a level that is nothing but pool - it is a level-authoring error, not something
            // to paper over by quietly spawning the zombie somewhere the author did not ask for.
            for (int y = 0; y < def.height(); y++) {
                land.add(y);
            }
        }
        return land;
    }

    /**
     * The row a zombie of this definition should actually arrive in.
     *
     * <p>A swimmer may use any lane; a walker is moved to the nearest land lane instead of being
     * dropped into the pool. Wave spawns start off the right edge and {@link
     * com.pvzce.api.entity.Entity#gridX()} clamps that to the last column, so a walker placed in a
     * water row drowned on its very first tick - "it spawns on the water and dies before it
     * appears", which is what players reported. The lane is redirected rather than the spawn
     * refused, because a refused spawn leaves the wave owing a zombie forever.
     *
     * <p>This is the backstop for <em>every</em> spawn path, not just the wave director: a mutation
     * that conjures a zombie in a random lane, a boss phase that summons in a random lane, a
     * dancer's escort, an imp thrown at a fixed offset - all of them arrive here, and none of them
     * knows which rows are water.
     *
     * <p>The bobsled's lane is corrected here for the same reason and in the same shape: a sled is
     * only a sled on ice (see {@code BobsledCapability}), so a lead that was aimed at a bare lane
     * is moved to the nearest iced one. A board with no ice at all keeps the lane it was given -
     * the spawn is not refused, because a wave that is owed a zombie must not lose it, and the
     * wave director is where "there is nowhere to sled, so send ordinary zombies instead" is
     * decided (see {@code WaveDirector.spawnQueued}).
     */
    public int spawnRowFor(ZombieDef def, int row) {
        if (def == null) {
            return row;
        }
        int resolved = row;
        if (!def.canSwim() && !def.spawnsAirborne() && rowIsWater(resolved)) {
            resolved = nearestRow(resolved, landRows());
            LOGGER.debug("A zombie that cannot swim may not walk in water: lane {} becomes {}",
                    row, resolved);
        }
        if (def.capability(com.pvzce.common.capability.zombie.BobsledCapability.class).isPresent()
                && !rowHasIce(resolved)) {
            List<Integer> iced = new ArrayList<>();
            for (int y = 0; y < this.def.height(); y++) {
                if (rowHasIce(y)) {
                    iced.add(y);
                }
            }
            if (!iced.isEmpty()) {
                resolved = nearestRow(resolved, iced);
                LOGGER.debug("A bobsled may only be dealt onto ice: lane {} becomes {}",
                        row, resolved);
            }
        }
        return resolved;
    }

    /** Whichever of {@code candidates} is closest to {@code row}; the first when tied. */
    private static int nearestRow(int row, List<Integer> candidates) {
        int best = candidates.get(0);
        for (int candidate : candidates) {
            if (Math.abs(candidate - row) < Math.abs(best - row)) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Builds the row split from the scene.
     *
     * <p>Pure, and asked afresh every time: the scene is not fixed for the life of a level (a flood
     * mutation rewrites it), and the wave director reads round one's length while the level is still
     * being constructed - so there is no moment at which caching this would be both correct and
     * cheap.
     */
    private com.pvzce.common.level.endless.EndlessWaves.Rows resolveEndlessRows() {
        List<Integer> land = new ArrayList<>();
        List<Integer> water = new ArrayList<>();
        for (int y = 0; y < def.height(); y++) {
            (rowIsWater(y) ? water : land).add(y);
        }
        if (water.isEmpty()) {
            // A board with no water rows at all: every row is land, which is what a lawn endless
            // wants and what a pool endless cannot be (its floaters would have nowhere to walk).
            land.clear();
            for (int y = 0; y < def.height(); y++) {
                land.add(y);
            }
        }
        return new com.pvzce.common.level.endless.EndlessWaves.Rows(land, water);
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

    /** The opponent's card bar, or {@code null} when this level has no second deck. */
    public PvzcePlayer opponentPlayer() {
        return opponentPlayer;
    }

    /**
     * Builds the bar the AI plays from, from the versus block's own card list for its side.
     *
     * <p>Nothing else in the level creates one: a level without a versus block has no opponent with a
     * deck (the original's levels spawn zombies from a wave table), and a versus level whose second
     * side is the human needs none either.
     */
    private PvzcePlayer buildOpponentBar(LevelDef def) {
        if (humanTeamId == null || teams.get(PvzceIds.PLANT_TEAM) == null
                || teams.get(PvzceIds.ZOMBIE_TEAM) == null) {
            return null;
        }
        Identifier opponentTeam = PvzceIds.PLANT_TEAM.equals(humanTeamId)
                ? PvzceIds.ZOMBIE_TEAM : PvzceIds.PLANT_TEAM;
        java.util.Optional<com.pvzce.api.content.VersusData> versus =
                LevelMechanics.versusData(def);
        if (versus.isEmpty()) {
            return null;
        }
        List<Identifier> deck = versus.get().cardsFor(opponentTeam);
        if (deck.isEmpty()) {
            return null;
        }
        Team owner = teams.get(opponentTeam);
        if (owner == null) {
            return null;
        }
        PvzcePlayer player = new PvzcePlayer(owner);
        player.replaceSlots(PvzcePlayer.deckSlots(deck));
        return player;
    }

    /**
     * Charges a card: its cooldown, its price and one of its uses. Answers {@code null} when it went
     * through, or the message to show the player when it did not.
     *
     * <p>One method because it is one rule. The human's zombie placements and the opponent's decisions
     * both come through here, so "a card recharges after it is played" cannot be true of one of them
     * and false of the other - which is what the AI ignoring cooldowns was.
     */
    public String chargeCard(Team owner, Slot slot) {
        if (slot == null) {
            return "无效的卡槽。";
        }
        if (!slot.ready()) {
            return "卡片冷却中。";
        }
        int cost = slot.costSun();
        if (cost > 0 && !owner.consume(PvzceIds.SUN, cost)) {
            return "阳光不足！";
        }
        if (slot.usesLeft() != Slot.UNLIMITED_USES) {
            slot.consumeUse();
        }
        slot.startCooldown(effectiveCooldownTicks(slot));
        return null;
    }

    /**
     * Gives back what {@link #chargeCard} took: one use and the recharge.
     *
     * <p>For a placement the board refused after the charge. The cooldown is cleared rather than left
     * running because the card was ready when it was spent - the charge is what made it busy.
     */
    public void restoreCard(Slot slot) {
        if (slot == null) {
            return;
        }
        if (slot.usesLeft() != Slot.UNLIMITED_USES) {
            slot.restoreUses(slot.usesLeft() + 1);
        }
        slot.clearCooldown();
    }

    /**
     * The opponent's slot for a card id, or {@code null}.
     *
     * <p>Matched on the slot's own id first and then on its content, because a deck names cards and a
     * bar holds slots: one zombie may have more than one slot in a level that sells two versions of it.
     */
    public Slot opponentSlot(Identifier card) {
        if (opponentPlayer == null || card == null) {
            return null;
        }
        for (Slot slot : opponentPlayer.slots()) {
            if (slot.defId().equals(card)) {
                return slot;
            }
        }
        for (Slot slot : opponentPlayer.slots()) {
            if (slot.defId().path().equals(card.path())) {
                return slot;
            }
        }
        return null;
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

    /**
     * The versus block this running level declared, or {@code null} for every other level.
     *
     * <p>Read from the level's own mechanic list rather than from its definition, so a mutation that
     * installed or replaced the block is what the running level reports.
     */
    public com.pvzce.api.content.VersusData versusData() {
        return LevelMechanics.versusData(mechanics).orElse(null);
    }

    /** The versus mode's opponent; built on first use, so ordinary levels never pay for it. */
    public com.pvzce.server.ai.JevBrain jevBrain() {
        if (jevBrain == null) {
            jevBrain = new com.pvzce.server.ai.JevBrain();
        }
        return jevBrain;
    }

    public com.pvzce.common.jev.AiSettings jevSettings() {
        return jevSettings;
    }

    /**
     * Hands this run the credential its opponent should be reached with.
     *
     * <p>Called when the level is created and whenever the player edits their settings, so a key
     * pasted mid-run starts working on the next decision instead of on the next level. An
     * unconfigured value is a real setting (it is how a player says "use the built-in opponent"),
     * which is why it is not ignored here - the caller decides where a value came from.
     */
    public void setAiSettings(com.pvzce.common.jev.AiSettings settings) {
        this.jevSettings = settings == null ? com.pvzce.common.jev.AiSettings.NONE : settings;
    }

    public com.pvzce.common.jev.AiSettings commanderSettings() {
        return commanderSettings;
    }

    /** See {@code AiSettingsC2S}: whether the strategist plays the move itself at the door. */
    public boolean commanderTakesOverAtTheDoor() {
        return commanderTakesOverAtTheDoor;
    }

    public void setCommanderTakesOverAtTheDoor(boolean value) {
        this.commanderTakesOverAtTheDoor = value;
    }

    /** The strategist tier's endpoint, if the player configured one. */
    public void setCommanderSettings(com.pvzce.common.jev.AiSettings settings) {
        this.commanderSettings = settings == null ? com.pvzce.common.jev.AiSettings.NONE : settings;
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
    public float zombieSpeedMultiplier(ZombieEntity zombie) {
        float factor = 1F;
        for (TypedMechanic typed : mechanics) {
            factor *= LevelMechanics.zombieSpeedMultiplier(typed, this, zombie);
        }
        return factor;
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

    /**
     * The id of the cell's scene element, or {@code null} off the board.
     *
     * <p>For the callers that only want to ask "is this cell a vase / a gravestone", which is a
     * question about the element's id and nothing else. {@link #sceneAt} hands out the definition
     * object, so each of them would otherwise repeat the null check and the {@code id()} call.
     */
    public Identifier sceneIdAt(int x, int y) {
        SceneElementDef element = scene.get(x, y);
        return element == null ? null : element.id();
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
     * The plant a zombie in this cell would bite, or {@code null} when there is nothing to eat.
     *
     * <p>{@link #plantAt} plus one rule: a plant tagged {@code #c:walk_over} is not food. The
     * spikeweed is the only one, and without this a zombie that stepped on it would stop and eat
     * it - which is the opposite of what a spikeweed is. Tools deliberately keep using
     * {@code plantAt}: a spikeweed is a plant, and the shovel and the watering can must still find
     * it.
     */
    public com.pvzce.server.entity.PlantEntity biteTargetAt(int column, int row) {
        List<com.pvzce.server.entity.PlantEntity> plants = plantsBottomFirst(column, row);
        for (int i = plants.size() - 1; i >= 0; i--) {
            com.pvzce.server.entity.PlantEntity plant = plants.get(i);
            if (!plant.occupiesCell()) {
                continue;
            }
            if (com.pvzce.common.core.PlantPlacement.is(plant.def(), PvzceTags.WALK_OVER)) {
                continue;
            }
            return plant;
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
                && (mutations == null || mutations.canPlacePlant(def, x, y))
                && PlantPlacement.canPlace(def, placementContext, x, y);
    }

    /**
     * Whether the zombie side may spend a card on this cell.
     *
     * <p>The mirror of {@link #canPlacePlant} and deliberately much shorter: a zombie is not placed
     * <em>on</em> anything, so there is no terrain or stacking to consult - only the level's own
     * zombie zone. It is the one gate the player's placement and the opponent's decisions share, so
     * "zombies only on the right four columns" cannot be a rule one of them follows.
     */
    public boolean canPlaceZombie(int x, int y) {
        return inBounds(x, y) && LevelMechanics.canPlaceZombie(mechanics, this, x, y);
    }

    /** True when the level's plantable area covers this column at all; asked by the plant AI. */
    public boolean plantZoneContains(int x) {
        com.pvzce.api.content.PlacementZone zone = LevelMechanics.zoneOf(mechanics,
                PvzceIds.MECHANIC_PLACEMENT_ZONE);
        return x >= zone.minX() && x <= zone.maxX();
    }

    /** The zombie side's twin of {@link #plantZoneContains}. */
    public boolean zombieZoneContains(int x) {
        com.pvzce.api.content.PlacementZone zone = LevelMechanics.zoneOf(mechanics,
                PvzceIds.MECHANIC_ZOMBIE_ZONE);
        return x >= zone.minX() && x <= zone.maxX();
    }

    /**
     * True while the level is holding its waves for a preparation phase.
     *
     * <p>Asked every tick from the tick loop, so it reads the mechanic's own state rather than a
     * copy: one answer to "are the waves being held", owned by the mechanic that holds them.
     */
    public boolean isPreparing() {
        return com.pvzce.common.level.mechanic.PreparationMechanic.isPreparing(this);
    }

    /** Starts this level's preparation phase. Called by the mechanic when the level is built. */
    public void beginPreparation() {
        com.pvzce.common.level.mechanic.PreparationMechanic.begin(this);
    }

    /**
     * Ends the preparation phase and lets the waves run.
     *
     * <p>The player's "开始" button and the mechanic's own countdown both land here, so a level
     * that is started by hand and one that starts itself cannot drift apart in what starting
     * means.
     */
    public void beginWaves() {
        if (!isPreparing()) {
            return;
        }
        com.pvzce.common.level.mechanic.PreparationMechanic.end(this);
        // The second clock the level's music may be written against, and the one a rhythm level's
        // song is: every cue that says `waves_start` counts from here, which is the same tick the
        // chart anchors itself on - so the two cannot drift by however long the player spent
        // arranging the lawn.
        wavesStartTick = tickCount;
        LOGGER.debug("Preparation phase over at tick {}, waves released", tickCount);
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
    public List<com.pvzce.server.entity.ProjectileEntity> projectilesInCell(int column, int row) {
        List<com.pvzce.server.entity.ProjectileEntity> found = new ArrayList<>();
        for (PvzceEntity entity : entities) {
            if (entity instanceof com.pvzce.server.entity.ProjectileEntity shot
                    && !shot.isRemoved()
                    && shot.gridX() == column && shot.gridY() == row) {
                found.add(shot);
            }
        }
        return found;
    }

    @Override
    public List<ZombieEntity> enemiesOf(Team team) {
        List<ZombieEntity> found = new ArrayList<>();
        for (PvzceEntity entity : entities) {
            if (entity instanceof ZombieEntity zombie && zombie.isAlive()
                    && isEnemyOf(zombie.team(), team)) {
                found.add(zombie);
            }
        }
        return found;
    }

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
     * The living zombies' ids, in level order.
     *
     * <p>{@link WaveDirector}'s resume path: a stockpile wave counts its own zombies, entity ids
     * do not survive a process, and the only remaining answer to "how full is the lawn" is the
     * lawn itself.
     */
    @Override
    public List<Integer> livingZombieIds() {
        List<Integer> ids = new ArrayList<>();
        for (PvzceEntity entity : entities) {
            if (entity instanceof ZombieEntity zombie && zombie.isAlive()) {
                ids.add(zombie.id());
            }
        }
        return ids;
    }

    /**
     * What one wave's own zombies still have left to chew through, armour included.
     *
     * <p>{@link WaveDirector}'s health drain asks this every tick a countdown is running, so it is
     * a walk of the entity list with an owner test rather than a per-wave total kept in step: the
     * owner map already exists for the stockpile cap, and a second index that could disagree with
     * it is exactly the kind of state this class does not need.
     *
     * @param waveKey the wave being asked about, as {@code WaveDirector.waveKey}
     * @return the total, or {@code -1} when this level cannot answer - which switches the drain off
     */
    @Override
    public int waveHealth(int waveKey) {
        return waves.healthOfWave(waveKey);
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
        // Before the entity exists: a mutation that rewrites what a card plants has to rewrite
        // the definition, because the capabilities are built from it in the constructor and
        // "the same plant with another behaviour" is not something an entity can be told later.
        PlantDef planted = def;
        if (mutations != null) {
            PlantDef replacement = mutations.replacePlantedPlant(def, x, y);
            if (replacement != null) {
                planted = replacement;
            }
        }
        PlantEntity plant = spawnPlantInternal(planted, team, x, y);
        if (mutations != null) {
            mutations.onPlantPlaced(plant);
        }
        return plant;
    }

    /**
     * Puts a card on the player's bar at runtime.
     *
     * <p>The vase's other half: a plant card that was put inside one comes back out of it, and
     * "comes back out" means it is playable again. Until this existed the bar was built once, at
     * level start, from the level's cards and the player's choice - there was no way for anything
     * that happened <em>during</em> a run to hand the player another card.
     *
     * <p>A card the bar already holds is <b>stacked rather than duplicated</b>: two slots with the
     * same id would be two cards the player cannot tell apart, and the bar's own index rules -
     * which a belt and a save both read - assume one slot per card. So a second vase holding a
     * peashooter gives the peashooter card another use, which is what "you have another one of
     * these" means on a bar that tracks uses.
     *
     * @return true when the bar changed
     */
    public boolean addCardToBar(Identifier cardId) {
        if (plantPlayer == null || cardId == null) {
            return false;
        }
        com.pvzce.common.core.SlotResolver.ResolvedCard resolved =
                com.pvzce.common.core.SlotResolver.resolve(cardId).orElse(null);
        if (resolved == null) {
            return false;
        }
        for (Slot slot : plantPlayer.slots()) {
            if (slot.defId().equals(cardId) && slot.kind() == resolved.kind()) {
                if (slot.usesLeft() != Slot.UNLIMITED_USES && slot.usesLeft() > 0) {
                    slot.restoreUses(slot.usesLeft() + 1);
                }
                send(new SlotSyncS2C(toSlotInfo(slot)));
                return true;
            }
        }
        List<Slot> slots = new ArrayList<>(plantPlayer.slots());
        int index = 0;
        for (Slot slot : slots) {
            index = Math.max(index, slot.index() + 1);
        }
        Slot added = new Slot(index, resolved.kind(), resolved.content(), resolved.costSun(), 0,
                Slot.UNLIMITED_USES, resolved.cooldownTicks());
        slots.add(added);
        plantPlayer.replaceSlots(slots);
        send(new SlotSyncS2C(toSlotInfo(added)));
        return true;
    }

    /**
     * The 3-9 reward: the kelp the player just planted grows a second one beside it.
     *
     * <p>Called from the <em>placement</em> path and not from {@code spawnPlant}, and the
     * difference is not tidiness - it is the difference between one plant and eight. The spread
     * grows its kelp through {@code spawnPlant}, so a hook there would fire again for the kelp the
     * spread just made, and again for that one's neighbour, until the pool was full. The buff says
     * "planting one grows one more"; it does not say "the pool fills itself", which is what the
     * mutation of the same name does on a timer.
     */
    private void spreadKelpFrom(PlantDef planted, int x, int y) {
        if (PvzceIds.TANGLE_KELP.equals(planted.id())
                && com.pvzce.common.buff.LevelBuffs.spreadsKelp(activeBuffs)) {
            com.pvzce.common.level.mutation.KelpSpread.spreadFrom(this, x, y);
        }
    }

    /** The placement itself, past every rewrite: one entity, one {@code onPlaced}. */
    private PlantEntity spawnPlantInternal(PlantDef def, Team team, int x, int y) {
        // The plant's full health, as this level's rules say it at this moment. Passed in rather
        // than left to the definition because the watering can heals to full and the client draws
        // a damaged plant against it - see `PlantEntity`'s own constructor.
        int fullHealth = Math.max(1, Math.round(def.health()
                * rules.getFloat(PvzceIds.RULE_PLANT_HEALTH_MULTIPLIER)));
        PlantEntity plant = new PlantEntity(def, team, x, y, fullHealth);
        plant.setGridBounds(width(), height());
        if (plantsAt(x, y).stream().anyMatch(LevelServer::isCarrier)) {
            plant.setCellX(plant.cellX() + PlantPlacement.CARRIER_X_OFFSET);
        }
        plant.setHeight(PlantPlacement.placementHeight(placementContext, x, y));
        plant.setActionSpeedMultiplier(rules.getFloat(PvzceIds.RULE_PLANT_ACTION_SPEED_MULTIPLIER));
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
        if (mutations != null) {
            mutations.onProjectileFired(shot.projectile());
        }
    }

    /**
     * A shot fired by a zombie, travelling left.
     *
     * <p>No {@code scaledShot} and no mutation hook: the buff that lengthens a plant's shots is the
     * player's, and the mutation that substitutes peas is about what the <em>plants</em> fire. A
     * ZomBotany zombie's pea is the plain one, which is also what makes it readable - the player
     * can tell whose shot is whose by where it came from.
     */
    @Override
    public void spawnZombieProjectile(ProjectileRef ref, float x, float y,
                                      com.pvzce.server.entity.ZombieEntity source) {
        ProjectileDef projectileDef = BuiltInRegistries.PROJECTILES.get(ref.projectile());
        if (projectileDef == null) {
            return;
        }
        addEntity(new ProjectileEntity(projectileDef, ref, source.team(), x, y, source.height()));
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
        if (mutations != null) {
            mutations.onProjectileFired(shot.projectile());
        }
    }

    /**
     * Every resource drop within a radius of a point, nearest first.
     *
     * <p>For the gold magnet, which needs to see the lawn the way the player does. A plant
     * capability has no way to enumerate drops - {@code LevelAccess} has no such accessor, and
     * adding one would put "what a drop is" into the API surface every mod compiles against - so
     * the collector asks the level, which owns the entity list.
     */
    public List<com.pvzce.server.entity.ResourceDropEntity> resourceDropsInReach(
            float centerX, float centerY, float radius) {
        List<com.pvzce.server.entity.ResourceDropEntity> found = new ArrayList<>();
        List<Float> distances = new ArrayList<>();
        for (PvzceEntity entity : entities) {
            if (!(entity instanceof com.pvzce.server.entity.ResourceDropEntity drop)
                    || drop.isRemoved() || drop.collected()) {
                continue;
            }
            float dx = drop.cellX() - centerX;
            float dy = drop.cellY() - centerY;
            float distance = dx * dx + dy * dy;
            if (distance > radius * radius) {
                continue;
            }
            // Sorted on the way out rather than left in entity order: the caller collects the first
            // one it is allowed to, and "the nearest coin" is the one the player saw the magnet
            // reach for.
            int at = 0;
            while (at < distances.size() && distances.get(at) <= distance) {
                at++;
            }
            distances.add(at, distance);
            found.add(at, drop);
        }
        return found;
    }

    /**
     * Collects one drop on a plant's behalf, through every rule the player's own click goes through.
     *
     * <p>{@code silent} and {@code auto} are the pair the auto-pickup buff already uses: the receipt
     * is not printed (the magnet does this dozens of times a level) and a refusal is not answered
     * (nobody asked). Everything else is the same code, which is the point - a gold magnet that
     * collected a resource the level had not unlocked would be a second, weaker set of rules.
     *
     * @return {@code true} when the drop was actually credited
     */
    public boolean autoCollectDrop(com.pvzce.server.entity.ResourceDropEntity drop) {
        if (drop == null || drop.isRemoved() || drop.collected()) {
            return false;
        }
        // Through the outbound bridge, because a plant's tick has no player action to ride on:
        // `collectResourceInternal` answers the collect animation with three packets, and without
        // a bridge the coin would vanish from the server's books while still lying on the lawn.
        ServerBridge target = bridge != null ? bridge : outbound;
        if (target == null) {
            return false;
        }
        // The drop's own team collects it: a gold magnet cannot pick up the other side's sun, and a
        // versus level's AI-driven plant side collects exactly the sun that is its own.
        return collectResourceFor(target, drop.id(), true, true, drop.team());
    }

    /**
     * Launches a shot at a cell rather than at a zombie: the cob cannon's shot.
     *
     * <p>The only shot in the game that is aimed at the ground. Everything else in this class
     * resolves its target by looking for a zombie, because everything else fires by itself; a
     * hand-aimed shot has no zombie to name, and the blast that follows is the projectile's own
     * (see {@code pvzce:splash} with {@code square: true}).
     *
     * <p>The landing height comes from the scene at the target cell, so a cob aimed into the pool
     * lands on the water and one aimed at a crater lands in the crater - the same lookup a lobbed
     * shot does for the zombie it is homing on.
     */
    public void launchAimedProjectile(com.pvzce.api.util.Identifier projectileId, int damage,
                                      PlantEntity source, int gridX, int gridY) {
        if (source == null || !inBounds(gridX, gridY)) {
            return;
        }
        com.pvzce.api.content.ProjectileDef projectileDef =
                BuiltInRegistries.PROJECTILES.get(projectileId);
        if (projectileDef == null) {
            LOGGER.warn("A plant fired '{}', which is not a registered projectile", projectileId);
            return;
        }
        com.pvzce.api.content.ProjectileRef ref = new com.pvzce.api.content.ProjectileRef(
                projectileId, damage, 1, 0, false, 0,
                com.pvzce.api.content.ProjectileRef.UNLIMITED_RANGE, 0, 0, false);
        com.pvzce.api.content.ProjectileRef shot = scaledShot(ref, source);
        float targetX = gridX + 0.5F;
        SceneElementDef base = sceneAt(gridX, gridY);
        float targetHeight = base == null ? 0F : base.heightAt(targetX, width());
        addEntity(new ProjectileEntity(projectileDef, shot, source.team(),
                source.cellX() + com.pvzce.common.capability.plant.PlantShots.MUZZLE_OFFSET_X, source.cellY(),
                source.height(),
                new ProjectileEntity.Aim(targetX, targetHeight)));
        if (mutations != null) {
            mutations.onProjectileFired(shot.projectile());
        }
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
        ProjectileRef scaled = com.pvzce.common.capability.plant.PlantShots.scaled(ref, source, this);
        // The same helper the shooters aim with - see PlantShots.scaled. Two implementations of
        // this rule is what let the aiming half keep reading the unscaled number.
        return substituteProjectile(scaled);
    }

    /**
     * The bullet a shot really carries, after the mutations that rewrite ammunition.
     *
     * <p>Applied here for the same reason the buff's range multiplier is: this is the one place a
     * projectile is born, so a substitution cannot be bypassed by a plant that fires through a
     * different capability. The damage number stays the shooter's - "this pea is now a fire pea"
     * is about what the bullet <em>is</em>, and a mutation that also rewrote the damage would be
     * two mutations wearing one name.
     */
    private ProjectileRef substituteProjectile(ProjectileRef ref) {
        if (mutations == null) {
            return ref;
        }
        com.pvzce.common.level.mutation.MutationManager mutationManager = mutations;
        com.pvzce.common.level.mutation.ProjectileSubstitution substitution =
                mutationManager.projectileSubstitution();
        if (substitution == null) {
            return ref;
        }
        Identifier replacement = substitution.replacementFor(ref.projectile(), random);
        if (replacement == null || replacement.equals(ref.projectile())) {
            return ref;
        }
        return new ProjectileRef(replacement, ref.damage(), ref.count(), ref.rowOffset(),
                ref.backward(), ref.rows(), ref.range(), ref.burstDelay(), ref.initialDelay(),
                ref.targetRow(), ref.vectorX(), ref.vectorY(), ref.launchHeight());
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
     *
     * <p>{@code healthScale} is the endless round's growth, applied here rather than through a
     * game rule: it belongs to the wave the zombie arrived in, and a level-wide multiplier would
     * be a rule the level rewrites under a running mutation's feet. Ordinary levels pass 1 and
     * get exactly the zombie their definition describes.
     */
    @Override
    public ZombieEntity spawnZombie(Identifier zombieId, float x, int row, float healthScale) {
        return spawnZombie(zombieId, zombieTeam(), x, row, healthScale);
    }

    public ZombieEntity spawnZombie(Identifier zombieId, float x, int row) {
        return spawnZombie(zombieId, zombieTeam(), x, row, 1F);
    }

    public ZombieEntity spawnZombie(Identifier zombieId, Team team, float x, int row) {
        return spawnZombie(zombieId, team, x, row, 1F);
    }

    /** One zombie, with the arriving wave's own health growth applied. */
    public ZombieEntity spawnZombie(Identifier zombieId, Team team, float x, int row,
                                    float healthScale) {
        ZombieDef def = BuiltInRegistries.ZOMBIES.get(zombieId);
        if (def == null) {
            return null;
        }
        // The one place every zombie comes into the world, and therefore the one place that can
        // promise a walker is never dropped into the pool: see spawnRowFor. Callers that picked a
        // lane blind - the wave director's shuffle, a mutation's random row, a boss summon, a
        // dancer's escort - all arrive through here.
        row = spawnRowFor(def, row);
        // The level's own half of "how tough is this zombie", folded into the wave's growth here
        // because this is the one place a zombie is created. Read at spawn and never again: a
        // zombie's health is its health for life, and re-scaling one mid-bite would heal it.
        ZombieEntity zombie = new ZombieEntity(def, team, x, row,
                healthScale * rules.getFloat(PvzceIds.RULE_ZOMBIE_HEALTH_MULTIPLIER));
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

    /**
     * Opens every container the blast covers: the scary pots, and the player's own vases.
     *
     * <p>Both kinds go through the same two methods a click goes through ({@link #smashPot} and
     * {@link #smashVase}), so a pot opened by a blast delivers exactly what it would have delivered
     * to a swing - its card, its zombie, or its sun - and the messages the player sees are the same
     * ones. The footprint is the blast's own, one cell of reach per radius unit, which is the same
     * measure {@link #killPlantsAround} uses for the plants standing there.
     */
    @Override
    public void breakContainers(float centerX, float centerY, float radius) {
        float limit = Math.max(0F, radius);
        for (int x = 0; x < width(); x++) {
            for (int y = 0; y < height(); y++) {
                if (Math.abs(x + 0.5F - centerX) > limit || Math.abs(y + 0.5F - centerY) > limit) {
                    continue;
                }
                if (ScaryPotterMechanic.isPot(this, x, y)) {
                    smashPot(x, y);
                } else if (isVaseAt(x, y)) {
                    smashVase(x, y);
                }
            }
        }
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
    public void damageColumn(com.pvzce.api.content.DamageTypeDef type, int column, int damage,
                             Team sourceTeam) {
        for (PvzceEntity entity : new ArrayList<>(entities)) {
            if (!(entity instanceof ZombieEntity zombie) || !zombie.isAlive()) {
                continue;
            }
            // The zombie's own column, not its position - the same rule the row attack uses, and
            // for the same reason: a zombie straddling a boundary walks in one column or the other.
            if (zombie.gridX() == column && isEnemyOf(zombie.team(), sourceTeam)) {
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
    public void emitMagnetItem(int plantId, Identifier item, float x, float y,
                               int startTick, int pullTicks, int holdTicks) {
        send(new com.pvzce.common.network.packet.MagnetItemS2C(plantId, item.toString(), x, y,
                startTick, pullTicks, holdTicks));
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
        SceneElementDef element = sceneAt(x, y);
        if (element != null) {
            // Through `send`, not straight down the ambient bridge: a mutation rewriting the scene
            // has no ambient bridge at all (see `outbound`).
            send(SceneSyncS2C.of(x, y, element.id().toString()));
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
        ServerBridge target = bridge != null ? bridge : outbound;
        if (target != null) {
            target.send(packet);
        }
    }

    /**
     * The level's mechanics plus the ones the <em>player</em> brings.
     *
     * <p>Exactly one thing is in this category today, and it is worth the method anyway: the rake
     * is a shop item, so whether it exists is a fact about the profile rather than about the level,
     * and the profile is only known here. Adding it to {@code LevelMechanics.effective} instead
     * would have handed a rake to every player who never bought one.
     *
     * <p>The level keeps the last word. Its own block - including one that names no rows at all -
     * means it decided, and this adds nothing; only an absent block falls through to the player's
     * rake. That is the same "absence is a statement" rule the mower and the deck follow.
     */
    private List<TypedMechanic> withPlayerMechanics(List<TypedMechanic> base) {
        if (!seedContext.ownsRake() || declaresMechanic(base, PvzceIds.MECHANIC_RAKE)) {
            return base;
        }
        List<TypedMechanic> withRake = new ArrayList<>(base);
        withRake.add(TypedMechanic.of(PvzceIds.MECHANIC_RAKE, com.pvzce.api.content.RakeData.RANDOM));
        return List.copyOf(withRake);
    }

    private static boolean declaresMechanic(List<TypedMechanic> mechanics, Identifier id) {
        for (TypedMechanic mechanic : mechanics) {
            if (mechanic.type().equals(id)) {
                return true;
            }
        }
        return false;
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
     * Installs a mechanic on a level that is already running.
     *
     * <p>For a mutation that brings a mechanic with it - the fog it rolls in is the level's own
     * {@code pvzce:fog} mechanic, and installing it rather than re-implementing it is what keeps
     * one answer to "where does the fog start" (the mechanic's own tick republishes the span as
     * the retreat buff and the lamps change it). The mechanic runs its {@code onLevelCreated} hook
     * immediately, so the client hears about the new state on the same tick.
     *
     * <p>Idempotent: a level that already declares the mechanic keeps the block it declared, and
     * the mutation's override lives on {@link #setFogOverride} instead.
     *
     * @return true when the mechanic was added
     */
    public boolean installMechanic(com.pvzce.api.content.mechanic.TypedMechanic mechanic) {
        if (mechanic == null) {
            return false;
        }
        for (TypedMechanic existing : mechanics) {
            if (existing.type().equals(mechanic.type())) {
                return false;
            }
        }
        mechanics.add(mechanic);
        LevelMechanics.onLevelCreated(mechanic, this);
        return true;
    }

    /**
     * Takes a runtime-installed mechanic back off.
     *
     * <p>Only for a mechanic {@link #installMechanic} added - a level's own declaration is not a
     * mutation's to remove. The caller is the mutation that installed it, which is why this takes
     * the id rather than an index: the list order is the level's.
     */
    public boolean removeMechanic(Identifier mechanicId) {
        for (int i = 0; i < mechanics.size(); i++) {
            if (mechanics.get(i).type().equals(mechanicId)) {
                mechanics.remove(i);
                return true;
            }
        }
        return false;
    }

    /**
     * The level's {@code pvzce:mutation} block, or {@code null} when it does not mutate.
     *
     * <p>Read by the mutation manager at construction: whether this run also rolls mutations, and
     * which ones the level stages by hand. The block is content, so it is asked of the definition
     * rather than remembered from the last look.
     */
    public com.pvzce.api.content.MutationData mutationData() {
        return LevelMechanics.dataOf(def, PvzceIds.MECHANIC_MUTATION,
                com.pvzce.api.content.MutationData.class).orElse(null);
    }

    /** True when this level has that mechanic installed, declared or added at runtime. */
    public boolean hasMechanic(Identifier mechanicId) {
        return LevelMechanics.has(mechanics, mechanicId);
    }

    /**
     * A mechanic's own run state, or {@code null} when the level has none for it.
     *
     * <p>The reading half of {@link #mechanicState}: a caller that only wants to <em>look</em> at a
     * mechanic's state - a mutation asking "is there a mower rig on this level" - must not create one
     * by asking, which is what {@code mechanicState(id, create)} would do.
     */
    public <T> T mechanicStateOrNull(Identifier mechanicId, Class<T> type) {
        Object state = mechanicState.get(mechanicId);
        return type.isInstance(state) ? type.cast(state) : null;
    }

    /** Writes a mechanic's own run state. The counterpart of {@link #mechanicState}. */
    public void setMechanicState(Identifier mechanicId, Object state) {
        mechanicState.put(mechanicId, state);
    }

    /** Extends the temporary clear period and publishes the new fog boundary. */
    @Override public void blowFog(int ticks) {
        fogBlownUntil = Math.max(fogBlownUntil, tickCount() + ticks);
        com.pvzce.common.level.mechanic.FogMechanic.publish(this);
    }

    /**
     * How much fog this board has right now, and where it starts.
     *
     * <p>The one place the question is answered, because three things have an opinion about it:
     * the level's own {@code pvzce:fog} block, a mutation that rolled the fog in on a lawn that
     * never declared any, and the fog-retreat buff the player brought. Folding them here means the
     * renderer draws one picture and none of the three has to know about the others.
     *
     * <p>Absent everywhere, the answer is "no fog": a span that ends before it starts, so
     * {@code alphaAt} is zero for every column and the client registers nothing to draw.
     */
    public com.pvzce.api.content.FogData fogData() {
        com.pvzce.api.content.FogData base = fogOverride != null
                ? fogOverride
                : com.pvzce.common.level.mechanic.LevelMechanics.fogData(def);
        if (base == null) {
            return NO_FOG;
        }
        base = base.retreatedBy(com.pvzce.common.buff.LevelBuffs.fogRetreat(activeBuffs));
        int left = fogBlownUntil - tickCount();
        float clear = Math.max(0F, Math.min(1F, left / (float) PvzceConstants.BLOVER_FOG_RETURN_TICKS));
        return new com.pvzce.api.content.FogData(
                base.startColumn() + (base.endColumn() - base.startColumn()) * clear,
                base.endColumn(), base.maxAlpha());
    }

    /**
     * A lamp: this circle of the fog is clear for as long as the plant stands.
     *
     * <p>Keyed by the plant's entity id, so a lamp that is eaten takes back its own light and no
     * other's - the alternative, a list the caller removes from by value, breaks the moment two
     * lamps stand at the same place.
     */
    public void addFogReveal(int entityId, float x, float y, float radius, float strength) {
        fogReveals.put(entityId, new FogReveal(entityId, x, y, Math.max(0F, radius),
                Math.max(0F, Math.min(1F, strength))));
    }

    /** Takes a lamp's light back; a no-op for an id that had none. */
    public void removeFogReveal(int entityId) {
        fogReveals.remove(entityId);
    }

    /** Every lamp currently standing, for the folder that decides how dark it is where. */
    public java.util.Collection<FogReveal> fogReveals() {
        return java.util.List.copyOf(fogReveals.values());
    }

    /** One lamp's circle. */
    public record FogReveal(int entityId, float x, float y, float radius, float strength) {
    }

    private final Map<Integer, FogReveal> fogReveals = new java.util.LinkedHashMap<>();

    /**
     * Installs a fog the level did not declare, or clears one with {@code null}.
     *
     * <p>For mutations. It is an override rather than a rewrite of the definition because the
     * definition belongs to the level file and a mutation is a temporary fact about this run: when
     * the mutation is evicted, {@code null} restores exactly what the level asked for, with no
     * copy of it kept anywhere.
     */
    public void setFogOverride(com.pvzce.api.content.FogData fog) {
        this.fogOverride = fog;
    }

    /** The fog a board with none at all reports: a span that draws nothing. */
    private static final com.pvzce.api.content.FogData NO_FOG =
            new com.pvzce.api.content.FogData(0F, 0F, 0F);

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
                              java.util.function.Predicate<Identifier> ownsBuff,
                              boolean ownsRake,
                              com.pvzce.common.level.Difficulty difficulty) {
        public SeedContext {
            pool = List.copyOf(pool);
            lockedSlotIds = List.copyOf(lockedSlotIds);
            buffPool = List.copyOf(buffPool);
            autoBuffs = List.copyOf(autoBuffs);
            activeBuffs = List.copyOf(activeBuffs);
            // ``null`` means "everything is owned", which is what a caller with no backpack wants.
            ownsBuff = ownsBuff == null ? buff -> true : ownsBuff;
            // A caller with no world has no tier either, and the original's is the one that
            // leaves every number in the level file exactly as written.
            difficulty = difficulty == null ? com.pvzce.common.level.Difficulty.DEFAULT : difficulty;
        }

        /** The card half alone; a context built before buffs existed has none of them. */
        public SeedContext(List<SeedOption> pool, List<String> lockedSlotIds, int maxSeedSlots) {
            this(pool, lockedSlotIds, maxSeedSlots, List.of(), 0,
                    PvzceConstants.DEFAULT_BUFF_SLOTS, List.of(), List.of(), null, false,
                    com.pvzce.common.level.Difficulty.DEFAULT);
        }

        /** Everything but the rake; what every caller that predates the shop wants. */
        public SeedContext(List<SeedOption> pool, List<String> lockedSlotIds, int maxSeedSlots,
                           List<SeedOption> buffPool, int maxBuffSlots, int buffSlots,
                           List<Identifier> autoBuffs, List<Identifier> activeBuffs,
                           java.util.function.Predicate<Identifier> ownsBuff) {
            this(pool, lockedSlotIds, maxSeedSlots, buffPool, maxBuffSlots, buffSlots, autoBuffs,
                    activeBuffs, ownsBuff, false, com.pvzce.common.level.Difficulty.DEFAULT);
        }

        /** The same context with the run's buffs filled in, after the caller resolved them. */
        public SeedContext withActiveBuffs(List<Identifier> buffs) {
            return new SeedContext(pool, lockedSlotIds, maxSeedSlots, buffPool, maxBuffSlots,
                    buffSlots, autoBuffs, buffs, ownsBuff, ownsRake, difficulty);
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
                    PvzceConstants.DEFAULT_BUFF_SLOTS, List.of(), List.of(), null,
                    // No rake either. `ownsCard == null` means "every *card* is available", which
                    // is a statement about a pool; a rake is a thing a specific world bought, and a
                    // caller with no profile has bought nothing. Folding the two together handed a
                    // free rake to every test and to the plant AI.
                    false,
                    // And no tier: a caller with no profile plays the original's game.
                    com.pvzce.common.level.Difficulty.DEFAULT);
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
                    profile == null ? List.of() : profile.autoBuffs(), List.of(), buffOwns,
                    profile != null && profile.ownsRake(),
                    profile == null ? com.pvzce.common.level.Difficulty.DEFAULT
                            : profile.difficulty());
        }
    }

    /** Runs an action with an ambient bridge and always restores the previous one. */
    private <T> T withBridge(ServerBridge bridge, java.util.function.Supplier<T> action) {
        ServerBridge previous = this.bridge;
        this.bridge = bridge;
        if (bridge != null) {
            this.outbound = bridge;
        }
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
            // The one place a plant stops existing, and therefore the one place its capabilities
            // can be told. `PlantCapability.onRemoved` was declared with no caller for as long as
            // it existed - a hook nothing dispatches is a hook that silently does nothing, and the
            // plantern's lamp was the first thing that needed it.
            if (entity instanceof PlantEntity plant) {
                for (PlantEntity.Instance instance : plant.capabilityInstances()) {
                    instance.capability().onRemoved(plant, this);
                }
            }
            bridge.send(new EntityDespawnS2C(entity.id()));
        }
        pendingRemove.clear();
    }

    public void tick(ServerBridge bridge) {
        if (bridge != null) {
            // The tick is the one call that happens for the whole life of a run, so it is where the
            // outbound bridge is refreshed: a mutation acting on its own clock sends through it
            // (see `outbound`).
            this.outbound = bridge;
        }
        if (!gameState.equals(GameStateS2C.RUNNING)) {
            return;
        }
        // Between rounds: the last wave of the round is dead, the lawn is clear, and the player
        // is choosing their next cards. Simulating through that would walk the next round's
        // zombies onto a board nobody is watching - and, because the lawn is kept between
        // rounds, it would do it while the player believes the game is paused.
        if (tickRoundClear(bridge)) {
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
            // After the mechanics and before the waves: a mutation that spawns or rewrites
            // something does it on the same board the mechanics just settled, and before the
            // wave director decides what is due this tick.
            if (mutations != null) {
                mutations.tick();
            }
            if (isPreparing()) {
                // The preparation phase: no wave is released and no sun falls, so the sun the player
                // builds with is the sun the level handed over - the whole rule of the phase. The
                // clock is re-sent anyway (the HUD draws it), and the sun clock is *not* ticked, so
                // the first drop after the phase is a full interval away rather than immediate.
                syncWaveAndTime(bridge);
            } else {
                waves.tick();
                syncWaveAndTime(bridge);
                maybeSpawnSun();
            }
            tickScene();
            flushPending(bridge);

            tickEntities(PlantEntity.class, bridge);
            if (envVars.getBoolean(PvzceIds.ENV_PLANT_AI, false)) {
                plantAi.tick(this);
                flushPending(bridge);
            }
            for (PvzceEntity entity : entities) {
                if (entity instanceof PlantEntity plant && !plant.isRemoved()) {
                    plant.syncEchoNetwork(this, bridge, false);
                }
            }
            tickEntities(ZombieEntity.class, bridge);
            tickEntities(ProjectileEntity.class, bridge);
            tickEntities(ResourceDropEntity.class, bridge);
            tickEntities(com.pvzce.server.entity.CardDropEntity.class, bridge);
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
                // The plant's last moment, told to the mutations before it leaves the list: an
                // event hook that fires when the entity is already gone has no position to act
                // on, and the blast a plant leaves behind belongs where it stood.
                if (mutations != null && entity instanceof PlantEntity plant) {
                    mutations.onPlantDied(plant);
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
            float beforeX = entity.cellX();
            int beforeRow = entity.gridY();
            type.cast(entity).tick(this);
            if (entity instanceof ZombieEntity zombie && zombie.isAlive()) {
                com.pvzce.common.level.mechanic.PortalMechanic.Exit exit =
                        com.pvzce.common.level.mechanic.PortalMechanic.cross(
                                this, zombie.id(), beforeX, zombie.cellX(), beforeRow);
                if (exit != null) {
                    zombie.setCellX(exit.x());
                    zombie.setCellY(exit.row() + 0.5F);
                }
            }
        }
        flushPending(bridge);
    }

    /** Recharges the opponent's cards. Silent: no client is watching the AI's bar. */
    private void tickOpponentBar() {
        if (opponentPlayer == null) {
            return;
        }
        for (Slot slot : opponentPlayer.slots()) {
            slot.tick();
        }
    }

    private void syncSlots(ServerBridge bridge) {
        if (plantPlayer == null || cardSource == null) {
            return;
        }
        cardSource.tick(this, bridge, tickCount);
        tickOpponentBar();
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

    /** The mutations this level runs with, or {@code null} for an ordinary level. */
    public com.pvzce.common.level.mutation.MutationManager mutations() {
        return mutations;
    }

    /** The plant cards the player owns, for the mutations that pick replacements. */
    public java.util.function.Predicate<Identifier> ownedCards() {
        return ownsCard;
    }

    /**
     * Hands the player a tool the level did not declare.
     *
     * <p>For a mutation: Whack-a-Zombie's mallet is a {@code tool} mechanic block when a level
     * authors it, and a mutation that wants the same thing mid-run cannot add a block to a
     * definition that is already loaded and shared. This is the same statement made at runtime -
     * "this run grants that tool" - and {@code ToolMechanic.granted} is where the two lists meet.
     */
    public void installTool(com.pvzce.api.content.ToolData tool) {
        if (tool == null || tool.tool() == null) {
            return;
        }
        for (com.pvzce.api.content.ToolData existing : grantedTools) {
            if (tool.tool().equals(existing.tool())) {
                return;
            }
        }
        grantedTools.add(tool);
    }

    /** Takes back a tool {@link #installTool} granted. A tool the level itself declared stays. */
    public void removeTool(Identifier toolId) {
        grantedTools.removeIf(tool -> toolId.equals(tool.tool()));
    }

    /** The tools this run grants that the level's own blocks do not declare, in grant order. */
    public List<com.pvzce.api.content.ToolData> grantedTools() {
        return List.copyOf(grantedTools);
    }

    /**
     * The buffs this run plays with, replaced wholesale.
     *
     * <p>For the mutation that shifts them. Wholesale rather than add/remove because that is what
     * the mutation decides - it rolls "one more" or "one fewer" and hands over the result - and
     * because the level's own pinned buffs have to stay in the list either way.
     */
    public void setActiveBuffs(List<com.pvzce.api.content.LevelBuff> buffs) {
        List<com.pvzce.api.content.LevelBuff> next =
                buffs == null ? List.of() : List.copyOf(buffs);
        boolean changed = !com.pvzce.server.LevelBuffSelection.resolveIds(next)
                .equals(com.pvzce.server.LevelBuffSelection.resolveIds(activeBuffs));
        this.activeBuffs = next;
        // The client draws the icon row from what it was last told, and this is the only thing
        // that ever changes the list mid-level: without the nudge the icons keep showing what the
        // run started with until the next mutation happens to arrive. See
        // MutationManager.buffsChanged.
        if (changed && mutations != null) {
            mutations.buffsChanged();
        }
    }

    /**
     * What kind of bar the player is looking at right now.
     *
     * <p>Asked by the mutation packet and by the client's bar rebuild, so the two cannot
     * disagree: a mutation that takes the bar over changes this answer, and both the panel's
     * "which bar is drawn" bit and the level's own bookkeeping read it rather than each keeping
     * their own idea.
     */
    public String cardSourceKind() {
        com.pvzce.common.level.mutation.MutationManager mutationManager = mutations;
        if (mutationManager != null && mutationManager.cardSourceFactory() != null) {
            return com.pvzce.server.level.cardsource.CardSource.KIND_MUTATED;
        }
        return cardSource == null
                ? com.pvzce.server.level.cardsource.CardSource.KIND_DECK
                : cardSource.kind();
    }

    /**
     * Rebuilds the bar after a mutation took it over or gave it back.
     *
     * <p>Called by {@code MutationManager} at the handoff. Three things have to move together:
     * which source is in force, what is on the bar, and what the client is told - a player whose
     * bar changed without being told keeps clicking cards that no longer exist, and a player
     * whose bar was not rebuilt keeps clicking the belt's cards after it is gone.
     */
    public void onMutationCardSourceChanged() {
        rebuildCardBar();
        syncAllSlots();
    }

    /** Pushes the whole bar to the client, for the moments when it is replaced wholesale. */
    private void syncAllSlots() {
        if (plantPlayer == null) {
            return;
        }
        for (Slot slot : plantPlayer.slots()) {
            send(new com.pvzce.common.network.packet.SlotSyncS2C(toSlotInfo(slot)));
        }
    }

    /** How hard this world plays; the original's own difficulty unless the profile says otherwise. */
    public com.pvzce.common.level.Difficulty difficulty() {
        return difficulty;
    }

    /**
     * Switches the tier, mid-run included.
     *
     * <p>The old tier's factors are divided back out before the new one's go in, because "the
     * value in the rule" is the level's own number times whichever tier is in force - and the
     * level's own number is the thing that must not be lost. Rules that were already read once
     * per entity keep the value they were read with: a zombie that spawned at 1.3x health is
     * 1.3x health, and the next one is whatever the tier says now.
     */
    public boolean setDifficulty(com.pvzce.common.level.Difficulty tier) {
        if (tier == null || tier == difficulty) {
            return false;
        }
        unwindDifficulty();
        difficulty = tier;
        applyDifficulty();
        return true;
    }

    /** Multiplies this level's rules by the tier's factors. Called once per tier change. */
    private void applyDifficulty() {
        for (java.util.Map.Entry<Identifier, Float> factor : difficulty.ruleFactors().entrySet()) {
            rules.set(factor.getKey(), rules.getFloat(factor.getKey()) * factor.getValue());
        }
    }

    /** Divides them back out; the inverse of {@link #applyDifficulty}, and exactly its factors. */
    private void unwindDifficulty() {
        for (java.util.Map.Entry<Identifier, Float> factor : difficulty.ruleFactors().entrySet()) {
            float applied = factor.getValue();
            if (applied > 0F) {
                rules.set(factor.getKey(), rules.getFloat(factor.getKey()) / applied);
            }
        }
    }

    /**
     * Writes one game rule from outside the command layer.
     *
     * <p>For mutations, which are the one thing in the game that rewrites a rule while a level
     * runs: a mutation says "from now on it is night", and it has to be able to say that without
     * a player typing anything. The value is clamped by the rule's own type, exactly as
     * {@code /gamerule} is.
     */
    public boolean setRule(Identifier id, Object value) {
        boolean applied = rules.set(id, value);
        if (applied) {
            onRuleChanged(id);
        }
        return applied;
    }

    /** The rules whose value is read once per plant rather than per tick, refreshed on change. */
    private void onRuleChanged(Identifier id) {
        if (PvzceIds.RULE_PLANT_ACTION_SPEED_MULTIPLIER.equals(id)) {
            float multiplier = rules.getFloat(PvzceIds.RULE_PLANT_ACTION_SPEED_MULTIPLIER);
            for (PvzceEntity entity : entities) {
                if (entity instanceof PlantEntity plant) {
                    plant.setActionSpeedMultiplier(multiplier);
                }
            }
        }
    }

    /** A rule's current value, for the code that has to read one back after writing it. */
    public Object ruleValue(Identifier id) {
        return rules.values().get(id);
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
     * How many times a plant's volley is repeated, or 1 when nothing applies.
     *
     * <p>The rhythm levels' energy bar, read per volley for the same reason the range multiplier is
     * read per shot: the bar crosses its gates while the run is going, and a plant that had the
     * answer baked in when it was planted would keep firing the old number for the rest of the
     * level. One question, one answer, asked of the mechanic that owns the bar.
     */
    @Override
    public int projectileCountMultiplier(PlantEntity plant) {
        if (plant == null) {
            return 1;
        }
        return com.pvzce.common.level.mechanic.RhythmMechanic.projectileCountMultiplier(this);
    }

    /**
     * Picks up resource drops on their own when a buff says so.
     *
     * <p>Runs after the drop entities have ticked, so a drop that is still falling has already
     * moved and its collect animation starts from where the player last saw it.
     */
    private void tickAutoCollect() {
        boolean buffed = !activeBuffs.isEmpty()
                && com.pvzce.common.buff.LevelBuffs.autoCollects(activeBuffs);
        if (!buffed && !bothSidesCollect) {
            // Neither mechanism wants the lawn swept, so nothing is waiting: clearing here is what
            // keeps the timer table the set of drops still waiting rather than every drop that has
            // ever existed. (It is also the reason this cannot be done unconditionally: a versus
            // level has no buff and still collects, and clearing every tick reset the very timers
            // that were about to ripen.)
            if (!autoCollectTimers.isEmpty()) {
                autoCollectTimers.clear();
            }
            return;
        }
        if (buffed) {
            // The buff is the player's own quality-of-life setting, so it picks up the player's own
            // drops: identical to what a click would collect, and nothing for a level where the
            // player owns none of them (I, Zombie's zombie side).
            autoCollectDropsFor(plantPlayer == null ? null : plantPlayer.team());
        }
        if (bothSidesCollect) {
            autoCollectDropsFor(team(PvzceIds.PLANT_TEAM));
            autoCollectDropsFor(team(PvzceIds.ZOMBIE_TEAM));
        }
    }

    /**
     * Whether this run sweeps the lawn for <b>both</b> sides; the versus mode turns it on.
     *
     * <p>The mode's reason is that neither side is necessarily a person: the plant side may be the
     * opponent, which cannot click, and a match where one player clicks and the other is handed its
     * sun is not the same match. Set as a fact about the run, once, at level creation.
     */
    public void setBothSidesCollect(boolean value) {
        this.bothSidesCollect = value;
    }

    /**
     * Picks up one team's resource drops on their own, a quarter of a second after each lands.
     *
     * <p>The versus mode's two sides both ask for this, through the same loop: an AI cannot click,
     * and the mode's own rules say both sides collect automatically. The delay and the rules are the
     * auto-pickup buff's, not a second set - {@code collectResourceFor} is the same door a click
     * goes through, so an auto-collected drop can never skip a rule a clicked one obeys.
     */
    public void autoCollectDropsFor(Team owner) {
        if (owner == null) {
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
            if (!owner.equals(drop.team())) {
                continue;
            }
            boolean ripe = autoCollectTimers.computeIfAbsent(drop.id(), id -> tickCount)
                    + AUTO_COLLECT_DELAY_TICKS <= tickCount;
            if (ripe) {
                // The drop is not removed here - the collect path marks it and the next
                // flushPending takes it off the field - so the id stays out of ``gone``.
                collectResourceFor(bridge != null ? bridge : outbound, drop.id(), true, true, owner);
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
     * The board a run of {@code def} opens on, built once and then thrown away.
     *
     * <p>The authored {@code scene} map is not the opening board. A level's mechanisms write
     * cells while the level is being built - {@code grave_field} scatters its tombstones from
     * the level's own random source, {@code grave_spawner} raises the opening stones - and only
     * the finished grid knows where they landed. The seed chooser is the screen that claims to
     * show "the lawn you are about to play", so it is the one caller that needs them.
     *
     * <p>Building the whole level is deliberate over re-deriving the cells: there is exactly one
     * implementation of "what does this level's opening board look like", and it is the
     * constructor. A second one that scattered graves by the same rules would be free to drift
     * from the run it previews. Nothing here is started - no tick, no bridge, no save - and the
     * caller drops the instance as soon as it has read the cells.
     *
     * <p>The draw is not the draw the player will get: this instance has its own random source,
     * so the tombstones stand somewhere else than they will in the run. That is the level's own
     * design - the layout is redrawn every attempt - and it is why the preview is honest about
     * showing a board of this shape rather than this exact board.
     *
     * @param seeds the chooser's own seed context, so a level whose board depends on the card
     *              source is built the way the chooser describes it
     */
    public static List<SceneSyncS2C.Cell> openingBoard(LevelDef def, SeedContext seeds) {
        LevelServer level = new LevelServer(def, def.slots(), seeds);
        return SceneCells.forGrid(level.sceneIds());
    }

    /**
     * The built board's elements, in the same order {@link #sendFullState} walks them.
     *
     * <p>Read from the grid rather than from the definitions, because half of what stands on a
     * night lawn got there through a mechanic rather than through the level file.
     */
    private SceneGrid<Identifier> sceneIds() {
        SceneGrid<Identifier> ids = SceneGrid.create(width(), height(), null);
        for (int x = 0; x < width(); x++) {
            for (int y = 0; y < height(); y++) {
                SceneElementDef element = scene.get(x, y);
                if (element != null && element.id() != null) {
                    ids.set(x, y, element.id());
                }
            }
        }
        return ids;
    }

    /**
     * The board description a client is given, for the level list and for the level init.
     *
     * <p>One builder because both callers describe the same level: the list hands it to the
     * seed chooser's preview and the init builds the running board from it, and a level that
     * says "I have a belt and the left four columns are plantable" in one of them and not
     * the other would show a screen contradicting the one that follows it.
     *
     * <p>{@code sceneCells} travels as the <strong>opening board</strong>, mechanics included -
     * see {@link #openingBoard}. It used to be the level's authored map, which is why a preview
     * of a night level showed an empty lawn: the tombstones are not in the file.
     *
     * <p>The mechanic list is a parameter, and the running level passes {@link #mechanics()} rather
     * than the file's own list: a level has one more source of mechanics than its file does (the
     * player's rake), and a client that is not told about one runs a board it cannot draw. The
     * level-list caller has no instance and passes the file's answer, which is what a preview
     * needs - there is nothing to preview about a rake whose lane is rolled at level creation.
     */
    public static LevelPayload payloadFor(LevelDef def, SeedContext seeds) {
        return payloadFor(def, seeds, LevelMechanics.effective(def));
    }

    /** As above, with the mechanic list the caller wants the client to know about. */
    public static LevelPayload payloadFor(LevelDef def, SeedContext seeds,
                                          List<TypedMechanic> resolvedMechanics) {
        // The resolved bar size, not the raw field: a level with no max_seed_slots of its
        // own is sized by the backpack, and the payload is where the client learns which.
        return new LevelPayload(def.width(), def.height(), seeds.pool(), seeds.maxSeedSlots(),
                previewZombies(def), openingBoard(def, seeds), seeds.lockedSlotIds(),
                LevelMechanics.payloads(resolvedMechanics),
                // The backdrop travels as its texture id rather than as a field the client
                // looks up in its own copy of the level file: the board it draws has to be the
                // one this server is running, even for a level that client has never seen.
                def.background().map(Identifier::toString).orElse(""),
                def.hiddenSceneElements(), def.disableShaders(),
                // The buffs the chooser may offer and the resolved cap, resolved here for the
                // same reason the card pool is: the client is told what it may pick rather than
                // working it out from its own copy of the level file.
                seeds.buffPool(), seeds.maxBuffSlots(),
                seeds.activeBuffs().stream().map(Identifier::toString).toList(),
                // The one rule the client's placement preview draws (see `plantableRows`).
                // Read from the file's own table rather than from a running level: this method is
                // static, and the level list previews a level that does not exist yet. A run that
                // turns the rule on mid-game with `/gamerule` is the server's business; the preview
                // follows the file, which is what the level list promised.
                def.rules().get(PvzceIds.RULE_PLANT_WHOLE_COLUMN) != null
                        && def.rules().get(PvzceIds.RULE_PLANT_WHOLE_COLUMN).getAsBoolean());
    }

    /**
     * The zombies a level list entry shows, for a level that has no wave table to read them off.
     *
     * <p>{@code LevelDef.previewZombieIds} walks the level's own waves, which is the right answer
     * for a level that wrote them and no answer at all for one whose waves are generated: the
     * endless levels and the rhythm ones both ship with an empty table, so both showed nothing on
     * the one screen that exists to say what a level sends at you. What replaces it is the opening
     * bar - the first round of the schedule, or the first bar of the song - which is the honest
     * preview of a run whose later waves do not exist until it is played.
     */
    private static List<String> previewZombies(LevelDef def) {
        List<String> written = def.previewZombieIds();
        if (!written.isEmpty()) {
            return written;
        }
        List<com.pvzce.api.content.WaveDef> opening;
        com.pvzce.api.content.RhythmChartData chart =
                com.pvzce.common.level.mechanic.RhythmMechanic.spawningChart(def);
        if (chart != null) {
            opening = com.pvzce.common.level.rhythm.RhythmWaves.preview(
                    BuiltInRegistries.ENDLESS_SCHEDULES.get(PvzceIds.ENDLESS_SCHEDULE_RHYTHM),
                    chart, def.height());
        } else {
            opening = com.pvzce.common.level.mechanic.EndlessMechanic.previewWaves(def);
        }
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (com.pvzce.api.content.WaveDef wave : opening) {
            for (com.pvzce.api.content.WaveDef.Entry entry : wave.entries()) {
                ids.add(entry.id().toString());
            }
        }
        return List.copyOf(ids);
    }

    private void processMusicCues(ServerBridge bridge) {
        List<LevelDef.MusicCue> cues = musicCues();
        if (cues.isEmpty() && !levelTracksSettled) {
            levelTracksSettled = true;
            // A level whose music block is empty has no music, and saying nothing is not the same
            // as saying "stop": the client enters a level with the menu and stinger tracks stopped
            // and the background track started on the ordinary theme (`PvzceMusicController.
            // startLevel`), waiting for the first cue to tell it what this level actually plays.
            // With no cue at all, the previous level's theme therefore kept playing over it -
            // measured on 4-10, the game's one silent level, whose whole point is that the rain is
            // the soundtrack. One explicit stop at tick zero is what the empty block means.
            for (String track : LEVEL_TRACKS) {
                bridge.send(new MusicEventS2C(track, "", false, true, 1F, 0.5F, false));
            }
            return;
        }
        levelTracksSettled = true;
        for (int i = 0; i < cues.size(); i++) {
            if ((musicCuesFired & (1L << i)) != 0) {
                continue;
            }
            LevelDef.MusicCue cue = cues.get(i);
            // Two clocks, and a cue on the second one does not exist until the waves do: a level
            // that sits in its preparation phase forever must not fire the song that belongs to
            // the chart (see MusicCue.Trigger).
            int base = cue.trigger() == LevelDef.MusicCue.Trigger.WAVES_START ? wavesStartTick : 0;
            if (base < 0 || tickCount < base + cue.atTick()) {
                continue;
            }
            musicCuesFired |= 1L << i;
            // A cue with no event, or one that stops the track, leaves nothing playing.
            currentMusicCue = cue.stop() || cue.event().isEmpty() ? null : cue;
            bridge.send(new MusicEventS2C(
                    cue.track(),
                    cue.event().map(Identifier::toString).orElse(""),
                    cue.loop(),
                    cue.stop() || cue.event().isEmpty(),
                    Math.max(0F, Math.min(1F, cue.volume())),
                    Math.max(0F, cue.fadeSeconds()), false));
        }
    }

    /**
     * The level's cues in the order the fired-mask indexes them: level-start ones first, each
     * group by its own tick.
     *
     * <p>The order is what makes "the first N cues" mean the same thing to a save written before
     * the mask existed (see {@link #restoreMusicCues}), and it keeps a {@code WAVES_START} cue
     * from ever being jumped over by a {@code LEVEL_START} one.
     */
    private List<LevelDef.MusicCue> musicCues() {
        return def.music().cues().stream()
                .sorted(Comparator
                        .comparing((LevelDef.MusicCue cue) -> cue.trigger()
                                == LevelDef.MusicCue.Trigger.LEVEL_START ? 0 : 1)
                        .thenComparingInt(LevelDef.MusicCue::atTick))
                .toList();
    }

    /**
     * Reads a save's music state: which cues had already fired, and when the waves began.
     *
     * <p>The cue that is playing is replayed rather than saved - "the last one that fired" is
     * exactly what the mask says, and storing the cue itself would be a second copy of the level's
     * own music block, which a data pack edit could leave disagreeing with the first.
     *
     * <p>{@code WavesStartTick} has to be in the save for the same reason the mask does: a run
     * resumed three minutes after 开始 must not answer "when did the waves begin" with the tick it
     * was reloaded on, or every {@code WAVES_START} cue would fire a second time (and a rhythm
     * level's song would start over mid-chart).
     */
    private void restoreMusicCues(CompoundTag root) {
        // No field means a save from before cues had a second clock: those cues were all
        // level-start ones, so the waves clock was never read and zero is the honest answer.
        wavesStartTick = root.contains("WavesStartTick") ? root.getInt("WavesStartTick") : 0;
        // One bit per cue, so sixty-four is the ceiling; a level with more cues than that is
        // already past what a hand-written music block is for, and the extra ones simply fire on
        // the tick they are due instead of being remembered.
        List<LevelDef.MusicCue> cues = musicCues();
        if (root.contains("MusicCuesFired")) {
            musicCuesFired = root.getLong("MusicCuesFired");
        } else {
            int legacy = Math.max(0, root.getInt("NextMusicCueIndex"));
            musicCuesFired = legacy >= Long.SIZE ? -1L : (1L << legacy) - 1L;
        }
        currentMusicCue = null;
        for (int i = 0; i < cues.size() && i < Long.SIZE; i++) {
            if ((musicCuesFired & (1L << i)) == 0) {
                continue;
            }
            LevelDef.MusicCue cue = cues.get(i);
            currentMusicCue = cue.stop() || cue.event().isEmpty() ? null : cue;
        }
    }

    /**
     * The tracks a level's own music can play on: everything except the menu's theme.
     *
     * <p>Listed here rather than in the music controller because this is the server saying "this
     * level has no music", and the client's track names are the wire values ({@code MusicEventS2C})
     * - the two ends share one spelling.
     */
    /**
     * Whether this level has told the client what its music is (or that it has none).
     *
     * <p>Needed because "no music" is an instruction rather than an absence, and it may only be
     * given once per level instance: a re-sent stop would cut the stingers a win or a loss plays.
     */
    private boolean levelTracksSettled;

    private static final List<String> LEVEL_TRACKS = List.of(
            MusicEventS2C.TRACK_BACKGROUND, MusicEventS2C.TRACK_BATTLE, MusicEventS2C.TRACK_STINGER);

    /**
     * Puts an abandoned carry back where it came from.
     *
     * <p>Re-spawning it in place is enough: the plant never left its cell in the world's
     * eyes until the drop, so "put it back" is just "stop carrying it". Nothing is
     * removed, so nothing can be lost.
     */
    private void tickCarry() {
        if (!hasCarry()) {
            return;
        }
        if (carriedVaseFrom >= 0L) {
            // A carried vase has nothing to put back: the cell it came from still holds it, and
            // only the drop writes anything. So an abandoned carry is just forgotten.
            if (--carryTimeoutTicks <= 0) {
                clearCarry();
            }
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
        return waves.waveInRound();
    }

    @Override
    public boolean holdsNextWave() {
        return com.pvzce.common.level.mechanic.PreparationMechanic.holdsNextWave(this);
    }

    public boolean currentWaveFullyReleased() {
        return waves.currentWaveFullyReleased();
    }

    public int totalWaves() {
        // Zero on a level that does not count its waves: the meter is drawn from this, and a song
        // has no denominator. See `showsWaveCount`.
        return showsWaveCount() ? waves.roundWaves() : 0;
    }

    /** The round the run is in, one-based; always 1 on a level that does not generate waves. */
    public int round() {
        return waves.round();
    }

    /** How many waves of the current round have arrived. */
    public int waveInRound() {
        return waves.waveInRound();
    }

    /** How many waves the run has released in total, across every round. */
    public int cumulativeWaves() {
        return waves.cumulativeWaves();
    }

    /** True when this level generates its waves round after round and can never be won. */
    public boolean isEndlessRun() {
        return rounds;
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
        return waves.runFinished();
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
        int iceMelt = rules.getInt(PvzceIds.RULE_ICE_MELT);
        for (int x = 0; x < width(); x++) {
            for (int y = 0; y < height(); y++) {
                SceneElementDef element = scene.get(x, y);
                if (element == null) {
                    continue;
                }
                if (PvzceIds.ICE.equals(element.id())) {
                    // The zamboni's trail: terrain with a clock rather than a permanent scar.
                    // Counted here, on the level, because by the time the ice is old the machine
                    // that made it is usually dead - "how long has this cell been frozen" is a
                    // fact about the lawn, and the lawn is what owns it.
                    if (iceMelt <= 0) {
                        continue;
                    }
                    int key = y * width() + x;
                    if (iceTimers.merge(key, 1, Integer::sum) >= iceMelt) {
                        // The timer is dropped inside `meltIceCell`, which the fire path shares.
                        meltIceCell(x, y);
                    }
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

    /** One cell of ice back to the lawn it was: bare ground again, and the client told. */
    private void meltIceCell(int x, int y) {
        iceTimers.remove(y * width() + x);
        // The same write a broken vase makes, for the same reason: whatever this cell was before
        // the ice (lawn, bare dirt) is what "not ice" means, and `resetSceneCell` already owns
        // that answer plus its scene packet.
        resetSceneCell(x, y);
    }

    /**
     * Melts the ice a fire blast covered, immediately.
     *
     * <p>The cherry bomb's and the jalapeno's second effect, and the original's own answer to a
     * zamboni that has just closed a lane: fire takes the trail back off the lawn, so a lane the
     * machine took is a lane a bomb can re-open. The ice's own clock on that cell is dropped with
     * it - a timer left running under a lawn would melt the *next* ice to land there.
     *
     * <p>Not every blast: {@code melts_ice} is opt-in content, because a potato mine and a
     * jack-in-the-box are explosions too and neither is fire.
     */
    @Override
    public void meltIce(float centerX, float centerY, float radius, boolean square) {
        float limit = square ? radius + 0.5F : radius;
        for (int x = 0; x < width(); x++) {
            for (int y = 0; y < height(); y++) {
                if (Math.abs(x + 0.5F - centerX) > limit || Math.abs(y + 0.5F - centerY) > limit) {
                    continue;
                }
                SceneElementDef element = scene.get(x, y);
                if (element == null || !PvzceIds.ICE.equals(element.id())) {
                    continue;
                }
                meltIceCell(x, y);
            }
        }
    }

    /** The same, for the one explosive whose blast is a whole row rather than a circle. */
    @Override
    public void meltIceRow(int row) {
        if (row < 0 || row >= height()) {
            return;
        }
        for (int x = 0; x < width(); x++) {
            SceneElementDef element = scene.get(x, row);
            if (element != null && PvzceIds.ICE.equals(element.id())) {
                meltIceCell(x, row);
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
        var graves = com.pvzce.common.level.mechanic.LevelMechanics.dataOf(def,
                PvzceIds.MECHANIC_GRAVE_SPAWNER, com.pvzce.api.content.GraveSpawnerData.class).orElse(null);
        if (graves != null && graves.phased()) {
            com.pvzce.common.level.mechanic.GraveSpawnerMechanic.finalBurst(this, graves);
            return;
        }
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
     * Raises a gravestone in a cell, if the cell is free <em>land</em>.
     *
     * <p>Used by the {@code grave_spawner} mechanic. Refused on a cell that already has a
     * gravestone (nothing to do) or a plant (a tombstone may not be dropped on the player's
     * lawn mid-level) - the caller picks another cell rather than this one being replaced.
     *
     * <p>And refused on water, which is the check that took a while to be needed. A gravestone
     * is a thing standing in soil: it is {@code #c:unplantable} terrain whose art is drawn over
     * the lawn beneath it, so a stone raised in a pool lane both punches a hole in the water and
     * takes the cell away from the plants that belong there. Nothing noticed while the only
     * callers were night lawns - they have no water - and then the mutation levels arrived with
     * their opening graves and a pool in the middle of the board, and the stones started landing
     * in it. The rule belongs here rather than in each caller for the reason this method exists
     * at all: "may a tombstone stand here" is one question.
     */
    public boolean placeGrave(Identifier graveElement, int x, int y) {
        if (!inBounds(x, y) || sceneAt(x, y) == null || plantAt(x, y) != null) {
            return false;
        }
        // Land only. `#c:ground` and `#c:plantable` are the pair the placement matrix already
        // reads as "soil, and never water" - the same two the apocalypse mutation asks before
        // it plants its doom-shrooms.
        PlantPlacement.Terrain under = PlantPlacement.Terrain.of(sceneAt(x, y));
        if (!PlantPlacement.terrainTagged(under, PvzceTags.SCENE_GROUND)
                && !PlantPlacement.terrainTagged(under, PvzceTags.SCENE_PLANTABLE)) {
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

    /**
     * Drops a sun from the sky when the level's interval clock says one is due.
     *
     * <p>The clock is ticked before the answer is read, so a level whose rules changed
     * mid-run ({@code /gamerule pvzce:sun_spawn_interval_min 0}) stops dropping on the next
     * tick instead of on the next drop. A level with both ends of the range at zero never
     * reaches the spawn below, which is the off switch the old {@code sun_spawn_chance: 0}
     * used to be.
     */
    private void maybeSpawnSun() {
        if (!sunDropClock.tick(rules)) {
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

    /**
     * Drops one sun from the sky in a cell, outside the level's own sun clock.
     *
     * <p>For the sun-shower mutation, which is a second, independent shower rather than a faster
     * clock: it has to be able to rain on a level whose own sky is silent. Written as the sky's own
     * drop (<em>not</em> {@code spawnResource}'s landed motion, which is what a kill drops) so the
     * sun falls in and the player collects it the same way as every other sun.
     *
     * @return true when a sun appeared
     */
    public boolean dropSkySun(float x, float y) {
        ResourceDef sun = BuiltInRegistries.RESOURCES.get(PvzceIds.SUN);
        Team plantTeam = teams.get(PvzceIds.PLANT_TEAM);
        if (sun == null || plantTeam == null) {
            return false;
        }
        addEntity(new ResourceDropEntity(sun, plantTeam, Math.round(x), Math.round(y),
                rules.getInt(PvzceIds.RULE_SUN_VALUE)));
        return true;
    }

    /**
     * Runs the between-rounds state: announces it once, and gives up waiting eventually.
     *
     * @return true while the level is frozen for a card choice
     */
    private boolean tickRoundClear(ServerBridge bridge) {
        if (rounds && rhythmChart != null && waves.pendingRoundClear()) {
            // A rhythm level rolls over by itself: there is no card choice in the middle of a song,
            // and the song is the clock rather than a list of rounds the player is working through.
            // Reached only if a track outlasts `RhythmWaves.WAVES_PER_ROUND` waves - the round
            // number is bookkeeping the player never sees - so this is a seam that must not show,
            // which is why it says nothing on the message log.
            beginNextRound(bridge, false);
            return false;
        }
        if (!rounds || !waves.pendingRoundClear()) {
            roundClearWaitedTicks = 0;
            return false;
        }
        roundClearWaitedTicks++;
        if (roundClearWaitedTicks == 1) {
            LOGGER.info("Round {} cleared after {} waves; waiting for the next card selection",
                    waves.round(), waves.cumulativeWaves());
            bridge.send(new com.pvzce.common.network.packet.RoundClearS2C(waves.round(),
                    waves.cumulativeWaves(), zombieKills, tickCount));
            bridge.send(roundSyncPacket());
            return true;
        }
        if (roundClearWaitedTicks >= roundClearTimeoutTicks()) {
            LOGGER.warn("No card selection for round {} after {} ticks; continuing with the"
                    + " current bar", waves.round() + 1, roundClearTimeoutTicks());
            beginNextRound(bridge);
        }
        return true;
    }

    /**
     * The player's answer to the round-clear dialog: these cards for the next round.
     *
     * <p>The lawn is deliberately untouched. Everything the last round was defended with - the
     * plants, the sun, the mowers, the cooldowns - is still standing, because that is what makes
     * the mode a run rather than a series of levels: only the bar is a fresh decision.
     *
     * @param newCards the cards to play the next round with, already sanitized by the caller
     * @return true when the level was waiting for this answer, false when it was not (a stale or
     *         duplicated packet, which is dropped rather than treated as a restart)
     */
    public boolean reselectCards(List<Identifier> newCards, ServerBridge bridge) {
        if (!rounds || plantPlayer == null || !waves.pendingRoundClear()) {
            return false;
        }
        this.selectedCards = List.copyOf(newCards);
        rebuildCardBar();
        syncAllSlots();
        LOGGER.info("Round {} begins with {} cards", waves.round() + 1, selectedCards.size());
        beginNextRound(bridge);
        return true;
    }

    /**
     * Closes the finished round, arms the next one, and tells the client about both.
     *
     * @param announce whether to say so on the message log. A player's round boundary is worth a
     *                 line; a rhythm level's silent rollover is not - it is bookkeeping, and a
     *                 "第 2 轮开始" in the middle of a song would be a count the mode does not have
     */
    private void beginNextRound(ServerBridge bridge, boolean announce) {
        int next = waves.beginNextRound();
        roundClearWaitedTicks = 0;
        // The client's meter is drawn from the round's wave list, so the new round's list has to
        // arrive with the round number: a client told only the number would keep the old flags.
        bridge.send(roundSyncPacket());
        bridge.send(waveProgressPacket());
        if (announce) {
            bridge.send(new ServerMessageS2C("第 " + next + " 轮开始"));
        }
    }

    /** The player's own round boundary: always announced. See the two-argument form. */
    private void beginNextRound(ServerBridge bridge) {
        beginNextRound(bridge, true);
    }

    /**
     * How long this level waits for the card choice, in ticks.
     *
     * <p>The level's {@code round_clear_timeout_ticks} rule when it writes one, and the engine's
     * minute otherwise. A level author who wants the round boundary to move on by itself (a demo,
     * a soak test) shortens it; a rule of zero falls back to the default rather than meaning
     * "never", because a level that never continues is a level that hangs.
     *
     * <p>Read live, like every other rule this level reads per tick, so {@code /gamerule} bites
     * on the boundary that is already open.
     */
    private int roundClearTimeoutTicks() {
        int configured = rules.getInt(PvzceIds.RULE_ROUND_CLEAR_TIMEOUT_TICKS);
        return configured > 0 ? configured : ROUND_CLEAR_TIMEOUT_TICKS;
    }

    /** Where the run stands, for the client's meter and round line. */
    public com.pvzce.common.network.packet.RoundSyncS2C roundSyncPacket() {
        return new com.pvzce.common.network.packet.RoundSyncS2C(waves.round(), waves.roundWaves(),
                waves.cumulativeWaves(), waves.pendingRoundClear(), rounds, waves.roundWaveTypes());
    }

    /**
     * True while the level is frozen between rounds.
     *
     * <p>Asked by the input paths: a player who plants, digs or fires a tool while choosing their
     * next cards would be acting on a level that is not running, and the actions would land on
     * the board a moment before the bar they were paid from is replaced.
     */
    public boolean isRoundClearPending() {
        return rounds && waves.pendingRoundClear();
    }

    /**
     * Refuses an action while the player is choosing their next round's cards.
     *
     * <p>The level is not simulating in that state, so an accepted action would be applied to a
     * board that is about to change bar under it: the plants would appear the moment the next
     * round starts, having been paid for out of a card the new bar may not even hold.
     */
    private boolean rejectWhileChoosingCards(ServerBridge bridge) {
        if (!isRoundClearPending()) {
            return false;
        }
        bridge.send(new ServerMessageS2C("请先选择下一轮的卡牌。"));
        return true;
    }

    /**
     * Rebuilds the bar from {@link #selectedCards}, whoever owns it.
     *
     * <p>Three cases, in order: a mutation is dealing its own bar, a mutation is rewriting the
     * level's bar, or neither is and the plain deck stands. The last two both produce the deck
     * first and let the rewriter lay its work on top - because a rewriter's cards are a function
     * of the player's own bar, and rebuilding that bar without telling the rewriter would silently
     * undo it.
     */
    private void rebuildCardBar() {
        if (plantPlayer == null) {
            return;
        }
        com.pvzce.common.level.mutation.MutationCardSource factory = mutations == null
                ? null : mutations.cardSourceFactory();
        if (factory != null) {
            com.pvzce.server.level.cardsource.CardSource created = factory.createCardSource(this,
                    new com.pvzce.server.level.cardsource.CardSource.Context(
                            plantPlayer, def, selectedCards, random));
            if (created != null) {
                cardSource = created;
                return;
            }
        }
        cardSource = LevelMechanics.createCardSource(def,
                new com.pvzce.server.level.cardsource.CardSource.Context(
                        plantPlayer, def, selectedCards, random));
        plantPlayer.replaceSlots(PvzcePlayer.deckSlots(selectedCards));
        if (mutations != null) {
            mutations.reapplyBarRewrite();
        }
    }

    private void checkEnd(ServerBridge bridge) {
        // An endless level is never won: its waves do not run out, so "every wave released"
        // would be true at the end of every round. The only way a run ends is a zombie reaching
        // the house, which `zombieReachedLeft` reports.
        //
        // And it is the *plant side's* way to win, so a level the player plays from the other end
        // must not be won by it: an I, Zombie level has no waves at all, which makes "every wave
        // released and the lawn clear" true from the first tick.
        if (!rounds && gameState.equals(GameStateS2C.RUNNING)
                && humanTeamId.equals(PvzceIds.PLANT_TEAM)
                && waves.allWavesReleased()
                && hostileZombieCount() == 0) {
            markEnd(teams.get(PvzceIds.PLANT_TEAM));
        }
        // The other side's end: nothing of the player's is on the lawn and nothing can be put
        // there, so the run is over whether or not anybody says so.
        if (!rounds && gameState.equals(GameStateS2C.RUNNING)
                && humanTeamId.equals(PvzceIds.ZOMBIE_TEAM)
                && zombieSideIsStuck()) {
            markEnd(teams.get(PvzceIds.PLANT_TEAM));
        }

        if (!gameState.equals(GameStateS2C.RUNNING) && !gameEndPacketSent) {
            gameEndPacketSent = true;
            Team winnerTeam = teams.get(winner);
            LOGGER.info("Game over, winner={}", winner);
            bridge.send(new GameStateS2C(gameState, winner != null ? winner.toString() : "",
                    completedWaves(), zombieKills, tickCount, rhythmScore()));
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
     * The versus mode's other ending: the plant side has collected the sun it was racing for.
     *
     * <p>A public door rather than the mechanic calling into the winner bookkeeping itself, so
     * "the plant side has won" is stated in the same place as every other way a run ends - and so a
     * mechanic never has to know what {@code markEnd} does to the game state, the client's outcome
     * or the wave warning.
     */
    public void plantGoalReached() {
        markEnd(teams.get(PvzceIds.PLANT_TEAM));
    }

    /**
     * Reports that a zombie just ate a plant out of existence.
     *
     * <p>Called from the bite itself rather than from the removal path, because only the bite knows
     * <em>who</em> did it: a shovel, an explosion and a plant that a level removed all end the same
     * way and none of them is eating. The versus mode's refund is the reason this exists; it is a
     * general hook so a mod's mechanic can key off the same fact without another callback.
     */
    public void plantConsumed(Team eater, com.pvzce.server.entity.PlantEntity plant) {
        LevelMechanics.onPlantConsumed(mechanics, this, eater, plant);
    }

    /** Zombies killed this run, for the end-of-level summary. */
    public int zombieKills() {
        return zombieKills;
    }

    /**
     * How many waves the run released: what an endless run's score is measured in.
     *
     * <p>Cumulative across the rounds rather than the wave inside the current one, because on an
     * endless level there is nothing else to count - "survived eleven waves" would reset to one
     * every time the player survived a round.
     */
    public int completedWaves() {
        return waves.cumulativeWaves();
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
        // First, and outside the game-state guard below: the wave director's per-wave counts are
        // what the stockpile cap and the survival-ratio gate read, so a kill it never hears about
        // is a wave that still believes it is being fought.
        waves.zombieDied(zombie.id());
        zombieKills++;
        // The mutations hear it before any of the payout paths can return: a blast on death is
        // part of what killed things this tick, and a mutation that only fired for zombies the
        // level happened to pay coins for would be a bug nobody could see.
        if (mutations != null) {
            mutations.onZombieDied(zombie);
        }
        // Where it fell, recorded before anything else can return: the end-of-level payout lands
        // on the last kill's cell, and a kill that happened on the same tick the level ended
        // (the level's own win check runs after the entities) would otherwise leave the payout
        // with no spot to land on.
        lastKillX = zombie.cellX();
        lastKillY = zombie.cellY();
        // A zombie that blew itself up (the jack-in-the-box) is a death the wave above had to
        // hear about and a payout nobody earned: the player did not kill this one, so the sun
        // and the coin roll would be a reward for standing next to a bomb. The same goes for the
        // bodies a finished chart sweeps off the lawn (see ZombieEntity.sweptAway).
        if (zombie.unearned()) {
            return;
        }
        if (!gameState.equals(GameStateS2C.RUNNING)) {
            return;
        }
        // The death animation has to reach the client even if the level ends this tick;
        // see entitySyncPending.
        entitySyncPending = true;
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

    /**
     * Ends the level, with the winning team deciding how it reads.
     *
     * <p>{@code GameStateS2C.LOST} means the plant side lost rather than "somebody lost": the
     * client's defeat screen is the original's, and it only ever plays for the player whose
     * house is being eaten. A level that ends with the zombies winning therefore reports
     * {@code LOST} - which on an endless level is the only way a run can end at all.
     */
    /**
     * Ends the run in the plant team's favour: for a level whose own condition is its win.
     *
     * <p>{@code checkEnd} wins a level once every wave has been released and the lawn is clear of
     * hostiles, which can never be true of a level with no waves at all - and 4-5 is exactly that
     * level. Its Scary Potter mechanic calls this when the last pot's zombie is down; the ending
     * itself (the packet, the summary, the winner) is the one every other level gets.
     */
    public void declareVictory() {
        markEnd(teams.get(PvzceIds.PLANT_TEAM));
    }

    /**
     * Sweeps every hostile off the lawn: they die where they stand and the level is told.
     *
     * <p>The other ending a level can author for itself. {@code checkEnd} wants a lawn that is
     * already empty, which is a shape only "the last wave was shot" has; a rhythm level's ending is
     * the chart running out, and whatever is still walking at that moment has to go - the song is
     * over, the notes are over, and a lawn that kept walking would be the level refusing to end.
     *
     * <p>The deaths are ordinary ones (see {@link ZombieEntity#sweptAway}), so the wave director
     * hears about each body and a wave still releasing its queue is not left waiting for a zombie
     * that will never die. What they do not do is pay.
     *
     * @return how many bodies were swept
     */
    public int sweepLawn() {
        Team plantTeam = teams.get(PvzceIds.PLANT_TEAM);
        int swept = 0;
        for (PvzceEntity entity : new ArrayList<>(entities)) {
            if (entity instanceof ZombieEntity zombie && zombie.isAlive()
                    && isEnemyOf(zombie.team(), plantTeam)) {
                zombie.sweptAway(this);
                swept++;
            }
        }
        return swept;
    }

    private void markEnd(Team winnerTeam) {
        if (!gameState.equals(GameStateS2C.RUNNING) || winnerTeam == null) {
            return;
        }
        // WON means *the player* won, which on every level but I, Zombie is the plant team. The
        // two are the same statement written twice before the zombie side existed, and the second
        // copy is the one that would have shown a zombie-side victory as a defeat.
        gameState = humanTeamId.equals(winnerTeam.id())
                ? GameStateS2C.WON
                : GameStateS2C.LOST;
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
        if (rejectWhileChoosingCards(bridge)) {
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
        // A mutation may have locked this card. Asked before the cell is even looked at: the card
        // is what the player cannot use, so "this card is locked" is the answer whatever they
        // clicked on - and the client draws the same lock from the same state (see
        // `MutationStateS2C.lockedSlots`).
        if (mutations != null && mutations.isSlotLocked(slotIndex)) {
            bridge.send(new ServerMessageS2C("这张卡被变异锁住了。"));
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
        // Before the placement rules, because the answer is not "a plant goes here" at all: a
        // click with a plant card on a vase is the player *storing* that card. Both vase cells come
        // through here - an empty one stores, a full one is refused by `storeCardInVase` - because
        // the vase's own cell is unplantable, which is exactly why this branch has to come first.
        // See `useVase` for the tool half.
        if (isVaseAt(x, y)) {
            return storeCardInVase(bridge, slot, plantDef, x, y);
        }
        // Where this one click lands. A cell, or - on a level that plants in columns - every cell
        // of the clicked column that will take the plant. The cells that will not are skipped in
        // silence rather than refusing the whole click: "this column, as far as it goes" is the
        // rule, and a lawn with one wall-nut in it must still be plantable around. See
        // `RULE_PLANT_WHOLE_COLUMN`.
        List<Integer> rows = plantableRows(plantDef, x, y);
        if (rows.isEmpty()) {
            bridge.send(new ServerMessageS2C("该格不能种植。"));
            return false;
        }
        // What a card costs - sun and a cooldown, or nothing at all because the level
        // handed it over - is the card source's business. This method only knows that a
        // card was asked for and that a plant has to appear if the payment went through.
        // Charged once, whatever the shape: the price is the card's, and a column of five is
        // what the level's rule is giving away.
        if (cardSource == null || !cardSource.spend(this, bridge, slot, plantDef)) {
            return false;
        }
        for (int row : rows) {
            // The base goes before the upgrade appears, so the cell never holds both: a frame with
            // two plants in one cell is a frame the client would draw as one of them, and the
            // upgrade is what the player paid for.
            plantDef.upgrade().ifPresent(upgrade -> consumeUpgradeBases(upgrade, x, row));
            spawnPlant(plantDef, plantPlayer.team(), x, row);
            spreadKelpFrom(plantDef, x, row);
            LOGGER.debug("Planted {} at ({},{}) count={}", slot.defId(), x, row, plantCount());
        }
        cardSource.afterSpend(this, bridge, slot);
        return true;
    }

    /**
     * Which rows of one column this placement fills, in row order; empty when it fills none.
     *
     * <p>One cell on an ordinary level - the clicked one, if it will take the plant. Every cell of
     * the clicked column on a level whose {@code plant_whole_column} rule is on, each of them asked
     * the same question the single-cell path asks ({@link #canPlacePlant}, which is where the
     * terrain, the stacking, the level's own placement zone and its mechanics all meet).
     *
     * <p>Returning a list rather than planting here is what lets the caller refuse <em>before</em>
     * the card is spent: a column with nowhere to put the plant costs nothing and says so, which is
     * the same answer an occupied cell gives on an ordinary level.
     */
    private List<Integer> plantableRows(PlantDef def, int x, int y) {
        if (!plantsWholeColumn()) {
            return canPlacePlant(def, x, y) ? List.of(y) : List.of();
        }
        List<Integer> rows = new ArrayList<>();
        for (int row = 0; row < height(); row++) {
            if (canPlacePlant(def, x, row)) {
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * True when this level's cards plant their whole column for the price of one.
     *
     * <p>The "排山倒海" planting rule (see {@code PvzceIds.RULE_PLANT_WHOLE_COLUMN}); the client is
     * told the same fact so its preview can draw the column it is about to fill.
     */
    public boolean plantsWholeColumn() {
        return rules.getBoolean(PvzceIds.RULE_PLANT_WHOLE_COLUMN);
    }

    /**
     * Removes the plants an upgrade is planted on.
     *
     * <p>Quietly: no dirt, no refund, no death effect. The original replaces the base plant with its
     * upgrade, and the player watching a sunflower turn into a twin sunflower is watching one
     * plant, not a plant dying and another arriving. The rule that said the bases are there is
     * {@link PlantPlacement#upgradeBasesInPlace} - this is the same walk, in the same order (left
     * neighbour first), so the neighbour that is consumed is the one the placement previewed.
     */
    private void consumeUpgradeBases(PlantDef.Upgrade upgrade, int x, int y) {
        List<int[]> cells = new ArrayList<>();
        cells.add(new int[] {x, y});
        if (upgrade.adjacent() > 0) {
            int[] neighbour = null;
            for (int dx : new int[] {-1, 1}) {
                if (PlantPlacement.countBase(upgrade, placementContext, x + dx, y)
                        >= upgrade.adjacent()) {
                    neighbour = new int[] {x + dx, y};
                    break;
                }
            }
            if (neighbour != null) {
                cells.add(neighbour);
            }
        }
        for (int[] cell : cells) {
            PlantEntity base = topmostBaseAt(upgrade.base(), cell[0], cell[1]);
            if (base != null) {
                base.remove();
            }
        }
        flushPending();
    }

    /** The topmost plant in a cell whose definition is {@code baseId}, or {@code null}. */
    private PlantEntity topmostBaseAt(Identifier baseId, int x, int y) {
        PlantEntity found = null;
        for (PvzceEntity entity : entities) {
            if (entity instanceof PlantEntity plant && !plant.isRemoved()
                    && plant.gridX() == x && plant.gridY() == y
                    && baseId.equals(plant.def().id())) {
                if (found == null || found.def() == null
                        || PlantPlacement.layerIndex(plant.def())
                                >= PlantPlacement.layerIndex(found.def())) {
                    found = plant;
                }
            }
        }
        return found;
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
        // The human's click: whoever the player is. A player on the zombie side has no collection
        // to do - their income is the versus mode's own - so this is the plant team or nobody.
        if (!gameState.equals(GameStateS2C.RUNNING) || !humanTeamId.equals(PvzceIds.PLANT_TEAM)
                || plantPlayer == null) {
            return false;
        }
        return collectResourceFor(bridge, entityId, silent, auto, plantPlayer.team());
    }

    /**
     * Credits one drop to the team that owns it, through every rule a click goes through.
     *
     * <p>The team is a parameter because a versus level's plant side may be the <em>opponent</em>:
     * an AI cannot click, and its whole win condition is collecting sun, so it collects its own
     * drops through this path rather than through a second, weaker set of rules. The ownership
     * check is what keeps that honest - a drop belongs to a team, and a team collects only what is
     * its own.
     *
     * <p>The resource-card rule applies to the human and not to an AI: the card bar is how a player
     * proves they may collect, and an opponent has no bar. Everything else - is the resource
     * collectible at all, has this level unlocked it, the sparkle, the sound, the bank animation -
     * is the same for both, which is the point.
     */
    private boolean collectResourceFor(ServerBridge bridge, int entityId, boolean silent, boolean auto,
                                       Team collector) {
        if (collector == null) {
            return false;
        }
        boolean humanCollector = collector.id().equals(humanTeamId) && plantPlayer != null
                && plantPlayer.team() == collector;
        for (PvzceEntity entity : entities) {
            if (entity.id() != entityId || !(entity instanceof ResourceDropEntity drop) || drop.isRemoved()) {
                continue;
            }
            if (!collector.equals(drop.team())) {
                return false;
            }
            if (!drop.def().collectible()) {
                return false;
            }
            if (!drop.def().collectibleWithoutCard()) {
                if (!collector.canCollect(drop.defId())) {
                    if (!auto && humanCollector) {
                        bridge.send(new ServerMessageS2C("该资源在本关未解锁。"));
                    }
                    return false;
                }
                if (humanCollector && !plantPlayer.hasResourceCard(drop.defId())) {
                    if (!auto) {
                        bridge.send(new ServerMessageS2C("没有对应资源卡，无法收集。"));
                    }
                    return false;
                }
            }
            drop.markCollected();
            collector.addResource(drop.defId(), drop.amount());
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
            bridge.send(new ResourceDeltaS2C(collector.id().toString(), drop.defId().toString(),
                    collector.resourcesOf(drop.defId())));
            // After the credit and before the receipt: the observers (the versus mode's sun race)
            // count what actually reached a wallet, not what a player aimed at.
            LevelMechanics.onResourceCollected(mechanics, this, collector, drop.defId(),
                    drop.amount());
            String label = drop.defId().path().equals("sun") ? "阳光" : drop.defId().toString();
            if (!silent && humanCollector) {
                bridge.send(new ServerMessageS2C("+" + drop.amount() + " " + label));
            }
            return true;
        }
        return false;
    }

    /**
     * Judges one rhythm note, and answers whether it counted.
     *
     * <p>A thin door on purpose: the mechanic owns the chart, the clock and the scoring, and this
     * only finds the block the level declared and hands the packet's four numbers to it.
     */
    public boolean rhythmHit(String laneKind, int laneIndex, int noteTick, int perceivedTicks) {
        com.pvzce.api.content.RhythmChartData chart = com.pvzce.common.level.mechanic.LevelMechanics
                .dataOf(def, PvzceIds.MECHANIC_RHYTHM, com.pvzce.api.content.RhythmChartData.class)
                .orElse(null);
        if (chart == null) {
            return false;
        }
        return com.pvzce.common.level.mechanic.LevelMechanics.RHYTHM
                .judge(this, chart, laneKind, laneIndex, noteTick, perceivedTicks);
    }

    /**
     * True when this level's own rules forbid changing the game speed.
     *
     * <p>A rhythm level does: its notes are written against the level's tick count, so 2x does not
     * move the chart, it halves the time the player has to answer it - the same chart becomes a
     * different, unplayable one. Asked here rather than decided in the packet handler so the rule
     * is the level's, and so the client can be told it (see {@code LevelInitS2C}).
     */
    public boolean forbidsSpeedChange() {
        return com.pvzce.common.level.mechanic.LevelMechanics.has(def, PvzceIds.MECHANIC_RHYTHM);
    }

    /**
     * True when this level's plants wait to be told to attack instead of firing on their own clock.
     *
     * <p>The rhythm levels do: their whole loop is "the note you play is the attack", so a
     * peashooter that shot by itself would be playing the level for the player. The answer is the
     * chart's own field rather than a rule, because it is a fact about how that mode is played
     * rather than a knob on a level, and it defaults on for exactly that reason.
     *
     * <p>Read by {@code PlantEntity} on every tick of every plant, and by nothing else: which
     * capabilities that skips is {@code PlantCapability#holdsFire}'s answer, not this one's.
     */
    public boolean plantsHoldFire() {
        return com.pvzce.common.level.mechanic.LevelMechanics
                .dataOf(def, PvzceIds.MECHANIC_RHYTHM, com.pvzce.api.content.RhythmChartData.class)
                .map(com.pvzce.api.content.RhythmChartData::plantsHoldFire)
                .orElse(false);
    }

    /**
     * True when the zombie-side player can no longer do anything.
     *
     * <p>The original ends an I, Zombie level when the player has no zombies left and cannot
     * afford another one - there is no clock to run out and no wave to survive, so without this
     * the run would sit there forever with nothing to click. "Cannot afford" is read from the
     * bar's own numbers (price and balance) rather than from the sun alone, because a card the
     * player owns and cannot pay for is exactly the case this is about.
     */
    private boolean zombieSideIsStuck() {
        if (plantPlayer == null) {
            return true;
        }
        for (PvzceEntity entity : entities) {
            if (entity instanceof ZombieEntity zombie && !zombie.isRemoved() && zombie.isAlive()
                    && zombie.team() == plantPlayer.team()) {
                return false;
            }
        }
        int sun = plantPlayer.team().resourcesOf(PvzceIds.SUN);
        for (Slot slot : plantPlayer.slots()) {
            if (slot.kind() == Slot.Kind.ZOMBIE && slot.costSun() <= sun) {
                return false;
            }
        }
        return true;
    }

    /**
     * Places a zombie the player paid for: the I, Zombie card's own placement path.
     *
     * <p>The mirror of {@link #placePlant}, and deliberately a second method rather than a branch
     * inside it: the two differ in every line that matters (which side may act, what the card
     * spawns, which registry the id is looked up in) and share only the charging of a card, which
     * is four lines. What they do share is the rule that the *server* decides - the client sends a
     * slot and a cell, and the price, the cooldown and the side are all re-derived here.
     */
    public boolean placeZombie(ServerBridge bridge, int slotIndex, int x, int y) {
        return withBridge(bridge, () -> placeZombieInternal(bridge, slotIndex, x, y));
    }

    private boolean placeZombieInternal(ServerBridge bridge, int slotIndex, int x, int y) {
        if (!gameState.equals(GameStateS2C.RUNNING)) {
            bridge.send(new ServerMessageS2C("游戏已经结束。"));
            return false;
        }
        if (rejectWhileChoosingCards(bridge)) {
            return false;
        }
        if (!humanTeamId.equals(PvzceIds.ZOMBIE_TEAM)) {
            bridge.send(new ServerMessageS2C("当前控制的是植物方，僵尸由关卡派。"));
            return false;
        }
        if (plantPlayer == null) {
            return false;
        }
        Slot slot = plantPlayer.slot(slotIndex);
        if (slot == null || slot.kind() != Slot.Kind.ZOMBIE) {
            bridge.send(new ServerMessageS2C("无效的卡槽。"));
            return false;
        }
        if (!inBounds(x, y)) {
            bridge.send(new ServerMessageS2C("不能在草坪外放僵尸。"));
            return false;
        }
        // The level's own zombie zone, through the same gate the opponent's decisions go through:
        // "zombies only on the right four columns" has to be true of the player as well, and a
        // hand-written packet must not be able to place one in the flowerbeds.
        //
        // The refusal is a **language key**, not a sentence: the server has no translations, and a
        // line of Chinese here would be one more string no resource pack can reach. The client
        // resolves a message that names a key it knows (see `PvzceClient.onServerMessage`).
        if (!LevelMechanics.canPlaceZombie(mechanics, this, x, y)) {
            bridge.send(new ServerMessageS2C("gui.pvzce.versus.zombie_zone"));
            return false;
        }
        // The same charge the opponent's decisions pay, so "a card recharges after it is played" is
        // one rule rather than one rule per caller.
        String refusal = chargeCard(plantPlayer.team(), slot);
        if (refusal != null) {
            bridge.send(new ServerMessageS2C(refusal));
            return false;
        }
        int cost = slot.costSun();
        ZombieEntity spawned = spawnZombie(slot.defId(), plantPlayer.team(), x + 0.5F, y);
        if (spawned == null) {
            // Give the sun *and the card* back: the cell was refused somewhere the player cannot see,
            // and a card that costs sun, a cooldown and produces nothing is a bug report.
            if (cost > 0) {
                plantPlayer.team().addResource(PvzceIds.SUN, cost);
            }
            restoreCard(slot);
            bridge.send(new ServerMessageS2C("这个僵尸放不下。"));
            return false;
        }
        requestEntitySync();
        bridge.send(new SlotSyncS2C(toSlotInfo(slot)));
        bridge.send(new ResourceDeltaS2C(plantPlayer.team().id().toString(),
                PvzceIds.SUN.toString(), plantPlayer.team().resourcesOf(PvzceIds.SUN)));
        return true;
    }

    /**
     * Fires a hand-aimed plant at a cell: what the cob cannon's second click sends.
     *
     * <p>The id is looked up as a plant on the sender's own team, so a client cannot aim the other
     * side's cannon. Everything else - loaded or not, cell on the board or not - belongs to the
     * capability, which is the thing that owns the clock; this method's job is the ownership check
     * and the two messages that say why nothing happened.
     */
    public boolean fireAt(ServerBridge bridge, int entityId, int gridX, int gridY) {
        return withBridge(bridge, () -> {
            if (!gameState.equals(GameStateS2C.RUNNING)) {
                return false;
            }
            if (!humanTeamId.equals(PvzceIds.PLANT_TEAM)) {
                bridge.send(new ServerMessageS2C("当前控制的是僵尸方，僵尸方由 AI 指挥。"));
                return false;
            }
            PlantEntity plant = plantById(entityId);
            if (plant == null || plant.isRemoved() || plant.team() != plantPlayer.team()) {
                bridge.send(new ServerMessageS2C("这一格没有你的植物。"));
                return false;
            }
            com.pvzce.common.capability.plant.CobCannonCapability cannon =
                    plant.capability(com.pvzce.common.capability.plant.CobCannonCapability.class);
            if (cannon == null) {
                bridge.send(new ServerMessageS2C("这株植物不能指定目标。"));
                return false;
            }
            if (!cannon.loaded()) {
                // How long is left, rather than "no": the player is clicking a cannon they can see
                // is still loading, and the number is the difference between a refusal and a wait.
                // Rounded *up* and floored at one, because a refusal that says "0 seconds left" is
                // a refusal that reads like the game is broken - and it is the message a player
                // gets on the one tick where the wait is real but shorter than a second.
                int seconds = Math.max(1, (int) Math.ceil(
                        cannon.chargeLeft() / (double) PvzceConstants.TICKS_PER_SECOND));
                bridge.send(new ServerMessageS2C("玉米加农炮还在装填（还剩 " + seconds + " 秒）。"));
                return false;
            }
            if (!cannon.fireAt(plant, this, gridX, gridY)) {
                bridge.send(new ServerMessageS2C("不能打到草坪外。"));
                return false;
            }
            return true;
        });
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
        if (!applyToolEffect(tool, x, y, ToolMechanic.damage(granted), granted.singleTarget())) {
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
        if (rejectWhileChoosingCards(bridge)) {
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
        if (hasCarry() && slot != null && !isGlove(slot)) {
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
        boolean finishingMove = hasCarry() && isGlove(slot);
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
        } else if (!hasCarry()) {
            // That was the drop: the move is over, so this is where its recharge belongs.
            slot.startCooldown(effectiveCooldownTicks(slot));
        }
        bridge.send(new SlotSyncS2C(toSlotInfo(slot)));
        return true;
    }

    /** True when this card has just started a move and is still holding the plant. */
    private boolean awaitingDrop(Slot slot) {
        return hasCarry() && isGlove(slot);
    }

    /**
     * Forgets a carry that no longer makes sense.
     *
     * <p>Called when the player reaches for something else: picking a card, using another tool
     * or leaving the level ends the move, and a carry that survived it would put the plant
     * down somewhere the player was no longer thinking about.
     */
    private void abandonCarry() {
        if (hasCarry()) {
            clearCarry();
        }
    }

    /**
     * Applies a tool's effect to a cell.
     *
     * @return false when the click was refused, so the caller can leave the card ready
     *         instead of charging a cooldown and a use for nothing
     */
    /**
     * How long one watering lasts, in ticks: fifteen seconds of the faster clock.
     *
     * <p>Long enough to matter for the volley that is already coming and short enough that
     * keeping a whole lawn watered is a job rather than a one-off purchase - which is the
     * tension the original's garden has, transplanted onto a lawn.
     */
    public static final int WATERED_TICKS = 15 * PvzceConstants.TICKS_PER_SECOND;

    /**
     * Gives back what a plant cost, in full, because it is being taken up during preparation.
     *
     * <p>The price is read from the plant's own definition - the same number the card printed and
     * the till charged - so a refund cannot pay out something the player never spent.
     */
    private void refundWholePrice(PlantEntity plant) {
        if (plantPlayer == null || plant.def() == null) {
            return;
        }
        int price = plant.def().cost().amountOf(PvzceIds.SUN);
        if (price <= 0) {
            return;
        }
        plantPlayer.team().putResource(PvzceIds.SUN,
                plantPlayer.team().resourcesOf(PvzceIds.SUN) + price);
        send(new com.pvzce.common.network.packet.ResourceDeltaS2C(
                plantPlayer.team().id().toString(), PvzceIds.SUN.toString(),
                plantPlayer.team().resourcesOf(PvzceIds.SUN)));
    }

    private boolean applyToolEffect(ToolDef tool, int x, int y) {
        return applyToolEffect(tool, x, y, tool.damage(), false);
    }

    private void strikeWithHammer(ZombieEntity zombie, ToolDef tool, int damage) {
        zombie.damage(damage, toolTypeFor(tool), this);
        emitEffect("", zombie.cellX(), zombie.cellY(), PvzceSounds.EFFECT_BONK);
    }

    private boolean applyToolEffect(ToolDef tool, int x, int y, int damage, boolean singleTarget) {
        return switch (tool.effect()) {
            // PVZ original: one shovel click removes exactly one plant, always the
            // topmost layer of the target cell.
            case "pvzce:shovel" -> {
                PlantEntity plant = plantAt(x, y);
                if (plant != null) {
                    // During a preparation phase the dig is a *rearrangement*, not a mistake: the
                    // original gives the whole price back until the first wave, which is what makes
                    // "try a layout, look at it, change your mind" possible at all. After the phase
                    // the ordinary rule applies (the sun shovel's fraction, or nothing).
                    if (com.pvzce.common.level.mechanic.PreparationMechanic.refundsFully(this)) {
                        refundWholePrice(plant);
                    } else {
                        refundShovel(plant);
                    }
                    plant.remove();
                    flushPending();
                    emitEffect(PvzceParticles.DIRT_SMALL.toString(), x + 0.5F, y + 0.5F, PvzceSounds.EFFECT_SHOVEL);
                }
                yield true;
            }
            case "pvzce:glove" -> movePlant(x, y);
            case "pvzce:hammer" -> {
                // A pot is what this swing is for on a vase level (4-5), and a zombie is what it
                // is for everywhere else - and on a vase level it is both at once: the swing
                // opens the pot *and* lands on whatever is standing within reach, because the
                // mallet does not stop being a mallet just because the level is full of pots.
                // (It used to `yield` here, so a zombie one cell away from a pot took nothing
                // from a swing aimed at the pot it was standing next to.)
                boolean smashed = ScaryPotterMechanic.isPot(this, x, y) && smashPot(x, y);
                // The ordinary card uses ToolDef.damage; a level can override it through
                // ToolData. Whack-a-Zombie declares a 900-point, single-target impact: armour
                // absorbs the entire blow, so a cone takes two swings and a bucket takes three.
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
                ZombieEntity closest = null;
                float closestDistance = Float.MAX_VALUE;
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
                    if (singleTarget) {
                        float distance = dx * dx + dy * dy;
                        if (distance < closestDistance) {
                            closest = zombie;
                            closestDistance = distance;
                        }
                        continue;
                    }
                    // The blow's sound only. It used to throw the hit spark as well, and the
                    // spark is a 25-star burst drawn where the cursor is standing - at which
                    // point the player reads it as a halo around the mallet rather than as a
                    // hit. The hit's own picture is the mallet's swing, which the client plays
                    // on the click (see InGameScreen#swingDefaultToolCursor); what the server
                    // still owes the player is the confirmation that it landed, and that is the
                    // bonk.
                    strikeWithHammer(zombie, tool, damage);
                    hitSomething = true;
                }
                if (closest != null) {
                    strikeWithHammer(closest, tool, damage);
                    hitSomething = true;
                }
                yield hitSomething || smashed;
            }
            case "pvzce:water" -> waterPlant(x, y);
            // The vase: place it, fill it, or smash it. Which of the three depends on the cell and
            // on what the player is holding, and the rule is the user's own wording ("选一张植物卡
            // 再点它可以把它存进去，空手点它直接砸开").
            case "pvzce:vase" -> useVase(x, y);
            // An effect this build does not implement: refused, so no use is spent.
            default -> false;
        };
    }

    /**
     * Gives back part of what a dug-up plant cost, when a buff says to.
     *
     * <p>The shop's sun shovel. Two conditions, and the second is the one worth stating: the run
     * has to be one where the player <em>bought</em> their cards. A conveyor belt hands them out
     * free, so a refund there would be sun conjured out of a plant that cost nothing - which is
     * the case the user named when the buff was asked for ("传送带无效").
     *
     * <p>The refund is computed from the plant's <em>definition</em> price rather than from the
     * card's, because the card's has already been through whatever multipliers the level and the
     * mutations apply: "a fifth of what this plant is worth" is the promise, and a level that
     * doubled every price should not also double the refund of a plant that was planted before
     * the change.
     */
    private void refundShovel(PlantEntity plant) {
        float fraction = com.pvzce.common.buff.LevelBuffs.shovelRefundFraction(activeBuffs);
        if (fraction <= 0F || cardSource == null || cardSource.dealsItsOwnCards()) {
            return;
        }
        if (plantPlayer == null) {
            return;
        }
        int price = plant.def().cost().amountOf(PvzceIds.SUN);
        if (price <= 0) {
            return;
        }
        int refund = Math.max(1, Math.round(price * fraction));
        // `addResource`, not `putResource`: the latter *sets* the total, so a refund written with
        // it replaced the player's whole sun bank with the refund. The test caught it as "digging
        // up a 100-sun plant while holding 50 sun left me with 20".
        plantPlayer.team().addResource(PvzceIds.SUN, refund);
        // And the client has to hear about it, or the sun bank keeps drawing the old total until
        // the next collection happens to sync - the same packet the tool's own charge sends.
        send(new com.pvzce.common.network.packet.ResourceDeltaS2C(
                plantPlayer.team().id().toString(), PvzceIds.SUN.toString(),
                plantPlayer.team().resourcesOf(PvzceIds.SUN)));
        // The id, not a display name: the server has no language file, and the client's own name
        // for a plant is a key it looks up (`GuiLang`). The plants' names live there because the
        // server's copy would be a second translation to keep in step - see `MutationText`, which
        // exists only because a mutation's banner is *built* on the server.
        send(new ServerMessageS2C("铲掉 " + plant.def().id() + "，返还 " + refund + " 阳光"));
    }

    /**
     * One click of the vase: put one down, put a card in one, or break one open.
     *
     * <p>Three outcomes from one click, and the cell decides which:
     *
     * <ul>
     *   <li><b>an empty cell</b> - a vase is placed, and the cell becomes unplantable, which is
     *       what makes putting a plant <em>in</em> it mean anything;</li>
     *   <li><b>a vase</b> - it is smashed, and whatever was inside is the player's again.</li>
     * </ul>
     *
     * <p><b>Putting a card in is not this method's job.</b> The player does it by selecting a
     * <em>plant</em> card and clicking the vase, which is a placement click rather than a tool
     * one - so it is answered in {@code placePlantInternal}, where a slot index already travels.
     * Routing it through the tool as well would need the server to know what the client has
     * selected, which it deliberately never has.
     *
     * <p>The shovel is deliberately not part of this either: a shovel click on a vase digs up a
     * <em>plant</em>, and a level where the shovel could smash a vase would make the two tools
     * fight over the same cell. The vase smashes with the vase.
     */
    private boolean useVase(int x, int y) {
        SceneElementDef here = sceneAt(x, y);
        if (here == null) {
            return false;
        }
        if (!PvzceIds.VASE.equals(here.id()) && !PvzceIds.VASE_FULL.equals(here.id())) {
            // A vase goes on bare ground and nowhere else: one cell, one thing standing in it.
            //
            // It used to ask only about plants, which quietly made the vase a thing that
            // *overwrites*: a click on a gravestone buried the gravestone, and a click on one of
            // 4-5's scary pots turned that pot into an empty vase - the pot's contents stayed in
            // the mechanic's bookkeeping with nothing on the lawn to break, so the round could
            // never be finished and the cell looked like a vase with nothing in it. The terrain
            // list is the bare surfaces; everything else (a grave, a crater, ice, a pot, a vase)
            // is something already standing there.
            boolean bare = plantAt(x, y) == null
                    && (PvzceIds.GRASS.equals(here.id()) || PvzceIds.GROUND.equals(here.id())
                        || PvzceIds.ROOF_FLAT.equals(here.id())
                        || PvzceIds.ROOF_SLOPE.equals(here.id()));
            if (!bare) {
                bridge.send(new ServerMessageS2C("这一格已经有东西了。"));
                return false;
            }
            setScene(x, y, PvzceIds.VASE);
            sendSceneCell(x, y);
            emitEffect(PvzceParticles.DIRT_SMALL.toString(), x + 0.5F, y + 0.5F,
                    PvzceSounds.PLANT_PLANT);
            return true;
        }
        return smashVase(x, y);
    }

    /**
     * Breaks the vase standing in a cell open.
     *
     * <p>Reached two ways, and they are the same act: the vase tool clicked on a vase, and a
     * click with nothing in hand (see {@link #smashContainer}). What is inside is the player's
     * again either way.
     */
    private boolean smashVase(int x, int y) {
        // The cell goes back to whatever a bare lawn is made of, and the card inside - if there
        // was one - is the player's again.
        Identifier inside = vaseContents.remove(cellKey(x, y));
        resetSceneCell(x, y);
        // The vase's own pieces, not the cherry bomb's cloud: see `PvzceParticles.POT_SHATTER`.
        emitEffect(PvzceParticles.VASE_SHATTER.toString(), x + 0.5F, y + 0.5F,
                Identifier.withDefaultNamespace("sfx/effect/vase_breaking"));
        if (inside != null) {
            // A packet on the lawn rather than a card on the bar, which is what the original
            // does and what the pot's plant does: the player clicks it up and plants it. The
            // card used to be handed over directly, and a plant the player already had then
            // looked exactly like a vase that was empty.
            spawnCardDrop(inside, x, y);
            bridge.send(new ServerMessageS2C("花瓶里掉出了一张卡。"));
        }
        return true;
    }

    /**
     * A click with nothing in hand on a vase or one of the vase level's pots: the swing that
     * breaks it.
     *
     * <p>The mallet in this build is an animation, not a tool (see {@code InGameScreen}'s summoned
     * swing), so the server's half is the answer to "what did that click break": a pot of the
     * vase level, a vase the player placed, or nothing at all. The two kinds of container live in
     * different books - the pots are the {@code pvzce:scary_potter} mechanic's, a vase is the
     * player's own - which is why this is the one place that asks both.
     *
     * <p>Refused for any other cell rather than treated as "a swing at the lawn": a click that
     * breaks nothing must not spend anything, and there is nothing here to spend.
     */
    public boolean smashContainer(ServerBridge bridge, int x, int y) {
        return withBridge(bridge, () -> {
            if (!gameState.equals(GameStateS2C.RUNNING) || !inBounds(x, y)) {
                return false;
            }
            if (ScaryPotterMechanic.isPot(this, x, y)) {
                return smashPot(x, y);
            }
            SceneElementDef here = sceneAt(x, y);
            if (here != null && (PvzceIds.VASE.equals(here.id())
                    || PvzceIds.VASE_FULL.equals(here.id()))) {
                return smashVase(x, y);
            }
            return false;
        });
    }

    /**
     * A plant card put into an empty vase: the click that stores instead of planting.
     *
     * <p>Reached from {@link #placePlantInternal}, because that is the packet a plant card travels
     * on. {@code useVase} documents why: the server deliberately never learns which card the client
     * has selected, so the only moment it can tell "the player meant a vase" from "the player meant
     * a plant" is the click that carries both.
     *
     * <p><b>The card is paid for now, and comes back free.</b> It goes through the same
     * {@code spend} the planting path uses, so the sun and the recharge are the price of the card
     * either way; the difference is only <em>when</em> the plant appears. Charging nothing here and
     * charging at the second planting would be the same total, but it would also mean a card could
     * sit in a vase through a mutation that doubles prices and be planted at the old price - the
     * bar's price is the moment of payment, so payment happens at the click.
     *
     * <p>One card per vase. A filled vase refuses, which is what makes the full vase's picture
     * mean something: the player can see at a glance which vases still have room.
     */
    private boolean storeCardInVase(ServerBridge bridge, Slot slot, PlantDef plantDef, int x, int y) {
        long key = cellKey(x, y);
        // Asked of the contents rather than of the picture: `vase_full` and "there is a card in
        // here" are written together and are meant to agree, and if they ever did not, the answer
        // the player needs is the one that keeps their card.
        if (vaseContents.containsKey(key)) {
            bridge.send(new ServerMessageS2C("这个花瓶里已经有东西了。"));
            return false;
        }
        if (cardSource == null || !cardSource.spend(this, bridge, slot, plantDef)) {
            return false;
        }
        vaseContents.put(key, plantDef.id());
        setScene(x, y, PvzceIds.VASE_FULL);
        sendSceneCell(x, y);
        cardSource.afterSpend(this, bridge, slot);
        // The same glow a ripening plant gets: the click did something the vase's own picture has
        // to be looked at twice to notice, and the sparkle is what says "that landed".
        emitEffect(PvzceParticles.POTTED_ZEN_GLOW.toString(), x + 0.5F, y + 0.5F,
                PvzceSounds.PLANT_PLANT);
        bridge.send(new ServerMessageS2C("把 " + plantDef.id() + " 存进了花瓶，砸开就能拿回来。"));
        return true;
    }

    /**
     * One swing at a scary pot: it opens, and what is inside is the player's problem.
     *
     * <p>A plant pot hands its card over - the same delivery a smashed vase makes - and a zombie
     * pot puts that zombie on the lawn in the cell the pot was standing in, which is the
     * original's own arrangement: the pot is the zombie's way in, so it comes out where the pot
     * was. Either way the pot is forgotten by the mechanic that laid it out, which is what makes
     * the round advance when the last one goes.
     */
    private boolean smashPot(int x, int y) {
        ScaryPotterMechanic.Contents contents = ScaryPotterMechanic.contentsAt(this, x, y);
        if (contents == null) {
            return false;
        }
        // Which pieces: the pot's own colour, read off the picture it was wearing.
        boolean leaf = PvzceIds.POT_LEAF.equals(sceneIdAt(x, y));
        ScaryPotterMechanic.forget(this, x, y);
        resetSceneCell(x, y);
        emitEffect((leaf ? PvzceParticles.POT_SHATTER_LEAF : PvzceParticles.POT_SHATTER).toString(),
                x + 0.5F, y + 0.5F,
                Identifier.withDefaultNamespace("sfx/effect/vase_breaking"));
        if (contents.isPlant()) {
            // A packet on the lawn, not a card on the bar - the same delivery a smashed vase
            // makes. See `spawnCardDrop`.
            spawnCardDrop(contents.id(), x, y);
            bridge.send(new ServerMessageS2C("花瓶里掉出了一张卡。"));
        } else if (contents.isResource()) {
            // The vase level's economy: the pots are where its sun comes from, so a level with no
            // sky and no producers still pays for the one card it hands out. A sun pot pays a
            // bundle rather than a single sun - see SCARY_POT_SUN_DROPS - spread across the cell
            // it stood in, so the bundle reads as three objects rather than one.
            ResourceDef resource = BuiltInRegistries.RESOURCES.get(contents.id());
            if (resource != null) {
                spawnResourceBundle(contents.id(), resource, x, y);
            }
            bridge.send(new ServerMessageS2C("花瓶里掉出了阳光。"));
        } else {
            ZombieEntity zombie = spawnZombie(contents.id(), x + 0.5F, y);
            if (zombie != null) {
                // A pot is not a lane: a zombie it releases appears in the middle of the board, so
                // anything that counts how far it has walked starts from nothing (see
                // `ZombieCapability.onReleased`).
                zombie.onReleased(this);
            }
            bridge.send(new ServerMessageS2C("花瓶里跳出了一只僵尸。"));
        }
        return true;
    }

    /**
     * Drops one container's sun: {@link PvzceConstants#SCARY_POT_SUN_DROPS} suns in one cell.
     *
     * <p>Spread either side of the cell's centre by
     * {@link PvzceConstants#SCARY_POT_SUN_SPREAD}, so the bundle reads as three objects rather than
     * as one sun that has to be clicked three times. Every one of them still counts as standing in
     * the cell the pot was in (a drop's pickup radius is under half a cell, and the offsets are
     * written out rather than rolled: three suns are a bundle, not three independent drops, and the
     * same pot has to look the same on every attempt).
     *
     * <p>A bundle dropped in the last column is shifted inward by the same amount, because half of
     * a sun is nearly half a cell: without it, one sun of every bundle from the board's right-hand
     * edge hangs over the road. The shift keeps all three on the lawn and the middle one in the
     * pot's own cell - a drop's own grid cell is what the collection and the save read, and it is
     * the pot's cell either way.
     */
    private void spawnResourceBundle(Identifier resourceId, ResourceDef resource, int x, int y) {
        int drops = Math.max(1, PvzceConstants.SCARY_POT_SUN_DROPS);
        float spread = PvzceConstants.SCARY_POT_SUN_SPREAD;
        float shift = x >= width() - 1 ? -spread : 0F;
        for (int index = 0; index < drops; index++) {
            float offset = (index - (drops - 1) / 2F) * spread + shift;
            addEntity(new com.pvzce.server.entity.ResourceDropEntity(
                    resource, teams.get(PvzceIds.PLANT_TEAM), x, y, resource.defaultValue(),
                    null, offset));
        }
    }

    /**
     * What is inside each vase, keyed by cell.
     *
     * <p>Kept on the level rather than in a mechanic because it is not a mechanic: the vase is a
     * tool the player owns, not a level that declares something. It survives a save with the rest
     * of the scene, which is what a vase is.
     */
    private final Map<Long, Identifier> vaseContents = new java.util.HashMap<>();

    /**
     * What one sweep of the lawn took off it.
     *
     * @param plants  plants removed, riders and carriers alike
     * @param packets seed packets removed, the one in the player's hand included
     */
    public record LawnSweep(int plants, int packets) {
        public boolean empty() {
            return plants == 0 && packets == 0;
        }
    }

    /**
     * Sweeps the lawn clean: every plant, and every seed packet on it.
     *
     * <p>What the vase level does between rounds (see {@code ScaryPotterMechanic.tick}), and the
     * one place that removes things without a player asking: the shovel digs up what the player
     * points at, and this is the lawn being cleared for a board that is laid out over the cells
     * they were standing on. Carriers go with their riders, because both are entities in the same
     * cell - the same "one cell, several entities" the shovel's digging rule is written against.
     *
     * <p><b>Packets go too, the held one included.</b> A packet is a plant the player has been
     * given and has not spent; a round that starts by clearing the lawn and then leaves last
     * round's plants lying around as cards is not a clean lawn, it is a stockpile - and the next
     * round's plant pots are balanced against a player who starts it with nothing. The half-picked
     * packet in the player's hand is the same thing one step further along, so it goes with the
     * rest (the client is told the hand is empty; see {@code HeldCardS2C}).
     *
     * <p>Removal is the plain kind ({@code PlantEntity.remove}), deliberately: a sweep is not
     * twenty shovels, so nothing here refunds sun, triggers a death effect or leaves a drop. Last
     * round's plants are the last round's.
     */
    public LawnSweep clearLawn() {
        int plants = 0;
        int packets = 0;
        for (PvzceEntity entity : new ArrayList<>(entities)) {
            if (entity.isRemoved()) {
                continue;
            }
            if (entity instanceof PlantEntity plant) {
                plant.remove();
                plants++;
            } else if (entity instanceof com.pvzce.server.entity.CardDropEntity packet) {
                packet.remove();
                packets++;
            }
        }
        if (plants > 0 || packets > 0) {
            if (heldCardDropId >= 0) {
                // The hand is part of the sweep: the packet it holds has just been removed, and
                // leaving the client drawing a ghost of it would be a plant that can never be
                // put down.
                heldCardDropId = -1;
                packets = Math.max(packets, 1);
                send(HeldCardS2C.NONE);
            }
            flushPending();
        }
        return new LawnSweep(plants, packets);
    }

    /**
     * Tells the player a new round's pots are up, and what the sweep took to make room.
     *
     * <p>Says the sweep out loud when it happened: plants and cards disappearing on their own is
     * the kind of thing a player has to be told about, or it reads as a bug.
     */
    public void announceRound(int round, int rounds, LawnSweep sweep) {
        if (bridge == null) {
            return;
        }
        StringBuilder taken = new StringBuilder();
        if (sweep.plants() > 0) {
            taken.append("清掉了 ").append(sweep.plants()).append(" 株植物");
        }
        if (sweep.packets() > 0) {
            if (taken.length() > 0) {
                taken.append("、");
            }
            taken.append("收回了 ").append(sweep.packets()).append(" 张种子包");
        }
        String detail = taken.length() == 0 ? "" : "，" + taken;
        bridge.send(new ServerMessageS2C("第 " + round + "/" + rounds + " 回合：场地已清理"
                + detail + "，新的花瓶出现了。"));
    }

    /**
     * Stands a filled vase in a cell: the level's own way in, used by {@code pvzce:vase_field}.
     *
     * <p>Public because the mechanic that lays a board out is a layer above this one and must not
     * be able to build a vase cell by hand: a vase the level placed and a vase the player placed
     * have to be the same two writes (the scene element, and the contents block) in the same order,
     * or a save taken between them would restore a full vase with nothing in it.
     *
     * <p>No packet is sent. This runs while the level is being constructed, before there is a client
     * to tell - the first snapshot carries the finished board.
     */
    public void fillVase(int x, int y, Identifier card) {
        if (!inBounds(x, y) || card == null) {
            return;
        }
        vaseContents.put(cellKey(x, y), card);
        setScene(x, y, PvzceIds.VASE_FULL);
    }

    /** What is inside the vase in this cell, or {@code null} when there is no filled vase. */
    public Identifier vaseContentAt(int x, int y) {
        return vaseContents.get(cellKey(x, y));
    }

    /**
     * Puts a cell back to the terrain the board is made of, and tells the client.
     *
     * <p>What a smashed vase leaves behind, and what a broken scary pot leaves behind: one write
     * of the cell and one packet, in that order. It used to be two copies of the same pair, which
     * is exactly the shape that ends up with one copy forgetting the packet.
     */
    public void resetSceneCell(int x, int y) {
        setScene(x, y, defaultSceneElement().id());
        sendSceneCell(x, y);
    }

    /**
     * Hands one card to whatever is dealing this level's cards.
     *
     * <p>The vase's delivery path and the scary pot's: a card that came out of something the
     * player broke goes onto the bar through the card source - on a conveyor level the bar is the
     * belt's projection, so a card appended behind the belt's back is dropped by its next rebuild.
     * {@code false} means there was no room, which the callers turn into a message rather than
     * into silence.
     */
    public boolean deliverCard(Identifier card) {
        return cardSource != null && cardSource.receiveCard(this, bridge, card);
    }

    /**
     * Drops a seed packet where a broken container stood.
     *
     * <p>What a scary pot's plant and a smashed full vase hand over: the card stops being the
     * level's and becomes an object on the lawn the player has to pick up. It used to go straight
     * into the bar, which is why a second pot of a plant the player already had looked like a pot
     * that dropped nothing at all - there was nothing to see, and the bar had no room to show it.
     *
     * @param card  the card the container held; a plant id for the pots, and whatever a vase was
     *              filled with for the vase tool
     * @param x     the cell the container stood in
     */
    public void spawnCardDrop(Identifier card, int x, int y) {
        if (card == null || !inBounds(x, y)) {
            return;
        }
        addEntity(new com.pvzce.server.entity.CardDropEntity(card, teams.get(PvzceIds.PLANT_TEAM), x, y));
    }

    public void spawnFallingCardDrop(Identifier card, int x, int y) {
        if (com.pvzce.common.core.SlotResolver.resolve(card).isEmpty()) {
            return;
        }
        com.pvzce.server.entity.CardDropEntity drop = new com.pvzce.server.entity.CardDropEntity(
                card, teams.get(PvzceIds.PLANT_TEAM), x, y);
        drop.fallFromSky(height());
        addEntity(drop);
    }

    /** The card the player is carrying, or {@code null} when their hand is empty. */
    public Identifier heldCard() {
        com.pvzce.server.entity.CardDropEntity drop = heldCardDrop();
        return drop == null ? null : drop.card();
    }

    /** The packet the player is carrying, or {@code null}; one lookup for the state above. */
    private com.pvzce.server.entity.CardDropEntity heldCardDrop() {
        if (heldCardDropId < 0) {
            return null;
        }
        for (PvzceEntity entity : entities) {
            if (entity.id() == heldCardDropId
                    && entity instanceof com.pvzce.server.entity.CardDropEntity drop
                    && !drop.isRemoved()) {
                return drop;
            }
        }
        // The packet is gone from under the hand (the level was rebuilt, or it was picked up
        // twice): forget the hand rather than holding an id that answers nothing.
        heldCardDropId = -1;
        return null;
    }

    /**
     * Picks a seed packet up: the plant goes into the player's hand, and is planted by the next
     * click on a cell.
     *
     * <p><b>A plant is held; anything else goes to the bar.</b> A container may hold a card this
     * build cannot plant (a tool, in a vase an author filled by hand), and "held" would then be a
     * plant that can never be put down. The bar is where every card that is not a plant belongs,
     * and the packet that carried it is consumed either way.
     */
    public boolean pickUpCardDrop(ServerBridge bridge, int entityId) {
        return withBridge(bridge, () -> {
            if (!gameState.equals(GameStateS2C.RUNNING) || plantPlayer == null) {
                return false;
            }
            com.pvzce.server.entity.CardDropEntity drop = null;
            for (PvzceEntity entity : entities) {
                if (entity.id() == entityId
                        && entity instanceof com.pvzce.server.entity.CardDropEntity candidate
                        && !candidate.isRemoved()) {
                    drop = candidate;
                    break;
                }
            }
            if (drop == null || drop.held()) {
                return false;
            }
            if (hasCarry()) {
                bridge.send(new ServerMessageS2C("手上已经拿着一株植物了。"));
                return false;
            }
            if (heldCardDrop() != null) {
                bridge.send(new ServerMessageS2C("手上已经有一株植物了，先把它种下去。"));
                return false;
            }
            PlantDef plant = heldPlantOf(drop.card());
            if (plant == null) {
                // Not a plant: the card the container held goes onto the bar the way it used to,
                // and the packet is spent. Nothing else can be put into a hand that plants.
                boolean delivered = deliverCard(drop.card());
                drop.remove();
                bridge.send(new ServerMessageS2C(delivered
                        ? "捡起了一张卡，已经放进卡槽。"
                        : "捡起了一张卡，但卡槽已经放不下了。"));
                return true;
            }
            drop.setHeld(true);
            heldCardDropId = drop.id();
            bridge.send(new HeldCardS2C(drop.id(), drop.card().toString()));
            bridge.send(new ServerMessageS2C("捡起了 " + plant.id() + "，点草坪把它种下去。"));
            return true;
        });
    }

    /**
     * Plants the card in the player's hand on a cell.
     *
     * <p><b>Free.</b> The sun and the cooldown a card costs are what the bar charges for handing
     * a plant over; a packet is one the player has already earned by breaking the container it was
     * in, so the price was paid there. The plant appears the way any other does - the same
     * {@code spawnPlant}, so its own effects (a squash's fuse, a cherry bomb's blast) are the
     * plant's business rather than this method's.
     *
     * <p>A refused cell keeps the plant in hand and says why, which is the same shape every other
     * refused click has: the click cost nothing.
     */
    public boolean plantHeldCard(ServerBridge bridge, int x, int y) {
        return withBridge(bridge, () -> {
            com.pvzce.server.entity.CardDropEntity drop = heldCardDrop();
            if (drop == null) {
                return false;
            }
            PlantDef plant = heldPlantOf(drop.card());
            if (plant == null) {
                return false;
            }
            if (!inBounds(x, y)) {
                bridge.send(new ServerMessageS2C("不能在草坪外种植。"));
                return false;
            }
            if (isVaseAt(x, y)) {
                // The vase's own rule: a plant card on a vase is *stored*, not planted. A packet
                // in hand is one plant and one use, so storing it would have to put a use back in
                // the vase - more bookkeeping than the mechanic is worth, and the pot's plant is
                // meant to be planted.
                bridge.send(new ServerMessageS2C("手里这株植物不能放进花瓶。"));
                return false;
            }
            if (!canPlacePlant(plant, x, y)) {
                bridge.send(new ServerMessageS2C("该格不能种植。"));
                return false;
            }
            spawnPlant(plant, plantPlayer.team(), x, y);
            drop.remove();
            clearHeldCard(bridge);
            return true;
        });
    }

    /**
     * Puts a carried packet back on the lawn, where it fell.
     *
     * <p>The way out of a pick-up the player did not mean: without it, a packet picked up by
     * accident could only be spent somewhere, and a plant the level handed out would be planted
     * wherever the player finally gave up looking. Putting it back is one flag - the packet never
     * left its cell while it was in hand (the client is what draws it at the cursor) - and it keeps
     * the time it had left, because the clock was never running while it was held.
     */
    public boolean releaseHeldCard(ServerBridge bridge) {
        return withBridge(bridge, () -> {
            com.pvzce.server.entity.CardDropEntity drop = heldCardDrop();
            if (drop == null) {
                return false;
            }
            drop.setHeld(false);
            clearHeldCard(bridge);
            return true;
        });
    }

    /** Empties the hand and tells the client, which is what stops it drawing the ghost. */
    private void clearHeldCard(ServerBridge bridge) {
        heldCardDropId = -1;
        bridge.send(HeldCardS2C.NONE);
    }

    /**
     * The plant a container's card grants, or {@code null} when it grants something else.
     *
     * <p>Asked through the card resolver rather than by looking the id up among the plants: a
     * container may hold a <em>slot</em> id (the vase field's own {@code card} is one), and the
     * plant it grants is the slot's content. Resolving also answers "is this a plant at all",
     * which is the question that decides between the hand and the bar.
     */
    private static PlantDef heldPlantOf(Identifier card) {
        var resolved = com.pvzce.common.core.SlotResolver.resolve(card).orElse(null);
        Identifier content = resolved == null ? card : resolved.content();
        return BuiltInRegistries.PLANTS.get(content);
    }


    /**
     * The vases' contents for the save file: one entry per filled vase.
     *
     * <p>Only the filled ones. An empty vase is a {@code pvzce:vase} cell in the scene block that
     * is written next to this one, and a smashed one is a cell that says so - so what is left to
     * write down is exactly the vases whose picture and contents could disagree.
     */
    private ListTag saveVaseContents() {
        ListTag vases = new ListTag();
        for (Map.Entry<Long, Identifier> entry : vaseContents.entrySet()) {
            CompoundTag vaseTag = new CompoundTag();
            vaseTag.putInt("x", (int) (entry.getKey() >> 32));
            vaseTag.putInt("y", (int) (long) entry.getKey());
            vaseTag.putString("card", entry.getValue().toString());
            vases.add(vaseTag);
        }
        return vases;
    }

    /**
     * Reads back the vases' contents. Must tolerate a missing block: every save written before the
     * vase tool existed has none, and then no vase is filled - which is right, because there were
     * no vases to fill.
     */
    private void restoreVaseContents(ListTag vases) {
        vaseContents.clear();
        for (Tag element : vases.values()) {
            if (!(element instanceof CompoundTag vaseTag)) {
                continue;
            }
            Identifier card = Identifier.tryParse(vaseTag.getString("card"));
            if (card != null) {
                vaseContents.put(cellKey(vaseTag.getInt("x"), vaseTag.getInt("y")), card);
            }
        }
    }

    private static long cellKey(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    /**
     * One click of the watering can: the plant in the cell is watered.
     *
     * <p>What watering means is three things, and all three are data or already-existing state:
     *
     * <ul>
     *   <li><b>it is healed to full</b> - the garden's "watering keeps the plant alive", which on
     *       a lawn is the one way to repair a half-eaten wall-nut;</li>
     *   <li><b>it ripens</b> if a capability says so: the producer that has not grown up yet
     *       grows on the spot ({@code PlantCapability.water}), which is the sun-shroom's whole
     *       reason to be watered;</li>
     *   <li><b>it runs a quarter faster for {@value #WATERED_TICKS} ticks</b> - the same
     *       "well-watered" clock a garden plant's growth is on, and the reason to water
     *       something that is already grown.</li>
     * </ul>
     *
     * <p>A click on a cell with nothing in it is refused - {@code false} - so it costs neither
     * the cooldown nor a use, exactly like a mallet swung at empty grass.
     */
    private boolean waterPlant(int x, int y) {
        PlantEntity plant = plantAt(x, y);
        if (plant == null || plant.isRemoved()) {
            return false;
        }
        PlantEntity.Watering watering = plant.water(WATERED_TICKS, this);
        // Sound and splash at the plant's own position rather than the clicked cell: the plant
        // is drawn with its cell's offset (a lily pad rides low, a pot rides high), and a splash
        // at the cell's floor would come out of the lawn beside it.
        emitEffect(PvzceParticles.POOL_SPLASH.toString(), plant.cellX(), plant.cellY(),
                PvzceSounds.EFFECT_WATERING);
        // The glow used to be conditional on the watering having healed or ripened something -
        // which a plant that was already whole and already grown never does, so the commonest case
        // ("I watered a full-health peashooter") answered with a splash that is over in a quarter of
        // a second and nothing else. The player's report was exactly "I cannot tell whether it did
        // anything". The glow is now the answer to "the water landed on a plant": it says the click
        // was accepted, and the growth sound is what distinguishes the cases where it did more.
        emitEffect(PvzceParticles.POTTED_ZEN_GLOW.toString(),
                plant.cellX(), plant.cellY(),
                watering.ripened() || watering.healed() > 0 ? PvzceSounds.PLANT_GROW : null);
        return true;
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
        // One hand, not two: the glove may not start a lift while a seed packet is in it. Checked
        // before the move that is already in progress, because a carry of the glove's own has to
        // be finishable either way.
        if (heldCardDropId >= 0 && carriedPlantId < 0 && carriedVaseFrom < 0L
                && heldCardDrop() != null) {
            bridge.send(new ServerMessageS2C("手上已经有一株植物了，先把它种下去。"));
            return false;
        }
        if (carriedVaseFrom >= 0L) {
            return dropCarriedVase(x, y);
        }
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
            if (isVaseAt(x, y)) {
                return liftVase(x, y);
            }
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

    /** True when this cell's scene is a vase, full or empty. */
    private boolean isVaseAt(int x, int y) {
        Identifier id = sceneIdAt(x, y);
        return PvzceIds.VASE.equals(id) || PvzceIds.VASE_FULL.equals(id);
    }

    /**
     * The glove's other carry: a vase, which is a scene element rather than an entity.
     *
     * <p>The difference from a plant shows up in what "carrying" means. A plant is an entity and
     * simply stays where it is until the drop; a vase <em>is</em> its cell, so the cell it came from
     * has to be written back to bare lawn when it lands somewhere else. Nothing is cleared here, at
     * the lift, for the reason the plant carry documents: an abandoned move then has nothing to undo
     * - the vase never left. What travels in the hand is the pair (origin cell, card inside), which
     * is all the drop needs to move the vase and its contents together.
     */
    private boolean liftVase(int x, int y) {
        carriedVaseFrom = cellKey(x, y);
        carriedVaseCard = vaseContents.get(carriedVaseFrom);
        carryTimeoutTicks = CARRY_TIMEOUT_TICKS;
        // The cursor draws the vase, so what the player is holding is visible; the cell keeps its
        // own vase until the drop clears it.
        send(new CarrySyncS2C(PvzceIds.VASE.toString()));
        emitEffect(PvzceParticles.LANTERN_SHINE.toString(), x + 0.5F, y + 0.5F, PvzceSounds.UI_TAP);
        bridge.send(new ServerMessageS2C("已拿起花瓶，再点一次放下。"));
        return true;
    }

    /**
     * Puts the carried vase down, and takes it off the cell it came from.
     *
     * <p>The same rules a vase is placed under by the tool: a cell that is inside the board and
     * holds neither a plant nor another vase. The last one is what stops a vase from being dropped
     * on its own cell and quietly doubling its contents.
     */
    private boolean dropCarriedVase(int x, int y) {
        long from = carriedVaseFrom;
        if (from == cellKey(x, y)) {
            // Dropped back where it was: the cell never changed, so there is nothing to move. Asked
            // before the occupancy check, because its own cell is of course occupied - by the vase
            // in the player's hand.
            clearCarry();
            return true;
        }
        if (!inBounds(x, y) || plantAt(x, y) != null || isVaseAt(x, y)) {
            bridge.send(new ServerMessageS2C("不能放在这里。"));
            return false;
        }
        Identifier card = carriedVaseCard;
        setScene((int) (from >> 32), (int) from, defaultSceneElement().id());
        sendSceneCell((int) (from >> 32), (int) from);
        vaseContents.remove(from);
        if (card != null) {
            vaseContents.put(cellKey(x, y), card);
        }
        setScene(x, y, card == null ? PvzceIds.VASE : PvzceIds.VASE_FULL);
        sendSceneCell(x, y);
        clearCarry();
        emitEffect(PvzceParticles.DIRT_SMALL.toString(), x + 0.5F, y + 0.5F, PvzceSounds.PLANT_PLANT);
        return true;
    }

    /** True when the glove is holding something, plant or vase. */
    private boolean hasCarry() {
        return carriedPlantId >= 0 || carriedVaseFrom >= 0L;
    }

    /** True when this slot is the glove, by its tool effect rather than its id. */
    private static boolean isGlove(Slot slot) {
        ToolDef tool = BuiltInRegistries.TOOLS.get(slot.defId());
        return tool != null && "pvzce:glove".equals(tool.effect());
    }

    private void clearCarry() {
        boolean wasCarrying = hasCarry();
        carriedPlantId = -1;
        carriedVaseFrom = -1L;
        carriedVaseCard = null;
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
        // The preparation phase has no cooldowns. It is the one part of a level where the player
        // spends a fixed purse and nothing else is happening yet: the original's Last Stand is
        // built on that (its whole opening is "arrange a defence with the sun you were given"),
        // and a seed packet that makes the player stand there waiting is a wait with nothing
        // behind it - the level has not started. Answered here rather than at each till because
        // this is the one place a card's recharge is worked out, so every card source - the bar,
        // a belt, a tool - gets the same answer.
        if (isPreparing()) {
            return 0;
        }
        // Two multipliers, multiplied: the level's own and the mutations'. Kept apart so that
        // undoing a mutation only has to divide out its own half (see the rule's own doc).
        float factor = rules.getFloat(PvzceIds.RULE_SEED_COOLDOWN_MULTIPLIER)
                * rules.getFloat(PvzceIds.RULE_MUTATION_SEED_COOLDOWN_FACTOR);
        return CardCooldown.effective(slot.cooldownTicks(), factor);
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
        // The running level's own list, not the file's: it is the only one that knows about a
        // mechanic the *player* brought, and the overlay the client draws from it.
        LevelPayload payload = payloadFor(def, seedContext, mechanics);
        bridge.send(new LevelInitS2C(def.id().toString(), slotInfos(), waves.roundWaveTypes(), payload,
                humanTeamId.toString(), teamName(humanTeamId), PvzcePackets.PROTOCOL_VERSION));
        withBridge(bridge, () -> {
            emitEffect("", width() / 2F, height() / 2F, PvzceSounds.AMBIENT_READY_SET_PLANT, 1F, 1F);
            return null;
        });
        bridge.send(new SceneSyncS2C(SceneCells.forGrid(sceneIds())));
        for (TypedMechanic typed : mechanics) {
            LevelMechanics.sendState(typed, this, bridge);
        }
        // What is playing, for a client that arrived after the cue did: a resumed run, or a
        // second player joining. Sent after the init packet so the client has a level to attach
        // the track to, and harmless when the track is the one it already started - the music
        // controller ignores a start of the track that is already playing.
        if (currentMusicCue != null) {
            bridge.send(new MusicEventS2C(currentMusicCue.track(),
                    currentMusicCue.event().map(Identifier::toString).orElse(""),
                    currentMusicCue.loop(), false,
                    Math.max(0F, Math.min(1F, currentMusicCue.volume())),
                    Math.max(0F, currentMusicCue.fadeSeconds()), false));
        }
        bridge.send(waveProgressPacket());
        bridge.send(roundSyncPacket());
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
        for (PvzceEntity entity : entities) {
            if (entity instanceof PlantEntity plant && !plant.isRemoved()) {
                plant.syncEchoNetwork(this, bridge, true);
                var magnet = plant.capability(com.pvzce.common.capability.plant.MagnetCapability.class);
                if (magnet != null) {
                    var item = magnet.itemSnapshot(plant, tickCount());
                    if (item != null) bridge.send(item);
                }
            }
        }
        // After the entities, so a client that joins a run whose player is carrying a packet
        // learns both where the packet was and that it is in the hand rather than on the lawn.
        Identifier inHand = heldCard();
        bridge.send(inHand == null ? HeldCardS2C.NONE
                : new HeldCardS2C(heldCardDropId, inHand.toString()));
        if (!gameState.equals(GameStateS2C.RUNNING)) {
            bridge.send(new GameStateS2C(gameState, winner != null ? winner.toString() : ""));
        }
    }

    /**
     * The rhythm levels' run report, or "nothing to report" on every other level.
     *
     * <p>Read at the moment the level ends rather than streamed while it ran: this is the receipt,
     * and the last note's verdict is part of it. The streamed {@code Status} is the HUD's own copy
     * and is up to six ticks behind - which at the end of a song is the difference between the
     * streak the player watched and the one the receipt claims.
     */
    private GameStateS2C.RhythmScore rhythmScore() {
        if (com.pvzce.common.level.mechanic.RhythmMechanic.spawningChart(def) == null
                && !com.pvzce.common.level.mechanic.LevelMechanics.has(def,
                        PvzceIds.MECHANIC_RHYTHM)) {
            return GameStateS2C.RhythmScore.NONE;
        }
        return GameStateS2C.RhythmScore.of(
                com.pvzce.common.level.mechanic.RhythmMechanic.score(this));
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
        if (jevBrain != null) {
            // The worker thread and its HTTP client belong to this run's opponent, and a level the
            // player has left must not keep either alive.
            jevBrain.close();
            jevBrain = null;
        }
        bridge = null;
        gameState = "closed";
        pendingAdd.clear();
        awaitSpawnPacket.clear();
        pendingRemove.clear();
        entities.clear();
        waves.clearQueues();
        craterTimers.clear();
        iceTimers.clear();
    }

    // ------------------------------------------------------------------
    // Save / restore
    // ------------------------------------------------------------------

    private static final String KEY_ENTITIES = "Entities";
    private static final String KEY_KIND = "Kind";

    /**
     * Which side a saved run was played from, or the level's own default.
     *
     * <p>A static on the level rather than a field on a save DTO: the answer is needed before a
     * level exists (the side is a constructor input), and it is a fact about the save file, not
     * about a running level.
     */
    public static Identifier humanTeamFromSave(LevelDef def, CompoundTag root) {
        if (def == null || root == null || !root.contains("HumanTeam")) {
            return def == null ? PvzceIds.PLANT_TEAM : def.humanTeam();
        }
        Identifier saved = Identifier.tryParse(root.getString("HumanTeam"));
        return saved == null ? def.humanTeam() : saved;
    }

    public CompoundTag save() {
        CompoundTag root = new CompoundTag();
        root.putString("LevelId", def.id().toString());
        // Which side this run is being played from. It is not a preference the player can change
        // mid-run, and a save that did not carry it would resume a versus match with the two sides
        // swapped - the board is the same, but "which cards are mine" and "who wins" are not.
        root.putString("HumanTeam", humanTeamId.toString());
        root.putString("GameState", gameState);
        if (winner != null) {
            root.putString("Winner", winner.toString());
        }
        root.putInt("Tick", tickCount);
        root.putInt("FogBlownLeft", Math.max(0, fogBlownUntil - tickCount()));
        root.putLong("DayTicks", clock.dayTicks());
        root.putLong("MusicCuesFired", musicCuesFired);
        root.putInt("WavesStartTick", wavesStartTick);
        waves.save(root);
        // The sky's own countdown, not the team's sun total (which is written with the teams
        // below): a resumed run has to keep the gap it was in, or continuing a save hands the
        // player a free sun on the first tick.
        sunDropClock.save(root);
        // The run's own tally, so a resumed endless attempt keeps counting from where it was
        // rather than from zero.
        root.putInt("ZombieKills", zombieKills);
        // The live rules, not the level's written ones: a mutation rewrites rules while the level
        // runs ("it is night now" is four of them), and a save that kept only the definition would
        // hand the player a daylight board with mushrooms that had gone to sleep in it.
        java.util.Map<Identifier, Float> liveRules = rules.floatView();
        java.util.Map<Identifier, Float> forSave = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<Identifier, Float> entry : liveRules.entrySet()) {
            forSave.put(entry.getKey(), entry.getValue());
        }
        // The rules the file gets are the level's own, with every mutation's factor divided back
        // out: a mutation writes into a live rule, so a save that wrote the live value would be
        // read back by `applyFromSave` and multiplied a second time. The in-memory rules are put
        // straight back, so nothing that reads them in the same tick sees the unwound value.
        List<com.pvzce.common.level.mutation.MutationManager.RuleWrite> owned =
                mutations == null ? List.of() : mutations.rulesForSave(liveRules, forSave);
        // The world's difficulty comes out of the file for the same reason a mutation's factor
        // does, and the mutation pass has already restored the live values - this only touches what
        // is written, so the running level keeps playing with the tier in force. There is nothing
        // to write down: the profile already knows which tier it is.
        for (java.util.Map.Entry<Identifier, Float> factor : difficulty.ruleFactors().entrySet()) {
            Float folded = forSave.get(factor.getKey());
            if (folded != null && factor.getValue() > 0F) {
                forSave.put(factor.getKey(), folded / factor.getValue());
            }
        }
        root.put("Rules", rules.toNbt("Rules", forSave));
        for (com.pvzce.common.level.mutation.MutationManager.RuleWrite written : owned) {
            rules.set(written.rule(), written.value());
        }


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
        // Which vase holds which card. On the level rather than in a mechanic, so it is saved here
        // and not through `LevelMechanics.collectSave` - see `vaseContents` for why.
        root.put("VaseContents", saveVaseContents());
        if (cardSource != null) {
            cardSource.save(root);
        }
        // A mechanic with state of its own - anything that is not the card bar - writes it
        // through its own hook, so the save file grows with the mechanic rather than here.
        for (TypedMechanic typed : mechanics) {
            LevelMechanics.collectSave(typed, this, root);
        }
        // And the mutations, which are not mechanics but are per-run state in the same way: the
        // list, its clock, and whatever each one is counting down.
        if (mutations != null) {
            mutations.save(root);
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
    /**
     * Reads a run back into this level.
     *
     * <p>The save's {@code HumanTeam} is deliberately <em>not</em> applied here: the side decides
     * the seat and the card bar at construction time, so the caller that is resuming a versus run
     * has to read it first and construct the level with it - see
     * {@link #humanTeamFromSave(LevelDef, CompoundTag)}. Re-seating a built level would leave it
     * with the other side's bar.
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
        // The rules as they were being played, before anything reads them: the saved board was
        // saved under those rules, and a mutation that owns a share of them re-applies its own
        // factor afterwards (see MutationManager.restore). A save written before this block
        // existed keeps the level definition's rules, which is what it was played under.
        if (root.contains("Rules")) {
            rules.applySaved(root.getCompound("Rules"));
            // The file carries the level's own numbers; the tier is folded back on top, exactly
            // as it was at creation. Without this a resumed run would silently play at the
            // original's difficulty while the menu still showed the chosen one.
            applyDifficulty();
        }
        // A save written before the sky had a countdown has no block here, and then the clock
        // stays at the opening delay this level's own rules gave it (see the constructor).
        sunDropClock.restore(root);
        // A save written before the tally existed reads zero, which is what a run that had killed
        // nothing would say.
        zombieKills = Math.max(0, root.getInt("ZombieKills"));
        clock.setDayTicks(root.getLong("DayTicks"));
        waves.restore(root);
        restoreMusicCues(root);
        fogBlownUntil = tickCount() + root.getInt("FogBlownLeft");
        restoreScene(root.getList("Scene"));
        restoreVaseContents(root.getList("VaseContents"));
        restoreTeams(root.getCompound("Teams"));
        // Before restoreSlots: a self-dealt bar is a projection of the source, so the source
        // has to be restored first or its cards would have nothing to be put back into.
        if (cardSource != null) {
            cardSource.restore(this, root);
        }
        for (TypedMechanic typed : mechanics) {
            LevelMechanics.applySave(typed, this, root);
        }
        // Before the slots: a mutation may be dealing the cards, and then the bar the saved
        // cooldowns belong to is the mutation's (a belt's queue, rebuilt here) rather than the
        // deck's. `restoreSlots` matches by card index, so the bar has to be the right one first.
        if (mutations != null) {
            mutations.restore(root);
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
            // A seed packet is the one drop that is restored rather than dropped with the save:
            // it is a plant the player earned by breaking a container, and losing it to a quit
            // would be losing a card. A sun lying on the lawn is worth 25 and is not.
            case com.pvzce.api.entity.EntityKind.CARD_DROP ->
                    new com.pvzce.server.entity.CardDropEntity(defId,
                            teams.get(PvzceIds.PLANT_TEAM),
                            (int) Math.floor(tag.getFloat("x")), (int) Math.floor(tag.getFloat("y")));
            default -> null;
        };
    }

    /** Removes the default initial entities before a saved field snapshot is applied. */
    private void clearEntitiesForRestore() {
        entities.clear();
        pendingAdd.clear();
        pendingRemove.clear();
        // The hand is part of the entity list, so it goes with it: a restored run starts empty
        // handed and the packet comes back lying where it fell (see CardDropEntity).
        heldCardDropId = -1;
    }

}
