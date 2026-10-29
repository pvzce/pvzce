package com.pvzce.common.network;

import com.pvzce.common.PvzceConstants;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** LAN announcements carry public room metadata; joining still uses the normal TCP handshake. */
public final class LanDiscovery {
    private LanDiscovery() { }

    public record Room(String name, String address, int players, int capacity, boolean versus,
                       boolean playing, int protocol, String instanceId) {
        public boolean joinable() { return protocol == PvzcePackets.PROTOCOL_VERSION && players < capacity && !(versus && playing); }
    }
    public record Announcement(String name, int port, int players, int capacity, boolean versus, boolean playing) { }

    static byte[] encode(String instanceId, Announcement room) {
        return ("PVZCE_LAN\n" + PvzcePackets.PROTOCOL_VERSION + "\n" + room.port() + "\n"
                + room.players() + "\n" + room.capacity() + "\n" + (room.versus() ? "versus" : "coop") + "\n"
                + (room.playing() ? "playing" : "lobby") + "\n" + room.name().replaceAll("[\\p{Cntrl}]", " ") + "\n" + instanceId).getBytes(StandardCharsets.UTF_8);
    }

    static Room decode(byte[] bytes, InetAddress source) {
        if (bytes.length > PvzceConstants.LAN_MAX_BYTES || bytes.length == 0) return null;
        String[] parts = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        if (parts.length != 9 || !parts[0].equals("PVZCE_LAN") || parts[7].isBlank() || parts[7].length() > 64
                || parts[7].chars().anyMatch(Character::isISOControl)
                || !Set.of("versus", "coop").contains(parts[5]) || !Set.of("playing", "lobby").contains(parts[6])) return null;
        try {
            if (parts[8].length() != 36) return null;
            String instanceId = UUID.fromString(parts[8]).toString();
            int port = Integer.parseInt(parts[2]), players = Integer.parseInt(parts[3]), capacity = Integer.parseInt(parts[4]);
            boolean versus = parts[5].equals("versus");
            if (players < 1 || players > capacity || capacity != (versus ? 2 : PvzceConstants.MULTIPLAYER_MAX_PLAYERS)) return null;
            return new Room(parts[7], new ServerAddress(source.getHostAddress(), port).toString(), players, capacity,
                    versus, parts[6].equals("playing"), Integer.parseInt(parts[1]), instanceId);
        } catch (IllegalArgumentException e) { return null; }
    }

    private static List<NetworkInterface> interfaces() throws SocketException {
        List<NetworkInterface> result = new ArrayList<>();
        for (var iterator = NetworkInterface.getNetworkInterfaces(); iterator.hasMoreElements();) {
            var network = iterator.nextElement();
            if (network.isUp() && network.supportsMulticast() && network.inetAddresses().anyMatch(a -> a instanceof Inet4Address)) result.add(network);
        }
        return result;
    }

    public static final class Advertiser implements AutoCloseable {
        private volatile Announcement room;
        private final String instanceId = UUID.randomUUID().toString();
        private volatile boolean running = true;
        private volatile MulticastSocket socket;
        private final Thread worker;
        public Advertiser(Announcement room) {
            this.room = room;
            worker = new Thread(this::run, "PvzceLanAdvertise"); worker.setDaemon(true); worker.start();
        }
        public void update(Announcement room) { this.room = room; }
        private void run() {
            try (var sending = new MulticastSocket()) {
                socket = sending;
                sending.setTimeToLive(1);
                var group = InetAddress.getByName(PvzceConstants.LAN_GROUP);
                while (running) {
                    byte[] bytes = encode(instanceId, room);
                    for (var network : interfaces()) {
                        if (!running) break;
                        sending.setNetworkInterface(network);
                        sending.send(new DatagramPacket(bytes, bytes.length, group, PvzceConstants.LAN_PORT));
                    }
                    Thread.sleep(PvzceConstants.LAN_ADVERTISE_MILLIS);
                }
            } catch (IOException e) {
                if (running) org.slf4j.LoggerFactory.getLogger("PVZCE/LAN").warn("局域网广播失败，地址直连仍可用：{}", e.getMessage());
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        @Override public void close() { running = false; worker.interrupt(); if (socket != null) socket.close(); }
    }

    public static final class Browser implements AutoCloseable {
        private record Seen(Room room, long seenNanos) { }
        private final Map<String, Seen> discovered = new ConcurrentHashMap<>();
        private volatile boolean running = true;
        private volatile String status = "正在扫描局域网…";
        private volatile MulticastSocket socket;
        private final int port;
        private final Thread worker;
        public Browser() { this(PvzceConstants.LAN_PORT); }
        Browser(int port) {
            this.port = port;
            worker = new Thread(this::run, "PvzceLanBrowse"); worker.setDaemon(true); worker.start();
        }
        private void run() {
            try (var receiving = new MulticastSocket(null)) {
                socket = receiving;
                receiving.setReuseAddress(true);
                receiving.bind(new InetSocketAddress(port));
                receiving.setSoTimeout(PvzceConstants.LAN_ADVERTISE_MILLIS);
                var group = new InetSocketAddress(InetAddress.getByName(PvzceConstants.LAN_GROUP), PvzceConstants.LAN_PORT);
                int joined = 0;
                for (var network : interfaces()) {
                    try { receiving.joinGroup(group, network); joined++; }
                    catch (IOException e) { /* An unavailable adapter does not hide rooms on another adapter. */ }
                }
                status = joined > 0 ? "正在扫描局域网…" : "没有可用的组播网卡，可使用直接连接";
                while (running) {
                    byte[] bytes = new byte[PvzceConstants.LAN_MAX_BYTES + 1];
                    var packet = new DatagramPacket(bytes, bytes.length);
                    try { receiving.receive(packet); }
                    catch (SocketTimeoutException e) { continue; }
                    accept(Arrays.copyOf(bytes, packet.getLength()), packet.getAddress(), System.nanoTime());
                }
            } catch (IOException e) {
                if (running) status = "局域网扫描不可用，可使用直接连接";
            }
        }
        void accept(byte[] bytes, InetAddress source, long now) {
            if (!running) return;
            Room room = decode(bytes, source);
            if (room == null) return;
            expire(now);
            Seen existing = discovered.get(room.instanceId());
            // Keep the first reachable address stable when a host advertises on several adapters.
            if (existing != null) room = new Room(room.name(), existing.room().address(), room.players(), room.capacity(),
                    room.versus(), room.playing(), room.protocol(), room.instanceId());
            if (existing != null || discovered.size() < PvzceConstants.LAN_MAX_ROOMS)
                discovered.put(room.instanceId(), new Seen(room, now));
        }
        private void expire(long now) {
            discovered.values().removeIf(seen -> now - seen.seenNanos() > PvzceConstants.LAN_EXPIRY_MILLIS * 1_000_000L);
        }
        public List<Room> rooms() {
            expire(System.nanoTime());
            return discovered.values().stream().map(Seen::room).sorted(Comparator.comparing(Room::name).thenComparing(Room::address)).toList();
        }
        public String status() { return status; }
        int boundPort() { return socket == null ? -1 : socket.getLocalPort(); }
        @Override public void close() { running = false; worker.interrupt(); if (socket != null) socket.close(); discovered.clear(); }
    }
}
