package com.pvzce.server;

import com.google.gson.JsonObject;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.*;
import com.pvzce.common.network.packet.*;
import com.pvzce.common.resource.PvzceResourceManager;
import java.nio.file.Path;
import java.util.List;

/** Separate JVM peer: it has no integrated server and must obtain its data from the wire. */
public final class MultiplayerClientProbe {
    public static void main(String[] args) throws Exception {
        BuiltInRegistries.bootstrap();
        PvzcePackets.register();
        try (var resources = new PvzceResourceManager(MultiplayerClientProbe.class.getClassLoader());
             var remote = new ContentSnapshot();
             var connection = TcpPacketTransport.connect("127.0.0.1", Integer.parseInt(args[0]))) {
            resources.init(Path.of(args[1]));
            var packs = new SessionPacks.Client(Path.of(args[1]), resources);
            boolean[] prepared = {false};
            connection.setListener(packet -> {
                if (packet instanceof PackManifestS2C manifest) {
                    try { packs.select(manifest, java.util.Set.of(), connection::send); }
                    catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                } else if (packet instanceof PackChunkS2C chunk) {
                    try { packs.accept(chunk, connection::send); }
                    catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                } else if (packet instanceof ContentS2C content) {
                    if (content.index() == 0) {
                        resources.sessionPacks(packs.roots());
                        try {
                            resources.reload();
                            String art = new String(resources.getResource("assets/test/lang/zh_cn.json").orElseThrow().bytes(), java.nio.charset.StandardCharsets.UTF_8);
                            if (!art.equals("host-art")) throw new AssertionError("Host assets were not mounted");
                            System.out.println("PACK_ASSETS_INSTALLED");
                        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                    }
                    if (!remote.accept(content)) return;
                    connection.send(new RoomC2S("content_ready", content.data(), ""));
                    System.out.println("CONTENT_INSTALLED");
                } else if (packet instanceof RoomS2C room && !prepared[0] && !room.levelId().isEmpty()) {
                    prepared[0] = true;
                    connection.send(new PlayLevelC2S(room.levelId(), "untrusted-world", true,
                            List.of("pvzce:sun", "pvzce:pea_shooter", "pvzce:shovel")));
                } else if (packet instanceof LevelInitS2C init) {
                    if (BuiltInRegistries.PLANTS.get(com.pvzce.common.PvzceIds.id("pea_shooter")).cost()
                            .amountOf(com.pvzce.common.PvzceIds.SUN) != 77) throw new AssertionError("Server data was not installed");
                    System.out.println("BATTLE_INITIALIZED " + init.controlledTeamId());
                    int slot = init.slots().stream().filter(s -> s.defId().equals("pvzce:pea_shooter")).findFirst().orElseThrow().index();
                    connection.send(new PlacePlantC2S(slot, 1, 0));
                } else if (packet instanceof EntitySpawnS2C spawn && spawn.defId().equals("pvzce:pea_shooter")) {
                    System.out.println("PLANT_CONFIRMED");
                    connection.send(new RoomC2S("leave", "", ""));
                }
            });
            JsonObject hello = new JsonObject(); hello.addProperty("name", "independent-jvm");
            connection.send(new RoomC2S("hello", hello.toString(), ""));
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (connection.isConnected() && System.nanoTime() < deadline) { connection.tick(); Thread.sleep(2); }
            packs.close();
        }
        // Register a packet only in this JVM: equal game version strings must not hide a different registry.
        String before = PacketRegistry.fingerprint();
        PacketRegistry.register(PacketRegistry.MAX_PACKETS - 1, ExtraPacket.class, ConnectionDirection.CLIENTBOUND,
                buf -> new ExtraPacket(buf.readInt()));
        if (before.equals(PacketRegistry.fingerprint())) throw new AssertionError("Registration did not change the fingerprint");
        try (var incompatible = TcpPacketTransport.connect("127.0.0.1", Integer.parseInt(args[0]))) {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (incompatible.isConnected() && System.nanoTime() < deadline) { incompatible.tick(); Thread.sleep(2); }
            if (incompatible.isConnected() || !incompatible.disconnectReason().contains("注册的网络包不一致"))
                throw new AssertionError("Different packet registry was accepted: " + incompatible.disconnectReason());
            System.out.println("REGISTRY_MISMATCH_REJECTED");
        }
    }
    private record ExtraPacket(int value) implements PvzcePacket {
        @Override public ConnectionDirection direction() { return ConnectionDirection.CLIENTBOUND; }
        @Override public void encode(PacketByteBuf buf) { buf.writeInt(value); }
    }
}
