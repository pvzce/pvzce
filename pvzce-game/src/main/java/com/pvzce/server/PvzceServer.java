package com.pvzce.server;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.api.util.LevelGrouping;
import com.pvzce.common.PvzceParticles;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.nbt.CompoundTag;
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
import com.pvzce.common.network.packet.ReloadPacksC2S;
import com.pvzce.common.network.packet.ContinueLevelC2S;
import com.pvzce.common.network.packet.RequestLevelListC2S;
import com.pvzce.common.network.packet.RequestProfileC2S;
import com.pvzce.common.network.packet.RequestSuggestionsC2S;
import com.pvzce.common.network.packet.PlayLevelC2S;
import com.pvzce.common.network.packet.ServerMessageS2C;
import com.pvzce.common.network.packet.SetDifficultyC2S;
import com.pvzce.common.network.packet.SetGameSpeedC2S;
import com.pvzce.common.network.packet.RestartLevelC2S;
import com.pvzce.common.network.packet.SuggestionsS2C;
import com.pvzce.common.network.packet.UseToolC2S;
import com.pvzce.common.core.LevelUnlocks;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.resource.PvzceDataLoader;
import com.pvzce.common.resource.PvzceResourceManager;
import com.pvzce.common.tag.PvzceTags;
import com.pvzce.common.tag.TagManager;
import com.pvzce.server.command.PvzceCommandSource;
import com.pvzce.server.command.PvzceCommands;
import com.pvzce.common.util.LevelKey;
import com.pvzce.common.util.WorldPaths;
import com.pvzce.server.level.LevelServer;
import com.pvzce.server.level.LevelTabs;
import com.pvzce.server.level.LevelValidator;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

/** The 60tps authoritative game server (integrated in phase 1). */
public final class PvzceServer implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger("PVZCE/Server");

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
    /** Where this world's profile and level saves live. */
    private final WorldStore worlds;
    private Thread thread;
    private long lastDebugInfoNanos;

    public PvzceServer(Connection connection, Path gameDir, ClassLoader classLoader) {
        this.worlds = new WorldStore(gameDir);
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

    /** The world store: profiles, run saves and completion markers on disk. */
    public WorldStore worlds() {
        return worlds;
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
            LOGGER.warn("Suggestions failed for '" + input + "'", e);
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
                    // The level's own counter rides along: the tutorial's timed lines are written
                    // against it, and the server's clock counts across levels.
                    connection.send(new DebugInfoS2C(tickRate.tickCount(),
                            level == null ? 0 : level.tickCount(),
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
                        LOGGER.debug("tick check: tick={} elapsed_ms={} target={}tps",
                                tickCount, elapsedMs, tickRate.tickRate());
                    }
                }
                if (ticks == MAX_TICKS_PER_FRAME) {
                    tickRate.reset();
                }

                LockSupport.parkNanos(tickRate.nanosUntilNextTick());
            }
        } catch (Throwable t) {
            LOGGER.error("The server loop failed", t);
            connection.send(new ServerMessageS2C("服务器异常: " + t.getMessage()));
        } finally {
            saveGame();
            // The wallet has to survive a shutdown that never went through leaveLevel.
            worlds.flushProfile();
            try {
                resourceManager.close();
            } catch (IOException e) {
                LOGGER.warn("Failed to close the resource manager", e);
            }
        }
    }

    private void invokeServerEntrypoints() {
        try {
            FabricLoader.getInstance().invokeEntrypoints("server", DedicatedServerModInitializer.class,
                    DedicatedServerModInitializer::onInitializeServer);
        } catch (Throwable t) {
            LOGGER.error("A server entrypoint failed", t);
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
            reportErrors("伤害类型", LevelValidator.validateDamageTypes());
        } catch (Throwable t) {
            LOGGER.error("Reload failed", t);
            if (announce) {
                connection.send(new ServerMessageS2C("重载失败: " + t.getMessage()));
            }
        }
    }

    /** Surfaces load errors to the log and (at most once per message) to the player. */
    private void reportErrors(String label, java.util.List<String> errors) {
        for (String error : errors) {
            LOGGER.warn("[{}] {}", label, error);
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
        String safeWorld = WorldPaths.sanitize(worldName);
        // The list is where a client learns the world's coins and unlocks, so the
        // profile travels with it rather than on a separate request.
        PlayerProfile profile = worlds.profileFor(safeWorld);
        Set<Identifier> cleared = clearedLevels(safeWorld);
        LevelUnlocks.Context unlockContext = new LevelUnlocks.Context(cleared,
                profile.unlockedLevels(), profile::ownsCard, profile.coins(), profile.unlocksEverything());
        List<LevelListS2C.LevelInfo> levels = new ArrayList<>();
        BuiltInRegistries.LEVELS.keySet().stream()
                // Natural order, so 1-10 comes after 1-9 instead of after 1-1.
                .sorted(Comparator.comparing(Identifier::toString,
                        com.pvzce.api.util.LevelGrouping.idOrder()))
                .forEach(id -> {
                    LevelDef def = BuiltInRegistries.LEVELS.get(id);
                    LevelUnlocks.State unlock = LevelUnlocks.evaluate(id, def.unlock(), unlockContext);
                    if (unlock.isHidden()) {
                        // A hidden level is not listed at all until it opens, so the client
                        // never learns it exists - the point of a secret level.
                        return;
                    }
                    List<LevelListS2C.TeamInfo> teams = def.teams().stream()
                            .map(t -> new LevelListS2C.TeamInfo(t.id().toString(), t.name(), t.winCondition(),
                                    def.playableTeamDefs().contains(t)))
                            .toList();
                    LevelGrouping.Group group = groupOf(id);
                    LevelServer.SeedContext seeds = LevelServer.SeedContext.forProfile(def, profile);
                    // Two independent facts travel side by side: whether a run is waiting to
                    // be resumed, and the label. A cleared level with an abandoned replay is
                    // both "has a save" and "completed", and the entry decision needs the
                    // first while the row needs the second. Whether it was ever cleared
                    // travels as its own third field: the row's trophy is earned once and is
                    // not taken back by an abandoned replay.
                    boolean runningSave = WorldStore.hasRunningSave(LevelKey.levelDir(WorldPaths.worldDir(gameDir, safeWorld), id));
                    boolean beaten = cleared.contains(id);
                    String status = runningSave ? LevelListS2C.LevelInfo.IN_PROGRESS
                            : (beaten ? LevelListS2C.LevelInfo.COMPLETED : "");
                    levels.add(LevelListS2C.LevelInfo.of(id.toString(), def.displayName(), def.description(),
                            def.winTeam().toString(), teams, status, levelIcon(def),
                            group.theme().toString(), group.category().toString(), runningSave, beaten,
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
        String safeWorld = WorldPaths.sanitize(worldName);
        PlayerProfile profile = worlds.profileFor(safeWorld);
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
        worlds.saveProfile(safeWorld, profile);
        connection.send(new ServerMessageS2C("已解锁 " + def.displayName() + "，花费 " + cost + " 金币"));
        // The list carries the profile, so sending it also refreshes the coin counter and
        // the row's padlock in one go.
        sendLevelList(safeWorld);
    }

    /**
     * One shop purchase.
     *
     * <p>The price is re-read here from the catalogue rather than taken from the packet, exactly as
     * {@link #buyLevel} re-derives a level's cost: the client's copy of the number is for drawing,
     * and this is the copy that charges.
     *
     * <p>Charging and applying are deliberately in that order and both before the save, so a world
     * whose profile fails to apply the item (a ceiling reached between the click and the packet)
     * is not charged for it. {@code ShopPurchases.apply} is the one place that decides, and it
     * returns the reason rather than throwing.
     */
    private void buyShopItem(String itemId, String worldName) {
        Identifier id = Identifier.tryParse(itemId);
        com.pvzce.common.shop.ShopItems.Item item = id == null
                ? null : com.pvzce.common.shop.ShopItems.byId(id).orElse(null);
        if (item == null) {
            connection.send(new ServerMessageS2C("商店里没有 " + itemId));
            return;
        }
        String safeWorld = WorldPaths.sanitize(worldName);
        PlayerProfile profile = worlds.profileFor(safeWorld);
        if (com.pvzce.server.shop.ShopPurchases.maxedOut(profile, item)) {
            connection.send(new ServerMessageS2C("已经拥有 " + id + " 了"));
            return;
        }
        if (profile.coins() < item.price()) {
            connection.send(new ServerMessageS2C("金币不足：需要 " + item.price()
                    + "，当前 " + profile.coins()));
            return;
        }
        String refusal = com.pvzce.server.shop.ShopPurchases.apply(profile, item);
        if (!refusal.isEmpty()) {
            connection.send(new ServerMessageS2C(refusal));
            return;
        }
        profile.setCoins(profile.coins() - item.price());
        worlds.saveProfile(safeWorld, profile);
        connection.send(new ServerMessageS2C("已购买 " + id + "，花费 " + item.price() + " 金币"));
        // The list carries the profile, so sending it also refreshes the wallet and the "已拥有"
        // marks in one go - the same reason `buyLevel` ends by re-sending the list.
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
                profile.unlockedLevels(), profile::ownsCard, profile.coins(), profile.unlocksEverything());
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
        Path worldDir = WorldPaths.worldDir(gameDir, worldName);
        Set<Identifier> cleared = new LinkedHashSet<>();
        for (Identifier id : BuiltInRegistries.LEVELS.keySet()) {
            if (WorldStore.isCompletedIn(worldDir, id)) {
                cleared.add(id);
            }
        }
        return cleared;
    }

    /**
     * The bits of a world's record the {@code /profile} command reports.
     *
     * <p>A record rather than a handful of getters so the command cannot read a profile
     * that changed between two of the calls.
     */
    public record ProfileSnapshot(String world, int coins, int cards, int levels, boolean sandbox,
                                  int seedSlots, int buffSlots, int autoBuffs) {
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
        PlayerProfile snapshot = worlds.profileFor(world);
        return new ProfileSnapshot(WorldPaths.sanitize(world), snapshot.coins(),
                snapshot.unlockedIds().size(), snapshot.unlockedLevelIds().size(),
                snapshot.unlocksEverything(), snapshot.seedSlots(), snapshot.buffSlots(),
                snapshot.autoBuffs().size());
    }

    /**
     * Sets the backpack's card-slot count.
     *
     * <p>Reached by {@code /profile slots <n>}. This is the same door a future shop
     * upgrade would use - {@link PlayerProfile#addSeedSlots} is the other half - so the
     * clamp lives in the profile and both callers get the same ceiling.
     */
    public String grantSeedSlots(int slots) {
        String safeWorld = WorldPaths.sanitize(menuWorld());
        PlayerProfile profile = worlds.profileFor(safeWorld);
        profile.setSeedSlots(slots);
        worlds.saveProfile(safeWorld, profile);
        refreshLevelList();
        connection.send(profilePacket(profile));
        return "世界 " + safeWorld + " 的卡槽数现在是 " + profile.seedSlots()
                + "（关卡自己声明了 max_seed_slots 时以关卡为准）";
    }

    /**
     * Sets the backpack's buff-slot count.
     *
     * <p>The buff twin of {@link #grantSeedSlots}, reached by {@code /profile buffslots <n>}:
     * the count a level uses when it declares no {@code max_buff_slots} of its own.
     */
    public String grantBuffSlots(int slots) {
        String safeWorld = WorldPaths.sanitize(menuWorld());
        PlayerProfile profile = worlds.profileFor(safeWorld);
        profile.setBuffSlots(slots);
        worlds.saveProfile(safeWorld, profile);
        refreshLevelList();
        connection.send(profilePacket(profile));
        return "世界 " + safeWorld + " 的增益槽现在是 " + profile.buffSlots()
                + "（关卡自己声明了 max_buff_slots 时以关卡为准）";
    }

    /**
     * Replaces this world's auto-enabled buff list.
     *
     * <p>Reached by {@code /profile buffs <id...>}, and by {@code /profile buffs} with nothing
     * after it, which clears the list. It writes the same field the chooser writes when a run
     * starts, so a smoke run or a level under test can be set up without clicking through a
     * screen first.
     */
    public String setAutoBuffs(List<Identifier> buffs) {
        String safeWorld = WorldPaths.sanitize(menuWorld());
        PlayerProfile profile = worlds.profileFor(safeWorld);
        profile.setAutoBuffs(buffs);
        worlds.saveProfile(safeWorld, profile);
        refreshLevelList();
        connection.send(profilePacket(profile));
        return profile.autoBuffs().isEmpty()
                ? "世界 " + safeWorld + " 的自动启用增益已清空"
                : "世界 " + safeWorld + " 的自动启用增益：" + profile.autoBuffIds();
    }

    /**
     * Hands this world a level buff without making it clear the level that gives it.
     *
     * <p>Reached by {@code /profile buff <id>}. The same door the reward from 1-9 / 2-9 opens -
     * {@link PlayerProfile#unlockBuff} is the other half - so a smoke run or a level under test can
     * have the unlocked pool without a full playthrough.
     */
    public String grantBuff(Identifier buff) {
        if (buff == null || !com.pvzce.common.buff.LevelBuffs.isRegistered(buff)) {
            return "未找到增益 " + buff;
        }
        String safeWorld = WorldPaths.sanitize(menuWorld());
        PlayerProfile profile = worlds.profileFor(safeWorld);
        boolean granted = profile.unlockBuff(buff);
        worlds.saveProfile(safeWorld, profile);
        refreshLevelList();
        connection.send(profilePacket(profile));
        return granted
                ? "世界 " + safeWorld + " 已获得增益 " + buff
                : "世界 " + safeWorld + " 早就有增益 " + buff + " 了";
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
        String safeWorld = WorldPaths.sanitize(menuWorld());
        PlayerProfile profile = worlds.profileFor(safeWorld);
        profile.unlockLevel(levelId);
        worlds.saveProfile(safeWorld, profile);
        refreshLevelList();
        connection.send(profilePacket(profile));
        return "已解锁关卡 " + levelId + "（世界 " + safeWorld + "）";
    }

    /**
     * Puts coins in the menus' world, the way a level's payout does.
     *
     * <p>Reached by {@code /profile coins <n>}. It exists for the one state no other command can
     * produce: a world that can <em>afford</em> something. The shop's whole page is a price list,
     * and {@code pvzce.smokeCoins} uses this to photograph it next to a real balance rather than
     * next to zero. Named "...ToWorld" rather than {@code grantCoins} because the profile has a
     * method by that name and the two are not the same call: this one picks the world, saves it and
     * pushes the new wallet to the client.
     */
    public String grantCoinsToWorld(int amount) {
        if (amount < 0) {
            return "金币要一个非负数，收到 " + amount;
        }
        String safeWorld = WorldPaths.sanitize(menuWorld());
        PlayerProfile profile = worlds.profileFor(safeWorld);
        int added = profile.grantCoins(amount);
        worlds.saveProfile(safeWorld, profile);
        refreshLevelList();
        connection.send(profilePacket(profile));
        return "世界 " + safeWorld + " 的金币现在是 " + profile.coins()
                + (added < amount ? "（上限截断了 " + (amount - added) + "）" : "");
    }

    /** Switches the current world into sandbox mode: every card and every level. */
    public String grantEverything() {
        String safeWorld = WorldPaths.sanitize(menuWorld());
        PlayerProfile profile = worlds.profileFor(safeWorld);
        profile.unlockAll();
        worlds.saveProfile(safeWorld, profile);
        refreshLevelList();
        connection.send(profilePacket(profile));
        return "世界 " + safeWorld + " 已切换为沙盒：全部卡与全部关卡解锁";
    }

    /**
     * Says one line from the player, into whatever the message log is attached to.
     *
     * <p>Trimmed and cut rather than refused: a chat line too long to store is a player holding a
     * key down, not an attack, and dropping it silently would look like the key did nothing. Blank
     * lines are dropped - the client refuses to send them, and a modified one that does gets
     * nothing said back.
     */
    private void say(String text) {
        if (text == null) {
            return;
        }
        String line = text.strip();
        if (line.isEmpty()) {
            return;
        }
        if (line.length() > com.pvzce.common.network.packet.ChatC2S.MAX_LENGTH) {
            line = line.substring(0, com.pvzce.common.network.packet.ChatC2S.MAX_LENGTH);
        }
        String said = WorldPaths.sanitize(menuWorld()) + "：" + line;
        LevelServer running = level;
        if (running == null) {
            connection.send(new com.pvzce.common.network.packet.ServerMessageS2C(said));
            return;
        }
        // `send`, not `sendMessage`: the latter needs the bridge a *tick* installs, and a command
        // runs between ticks - so the line was accepted and dropped. `send` falls back to the
        // level's own outbound channel, which is the same one every other packet uses.
        running.send(new com.pvzce.common.network.packet.ServerMessageS2C(said));
    }

    /** The menu world's tier; what {@code /difficulty} reports with no level running. */
    public com.pvzce.common.level.Difficulty menuDifficulty() {
        return worlds.profileFor(WorldPaths.sanitize(menuWorld())).difficulty();
    }

    /**
     * Switches the menu world's difficulty, and the running level with it.
     *
     * <p>The tier is a fact about the <em>world</em>, so it is written to the profile and saved -
     * a save that moved to another machine plays the way its owner left it. The running level is
     * retuned as well ("switchable at any time"): its rules are unfolded from the old tier and
     * folded into the new one, so the next zombie to spawn has the new health and speed. Entities
     * already on the lawn keep what they spawned with, which is the only reading of a mid-run
     * change that cannot make a zombie's health bar jump under the player's cursor.
     *
     * @return a message for the console, or an empty string on success
     */
    public String setDifficulty(String name) {
        if (!com.pvzce.common.level.Difficulty.isKnown(name)) {
            return "没有这个难度档：" + name;
        }
        com.pvzce.common.level.Difficulty tier =
                com.pvzce.common.level.Difficulty.parse(name);
        String world = WorldPaths.sanitize(menuWorld());
        PlayerProfile profile = worlds.profileFor(world);
        boolean changed = profile.setDifficulty(tier);
        if (changed) {
            worlds.saveProfile(world, profile);
        }
        LevelServer running = level;
        if (running != null && running.difficulty() != tier) {
            running.setDifficulty(tier);
        }
        if (changed) {
            connection.send(profilePacket(profile));
        }
        return "";
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

    /**
     * The wallet, card slots, buff slots and unlocks, in the one shape the client understands.
     *
     * <p>{@code pvzce.smokeUnlockAll} flips the flag on the way out, for the screenshot runs that
     * need to see a page a new world cannot reach: the almanac draws a locked plant as a
     * silhouette, so "what an entry looks like once you own it" is otherwise 32 levels of play
     * away. The flag is a display fact the client re-checks nothing against - every card pool the
     * server hands out is filtered by the same profile - and it is only ever set by a smoke
     * property, so a normal run cannot reach this branch.
     */
    private static ProfileS2C profilePacket(PlayerProfile profile) {
        boolean unlockAll = profile.unlocksEverything()
                || Boolean.getBoolean("pvzce.smokeUnlockAll");
        return new ProfileS2C(profile.coins(), profile.unlockedIds(), unlockAll,
                profile.unlockedLevelIds(), profile.seedSlots(), profile.buffSlots(),
                profile.autoBuffIds(), profile.unlockedBuffIds(), profile.difficulty().key());
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
            LOGGER.info("{} built-in effect(s) have no particle definition and will draw nothing: {}",
                    missing.size(), String.join(", ", missing));
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
            } catch (RuntimeException e) {
                LOGGER.warn("Level {} has a non-numeric night_length; showing it as a day level",
                        def.id(), e);
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

    /** A world's persistent player record: coins and unlocked cards. */
    /**
     * What the client asked for when it named a level.
     *
     * <p>Recovered from the packet <em>type</em> rather than from a boolean beside it: the
     * three entry packets exist because these are three different requests, and the server
     * used to reconstruct the difference from a {@code restart} flag plus a "confirmed"
     * boolean it tracked across the save prompt. Every rule that depended on those two
     * booleans now reads this instead.
     */
    private enum LevelIntent {
        /** Load the saved run; never ask, the player already answered the prompt. */
        CONTINUE,
        /**
         * Start with the cards the request carries. A save on disk is a run the player closed
         * the game in the middle of, so it is loaded and asked about: the seed chooser is
         * submitted before anything knows the save is there.
         */
        PLAY,
        /**
         * Start with the cards the request carries and <em>do not</em> ask about a save - the
         * player has already turned it down (the save prompt's 重新开始, which goes through the
         * chooser to pick a new bar). This is the distinction the old {@code StartLevelC2S}
         * drew with its {@code restart} flag.
         */
        PLAY_OVER_SAVE,
        /** Discard the save and start over with the level's own cards. */
        RESTART
    }

    /** The editor's "test", the smoke hooks and {@code /level enter} all start a fresh run. */
    public void requestLevel(String levelId, String worldName, boolean restart) {
        createLevel(levelId, worldName, restart ? LevelIntent.RESTART : LevelIntent.CONTINUE, null);
    }

    private void createLevel(String levelId, String worldName, boolean restart) {
        createLevel(levelId, worldName, restart ? LevelIntent.RESTART : LevelIntent.CONTINUE, null);
    }

    /**
     * Enters a level from a test, through the same path the menu's own request takes.
     *
     * <p>Package-private rather than public because {@link LevelIntent} is private: the questions
     * "start fresh" and "continue the save" are the whole of what a caller outside this class may
     * say, and a test that starts a world and then resumes it is saying exactly those two things.
     * The alternative - driving {@code RestartLevelC2S} through a connection - needs a running
     * client, which is a much larger harness for the same coverage.
     */
    void createLevelForTest(String levelId, String worldName, boolean restart) {
        createLevel(levelId, worldName, restart);
    }

    /**
     * The single entry point for "put this level on the server".
     *
     * <p>The intent says whether the save on disk is a run to resume or a leftover to discard,
     * so no separate "the player already answered" flag is needed.
     *
     * @param requestedSeeds the client's card bar, or {@code null} to use the level's own
     *                       cards (a {@code CONTINUE} then takes the bar out of the save)
     */
    private void createLevel(String levelId, String worldName, LevelIntent intent,
                             List<Identifier> requestedSeeds) {
        createLevel(levelId, worldName, intent, requestedSeeds, null);
    }

    /**
     * @param requestedBuffs the buffs the client's chooser had switched on, or {@code null} for
     *                       every entry point that has no chooser behind it - in which case the
     *                       world's auto list decides (see {@code LevelBuffSelection.plan})
     */
    private void createLevel(String levelId, String worldName, LevelIntent intent,
                             List<Identifier> requestedSeeds, List<Identifier> requestedBuffs) {
        Identifier id = Identifier.parse(levelId);
        LevelDef def = BuiltInRegistries.LEVELS.get(id);
        if (def == null) {
            connection.send(new ServerMessageS2C("未找到关卡 " + id));
            return;
        }

        String safeWorld = WorldPaths.sanitize(worldName);
        // Leaving a world for another one has to write the first world's record
        // before the cache below replaces it.
        if (currentWorld != null && !safeWorld.equals(currentWorld)) {
            worlds.flushProfile();
        }
        // Refused before anything is torn down: a locked level must not close the run the
        // player is already in. The verdict is recomputed here rather than trusted from
        // the list, so a client that skips the menu cannot walk past the gate.
        LevelUnlocks.State unlock = LevelUnlocks.evaluate(id, def.unlock(),
                unlockContext(safeWorld, worlds.profileFor(safeWorld)));
        if (!unlock.unlocked()) {
            String label = def.displayName().isBlank() ? id.toString() : def.displayName();
            connection.send(new ServerMessageS2C("关卡 " + label + " 尚未解锁：" + unlock.reason()));
            return;
        }
        // Only "continue" accepts the instance that is already running. Everything else -
        // including a card bar submitted for the level that happens to be live - is the
        // player asking for a run from the start, and answering that with a resync is what
        // silently discarded their chosen cards.
        boolean fresh = intent != LevelIntent.CONTINUE;
        if (level != null && currentLevelId != null && currentLevelId.equals(id)
                && safeWorld.equals(currentWorld) && !fresh) {
            // Continuing the level that is already running: nothing to load, and re-sending
            // the state is what the client's resync path expects.
            level.sendFullState(bridge);
            connection.send(new GameSpeedS2C(tickRate.tickRate()));
            return;
        }
        Path worldDir = WorldPaths.worldDir(gameDir, safeWorld);
        Path saveDir = LevelKey.levelDir(worldDir, id);
        boolean hasSave = WorldStore.hasRunningSave(saveDir);
        // Resolved before the level is built: the backpack decides the card bar.
        PlayerProfile profile = worlds.profileFor(safeWorld);

        // Switching to a different level without asking for a fresh run keeps
        // the current level's progress under its own save directory.
        if (level != null && currentWorld != null && !fresh) {
            saveGame();
        }

        // A save is a run to resume unless the player has already said no to it: "continue"
        // and a seed selection from the level list load it (the second so the save can be
        // asked about), while the save prompt's 重新开始 and the pause menu's restart discard it.
        boolean loadSave = (intent == LevelIntent.CONTINUE || intent == LevelIntent.PLAY) && hasSave;
        if (!loadSave && hasSave) {
            WorldStore.deleteRunningSave(saveDir);
        }

        // A level whose card source deals its own cards - a conveyor belt - never takes a
        // seed selection: the level's own cards would be a second source for the same bar,
        // and its save holds the cards it was carrying, which the source restores itself.
        boolean selfDealt = com.pvzce.common.level.mechanic.LevelMechanics.dealsItsOwnCards(def);
        List<Identifier> seeds = SeedSelection.plan(def, profile, requestedSeeds, saveDir, loadSave,
                selfDealt);
        // Resolved here rather than in the level, because "continue a save" and "the player's
        // world-wide auto list" are both things a level instance cannot know. The list is then
        // remembered as the world's new auto list - see below.
        List<Identifier> buffs = LevelBuffSelection.planForRun(def, profile.buffSlots(),
                requestedBuffs, profile.autoBuffs(), saveDir, loadSave, profile::ownsBuff);

        LevelServer newLevel = new LevelServer(def, seeds,
                LevelServer.SeedContext.forProfile(def, profile), buffs, profile::ownsCard);
        // "The buffs I last went in with." Written from the resolved list, so a buff the level
        // refused never becomes a preference and a buff the level pinned joins it for the levels
        // that leave the choice open. Skipped while a save is being loaded: that run's buffs were
        // chosen in an earlier session and are not a fresh statement about what the player wants.
        if (!loadSave) {
            LevelBuffSelection.rememberAutoBuffs(profile, buffs);
            // Told to the client right away rather than left for the next menu: this list is what
            // the *next* chooser pre-selects, and a client that only learns it after the level
            // list happens to refresh would open the next buff page empty. Same reason every other
            // profile change sends the packet.
            connection.send(profilePacket(profile));
        }
        // Entering/restarting a level always starts at the normal 60tps speed.
        tickRate.setTickRate(PvzceTickRateManager.DEFAULT_TICK_RATE);

        CompoundTag saveTag = null;
        boolean loadedSave = false;
        if (loadSave) {
            try {
                saveTag = worlds.readLevelSave(saveDir);
                newLevel.restore(saveTag);
                loadedSave = true;
                LOGGER.info("Restored {} plants from {}", saveTag.getInt("PlantCount"), saveDir);
                connection.send(new ServerMessageS2C("已从存档继续 " + def.displayName()));
            } catch (Throwable t) {
                LOGGER.warn("Failed to read save; treating the level as not started.", t);
                WorldStore.deleteRunningSave(saveDir);
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

        if (loadedSave && saveTag != null) {
            // The saved world is already being rendered behind the dialog; keep
            // the simulation frozen until the client chooses continue/restart. A load only
            // ever happens for "continue", so the question always still needs asking.
            savePromptPending = true;
            // Remembered so a discard can be checked against the run the prompt was actually
            // about, rather than against whatever the client names.
            pendingSaveLevel = id;
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
     * Gives a freshly created world its profile.
     *
     * <p>The directory is made on the client (the world list reads the disk
     * directly) and this may arrive before or after that; either way the profile
     * write creates the directory too, so a world always ends up with a record.
     */
    private void createWorld(String worldName, boolean unlockAll) {
        String safeWorld = WorldPaths.sanitize(worldName);
        PlayerProfile created = unlockAll ? PlayerProfile.unlockEverything() : PlayerProfile.starter();
        worlds.saveProfile(safeWorld, created);
        connection.send(new ServerMessageS2C("世界 " + safeWorld + " 已创建"
                + (unlockAll ? "（全解锁）" : "（初始：豌豆射手 + 铲子）")));
        connection.send(profilePacket(created));
    }

    public void leaveLevel() {
        savePromptPending = false;
        manualPause = false;
        saveGame();
        // Coins and unlocks outlive the level they were earned in.
        worlds.flushProfile();
        if (level != null) {
            level.shutdown();
        }
        level = null;
        currentWorld = null;
        currentLevelId = null;
        levelFinishHandled = false;
        connection.send(new ServerMessageS2C("关卡已退出。"));
    }

    /**
     * Deletes the run left on disk for a level, on the player's word.
     *
     * <p>The other half of the save prompt's 重新开始: the chooser that opens next can be backed out
     * of, and the player who does so has still refused the run they were shown. Refuses anything
     * but the level they are actually being asked about, so a stale or hostile packet cannot delete
     * a different level's save.
     */
    private void discardSavedRun(com.pvzce.common.network.packet.DiscardLevelSaveC2S discard) {
        Identifier id = Identifier.tryParse(discard.levelId());
        if (id == null || discard.worldName() == null || discard.worldName().isBlank()) {
            return;
        }
        // Two states are legitimate: the player is looking at this level's save prompt right now,
        // or they have stepped out of the level to answer it (leaving clears the prompt, and the
        // answer has to survive that). Anything else - a level actually being played, or a level
        // that is not the one the prompt named - is refused, so a stale or hostile packet cannot
        // delete a running game's own file.
        boolean promptOpen = savePromptPending && id.equals(pendingSaveLevel);
        boolean steppedOut = level == null;
        if (!promptOpen && !steppedOut) {
            LOGGER.debug("Ignoring a discard for {}; the save prompt is about {}", id, pendingSaveLevel);
            return;
        }
        Path saveDir = LevelKey.levelDir(WorldPaths.worldDir(gameDir, discard.worldName()), id);
        WorldStore.deleteRunningSave(saveDir);
        pendingSaveLevel = null;
        LOGGER.info("Discarded the saved run for {}", id);
    }

    /**
     * The level whose save prompt is currently open, or {@code null}.
     *
     * <p>A save is only discarded on the player's word for the run they were actually shown: the
     * prompt names a level, and this is what makes {@link #discardSavedRun} check that name.
     */
    private Identifier pendingSaveLevel;

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
        Path saveDir = LevelKey.levelDir(WorldPaths.worldDir(gameDir, currentWorld), id);
        try {
            worlds.saveLevel(current.save(), saveDir);
            LOGGER.info("Saved {}", saveDir);
        } catch (Throwable t) {
            LOGGER.error("Failed to save {}", saveDir, t);
        }
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
        Path worldDir = WorldPaths.worldDir(gameDir, currentWorld);
        try {
            boolean plantWin = RewardSettlement.isPlantWin(current);
            boolean firstClear = plantWin && !worlds.isCompleted(currentWorld, id);
            if (plantWin) {
                worlds.writeCompletion(worldDir, id, current.winner(), current.tickCount(), current.plantCount());
            }
            WorldStore.deleteRunningSave(LevelKey.levelDir(worldDir, id));
            LOGGER.info("Level finished, cleared running save for {}", id);
            // Banks the run and pushes a fresh level list - after the running save is
            // gone, because that file IS the "进行中" label: refreshing first described
            // the level as still resumable, which is the opposite of what just happened.
            awardProfile(id, current, plantWin, firstClear);
        } catch (Throwable t) {
            LOGGER.error("Failed to finish level " + id, t);
        }
    }

    /**
     * Turns a finished run into profile progress and tells the client what it got.
     *
     * <p>The rules themselves live in {@link RewardSettlement}; this is the wiring around them -
     * which world's profile, saving it once, and the packets that describe the outcome.
     */
    private void awardProfile(Identifier id, LevelServer current, boolean plantWin, boolean firstClear) {
        PlayerProfile profile = worlds.profileFor(currentWorld);
        RewardSettlement.Payout payout =
                RewardSettlement.settle(current, profile, plantWin, firstClear);
        worlds.saveProfile(currentWorld, profile);
        // The award screen is presentation; the wallet is already written above, so
        // a client that never renders the screen still got its coins.
        connection.send(new LevelRewardS2C(id.toString(), payout.collected(), payout.bonus(),
                profile.coins(), payout.grants(),
                current.lastKillX(), current.lastKillY(), payout.mowers(), payout.mowerCoins()));
        connection.send(profilePacket(profile));
        // The finished level's row must stop saying "进行中" without a round trip. The
        // refresh names the world the level was in, not the last world a *list* was
        // asked for: those differ whenever a level was entered without going through
        // the list (the editor's test button), and the row belongs to the former.
        sendLevelList(currentWorld);
    }

    /** Parses a packet's card ids, skipping the ones the identifier rules reject. */
    private static List<Identifier> seedIds(List<String> raw) {
        List<Identifier> seeds = new ArrayList<>();
        for (String id : raw) {
            Identifier seed = Identifier.tryParse(id);
            if (seed != null) {
                seeds.add(seed);
            }
        }
        return seeds;
    }

    private final class ServerPacketListener implements PacketListener {
        @Override
        public void handle(PvzcePacket packet) {
            LevelServer current = level;
            if (packet instanceof ContinueLevelC2S request) {
                // "Load the run I have saved." When that run is already loaded and waiting on
                // the prompt, resuming is just an unpause - which is the whole point of
                // loading the save *before* asking about it.
                if (savePromptPending && current != null && currentWorld != null
                        && currentWorld.equals(WorldPaths.sanitize(request.worldName()))
                        && current.def().id().toString().equals(request.levelId())) {
                    savePromptPending = false;
                    return;
                }
                createLevel(request.levelId(), request.worldName(), LevelIntent.CONTINUE, null);
            } else if (packet instanceof RestartLevelC2S restart) {
                createLevel(restart.levelId(), restart.worldName(), LevelIntent.RESTART,
                        seedIds(restart.selectedSeeds()));
            } else if (packet instanceof PlayLevelC2S play) {
                // The seed chooser's 开始游戏. From the level list (restart=false) a save on
                // disk means the player closed the game mid-run: it is loaded and asked about
                // like any other entry with a save. From the save prompt's 重新开始
                // (restart=true) the player has already turned that save down.
                LevelIntent intent = play.restart() ? LevelIntent.PLAY_OVER_SAVE : LevelIntent.PLAY;
                createLevel(play.levelId(), play.worldName(), intent, seedIds(play.selectedSeeds()),
                        seedIds(play.selectedBuffs()));
            } else if (packet instanceof RequestLevelListC2S request) {
                lastRequestedWorld = request.worldName();
                sendLevelList(request.worldName());
            } else if (packet instanceof RequestProfileC2S request) {
                // The shop asks for this on its own: it is reachable without the level list, and
                // on a fresh session the client has no profile at all (see RequestProfileC2S).
                String world = WorldPaths.sanitize(request.worldName());
                if (!world.isBlank()) {
                    lastRequestedWorld = world;
                }
                connection.send(profilePacket(worlds.profileFor(menuWorld())));
            } else if (packet instanceof RequestSuggestionsC2S suggestions) {
                sendSuggestions(suggestions.input(), suggestions.requestId());
            } else if (packet instanceof SetGameSpeedC2S speed) {
                int speedIndex = Math.max(1, Math.min(3, speed.speedIndex()));
                tickRate.setTickRate(PvzceTickRateManager.DEFAULT_TICK_RATE * speedIndex);
                connection.send(new GameSpeedS2C(tickRate.tickRate()));
            } else if (packet instanceof com.pvzce.common.network.packet.ChatC2S chat) {
                say(chat.text());
            } else if (packet instanceof SetDifficultyC2S difficulty) {
                setDifficulty(difficulty.difficulty());
            } else if (packet instanceof PauseGameC2S pause) {
                manualPause = pause.paused();
            } else if (packet instanceof com.pvzce.common.network.packet.StartWavesC2S) {
                // A preparation phase's own button. Asked of the running level rather than of a
                // flag here: the level is the one that knows whether it was still preparing, so a
                // press that arrives after the first wave is a no-op instead of a second start.
                if (level != null) {
                    level.beginWaves();
                }
            } else if (packet instanceof com.pvzce.common.network.packet.FireAtC2S fire) {
                // A hand-aimed shot. The level re-derives everything: which entity that id is,
                // whether it is the sender's, whether it is loaded, and whether the cell exists.
                if (level != null) {
                    level.fireAt(bridge, fire.entityId(), fire.gridX(), fire.gridY());
                }
            } else if (packet instanceof CreateWorldC2S create) {
                createWorld(create.worldName(), create.unlockAll());
            } else if (packet instanceof com.pvzce.common.network.packet.UnlockLevelC2S unlockLevel) {
                buyLevel(unlockLevel.levelId(), unlockLevel.worldName());
            } else if (packet instanceof com.pvzce.common.network.packet.BuyShopItemC2S buy) {
                buyShopItem(buy.itemId(), buy.worldName());
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
            } else if (packet instanceof com.pvzce.common.network.packet.SmashContainerC2S smash) {
                // A bare click on a vase or one of the vase level's pots. The client decides
                // whether there is anything to hit (it draws the board, so it knows what is in
                // the cell) and the server decides whether it broke - the same split every other
                // click has.
                if (current != null) {
                    current.smashContainer(bridge, smash.gridX(), smash.gridY());
                }
            } else if (packet instanceof com.pvzce.common.network.packet.UseGrantedToolC2S granted) {
                // A tool the level hands over rather than a card the player holds. Which tool
                // that is comes from the level's own block, found by id - the client names the
                // tool it saw, and a client naming one the level does not grant is refused here.
                if (current != null) {
                    com.pvzce.api.content.ToolData data =
                            com.pvzce.common.level.mechanic.ToolMechanic.granted(current).stream()
                                    .filter(block -> block.tool() != null && block.tool().equals(granted.tool()))
                                    .findFirst()
                                    .orElse(null);
                    if (data != null) {
                        current.useGrantedTool(bridge, data, granted.gridX(), granted.gridY());
                    }
                }
            } else if (packet instanceof com.pvzce.common.network.packet.ReselectCardsC2S reselect) {
                // The endless round-clear chooser's answer. Answered only by the run it names:
                // a card choice for another level (or for a run that has already moved on) is
                // dropped rather than applied to whatever happens to be loaded.
                if (current != null && currentWorld != null
                        && current.def().id().toString().equals(reselect.levelId())
                        && currentWorld.equals(WorldPaths.sanitize(reselect.worldName()))) {
                    List<Identifier> cards = SeedSelection.sanitize(current.def(),
                            seedIds(reselect.selectedSeeds()), worlds.profileFor(currentWorld));
                    current.reselectCards(cards, bridge);
                }
            } else if (packet instanceof com.pvzce.common.network.packet.DiscardLevelSaveC2S discard) {
                discardSavedRun(discard);
            } else if (packet instanceof CollectResourceC2S collect) {
                if (current != null) {
                    current.collectResource(bridge, collect.entityId());
                }
            } else if (packet instanceof com.pvzce.common.network.packet.PickUpCardC2S pickUp) {
                // A click on a seed packet on the lawn. The client decides which packet is under
                // the cursor (it draws them) and the server decides whether it is still there and
                // whether the player's hand is free - the same split every other click has.
                if (current != null) {
                    current.pickUpCardDrop(bridge, pickUp.entityId());
                }
            } else if (packet instanceof com.pvzce.common.network.packet.PlantHeldCardC2S plantHeld) {
                if (current != null) {
                    current.plantHeldCard(bridge, plantHeld.gridX(), plantHeld.gridY());
                }
            } else if (packet instanceof com.pvzce.common.network.packet.ReleaseHeldCardC2S) {
                if (current != null) {
                    current.releaseHeldCard(bridge);
                }
            } else if (packet instanceof com.pvzce.common.network.packet.ReleaseMowerC2S mower) {
                if (current != null) {
                    current.releaseMower(mower.row());
                }
            } else if (packet instanceof ReloadPacksC2S) {
                // The player switched a pack off on the packs page. The list is the file both
                // sides read, so a reload here picks it up; the message reloadData sends is also
                // the client's cue to rebuild its own caches afterwards - server first, because
                // the registries and level definitions it pushes are what the client parses the
                // new resources against.
                reloadData(true);
                refreshLevelList();
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
