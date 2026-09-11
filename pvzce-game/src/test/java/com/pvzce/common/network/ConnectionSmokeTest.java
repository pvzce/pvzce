package com.pvzce.common.network;

import com.pvzce.common.network.packet.CommandC2S;
import com.pvzce.common.network.packet.MusicEventS2C;
import com.pvzce.common.network.packet.ServerMessageS2C;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConnectionSmokeTest {
    @Test
    void memoryConnectionEncodesAndDecodesPackets() {
        PvzcePackets.register();
        Connection.Pair pair = Connection.createMemoryPair();

        List<PvzcePacket> serverReceived = new ArrayList<>();
        List<PvzcePacket> clientReceived = new ArrayList<>();
        pair.server().setListener(serverReceived::add);
        pair.client().setListener(clientReceived::add);

        pair.client().send(new CommandC2S("/registries"));
        pair.server().tick();
        assertEquals(1, serverReceived.size());
        assertEquals(CommandC2S.class, serverReceived.get(0).getClass());

        pair.server().send(new ServerMessageS2C("hello"));
        pair.client().tick();
        assertEquals(1, clientReceived.size());
        assertEquals(ServerMessageS2C.class, clientReceived.get(0).getClass());

        pair.server().send(new MusicEventS2C("battle", "pvzce:music/ultimate_battle", true, false, 0.9F, 1.5F));
        pair.client().tick();
        assertEquals(2, clientReceived.size());
        MusicEventS2C music = (MusicEventS2C) clientReceived.get(1);
        assertEquals("battle", music.track());
        assertEquals("pvzce:music/ultimate_battle", music.event());
        assertEquals(0.9F, music.volume(), 0.0001F);
    }
}
