package com.pvzce.server.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.pvzce.api.util.Identifier;
import com.pvzce.api.tag.TagKey;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.PvzceRegistries;
import com.pvzce.common.network.packet.GameSpeedS2C;
import com.pvzce.common.network.packet.OpenEditorS2C;
import com.pvzce.common.network.packet.ResourceDeltaS2C;
import com.pvzce.common.network.packet.TeamSyncS2C;
import com.pvzce.common.tag.PvzceTags;
import com.pvzce.server.PvzceServer;
import com.pvzce.server.PvzceTickRateManager;
import com.pvzce.server.Team;
import com.pvzce.server.level.LevelServer;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.builder.RequiredArgumentBuilder.argument;

/** Brigadier command tree (MC-shaped) for PVZCE. */
public final class PvzceCommands {
    private PvzceCommands() {
    }

    /**
     * The command tree, for tests and for anything that only needs to parse.
     *
     * <p>A few subcommands ({@code /tick}, {@code /editor open}, {@code /reload}) act on a
     * running server. They are registered by {@link #create} and simply absent here.
     */
    public static CommandDispatcher<PvzceCommandSource> create() {
        return create(null);
    }

    public static CommandDispatcher<PvzceCommandSource> create(PvzceServer server) {
        CommandDispatcher<PvzceCommandSource> dispatcher = new CommandDispatcher<>();
        dispatcher.register(pvzce());
        dispatcher.register(level());
        dispatcher.register(gamerule());
        dispatcher.register(team());
        dispatcher.register(resource());
        dispatcher.register(spawn());
        dispatcher.register(lit("summon").executes(ctx -> spawn(ctx, "zombie", "pvzce:basic_zombie", 4, 0))
                .then(argKind("kind").then(argIdentifier("id", "any_entity").then(argInt("x", 0).then(argInt("y", 0))
                        .executes(ctx -> spawn(ctx, get(ctx, "kind"), ctx.getArgument("id", Identifier.class).toString(),
                                ctx.getArgument("x", Integer.class), ctx.getArgument("y", Integer.class)))))));
        if (server == null) {
            // Parse-only tree: the data commands are all here, the ones that need a
            // running server are not.
            return dispatcher;
        }
        dispatcher.register(profile(server));
        dispatcher.register(tick(server));
        dispatcher.register(time(server));
        dispatcher.register(lit("editor")
                .then(lit("open").then(argIdentifier("level", "level").executes(ctx -> {
                    server.sendPacket(new OpenEditorS2C(ctx.getArgument("level", Identifier.class).toString()));
                    ctx.getSource().sendFeedback("已打开编辑器: " + ctx.getArgument("level", Identifier.class));
                    return 1;
                }))));
        dispatcher.register(lit("reload").executes(ctx -> {
            server.reloadData(true);
            // Push the reloaded level list right away. The editor saves a level and then
            // wants to offer its seed chooser, which needs the new definition; without this
            // the client would have to guess how long the reload takes before asking.
            server.refreshLevelList();
            return 1;
        }));
        dispatcher.register(lit("save").executes(ctx -> {
            server.saveGame();
            ctx.getSource().sendFeedback("已保存世界。");
            return 1;
        }));
        dispatcher.register(lit("stop").executes(ctx -> {
            server.stop();
            return 1;
        }));
        dispatcher.register(lit("help").executes(ctx -> {
            ctx.getSource().sendFeedback("/pvzce registry list <category> · /pvzce tags list|get · /level load|stop|rules · /gamerule <id> [value]"
                    + " · /team list|join <team> · /resource give <team> <id> <amount>"
                    + " · /resource spawn <id> [x] [y] · /spawn <plant|zombie|projectile> <id> [x] [y]"
                    + " · /time query|set|add · /tick query|rate|reset|freeze|step|sprint|unfreeze"
                    + " · /profile info|slots <n>|buffslots <n>|buffs <ids>|buff <id>"
                    + "|unlock <level>|unlockall"
                    + " · /reload /save /stop /editor open <level>");
            return 1;
        }));
        return dispatcher;
    }

    /** Spawnable entity kinds; the three registries a {@code /spawn} can draw from. */
    private static RequiredArgumentBuilder<PvzceCommandSource, String> argKind(String name) {
        // Everything ``LevelServer.spawnEntity`` accepts. ``resource`` was missing, so
        // ``/spawn resource ...`` parsed as far as the literal and then failed with
        // "unknown command at position 5" - which reads like a typo in the command name
        // rather than an unsupported kind. The list is exactly the kind constants now that
        // the resource one is spelled "resource" instead of "sun".
        return argument(name, EnumArgumentType.of(
                com.pvzce.api.entity.EntityKind.PLANT,
                com.pvzce.api.entity.EntityKind.ZOMBIE,
                com.pvzce.api.entity.EntityKind.PROJECTILE,
                com.pvzce.api.entity.EntityKind.RESOURCE));
    }

    /**
     * Registry categories, derived from {@code PvzceRegistries.byCategory()} so the
     * command tree, the identifier suggestions and the tag loader all enumerate the
     * same registries. The three lists used to be typed by hand, spelled differently
     * and already inconsistent.
     */
    private static RequiredArgumentBuilder<PvzceCommandSource, String> argCategory(String name) {
        return argument(name, EnumArgumentType.of(registryCategories()));
    }

    private static RequiredArgumentBuilder<PvzceCommandSource, String> argTagRegistry(String name) {
        return argument(name, EnumArgumentType.of(registryCategories()));
    }

    /** The registry for a canonical category name, or {@code null}. */
    @SuppressWarnings("unchecked")
    private static com.pvzce.api.registry.Registry<?> registryFor(String canonical) {
        var key = PvzceRegistries.byCategory().get(canonical);
        if (key == null) {
            return null;
        }
        return BuiltInRegistries.ACCESS.get(
                (com.pvzce.api.registry.ResourceKey<com.pvzce.api.registry.Registry<Object>>) (Object) key);
    }

    private static String[] registryCategories() {
        return com.pvzce.common.core.PvzceRegistries.byCategory().keySet().toArray(String[]::new);
    }

    private static RequiredArgumentBuilder<PvzceCommandSource, Identifier> argTag(String name) {
        return argument(name, IdentifierArgumentType.forCategory("tag"));
    }

    private static RequiredArgumentBuilder<PvzceCommandSource, Integer> argInt(String name, int min) {
        return argument(name, integer(min));
    }

    /** An integer with both bounds; Brigadier rejects out-of-range input before the executor runs. */
    private static RequiredArgumentBuilder<PvzceCommandSource, Integer> argInteger(String name, int min, int max) {
        return argument(name, integer(min, max));
    }

    private static RequiredArgumentBuilder<PvzceCommandSource, Integer> argTime(String name, int min) {
        return argument(name, TimeArgumentType.time(min));
    }

    private static RequiredArgumentBuilder<PvzceCommandSource, Float> argFloat(String name, float min, float max) {
        return argument(name, FloatArgumentType.floatArg(min, max));
    }

    private static RequiredArgumentBuilder<PvzceCommandSource, Identifier> argIdentifier(String name, String category) {
        return argument(name, IdentifierArgumentType.forCategory(category));
    }

    private static RequiredArgumentBuilder<PvzceCommandSource, String> argString(String name) {
        return argument(name, StringArgumentType.greedyString());
    }

    /**
     * Parses a whitespace-separated list of ids, keeping only the parts that parse.
     *
     * <p>Used by {@code /profile buffs <list>}, where a greedy string is the only way to take an
     * arbitrary number of ids in one command. An unparsable word is dropped rather than failing
     * the command: the answer the operator gets back is the list that was actually stored.
     */
    private static List<Identifier> buffIds(String raw) {
        List<Identifier> ids = new java.util.ArrayList<>();
        for (String part : raw == null ? new String[0] : raw.trim().split("\\s+")) {
            Identifier id = Identifier.tryParse(part);
            if (id != null) {
                ids.add(id);
            }
        }
        return List.copyOf(ids);
    }

    private static LiteralArgumentBuilder<PvzceCommandSource> lit(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static LiteralArgumentBuilder<PvzceCommandSource> pvzce() {
        return lit("pvzce")
                .then(lit("registry")
                        .then(lit("list")
                                .then(argCategory("category").executes(ctx -> {
                                    String canonical = PvzceRegistries.canonicalCategory(get(ctx, "category"));
                                    var registry = registryFor(canonical);
                                    if (registry == null) {
                                        ctx.getSource().sendFeedback("未知分类: " + canonical);
                                        return 0;
                                    }
                                    ctx.getSource().sendFeedback(registry.keySet().toString());
                                    return 1;
                                }))))
                .then(lit("tags")
                        .then(lit("list")
                                .then(argTagRegistry("registry").executes(ctx -> {
                                    String registryName = PvzceRegistries.canonicalCategory(get(ctx, "registry"));
                                    List<TagKey<?>> keys = PvzceTags.keys().stream()
                                            .filter(key -> key.registry().location().path().equals(registryName))
                                            .sorted(Comparator.comparing(key -> key.id().toString()))
                                            .toList();
                                    if (keys.isEmpty()) {
                                        ctx.getSource().sendFeedback("该注册表没有已加载的标签。");
                                        return 0;
                                    }
                                    for (TagKey<?> key : keys) {
                                        ctx.getSource().sendFeedback("#" + key.id() + " -> " + PvzceTags.ids(key));
                                    }
                                    return keys.size();
                                })))
                        .then(lit("get")
                                .then(argTag("tag").executes(ctx -> {
                                    Identifier id = ctx.getArgument("tag", Identifier.class);
                                    List<TagKey<?>> matches = PvzceTags.keys().stream()
                                            .filter(key -> key.id().equals(id))
                                            .toList();
                                    if (matches.isEmpty()) {
                                        ctx.getSource().sendFeedback("未知标签 #" + id);
                                        return 0;
                                    }
                                    for (TagKey<?> key : matches) {
                                        ctx.getSource().sendFeedback("#" + key.id()
                                                + " (" + key.registry().location() + ") -> " + PvzceTags.ids(key));
                                    }
                                    return matches.size();
                                }))));
    }

    private static LiteralArgumentBuilder<PvzceCommandSource> time(PvzceServer server) {
        return lit("time")
                .then(lit("query")
                        .then(lit("daytime").executes(ctx -> queryTime(ctx, "daytime")))
                        .then(lit("gametime").executes(ctx -> queryTime(ctx, "gametime")))
                        .then(lit("day").executes(ctx -> queryTime(ctx, "day"))))
                .then(lit("set")
                        .then(lit("day").executes(ctx -> setTime(ctx, 0)))
                        .then(lit("noon").executes(ctx -> setTime(ctx, dayLength(ctx) / 2)))
                        .then(lit("night").executes(ctx -> setTime(ctx, dayLength(ctx))))
                        .then(lit("midnight").executes(ctx -> setTime(ctx, dayLength(ctx) + nightLength(ctx) / 2)))
                        .then(argTime("time", 0).executes(ctx -> setTime(ctx, ctx.getArgument("time", Integer.class)))))
                .then(lit("add").then(argTime("time", Integer.MIN_VALUE)
                        .executes(ctx -> addTime(ctx, ctx.getArgument("time", Integer.class)))));
    }

    /**
     * The wallet, the backpack's card slots, and which levels are open.
     *
     * <p>{@code /profile unlock <level>} exists because progression is otherwise only
     * reachable by playing: a smoke run, a test level behind a chain, or a player who
     * wants to show someone level 1-4 without clearing 1-3 first all need a way in.
     * It records the level as <em>bought</em>, so the effect is exactly the one a coin
     * purchase has and it survives a reload.
     *
     * <p>{@code /profile slots <n>} is the operator half of the card-slot count the
     * backpack owns: the shop that will raise it is not built, so this is how a level that
     * declares no {@code max_seed_slots} gets a bigger bar today.
     */
    private static LiteralArgumentBuilder<PvzceCommandSource> profile(PvzceServer server) {
        return lit("profile")
                .then(lit("info").executes(ctx -> {
                    PvzceServer.ProfileSnapshot snapshot = server.profileSnapshot();
                    ctx.getSource().sendFeedback("世界 " + snapshot.world()
                            + "：金币 " + snapshot.coins()
                            + "，已解锁卡 " + snapshot.cards() + " 张"
                            + "，已购买关卡 " + snapshot.levels() + " 个"
                            + "，卡槽 " + snapshot.seedSlots()
                            + "，增益槽 " + snapshot.buffSlots()
                            + "，自动增益 " + snapshot.autoBuffs() + " 个"
                            + (snapshot.sandbox() ? "（沙盒：全部解锁）" : ""));
                    return 1;
                }))
                .then(lit("slots").then(argInteger("slots", 1,
                        com.pvzce.common.PvzceConstants.MAX_SEED_SLOTS).executes(ctx -> {
                    ctx.getSource().sendFeedback(server.grantSeedSlots(ctx.getArgument("slots", Integer.class)));
                    return 1;
                })))
                .then(lit("buffslots").then(argInteger("slots", 1,
                        com.pvzce.common.PvzceConstants.MAX_BUFF_SLOTS).executes(ctx -> {
                    ctx.getSource().sendFeedback(server.grantBuffSlots(ctx.getArgument("slots", Integer.class)));
                    return 1;
                })))
                // With no argument this clears the list, which is the only way to say "stop
                // picking anything for me" without going through a level's chooser.
                .then(lit("buffs").executes(ctx -> {
                    ctx.getSource().sendFeedback(server.setAutoBuffs(List.of()));
                    return 1;
                }).then(argString("list").executes(ctx -> {
                    // A greedy string, so a whole list is written in one command:
                    // `/profile buffs pvzce:auto_collect pvzce:mushroom_range`.
                    ctx.getSource().sendFeedback(server.setAutoBuffs(
                            buffIds(ctx.getArgument("list", String.class))));
                    return 1;
                })))
                .then(lit("buff").then(argIdentifier("buff", "level_buff").executes(ctx -> {
                    ctx.getSource().sendFeedback(
                            server.grantBuff(ctx.getArgument("buff", Identifier.class)));
                    return 1;
                })))
                .then(lit("unlock").then(argIdentifier("level", "level").executes(ctx -> {
                    Identifier id = ctx.getArgument("level", Identifier.class);
                    String result = server.grantLevelUnlock(id);
                    ctx.getSource().sendFeedback(result);
                    return 1;
                })))
                .then(lit("unlockall").executes(ctx -> {
                    ctx.getSource().sendFeedback(server.grantEverything());
                    return 1;
                }));
    }

    private static LiteralArgumentBuilder<PvzceCommandSource> tick(PvzceServer server) {
        var manager = server.tickRateManager();
        return lit("tick")
                .executes(ctx -> {
                    LevelServer level = server.level();
                    String status = manager.isFrozen() ? "（已冻结）"
                            : manager.isSprinting() ? "（冲刺中）" : "";
                    ctx.getSource().sendFeedback("游戏 tick=" + (level != null ? level.tickCount() : 0)
                            + "，目标速率 " + String.format(Locale.ROOT, "%.1f", manager.tickRate()) + " tps" + status);
                    return 1;
                })
                .then(lit("query").executes(ctx -> {
                    String status = manager.isFrozen() ? "已冻结"
                            : manager.isSprinting() ? "冲刺中"
                            : manager.isStepping() ? "单步执行中"
                            : (manager.tickTimeSampleCount() > 0
                                    && manager.averageTickTimeNanos() > manager.nanosPerTick())
                                    ? "运行中（略落后）" : "运行中";
                    ctx.getSource().sendFeedback("服务器状态: " + status);
                    ctx.getSource().sendFeedback("目标速率 " + String.format(Locale.ROOT, "%.1f", manager.tickRate())
                            + " tps（目标每 tick " + nanosToMillis(manager.nanosPerTick())
                            + " ms），游戏 tick=" + (server.level() != null ? server.level().tickCount() : 0));
                    if (manager.tickTimeSampleCount() > 0) {
                        ctx.getSource().sendFeedback("最近 " + manager.tickTimeSampleCount()
                                + " 个 tick 耗时：平均 " + nanosToMillis(manager.averageTickTimeNanos())
                                + " ms，p50 " + nanosToMillis(manager.percentileTickTimeNanos(0.50))
                                + " ms，p95 " + nanosToMillis(manager.percentileTickTimeNanos(0.95))
                                + " ms，p99 " + nanosToMillis(manager.percentileTickTimeNanos(0.99)) + " ms");
                    }
                    return (int) manager.tickRate();
                }))
                .then(lit("rate")
                        .then(argFloat("rate", PvzceTickRateManager.MIN_TICK_RATE, PvzceTickRateManager.MAX_TICK_RATE)
                                .suggests((ctx, builder) -> builder
                                        .suggest(String.valueOf(PvzceTickRateManager.DEFAULT_TICK_RATE))
                                        .buildFuture())
                                .executes(ctx -> {
                                    float rate = FloatArgumentType.getFloat(ctx, "rate");
                                    manager.setTickRate(rate);
                                    server.sendPacket(new GameSpeedS2C(manager.tickRate()));
                                    ctx.getSource().sendFeedback("目标速率已设置为 "
                                            + String.format(Locale.ROOT, "%.1f", rate) + " tps。");
                                    return (int) rate;
                                })))
                .then(lit("reset").executes(ctx -> {
                    manager.resetTickRate();
                    server.sendPacket(new GameSpeedS2C(manager.tickRate()));
                    ctx.getSource().sendFeedback("tick 速率已重置为默认 "
                            + String.format(Locale.ROOT, "%.1f", PvzceTickRateManager.DEFAULT_TICK_RATE)
                            + " tps，并已解冻、停止冲刺/单步。");
                    return (int) manager.tickRate();
                }))
                .then(lit("freeze").executes(ctx -> {
                    manager.setFrozen(true);
                    ctx.getSource().sendFeedback("服务器已冻结。");
                    return 1;
                }))
                .then(lit("unfreeze").executes(ctx -> {
                    manager.setFrozen(false);
                    ctx.getSource().sendFeedback("服务器已恢复运行。");
                    return 1;
                }))
                .then(lit("step")
                        .executes(ctx -> step(ctx, 1))
                        .then(lit("stop").executes(ctx -> {
                            if (manager.stopStepping()) {
                                ctx.getSource().sendFeedback("已停止单步执行。");
                                return 1;
                            }
                            ctx.getSource().sendFeedback("当前没有进行中的单步执行。");
                            return 0;
                        }))
                        .then(argTime("time", 1).executes(ctx -> step(ctx, ctx.getArgument("time", Integer.class)))))
                .then(lit("sprint")
                        .then(lit("stop").executes(ctx -> {
                            if (manager.isSprinting()) {
                                manager.stopSprinting();
                                ctx.getSource().sendFeedback("已停止冲刺。");
                                return 1;
                            }
                            ctx.getSource().sendFeedback("当前没有进行中的冲刺。");
                            return 0;
                        }))
                        .then(argTime("time", 1).executes(ctx -> {
                            int ticks = ctx.getArgument("time", Integer.class);
                            manager.requestSprint(ticks);
                            ctx.getSource().sendFeedback("已开始冲刺 " + ticks + " tick。");
                            return 1;
                        })));
    }

    private static String nanosToMillis(long nanos) {
        return String.format(Locale.ROOT, "%.2f", nanos / 1_000_000F);
    }

    private static int queryTime(CommandContext<PvzceCommandSource> ctx, String query) {
        LevelServer level = ctx.getSource().server().level();
        if (level == null) {
            ctx.getSource().sendFeedback("没有进行中的关卡。");
            return 0;
        }
        long dayTicks = level.clock().dayTicks();
        int dayLength = dayLength(ctx);
        int nightLength = nightLength(ctx);
        long value = switch (query) {
            case "daytime" -> dayLength > 0 ? dayTicks % dayLength : dayTicks;
            case "gametime" -> dayTicks;
            case "day" -> com.pvzce.common.level.DayNightCycle.dayCount(dayTicks, dayLength, Math.max(0, nightLength));
            default -> dayTicks;
        };
        ctx.getSource().sendFeedback("查询时间 " + query + " 为 " + value);
        return 1;
    }

    private static int setTime(CommandContext<PvzceCommandSource> ctx, long ticks) {
        LevelServer level = ctx.getSource().server().level();
        if (level == null) {
            ctx.getSource().sendFeedback("没有进行中的关卡。");
            return 0;
        }
        level.setDayTicks(ticks);
        ctx.getSource().server().sendPacket(level.timeOfDayPacket());
        ctx.getSource().sendFeedback("已将时间设置为 " + level.clock().dayTicks());
        return 1;
    }

    private static int addTime(CommandContext<PvzceCommandSource> ctx, int ticks) {
        LevelServer level = ctx.getSource().server().level();
        if (level == null) {
            ctx.getSource().sendFeedback("没有进行中的关卡。");
            return 0;
        }
        level.addDayTicks(ticks);
        ctx.getSource().server().sendPacket(level.timeOfDayPacket());
        ctx.getSource().sendFeedback("已将时间调整为 " + level.clock().dayTicks());
        return 1;
    }

    private static int step(CommandContext<PvzceCommandSource> ctx, int ticks) {
        if (ctx.getSource().server().tickRateManager().stepGameIfPaused(ticks)) {
            ctx.getSource().sendFeedback("已安排单步执行 " + ticks + " tick。");
            return 1;
        }
        ctx.getSource().sendFeedback("只有冻结中的服务器可以单步执行。");
        return 0;
    }

    private static int dayLength(CommandContext<PvzceCommandSource> ctx) {
        LevelServer level = ctx.getSource().server().level();
        return level == null ? 0 : level.clock().dayLength(level.rules());
    }

    private static int nightLength(CommandContext<PvzceCommandSource> ctx) {
        LevelServer level = ctx.getSource().server().level();
        return level == null ? -1 : level.clock().nightLength(level.rules());
    }

    private static LiteralArgumentBuilder<PvzceCommandSource> level() {
        return lit("level")
                .then(lit("load").then(argIdentifier("id", "level").executes(ctx -> {
                    PvzceServer server = ctx.getSource().server();
                    Identifier id = ctx.getArgument("id", Identifier.class);
                    server.requestLevel(id.toString(), server.currentWorld() == null ? "world" : server.currentWorld(), false);
                    ctx.getSource().sendFeedback("正在载入关卡 " + id);
                    return 1;
                })))
                .then(lit("stop").executes(ctx -> {
                    ctx.getSource().server().leaveLevel();
                    return 1;
                }))
                .then(lit("rules").executes(ctx -> {
                    LevelServer level = ctx.getSource().server().level();
                    if (level != null) {
                        ctx.getSource().sendFeedback(level.rules().toJson().toString());
                    }
                    return 1;
                }));
    }

    private static LiteralArgumentBuilder<PvzceCommandSource> gamerule() {
        return lit("gamerule")
                .then(argIdentifier("rule", "game_rule")
                        .executes(ctx -> {
                            LevelServer level = ctx.getSource().server().level();
                            if (level != null) {
                                JsonElement value = level.rules().toJson().get(ctx.getArgument("rule", Identifier.class));
                                ctx.getSource().sendFeedback(value == null ? "未知规则" : value.toString());
                            }
                            return 1;
                        })
                        .then(argString("value").executes(ctx -> {
                            LevelServer level = ctx.getSource().server().level();
                            if (level != null) {
                                level.rules().setFromJson(ctx.getArgument("rule", Identifier.class),
                                        JsonParser.parseString(get(ctx, "value")));
                                ctx.getSource().sendFeedback("规则已更新: " + ctx.getArgument("rule", Identifier.class));
                            }
                            return 1;
                        })));
    }

    private static LiteralArgumentBuilder<PvzceCommandSource> team() {
        return lit("team")
                .then(lit("list").executes(ctx -> {
                    LevelServer level = ctx.getSource().server().level();
                    if (level != null) {
                        level.def().teams().forEach(t -> ctx.getSource().sendFeedback(
                                t.id() + " - " + t.name() + (t.id().equals(level.humanTeamId()) ? " (当前)" : "")));
                    }
                    return 1;
                }))
                .then(lit("join").then(argIdentifier("team", "team").executes(ctx -> {
                    LevelServer level = ctx.getSource().server().level();
                    Identifier id = ctx.getArgument("team", Identifier.class);
                    Team team = id == null || level == null ? null : level.team(id);
                    if (team == null) {
                        ctx.getSource().sendFeedback("未知队伍 " + id);
                        return 0;
                    }
                    level.setHumanTeam(id);
                    // The client must learn about the switch immediately: without this
                    // packet the server rejected every placement while the HUD still
                    // claimed the player was on the plant team.
                    ctx.getSource().server().sendPacket(new TeamSyncS2C(id.toString(), team.name()));
                    ctx.getSource().server().sendMessage("已加入 " + team.name() + "。");
                    return 1;
                })));
    }

    private static LiteralArgumentBuilder<PvzceCommandSource> resource() {
        return lit("resource")
                .then(resourceSpawn())
                .then(lit("give")
                        .then(argIdentifier("team", "team")
                                .then(argIdentifier("resource", "resource")
                                        .then(argInt("amount", 1).executes(ctx -> {
                                            LevelServer level = ctx.getSource().server().level();
                                            Team team = level == null ? null : level.team(ctx.getArgument("team", Identifier.class));
                                            if (team == null) {
                                                ctx.getSource().sendFeedback("未知队伍");
                                                return 0;
                                            }
                                            Identifier resource = ctx.getArgument("resource", Identifier.class);
                                            if (BuiltInRegistries.RESOURCES.get(resource) == null) {
                                                ctx.getSource().sendFeedback("未知资源 " + resource);
                                                return 0;
                                            }
                                            team.addResource(resource, ctx.getArgument("amount", Integer.class));
                                            ctx.getSource().server().sendPacket(new ResourceDeltaS2C(
                                                    team.id().toString(), resource.toString(), team.resourcesOf(resource)));
                                            ctx.getSource().sendFeedback("已给予资源。");
                                            return 1;
                                        })))));
    }

    /**
     * {@code /resource spawn <id> [x] [y]}: puts a drop on the board.
     *
     * <p>The sibling of {@code /resource give}, which adds to the bank. Drops are the
     * things with physics - a sun falls, a coin is simply there - so being able to place
     * one is what makes their motion checkable without waiting for a sunflower or a kill.
     */
    private static LiteralArgumentBuilder<PvzceCommandSource> resourceSpawn() {
        return lit("spawn")
                .then(argIdentifier("resource", "any_resource")
                        .then(argInt("x", 0)
                                .then(argInt("y", 0).executes(ctx -> {
                                    LevelServer level = ctx.getSource().server().level();
                                    if (level == null) {
                                        ctx.getSource().sendFeedback("没有进行中的关卡。");
                                        return 0;
                                    }
                                    Identifier resource = ctx.getArgument("resource", Identifier.class);
                                    Team team = level.team(PvzceIds.PLANT_TEAM);
                                    var def = BuiltInRegistries.RESOURCES.get(resource);
                                    if (def == null || team == null) {
                                        ctx.getSource().sendFeedback("未知资源 " + resource);
                                        return 0;
                                    }
                                    // One unit at the value the resource declares, so /summon
                                    // agrees with what the same resource is worth when it drops.
                                    level.spawnResource(resource, def.defaultValue(),
                                            ctx.getArgument("x", Integer.class),
                                            ctx.getArgument("y", Integer.class), team);
                                    ctx.getSource().sendFeedback("已生成掉落物 " + resource);
                                    return 1;
                                }))));
    }

    private static LiteralArgumentBuilder<PvzceCommandSource> spawn() {
        return lit("spawn")
                .then(argKind("kind")
                        .then(argIdentifier("id", "any_entity")
                                .then(argInt("x", 0)
                                        .then(argInt("y", 0).executes(ctx -> spawn(ctx,
                                                get(ctx, "kind"), ctx.getArgument("id", Identifier.class).toString(),
                                                ctx.getArgument("x", Integer.class),
                                                ctx.getArgument("y", Integer.class)))))));
    }

    private static int spawn(CommandContext<PvzceCommandSource> ctx, String kind, String id, int x, int y) {
        LevelServer level = ctx.getSource().server().level();
        if (level == null) {
            ctx.getSource().sendFeedback("没有进行中的关卡。");
            return 0;
        }
        boolean ok = level.spawnEntity(kind.toLowerCase(Locale.ROOT), Identifier.parse(id), x, y);
        ctx.getSource().sendFeedback(ok ? "已生成 " + id : "生成失败（未知类型/ID 或坐标越界）");
        return ok ? 1 : 0;
    }

    private static String get(CommandContext<PvzceCommandSource> ctx, String name) {
        return ctx.getArgument(name, String.class);
    }
}
