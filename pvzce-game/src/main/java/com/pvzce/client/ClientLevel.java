package com.pvzce.client;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.animation.AnimationManager;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.level.DayNightCycle;
import com.pvzce.common.level.SceneGrid;
import com.pvzce.common.network.packet.EffectEventS2C;
import com.pvzce.common.network.packet.SceneSyncS2C;
import com.pvzce.common.network.packet.SeedOption;
import com.pvzce.common.network.packet.SlotInfo;
import com.pvzce.common.network.packet.SuggestionsS2C;
import com.pvzce.common.network.packet.TimeOfDayS2C;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The client's mirror of the server level.
 *
 * <p>It holds only state the server streams; the shared {@link SceneGrid} and
 * {@link DayNightCycle} mean the board and the clock are described the same way on
 * both sides. {@link #init} and {@link #reset} both go through
 * {@link #clearTransientState()} - they used to repeat the field list by hand, and
 * had already drifted (only one of them cleared suggestions, messages and the
 * controlled team).
 */
public final class ClientLevel {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("PVZCE/ClientLevel");
    private final Map<Integer, ClientEntity> entities = new ConcurrentHashMap<>();
    private final List<SlotInfo> slots = new ArrayList<>();
    /** Whether a preparation phase is holding the waves; see {@link #preparing()}. */
    private volatile boolean preparing;
    private final List<String> messages = new ArrayList<>();
    /**
     * Resource balances per team, keyed by team then resource. Teams share resources
     * and display them separately, so the server's {@code ResourceDeltaS2C.teamId}
     * is honoured rather than ignored - the client used to drop every resource that
     * was not sun.
     */
    private final Map<Identifier, Map<Identifier, Integer>> teamResources = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<EffectEventS2C> effects = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<SuggestionsS2C> suggestions = new ConcurrentLinkedQueue<>();
    private final List<ResourceCollectAnimation> collectAnimations = new CopyOnWriteArrayList<>();

    private volatile List<SeedOption> seedPool = List.of();
    private volatile int maxSeedSlots = 6;
    /**
     * The level buffs this run is played with, as the server resolved them.
     *
     * <p>Mirrored rather than derived: the buff set decides what the simulation does, so it is
     * server state like the card bar is, and the HUD draws exactly the icons it was told to.
     */
    private volatile List<String> activeBuffs = List.of();
    private volatile List<String> previewZombies = List.of();
    private volatile SceneGrid<String> scene = SceneGrid.create(0, 0, PvzceIds.GRASS.toString());
    /** Cells whose element is out of place right now; see {@link SceneShifts}. */
    private final SceneShifts sceneShifts = new SceneShifts();
    /** The backdrop this level is played on, or {@code null} for the built-in yard. */
    /** The run's tally, from the state packet; zero until the level ends. */
    private volatile int wavesArrived;
    private volatile int kills;
    private volatile int survivedTicks;
    private volatile Identifier background;
    /** Scene elements this level does not draw; see {@link SceneVisibility}. */
    private volatile SceneVisibility sceneVisibility = SceneVisibility.NONE;
    /** True when this level turns the shader effects off whatever the player's setting is. */
    private volatile boolean shadersDisabled;
    /** The plant a glove is holding, or empty; see {@code CarrySyncS2C}. */
    private volatile String carriedPlant = "";
    /**
     * The seed packet the player is carrying: its entity id and the card it is, or {@code -1} and
     * empty when their hand is free.
     *
     * <p>Separate from {@link #carriedPlant} rather than folded into it, because the two carries
     * are answered differently - a carried plant goes back down through the glove card and a
     * carried packet through its own request - and because a packet keeps its entity: the id is
     * what tells the board to stop drawing it where it fell. See {@code HeldCardS2C}.
     */
    private volatile int heldCardEntityId = -1;
    private volatile String heldCardId = "";
    private volatile String levelId = "";
    private volatile int width = 9;
    private volatile int height = 5;
    private volatile String gameState = "running";
    private volatile String winTeam = "";
    private volatile String controlledTeam = PvzceIds.PLANT_TEAM.toString();
    private volatile String controlledTeamName = "";
    private volatile int currentWave;
    private volatile int totalWaves;
    /** The round the run is in, and how many waves it has released across every round. */
    private volatile int round = 1;
    private volatile int cumulativeWaves;
    /** True while the server has stopped between rounds, waiting for a card choice. */
    private volatile boolean roundClearPending;
    /**
     * True when the level generates its waves round after round.
     *
     * <p>Told by the server rather than guessed from the wave count: the round machinery is the
     * same for both kinds of level, so what makes a run endless is a fact the level knows and the
     * client does not.
     */
    private volatile boolean endless;
    private volatile float waveProgress;
    private volatile boolean waveWarningActive;
    private volatile boolean waveWarningFinal;
    private volatile List<String> waveTypes = List.of();
    private volatile TimeOfDayS2C timeOfDay = new TimeOfDayS2C(0, 0, -1);
    private volatile long timeAnchorNanos;
    private volatile long lastSyncedDayTicks;
    private volatile long lastSyncedNanos;
    private volatile double serverTicksPerNano = TICKS_PER_NANO;
    private volatile long debugTickCount;
    /** The running level's own counter, as last reported by the server's heartbeat. */
    private volatile int levelTickCount;
    private volatile float measuredTps;
    private volatile float targetTps = 60F;
    private volatile boolean serverFrozen;
    private volatile boolean serverSprinting;
    private volatile long lastDebugTickCount;
    private volatile long lastDebugNanos;
    private volatile long debugAnchorNanos;
    private volatile long debugAnchorTick;
    private volatile boolean initialized;
    /**
     * The mutations this level is running with, as the server last sent them.
     *
     * <p>Held as the packet rather than unpacked into fields because nothing on the client needs
     * the pieces separately: the panel draws every entry, and the overlays ask whole-set questions
     * ("is anything asking for darkness"). A mutation is not a mechanic - it comes and goes while
     * the level runs - which is why this is its own slot rather than a mechanic block.
     */
    private volatile com.pvzce.common.network.packet.MutationStateS2C mutations;

    /**
     * Per-mechanic run state, the client's mirror of {@code LevelServer.mechanicState}.
     *
     * <p>A client mechanic is a shared registry entry too, so a mechanic that draws something
     * that changes (where the mowers are) keeps its state here: the object is created once per
     * level instance by whichever hook asks first, and both the sync handler and the world
     * overlay get the same one.
     */
    private final java.util.Map<Identifier, Object> mechanicState = new java.util.HashMap<>();
    /**
     * The level's mechanics, as the server sent them: id to decoded block.
     *
     * <p>Held rather than reduced to a handful of fields. The client used to keep
     * {@code conveyor}/{@code beltCapacity}/{@code zone*} here, which meant every new
     * mechanic added four fields and a getter; now a mechanic's data is whatever its codec
     * decoded, and the client's HUD asks for it by id.
     */
    private final java.util.Map<Identifier, com.pvzce.api.content.mechanic.MechanicData> mechanics =
            new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Every decoded block, in the order the server sent them, duplicates included.
     *
     * <p>{@link #mechanics} cannot hold a mechanic a level declares twice - and one of them
     * legitimately can be: {@code pvzce:tool} is written once per tool the level hands over. The
     * map stays because the singular mechanics read better through it and their callers already
     * ask that way; this list is the complete record.
     */
    private volatile java.util.List<com.pvzce.api.content.mechanic.TypedMechanic> mechanicsInOrder =
            List.of();
    private volatile java.util.List<Identifier> mechanicOrder = List.of();
    private volatile String disconnectReason = "";
    private volatile AnimationManager animations;

    /** Ticks per nanosecond at the nominal rate, derived from the shared tick rate. */
    private static final double TICKS_PER_NANO =
            (double) com.pvzce.common.PvzceConstants.TICKS_PER_SECOND / 1_000_000_000D;

    /**
     * The same board with the built-in yard and nothing hidden.
     *
     * <p>For callers that predate a level having a look of its own; the board itself is the
     * whole of what they describe.
     */
    public void init(String levelId, int width, int height, List<SlotInfo> slots, List<String> waveTypes,
                     List<SeedOption> seedPool, int maxSeedSlots, List<String> previewZombies,
                     List<SceneSyncS2C.Cell> sceneCells, String controlledTeamId, String controlledTeamName,
                     List<com.pvzce.common.network.packet.LevelPayload.MechanicPayload> levelMechanics) {
        init(levelId, width, height, slots, waveTypes, seedPool, maxSeedSlots, previewZombies,
                sceneCells, controlledTeamId, controlledTeamName, levelMechanics, null, List.of(),
                false);
    }

    /**
     * Replaces the whole mirror with the server's full state. Every field the
     * server owns is written here; nothing is left over from the previous level.
     */
    public void init(String levelId, int width, int height, List<SlotInfo> slots, List<String> waveTypes,
                     List<SeedOption> seedPool, int maxSeedSlots, List<String> previewZombies,
                     List<SceneSyncS2C.Cell> sceneCells, String controlledTeamId, String controlledTeamName,
                     List<com.pvzce.common.network.packet.LevelPayload.MechanicPayload> levelMechanics,
                     Identifier background, List<String> hiddenSceneElements,
                     boolean shadersDisabled) {
        init(levelId, width, height, slots, waveTypes, seedPool, maxSeedSlots, previewZombies,
                sceneCells, controlledTeamId, controlledTeamName, levelMechanics, background,
                hiddenSceneElements, shadersDisabled, List.of());
    }

    /** The whole mirror, including the level buffs this run was started with. */
    public void init(String levelId, int width, int height, List<SlotInfo> slots, List<String> waveTypes,
                     List<SeedOption> seedPool, int maxSeedSlots, List<String> previewZombies,
                     List<SceneSyncS2C.Cell> sceneCells, String controlledTeamId, String controlledTeamName,
                     List<com.pvzce.common.network.packet.LevelPayload.MechanicPayload> levelMechanics,
                     Identifier background, List<String> hiddenSceneElements,
                     boolean shadersDisabled, List<String> activeBuffs) {
        clearTransientState();
        this.seedPool = List.copyOf(seedPool);
        this.maxSeedSlots = Math.max(0, maxSeedSlots);
        this.activeBuffs = List.copyOf(activeBuffs == null ? List.of() : activeBuffs);
        this.previewZombies = List.copyOf(previewZombies);
        this.levelId = levelId;
        this.width = width;
        this.height = height;
        this.background = background;
        this.sceneVisibility = SceneVisibility.of(hiddenSceneElements);
        this.shadersDisabled = shadersDisabled;
        applyMechanics(levelMechanics);
        this.scene = SceneGrid.create(width, height, PvzceIds.GRASS.toString());
        // Written without the rise bookkeeping: a level's opening graves were always there.
        writeSceneCells(sceneCells);
        sceneShifts.clear();
        synchronized (this.slots) {
            this.slots.clear();
            this.slots.addAll(slots);
        }
        this.waveTypes = List.copyOf(waveTypes);
        this.controlledTeam = controlledTeamId == null || controlledTeamId.isEmpty()
                ? PvzceIds.PLANT_TEAM.toString()
                : controlledTeamId;
        this.controlledTeamName = controlledTeamName == null ? "" : controlledTeamName;
        this.timeAnchorNanos = System.nanoTime();
        this.lastSyncedDayTicks = 0;
        this.lastSyncedNanos = timeAnchorNanos;
        this.serverTicksPerNano = TICKS_PER_NANO;
        this.initialized = true;
    }

    /** Drops everything that belongs to a level instance. Shared by init and reset. */
    private void clearTransientState() {
        entities.clear();
        effects.clear();
        synchronized (suggestions) {
            suggestions.clear();
        }
        synchronized (messages) {
            messages.clear();
        }
        collectAnimations.clear();
        teamResources.clear();
        synchronized (slots) {
            slots.clear();
        }
        suggestions.clear();
        preparing = false;
        seedPool = List.of();
        maxSeedSlots = com.pvzce.common.PvzceConstants.DEFAULT_SEED_SLOTS;
        previewZombies = List.of();
        scene = SceneGrid.create(0, 0, PvzceIds.GRASS.toString());
        sceneShifts.clear();
        background = null;
        sceneVisibility = SceneVisibility.NONE;
        shadersDisabled = false;
        carriedPlant = "";
        heldCardEntityId = -1;
        heldCardId = "";
        initialized = false;
        mechanics.clear();
        mechanicState.clear();
        mutations = null;
        mechanicOrder = List.of();
        mechanicsInOrder = List.of();
        gameState = "running";
        winTeam = "";
        disconnectReason = "";
        controlledTeam = PvzceIds.PLANT_TEAM.toString();
        controlledTeamName = "";
        waveTypes = List.of();
        currentWave = 0;
        totalWaves = 0;
        waveProgress = 0F;
        round = 1;
        cumulativeWaves = 0;
        roundClearPending = false;
        endless = false;
        waveWarningActive = false;
        waveWarningFinal = false;
        timeAnchorNanos = System.nanoTime();
        lastSyncedDayTicks = 0;
        lastSyncedNanos = timeAnchorNanos;
        serverTicksPerNano = TICKS_PER_NANO;
        timeOfDay = new TimeOfDayS2C(0, 0, -1);
        debugTickCount = 0;
        measuredTps = 60F;
        targetTps = 60F;
        serverFrozen = false;
        serverSprinting = false;
        lastDebugTickCount = -1;
        lastDebugNanos = -1;
        debugAnchorNanos = 0L;
        debugAnchorTick = 0L;
    }

    public void reset() {
        clearTransientState();
        levelId = "";
        width = 9;
        height = 5;
    }

    public boolean initialized() {
        return initialized;
    }

    /** The buffs this run is played with, in the order the server resolved them. */
    public List<String> activeBuffs() {
        return activeBuffs;
    }

    /**
     * Replaces the buff list from the server's own answer.
     *
     * <p>The level init used to be the only writer, which is why the icon row in the corner kept
     * showing the buffs a run started with even after the buff-shift mutation rewrote them mid
     * level. The mutation state packet carries the list now - see
     * {@code MutationStateS2C.activeBuffs} - and this is where it lands. The row re-reads it every
     * frame, so an icon appears or disappears on the frame the packet arrives.
     */
    public void setActiveBuffs(List<String> buffs) {
        this.activeBuffs = List.copyOf(buffs == null ? List.of() : buffs);
    }

    public String levelId() {
        return levelId;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public String gameState() {
        return gameState;
    }

    public String winTeam() {
        return winTeam;
    }

    public String controlledTeam() {
        return controlledTeam;
    }

    public String controlledTeamName() {
        return controlledTeamName;
    }

    public void setControlledTeam(String teamId, String teamName) {
        this.controlledTeam = teamId;
        this.controlledTeamName = teamName;
    }

    /** Non-empty when the connection to the server was lost. */
    public String disconnectReason() {
        return disconnectReason;
    }

    public void setDisconnected(String reason) {
        this.disconnectReason = reason == null ? "" : reason;
        if (!disconnectReason.isEmpty()) {
            addMessage("与服务器的连接已断开：" + disconnectReason);
        }
    }

    public Map<Integer, ClientEntity> entities() {
        return entities;
    }

    public List<SeedOption> seedPool() {
        return seedPool;
    }

    public int maxSeedSlots() {
        return maxSeedSlots;
    }

    public List<String> previewZombies() {
        return previewZombies;
    }

    /** Snapshot of the current scene grid, used by the seed chooser restart path. */
    public List<SceneSyncS2C.Cell> sceneCells() {
        List<SceneSyncS2C.Cell> cells = new ArrayList<>();
        for (SceneGrid.Cell<String> cell : scene.cells()) {
            cells.add(new SceneSyncS2C.Cell(cell.x(), cell.y(), cell.value()));
        }
        return List.copyOf(cells);
    }

    public void addCollectAnimation(ResourceCollectAnimation animation) {
        collectAnimations.add(animation);
    }

    public List<ResourceCollectAnimation> collectAnimations() {
        return List.copyOf(collectAnimations);
    }

    /** Removes animations whose fly duration has elapsed. */
    public void pruneCollectAnimations(long nowNanos) {
        collectAnimations.removeIf(animation -> animation.finished(nowNanos));
    }

    public void setAnimationManager(AnimationManager manager) {
        this.animations = manager;
    }

    public AnimationManager animations() {
        return animations;
    }

    /** Adds an entity and injects the client animation manager. */
    public ClientEntity addEntity(ClientEntity entity) {
        if (animations != null) {
            entity.attachAnimationManager(animations);
        }
        entity.setGridBounds(width, height);
        entities.put(entity.id(), entity);
        return entity;
    }

    public void removeEntity(int entityId) {
        ClientEntity removed = entities.remove(entityId);
        if (removed != null && animations != null) {
            animations.stop(removed);
        }
    }

    public List<SlotInfo> slots() {
        synchronized (slots) {
            return List.copyOf(slots);
        }
    }

    public void upsertSlot(SlotInfo info) {
        synchronized (slots) {
            for (int i = 0; i < slots.size(); i++) {
                if (slots.get(i).index() == info.index()) {
                    slots.set(i, info);
                    return;
                }
            }
            slots.add(info);
        }
    }

    /**
     * Replaces the whole bar.
     *
     * <p>What a conveyor belt needs and an upsert cannot say: a card that has been spent
     * is gone, and a bar built by upserts would keep showing it as usable forever.
     */
    public void replaceSlots(List<SlotInfo> newSlots) {
        synchronized (slots) {
            slots.clear();
            slots.addAll(newSlots);
        }
    }

    /**
     * Decodes the mechanics the server sent and stores them by id.
     *
     * <p>A block this client cannot decode - an unknown mechanic from a mod it does not
     * have, or a malformed one - is skipped with a log rather than refusing the level: the
     * board is fully described by the rest of the payload, so the worst case is a missing
     * piece of HUD.
     */
    private void applyMechanics(List<com.pvzce.common.network.packet.LevelPayload.MechanicPayload> levelMechanics) {
        mechanics.clear();
        List<Identifier> order = new java.util.ArrayList<>();
        List<com.pvzce.api.content.mechanic.TypedMechanic> blocks = new java.util.ArrayList<>();
        for (var payload : levelMechanics) {
            var data = com.pvzce.common.level.mechanic.LevelMechanics
                    .decodeBlock(payload.type(), payload.block());
            if (data.isEmpty()) {
                LOGGER.warn("[mechanics] cannot decode the block for {}; skipping it", payload.type());
                continue;
            }
            mechanics.put(payload.type(), data.get());
            order.add(payload.type());
            blocks.add(new com.pvzce.api.content.mechanic.TypedMechanic(payload.type(), data.get()));
        }
        mechanicOrder = List.copyOf(order);
        mechanicsInOrder = List.copyOf(blocks);
    }

    /** The ids of this level's mechanics, in the order the level declares them. */
    /** The mutation state, or {@code null} on a level that does not mutate. */
    public com.pvzce.common.network.packet.MutationStateS2C mutations() {
        return mutations;
    }

    /** Applies one mutation state update; the whole set arrives at once. */
    public void setMutations(com.pvzce.common.network.packet.MutationStateS2C state) {
        this.mutations = state;
        // The buffs ride in the same packet: a mutation is what changes them, and the panel that
        // draws the mutations is what the player is already watching. See setActiveBuffs.
        setActiveBuffs(state == null ? List.of() : state.activeBuffs());
    }

    /**
     * Whether the wave at that index is a huge (or final) one, i.e. carries a flag on the meter.
     *
     * <p>Asked by index rather than handing the whole list to the HUD: a level's wave table may be
     * thousands of entries long (an endless level's is), and a caller that copies it to look at one
     * element is copying thousands of strings a frame.
     */
    public boolean isHugeWave(int index) {
        String type = waveTypeAt(index);
        return "huge".equals(type) || "final".equals(type);
    }

    /** Whether the wave at that index is the level's final one. */
    public boolean isFinalWave(int index) {
        return "final".equals(waveTypeAt(index));
    }

    /**
     * The waves a meter of {@code capacity} flags should plant one for, out of {@code total}.
     *
     * <p>Pure, and static, because it is the part worth pinning: the caller draws from this list
     * and nothing else, so "the meter never walks the whole table" is a property of this method
     * rather than of a rendering loop. It was written the other way round first - the loop walked
     * every wave in the level - which on an endless level's four-thousand-entry table was 46ms of a
     * 55ms frame, every frame.
     *
     * <p>The window is centred on where the player is, so the flags they can see are the ones near
     * their position; the cap is what keeps the count bounded whatever the table holds.
     *
     * @param total    how many waves the level has
     * @param current  the wave the run is on
     * @param capacity the most flags the meter may plant in total
     * @return the wave indices to consider, in ascending order
     */
    public static int[] waveFlagCandidates(int total, int current, int capacity) {
        int waves = Math.max(0, total);
        int here = Math.max(0, Math.min(waves, current));
        int cap = Math.max(1, capacity);
        if (waves <= cap) {
            int[] all = new int[waves];
            for (int i = 0; i < waves; i++) {
                all[i] = i;
            }
            return all;
        }
        // Half the budget either side of the player: the flags in front matter as much as the ones
        // behind, and a window anchored at the start would leave the far end of the meter bare.
        int half = cap / 2;
        int first = Math.max(0, Math.min(waves - cap, here - half));
        int last = Math.min(waves, first + cap);
        int step = Math.max(1, (last - first) / cap);
        int count = (last - first + step - 1) / step;
        int[] picked = new int[count];
        for (int i = 0; i < count; i++) {
            picked[i] = first + i * step;
        }
        return picked;
    }

    /** The type of one wave, or {@code "small"} for an index this level does not have. */
    private String waveTypeAt(int index) {
        List<String> types = waveTypes;
        return index >= 0 && index < types.size() ? types.get(index) : "small";
    }

    /** True while the server says a mutation is dealing the cards. */
    /**
     * True while the level is holding its waves for a preparation phase.
     *
     * <p>The server's own state, streamed by {@code PreparationClientMechanic}: the client draws
     * the start button and the phase's hint from it, and the server is what decides whether the
     * press does anything - so a client that has not heard the latest value can be wrong about the
     * label but never about the rule.
     */
    public boolean preparing() {
        return preparing;
    }

    public void setPreparing(boolean preparing) {
        this.preparing = preparing;
    }

    public boolean mutatedCardBar() {
        com.pvzce.common.network.packet.MutationStateS2C state = mutations;
        return state != null && "mutated".equals(state.cardBarKind());
    }

    /** The union of the running mutations' client effects, or 0 when none are running. */
    public int mutationEffects() {
        com.pvzce.common.network.packet.MutationStateS2C state = mutations;
        return state == null ? 0 : state.effects();
    }

    public List<Identifier> mechanicIds() {
        return mechanicOrder;
    }

    /**
     * Every decoded block of one mechanic, in declaration order.
     *
     * <p>A different question from {@link #mechanicData}: that one asks "the block of the mower
     * mechanic", of which a level has at most one, while a mechanic like {@code pvzce:tool} is
     * written several times - once per tool the level hands over. The map keeps the last of a
     * repeated id, which is fine for the singular mechanics, so the list is kept beside it
     * rather than replacing it.
     */
    public List<com.pvzce.api.content.mechanic.MechanicData> mechanicBlocks(Identifier mechanicId) {
        List<com.pvzce.api.content.mechanic.MechanicData> blocks = new java.util.ArrayList<>();
        for (var entry : mechanicsInOrder) {
            if (entry.type().equals(mechanicId)) {
                blocks.add(entry.value());
            }
        }
        return List.copyOf(blocks);
    }

    /** True when this level declares that mechanic. */
    public boolean hasMechanic(Identifier mechanicId) {
        return mechanics.containsKey(mechanicId);
    }

    /** One mechanic's decoded block, or {@code null} when the level does not declare it. */
    public <D extends com.pvzce.api.content.mechanic.MechanicData> D mechanicData(
            Identifier mechanicId, Class<D> type) {
        var data = mechanics.get(mechanicId);
        return type.isInstance(data) ? type.cast(data) : null;
    }

    /** One mechanic's own run state; see {@link #mechanicState}. */
    @SuppressWarnings("unchecked")
    public <T> T mechanicState(Identifier mechanicId, java.util.function.Supplier<T> create) {
        return (T) mechanicState.computeIfAbsent(mechanicId, id -> create.get());
    }

    /** Writes a mechanic's own run state; the counterpart of {@link #mechanicState}. */
    public void setMechanicState(Identifier mechanicId, Object state) {
        mechanicState.put(mechanicId, state);
    }

    /**
     * A mechanic's run state if it has one, without creating it.
     *
     * <p>The difference from {@link #mechanicState} matters for anything read per frame: creating
     * the slot as a side effect of asking whether it exists would make a level that never synced
     * the mechanic look like one that had, and a renderer must not mutate what it draws.
     */
    public <T> T mechanicStateOrNull(Identifier mechanicId, Class<T> type) {
        Object state = mechanicState.get(mechanicId);
        return type.isInstance(state) ? type.cast(state) : null;
    }

    /** The level's plantable area, or the whole board when it restricts nothing. */
    public com.pvzce.api.content.PlacementZone placementZone() {
        com.pvzce.api.content.PlacementZone zone = mechanicData(
                com.pvzce.common.PvzceIds.MECHANIC_PLACEMENT_ZONE, com.pvzce.api.content.PlacementZone.class);
        return zone == null ? com.pvzce.api.content.PlacementZone.FULL : zone;
    }

    /** True when this cell is inside the level's plantable area. */
    public boolean inPlacementZone(int x, int y) {
        return placementZone().contains(x, y);
    }

    /** Balance of one resource for one team. */
    public int resource(Identifier teamId, Identifier resourceId) {
        if (teamId == null || resourceId == null) {
            return 0;
        }
        return teamResources.getOrDefault(teamId, Map.of()).getOrDefault(resourceId, 0);
    }

    /** Balance of one resource for the team the player controls. */
    public int resource(Identifier resourceId) {
        return resource(controlledTeamId(), resourceId);
    }

    /** Every balance of the controlled team. */
    public Map<Identifier, Integer> resources() {
        return Map.copyOf(teamResources.getOrDefault(controlledTeamId(), Map.of()));
    }

    public void setResource(Identifier teamId, Identifier resourceId, int amount) {
        if (teamId == null || resourceId == null) {
            return;
        }
        teamResources.computeIfAbsent(teamId, ignored -> new ConcurrentHashMap<>()).put(resourceId, amount);
    }

    /** The controlled team as an id (falls back to the plant team). */
    public Identifier controlledTeamId() {
        Identifier id = Identifier.tryParse(controlledTeam);
        return id == null ? PvzceIds.PLANT_TEAM : id;
    }

    public int sun() {
        return resource(PvzceIds.SUN);
    }

    public void setSun(int sun) {
        setResource(controlledTeamId(), PvzceIds.SUN, sun);
    }

    public void setGameState(String state, String winTeam) {
        this.gameState = state;
        this.winTeam = winTeam;
    }

    /** The run's tally as the server last reported it: waves, kills, ticks. */
    public void setRunSummary(int wavesArrived, int kills, int survivedTicks) {
        this.wavesArrived = wavesArrived;
        this.kills = kills;
        this.survivedTicks = survivedTicks;
    }

    /** How many waves this run released. */
    public int wavesArrived() {
        return wavesArrived;
    }

    /** Zombies killed this run. */
    public int kills() {
        return kills;
    }

    /** How long the run lasted, in ticks. */
    public int survivedTicks() {
        return survivedTicks;
    }

    public void addMessage(String message) {
        synchronized (messages) {
            messages.add(message);
            while (messages.size() > 6) {
                messages.remove(0);
            }
        }
    }

    public List<String> messages() {
        synchronized (messages) {
            return List.copyOf(messages);
        }
    }

    /**
     * Applies cell changes that happened <em>during play</em>.
     *
     * <p>The distinction from the opening board is what makes a tombstone that the
     * {@code grave_spawner} raises come up out of the lawn: a cell that changes while the
     * level runs can be animated, and one that was already painted when the level arrived
     * cannot (see {@link SceneShifts}, and {@link #init} for the other half of the rule).
     */
    public void applyScene(List<SceneSyncS2C.Cell> cells) {
        if (cells == null) {
            return;
        }
        for (SceneSyncS2C.Cell cell : cells) {
            String previous = scene.get(cell.x(), cell.y());
            writeSceneCell(cell);
            sceneShifts.cellChanged(cell.x(), cell.y(), previous, cell.elementId(), gameSeconds());
        }
    }

    /** Writes scene cells without asking whether any of them should animate. */
    private void writeSceneCells(List<SceneSyncS2C.Cell> cells) {
        if (cells == null) {
            return;
        }
        for (SceneSyncS2C.Cell cell : cells) {
            writeSceneCell(cell);
        }
    }

    private void writeSceneCell(SceneSyncS2C.Cell cell) {
        scene.set(cell.x(), cell.y(), cell.elementId());
    }

    /**
     * The backdrop this level is played on, or {@code null} when it uses the built-in yard.
     *
     * <p>A texture id, and deliberately not a "theme": every stage the original draws - day,
     * night, pool, fog, roof, the boss arena - is the same 1400x600 picture with the board in
     * the same place, so a backdrop is a picture and nothing else moves with it.
     */
    public Identifier background() {
        // A mutation may ask for a different backdrop (the pool after dark), and that request rides
        // the mutation state rather than the level: the picture is the client's, and nothing else
        // on the wire could carry it. The geometry is unaffected - both pool backdrops are the same
        // 1400x600 canvas with the basin in the same place (see LevelStage.geometryFor).
        com.pvzce.common.network.packet.MutationStateS2C state = mutations;
        if (state != null) {
            for (com.pvzce.common.level.mutation.MutationEffects effect
                    : com.pvzce.common.level.mutation.MutationEffects.values()) {
                if (effect.isSet(state.effects()) && effect.backdrop().isPresent()) {
                    return effect.backdrop().get();
                }
            }
        }
        return background;
    }

    /** True when this level does not draw the given scene element; see {@link SceneVisibility}. */
    public boolean hidesSceneElement(String elementId) {
        return sceneVisibility.hides(elementId);
    }

    /** Which scene elements this level draws at all; see {@link SceneVisibility}. */
    public SceneVisibility sceneVisibility() {
        return sceneVisibility;
    }

    /**
     * The plant a glove is holding, or empty.
     *
     * <p>The client's copy of a server decision, sent on every change: while it is set, the
     * board draws that plant's art at the cursor instead of in a cell.
     */
    public String carriedPlant() {
        return carriedPlant;
    }

    /** Applies the server's carry state; see {@code CarrySyncS2C}. */
    public void setCarriedPlant(String plantId) {
        carriedPlant = plantId == null ? "" : plantId;
    }

    /**
     * The card of the seed packet in the player's hand, or empty.
     *
     * <p>While it is set, the board draws that packet's card at the cursor and the packet's own
     * entity is skipped where it fell; a click on the lawn asks the server to plant it.
     */
    public String heldCard() {
        return heldCardId;
    }

    /** The entity the held packet is, or {@code -1}: the one the lawn must not draw. */
    public int heldCardEntityId() {
        return heldCardEntityId;
    }

    /** True when a seed packet is in the player's hand. */
    public boolean holdingCard() {
        return !heldCardId.isEmpty();
    }

    /** Applies the server's held-packet state; see {@code HeldCardS2C}. */
    public void setHeldCard(int entityId, String cardId) {
        this.heldCardEntityId = cardId == null || cardId.isEmpty() ? -1 : entityId;
        this.heldCardId = cardId == null ? "" : cardId;
    }

    /**
     * True when this level asks for the shader effects to be off.
     *
     * <p>The level's word beats the player's setting in one direction only: a level that says
     * {@code disable_shaders} runs without them even for a player who has them on, and a level
     * that says nothing follows the player. See {@code PvzceClient.shadersEnabled}.
     */
    public boolean shadersDisabled() {
        return shadersDisabled;
    }

    /**
     * Cells whose element is out of place right now; see {@link SceneShifts}.
     *
     * <p>{@link #syncSceneShifts} has to have run for this frame before it is asked.
     */
    public SceneShifts sceneShifts() {
        return sceneShifts;
    }

    /**
     * Recomputes what is out of place on the board.
     *
     * <p>Called once per frame by the board rather than driven by packets: half of the answer is
     * a clock and the other half is the state of the plants standing on graves.
     */
    public void syncSceneShifts() {
        sceneShifts.sync(gameSeconds(), entities.values());
    }

    /** The scene element id in a cell; outside the board it reads as grass. */
    public String sceneAt(int x, int y) {
        String value = scene.get(x, y);
        return value == null ? PvzceIds.GRASS.toString() : value;
    }

    public void addEffect(EffectEventS2C effect) {
        effects.add(effect);
        while (effects.size() > 128) {
            effects.poll();
        }
    }

    public ConcurrentLinkedQueue<EffectEventS2C> effects() {
        return effects;
    }

    public void addSuggestions(SuggestionsS2C packet) {
        suggestions.add(packet);
        while (suggestions.size() > 4) {
            suggestions.poll();
        }
    }

    public ConcurrentLinkedQueue<SuggestionsS2C> suggestions() {
        return suggestions;
    }

    public int currentWave() {
        return currentWave;
    }

    public int totalWaves() {
        return totalWaves;
    }

    public float waveProgress() {
        return waveProgress;
    }

    public boolean waveWarningActive() {
        return waveWarningActive;
    }

    public boolean waveWarningFinal() {
        return waveWarningFinal;
    }

    /** Kept for the F3 overlay; true while the final-wave warning is on screen. */
    public boolean finalWave() {
        return waveWarningActive && waveWarningFinal;
    }

    public List<String> waveTypes() {
        return waveTypes;
    }

    public void setWaveProgress(int currentWave, int totalWaves, float waveProgress,
                                boolean warningActive, boolean finalWarning, int round) {
        this.currentWave = currentWave;
        this.totalWaves = totalWaves;
        this.waveProgress = waveProgress;
        this.waveWarningActive = warningActive;
        this.waveWarningFinal = finalWarning;
        this.round = round;
    }

    /**
     * Applies a round sync: which round the run is in, how long it is, and its wave list.
     *
     * <p>The wave list has to travel with the round on an endless level, because the client's
     * meter draws its flags from the level's wave types - and those were sent once, when the
     * level started, from the round the player entered. A round that changed without a new list
     * would keep drawing the previous round's flags.
     */
    public void setRound(com.pvzce.common.network.packet.RoundSyncS2C sync) {
        this.endless = sync.endless();
        this.round = sync.round();
        this.totalWaves = sync.wavesInRound();
        this.cumulativeWaves = sync.cumulativeWaves();
        this.roundClearPending = sync.roundClearPending();
        this.waveWarningActive = false;
        this.waveWarningFinal = false;
        if (!sync.waveTypes().isEmpty()) {
            this.waveTypes = java.util.List.copyOf(sync.waveTypes());
            this.currentWave = 0;
            this.waveProgress = 0F;
        }
    }

    /** The round the run is in, one-based; 1 on a level that does not generate its waves. */
    public int round() {
        return round;
    }

    /** How many waves the run has released in total, across every round. */
    public int cumulativeWaves() {
        return cumulativeWaves;
    }

    /** True while the level is frozen between rounds, waiting for the next card selection. */
    public boolean roundClearPending() {
        return roundClearPending;
    }

    /**
     * True when the level is played in rounds at all.
     *
     * <p>What the HUD asks before it draws a round line: an ordinary level's meter is already the
     * whole run, and the server sends round 1 with a wave count for those too (the director's
     * answer for a level with a table), so the number alone cannot tell the two apart. A round
     * count above one is only ever produced by a level that generates its waves.
     */
    public boolean runsInRounds() {
        return endless;
    }

    public TimeOfDayS2C timeOfDay() {
        return timeOfDay;
    }

    /**
     * Records a server time sync and estimates the current server tick rate from
     * the last two syncs, so interpolation stays smooth after {@code /tick rate}.
     */
    public void setTimeOfDay(TimeOfDayS2C packet) {
        long now = System.nanoTime();
        long dayTicks = packet.dayTicks();
        if (lastSyncedNanos > 0 && now > lastSyncedNanos && dayTicks >= lastSyncedDayTicks) {
            long deltaTicks = dayTicks - lastSyncedDayTicks;
            long deltaNanos = now - lastSyncedNanos;
            if (deltaNanos > 0 && deltaTicks <= deltaNanos * 10_000L / 1_000_000_000L) {
                serverTicksPerNano = Math.max(1D / 1_000_000_000D,
                        Math.min(10_000D / 1_000_000_000D, (double) deltaTicks / deltaNanos));
            }
        }
        this.timeOfDay = packet;
        this.timeAnchorNanos = now;
        this.lastSyncedDayTicks = dayTicks;
        this.lastSyncedNanos = now;
    }

    /** Extrapolated continuous day tick (server time advances between syncs). */
    public float smoothDayTicks() {
        TimeOfDayS2C packet = timeOfDay;
        long deltaNanos = System.nanoTime() - timeAnchorNanos;
        return packet.dayTicks() + (float) (deltaNanos * serverTicksPerNano);
    }

    /** Authoritative server target rate, synced by {@code GameSpeedS2C}. */
    public void setTargetTickRate(float targetTps) {
        this.targetTps = targetTps;
    }

    /** F3 heartbeat: measures the real tick rate with an exponential moving average. */
    public void setDebugInfo(long tickCount, int levelTick, boolean frozen, boolean sprinting) {
        long now = System.nanoTime();
        if (lastDebugTickCount >= 0 && lastDebugNanos > 0 && now > lastDebugNanos) {
            long deltaTicks = Math.max(0L, tickCount - lastDebugTickCount);
            float instant = (float) (deltaTicks * 1_000_000_000D / (now - lastDebugNanos));
            measuredTps = measuredTps <= 0F ? instant : measuredTps * 0.7F + instant * 0.3F;
        }
        this.debugTickCount = tickCount;
        // The running level's own clock, which is what per-level moments are written against (the
        // tutorial's timed lines). Not derived from `tickCount`: that one counts across levels.
        this.levelTickCount = Math.max(0, levelTick);
        this.serverFrozen = frozen;
        this.serverSprinting = sprinting;
        this.lastDebugTickCount = tickCount;
        this.lastDebugNanos = now;
        this.debugAnchorTick = tickCount;
        this.debugAnchorNanos = now;
    }

    /**
     * Interpolated server tick count. Frozen servers do not advance; sprinting or
     * changed tick rates are followed through the measured TPS estimate.
     */
    public double smoothGameTicks() {
        long anchorNanos = debugAnchorNanos;
        if (anchorNanos <= 0L || serverFrozen) {
            return debugTickCount;
        }
        long elapsed = Math.max(0L, Math.min(2_000_000_000L, System.nanoTime() - anchorNanos));
        float tps = measuredTps > 0F ? measuredTps : Math.max(1F, targetTps);
        return debugAnchorTick + elapsed / 1_000_000_000D * tps;
    }

    /** World animation clock: 60 game ticks per game second. */
    public double gameSeconds() {
        return smoothGameTicks() / 60D;
    }

    public long debugTickCount() {
        return debugTickCount;
    }

    /** The running level's own tick counter; zero when there is no level. */
    public int levelTickCount() {
        return levelTickCount;
    }

    /**
     * The level's own clock, interpolated between heartbeats.
     *
     * <p>The heartbeat carries both counters (see {@link #setDebugInfo}), so their difference is how
     * far the world has moved since the last one - and adding that to the level's counter gives a
     * smooth, level-relative tick that is right to within the heartbeat's own accuracy.
     *
     * <p>It is what the rhythm levels judge against: their notes are written in level ticks, and a
     * judgement made on the 250-millisecond heartbeat alone would be four ticks coarse in one
     * direction - wider than the perfect window, which is the difference between a mode and a
     * lottery.
     */
    public double smoothLevelTicks() {
        return levelTickCount + (smoothGameTicks() - debugTickCount);
    }

    public float measuredTps() {
        return measuredTps;
    }

    public float targetTps() {
        return targetTps;
    }

    public boolean serverFrozen() {
        return serverFrozen;
    }

    public boolean serverSprinting() {
        return serverSprinting;
    }

    /**
     * Smooth 0..1 night factor. Delegates to the shared {@link DayNightCycle}, so
     * the client's lighting and the server's hard "is it night" answer - used for
     * grave spawning - can never disagree at the dusk boundary.
     */
    public float nightBlendAt(float dayTicks) {
        TimeOfDayS2C packet = timeOfDay;
        return DayNightCycle.nightBlend((long) Math.floor(dayTicks), packet.dayLength(), packet.nightLength());
    }

    /** Hard day/night answer for the interpolated clock. */
    public boolean isNightAt(float dayTicks) {
        TimeOfDayS2C packet = timeOfDay;
        return DayNightCycle.isNight((long) Math.floor(dayTicks), packet.dayLength(), packet.nightLength());
    }
}
