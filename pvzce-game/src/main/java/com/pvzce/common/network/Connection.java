package com.pvzce.common.network;

import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MC-{@code net.minecraft.network.Connection}-shaped connection: encodes packets
 * through a {@link PacketByteBuf}, dispatches decoded packets to a
 * {@link PacketListener}, and ticks its transport every frame.
 *
 * <p>A failed decode or a handler exception ends the connection, but it is now
 * reported with its stack trace and the transports are actually closed. Before,
 * the throwable was swallowed into a one-line reason (an NPE produced the reason
 * "Packet handling failed: null"), transports were never closed, and the client
 * had no disconnect handling at all - it kept rendering a frozen world against a
 * connection that silently discarded every further {@code send}.
 */
public final class Connection implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("PVZCE/Network");
    /** Upper bound on packets handled per tick, so one flooding peer cannot stall the loop. */
    private static final int MAX_PACKETS_PER_TICK = 256;

    private final ConnectionDirection receivingDirection;
    private final PacketTransport sendTransport;
    private final PacketTransport receiveTransport;
    private volatile PacketListener listener;
    private volatile boolean connected = true;
    private volatile String disconnectReason = "";

    public Connection(ConnectionDirection receivingDirection, PacketTransport sendTransport,
                      PacketTransport receiveTransport) {
        this.receivingDirection = receivingDirection;
        this.sendTransport = sendTransport;
        this.receiveTransport = receiveTransport;
    }

    /** Creates an integrated-server client/server connection pair (same process, two threads). */
    public static Pair createMemoryPair() {
        MemoryPacketTransport.Pair c2s = MemoryPacketTransport.createPair();
        MemoryPacketTransport.Pair s2c = MemoryPacketTransport.createPair();
        Connection client = new Connection(ConnectionDirection.CLIENTBOUND, c2s.first(), s2c.second());
        Connection server = new Connection(ConnectionDirection.SERVERBOUND, s2c.first(), c2s.second());
        return new Pair(client, server);
    }

    public record Pair(Connection client, Connection server) {
    }

    public void setListener(PacketListener listener) {
        this.listener = listener;
    }

    /** The direction this connection receives on. */
    public ConnectionDirection receivingDirection() {
        return receivingDirection;
    }

    public boolean isConnected() {
        return connected;
    }

    public String disconnectReason() {
        return disconnectReason;
    }

    public void send(PvzcePacket packet) {
        if (!connected) {
            return;
        }
        if (packet.direction() == receivingDirection) {
            throw new IllegalArgumentException("Cannot send " + packet.direction()
                    + " packet on a " + receivingDirection + " connection");
        }
        int id = PacketRegistry.id(packet);
        var buffer = Unpooled.buffer();
        try {
            PacketByteBuf stream = new PacketByteBuf(buffer);
            stream.writeVarInt(id);
            packet.encode(stream);
            byte[] data = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), data);
            sendTransport.send(data);
        } catch (RuntimeException e) {
            disconnect("Failed to send " + packet.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            buffer.release();
        }
    }

    /** Drains and dispatches pending packets. Call from the owning thread only. */
    public void tick() {
        if (!connected) {
            return;
        }
        int processed = 0;
        while (processed++ < MAX_PACKETS_PER_TICK) {
            byte[] data;
            try {
                data = receiveTransport.poll();
            } catch (RuntimeException e) {
                // poll() used to be called in the while condition, outside this guard,
                // so a transport-level failure escaped tick() and killed the caller.
                disconnect("Transport read failed: " + e.getMessage());
                return;
            }
            if (data == null) {
                return;
            }
            if (!handle(data)) {
                return;
            }
        }
    }

    private boolean handle(byte[] data) {
        var buffer = Unpooled.wrappedBuffer(data);
        try {
            PacketByteBuf stream = new PacketByteBuf(buffer);
            int id = stream.readVarInt();
            PvzcePacket packet = PacketRegistry.decode(receivingDirection, id, stream);
            PacketListener current = listener;
            if (current != null) {
                current.handle(packet);
            }
            return true;
        } catch (Throwable t) {
            LOGGER.error("Failed to handle a {} packet", receivingDirection, t);
            disconnect("Packet handling failed: " + t);
            return false;
        } finally {
            buffer.release();
        }
    }

    public void disconnect(String reason) {
        if (!connected) {
            return;
        }
        connected = false;
        disconnectReason = reason == null ? "" : reason;
        LOGGER.warn("Disconnected ({})", disconnectReason);
        closeTransports();
        PacketListener current = listener;
        if (current != null) {
            try {
                current.onDisconnect(disconnectReason);
            } catch (Throwable t) {
                LOGGER.error("onDisconnect handler failed", t);
            }
        }
    }

    private void closeTransports() {
        for (PacketTransport transport : new PacketTransport[]{sendTransport, receiveTransport}) {
            if (transport instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    LOGGER.debug("Failed to close transport", e);
                }
            }
        }
    }

    @Override
    public void close() {
        disconnect("closed");
    }
}
