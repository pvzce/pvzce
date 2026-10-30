package com.pvzce.server;

import com.google.gson.JsonObject;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.network.*;
import com.pvzce.common.network.packet.*;
import com.pvzce.testutil.ServerHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class MultiplayerRoomTest {
    private static final String LEVEL = "pvzce:yard/adventure/1_2";
    private static final List<String> CARDS = List.of("pvzce:sun", "pvzce:pea_shooter", "pvzce:glove");

    static final class Peer implements AutoCloseable {
        final Connection connection;
        final List<PvzcePacket> packets = new ArrayList<>();
        RoomS2C room;
        LevelInitS2C init;
        Peer(int port, String resume) throws Exception {
            connection = TcpPacketTransport.connect("127.0.0.1", port);
            connection.setListener(packet -> {
                packets.add(packet);
                if (packet instanceof PackManifestS2C manifest) {
                    var selected = new com.google.gson.JsonArray(); manifest.packs().forEach(p -> selected.add(p.hash()));
                    connection.send(new RoomC2S("packs_select", selected.toString(), ""));
                    connection.send(new RoomC2S("packs_ready", "", ""));
                }
                if (packet instanceof ContentS2C c && c.last()) connection.send(new RoomC2S("content_ready", c.data(), ""));
                if (packet instanceof RoomS2C r) room = r;
                if (packet instanceof LevelInitS2C i) init = i;
            });
            JsonObject hello = new JsonObject(); hello.addProperty("name", "guest");
            connection.send(new RoomC2S("hello", hello.toString(), resume));
        }
        int slot(String id) { return init.slots().stream().filter(s -> s.defId().equals(id)).findFirst().orElseThrow().index(); }
        @Override public void close() { connection.close(); }
    }

    static void await(ServerHarness host, List<Peer> peers, BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + 15_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < until) {
            host.pair().client().tick();
            peers.forEach(p -> p.connection.tick());
            Thread.sleep(2);
        }
        assertTrue(condition.getAsBoolean(), "Timed out; host=" + host.packets().stream().filter(p -> p instanceof ServerMessageS2C || p instanceof RoomS2C).toList()
                + "; peers=" + peers.stream().map(p -> p.room + ":" + p.connection.disconnectReason()).toList());
    }

    @Test void selectingAWorldRefreshesGuestPreparationDataBeforeTheRoomAnnouncement(@TempDir Path dir) throws Exception {
        try (var host = ServerHarness.createWithWorld(dir, "unlocked", true)) {
            host.createWorld("fresh", false);
            host.send(new RoomC2S("open", "0", "coop"));
            host.awaitPacket(RoomS2C.class, 15_000);
            try (var guest = new Peer(host.server().multiplayerPort(), "")) {
                var peers = List.of(guest);
                await(host, peers, () -> guest.room != null);
                assertFalse(guest.packets.stream().filter(ProfileS2C.class::isInstance)
                        .map(ProfileS2C.class::cast).reduce((a, b) -> b).orElseThrow().unlockAll());
                guest.packets.clear();
                host.send(new PlayLevelC2S(LEVEL, "unlocked", true, CARDS));
                await(host, peers, () -> guest.room.levelId().equals(LEVEL));
                assertTrue(guest.packets.stream().filter(ProfileS2C.class::isInstance)
                        .map(ProfileS2C.class::cast).reduce((a, b) -> b).orElseThrow().unlockAll());
                int list = java.util.stream.IntStream.range(0, guest.packets.size())
                        .filter(i -> guest.packets.get(i) instanceof LevelListS2C).findFirst().orElseThrow();
                int room = java.util.stream.IntStream.range(0, guest.packets.size())
                        .filter(i -> guest.packets.get(i) instanceof RoomS2C r && r.levelId().equals(LEVEL)).findFirst().orElseThrow();
                assertTrue(list < room, "The announced level must already have its current card pool");
            }
        }
    }

    @Test void tcpCoopKeepsDecksPrivateArbitratesPlacementAndResumesASeat(@TempDir Path dir) throws Exception {
        try (var host = ServerHarness.createWithWorld(dir, "world", true)) {
            host.send(new RoomC2S("open", "0", "coop"));
            host.awaitPacket(RoomS2C.class, 15_000);
            int port = host.server().multiplayerPort();
            try (var guest = new Peer(port, "")) {
                var peers = List.of(guest);
                await(host, peers, () -> guest.room != null);
                host.send(new PlayLevelC2S(LEVEL, "world", true, CARDS));
                await(host, peers, () -> guest.room.levelId().equals(LEVEL));
                assertNull(guest.init, "A lobby must not start the battle before everyone is ready");
                guest.connection.send(new PlayLevelC2S(LEVEL, "untrusted-world", true, CARDS));
                await(host, peers, () -> guest.room.players().stream().anyMatch(s -> s.contains("guest") && s.contains("已准备")));
                host.send(new RoomC2S("start", "", ""));
                await(host, peers, () -> guest.init != null);
                host.send(new CommandC2S("/tick freeze"));
                host.send(new CommandC2S("/resource give pvzce:plant_team pvzce:sun 1000"));
                await(host, peers, () -> host.server().tickRateManager().isFrozen());
                int hostSlot = host.server().level().plantPlayer().slots().stream().filter(s -> s.defId().equals(PvzceIds.id("pea_shooter"))).findFirst().orElseThrow().index();
                int guestSlot = guest.slot("pvzce:pea_shooter");
                guest.packets.clear(); host.clear();
                host.send(new PlacePlantC2S(hostSlot, 0, 0));
                await(host, peers, () -> guest.packets.stream().anyMatch(p -> p instanceof EntitySpawnS2C s && s.defId().equals("pvzce:pea_shooter")));
                assertTrue(guest.packets.stream().noneMatch(p -> p instanceof SlotSyncS2C s && s.slot().defId().equals("pvzce:pea_shooter") && s.slot().cooldownLeft() > 0));
                guest.connection.send(new PlacePlantC2S(guestSlot, 0, 0));
                guest.connection.send(new PlacePlantC2S(guestSlot, 1, 0));
                await(host, peers, () -> host.server().level().plantAt(1, 0) != null);
                assertEquals(2, host.server().level().entities().stream().filter(e -> e instanceof com.pvzce.server.entity.PlantEntity).count());
                int hostGlove = host.server().level().plantPlayer().slots().stream().filter(slot -> slot.defId().equals(PvzceIds.id("glove"))).findFirst().orElseThrow().index();
                host.send(new UseToolC2S(hostGlove, 0, 0));
                await(host, peers, () -> host.packets().stream().anyMatch(p -> p instanceof CarrySyncS2C carry && !carry.plantId().isBlank()));
                guest.connection.send(new UseToolC2S(guest.slot("pvzce:glove"), 0, 0));
                await(host, peers, () -> guest.packets.stream().anyMatch(p -> p instanceof ServerMessageS2C msg && msg.message().contains("正在搬运")));
                assertTrue(guest.packets.stream().noneMatch(p -> p instanceof CarrySyncS2C), "The host hand must remain private");
                host.send(new UseToolC2S(hostGlove, 2, 0));
                await(host, peers, () -> host.server().level().plantAt(2, 0) != null);
                int balance = host.server().level().team(PvzceIds.PLANT_TEAM).resourcesOf(PvzceIds.SUN);
                String token = guest.room.token();
                guest.close();
                await(host, peers, () -> host.packets().stream().anyMatch(p -> p instanceof RoomS2C r && r.players().stream().anyMatch(s -> s.contains("掉线"))));
                assertTrue(host.server().thread().isAlive());
                try (var resumed = new Peer(port, token)) {
                    await(host, List.of(resumed), () -> resumed.init != null);
                    assertEquals(guest.room.playerId(), resumed.room.playerId());
                    assertTrue(resumed.init.slots().stream().anyMatch(s -> s.defId().equals("pvzce:pea_shooter") && s.cooldownLeft() > 0));
                    assertEquals(balance, host.server().level().team(PvzceIds.PLANT_TEAM).resourcesOf(PvzceIds.SUN));
                    resumed.connection.send(new CommandC2S("/resource give pvzce:plant_team pvzce:sun 9999"));
                    resumed.connection.send(new PauseGameC2S(false));
                    await(host, List.of(resumed), () -> resumed.packets.stream().anyMatch(p -> p instanceof ServerMessageS2C s && s.message().contains("仅房主")));
                    assertEquals(balance, host.server().level().team(PvzceIds.PLANT_TEAM).resourcesOf(PvzceIds.SUN));
                    assertTrue(host.server().tickRateManager().isFrozen());
                    resumed.connection.send(new RoomC2S("leave", "", ""));
                    await(host, List.of(resumed), () -> !resumed.connection.isConnected());
                    assertTrue(host.server().thread().isAlive());
                }
            }
        }
    }

    @Test void contentSnapshotRestoresLocalObjectIdentityAndTags(@TempDir Path dir) throws Exception {
        try (var host = ServerHarness.createWithWorld(dir, "world", true)) {
            var registry = com.pvzce.common.core.BuiltInRegistries.PLANTS;
            var original = registry.get(PvzceIds.id("pea_shooter"));
            int rawId = registry.getId(original);
            var tags = Set.copyOf(registry.tagKeys());
            List<PvzcePacket> chunks = new ArrayList<>();
            ContentSnapshot.send(ContentSnapshot.capture(), chunks::add);
            try (var remote = new ContentSnapshot()) {
                for (var chunk : chunks) remote.accept((ContentS2C)chunk);
                assertNotSame(original, registry.get(PvzceIds.id("pea_shooter")));
                assertEquals(original.health(), registry.get(PvzceIds.id("pea_shooter")).health());
                assertEquals(original.cost(), registry.get(PvzceIds.id("pea_shooter")).cost());
            }
            assertSame(original, registry.get(PvzceIds.id("pea_shooter")));
            assertEquals(rawId, registry.getId(original));
            assertEquals(tags, registry.tagKeys());
        }
    }
    @Test void independentJvmLoadsServerDataAndPlaysOverTcp(@TempDir Path dir) throws Exception {
        Path datapack = dir.resolve("datapacks/network-test/data/pvzce/plants/pea_shooter.json");
        java.nio.file.Files.createDirectories(datapack.getParent());
        String plant = new String(getClass().getResourceAsStream("/data/pvzce/plants/pea_shooter.json").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        var json = com.google.gson.JsonParser.parseString(plant).getAsJsonObject();
        json.getAsJsonObject("cost").getAsJsonObject("resources").addProperty("pvzce:sun", 77);
        java.nio.file.Files.writeString(datapack, json.toString());
        Path language = dir.resolve("datapacks/network-test/assets/test/lang/zh_cn.json");
        java.nio.file.Files.createDirectories(language.getParent());
        java.nio.file.Files.writeString(language, "host-art");
        Path old = dir.resolve("remote-client/datapacks/network-test/assets/test/lang/zh_cn.json");
        java.nio.file.Files.createDirectories(old.getParent()); java.nio.file.Files.writeString(old, "local-old-art");
        try (var host = ServerHarness.createWithWorld(dir, "world", true)) {
            host.send(new RoomC2S("open", "0", "coop")); host.awaitPacket(RoomS2C.class, 15_000);
            host.send(new PlayLevelC2S(LEVEL, "world", true, CARDS));
            await(host, List.of(), () -> host.server().level() != null);
            List<String> classpath = new ArrayList<>(List.of(System.getProperty("java.class.path").split(java.io.File.pathSeparator)));
            for (ClassLoader loader = Thread.currentThread().getContextClassLoader(); loader != null; loader = loader.getParent())
                if (loader instanceof java.net.URLClassLoader urls)
                    for (var url : urls.getURLs()) if (url.getProtocol().equals("file")) classpath.add(Path.of(url.toURI()).toString());
            Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", String.join(java.io.File.pathSeparator, classpath), MultiplayerClientProbe.class.getName(),
                    Integer.toString(host.server().multiplayerPort()), dir.resolve("remote-client").toString())
                    .redirectErrorStream(true).redirectOutput(dir.resolve("remote-client.log").toFile()).start();
            try {
                await(host, List.of(), () -> host.packets().stream().anyMatch(p -> p instanceof RoomS2C r
                        && r.players().stream().anyMatch(name -> name.contains("independent-jvm") && name.contains("已准备"))));
                host.send(new CommandC2S("/resource give pvzce:plant_team pvzce:sun 1000"));
                host.send(new RoomC2S("start", "", ""));
                await(host, List.of(), () -> host.server().level().plantAt(1, 0) != null);
                assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
                String log = java.nio.file.Files.readString(dir.resolve("remote-client.log"));
                assertEquals(0, process.exitValue(), log);
                assertTrue(log.contains("CONTENT_INSTALLED"), log);
                assertTrue(log.contains("PACK_ASSETS_INSTALLED"), log);
                assertTrue(log.contains("REGISTRY_MISMATCH_REJECTED"), log);
                assertEquals("local-old-art", java.nio.file.Files.readString(old));
                assertTrue(log.contains("BATTLE_INITIALIZED pvzce:plant_team"), log);
                assertTrue(log.contains("PLANT_CONFIRMED"), log);
            } finally { process.destroyForcibly(); }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pvzce:plant_team", "pvzce:zombie_team"})
    void humanVersusUsesUnhandicappedEconomyAndProjectsOppositeOutcomes(String hostTeam, @TempDir Path dir) throws Exception {
        try (var host = ServerHarness.createWithWorld(dir, "world", true)) {
            host.send(new RoomC2S("open", "0", "versus")); host.awaitPacket(RoomS2C.class, 15_000);
            try (var guest = new Peer(host.server().multiplayerPort(), "")) {
                var peers = List.of(guest);
                await(host, peers, () -> guest.room != null);
                assertTrue(guest.room.versus());
                assertFalse(guest.room.canStart());
                try (var extra = new Peer(host.server().multiplayerPort(), "")) {
                    await(host, List.of(extra), () -> extra.room != null && extra.room.state().equals("error"));
                    assertTrue(extra.room.message().contains("已满"));
                }
                var maps = guest.packets.stream().filter(LevelListS2C.class::isInstance).map(LevelListS2C.class::cast)
                        .reduce((a, b) -> b).orElseThrow().levels();
                assertFalse(maps.isEmpty());
                assertTrue(maps.stream().allMatch(l -> !l.isCollection() && l.name().startsWith("真人对战")),
                        maps.stream().map(l -> l.id() + "=" + l.name()).toList().toString());
                assertTrue(guest.packets.stream().filter(LevelTabsS2C.class::isInstance).map(LevelTabsS2C.class::cast)
                        .reduce((a, b) -> b).orElseThrow().tabs().stream().allMatch(t -> t.category().equals("pvzce:versus")));
                String duel = "pvzce:yard/versus/duel_1";
                host.send(new PlayLevelC2S(duel, "world", true, List.of(), List.of(), hostTeam));
                await(host, peers, () -> guest.room.levelId().equals(duel));
                String guestTeam = hostTeam.equals("pvzce:plant_team") ? "pvzce:zombie_team" : "pvzce:plant_team";
                assertEquals(guestTeam, guest.room.teamId());
                assertTrue(guest.room.players().stream().allMatch(p -> p.contains("植物方") || p.contains("僵尸方")));
                host.clear(); host.send(new RoomC2S("start", "", ""));
                await(host, peers, () -> host.packets().stream().anyMatch(p -> p instanceof ServerMessageS2C s && s.message().contains("等待")));
                assertNull(guest.init);
                guest.connection.send(new RoomC2S("ready", "", ""));
                await(host, peers, () -> guest.room.ready());
                assertTrue(guest.room.canStart());
                host.send(new RoomC2S("start", "", ""));
                await(host, peers, () -> guest.init != null);
                host.send(new CommandC2S("/tick freeze"));
                await(host, peers, () -> host.server().tickRateManager().isFrozen());
                assertEquals(guestTeam, guest.init.controlledTeamId());
                assertEquals(hostTeam, host.server().level().humanTeamId().toString());
                assertTrue(host.server().level().humanVersus());
                int opening = 50;
                assertEquals(opening, host.server().level().team(PvzceIds.PLANT_TEAM).resourcesOf(PvzceIds.SUN));
                assertTrue(host.server().level().entities().stream().noneMatch(e -> e instanceof com.pvzce.server.entity.PlantEntity));
                var opponent = versusState(guest.packets);
                assertEquals(3, opponent.opponentStatus());
                assertEquals(host.server().level().versusData().cardsFor(com.pvzce.api.util.Identifier.tryParse(hostTeam))
                        .stream().map(Object::toString).toList(), opponent.opponentHand());
                assertEquals(host.server().level().team(com.pvzce.api.util.Identifier.tryParse(hostTeam)).resourcesOf(PvzceIds.SUN), opponent.opponentSun());
                var hostHud = versusState(host.packets());
                assertEquals(host.server().level().versusData().cardsFor(com.pvzce.api.util.Identifier.tryParse(guestTeam))
                        .stream().map(Object::toString).toList(), hostHud.opponentHand());
                guest.connection.send(new ReselectCardsC2S(duel, "ignored", List.of("pvzce:cherry_bomb")));
                await(host, peers, () -> guest.packets.stream().anyMatch(p -> p instanceof ServerMessageS2C m && m.message().contains("地图规定")));
                host.send(new CommandC2S("/resource give pvzce:plant_team pvzce:sun 1000"));
                host.send(new CommandC2S("/resource give pvzce:zombie_team pvzce:sun 1000"));
                int zombieOpening = host.server().level().versusData().zombieInitialSun();
                await(host, peers, () -> host.server().level().team(PvzceIds.PLANT_TEAM).resourcesOf(PvzceIds.SUN) == opening + 1000
                        && host.server().level().team(PvzceIds.ZOMBIE_TEAM).resourcesOf(PvzceIds.SUN) == zombieOpening + 1000);
                boolean hostPlants = hostTeam.equals("pvzce:plant_team");
                Connection planter = hostPlants ? host.pair().client() : guest.connection;
                Connection zombies = hostPlants ? guest.connection : host.pair().client();
                LevelInitS2C plantInit = hostPlants ? host.packet(LevelInitS2C.class) : guest.init;
                LevelInitS2C zombieInit = hostPlants ? guest.init : host.packet(LevelInitS2C.class);
                var pea = plantInit.slots().stream().filter(s -> s.defId().equals("pvzce:pea_shooter")).findFirst().orElseThrow();
                var zombie = zombieInit.slots().stream().filter(s -> s.defId().equals("pvzce:basic_zombie")).findFirst().orElseThrow();
                // A forged opposite-side action must not turn that side's slot into an enemy card.
                zombies.send(new PlacePlantC2S(zombie.index(), 4, 0));
                planter.send(new PlaceZombieC2S(pea.index(), 8, 0));
                planter.send(new PlacePlantC2S(pea.index(), 0, 0));
                zombies.send(new PlaceZombieC2S(zombie.index(), 8, 4));
                guest.connection.send(new ChatC2S("placement-probe"));
                await(host, peers, () -> host.packets().stream().anyMatch(p -> p instanceof ServerMessageS2C m && m.message().contains("placement-probe")));
                host.send(new CommandC2S("/tick step 1"));
                await(host, peers, () -> host.server().level().plantAt(0, 0) != null
                        && host.server().level().entities().stream().anyMatch(e -> e instanceof com.pvzce.server.entity.ZombieEntity));
                assertNull(host.server().level().plantAt(4, 0));
                assertEquals(opening + 1000 - pea.costSun(), host.server().level().team(PvzceIds.PLANT_TEAM).resourcesOf(PvzceIds.SUN));
                assertEquals(zombieOpening + 1000 - zombie.costSun(), host.server().level().team(PvzceIds.ZOMBIE_TEAM).resourcesOf(PvzceIds.SUN));
                host.send(new CommandC2S("/resource spawn pvzce:sun 4 2"));
                host.send(new CommandC2S("/tick step 1"));
                await(host, peers, () -> host.server().level().entities().stream().anyMatch(e -> e instanceof com.pvzce.server.entity.ResourceDropEntity));
                var drop = host.server().level().entities().stream().filter(e -> e instanceof com.pvzce.server.entity.ResourceDropEntity)
                        .map(com.pvzce.server.entity.ResourceDropEntity.class::cast).findFirst().orElseThrow();
                zombies.send(new CollectResourceC2S(drop.id()));
                zombies.send(new ChatC2S("collection-probe"));
                await(host, peers, () -> host.packets().stream().anyMatch(p -> p instanceof ServerMessageS2C m && m.message().contains("collection-probe")));
                assertFalse(drop.isRemoved(), "The zombie side must not collect the plant side's sun");
                planter.send(new CollectResourceC2S(drop.id()));
                await(host, peers, drop::isRemoved);
                assertEquals(opening + 1000 - pea.costSun() + drop.amount(), host.server().level().team(PvzceIds.PLANT_TEAM).resourcesOf(PvzceIds.SUN));
                host.send(new CommandC2S("/spawn zombie pvzce:basic_zombie 0 2"));
                host.send(new CommandC2S("/tick step 400"));
                await(host, peers, () -> guest.packets.stream().anyMatch(p -> p instanceof GameStateS2C));
                var result = guest.packets.stream().filter(p -> p instanceof GameStateS2C).map(p -> (GameStateS2C)p).reduce((a,b) -> b).orElseThrow();
                assertEquals(guestTeam.equals("pvzce:zombie_team") ? GameStateS2C.WON : GameStateS2C.LOST, result.state());
                assertEquals("pvzce:zombie_team", result.winTeamId());
                assertTrue(host.packets().stream().anyMatch(p -> p instanceof GameStateS2C g
                        && g.state().equals(hostTeam.equals("pvzce:zombie_team") ? GameStateS2C.WON : GameStateS2C.LOST)));
            }
        }
    }

    private static com.pvzce.common.level.mechanic.VersusMechanic.State versusState(List<PvzcePacket> packets) {
        var packet = packets.stream().filter(p -> p instanceof MechanicSyncS2C s && s.mechanic().equals(PvzceIds.MECHANIC_VERSUS))
                .map(MechanicSyncS2C.class::cast).reduce((a, b) -> b).orElseThrow();
        var buf = packet.payloadBuffer();
        try { return com.pvzce.common.level.mechanic.VersusMechanic.State.CODEC.decode(buf); }
        finally { buf.delegate().release(); }
    }

    @Test void plantSunGoalAwardsTheHumanPlantSideAndRejectsTheZombieSide(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("datapacks/room-test/data/pvzce/levels/yard/versus/duel_1.json");
        java.nio.file.Files.createDirectories(path.getParent());
        var json = com.google.gson.JsonParser.parseString(new String(getClass().getResourceAsStream(
                "/data/pvzce/levels/yard/versus/duel_1.json").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        json.getAsJsonArray("mechanics").get(0).getAsJsonObject().addProperty("sun_goal", 25);
        java.nio.file.Files.writeString(path, json.toString());
        try (var host = ServerHarness.createWithWorld(dir, "world", true)) {
            host.send(new RoomC2S("open", "0", "versus")); host.awaitPacket(RoomS2C.class, 15_000);
            try (var guest = new Peer(host.server().multiplayerPort(), "")) {
                var peers = List.of(guest);
                await(host, peers, () -> guest.room != null);
                host.send(new PlayLevelC2S("pvzce:yard/versus/duel_1", "world", true, List.of(), List.of(), "pvzce:plant_team"));
                await(host, peers, () -> !guest.room.levelId().isEmpty());
                guest.connection.send(new RoomC2S("ready", "", ""));
                await(host, peers, () -> guest.room.ready());
                host.send(new RoomC2S("start", "", ""));
                await(host, peers, () -> guest.init != null);
                host.send(new CommandC2S("/tick freeze"));
                host.send(new CommandC2S("/resource spawn pvzce:sun 4 2"));
                host.send(new CommandC2S("/tick step 1"));
                await(host, peers, () -> host.server().level().entities().stream().anyMatch(e -> e instanceof com.pvzce.server.entity.ResourceDropEntity));
                var drop = host.server().level().entities().stream().filter(e -> e instanceof com.pvzce.server.entity.ResourceDropEntity).findFirst().orElseThrow();
                host.send(new CollectResourceC2S(drop.id()));
                host.send(new CommandC2S("/tick step 1"));
                await(host, peers, () -> guest.packets.stream().anyMatch(p -> p instanceof GameStateS2C g && g.state().equals(GameStateS2C.LOST)));
                assertTrue(host.packets().stream().anyMatch(p -> p instanceof GameStateS2C g
                        && g.state().equals(GameStateS2C.WON) && g.winTeamId().equals("pvzce:plant_team")));
            }
        }
    }

    @Test void coopAdmitsFourPlayersAndRejectsAnAdditionalSeat(@TempDir Path dir) throws Exception {
        try (var host = ServerHarness.createWithWorld(dir, "world", true)) {
            host.send(new RoomC2S("open", "0", "coop")); host.awaitPacket(RoomS2C.class, 15_000);
            int port = host.server().multiplayerPort();
            try (var one = new Peer(port, ""); var two = new Peer(port, "");
                 var three = new Peer(port, "")) {
                var peers = List.of(one, two, three);
                await(host, peers, () -> three.room != null && one.room != null && two.room != null && three.room.players().size() == 4);
                try (var extra = new Peer(port, "")) {
                    await(host, List.of(extra), () -> extra.room != null && extra.room.state().equals("error"));
                    assertTrue(extra.room.message().contains("已满"));
                    assertTrue(host.server().thread().isAlive());
                }
            }
        }
    }

    @Test void joiningSnapshotDoesNotConsumeDirtyUpdatesOrReplaceBroadcastRoute(@TempDir Path dir) throws Exception {
        try (var host = ServerHarness.createWithWorld(dir, "world", true)) {
            var def = com.pvzce.common.core.BuiltInRegistries.LEVELS.get(com.pvzce.api.util.Identifier.parse(LEVEL));
            var level = new com.pvzce.server.level.LevelServer(def);
            List<PvzcePacket> broadcast = new ArrayList<>(), joiner = new ArrayList<>();
            var plant = level.spawnPlant(com.pvzce.common.core.BuiltInRegistries.PLANTS.get(PvzceIds.id("pea_shooter")),
                    level.team(PvzceIds.PLANT_TEAM), 0, 0);
            level.tick(broadcast::add);
            plant.attributes().get(com.pvzce.common.entity.EntityAttributes.MAX_HEALTH).setBaseValue(350);
            level.sendFullState(joiner::add);
            assertTrue(plant.attributes().dirty());
            assertTrue(joiner.stream().noneMatch(p -> p instanceof EffectEventS2C), "Snapshots must not replay entry events");
            broadcast.clear();
            level.tick(broadcast::add);
            assertTrue(broadcast.stream().anyMatch(p -> p instanceof EntityAttributesS2C a && a.entityId() == plant.id()));
            int before = joiner.size();
            level.send(new ServerMessageS2C("still broadcast"));
            assertEquals(before, joiner.size());
            assertTrue(broadcast.stream().anyMatch(p -> p instanceof ServerMessageS2C m && m.message().equals("still broadcast")));
            level.shutdown();
        }
    }

}
