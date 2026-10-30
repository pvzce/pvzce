package com.pvzce.common.network;

import com.pvzce.common.PvzceConstants;
import com.pvzce.common.network.packet.ChatC2S;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TcpPacketTransportTest {
    @Test void differentRegisteredPacketsAreRejectedBeforeAnyFrameIsDecoded() throws Exception {
        try (var listener = new ServerSocket(0); var raw = new Socket("127.0.0.1", listener.getLocalPort());
             var connection = TcpPacketTransport.connection(listener.accept(), ConnectionDirection.SERVERBOUND)) {
            var output = new DataOutputStream(raw.getOutputStream());
            output.writeInt(0x50565A43); output.writeUTF(ContentSnapshot.digest("a different packet registry")); output.flush();
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (connection.isConnected() && System.nanoTime() < deadline) { connection.tick(); Thread.sleep(1); }
            assertFalse(connection.isConnected());
            assertTrue(connection.disconnectReason().contains("注册的网络包不一致"), connection.disconnectReason());
        }
    }

    @Test void fragmentedFramesAreReassembledAndOversizeFramesDisconnect() throws Exception {
        PvzcePackets.register();
        try (var listener = new ServerSocket(0); var raw = new Socket("127.0.0.1", listener.getLocalPort());
             var connection = TcpPacketTransport.connection(listener.accept(), ConnectionDirection.SERVERBOUND)) {
            var output = new DataOutputStream(raw.getOutputStream());
            output.writeInt(0x50565A43); output.writeUTF(PacketRegistry.fingerprint()); output.flush();
            List<PvzcePacket> received = new ArrayList<>(); connection.setListener(received::add);
            var frame = io.netty.buffer.Unpooled.buffer();
            try {
                PacketByteBuf buffer = new PacketByteBuf(frame);
                buffer.writeVarInt(PacketRegistry.id(new ChatC2S("hello")));
                new ChatC2S("hello").encode(buffer);
                output.writeInt(frame.readableBytes()); output.flush();
                while (frame.isReadable()) { output.writeByte(frame.readByte()); output.flush(); }
            } finally { frame.release(); }
            long until = System.nanoTime() + 2_000_000_000L;
            while (received.isEmpty() && System.nanoTime() < until) { connection.tick(); Thread.sleep(1); }
            assertEquals(List.of(new ChatC2S("hello")), received);
            output.writeInt(PvzceConstants.NETWORK_MAX_FRAME_BYTES + 1); output.flush();
            until = System.nanoTime() + 2_000_000_000L;
            while (connection.isConnected() && System.nanoTime() < until) { connection.tick(); Thread.sleep(1); }
            assertFalse(connection.isConnected());
            assertTrue(connection.disconnectReason().contains("长度无效"));
        }
    }
}
