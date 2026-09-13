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
                           List<String> previewZombies, List<SceneSyncS2C.Cell> sceneCells,
                           List<String> lockedSlots, boolean conveyor, int beltCapacity,
                           int zoneMinX, int zoneMaxX, int zoneMinY, int zoneMaxY) {
    public LevelPayload {
        seedPool = List.copyOf(seedPool);
        previewZombies = List.copyOf(previewZombies);
        sceneCells = List.copyOf(sceneCells);
        lockedSlots = List.copyOf(lockedSlots);
    }

    /** A payload with nothing fixed in the player's bar. */
    public LevelPayload(int width, int height, List<SeedOption> seedPool, int maxSeedSlots,
                        List<String> previewZombies, List<SceneSyncS2C.Cell> sceneCells) {
        this(width, height, seedPool, maxSeedSlots, previewZombies, sceneCells, List.of(),
                false, 0, 0, width - 1, 0, height - 1);
    }

    public LevelPayload(int width, int height, List<SeedOption> seedPool, int maxSeedSlots,
                        List<String> previewZombies, List<SceneSyncS2C.Cell> sceneCells,
                        List<String> lockedSlots) {
        this(width, height, seedPool, maxSeedSlots, previewZombies, sceneCells, lockedSlots,
                false, 0, 0, width - 1, 0, height - 1);
    }

    /**
     * True when the card bar is a conveyor belt.
     *
     * <p>Sent rather than read from the local level definition: the client does load the
     * same data packs, but which bar the server is actually running is server state, and
     * the belt's contents already travel separately.
     */
    public boolean isConveyor() {
        return conveyor;
    }

    /** True when the whole board is plantable, i.e. there is no boundary to draw. */
    public boolean zoneIsWholeBoard() {
        return zoneMinX <= 0 && zoneMinY <= 0 && zoneMaxX >= width - 1 && zoneMaxY >= height - 1;
    }

    public static final PacketStruct.Codec<LevelPayload> CODEC = PacketStruct.<LevelPayload>builder()
            .field(LevelPayload::width, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(LevelPayload::height, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .list(LevelPayload::seedPool, SeedOption::encode, SeedOption::decode)
            .field(LevelPayload::maxSeedSlots, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .stringList(LevelPayload::previewZombies)
            .list(LevelPayload::sceneCells, SceneSyncS2C.Cell::encode, SceneSyncS2C.Cell::decode)
            .stringList(LevelPayload::lockedSlots)
            .field(LevelPayload::conveyor, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
            .field(LevelPayload::beltCapacity, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(LevelPayload::zoneMinX, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(LevelPayload::zoneMaxX, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(LevelPayload::zoneMinY, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(LevelPayload::zoneMaxY, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .build(values -> new LevelPayload((Integer) values.get(0), (Integer) values.get(1),
                    (List<SeedOption>) values.get(2), (Integer) values.get(3),
                    (List<String>) values.get(4), (List<SceneSyncS2C.Cell>) values.get(5),
                    (List<String>) values.get(6), (Boolean) values.get(7), (Integer) values.get(8),
                    (Integer) values.get(9), (Integer) values.get(10), (Integer) values.get(11),
                    (Integer) values.get(12)));

    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static LevelPayload decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
