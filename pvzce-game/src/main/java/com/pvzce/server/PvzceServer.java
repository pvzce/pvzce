package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.LevelRewards;
import com.pvzce.api.util.Identifier;
import com.pvzce.api.util.LevelGrouping;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceParticles;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SeedOptions;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.IntTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.common.nbt.NbtIo;
import com.pvzce.common.nbt.StringTag;
import com.pvzce.common.network.Connection;
import com.pvzce.common.network.PacketListener;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.PvzcePackets;
import com.pvzce.common.network.packet.CollectResourceC2S;
import com.pvzce.common.network.packet.CommandC2S;
import com.pvzce.common.network.packet.CreateWorldC2S;
import com.pvzce.common.network.packet.DebugInfoS2C;
import com.pvzce.common.network.packet.GameSpeedS2C;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.packet.LeaveLevelC2S;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelRewardS2C;
import com.pvzce.common.network.packet.LevelSavePromptS2C;
import com.pvzce.common.network.packet.LevelTabsS2C;
import com.pvzce.common.network.packet.MusicEventS2C;
import com.pvzce.common.network.packet.PauseGameC2S;
import com.pvzce.common.network.packet.PlacePlantC2S;
import com.pvzce.common.network.packet.ProfileS2C;
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
import com.pvzce.common.core.LevelUnlocks;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.resource.PvzceDataLoader;
import com.pvzce.common.resource.PvzceResourceManager;
import com.pvzce.common.tag.PvzceTags;
import com.pvzce.common.tag.TagManager;
import com.pvzce.server.command.PvzceCommandSource;
import com.pvzce.server.command.PvzceCommands;
import com.pvzce.server.level.LevelKey;
import com.pvzce.server.level.LevelServer;
import com.pvzce.server.level.LevelTabs;
import com.pvzce.server.level.LevelValidator;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    /**
     * The world of the last level list the client asked for.
     *
     * <p>A pushed refresh (the {@code /reload} at the end of an editor test) has no request
     * packet to read a world from, and the level list is per world: its 进行中 labels come
     * from that world's save files. Answering the push for the server's own world instead
     * gave the client another world's labels, which then decided whether entering a level
     * loads a save or opens the seed chooser.
     */
    private volatile String lastRequestedWorld;
    /**
     * The loaded profile and the world it belongs to.
     *
     * <p>Cached rather than re-read per query: the level list asks for it once per
     * level, and the profile is the one piece of state the server keeps across
     * levels, so re-reading the file would turn every list refresh into N reads of
     * the same bytes. The world name is the cache key, which also makes switching
     * worlds in the level list load the right file.
     */
    private PlayerProfile profile;
    private String profileWorld;
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
            // The wallet has to survive a shutdown that never went through leaveLevel.
            flushProfile();
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
            reportErrors("关卡分类", validateLevelGroups());
            reportErrors("对话", LevelValidator.validateAllDialogues(resourceManager));
            reportErrors("内置粒子", validateBuiltInParticles());
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

    /**
     * Sends the level list for the world the client last listed, or for the running level's
     * world when it has never listed one.
     *
     * <p>Public so the {@code /reload} command can push it after rebuilding the registries:
     * anything that just changed a level on disk wants the refreshed definitions.
     */
    public void refreshLevelList() {
        String world = lastRequestedWorld;
        sendLevelList(world != null ? world : currentWorld);
    }

    private void sendLevelList(String worldName) {
        String safeWorld = sanitizeWorldName(worldName);
        // The list is where a client learns the world's coins and unlocks, so the
        // profile travels with it rather than on a separate request.
        PlayerProfile profile = profileFor(safeWorld);
        Set<Identifier> cleared = clearedLevels(safeWorld);
        LevelUnlocks.Context unlockContext = new LevelUnlocks.Context(cleared,
                profile.unlockedLevels(), profile::owns, profile.coins(), profile.unlocksEverything());
        List<LevelListS2C.LevelInfo> levels = new ArrayList<>();
        BuiltInRegistries.LEVELS.keySet().stream()
                .sorted(Comparator.comparing(Identifier::toString))
                .forEach(id -> {
                    LevelDef def = BuiltInRegistries.LEVELS.get(id);
                    LevelUnlocks.State unlock = LevelUnlocks.evaluate(id, def.unlock(), unlockContext);
                    if (unlock.isHidden()) {
                        // A hidden level is not listed at all until it opens, so the client
                        // never learns it exists - the point of a secret level.
                        return;
                    }
                    List<LevelListS2C.TeamInfo> teams = def.teams().stream()
                            .map(t -> new LevelListS2C.TeamInfo(t.id().toString(), t.name(), t.winCondition()))
                            .toList();
                    LevelGrouping.Group group = groupOf(id);
                    LevelServer.SeedContext seeds = LevelServer.SeedContext.forProfile(def, profile);
                    // Two independent facts travel side by side: whether a run is waiting to
                    // be resumed, and the label. A cleared level with an abandoned replay is
                    // both "has a save" and "completed", and the entry decision needs the
                    // first while the row needs the second.
                    boolean runningSave = hasRunningSave(levelDir(worldPath(gameDir, safeWorld), id));
                    String status = runningSave ? LevelListS2C.LevelInfo.IN_PROGRESS
                            : (cleared.contains(id) ? LevelListS2C.LevelInfo.COMPLETED : "");
                    levels.add(LevelListS2C.LevelInfo.of(id.toString(), def.displayName(), def.description(),
                            def.winTeam().toString(), teams, status, levelIcon(def),
                            group.theme().toString(), group.category().toString(), runningSave,
                            LevelServer.payloadFor(def, seeds),
                            LevelListS2C.UnlockInfo.of(unlock)));
                });
        // Pages travel with the list they describe: a screen that received the levels but
        // not the tabs would have to invent them, and the two would be free to disagree.
        connection.send(profilePacket(profile));
        connection.send(new LevelTabsS2C(LevelTabs.build()));
        connection.send(new LevelListS2C(levels));
    }

    /**
     * Buys a level outright.
     *
     * <p>The price comes from the level's own definition and is never taken from the
     * client, so a modified client can only choose <em>which</em> level to buy. The order
     * matters: the wallet is charged, then the purchase is recorded, then the profile is
     * written - a crash between the last two would charge for nothing, so the write comes
     * before the player is told anything.
     */
    private void buyLevel(String levelId, String worldName) {
        Identifier id = Identifier.tryParse(levelId);
        LevelDef def = id == null ? null : BuiltInRegistries.LEVELS.get(id);
        if (def == null) {
            connection.send(new ServerMessageS2C("未找到关卡 " + levelId));
            return;
        }
        String safeWorld = sanitizeWorldName(worldName);
        PlayerProfile profile = profileFor(safeWorld);
        LevelUnlocks.State state = LevelUnlocks.evaluate(id, def.unlock(),
                unlockContext(safeWorld, profile));
        if (state.unlocked()) {
            // Already playable: report the refreshed list and charge nothing. This is what
            // a double-click, or a second client in the same world, ends up doing.
            sendLevelList(safeWorld);
            return;
        }
        int cost = state.cost();
        if (!state.buyable()) {
            connection.send(new ServerMessageS2C(cost <= 0
                    ? "关卡 " + id + " 不能用金币解锁"
                    : "金币不足：需要 " + cost + "，当前 " + profile.coins()));
            return;
        }
        profile.setCoins(profile.coins() - cost);
        profile.unlockLevel(id);
        saveProfile(safeWorld, profile);
        connection.send(new ServerMessageS2C("已解锁 " + def.displayName() + "，花费 " + cost + " 金币"));
        // The list carries the profile, so sending it also refreshes the coin counter and
        // the row's padlock in one go.
        sendLevelList(safeWorld);
    }

    /**
     * Everything the unlock rule needs about this world.
     *
     * <p>Assembled per request rather than cached: it depends on the profile (which a
     * purchase or a first clear changes) and on which levels have a completion marker on
     * disk (which a finished run writes). {@code sendLevelList} already reads every
     * level's status, so asking again for the cleared set costs the same file reads.
     */
    private LevelUnlocks.Context unlockContext(String worldName, PlayerProfile profile) {
        return new LevelUnlocks.Context(clearedLevels(worldName),
                profile.unlockedLevels(), profile::owns, profile.coins(), profile.unlocksEverything());
    }

    /**
     * The levels of one world that have a completion marker, in a single pass.
     *
     * <p>This is what the unlock rules ask, and it is deliberately independent of whether a
     * run is currently saved: a level that was cleared stays cleared, so a replay waiting to
     * be resumed must not take it out of the set that gates the levels behind it.
     *
     * <p>Deliberately not a loop over {@link #isLevelCompleted}: the level list also needs
     * each level's row label, so this reads every marker once and hands the answers to both
     * callers.
     */
    private Set<Identifier> clearedLevels(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return Set.of();
        }
        Path worldDir = worldPath(gameDir, worldName);
        Set<Identifier> cleared = new LinkedHashSet<>();
        for (Identifier id : BuiltInRegistries.LEVELS.keySet()) {
            if (isLevelCompleted(worldDir, id)) {
                cleared.add(id);
            }
        }
        return cleared;
    }

    /**
     * The bits of a world's record the {@code /profile} command reports.
     *
     * <p>A record rather than three getters so the command cannot read a profile that
     * changed between two of the calls.
     */
    public record ProfileSnapshot(String world, int coins, int cards, int levels, boolean sandbox) {
    }

    /**
     * What {@code /profile info} prints, for the world the menus are looking at.
     *
     * <p>Falls back to {@code currentWorld} and then to the default world name. Both are
     * null between a world being created and a level being entered, which used to report
     * the default world's wallet - a different world from the one on screen.
     */
    public ProfileSnapshot profileSnapshot() {
        String world = menuWorld();
        PlayerProfile snapshot = profileFor(world);
        return new ProfileSnapshot(sanitizeWorldName(world), snapshot.coins(),
                snapshot.unlockedIds().size(), snapshot.unlockedLevelIds().size(),
                snapshot.unlocksEverything());
    }

    /**
     * Marks a level as bought, without charging for it.
     *
     * <p>Reached by {@code /profile unlock <level>} - an operator action, which is why it
     * does not go through {@link #buyLevel}: there is nothing to pay with when the point
     * is to get past a gate.
     */
    public String grantLevelUnlock(Identifier levelId) {
        if (levelId == null || BuiltInRegistries.LEVELS.get(levelId) == null) {
            return "未找到关卡 " + levelId;
        }
        String safeWorld = sanitizeWorldName(menuWorld());
        PlayerProfile profile = profileFor(safeWorld);
        profile.unlockLevel(levelId);
        saveProfile(safeWorld, profile);
        refreshLevelList();
        connection.send(profilePacket(profile));
        return "已解锁关卡 " + levelId + "（世界 " + safeWorld + "）";
    }

    /** Switches the current world into sandbox mode: every card and every level. */
    public String grantEverything() {
        String safeWorld = sanitizeWorldName(menuWorld());
        PlayerProfile profile = profileFor(safeWorld);
        profile.unlockAll();
        saveProfile(safeWorld, profile);
        refreshLevelList();
        connection.send(profilePacket(profile));
        return "世界 " + safeWorld + " 已切换为沙盒：全部卡与全部关卡解锁";
    }

    /**
     * The world the menus are working with.
     *
     * <p>One answer for the profile snapshot and the two operator commands: they each used
     * to re-derive it, and the "no level list asked yet" case fell through to the default
     * world name in one of them.
     */
    private String menuWorld() {
        if (lastRequestedWorld != null) {
            return lastRequestedWorld;
        }
        return currentWorld != null ? currentWorld : "world";
    }

    /** The wallet and unlocks, in the one shape the client understands. */
    private static ProfileS2C profilePacket(PlayerProfile profile) {
        return new ProfileS2C(profile.coins(), profile.unlockedIds(), profile.unlocksEverything(),
                profile.unlockedLevelIds());
    }

    /**
     * The page a level belongs to, by the same rule the client renders with.
     *
     * <p>Uses the id, not the file path: an explicit {@code "id"} in the JSON wins over the
     * path in the loader, so the path is not a reliable statement of where a level is.
     */
    private static LevelGrouping.Group groupOf(Identifier id) {
        return LevelGrouping.resolve(id, List.copyOf(BuiltInRegistries.LEVEL_THEMES.keySet()),
                List.copyOf(BuiltInRegistries.LEVEL_CATEGORIES.keySet()));
    }

    /**
     * Reports levels whose id names a theme or category that does not exist.
     *
     * <p>Such a level silently lands in the unclassified tab, which is safe but points
     * nowhere: the author sees a level listed in the wrong place and no reason why.
     */
    private static List<String> validateLevelGroups() {
        List<Identifier> themeIds = List.copyOf(BuiltInRegistries.LEVEL_THEMES.keySet());
        List<Identifier> categoryIds = List.copyOf(BuiltInRegistries.LEVEL_CATEGORIES.keySet());
        List<String> errors = new ArrayList<>();
        for (Identifier id : BuiltInRegistries.LEVELS.keySet()) {
            String reason = LevelGrouping.unknownGroupReason(id, themeIds, categoryIds);
            if (reason != null) {
                errors.add(id + ": " + reason + " - it is listed as unclassified");
            }
        }
        return errors;
    }

    /**
     * Checks that the particle ids the built-in capabilities emit actually exist.
     *
     * <p>An effect with no definition draws nothing and says so once per second from
     * the client, which is a symptom with no address: nothing connects "the pea impact
     * is invisible" to a missing file under {@code data/pvzce/particles/}. A resource
     * pack that ships its own particles can legitimately drop one of ours, so this is
     * reported rather than treated as fatal.
     */
    private static List<String> validateBuiltInParticles() {
        List<String> missing = new ArrayList<>();
        for (Identifier id : PvzceParticles.all()) {
            if (BuiltInRegistries.PARTICLES.get(id) == null) {
                missing.add(id.toString());
            }
        }
        if (!missing.isEmpty()) {
            // Reported as information, not as a data error: a pack is allowed to drop a
            // particle we default to, and the alternative - a red square for every
            // unknown name - is what made a typo look like a real effect.
            System.out.println("[PVZCE] " + missing.size() + " built-in effect(s) have no particle definition "
                    + "and will draw nothing: " + String.join(", ", missing));
        }
        return List.of();
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

    private static Path worldPath(Path gameDir, String worldName) {
        return gameDir.resolve("saves").resolve(sanitizeWorldName(worldName));
    }

    /**
     * One level's save directory inside a world; the key is {@link LevelKey}'s.
     */
    private static Path levelDir(Path worldDir, Identifier id) {
        return worldDir.resolve("levels").resolve(LevelKey.of(id));
    }

    private static Path levelStatusFile(Path worldDir, Identifier id) {
        return worldDir.resolve("level_status").resolve(LevelKey.of(id) + ".dat");
    }

    /** A world's persistent player record: coins and unlocked cards. */
    private static Path profileFile(Path worldDir) {
        return worldDir.resolve("profile.dat");
    }

    /**
     * The profile of a world, loaded on first use.
     *
     * <p>A world without a record - every world that existed before profiles did -
     * gets {@link PlayerProfile#starter()}, the same thing a new world gets; see
     * the round-trip note in {@link #saveProfile}. Never returns null, so callers
     * do not have to decide what an absent profile means.
     */
    public PlayerProfile profileFor(String worldName) {
        String safeWorld = sanitizeWorldName(worldName);
        if (profile != null && safeWorld.equals(profileWorld)) {
            return profile;
        }
        Path file = profileFile(worldPath(gameDir, safeWorld));
        PlayerProfile loaded = PlayerProfile.starter();
        if (Files.isRegularFile(file)) {
            try {
                loaded = PlayerProfile.load(NbtIo.readCompressed(file));
            } catch (Throwable t) {
                System.err.println("Failed to read profile " + file + ": " + t.getMessage()
                        + " - starting from the default profile.");
            }
        }
        profile = loaded;
        profileWorld = safeWorld;
        return loaded;
    }

    /** Writes a world's profile, creating the world directory when needed. */
    public void saveProfile(String worldName, PlayerProfile toSave) {
        String safeWorld = sanitizeWorldName(worldName);
        Path worldDir = worldPath(gameDir, safeWorld);
        try {
            Files.createDirectories(worldDir);
            NbtIo.writeCompressed(toSave.save(), profileFile(worldDir));
        } catch (Throwable t) {
            System.err.println("Failed to write profile for " + safeWorld + ": " + t.getMessage());
        }
        profile = toSave;
        profileWorld = safeWorld;
    }

    /** Flushes the cached profile if it belongs to this world; used on the way out. */
    private void flushProfile() {
        if (profile != null && profileWorld != null) {
            saveProfile(profileWorld, profile);
        }
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
        // Leaving a world for another one has to write the first world's record
        // before the cache below replaces it.
        if (currentWorld != null && !safeWorld.equals(currentWorld)) {
            flushProfile();
        }
        // Refused before anything is torn down: a locked level must not close the run the
        // player is already in. The verdict is recomputed here rather than trusted from
        // the list, so a client that skips the menu cannot walk past the gate.
        LevelUnlocks.State unlock = LevelUnlocks.evaluate(id, def.unlock(),
                unlockContext(safeWorld, profileFor(safeWorld)));
        if (!unlock.unlocked()) {
            String label = def.displayName().isBlank() ? id.toString() : def.displayName();
            connection.send(new ServerMessageS2C("关卡 " + label + " 尚未解锁：" + unlock.reason()));
            return;
        }
        if (level != null && currentLevelId != null && currentLevelId.equals(id)
                && safeWorld.equals(currentWorld) && !restart) {
            level.sendFullState(bridge);
            connection.send(new GameSpeedS2C(tickRate.tickRate()));
            return;
        }
        Path worldDir = worldPath(gameDir, safeWorld);
        Path saveDir = levelDir(worldDir, id);
        boolean hasSave = hasRunningSave(saveDir);
        // Resolved before the level is built: the backpack decides the card bar.
        PlayerProfile profile = profileFor(safeWorld);

        // Switching to a different level without an explicit restart keeps
        // the current level's progress under its own save directory.
        if (level != null && currentWorld != null && !restart) {
            saveGame();
        }

        boolean loadSave = !restart && hasSave;
        if (!loadSave && hasSave) {
            deleteRunningSave(saveDir);
        }

        // A conveyor level's bar comes from its belt: the level's own cards would be a
        // second source for the same bar, and the saved selection of such a level is the
        // belt it was holding, which the belt restores itself.
        List<Identifier> seeds = def.hasConveyor()
                ? List.of()
                : requestedSeeds == null ? null : sanitizeSeedSelection(def, requestedSeeds, profile);
        if (loadSave && !def.hasConveyor()) {
            // Continuing a save restores the exact card bar the player had.
            List<Identifier> savedSeeds = readSavedSeedSelection(saveDir);
            if (savedSeeds != null) {
                seeds = sanitizeSeedSelection(def, savedSeeds, profile);
            }
        }
        if (seeds == null) {
            seeds = defaultSeedSelection(def, profile);
        }

        LevelServer newLevel = new LevelServer(def, seeds, LevelServer.SeedContext.forProfile(def, profile));
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
        // A run's music belongs to that run. A level's timeline switches from the background
        // track to the battle track at a wave, and a restart used to leave that battle loop
        // playing underneath the new run's track, because only leaving a level stopped it.
        // The reset goes out *before* the level init: the init is what starts the new track.
        // A resync of the running level never reaches this point - its music is still current.
        silenceClientLevelMusic();
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

    /**
     * Stops the tracks a level owns on the client, whatever it was playing.
     *
     * <p>Called when a <em>new</em> level instance takes over (see {@link #createLevel}); the
     * client's own {@code leaveLevel()} only covers the paths that go through leaving a level,
     * and a restart from the save prompt or from the editor's test button never leaves one -
     * so the previous run's track kept looping under the new run's music.
     */
    private void silenceClientLevelMusic() {
        connection.send(MusicEventS2C.reset(MusicEventS2C.TRACK_BACKGROUND));
        connection.send(MusicEventS2C.reset(MusicEventS2C.TRACK_BATTLE));
        connection.send(MusicEventS2C.reset(MusicEventS2C.TRACK_STINGER));
    }

    /**
     * The bar a level actually starts with: the level's own cards, then as many of the
     * player's picks as still fit.
     *
     * <p>The level's cards come first and are never dropped, so a client cannot talk its way
     * out of them, and a pick naming a card the level already pinned is ignored. The slot
     * count applies to the combined bar, which is why a level whose {@code slots} already
     * fill it simply grants those.
     *
     * <p>{@code profile} filters what may be <em>picked</em>, never what the level pinned:
     * a level that hands the player a card they have not unlocked still gets to do that,
     * which is exactly how the first level works before sunflower exists. The same filter
     * builds the pool the client was shown ({@link LevelServer.SeedContext}), so a card the
     * client could not see is also a card this method refuses.
     */
    private static List<Identifier> sanitizeSeedSelection(LevelDef def, List<Identifier> requested,
                                                         PlayerProfile profile) {
        List<Identifier> pool = SeedOptions.cardPool(def, profile == null ? null : profile::owns);
        LevelDef.SeedPlan plan = def.seedPlan(pool);
        List<Identifier> result = new ArrayList<>();
        Set<Identifier> seen = new HashSet<>();
        for (Identifier locked : plan.lockedSlots()) {
            if (seen.add(locked)) {
                result.add(locked);
            }
        }
        Set<Identifier> pickable = new HashSet<>(plan.pickableSlots());
        for (Identifier seed : requested) {
            if (seed == null || !pickable.contains(seed) || !seen.add(seed)) {
                continue;
            }
            result.add(seed);
            if (result.size() >= plan.maxSlots()) {
                break;
            }
        }
        return List.copyOf(result);
    }

    /**
     * The bar a level starts with when nobody made a choice - the "next level" button
     * rather than the seed chooser.
     *
     * <p>Fills the free slots from the backpack, so a level that pins nothing hands the
     * player everything they have unlocked instead of an empty bar. A level whose own cards
     * already fill the bar is unaffected.
     */
    private static List<Identifier> defaultSeedSelection(LevelDef def, PlayerProfile profile) {
        return def.defaultSeedSelection(SeedOptions.cardPool(def, profile == null ? null : profile::owns));
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

    /**
     * Gives a freshly created world its profile.
     *
     * <p>The directory is made on the client (the world list reads the disk
     * directly) and this may arrive before or after that; either way the profile
     * write creates the directory too, so a world always ends up with a record.
     */
    private void createWorld(String worldName, boolean unlockAll) {
        String safeWorld = sanitizeWorldName(worldName);
        PlayerProfile created = unlockAll ? PlayerProfile.unlockEverything() : PlayerProfile.starter();
        saveProfile(safeWorld, created);
        connection.send(new ServerMessageS2C("世界 " + safeWorld + " 已创建"
                + (unlockAll ? "（全解锁）" : "（初始：豌豆射手 + 铲子）")));
        connection.send(profilePacket(created));
    }

    public void leaveLevel() {
        savePromptPending = false;
        manualPause = false;
        saveGame();
        // Coins and unlocks outlive the level they were earned in.
        flushProfile();
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
        newLevel.restore(root);
    }

    /**
     * Called when the current level reports a non-running state. A win writes
     * a small completion marker and both win/loss clear the resumable save.
     *
     * <p>This is also the one place a run turns into persistent progress: the coins
     * the level collected are banked whatever the outcome (they were already earned
     * inside the run), and a first clear additionally pays out the level's
     * {@code rewards.first_clear} - which is how the first level hands over
     * sunflower. Replays pay {@code rewards.repeat} instead, because the unlock is
     * already owned.
     *
     * <p>Runs from the bridge callback inside {@link LevelServer#tick}, so the
     * profile is mutated in memory and written once, here, rather than on every
     * coin.
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
            boolean plantWin = isPlantWin(current);
            boolean firstClear = plantWin && !isLevelCompleted(currentWorld, id);
            if (plantWin) {
                writeLevelStatus(worldDir, id, current);
            }
            deleteRunningSave(levelDir(worldDir, id));
            System.out.println("[PVZCE] Level finished, cleared running save for " + id);
            // Banks the run and pushes a fresh level list - after the running save is
            // gone, because that file IS the "进行中" label: refreshing first described
            // the level as still resumable, which is the opposite of what just happened.
            awardProfile(id, current, plantWin, firstClear);
        } catch (Throwable t) {
            System.err.println("Failed to finish level " + id + ": " + t.getMessage());
        }
    }

    /**
     * Turns a finished run into profile progress and tells the client what it got.
     *
     * <p>Split out of {@link #onLevelFinished} because it is the half that must stay
     * correct when the win/loss rules change: the level's own card rewards only apply
     * on a plant win, while collected coins bank either way.
     */
    private void awardProfile(Identifier id, LevelServer current, boolean plantWin, boolean firstClear) {
        PlayerProfile profile = profileFor(currentWorld);
        LevelRewards rewards = current.def().rewards();

        int collected = collectedCoins(current);
        // The level's own rewards are for a win, first clear or repeat; a loss banks only
        // what the run picked up. This used to read ``isPlantWin(current)`` - and the
        // refactor that pulled the payout out of here dropped that condition, which paid
        // every loss the full completion stipend.
        RewardOutcome outcome = plantWin
                ? applyRewards(id, rewards, firstClear, profile)
                : new RewardOutcome(0, null);
        int bonus = outcome.bonus();
        Identifier unlocked = outcome.unlocked();
        profile.grantCoins(collected + bonus);
        saveProfile(currentWorld, profile);
        // The award screen is presentation; the wallet is already written above, so
        // a client that never renders the screen still got its coins.
        connection.send(new LevelRewardS2C(id.toString(), collected, bonus, profile.coins(),
                unlocked == null ? "" : unlocked.toString(),
                current.lastKillX(), current.lastKillY()));
        connection.send(profilePacket(profile));
        // The finished level's row must stop saying "进行中" without a round trip. The
        // refresh names the world the level was in, not the last world a *list* was
        // asked for: those differ whenever a level was entered without going through
        // the list (the editor's test button), and the row belongs to the former.
        sendLevelList(currentWorld);
    }

    /** What one payout granted: the coin bonus, and the first card it unlocked (if any). */
    private record RewardOutcome(int bonus, Identifier unlocked) {
    }

    /**
     * Applies a level's rewards to a profile.
     *
     * <p>The coins follow the first-clear/repeat split. The unlocks do <em>not</em>: a
     * {@code first_clear} unlock is paid out on any clear whose profile does not have that
     * card yet. "First clear" is a file's existence, not "the reward was handed over", and
     * the two disagree in both directions that matter:
     *
     * <ul>
     *   <li>a world cleared before the level declared the unlock can never receive it -
     *       every later clear takes the repeat branch, so the reward page shows the money
     *       bag forever (this is what happened to 1-4's glove);</li>
     *   <li>a pack that adds an unlock to an already-cleared level would never pay it.</li>
     * </ul>
     *
     * <p>Granting an already-owned card is a no-op, so this is idempotent: the card is
     * what is idempotent, not the payout. Repeat coins are unaffected, so a replay still
     * pays its stipend.
     *
     * <p>Shared by the real win and by {@link #awardProfileForTest}, because the bug this
     * guards against lives here and nowhere else.
     */
    private RewardOutcome applyRewards(Identifier id, LevelRewards rewards, boolean firstClear,
                                       PlayerProfile profile) {
        int bonus = 0;
        Identifier unlocked = null;
        for (LevelRewards.Reward reward : rewards.firstClear()) {
            if (!reward.isUnlock() || reward.id().isEmpty()) {
                continue;
            }
            Identifier card = reward.id().get();
            if (profile.owns(card)) {
                // Already in the backpack - an ordinary replay of a level whose unlock was
                // paid long ago, or a sandbox world where everything is open. Nothing to do;
                // ``unlock`` is a set add and would report a grant the player cannot see.
                continue;
            }
            if (!SlotResolver.requiresUnlock(card)) {
                System.err.println("[PVZCE] Level " + id + " rewards '" + card
                        + "' as an unlock, but that card needs no unlocking."
                        + " The reward does nothing.");
                continue;
            }
            if (profile.unlock(card) && unlocked == null) {
                unlocked = card;
            }
        }
        if (firstClear) {
            for (LevelRewards.Reward reward : rewards.firstClear()) {
                if (reward.isCoins()) {
                    bonus += reward.amount();
                }
            }
        } else {
            for (LevelRewards.Reward reward : rewards.repeat()) {
                if (reward.isCoins()) {
                    bonus += reward.amount();
                }
            }
        }
        return new RewardOutcome(bonus, unlocked);
    }

    /**
     * Runs the reward payout for a level without a finished run.
     *
     * <p>For tests of the payout itself: reaching it through a real win would mostly
     * exercise the simulation, and the bug it guards against - an unlock reward that
     * granted nothing because it was gated on {@code owns} - is entirely in here.
     */
    public void awardProfileForTest(Identifier levelId, String worldName, boolean firstClear) {
        LevelDef def = BuiltInRegistries.LEVELS.get(levelId);
        if (def == null) {
            throw new IllegalArgumentException("unknown level " + levelId);
        }
        PlayerProfile profile = profileFor(worldName);
        applyRewards(levelId, def.rewards(), firstClear, profile);
        saveProfile(worldName, profile);
    }

    /**
     * Coins the run picked up, summed over every denomination.
     *
     * <p>Each denomination's amount is already its worth in coins, so this is a sum and
     * not a conversion - the ladder lives in the resource definitions, once.
     */
    private static int collectedCoins(LevelServer current) {
        Team plantTeam = current.team(PvzceIds.PLANT_TEAM);
        if (plantTeam == null) {
            return 0;
        }
        int total = 0;
        for (Identifier denomination : PvzceIds.COIN_DENOMINATIONS) {
            total += plantTeam.resourcesOf(denomination);
        }
        return total;
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
        status.putString("GameState", LevelListS2C.LevelInfo.COMPLETED);
        status.putString("Winner", current.winner().toString());
        status.putInt("Tick", current.tickCount());
        status.putInt("PlantCount", current.plantCount());
        NbtIo.writeCompressed(status, statusFile);
    }

    /**
     * True when this level has a completion marker in the world - "cleared at least once".
     *
     * <p><b>Completion is permanent.</b> The marker is a record of something that already
     * happened, so nothing that happens later may take it away: replaying a cleared level,
     * losing that replay, or leaving it half-finished all leave it cleared.
     *
     * <p>It is deliberately not the same question as "has a resumable save", which is a
     * property of the moment and lives beside it in {@code sendLevelList} and in
     * {@link LevelListS2C.LevelInfo#hasRunningSave()}. Reading one off the other is what
     * made an abandoned replay of a cleared level both lock the levels behind it again and
     * hide the fact that a run was waiting to be resumed.
     *
     * <p>Failed levels leave no marker of their own: a level that was never cleared has
     * nothing to report.
     */
    private boolean isLevelCompleted(String worldName, Identifier id) {
        if (worldName == null || worldName.isBlank()) {
            return false;
        }
        return isLevelCompleted(worldPath(gameDir, worldName), id);
    }

    /** True when this world's completion marker for the level is on disk and says so. */
    private static boolean isLevelCompleted(Path worldDir, Identifier id) {
        Path statusFile = levelStatusFile(worldDir, id);
        if (!Files.isRegularFile(statusFile)) {
            return false;
        }
        try {
            CompoundTag status = NbtIo.readCompressed(statusFile);
            return LevelListS2C.LevelInfo.COMPLETED.equals(status.getString("GameState"));
        } catch (Throwable t) {
            return false;
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
                lastRequestedWorld = request.worldName();
                sendLevelList(request.worldName());
            } else if (packet instanceof RequestSuggestionsC2S suggestions) {
                sendSuggestions(suggestions.input(), suggestions.requestId());
            } else if (packet instanceof SetGameSpeedC2S speed) {
                int speedIndex = Math.max(1, Math.min(3, speed.speedIndex()));
                tickRate.setTickRate(PvzceTickRateManager.DEFAULT_TICK_RATE * speedIndex);
                connection.send(new GameSpeedS2C(tickRate.tickRate()));
            } else if (packet instanceof PauseGameC2S pause) {
                manualPause = pause.paused();
            } else if (packet instanceof CreateWorldC2S create) {
                createWorld(create.worldName(), create.unlockAll());
            } else if (packet instanceof com.pvzce.common.network.packet.UnlockLevelC2S unlockLevel) {
                buyLevel(unlockLevel.levelId(), unlockLevel.worldName());
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
