package com.pvzce.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.network.*;
import com.pvzce.common.network.packet.*;
import com.pvzce.server.level.LevelServer;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;

/** One room in the integrated authoritative server; all seat changes run on its tick thread. */
final class MultiplayerRoom implements AutoCloseable {
    private final PvzceServer server;
    private final Connection host;
    private final Map<String, Seat> seats = new LinkedHashMap<>();
    private final List<Pending> pending = new ArrayList<>();
    private final java.util.concurrent.Semaphore handshakes = new java.util.concurrent.Semaphore(PvzceConstants.MULTIPLAYER_MAX_PLAYERS * 2);
    private final ArrayBlockingQueue<Connection> accepted = new ArrayBlockingQueue<>(PvzceConstants.MULTIPLAYER_MAX_PLAYERS);
    private volatile ServerSocket listener;
    private LanDiscovery.Advertiser lan;
    private Seat actor;
    private boolean versus;
    private boolean started;
    private boolean hostReady;
    private String content;
    private SessionPacks.Host packs;
    private boolean requireAllPacks;
    private String world;
    private String message = "";

    private static final class Seat {
        final String id = UUID.randomUUID().toString();
        final String token = UUID.randomUUID().toString();
        String name;
        Connection connection;
        Identifier team;
        boolean ready;
        boolean admitted;
        boolean entered;
        Set<String> packsSelected;
        boolean snapshotSent;
        long deadline;
        List<Identifier> cards = List.of();
    }
    private record Pending(Connection connection, long deadline) { }

    MultiplayerRoom(PvzceServer server, Connection host) { this.server = server; this.host = host; }
    boolean open() { return listener != null; }
    boolean waiting() { return open() && !started; }
    boolean versus() { return versus; }
    /** A versus room lists versus maps and a coop room the rest; alone, a map two people must play is not offered. */
    boolean acceptsLevel(com.pvzce.api.content.LevelDef def) {
        Optional<com.pvzce.api.content.VersusData> match =
                com.pvzce.common.level.mechanic.LevelMechanics.versusData(def);
        return open() ? versus == match.isPresent() : match.map(data -> !data.needsTwoPlayers()).orElse(true);
    }
    Connection replyConnection() { return actor == null ? host : actor.connection; }

    boolean handleHost(PvzcePacket packet) {
        if (packet instanceof RoomC2S room) {
            try {
                switch (room.action()) {
                    case "open" -> open(Integer.parseInt(room.value()), room.token().startsWith("versus"), room.token().endsWith("_all"));
                    case "close" -> { close(); server.leaveLevel(); }
                    case "resume" -> server.suspendLocal(false);
                    case "suspend" -> {
                        close(); server.leaveLevel(); server.suspendLocal(true);
                        host.send(new RoomS2C("suspended", "", "", "", true, List.of(), "", false, "", false, false));
                    }
                    case "start" -> start();
                    default -> host.send(new ServerMessageS2C("未知房间操作"));
                }
            } catch (IOException | IllegalArgumentException e) {
                host.send(new ServerMessageS2C("房间操作失败：" + e.getMessage()));
            }
            return true;
        }
        if (!open()) return false;
        if (packet instanceof PlayLevelC2S play) {
            selectLevel(play); return true;
        }
        if (packet instanceof ContinueLevelC2S play) {
            selectLevel(new PlayLevelC2S(play.levelId(), play.worldName(), true, List.of())); return true;
        }
        if (packet instanceof RestartLevelC2S play) {
            selectLevel(new PlayLevelC2S(play.levelId(), play.worldName(), true,
                    play.selectedSeeds(), List.of(), play.humanTeam())); return true;
        }
        if (packet instanceof LeaveLevelC2S) {
            started = false; hostReady = false;
            server.leaveLevel();
            seats.values().forEach(s -> { s.ready = false; s.entered = false; });
            publish(); return true;
        }
        if (packet instanceof ReloadPacksC2S || packet instanceof com.pvzce.common.network.packet.WorkshopSessionC2S
                || packet instanceof CommandC2S command && command.command().matches("/?(reload|level|world).*")) {
            host.send(new ServerMessageS2C("请先关闭联机房间，再重载内容或切换编辑环境。")); return true;
        }
        return false;
    }

    private void open(int port, boolean versus, boolean requireAll) throws IOException {
        if (open()) throw new IllegalArgumentException("已经创建房间");
        if (port < 0 || port > 65535) throw new IllegalArgumentException("端口应为 1～65535");
        packs = new SessionPacks.Host(server.resourceManager());
        requireAllPacks = requireAll;
        content = ContentSnapshot.capture();
        this.versus = versus;
        this.world = server.currentWorld() == null ? server.worlds().cachedWorld() : server.currentWorld();
        ServerSocket socket = new ServerSocket(port);
        listener = socket;
        started = false;
        hostReady = false;
        message = "端口 " + socket.getLocalPort() + " · " + (versus ? "植物对僵尸 1v1" : "合作闯关 2～4 人");
        Thread acceptor = new Thread(() -> {
            while (!socket.isClosed()) {
                try {
                    Socket remote = socket.accept();
                    if (!handshakes.tryAcquire()) { remote.close(); continue; }
                    try {
                        Connection connection = TcpPacketTransport.connection(remote, ConnectionDirection.SERVERBOUND);
                        if (!accepted.offer(connection)) { connection.disconnect("房间连接繁忙"); handshakes.release(); }
                    } catch (IOException e) { handshakes.release(); remote.close(); throw e; }
                } catch (IOException e) {
                    if (!socket.isClosed()) host.send(new ServerMessageS2C("监听连接失败：" + e.getMessage()));
                }
            }
        }, "PvzceRoomAccept");
        acceptor.setDaemon(true); acceptor.start();
        publish();
    }

    int port() { return listener == null ? -1 : listener.getLocalPort(); }

    void tick() {
        Connection connection;
        while ((connection = accepted.poll()) != null) {
            if (!open()) { connection.close(); handshakes.release(); continue; }
            Connection incoming = connection;
            pending.add(new Pending(incoming, System.nanoTime() + PvzceConstants.NETWORK_PACK_NEGOTIATION_MILLIS * 1_000_000L));
            incoming.setListener(packet -> receive(incoming, packet));
        }
        for (Pending entry : List.copyOf(pending)) {
            entry.connection().tick();
            if (!entry.connection().isConnected() || System.nanoTime() > entry.deadline()) {
                entry.connection().disconnect("联机握手超时");
                if (pending.remove(entry)) handshakes.release();
            }
        }
        for (Seat seat : List.copyOf(seats.values())) {
            if (seat.connection != null) {
                seat.connection.tick();
                if (!seat.connection.isConnected()) {
                    if (!seat.admitted) { remove(seat); continue; }
                    seat.connection = null;
                    seat.deadline = System.nanoTime() + PvzceConstants.MULTIPLAYER_RECONNECT_TICKS
                            * 1_000_000_000L / PvzceConstants.TICKS_PER_SECOND;
                    publish();
                }
            } else if (System.nanoTime() > seat.deadline) remove(seat);
        }
    }

    private void receive(Connection connection, PvzcePacket packet) {
        Seat seat = seats.values().stream().filter(s -> s.connection == connection).findFirst().orElse(null);
        if (seat == null) {
            if (!(packet instanceof RoomC2S hello) || !hello.action().equals("hello")) {
                connection.disconnect("请先完成联机握手"); return;
            }
            join(connection, hello); return;
        }
        if (packet instanceof RoomC2S control) {
            switch (control.action()) {
                case "packs_select" -> {
                    if (seat.admitted || seat.packsSelected != null) throw new IllegalArgumentException("数据包选择重复");
                    List<String> choice = new ArrayList<>();
                    for (var hash : JsonParser.parseString(control.value()).getAsJsonArray()) choice.add(hash.getAsString());
                    seat.packsSelected = packs.validateSelection(choice, requireAllPacks);
                }
                case "pack_get" -> {
                    if (seat.snapshotSent || seat.packsSelected == null || !seat.packsSelected.contains(control.value()))
                        throw new IllegalArgumentException("未选择该数据包");
                    connection.send(packs.chunk(control.value(), Integer.parseInt(control.token())));
                }
                case "packs_ready" -> {
                    if (seat.packsSelected == null || seat.snapshotSent) throw new IllegalArgumentException("数据包确认无效");
                    seat.snapshotSent = true;
                    ContentSnapshot.send(content, connection::send);
                }
                case "content_ready" -> {
                    if (!seat.snapshotSent || seat.admitted || !ContentSnapshot.digest(content).equals(control.value())) { connection.disconnect("内容确认无效"); return; }
                    seat.admitted = true;
                    if (pending.removeIf(p -> p.connection() == connection)) handshakes.release();
                    withActor(seat, () -> server.sendRoomLevelList(world));
                    if (seat.entered && started) snapshot(seat);
                    publish();
                }
                case "leave" -> remove(seat);
                case "ready" -> {
                    if (versus && seat.admitted && hostReady)
                        prepare(seat, new PlayLevelC2S(server.level().def().id().toString(), world, true, List.of()));
                    else connection.send(new ServerMessageS2C("请等待房主选择对战关卡"));
                }
                default -> connection.send(new ServerMessageS2C("此操作仅房主可用"));
            }
            return;
        }
        if (!seat.admitted) { connection.disconnect("内容同步尚未完成"); return; }
        if (packet instanceof PlayLevelC2S play) {
            prepare(seat, play); return;
        }
        if (packet instanceof ReselectCardsC2S reselect && started && seat.entered
                && server.level().def().id().toString().equals(reselect.levelId())) {
            if (versus) { connection.send(new ServerMessageS2C("对战使用地图规定的卡组")); return; }
            var cards = SeedSelection.sanitize(server.level().def(), reselect.selectedSeeds().stream()
                    .map(Identifier::tryParse).filter(Objects::nonNull).toList(), server.worlds().profileFor(world));
            withActor(seat, () -> server.level().asParticipant(seat.id, server.roomBridge(),
                    () -> server.level().reselectCards(cards, server.roomBridge(), false)));
            return;
        }
        if (packet instanceof RequestLevelListC2S || packet instanceof RequestProfileC2S) {
            withActor(seat, () -> server.sendRoomLevelList(world)); return;
        }
        if (packet instanceof ChatC2S chat) {
            broadcast(new ServerMessageS2C(seat.name + ": " + chat.text().substring(0, Math.min(256, chat.text().length())))); return;
        }
        if (packet instanceof LeaveLevelC2S) { remove(seat); return; }
        if (!allowed(packet)) { connection.send(new ServerMessageS2C("此操作仅房主可用")); return; }
        if (!started || !seat.entered || server.level() == null) return;
        withActor(seat, () -> server.level().asParticipant(seat.id, server.roomBridge(), () -> server.handleRoomGameplay(packet)));
    }

    private void join(Connection connection, RoomC2S hello) {
        try {
            JsonObject info = JsonParser.parseString(hello.value()).getAsJsonObject();
            Seat resume = hello.token().isBlank() ? null : seats.values().stream()
                    .filter(s -> s.token.equals(hello.token())).findFirst().orElse(null);
            if (resume != null && resume.connection != null && resume.connection.isConnected()) throw new IllegalArgumentException("该席位仍在线");
            if (resume == null && seats.size() + 1 >= (versus ? 2 : PvzceConstants.MULTIPLAYER_MAX_PLAYERS))
                throw new IllegalArgumentException("房间已满");
            if (resume == null && started && versus) throw new IllegalArgumentException("对战已经开始");
            Seat seat = resume == null ? new Seat() : resume;
            String name = info.get("name").getAsString().strip().replaceAll("[\\p{Cntrl}]", "");
            seat.name = name.isEmpty() ? "玩家" : name.substring(0, Math.min(24, name.length()));
            seat.connection = connection; seat.admitted = false; seat.packsSelected = null; seat.snapshotSent = false;
            seats.put(seat.id, seat);
            connection.send(packs.manifest(requireAllPacks));
        } catch (RuntimeException e) {
            connection.send(new RoomS2C("error", "", "", "", false, List.of(), "无法加入：" + e.getMessage(), versus, "", false, false));
        }
    }

    private static boolean allowed(PvzcePacket packet) {
        return packet instanceof PlacePlantC2S || packet instanceof PlaceZombieC2S
                || packet instanceof CollectResourceC2S || packet instanceof UseToolC2S
                || packet instanceof UseGrantedToolC2S || packet instanceof SmashContainerC2S
                || packet instanceof PickUpCardC2S || packet instanceof PlantHeldCardC2S
                || packet instanceof ReleaseHeldCardC2S || packet instanceof FireAtC2S
                || packet instanceof ReleaseMowerC2S || packet instanceof FusionActionC2S
                || packet instanceof PickCardC2S || packet instanceof OutpostStrikeC2S
                || packet instanceof ZombossCommandC2S;
    }

    private void selectLevel(PlayLevelC2S play) {
        var def = com.pvzce.common.core.BuiltInRegistries.LEVELS.get(Identifier.tryParse(play.levelId()));
        if (def == null) return;
        if (!acceptsLevel(def)) { host.send(new ServerMessageS2C(versus ? "请选择对战关卡" : "合作房间请选择非对战关卡")); return; }
        LevelServer previous = server.level();
        server.prepareRoomLevel(play, versus);
        if (server.level() == previous) return;
        started = false;
        seats.values().forEach(s -> { s.ready = false; s.entered = false; });
        world = server.currentWorld(); hostReady = true;
        for (Seat seat : seats.values()) if (seat.admitted && seat.connection != null)
            withActor(seat, () -> server.sendRoomLevelList(world));
        publish();
    }

    private void prepare(Seat seat, PlayLevelC2S play) {
        LevelServer level = server.level();
        if (!hostReady || level == null || !level.def().id().toString().equals(play.levelId())) {
            seat.connection.send(new ServerMessageS2C("请等待房主选择关卡")); return;
        }
        if (seat.entered) return;
        seat.team = guestTeam();
        seat.cards = SeedSelection.plan(level.def(), server.worlds().profileFor(world),
                play.selectedSeeds().stream().map(Identifier::tryParse).filter(Objects::nonNull).toList(),
                server.worlds().worldDir(world), false, level.cardSource().dealsItsOwnCards(), seat.team);
        level.addParticipant(seat.id, seat.team, seat.cards);
        seat.entered = true; seat.ready = true;
        if (started) snapshot(seat);
        publish();
    }

    private void start() {
        if (!canStart()) {
            host.send(new ServerMessageS2C("请选择关卡，并等待所有玩家选卡准备")); return;
        }
        started = true;
        publish();
        server.level().sendFullState(host::send);
        for (Seat seat : seats.values()) snapshot(seat);
        server.level().beginPresentation(server.roomBridge());
    }

    private boolean canStart() {
        return !started && server.level() != null && hostReady && !seats.isEmpty()
                && seats.values().stream().allMatch(s -> s.admitted && s.ready && s.connection != null && s.connection.isConnected());
    }

    private Identifier guestTeam() {
        Identifier hostTeam = server.level().humanTeamId();
        return versus ? (PvzceIds.PLANT_TEAM.equals(hostTeam) ? PvzceIds.ZOMBIE_TEAM : PvzceIds.PLANT_TEAM) : hostTeam;
    }

    private void snapshot(Seat seat) {
        withActor(seat, () -> server.level().asParticipant(seat.id, server.roomBridge(),
                () -> server.level().sendFullState(p -> seat.connection.send(project(p, seat)))));
        seat.connection.send(new GameSpeedS2C(server.tickRateManager().tickRate()));
    }

    void tickParticipants() {
        if (!started || server.level() == null || !server.level().gameState().equals(GameStateS2C.RUNNING)) return;
        for (Seat seat : seats.values()) if (seat.entered)
            withActor(seat, () -> server.level().tickParticipant(seat.id, server.roomBridge()));
    }

    private void withActor(Seat seat, Runnable action) {
        Seat previous = actor; actor = seat;
        try { action.run(); } finally { actor = previous; }
    }

    void route(PvzcePacket packet) {
        if (!open()) { host.send(packet); return; }
        // A card bar is the seat's, except a coop belt, which every seat of the team shares. Each
        // versus side holds its own bar, belts included, so the other side never sees it.
        boolean bar = packet instanceof SlotSyncS2C || packet instanceof MechanicSyncS2C sync
                && sync.mechanic().equals(PvzceIds.MECHANIC_CONVEYOR);
        if (bar && !versus && server.level() != null && server.level().cardSource().dealsItsOwnCards()) {
            broadcast(packet); return;
        }
        if (bar && (versus || packet instanceof SlotSyncS2C) || packet instanceof HeldCardS2C
                || packet instanceof CarrySyncS2C || packet instanceof ServerMessageS2C || packet instanceof TeamSyncS2C) {
            Connection target = replyConnection(); if (target != null) target.send(packet);
        } else broadcast(packet);
    }

    private PvzcePacket project(PvzcePacket packet, Seat seat) {
        if (packet instanceof GameStateS2C state && !GameStateS2C.RUNNING.equals(state.state()))
            return new GameStateS2C(state.winTeamId().equals(seat.team.toString()) ? GameStateS2C.WON : GameStateS2C.LOST,
                    state.winTeamId(), state.wavesArrived(), state.kills(), state.survivedTicks(), state.score());
        if (versus && packet instanceof MechanicSyncS2C sync && sync.mechanic().equals(PvzceIds.MECHANIC_VERSUS)) {
            var buf = sync.payloadBuffer();
            try {
                var state = com.pvzce.common.level.mechanic.VersusMechanic.State.CODEC.decode(buf);
                Identifier opponent = seat.team.equals(PvzceIds.PLANT_TEAM) ? PvzceIds.ZOMBIE_TEAM : PvzceIds.PLANT_TEAM;
                return MechanicSyncS2C.of(sync.mechanic(), com.pvzce.common.level.mechanic.VersusMechanic.State.CODEC,
                        new com.pvzce.common.level.mechanic.VersusMechanic.State(state.sunGoal(), state.sunCollected(),
                                opponent.equals(PvzceIds.PLANT_TEAM) ? "plant" : "zombie", 3, "", -1, -1,
                                server.level().team(opponent).resourcesOf(PvzceIds.SUN),
                                server.level().versusData().cardsFor(opponent).stream().map(Identifier::toString).toList(), "", false));
            } finally { buf.delegate().release(); }
        }
        return packet;
    }

    private void broadcast(PvzcePacket packet) {
        host.send(packet);
        for (Seat seat : seats.values()) if (seat.admitted && seat.connection != null && seat.entered && started)
            seat.connection.send(project(packet, seat));
    }

    private void publish() {
        if (!open()) return;
        String roomName = (world == null ? "PVZCE" : world) + (versus ? " · 真人对战" : " · 合作闯关");
        var announcement = new LanDiscovery.Announcement(roomName.substring(0, Math.min(64, roomName.length())), port(),
                seats.size() + 1, versus ? 2 : PvzceConstants.MULTIPLAYER_MAX_PLAYERS, versus, started);
        if (lan == null) lan = new LanDiscovery.Advertiser(announcement);
        else lan.update(announcement);
        String id = !hostReady || server.level() == null ? "" : server.level().def().id().toString();
        String hostTeam = id.isEmpty() ? "" : server.level().humanTeamId().toString();
        String guestTeam = id.isEmpty() ? "" : guestTeam().toString();
        List<String> roster = new ArrayList<>(); roster.add("房主 · " + teamLabel(hostTeam) + (hostReady ? "已准备" : "选关中"));
        for (Seat seat : seats.values()) roster.add(seat.name + " · " + teamLabel(guestTeam)
                + (seat.connection == null ? "掉线，等待重连" : seat.ready ? "已准备" : "未准备"));
        String state = started ? "playing" : "lobby";
        host.send(new RoomS2C(state, id, "host", "", true, roster, message, versus, hostTeam, hostReady, canStart()));
        for (Seat seat : seats.values()) if (seat.connection != null && seat.admitted)
            seat.connection.send(new RoomS2C(state, id, seat.id, seat.token, false, roster, message, versus, guestTeam, seat.ready, canStart()));
    }

    private String teamLabel(String id) {
        if (!versus || id.isEmpty()) return "";
        return server.level().def().teams().stream().filter(t -> t.id().toString().equals(id))
                .findFirst().orElseThrow().name() + " · ";
    }

    private void remove(Seat seat) {
        if (server.level() != null && seat.entered)
            withActor(seat, () -> server.level().removeParticipant(seat.id, server.roomBridge()));
        seats.remove(seat.id);
        if (seat.connection != null) seat.connection.disconnect("已离开房间");
        publish();
    }

    @Override public void close() {
        if (lan != null) { lan.close(); lan = null; }
        ServerSocket socket = listener; listener = null;
        if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        for (Seat seat : List.copyOf(seats.values())) remove(seat);
        pending.forEach(p -> p.connection().disconnect("房间已关闭"));
        handshakes.release(pending.size()); pending.clear();
        Connection connection; while ((connection = accepted.poll()) != null) { connection.close(); handshakes.release(); }
        started = false; content = null; packs = null;
        if (server.level() != null) server.level().setHumanVersus(false);
        if (socket != null) host.send(new RoomS2C("closed", "", "", "", true, List.of(), "房间已关闭", versus, "", false, false));
    }
}
