package com.pvzce.common.network;

/** Wire-level byte transport. Phase 1: memory pipes; phase 2+: TCP. */
public interface PacketTransport {
    void send(byte[] data);

    byte[] poll();
}
