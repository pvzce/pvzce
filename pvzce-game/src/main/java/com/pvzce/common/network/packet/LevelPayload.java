package com.pvzce.common.network.packet;

import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;

import java.util.List;

/**
 * The board description both level packets carry: size, scene grid and the seed
 * pool metadata the chooser needs.
 *
 * <p>{@link LevelInitS2C} and {@link LevelListS2C.LevelInfo} each hand-wrote the
 * same thirteen encode/decode steps, so adding one field to a level meant editing
 * two packets, two listeners and both screens - and any one of those could be
 * missed. One record, one field list.
 */
public record LevelPayload(int width, int height, List<SeedOption> seedPool, int maxSeedSlots,
                           List<String> previewZombies, List<SceneSyncS2C.Cell> sceneCells) {
    public LevelPayload {
        seedPool = List.copyOf(seedPool);
        previewZombies = List.copyOf(previewZombies);
        sceneCells = List.copyOf(sceneCells);
    }

    public static final PacketStruct.Codec<LevelPayload> CODEC = PacketStruct.<LevelPayload>builder()
            .field(LevelPayload::width, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(LevelPayload::height, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .list(LevelPayload::seedPool, SeedOption::encode, SeedOption::decode)
            .field(LevelPayload::maxSeedSlots, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .stringList(LevelPayload::previewZombies)
            .list(LevelPayload::sceneCells, SceneSyncS2C.Cell::encode, SceneSyncS2C.Cell::decode)
            .build(values -> new LevelPayload((Integer) values.get(0), (Integer) values.get(1),
                    (List<SeedOption>) values.get(2), (Integer) values.get(3),
                    (List<String>) values.get(4), (List<SceneSyncS2C.Cell>) values.get(5)));

    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static LevelPayload decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
