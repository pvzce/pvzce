package com.pvzce.common.network;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Global packet id registry.
 *
 * <p>Ids are <em>explicit</em>, not registration order. They used to be assigned
 * as {@code DECODERS.size()} while {@code PvzcePackets} registered 34 classes in a
 * hand-maintained list, so inserting or reordering one line silently renumbered
 * every later packet: two builds of the "same" protocol would decode each other's
 * packets as the wrong types instead of failing. Registration now also rejects
 * duplicate ids and duplicate classes loudly instead of ignoring the second call.
 */
public final class PacketRegistry {
    /** One byte of id at the head of every frame. */
    public static final int MAX_PACKETS = 256;

    private record Entry(Class<? extends PvzcePacket> type, ConnectionDirection direction,
                         Function<PacketByteBuf, ? extends PvzcePacket> decoder) {
    }

    private static final Map<Integer, Entry> BY_ID = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Integer> BY_TYPE = new ConcurrentHashMap<>();

    private PacketRegistry() {
    }

    public static synchronized <T extends PvzcePacket> void register(int id, Class<T> type,
                                                                    ConnectionDirection direction,
                                                                    Function<PacketByteBuf, T> decoder) {
        if (id < 0 || id >= MAX_PACKETS) {
            throw new IllegalArgumentException("Packet id out of range: " + id);
        }
        Entry existing = BY_ID.get(id);
        if (existing != null) {
            throw new IllegalStateException("Packet id " + id + " is already used by "
                    + existing.type().getName() + ", cannot also register " + type.getName());
        }
        Integer existingId = BY_TYPE.get(type);
        if (existingId != null) {
            throw new IllegalStateException("Packet " + type.getName()
                    + " is already registered as id " + existingId);
        }
        BY_ID.put(id, new Entry(type, direction, decoder));
        BY_TYPE.put(type, id);
    }

    /** The wire id of a packet instance. */
    public static int id(PvzcePacket packet) {
        Integer id = BY_TYPE.get(packet.getClass());
        if (id == null) {
            throw new IllegalArgumentException("Unregistered packet type " + packet.getClass().getName());
        }
        return id;
    }

    /** Decodes a frame received on a connection that expects {@code direction}. */
    public static PvzcePacket decode(ConnectionDirection direction, int id, PacketByteBuf buf) {
        Entry entry = BY_ID.get(id);
        if (entry == null) {
            throw new PacketByteBuf.DecoderException("Unknown packet id " + id
                    + " (protocol mismatch between client and server?)");
        }
        if (entry.direction() != direction) {
            throw new PacketByteBuf.DecoderException("Packet " + id + " (" + entry.type().getSimpleName()
                    + ") is " + entry.direction() + " but this connection expects " + direction);
        }
        return entry.decoder().apply(buf);
    }

    /** How many ids are in use; the two sides must agree on this for a compatible protocol. */
    public static int size() {
        return BY_ID.size();
    }

    /** Every registered id, ascending (used by diagnostics and tests). */
    public static List<Integer> ids() {
        return BY_ID.keySet().stream().sorted().toList();
    }
}
