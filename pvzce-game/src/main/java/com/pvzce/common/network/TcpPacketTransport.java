package com.pvzce.common.network;

import com.pvzce.common.PvzceConstants;
import java.io.*;
import java.net.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Framed, bounded TCP pipe. Workers only move bytes; the owner dispatches packets in tick(). */
public final class TcpPacketTransport implements PacketTransport, AutoCloseable {
    private static final int MAGIC = 0x50565A43;
    private final Socket socket;
    private final LinkedBlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<byte[]> outgoing = new LinkedBlockingQueue<>();
    private final AtomicInteger incomingBytes = new AtomicInteger();
    private final AtomicInteger outgoingBytes = new AtomicInteger();
    private volatile boolean closed;
    private volatile String reason = "连接已关闭";

    public static Connection connect(String host, int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), PvzceConstants.NETWORK_TIMEOUT_MILLIS);
            return connection(socket, ConnectionDirection.CLIENTBOUND);
        } catch (IOException e) { socket.close(); throw e; }
    }

    public static Connection connection(Socket socket, ConnectionDirection direction) throws IOException {
        TcpPacketTransport pipe = new TcpPacketTransport(socket);
        return new Connection(direction, pipe, pipe);
    }

    private TcpPacketTransport(Socket socket) throws IOException {
        this.socket = socket;
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(PvzceConstants.NETWORK_TIMEOUT_MILLIS);
        start("PvzceNetworkRead", this::readLoop);
        start("PvzceNetworkWrite", this::writeLoop);
    }

    private void start(String name, IoTask task) {
        Thread worker = new Thread(() -> {
            try { task.run(); }
            catch (Exception e) { fail(e.getMessage() == null ? e.toString() : e.getMessage()); }
        }, name);
        worker.setDaemon(true);
        worker.start();
    }

    private void readLoop() throws IOException {
        DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        if (input.readInt() != MAGIC) throw new IOException("不是 PVZCE 联机服务器");
        int version = input.readInt();
        if (version != PvzcePackets.PROTOCOL_VERSION)
            throw new IOException("协议版本不一致：对端 " + version + "，本机 " + PvzcePackets.PROTOCOL_VERSION);
        while (!closed) {
            int length = input.readInt();
            if (length == 0) continue; // transport heartbeat
            if (length < 0 || length > PvzceConstants.NETWORK_MAX_FRAME_BYTES)
                throw new IOException("联机数据帧过大或长度无效");
            byte[] frame = new byte[length];
            input.readFully(frame);
            enqueue(incoming, incomingBytes, frame);
        }
    }

    private void writeLoop() throws Exception {
        DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        output.writeInt(MAGIC);
        output.writeInt(PvzcePackets.PROTOCOL_VERSION);
        output.flush();
        while (!closed) {
            byte[] frame = outgoing.poll(PvzceConstants.NETWORK_HEARTBEAT_MILLIS, TimeUnit.MILLISECONDS);
            output.writeInt(frame == null ? 0 : frame.length);
            if (frame != null) {
                outgoingBytes.addAndGet(-frame.length);
                output.write(frame);
            }
            output.flush();
        }
    }

    private void enqueue(LinkedBlockingQueue<byte[]> queue, AtomicInteger count, byte[] data) {
        if (closed) throw new IllegalStateException(reason);
        if (data.length > PvzceConstants.NETWORK_MAX_FRAME_BYTES
                || count.addAndGet(data.length) > PvzceConstants.NETWORK_MAX_QUEUE_BYTES) {
            fail("联机缓冲区已满，连接速度不足");
            throw new IllegalStateException(reason);
        }
        queue.add(data);
    }

    @Override public void send(byte[] data) { enqueue(outgoing, outgoingBytes, data); }
    @Override public byte[] poll() {
        byte[] frame = incoming.poll();
        if (frame != null) incomingBytes.addAndGet(-frame.length);
        else if (closed) throw new IllegalStateException(reason);
        return frame;
    }
    @Override public boolean isClosed() { return closed; }
    private void fail(String reason) { this.reason = reason; close(); }
    @Override public void close() {
        closed = true;
        try { socket.close(); } catch (IOException ignored) { }
    }
    @FunctionalInterface private interface IoTask { void run() throws Exception; }
}
