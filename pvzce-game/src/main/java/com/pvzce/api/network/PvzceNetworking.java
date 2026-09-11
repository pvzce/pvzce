package com.pvzce.api.network;

import com.pvzce.common.network.Connection;
import com.pvzce.common.network.PacketListener;
import com.pvzce.common.network.PvzcePacket;

/** Minimal networking API facade (Fabric-networking-shaped, self-written). */
public final class PvzceNetworking {
    private PvzceNetworking() {
    }

    /** Sends a packet through a connection (client or server side). */
    public static void send(Connection connection, PvzcePacket packet) {
        connection.send(packet);
    }

    /** Registers the packet listener for one side of a connection. */
    public static void setReceiver(Connection connection, PacketListener listener) {
        connection.setListener(listener);
    }
}
