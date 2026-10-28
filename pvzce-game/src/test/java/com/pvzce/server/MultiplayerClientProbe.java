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
            String assets = ContentSnapshot.assets(resources);
            boolean[] prepared = {false};
            connection.setListener(packet -> {
                if (packet instanceof ContentS2C content && remote.accept(content, assets)) {
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
            hello.addProperty("mods", ContentSnapshot.mods()); hello.addProperty("assets", assets);
            connection.send(new RoomC2S("hello", hello.toString(), ""));
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (connection.isConnected() && System.nanoTime() < deadline) { connection.tick(); Thread.sleep(2); }
        }
    }
}
