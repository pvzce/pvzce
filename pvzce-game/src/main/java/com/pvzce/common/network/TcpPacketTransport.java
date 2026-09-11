package com.pvzce.common.network;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;

/**
 * Length-prefixed TCP transport for LAN play.
 *
 * <p>{@link Connection} treats a transport as a byte pipe, so swapping the memory
 * pipe for TCP keeps the protocol unchanged. Three things this class must not do,
 * and used to:
 *
 * <ul>
 *   <li><b>block.</b> {@code poll()} is called from the render loop (client) and
 *       the tick loop (server). The old version fell through to a blocking
 *       {@code read} whenever a frame was partially buffered, freezing the whole
 *       game until the peer sent more bytes. It now returns {@code null} when no
 *       bytes are available.</li>
 *   <li><b>trust the frame length.</b> A negative or oversized prefix made
 *       {@code Arrays.copyOfRange} throw, and a huge one allocated unbounded
 *       memory. Lengths are validated against {@link #MAX_FRAME_BYTES}.</li>
 *   <li><b>leak the socket.</b> {@code close()} existed but nothing called it;
 *       {@link Connection} now closes its transports on disconnect.</li>
 * </ul>
 */
public final class TcpPacketTransport implements PacketTransport, AutoCloseable {
    /** 16 MiB is far above any legitimate PVZCE packet and bounds a hostile peer. */
    public static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;
    private static final int LENGTH_PREFIX_BYTES = 4;

    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;
    private byte[] pending = new byte[0];
    private boolean closed;

    public TcpPacketTransport(Socket socket) throws IOException {
        this.socket = socket;
        this.input = socket.getInputStream();
        this.output = socket.getOutputStream();
    }

    public static TcpPacketTransport accept(ServerSocket serverSocket) throws IOException {
        return new TcpPacketTransport(serverSocket.accept());
    }

    @Override
    public synchronized void send(byte[] data) {
        if (closed) {
            throw new IllegalStateException("TCP transport is closed");
        }
        if (data.length > MAX_FRAME_BYTES) {
            throw new IllegalArgumentException("Frame too large: " + data.length);
        }
        try {
            writeInt(output, data.length);
            output.write(data);
            output.flush();
        } catch (IOException e) {
            throw new IllegalStateException("TCP send failed", e);
        }
    }

    /**
     * Returns the next complete frame, or {@code null} when none is buffered yet.
     * Never blocks on the caller's thread.
     */
    @Override
    public synchronized byte[] poll() {
        if (closed) {
            return null;
        }
        try {
            drainAvailable();
            if (pending.length < LENGTH_PREFIX_BYTES) {
                return null;
            }
            int length = readInt(pending, 0);
            if (length < 0 || length > MAX_FRAME_BYTES) {
                throw new IllegalStateException("Invalid TCP frame length: " + length);
            }
            if (pending.length < LENGTH_PREFIX_BYTES + length) {
                return null;
            }
            byte[] frame = Arrays.copyOfRange(pending, LENGTH_PREFIX_BYTES, LENGTH_PREFIX_BYTES + length);
            pending = Arrays.copyOfRange(pending, LENGTH_PREFIX_BYTES + length, pending.length);
            return frame;
        } catch (IOException e) {
            throw new IllegalStateException("TCP poll failed", e);
        }
    }

    /** Appends whatever the socket already has; never waits for more. */
    private void drainAvailable() throws IOException {
        while (input.available() > 0) {
            byte[] buffer = new byte[Math.min(input.available(), 64 * 1024)];
            int read = input.read(buffer);
            if (read < 0) {
                throw new EOFException("TCP stream closed");
            }
            if (read == 0) {
                return;
            }
            pending = concat(pending, Arrays.copyOf(buffer, read));
        }
    }

    private static void writeInt(OutputStream out, int value) throws IOException {
        out.write((value >>> 24) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        pending = new byte[0];
        socket.close();
    }
}
