package com.pvzce.common.network;

public interface PacketListener {
    void handle(PvzcePacket packet);

    default void onDisconnect(String reason) {
    }
}
