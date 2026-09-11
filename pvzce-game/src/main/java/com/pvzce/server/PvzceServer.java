package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SceneCells;
import com.pvzce.common.core.SeedOptions;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.IntTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.common.nbt.NbtIo;
import com.pvzce.common.nbt.NbtMigrations;
import com.pvzce.common.nbt.StringTag;
import com.pvzce.common.network.Connection;
import com.pvzce.common.network.PacketListener;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.PvzcePackets;
import com.pvzce.common.network.packet.CollectResourceC2S;
import com.pvzce.common.network.packet.CommandC2S;
import com.pvzce.common.network.packet.DebugInfoS2C;
import com.pvzce.common.network.packet.GameSpeedS2C;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.packet.LeaveLevelC2S;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelPayload;
import com.pvzce.common.network.packet.LevelSavePromptS2C;
import com.pvzce.common.network.packet.PauseGameC2S;
import com.pvzce.common.network.packet.PlacePlantC2S;
import com.pvzce.common.network.packet.RequestLevelC2S;
import com.pvzce.common.network.packet.RequestLevelListC2S;
import com.pvzce.common.network.packet.RequestSuggestionsC2S;
import com.pvzce.common.network.packet.ResumeLevelC2S;
import com.pvzce.common.network.packet.SeedOption;
import com.pvzce.common.network.packet.ServerMessageS2C;
import com.pvzce.common.network.packet.SetGameSpeedC2S;
import com.pvzce.common.network.packet.StartLevelC2S;
import com.pvzce.common.network.packet.SuggestionsS2C;
import com.pvzce.common.network.packet.TeamSyncS2C;
import com.pvzce.common.network.packet.UseToolC2S;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.resource.PvzceDataLoader;
import com.pvzce.common.resource.PvzceResourceManager;
import com.pvzce.common.tag.PvzceTags;
import com.pvzce.common.tag.TagManager;
import com.pvzce.server.command.PvzceCommandSource;
import com.pvzce.server.command.PvzceCommands;
import com.pvzce.server.level.LevelServer;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

/** The 60tps authoritative game server (integrated in phase 1). */
public final class PvzceServer implements Runnable {
    private static final int MAX_TICKS_PER_FRAME = 5;

    private final Connection connection;
    private final Path gameDir;
    private final PvzceResourceManager resourceManager;
    private final PvzceTickRateManager tickRate = new PvzceTickRateManager();
    private final ConcurrentLinkedQueue<String> commands = new ConcurrentLinkedQueue<>();
    private final LevelServer.ServerBridge bridge;
    private final CommandDispatcher<PvzceCommandSource> dispatcher;

    private volatile boolean running = true;
    private volatile LevelServer level;
    private volatile String currentWorld;
    private volatile Identifier currentLevelId;
    private boolean levelFinishHandled;
    private volatile boolean savePromptPending;
    private volatile boolean manualPause;
    private Thread thread;
    private long lastDebugInfoNanos;

    public PvzceServer(Connection connection, Path gameDir, ClassLoader classLoader) {
        this.connection = connection;
        this.gameDir = gameDir;
        this.resourceManager = new PvzceResourceManager(classLoader);
        this.bridge = packet -> {
            connection.send(packet);
            if (packet instanceof GameStateS2C state && !GameStateS2C.RUNNING.equals(state.state())) {
                onLevelFinished();
            }
        };
        this.dispatcher = PvzceCommands.create(this);
    }

    public LevelServer level() {
        return level;
    }

    public PvzceTickRateManager tickRateManager() {
        return tickRate;
    }

    public void start() {
        thread = new Thread(this, "PvzceServer");
        thread.setDaemon(true);
        thread.start();
    }

    public void submitCommand(String command) {
        commands.add(command);
    }

    public void stop() {
        running = false;
    }

    public PvzceResourceManager resourceManager() {
        return resourceManager;
    }

    public Path gameDir() {
        return gameDir;
    }

    public Thread thread() {
        return thread;
    }

    public void sendMessage(String message) {
        connection.send(new ServerMessageS2C(message));
    }

    public void sendPacket(PvzcePacket packet) {
        connection.send(packet);
    }

    /** Brigadier tab completion for the in-game console. */
    public void sendSuggestions(String input, int requestId) {
        try {
            String command = input.startsWith("/") ? input.substring(1) : input;
            int rangeOffset = input.startsWith("/") ? 1 : 0;
            var suggestions = dispatcher.getCompletionSuggestions(
                    dispatcher.parse(command, new PvzceCommandSource(this))).get();
            connection.send(new SuggestionsS2C(requestId, suggestions.getList().stream()
                    .map(suggestion -> new SuggestionsS2C.Suggestion(
                            suggestion.getRange().getStart() + rangeOffset,
                            suggestion.getRange().getEnd() + rangeOffset,
                            suggestion.getText()))
                    .distinct()
                    .limit(16)
                    .toList()));
        } catch (Exception e) {
            System.err.println("[Suggestions] failed for '" + input + "': " + e);
            connection.send(new SuggestionsS2C(requestId, List.of()));
        }
    }

    public String currentWorld() {
        return currentWorld;
    }

    @Override
    public void run() {
        try {
            PvzcePackets.register();
            BuiltInRegistries.bootstrap();

            Files.createDirectories(gameDir.resolve("mods"));
            Files.createDirectories(gameDir.resolve("resourcepacks"));
            Files.createDirectories(gameDir.resolve("datapacks"));
            Files.createDirectories(gameDir.resolve("saves"));
            resourceManager.init(gameDir);

            connection.setListener(new ServerPacketListener());
            // Mod entrypoints must run BEFORE the first data load: reloadData()
            // freezes every content registry, and a frozen registry rejects static
            // registration, so a mod that registered content from a
            // DedicatedServerModInitializer used to throw (into a swallowed catch)
            // and silently register nothing at all.
            invokeServerEntrypoints();
            reloadData(false);

            long lastFrameNanos = System.nanoTime();
            long serverStartNanos = lastFrameNanos;
            BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in));
            while (running) {
                long now = System.nanoTime();
                long elapsed = now - lastFrameNanos;
                lastFrameNanos = now;

                // Commands and network packets must keep flowing even while
                // the tick loop is frozen, otherwise /tick unfreeze could
                // never be delivered.
                processCommands();
                processStdin(stdin);
                connection.tick();
                if (now - lastDebugInfoNanos >= 250_000_000L) {
                    lastDebugInfoNanos = now;
                    // The tick rate is owned by GameSpeedS2C; this heartbeat only
                    // reports the measured clock and the run state.
                    connection.send(new DebugInfoS2C(tickRate.tickCount(),
                            tickRate.isFrozen(), tickRate.isSprinting()));
                }

                int ticks = 0;
                long frameBudget = elapsed;
                while (ticks < MAX_TICKS_PER_FRAME && tickRate.advance(frameBudget)) {
                    ticks++;
                    frameBudget = Math.max(0, frameBudget - tickRate.nanosPerTick());
                    processCommands();
                    processStdin(stdin);
                    connection.tick();
                    long tickStartNanos = System.nanoTime();
                    if (level != null && !savePromptPending && !manualPause) {
                        level.tick(bridge);
                    }
                    tickRate.recordTickTime(System.nanoTime() - tickStartNanos);
                    tickRate.onTick();
                    long tickCount = tickRate.tickCount();
                    if (tickCount % 600 == 0) {
                        long elapsedMs = (System.nanoTime() - serverStartNanos) / 1_000_000L;
                        System.out.println("[PVZCE] tick check: tick=" + tickCount + " elapsed_ms=" + elapsedMs
                                + " target=" + tickRate.tickRate() + "tps");
                    }
                }
                if (ticks == MAX_TICKS_PER_FRAME) {
                    tickRate.reset();
                }

                LockSupport.parkNanos(tickRate.nanosUntilNextTick());
            }
        } catch (Throwable t) {
            t.printStackTrace();
            connection.send(new ServerMessageS2C("服务器异常: " + t.getMessage()));
        } finally {
            saveGame();
            try {
                resourceManager.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void invokeServerEntrypoints() {
        try {
            FabricLoader.getInstance().invokeEntrypoints("server", DedicatedServerModInitializer.class,
                    DedicatedServerModInitializer::onInitializeServer);
        } catch (Throwable t) {
            System.err.println("A server entrypoint failed: " + t.getMessage());
        }
    }

    private void processStdin(BufferedReader stdin) throws IOException {
        if (!stdin.ready()) {
            return;
        }
        String line = stdin.readLine();
        if (line != null && !line.isBlank()) {
            commands.add(line.trim());
        }
    }

    private void processCommands() {
        String command;
        while ((command = commands.poll()) != null) {
            if (command.startsWith("/")) {
                command = command.substring(1);
            }
            String trimmed = command.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if ("quit".equalsIgnoreCase(trimmed)) {
                stop();
                continue;
            }
            try {
                dispatcher.execute(trimmed, new PvzceCommandSource(this));
            } catch (CommandSyntaxException e) {
                connection.send(new ServerMessageS2C("命令错误: " + e.getMessage()));
            } catch (Throwable t) {
                connection.send(new ServerMessageS2C("命令执行失败: " + t.getMessage()));
            }
        }
    }

    public void reloadData(boolean announce) {
        try {
            resourceManager.reload();
            PvzceDataLoader.LoadResult content =
                    new PvzceDataLoader().load(resourceManager, BuiltInRegistries.ACCESS);
            TagManager.LoadResult tags = PvzceTags.MANAGER.reload(resourceManager, BuiltInRegistries.ACCESS);
            // The card resolver caches which unknown ids it has already warned about,
            // so a reload must let it warn again.
            SlotResolver.resetReported();
            if (announce) {
                connection.send(new ServerMessageS2C("已重载数据包: " + content.loaded().size() + " 个条目，"
                        + tags.loaded().size() + " 个标签"));
            }
            // One reporting path for both loaders: they each had their own copy of
            // this loop, which is how the client ended up discarding its tag errors.
            reportErrors("数据错误", content.errors());
            reportErrors("标签错误", tags.errors());
        } catch (Throwable t) {
            t.printStackTrace();
            if (announce) {
                connection.send(new ServerMessageS2C("重载失败: " + t.getMessage()));
            }
        }
    }

    /** Surfaces load errors to the log and (at most once per message) to the player. */
    private void reportErrors(String label, java.util.List<String> errors) {
        for (String error : errors) {
            System.err.println("[PVZCE/" + label + "] " + error);
        }
        if (!errors.isEmpty()) {
            connection.send(new ServerMessageS2C("[" + label + "] " + errors.size() + " 个问题，详见日志"));
        }
    }

    private void sendLevelList(String worldName) {
        String safeWorld = sanitizeWorldName(worldName);
        List<LevelListS2C.LevelInfo> levels = new ArrayList<>();
        BuiltInRegistries.LEVELS.keySet().stream()
                .sorted(Comparator.comparing(Identifier::toString))
                .forEach(id -> {
                    LevelDef def = BuiltInRegistries.LEVELS.get(id);
                    List<LevelListS2C.TeamInfo> teams = def.teams().stream()
                            .map(t -> new LevelListS2C.TeamInfo(t.id().toString(), t.name(), t.winCondition()))
                            .toList();
                    levels.add(LevelListS2C.LevelInfo.of(id.toString(), def.displayName(), def.description(),
                            def.winTeam().toString(), teams, levelStatus(safeWorld, id), levelIcon(def),
                            new LevelPayload(def.width(), def.height(), SeedOptions.forLevel(def),
                                    def.maxSeedSlots(), def.previewZombieIds(), SceneCells.forLevel(def))));
                });
        connection.send(new LevelListS2C(levels));
    }

    /** Almanac-style icon theme sent to the level select screen. */
    private static String levelIcon(LevelDef def) {
        boolean night = false;
        var nightRule = def.rules().get(Identifier.withDefaultNamespace("night_length"));
        if (nightRule != null && nightRule.isJsonPrimitive()) {
            try {
                night = nightRule.getAsInt() > 0;
            } catch (RuntimeException ignored) {
            }
        }
        boolean roof = def.scene().keySet().stream().anyMatch(key -> key.path().contains("roof"));
        boolean water = def.scene().keySet().stream().anyMatch(key -> key.path().contains("water"));
        if (roof) {
            return "roof";
        }
        if (water) {
            return night ? "night_pool" : "pool";
        }
        return night ? "night" : "day";
    }

    private static String sanitizeWorldName(String worldName) {
        String safe = worldName == null ? "world" : worldName.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        return safe.isBlank() ? "world" : safe;
    }

    /** Safe flat directory key for one level inside a world save. */
    private static String levelKey(Identifier id) {
        return id.namespace() + "__" + id.path().replace('/', '_');
    }

    private static Path worldPath(Path gameDir, String worldName) {
        return gameDir.resolve("saves").resolve(sanitizeWorldName(worldName));
    }

    private static Path levelDir(Path worldDir, Identifier id) {
        return worldDir.resolve("levels").resolve(levelKey(id));
    }

    private static Path levelStatusFile(Path worldDir, Identifier id) {
        return worldDir.resolve("level_status").resolve(levelKey(id) + ".dat");
    }

    private static boolean hasRunningSave(Path levelDir) {
        return Files.isRegularFile(levelDir.resolve("level.dat"));
    }

    public void requestLevel(String levelId, String worldName, boolean restart) {
        createLevel(levelId, worldName, restart, false, null);
    }

    private void createLevel(String levelId, String worldName, boolean restart, boolean confirmed) {
        createLevel(levelId, worldName, restart, confirmed, null);
    }

    private void createLevel(String levelId, String worldName, boolean restart, boolean confirmed,
                             List<Identifier> requestedSeeds) {
        Identifier id = Identifier.parse(levelId);
        LevelDef def = BuiltInRegistries.LEVELS.get(id);
        if (def == null) {
            connection.send(new ServerMessageS2C("未找到关卡 " + id));
            return;
        }

        String safeWorld = sanitizeWorldName(worldName);
        if (level != null && currentLevelId != null && currentLevelId.equals(id)
                && safeWorld.equals(currentWorld) && !restart) {
            level.sendFullState(bridge);
            connection.send(new GameSpeedS2C(tickRate.tickRate()));
            return;
        }
        Path worldDir = worldPath(gameDir, safeWorld);
        Path saveDir = levelDir(worldDir, id);
        boolean hasSave = hasRunningSave(saveDir);

        // Switching to a different level without an explicit restart keeps
        // the current level's progress under its own save directory.
        if (level != null && currentWorld != null && !restart) {
            saveGame();
        }

        boolean loadSave = !restart && hasSave;
        if (!loadSave && hasSave) {
            deleteRunningSave(saveDir);
        }

        List<Identifier> seeds = requestedSeeds == null ? null : sanitizeSeedSelection(def, requestedSeeds);
        if (loadSave) {
            // Continuing a save restores the exact card bar the player had.
            List<Identifier> savedSeeds = readSavedSeedSelection(saveDir);
            if (savedSeeds != null) {
                seeds = sanitizeSeedSelection(def, savedSeeds);
            }
        }
        if (seeds == null) {
            seeds = def.slots();
        }

        LevelServer newLevel = new LevelServer(def, seeds);
        // Entering/restarting a level always starts at the normal 60tps speed.
        tickRate.setTickRate(PvzceTickRateManager.DEFAULT_TICK_RATE);

        CompoundTag saveTag = null;
        boolean loadedSave = false;
        if (loadSave) {
            try {
                saveTag = NbtIo.readCompressed(saveDir.resolve("level.dat"));
                restoreLevel(newLevel, saveDir, saveTag);
                loadedSave = true;
                System.out.println("[PVZCE] Restored " + saveTag.getInt("PlantCount") + " plants from " + saveDir);
                connection.send(new ServerMessageS2C("已从存档继续 " + def.displayName()));
            } catch (Throwable t) {
                System.err.println("Failed to read save: " + t.getMessage());
                deleteRunningSave(saveDir);
                saveTag = null;
            }
        }

        if (this.level != null && this.level != newLevel) {
            this.level.shutdown();
        }
        this.level = newLevel;
        this.currentWorld = safeWorld;
        this.currentLevelId = id;
        this.levelFinishHandled = false;
        this.savePromptPending = false;
        this.manualPause = false;
        try {
            Files.createDirectories(worldDir);
            Files.writeString(worldDir.resolve("session.lock"), String.valueOf(System.currentTimeMillis()));
        } catch (IOException ignored) {
        }
        newLevel.sendFullState(bridge);
        connection.send(new GameSpeedS2C(tickRate.tickRate()));

        if (loadedSave && !confirmed && saveTag != null) {
            // The saved world is already being rendered behind the dialog; keep
            // the simulation frozen until the client chooses continue/restart.
            savePromptPending = true;
            connection.send(new LevelSavePromptS2C(id.toString(), safeWorld, def.displayName(),
                    saveTag.getInt("Tick"), saveTag.getInt("PlantCount"), saveTag.getInt("Sun")));
        }
    }

    private static List<Identifier> sanitizeSeedSelection(LevelDef def, List<Identifier> requested) {
        List<Identifier> result = new ArrayList<>();
        Set<Identifier> seen = new HashSet<>();
        int max = Math.max(0, def.maxSeedSlots());
        for (Identifier seed : requested) {
            if (seed == null || !def.slots().contains(seed) || !seen.add(seed)) {
                continue;
            }
            result.add(seed);
            if (result.size() >= max) {
                break;
            }
        }
        return List.copyOf(result);
    }

    /** The card bar a running save was created with, or {@code null} when there is none. */
    private static List<Identifier> readSavedSeedSelection(Path saveDir) {
        Path saveFile = saveDir.resolve("level.dat");
        if (!Files.isRegularFile(saveFile)) {
            return null;
        }
        try {
            ListTag slots = NbtIo.readCompressed(saveFile).getList("Slots");
            List<Identifier> seeds = new ArrayList<>();
            for (int i = 0; i < slots.size(); i++) {
                Identifier seed = Identifier.tryParse(slots.getCompound(i).getString("def"));
                if (seed != null) {
                    seeds.add(seed);
                }
            }
            return List.copyOf(seeds);
        } catch (Throwable t) {
            System.err.println("Failed to read saved seed selection from " + saveFile + ": " + t.getMessage());
            return null;
        }
    }

    public void leaveLevel() {
        savePromptPending = false;
        manualPause = false;
        saveGame();
        if (level != null) {
            level.shutdown();
        }
        level = null;
        currentWorld = null;
        currentLevelId = null;
        levelFinishHandled = false;
        connection.send(new ServerMessageS2C("关卡已退出。"));
    }

    public void saveGame() {
        LevelServer current = level;
        if (current == null || currentWorld == null) {
            return;
        }
        if (!GameStateS2C.RUNNING.equals(current.gameState())) {
            onLevelFinished();
            return;
        }
        Identifier id = currentLevelId != null ? currentLevelId : current.def().id();
        Path saveDir = levelDir(worldPath(gameDir, currentWorld), id);
        try {
            saveLevel(current, saveDir);
            System.out.println("[PVZCE] Saved " + saveDir);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    /**
     * Writes one level's running save.
     *
     * <p>Everything the level needs - teams, resources, unlocks, the card bar,
     * the scene grid and every entity snapshot - is written by
     * {@link LevelServer#save()} into a single {@code level.dat}. The old split
     * (level.dat plus a byte-identical level_state.dat, plus per-team and player
     * side files that were written but never read) is gone; it silently dropped
     * every resource except sun and could not restore anything it wrote.
     */
    private void saveLevel(LevelServer current, Path saveDir) throws IOException {
        Files.createDirectories(saveDir);
        NbtIo.writeCompressed(current.save(), saveDir.resolve("level.dat"));
    }

    /** Hands the whole save to the level; there is nothing left for the server to unpack. */
    private void restoreLevel(LevelServer newLevel, Path saveDir, CompoundTag root) {
        newLevel.restore(NbtMigrations.migrate(root));
    }

    /**
     * Called when the current level reports a non-running state. A win writes
     * a small completion marker and both win/loss clear the resumable save.
     */
    private void onLevelFinished() {
        LevelServer current = level;
        if (current == null || currentWorld == null || levelFinishHandled) {
            return;
        }
        levelFinishHandled = true;
        Identifier id = currentLevelId != null ? currentLevelId : current.def().id();
        Path worldDir = worldPath(gameDir, currentWorld);
        try {
            if (isPlantWin(current)) {
                writeLevelStatus(worldDir, id, current);
            }
            deleteRunningSave(levelDir(worldDir, id));
            System.out.println("[PVZCE] Level finished, cleared running save for " + id);
        } catch (Throwable t) {
            System.err.println("Failed to finish level " + id + ": " + t.getMessage());
        }
    }

    private static boolean isPlantWin(LevelServer current) {
        return GameStateS2C.WON.equals(current.gameState())
                && current.winner() != null
                && current.winner().equals(current.def().winTeam());
    }

    private void writeLevelStatus(Path worldDir, Identifier id, LevelServer current) throws IOException {
        Path statusFile = levelStatusFile(worldDir, id);
        Files.createDirectories(statusFile.getParent());
        CompoundTag status = new CompoundTag();
        status.putInt("DataVersion", com.pvzce.common.PvzceConstants.SAVE_DATA_VERSION);
        status.putString("LevelId", id.toString());
        status.putString("GameState", "completed");
        status.putString("Winner", current.winner().toString());
        status.putInt("Tick", current.tickCount());
        status.putInt("PlantCount", current.plantCount());
        NbtIo.writeCompressed(status, statusFile);
    }

    /** "", "in_progress" or "completed"; failed levels deliberately keep no status. */
    private String levelStatus(String worldName, Identifier id) {
        if (worldName == null || worldName.isBlank()) {
            return "";
        }
        Path worldDir = worldPath(gameDir, worldName);
        if (hasRunningSave(levelDir(worldDir, id))) {
            return "in_progress";
        }
        Path statusFile = levelStatusFile(worldDir, id);
        if (!Files.isRegularFile(statusFile)) {
            return "";
        }
        try {
            CompoundTag status = NbtIo.readCompressed(statusFile);
            return "completed".equals(status.getString("GameState")) ? "completed" : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static void deleteRunningSave(Path saveDir) {
        if (!Files.exists(saveDir)) {
            return;
        }
        try (var paths = Files.walk(saveDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            System.err.println("Failed to delete level save " + saveDir + ": " + e.getMessage());
        }
    }

    private final class ServerPacketListener implements PacketListener {
        @Override
        public void handle(PvzcePacket packet) {
            LevelServer current = level;
            if (packet instanceof RequestLevelC2S request) {
                if (current != null && current.def().id().toString().equals(request.levelId())
                        && request.worldName() != null && request.worldName().equals(currentWorld)) {
                    if (request.restart()) {
                        createLevel(request.levelId(), request.worldName(), true, true);
                    } else {
                        current.sendFullState(bridge);
                        connection.send(new GameSpeedS2C(tickRate.tickRate()));
                    }
                    return;
                }
                createLevel(request.levelId(), request.worldName(), request.restart(), false);
            } else if (packet instanceof StartLevelC2S start) {
                List<Identifier> seeds = new ArrayList<>();
                for (String raw : start.selectedSeeds()) {
                    Identifier seed = Identifier.tryParse(raw);
                    if (seed != null) {
                        seeds.add(seed);
                    }
                }
                boolean sameRunningLevel = current != null
                        && current.def().id().toString().equals(start.levelId())
                        && start.worldName() != null && start.worldName().equals(currentWorld);
                if (sameRunningLevel && !start.restart()) {
                    // The client came through the seed chooser for the level that is
                    // already live. A card bar can only be picked before a run starts,
                    // so the request means "start this level with these cards" - it used
                    // to answer with a bare resync instead, which sent a LevelInitS2C
                    // for the still-running level, silently discarded the player's new
                    // cards and left the old run in place. That is the "restart did
                    // nothing / the old level was never closed" report.
                    System.out.println("[PVZCE] Card bar chosen for the running level " + start.levelId()
                            + "; starting a fresh run with it.");
                    createLevel(start.levelId(), start.worldName(), true, true, seeds);
                    return;
                }
                createLevel(start.levelId(), start.worldName(), start.restart(), false, seeds);
            } else if (packet instanceof ResumeLevelC2S resume) {
                if (savePromptPending && !resume.restart()) {
                    // Saved world is already loaded and rendered; just resume it.
                    savePromptPending = false;
                } else {
                    savePromptPending = false;
                    createLevel(resume.levelId(), resume.worldName(), resume.restart(), true);
                }
            } else if (packet instanceof RequestLevelListC2S request) {
                sendLevelList(request.worldName());
            } else if (packet instanceof RequestSuggestionsC2S suggestions) {
                sendSuggestions(suggestions.input(), suggestions.requestId());
            } else if (packet instanceof SetGameSpeedC2S speed) {
                int speedIndex = Math.max(1, Math.min(3, speed.speedIndex()));
                tickRate.setTickRate(PvzceTickRateManager.DEFAULT_TICK_RATE * speedIndex);
                connection.send(new GameSpeedS2C(tickRate.tickRate()));
            } else if (packet instanceof PauseGameC2S pause) {
                manualPause = pause.paused();
            } else if (packet instanceof LeaveLevelC2S) {
                if (current != null) {
                    leaveLevel();
                }
            } else if (packet instanceof PlacePlantC2S place) {
                if (current != null) {
                    current.placePlant(bridge, place.slotIndex(), place.gridX(), place.gridY());
                }
            } else if (packet instanceof UseToolC2S tool) {
                if (current != null) {
                    current.useTool(bridge, tool.slotIndex(), tool.gridX(), tool.gridY());
                }
            } else if (packet instanceof CollectResourceC2S collect) {
                if (current != null) {
                    current.collectResource(bridge, collect.entityId());
                }
            } else if (packet instanceof CommandC2S command) {
                submitCommand(command.command());
            } else if (packet instanceof com.pvzce.common.network.packet.PickCardC2S) {
                // Selected card remains client-side; the server validates on placement.
            }
        }

        @Override
        public void onDisconnect(String reason) {
            stop();
        }
    }
}
