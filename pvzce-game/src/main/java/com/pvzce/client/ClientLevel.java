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
    private volatile List<String> previewZombies = List.of();
    private volatile SceneGrid<String> scene = SceneGrid.create(0, 0, PvzceIds.GRASS.toString());
    private volatile String levelId = "";
    private volatile int width = 9;
    private volatile int height = 5;
    private volatile String gameState = "running";
    private volatile String winTeam = "";
    private volatile String controlledTeam = PvzceIds.PLANT_TEAM.toString();
    private volatile String controlledTeamName = "";
    private volatile int currentWave;
    private volatile int totalWaves;
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
     * Replaces the whole mirror with the server's full state. Every field the
     * server owns is written here; nothing is left over from the previous level.
     */
    public void init(String levelId, int width, int height, List<SlotInfo> slots, List<String> waveTypes,
                     List<SeedOption> seedPool, int maxSeedSlots, List<String> previewZombies,
                     List<SceneSyncS2C.Cell> sceneCells, String controlledTeamId, String controlledTeamName,
                     List<com.pvzce.common.network.packet.LevelPayload.MechanicPayload> levelMechanics) {
        clearTransientState();
        this.seedPool = List.copyOf(seedPool);
        this.maxSeedSlots = Math.max(0, maxSeedSlots);
        this.previewZombies = List.copyOf(previewZombies);
        this.levelId = levelId;
        this.width = width;
        this.height = height;
        applyMechanics(levelMechanics);
        this.scene = SceneGrid.create(width, height, PvzceIds.GRASS.toString());
        applyScene(sceneCells);
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
        seedPool = List.of();
        maxSeedSlots = com.pvzce.common.PvzceConstants.DEFAULT_SEED_SLOTS;
        previewZombies = List.of();
        scene = SceneGrid.create(0, 0, PvzceIds.GRASS.toString());
        initialized = false;
        mechanics.clear();
        mechanicState.clear();
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

    public void applyScene(List<SceneSyncS2C.Cell> cells) {
        if (cells == null) {
            return;
        }
        for (SceneSyncS2C.Cell cell : cells) {
            scene.set(cell.x(), cell.y(), cell.elementId());
        }
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
                                boolean warningActive, boolean finalWarning) {
        this.currentWave = currentWave;
        this.totalWaves = totalWaves;
        this.waveProgress = waveProgress;
        this.waveWarningActive = warningActive;
        this.waveWarningFinal = finalWarning;
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
    public void setDebugInfo(long tickCount, boolean frozen, boolean sprinting) {
        long now = System.nanoTime();
        if (lastDebugTickCount >= 0 && lastDebugNanos > 0 && now > lastDebugNanos) {
            long deltaTicks = Math.max(0L, tickCount - lastDebugTickCount);
            float instant = (float) (deltaTicks * 1_000_000_000D / (now - lastDebugNanos));
            measuredTps = measuredTps <= 0F ? instant : measuredTps * 0.7F + instant * 0.3F;
        }
        this.debugTickCount = tickCount;
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
