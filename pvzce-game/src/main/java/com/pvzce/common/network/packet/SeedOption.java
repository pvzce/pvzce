package com.pvzce.common.network.packet;

import com.pvzce.common.network.PacketByteBuf;

/**
 * One card available in a level's seed pool. The server resolves the card
 * definition (kind, backing content, icon, cost) so the client can render the
 * seed chooser without trusting its local registries, which matters once the
 * client runs in a separate process from the server.
 */
public record SeedOption(String slotId, String kind, String content, String icon, int costSun) {
    public static final com.pvzce.common.network.PacketStruct.Codec<SeedOption> CODEC =
            com.pvzce.common.network.PacketStruct.<SeedOption>builder()
                    .field(SeedOption::slotId, PacketByteBuf::writeString, PacketByteBuf::readString)
                    .field(SeedOption::kind, PacketByteBuf::writeString, PacketByteBuf::readString)
                    .field(SeedOption::content, PacketByteBuf::writeString, PacketByteBuf::readString)
                    .field(SeedOption::icon, PacketByteBuf::writeString, PacketByteBuf::readString)
                    .field(SeedOption::costSun, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                    .build(values -> new SeedOption((String) values.get(0), (String) values.get(1),
                            (String) values.get(2), (String) values.get(3), (Integer) values.get(4)));

    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static SeedOption decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
