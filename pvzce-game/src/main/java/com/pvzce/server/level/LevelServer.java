package com.pvzce.server.level;

import com.pvzce.api.content.LevelDef;
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
import com.pvzce.api.entity.Entity;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SceneCells;
import com.pvzce.common.core.SeedOptions;
import com.pvzce.common.level.DayNightCycle;
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
import com.pvzce.common.network.packet.SlotInfo;
import com.pvzce.common.network.packet.SlotSyncS2C;
import com.pvzce.common.network.packet.TimeOfDayS2C;
import com.pvzce.common.network.packet.WaveProgressS2C;
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
    /** Small right nudge so a plant sits visually centred on a flower pot/lily pad. */
    private static final float CARRIER_X_OFFSET = 0.06F;
    /** Visual heights of the two carriers, in world cells. */
    private static final float FLOWER_POT_TOP = 0.38F;
    private static final float LILY_PAD_TOP = 0.10F;

    private final LevelDef def;
    private final List<WaveDef> waves;
    private final SceneGrid<SceneElementDef> scene;
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
    private ServerBridge bridge;

    private int tickCount;
    private int nextWaveIndex;
    private int nextMusicCueIndex;
    private int waveIntervalTicks;
    private int nextWaveDelayTicks;
    private float waveProgress;
    private boolean waveWarningActive;
    private boolean waveWarningFinal;
    private String gameState = GameStateS2C.RUNNING;
    private Identifier winner;
    private Identifier humanTeamId = PvzceIds.PLANT_TEAM;
    private boolean gameEndPacketSent;
    private boolean waveDirty = true;

    public LevelServer(LevelDef def) {
        this(def, def.slots());
    }

    /** Creates a level whose plant player starts with the supplied seed selection, in order. */
    public LevelServer(LevelDef def, List<Identifier> selectedSlots) {
        this.def = def;
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

        Team plantTeam = teams.get(PvzceIds.PLANT_TEAM);
        this.plantPlayer = plantTeam != null ? PvzcePlayer.createPlantPlayer(plantTeam, def, selectedSlotsOrDef(selectedSlots)) : null;
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
        problems.addAll(GameRules.validate(def.rules()));
        problems.addAll(LevelValidator.validateEnvVars(def.envVars()));
        problems.addAll(LevelValidator.validateSlots(def.slots()));
        problems.addAll(LevelValidator.validateScene(def));
        problems.addAll(LevelValidator.validateInitialEntities(def));
        if (!problems.isEmpty()) {
            System.err.println("[PVZCE] Level " + def.id() + " has " + problems.size() + " problem(s):");
            for (String problem : problems) {
                System.err.println("[PVZCE]   - " + problem);
            }
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
     * Explicit stack layer, lower first. Carriers (flower pot / lily pad) sit
     * below normal plants, and {@code feet=plant} supports (coffee bean and
     * future covers) sit above them. Ties are broken by entity id, so the
     * later-planted plant is on top.
     */
    private static int stackLayer(PlantDef def) {
        return switch (def.placement().feet()) {
            case PvzceIds.FEET_GROUND, PvzceIds.FEET_LILY -> 0;
            case PvzceIds.FEET_PLANT -> 2;
            default -> 1;
        };
    }

    /** Plants in a cell ordered bottom-to-top by the explicit stacking rules. */
    public List<PlantEntity> plantsBottomFirst(int column, int row) {
        return plantsAt(column, row).stream()
                .sorted(Comparator.comparingInt((PlantEntity p) -> stackLayer(p.def()))
                        .thenComparingInt(PlantEntity::id))
                .toList();
    }

    /** The topmost plant in a cell (the one zombies bite and the shovel removes first). */
    @Override
    public PlantEntity plantAt(int column, int row) {
        List<PlantEntity> plants = plantsBottomFirst(column, row);
        return plants.isEmpty() ? null : plants.get(plants.size() - 1);
    }

    public boolean hasPlantWithFeet(int column, int row, String feet) {
        return plantsAt(column, row).stream().anyMatch(p -> feet.equals(p.def().placement().feet()));
    }

    /**
     * Stacking matrix: plantable needs grass/ground or a carrier (lily pad,
     * flower pot); lily needs water; ground-feet pots go on grass/roof; and
     * plant-feet items (coffee bean) stack on any plant.
     */
    public boolean canPlacePlant(PlantDef def, int x, int y) {
        if (!inBounds(x, y)) {
            return false;
        }
        String feet = def.placement().feet();
        SceneElementDef base = sceneAt(x, y);
        List<PlantEntity> stacked = plantsAt(x, y);
        if (PvzceIds.FEET_PLANT.equals(feet)) {
            return !stacked.isEmpty();
        }
        if (PvzceIds.FEET_LILY.equals(feet)) {
            return base != null && (PvzceIds.SURFACE_WATER.equals(base.surfaceClass()) || base.accepts(PvzceIds.FEET_LILY));
        }
        if (PvzceIds.FEET_GROUND.equals(feet)) {
            return base != null && (base.accepts("flower_pot") || base.accepts(PvzceIds.FEET_GROUND)
                    || PvzceIds.SURFACE_GROUND.equals(base.surfaceClass())
                    || PvzceIds.SURFACE_ROOF.equals(base.surfaceClass())
                    || PvzceIds.SURFACE_ROOF_SLOPE.equals(base.surfaceClass()));
        }
        boolean carrier = stacked.stream().anyMatch(p -> isCarrier(p));
        if (PvzceIds.FEET_PLANTABLE.equals(feet)) {
            return carrier || (base != null && base.accepts(PvzceIds.FEET_PLANTABLE));
        }
        return base != null && base.accepts(feet);
    }

    /** Carrier plants are identified by their placement layer, not by a hard-coded id list. */
    public static boolean isCarrier(PlantEntity plant) {
        return stackLayer(plant.def()) == 0;
    }

    public List<PvzceEntity> entities() {
        return List.copyOf(entities);
    }

    @Override
    public List<ZombieEntity> zombiesInRow(int row) {
        return entities.stream()
                .filter(e -> e instanceof ZombieEntity z && !z.isRemoved() && z.gridY() == row)
                .map(e -> (ZombieEntity) e)
                .toList();
    }

    public int plantCount() {
        return (int) entities.stream().filter(e -> e instanceof PlantEntity p && !p.isRemoved()).count();
    }

    public long aliveZombieCount() {
        return entities.stream().filter(e -> e instanceof ZombieEntity z && !z.isRemoved()).count();
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
            plant.setCellX(plant.cellX() + CARRIER_X_OFFSET);
        }
        plant.setHeight(stackedPlantHeight(x, y));
        addEntity(plant);
        flushPending();
        plant.onPlaced(this);
        flushPending();
        if (!plant.isRemoved()) {
            emitEffect("pvzce:plant", x + 0.5F, y + 0.5F, placementSound(def, x, y));
        }
        return plant;
    }

    /**
     * Placement height including any carrier already in the cell (flower pot /
     * lily pad). The constants are visual carrier tops in world cells.
     */
    private float stackedPlantHeight(int x, int y) {
        SceneElementDef base = sceneAt(x, y);
        float height = base == null ? 0F : base.heightAt(x + 0.5F, width());
        for (PlantEntity existing : plantsAt(x, y)) {
            int layer = stackLayer(existing.def());
            if (layer == 0) {
                height = Math.max(height, existing.height() + carrierTop(existing));
            }
        }
        return height;
    }

    private static float carrierTop(PlantEntity carrier) {
        return carrier.def().id().path().equals("flower_pot") ? FLOWER_POT_TOP : LILY_PAD_TOP;
    }

    private Identifier placementSound(PlantDef def, int x, int y) {
        SceneElementDef base = sceneAt(x, y);
        Identifier fallback = base != null && PvzceIds.SURFACE_WATER.equals(base.surfaceClass())
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
        ResourceDef resource = BuiltInRegistries.RESOURCES.get(resourceId);
        if (resource == null) {
            return;
        }
        addEntity(new ResourceDropEntity(resource, team, (int) Math.floor(x), (int) Math.floor(y), amount));
    }

    @Override
    public void spawnZombie(Identifier zombieId, Team team, float x, int row) {
        ZombieDef def = BuiltInRegistries.ZOMBIES.get(zombieId);
        if (def == null) {
            return;
        }
        addEntity(new ZombieEntity(def, team, x, row));
        emitEffect("", x, row + 0.5F, def.sounds().spawn().orElse(PvzceSounds.ZOMBIE_GROAN));
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
            default -> false;
        };
    }

    @Override
    public void damageArea(float centerX, float centerY, float radius, int damage, Team sourceTeam) {
        float multiplier = rules.getFloat(PvzceIds.RULE_PLANT_DAMAGE_MULTIPLIER);
        for (PvzceEntity entity : new ArrayList<>(entities)) {
            if (!(entity instanceof ZombieEntity zombie) || zombie.isRemoved()) {
                continue;
            }
            if (Math.abs(zombie.cellX() - centerX) <= radius && Math.abs(zombie.cellY() - centerY) <= radius) {
                zombie.damageBody(Math.round(damage * multiplier), this);
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

    // ------------------------------------------------------------------
    // Tick
    // ------------------------------------------------------------------

    public interface ServerBridge {
        void send(PvzcePacket packet);
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
        if (plantPlayer == null) {
            return;
        }
        for (Slot slot : plantPlayer.slots()) {
            int before = slot.cooldownLeft();
            slot.tick();
            // A card only changes while its cooldown is running. The old condition
            // also fired for every *ready* card on every tick, so a 13-card bar sent
            // ~780 packets per second for values that never changed. Cooldown cards
            // still stream every tick (the HUD bar animates), ready cards get a
            // keepalive and an immediate message the moment they finish.
            boolean animating = before > 0;
            boolean justBecameReady = before > 0 && slot.cooldownLeft() == 0;
            if (animating || justBecameReady || tickCount % 20 == 0) {
                bridge.send(new SlotSyncS2C(toSlotInfo(slot)));
            }
        }
        if (tickCount % 3 == 0) {
            for (PvzceEntity entity : entities) {
                bridge.send(entity.updatePacket());
            }
        }
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

    private void tickWaves(ServerBridge bridge) {
        if (nextWaveIndex < waves.size()) {
            waveIntervalTicks++;
            if (waveIntervalTicks >= nextWaveDelayTicks) {
                triggerWave(waves.get(nextWaveIndex));
            } else {
                updateWaveWarning();
            }
        } else {
            waveProgress = 1F;
            waveWarningActive = false;
            waveWarningFinal = false;
        }
        spawnPendingWaveZombies();
    }

    private void triggerWave(WaveDef wave) {
        nextWaveIndex++;
        waveIntervalTicks = 0;
        waveProgress = 0F;
        waveWarningActive = false;
        waveWarningFinal = false;
        waveDirty = true;

        List<Identifier> zombies = expandEntries(wave.entries());
        Collections.shuffle(zombies, random);
        pendingWaveSpawns.add(new PendingWaveSpawn(zombies, shuffledRows()));

        if (wave.isHuge()) {
            emitEffect("", width() / 2F, height() / 2F, PvzceSounds.AMBIENT_HUGE_WAVE, 1F, 1F);
        }
        if (wave.type() == WaveDef.WaveType.FINAL) {
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
        boolean active = next.isHuge() && warningTicks > 0 && remaining <= warningTicks && remaining > 0;
        boolean finalWarning = active && next.type() == WaveDef.WaveType.FINAL;
        if (active != waveWarningActive || finalWarning != waveWarningFinal) {
            waveDirty = true;
        }
        waveWarningActive = active;
        waveWarningFinal = finalWarning;
        waveProgress = nextWaveDelayTicks <= 0
                ? 1F
                : Math.max(0F, Math.min(1F, waveIntervalTicks / (float) nextWaveDelayTicks));
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
                queue.ticksUntilNext--;
                continue;
            }
            Identifier zombieId = queue.zombies.poll();
            int row = queue.rows.get(queue.rowIndex++ % queue.rows.size());
            spawnZombie(zombieId, zombieTeam, width() + 0.6F, row);
            queue.ticksUntilNext = WaveDef.SPAWN_INTERVAL_TICKS;
            if (queue.zombies.isEmpty()) {
                iterator.remove();
            }
        }
        flushPending();
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

    private static final class PendingWaveSpawn {
        private final ArrayDeque<Identifier> zombies;
        private final List<Integer> rows;
        private int rowIndex;
        private int ticksUntilNext;

        private PendingWaveSpawn(List<Identifier> zombies, List<Integer> rows) {
            this.zombies = new ArrayDeque<>(zombies);
            this.rows = rows;
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

    private void markEnd(Team winnerTeam) {
        if (!gameState.equals(GameStateS2C.RUNNING) || winnerTeam == null) {
            return;
        }
        gameState = GameStateS2C.WON;
        winner = winnerTeam.id();
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
        String feet = plantDef.placement().feet();
        if (!PvzceIds.FEET_PLANT.equals(feet) && hasPlantWithFeet(x, y, feet)) {
            bridge.send(new ServerMessageS2C("该格已有同类植物。"));
            return false;
        }
        int cost = slot.costSun() > 0 ? slot.costSun() : plantDef.cost().amountOf(PvzceIds.SUN);
        if (!plantPlayer.team().consume(PvzceIds.SUN, cost)) {
            bridge.send(new ServerMessageS2C("阳光不足！"));
            return false;
        }

        slot.startCooldown(plantDef.cost().cooldownTicks());
        PlantEntity plant = spawnPlant(plantDef, plantPlayer.team(), x, y);
        System.out.println("[PVZCE] Planted " + slot.defId() + " at (" + x + "," + y + ") count=" + plantCount());
        bridge.send(new ResourceDeltaS2C(plantPlayer.team().id().toString(), PvzceIds.SUN.toString(),
                plantPlayer.team().resourcesOf(PvzceIds.SUN)));
        bridge.send(new SlotSyncS2C(toSlotInfo(slot)));
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
            emitEffect("pvzce:sun_glow", drop.cellX(), drop.cellY(), PvzceSounds.UI_COLLECT);
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
        if (!slot.ready()) {
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
        applyToolEffect(tool, x, y);
        slot.startCooldown(tool.cooldownTicks());
        slot.consumeUse();
        bridge.send(new SlotSyncS2C(toSlotInfo(slot)));
        return true;
    }

    private void applyToolEffect(ToolDef tool, int x, int y) {
        switch (tool.effect()) {
            case "pvzce:shovel" -> {
                // PVZ original: one shovel click removes exactly one plant,
                // always the topmost layer of the target cell.
                PlantEntity plant = plantAt(x, y);
                if (plant != null) {
                    plant.remove();
                    flushPending();
                    emitEffect("pvzce:dirt", x + 0.5F, y + 0.5F, PvzceSounds.EFFECT_SHOVEL);
                }
            }
            case "pvzce:glove" -> {
                PlantEntity plant = plantAt(x, y);
                if (plant != null) {
                    plant.boost();
                    emitEffect("pvzce:sparkle", x + 0.5F, y + 0.5F, PvzceSounds.UI_TAP);
                }
            }
            case "pvzce:hammer" -> {
                for (ZombieEntity zombie : zombiesInRow(y)) {
                    if (!zombie.isRemoved() && Math.abs(zombie.cellX() - (x + 0.5F)) < 0.8F) {
                        zombie.damageBody(200, this);
                        emitEffect("pvzce:hit_spark", zombie.cellX(), zombie.cellY(), PvzceSounds.EFFECT_BONK);
                    }
                }
            }
            default -> {
            }
        }
    }

    public SlotInfo toSlotInfo(Slot slot) {
        boolean available = slot.kind() == Slot.Kind.RESOURCE
                || (slot.ready() && plantPlayer != null
                        && plantPlayer.team().resourcesOf(PvzceIds.SUN) >= slot.costSun());
        return new SlotInfo(slot.index(), slot.defId().toString(), slot.kind().json(), slot.costSun(),
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
        LevelPayload payload = new LevelPayload(width(), height(), SeedOptions.forLevel(def),
                def.maxSeedSlots(), def.previewZombieIds(), SceneCells.forLevel(def));
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
            queueTag.putInt("RowIndex", queue.rowIndex);
            queueTag.putInt("TicksUntilNext", queue.ticksUntilNext);
            pendingWaves.add(queueTag);
        }
        root.put("PendingWaveSpawns", pendingWaves);

        // Teams, cards, resources and every entity live in one tag; the server no
        // longer writes a second copy of the same data or a per-team side file.
        root.put("Teams", saveTeams());
        root.put("Slots", saveSlots());
        root.put("Scene", saveScene());

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
        waveIntervalTicks = Math.max(0, root.getInt("WaveIntervalTicks"));
        nextWaveDelayTicks = nextWaveIndex < waves.size() ? effectiveWaveDelay(nextWaveIndex) : -1;
        nextMusicCueIndex = Math.max(0, root.getInt("NextMusicCueIndex"));
        restoreScene(root.getList("Scene"));
        restoreTeams(root.getCompound("Teams"));
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
            PendingWaveSpawn queue = new PendingWaveSpawn(zombieIds, rows);
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
