package com.pvzce.common.network;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A full-duplex memory pipe pair for the integrated server. {@code a} sends into
 * {@code b}'s inbox.
 *
 * <p>Closing one end marks the pipe closed for both directions, so a peer that
 * goes away is actually noticed: without it the integrated client could keep
 * "sending" into a queue nobody drained and never learned the server had
 * stopped, because the memory transport had no notion of a closed connection.
 */
public final class MemoryPacketTransport implements PacketTransport, AutoCloseable {
    public record Pair(MemoryPacketTransport first, MemoryPacketTransport second) {
    }

    private final ConcurrentLinkedQueue<byte[]> inbox = new ConcurrentLinkedQueue<>();
    private volatile MemoryPacketTransport peer;
    private volatile boolean closed;

    public static Pair createPair() {
        MemoryPacketTransport a = new MemoryPacketTransport();
        MemoryPacketTransport b = new MemoryPacketTransport();
        a.peer = b;
        b.peer = a;
        return new Pair(a, b);
    }

    @Override
    public void send(byte[] data) {
        MemoryPacketTransport target = peer;
        if (closed || target == null || target.closed) {
            throw new IllegalStateException("Memory transport is closed");
        }
        target.inbox.add(data);
    }

    @Override
    public byte[] poll() {
        return closed ? null : inbox.poll();
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
        inbox.clear();
        MemoryPacketTransport target = peer;
        if (target != null) {
            target.closed = true;
            target.inbox.clear();
        }
    }
}
