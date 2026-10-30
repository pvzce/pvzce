package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.PvzceWindow;
import com.pvzce.client.PvzceClientPacketListener;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.renderer.font.Fonts;
import com.pvzce.common.network.*;
import com.pvzce.common.network.packet.*;
import com.pvzce.testutil.ClientHarness;
import com.pvzce.testutil.ServerHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class MultiplayerScreenTest {
    private static final String LEVEL = "pvzce:room_test";

    private static void window(PvzceClient client) throws Exception {
        var singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        var window = (PvzceWindow)((sun.misc.Unsafe)singleton.get(null)).allocateInstance(PvzceWindow.class);
        var f = client.getClass().getDeclaredField("window"); f.setAccessible(true); f.set(client, window);
        for (String dimension : List.of("width", "height")) {
            f = PvzceWindow.class.getDeclaredField(dimension); f.setAccessible(true);
            f.setInt(window, dimension.equals("width") ? 1280 : 720);
        }
    }

    @Test void hostCanRequireAllPacksAndGuestCanOnlyDeselectOptionalPacks(@TempDir Path dir) throws Exception {
        try (var h = ClientHarness.create("server-packs-ui")) {
            var client = h.client(); window(client);
            client.openScreen(new MultiplayerScreen(client));
            click(client.currentScreen(), "[ ] 必须接受所有数据包");
            click(client.currentScreen(), "创建合作房间");
            assertEquals(new RoomC2S("open", "24860", "coop_all"), h.sentPackets().getLast());
            var required = new PackManifestS2C.Pack("gameplay", ContentSnapshot.digest("required"), 100, true);
            var optional = new PackManifestS2C.Pack("textures", ContentSnapshot.digest("optional"), 100, false);
            for (boolean forceAll : List.of(false, true)) {
                try (var packs = new SessionPacks.Client(dir, null)) {
                    var field = PvzceClient.class.getDeclaredField("remotePacks"); field.setAccessible(true); field.set(client, packs);
                    var manifest = new PackManifestS2C(List.of(required, optional), forceAll);
                    var screen = new ServerPacksScreen(client, manifest); client.openScreen(screen); screen.initIfNeeded();
                    var list = (com.pvzce.client.gui.components.AbstractSelectionList<?>)screen.widgets().getFirst();
                    screen.dispatchMouseClicked(list.x() + 10, list.y() + list.height() - list.entryHeight() / 2D, 0);
                    screen.dispatchMouseClicked(list.x() + 10, list.y() + list.height() - list.entryHeight() * 1.5D, 0);
                    screen.onResize(); screen.initIfNeeded();
                    click(screen, "接受并加入");
                    RoomC2S choice = h.sentPackets().stream().filter(p -> p instanceof RoomC2S r && r.action().equals("packs_select"))
                            .map(RoomC2S.class::cast).reduce((a,b) -> b).orElseThrow();
                    var hashes = com.google.gson.JsonParser.parseString(choice.value()).getAsJsonArray();
                    assertTrue(hashes.contains(new com.google.gson.JsonPrimitive(required.hash())));
                    assertEquals(forceAll, hashes.contains(new com.google.gson.JsonPrimitive(optional.hash())));
                }
            }
            client.showTitle();
        }
    }

    @Test void bookmarksCanBeAddedEditedCancelledAndJoinedThroughWidgets() throws Exception {
        try (var h = ClientHarness.create("server-browser-ui")) {
            var client = h.client(); window(client);
            try {
                client.openScreen(new MultiplayerScreen(client));
                var browser = client.currentScreen();
                click(browser, "添加服务器");
                var form = assertInstanceOf(ServerEntryScreen.class, client.currentScreen());
                form.initIfNeeded();
                var fields = form.widgets().stream().filter(com.pvzce.client.gui.components.EditBox.class::isInstance)
                        .map(com.pvzce.client.gui.components.EditBox.class::cast).toList();
                fields.get(0).setValue("本地合作"); fields.get(1).setValue("localhost:24862");
                form.onResize(); form.initIfNeeded();
                click(form, "保存");
                assertSame(browser, client.currentScreen());
                var lists = browser.widgets().stream().filter(com.pvzce.client.gui.components.AbstractSelectionList.class::isInstance)
                        .map(com.pvzce.client.gui.components.AbstractSelectionList.class::cast).toList();
                var servers = lists.getFirst();
                assertEquals(3, servers.visibleCount());
                assertEquals(1, servers.entries().size());
                assertEquals(1, new com.pvzce.client.config.SavedServers(client.gameDir()).entries().size());
                click(browser, "编辑");
                var edit = client.currentScreen(); edit.initIfNeeded();
                var address = edit.widgets().stream().filter(com.pvzce.client.gui.components.EditBox.class::isInstance)
                        .map(com.pvzce.client.gui.components.EditBox.class::cast).toList().get(1);
                address.setValue("localhost:24863"); click(edit, "取消");
                assertEquals("localhost:24862", new com.pvzce.client.config.SavedServers(client.gameDir()).entries().getFirst().address());
                click(browser, "加入服务器");
                assertEquals("localhost:24862", client.roomAddress());
                assertEquals(List.of(new RoomC2S("suspend", "", "")), h.sentPackets());
                assertTrue(client.connectingMultiplayer());
            } finally { client.showTitle(); }
        }
    }

    @Test void directConnectionValidatesInputAndDoesNotSaveABookmark() throws Exception {
        try (var h = ClientHarness.create("server-direct-ui")) {
            var client = h.client(); window(client);
            try {
                client.openScreen(new MultiplayerScreen(client));
                click(client.currentScreen(), "直接连接");
                var form = assertInstanceOf(ServerEntryScreen.class, client.currentScreen());
                form.initIfNeeded();
                var address = (com.pvzce.client.gui.components.EditBox)form.widgets().stream()
                        .filter(com.pvzce.client.gui.components.EditBox.class::isInstance).findFirst().orElseThrow();
                address.setValue("host:70000"); click(form, "连接");
                assertSame(form, client.currentScreen()); assertTrue(h.sentPackets().isEmpty());
                address.setValue("[::1]:24862"); click(form, "连接");
                assertInstanceOf(MultiplayerScreen.class, client.currentScreen());
                assertEquals("[::1]:24862", client.roomAddress());
                assertEquals(List.of(new RoomC2S("suspend", "", "")), h.sentPackets());
                assertTrue(new com.pvzce.client.config.SavedServers(client.gameDir()).entries().isEmpty());
                click(client.currentScreen(), "返回");
                assertInstanceOf(TitleScreen.class, client.currentScreen());
                assertFalse(client.connectingMultiplayer());
                assertEquals(List.of(new RoomC2S("suspend", "", ""), new RoomC2S("close", "", ""),
                        new RoomC2S("resume", "", "")), h.sentPackets());
                client.onMultiplayerRoom(new RoomS2C("suspended", "", "", "", true, List.of(), "", false, "", false, false));
                assertFalse(client.connectingMultiplayer(), "Late suspend acknowledgement must not reconnect after cancellation");
            } finally { client.showTitle(); }
        }
    }

    @Test void lanRowsRejectFullRoomsAndJoinAvailableRoomsWithOneClick() throws Exception {
        try (var h = ClientHarness.create("server-lan-ui")) {
            var client = h.client(); window(client);
            var screen = new MultiplayerScreen(client);
            client.openScreen(screen); screen.initIfNeeded();
            var field = MultiplayerScreen.class.getDeclaredField("discovery"); field.setAccessible(true);
            ((LanDiscovery.Browser)field.get(screen)).close();
            var constructor = LanDiscovery.Browser.class.getDeclaredConstructor(int.class); constructor.setAccessible(true);
            var scanner = constructor.newInstance(0);
            field.set(screen, scanner);
            var accept = LanDiscovery.Browser.class.getDeclaredMethod("accept", byte[].class, java.net.InetAddress.class, long.class);
            accept.setAccessible(true);
            String prefix = "PVZCE_LAN\n" + com.pvzce.common.network.PacketRegistry.fingerprint() + "\n24862\n";
            var address = java.net.InetAddress.getByName("192.0.2.8");
            try {
                String full = prefix + "4\n4\ncoop\nlobby\nLAN game\n00000000-0000-0000-0000-000000000001";
                accept.invoke(scanner, full.getBytes(java.nio.charset.StandardCharsets.UTF_8), address, System.nanoTime());
                screen.tick();
                var list = (com.pvzce.client.gui.components.AbstractSelectionList<?>)screen.widgets().stream()
                        .filter(com.pvzce.client.gui.components.AbstractSelectionList.class::isInstance).toList().get(1);
                assertEquals(1, list.entries().size());
                screen.dispatchMouseClicked(list.x() + 10, list.y() + list.height() - list.entryHeight()/2D, 0);
                assertTrue(h.sentPackets().isEmpty(), "A full LAN room must not start a connection");
                String available = prefix + "1\n4\ncoop\nlobby\nLAN game\n00000000-0000-0000-0000-000000000001";
                accept.invoke(scanner, available.getBytes(java.nio.charset.StandardCharsets.UTF_8), address, System.nanoTime());
                screen.tick();
                screen.dispatchMouseClicked(list.x() + 10, list.y() + list.height() - list.entryHeight()/2D, 0);
                assertEquals("192.0.2.8:24862", client.roomAddress());
                assertEquals(List.of(new RoomC2S("suspend", "", "")), h.sentPackets());
            } finally { client.showTitle(); }
            assertTrue(scanner.rooms().isEmpty(), "Removing the browser must release its scanner");
        }
    }

    private static LevelListS2C.LevelInfo info() {
        return LevelListS2C.LevelInfo.of(LEVEL, "房间关卡", "", "pvzce:plant_team",
                List.of(new LevelListS2C.TeamInfo("pvzce:plant_team", "植物", "survive_waves")), "", "day",
                "pvzce:yard", "pvzce:adventure", false,
                new LevelPayload(9, 5, List.of(new SeedOption("pvzce:sun", "resource", "pvzce:sun", "", 0),
                        new SeedOption("pvzce:pea_shooter", "plant", "pvzce:pea_shooter", "", 100)),
                        1, List.of(), List.of(), List.of(), List.of()), LevelListS2C.UnlockInfo.OPEN);
    }

    private static void room(PvzceClient client, String level) {
        client.onMultiplayerRoom(new RoomS2C("lobby", level, "guest", "token", false,
                List.of("房主 · 已准备", "客人 · 未准备"), "合作闯关", false, "pvzce:plant_team", false, false));
    }

    private static void click(Screen screen, String label) {
        screen.initIfNeeded();
        var button = screen.widgets().stream().filter(w -> w instanceof Button b && b.label().equals(label)).findFirst().orElseThrow();
        screen.dispatchMouseClicked(button.x() + button.width()/2D, button.y() + button.height()/2D, 0);
    }

    @Test void versusPreparationUsesRoomFieldsAndDoesNotOpenACardChooser() throws Exception {
        try (var h = ClientHarness.create("multiplayer-versus-menu")) {
            var client = h.client(); window(client);
            var roster = List.of("房主 · 植物方 · 已准备", "客人 · 僵尸方 · 未准备");
            client.onMultiplayerRoom(new RoomS2C("lobby", "pvzce:yard/versus/duel_1", "guest", "token", false,
                    roster, "没有模式关键词的普通提示", true, "pvzce:zombie_team", false, false));
            click(client.currentScreen(), "准备对战");
            assertInstanceOf(MultiplayerScreen.class, client.currentScreen());
            assertEquals(List.of(new RoomC2S("ready", "", "")), h.sentPackets());
            client.onMultiplayerRoom(new RoomS2C("lobby", "pvzce:yard/versus/duel_1", "guest", "token", false,
                    roster, "", true, "pvzce:zombie_team", true, true));
            click(client.currentScreen(), "已准备");
            var button = client.currentScreen().widgets().stream().filter(w -> w instanceof Button b && b.label().equals("已准备")).findFirst().orElseThrow();
            assertFalse(button.isActive());
            assertEquals(1, h.sentPackets().size(), "Repeated clicks must not prepare twice");
        }
    }

    @Test void versusHostCanStartOnlyWithTheAuthoritativeReadyVerdictAndCanReturnFromMapSelection() throws Exception {
        try (var h = ClientHarness.create("multiplayer-versus-host")) {
            var client = h.client(); window(client);
            var roster = List.of("房主 · 植物方 · 已准备", "客人 · 僵尸方 · 未准备");
            client.onMultiplayerRoom(new RoomS2C("lobby", "pvzce:yard/versus/duel_1", "host", "", true,
                    roster, "", true, "pvzce:plant_team", true, false));
            click(client.currentScreen(), "开始对战");
            assertTrue(h.sentPackets().isEmpty());
            client.onMultiplayerRoom(new RoomS2C("lobby", "pvzce:yard/versus/duel_1", "host", "", true,
                    roster, "", true, "pvzce:plant_team", true, true));
            click(client.currentScreen(), "开始对战");
            assertEquals(List.of(new RoomC2S("start", "", "")), h.sentPackets());
            click(client.currentScreen(), "选择对战地图");
            assertInstanceOf(LevelSelectScreen.class, client.currentScreen());
            client.currentScreen().requestClose();
            assertInstanceOf(MultiplayerScreen.class, client.currentScreen());
        }
    }

    private static void await(ServerHarness host, PvzceClient client, BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + 15_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < until) {
            host.pair().client().tick(); client.connection().tick(); Thread.sleep(2);
        }
        assertTrue(condition.getAsBoolean(), client.multiplayerMessage());
        assertTrue(client.connection().isConnected(), client.connection().disconnectReason());
    }

    @Test void tcpGuestClicksChoosesACardReadiesAndEntersTheBattle(@TempDir Path dir) throws Exception {
        String id = "pvzce:yard/adventure/1_6";
        var path = dir.resolve("datapacks/room-test/data/pvzce/levels/yard/adventure/1_6.json");
        Files.createDirectories(path.getParent());
        var json = com.google.gson.JsonParser.parseString(new String(getClass().getResourceAsStream(
                "/data/pvzce/levels/yard/adventure/1_6.json").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        var slots = new com.google.gson.JsonArray(); slots.add("pvzce:sun");
        json.add("slots", slots); json.addProperty("max_seed_slots", 2);
        Files.writeString(path, json.toString());
        try (var host = ServerHarness.createWithWorld(dir, "world", true)) {
            host.send(new RoomC2S("open", "0", "coop")); host.awaitPacket(RoomS2C.class, 15_000);
            try (var connection = TcpPacketTransport.connect("127.0.0.1", host.server().multiplayerPort())) {
                var client = new PvzceClient(connection, dir.resolve("guest"), getClass().getClassLoader());
                window(client);
                // Same JVM shares the host's loaded definitions. Consume the wire handshake without
                // installing another global registry while its authoritative server is running.
                var listener = new PvzceClientPacketListener(client, client.level());
                connection.setListener(packet -> {
                    if (packet instanceof com.pvzce.common.network.packet.PackManifestS2C manifest) {
                        var selected = new com.google.gson.JsonArray(); manifest.packs().forEach(p -> selected.add(p.hash()));
                        connection.send(new RoomC2S("packs_select", selected.toString(), ""));
                        connection.send(new RoomC2S("packs_ready", "", ""));
                    } else if (packet instanceof ContentS2C content) {
                        if (content.last()) connection.send(new RoomC2S("content_ready", content.data(), ""));
                    } else listener.handle(packet);
                });
                var hello = new com.google.gson.JsonObject(); hello.addProperty("name", "UI客人");
                connection.send(new RoomC2S("hello", hello.toString(), ""));
                await(host, client, () -> client.multiplayer() != null);
                host.send(new PlayLevelC2S(id, "world", true, List.of("pvzce:sun", "pvzce:pea_shooter")));
                await(host, client, () -> client.multiplayer().levelId().equals(id));
                click(client.currentScreen(), "选卡并准备");
                await(host, client, () -> client.currentScreen() instanceof ChooseSeedsScreen);
                var chooser = (ChooseSeedsScreen)client.currentScreen();
                try (var fonts = new Fonts(host.server().resourceManager(), client::guiScale)) {
                    var field = PvzceClient.class.getDeclaredField("fonts"); field.setAccessible(true); field.set(client, fonts);
                    chooser.initIfNeeded();
                    // Advance animation clocks deterministically; input still goes through the real widgets.
                    field = ChooseSeedsScreen.class.getDeclaredField("panStartNanos"); field.setAccessible(true);
                    field.setLong(chooser, System.nanoTime() - 5_000_000_000L);
                    chooser.tick();
                    var pool = client.levelList().stream().filter(l -> l.id().equals(id)).findFirst().orElseThrow().seedPool();
                    int pea = java.util.stream.IntStream.range(0, pool.size()).filter(i -> pool.get(i).slotId().equals("pvzce:pea_shooter")).findFirst().orElseThrow();
                    var center = ChooseSeedsScreen.class.getDeclaredMethod("cardCenter", int.class); center.setAccessible(true);
                    float[] point = (float[])center.invoke(chooser, pea);
                    chooser.dispatchMouseClicked(point[0], point[1], 0);
                    click(chooser, "开始游戏");
                    field = ChooseSeedsScreen.class.getDeclaredField("exitNanos"); field.setAccessible(true);
                    assertNotEquals(0, field.getLong(chooser));
                    field.setLong(chooser, System.nanoTime() - 2_000_000_000L);
                    chooser.tick();
                    await(host, client, () -> client.multiplayer().players().contains("UI客人 · 已准备"));
                    assertInstanceOf(MultiplayerScreen.class, client.currentScreen());
                    host.send(new RoomC2S("start", "", ""));
                    await(host, client, () -> id.equals(client.level().levelId()));
                    assertInstanceOf(InGameScreen.class, client.currentScreen());
                    assertEquals(id, client.level().levelId());
                    assertTrue(client.level().slots().stream().anyMatch(s -> s.defId().equals("pvzce:pea_shooter")));
                }
            }
        }
    }

    @Test void oneClickRequestsCurrentRoomDataAndOpensTheChooserWhenItArrives() throws Exception {
        try (var h = ClientHarness.create("multiplayer-menu")) {
            var client = h.client(); window(client);
            room(client, LEVEL);
            var screen = assertInstanceOf(MultiplayerScreen.class, client.currentScreen());
            click(screen, "选卡并准备");
            assertSame(screen, client.currentScreen());
            assertTrue(client.multiplayerMessage().contains("同步"));
            assertTrue(h.sentPackets().stream().anyMatch(RequestLevelListC2S.class::isInstance),
                    "A missing join-time list must trigger a request, rather than wait forever");
            client.setLevelList(List.of(info()));
            assertInstanceOf(ChooseSeedsScreen.class, client.currentScreen());
            // Room roster updates must not pull an open chooser back to the lobby.
            room(client, LEVEL);
            assertInstanceOf(ChooseSeedsScreen.class, client.currentScreen());
            room(client, "pvzce:different_level");
            assertInstanceOf(MultiplayerScreen.class, client.currentScreen(), "Host changes invalidate the previous chooser");
        }
    }

    @Test void leavingTheLobbyOrChangingLevelCancelsThePendingClick() throws Exception {
        try (var h = ClientHarness.create("multiplayer-menu-cancel")) {
            var client = h.client(); window(client);
            room(client, LEVEL);
            click(client.currentScreen(), "选卡并准备");
            client.onNetworkDisconnect("test reconnect");
            room(client, LEVEL);
            click(client.currentScreen(), "选卡并准备");
            assertEquals(2, h.sentPackets().stream().filter(RequestLevelListC2S.class::isInstance).count(),
                    "Reconnecting to the same level must allow a fresh preparation request");
            room(client, "pvzce:different_level");
            client.setLevelList(List.of(info()));
            assertInstanceOf(MultiplayerScreen.class, client.currentScreen());
            click(client.currentScreen(), "选卡并准备");
            client.onMultiplayerRoom(new RoomS2C("closed", "", "", "", false, List.of(), "", false, "", false, false));
            client.setLevelList(List.of(info()));
            assertFalse(client.currentScreen() instanceof ChooseSeedsScreen);
        }
    }

    @Test void absentLevelHasAnExplicitMessageInsteadOfADeadButton() throws Exception {
        try (var h = ClientHarness.create("multiplayer-menu-missing")) {
            var client = h.client(); window(client);
            room(client, "");
            click(client.currentScreen(), "等待房主选关");
            assertTrue(h.sentPackets().stream().noneMatch(RequestLevelListC2S.class::isInstance));
            client.prepareMultiplayer();
            assertEquals("请等待房主选择关卡", client.multiplayerMessage());
            room(client, LEVEL);
            click(client.currentScreen(), "选卡并准备");
            client.setLevelList(List.of());
            assertTrue(client.multiplayerMessage().contains("未提供"));
        }
    }
}
