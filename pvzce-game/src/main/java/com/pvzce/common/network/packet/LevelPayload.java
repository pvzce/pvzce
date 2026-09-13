package com.pvzce.common.network.packet;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;

import java.util.List;
import java.util.Optional;

/**
 * The board description both level packets carry: size, scene grid, the seed pool metadata
 * the chooser needs, and the level's mechanics.
 *
 * <p>{@link LevelInitS2C} and {@link LevelListS2C.LevelInfo} each hand-wrote the same
 * thirteen encode/decode steps, so adding one field to a level meant editing two packets,
 * two listeners and both screens - and any one of those could be missed. One record, one
 * field list.
 *
 * <p>Mechanics travel as their own JSON blocks ({@link MechanicPayload}), encoded by the
 * mechanic's codec on the server and decoded by the same codec on the client. They used to
 * be three hand-written field groups here ({@code conveyor}, {@code beltCapacity} and the
 * four plantable-area bounds), so every new mechanic meant a field in this record, a field
 * in {@code ClientLevel} and a branch in the HUD. Now the payload has one list and does not
 * change again.
 */
public record LevelPayload(int width, int height, List<SeedOption> seedPool, int maxSeedSlots,
                           List<String> previewZombies, List<SceneSyncS2C.Cell> sceneCells,
                           List<String> lockedSlots, List<MechanicPayload> mechanics) {
    public LevelPayload {
        seedPool = List.copyOf(seedPool);
        previewZombies = List.copyOf(previewZombies);
        sceneCells = List.copyOf(sceneCells);
        lockedSlots = List.copyOf(lockedSlots);
        mechanics = List.copyOf(mechanics);
    }

    /**
     * One mechanic's data block, as the server has it.
     *
     * <p>Sent rather than read from the client's own data packs: "which rules is the server
     * actually running" is server state, and a modified or older client pack must not be
     * able to draw a red line where the server does not enforce one.
     */
    public record MechanicPayload(Identifier type, String block) {
        public static final PacketStruct.Codec<MechanicPayload> CODEC =
                PacketStruct.<MechanicPayload>builder()
                        .field(MechanicPayload::type, PacketByteBuf::writeIdentifier,
                                PacketByteBuf::readIdentifierOrNull)
                        .field(MechanicPayload::block, PacketByteBuf::writeString, PacketByteBuf::readString)
                        .build(values -> new MechanicPayload((Identifier) values.get(0), (String) values.get(1)));

        public void encode(PacketByteBuf buf) {
            CODEC.encode(this, buf);
        }

        public static MechanicPayload decode(PacketByteBuf buf) {
            MechanicPayload payload = CODEC.decode(buf);
            if (payload.type() == null) {
                throw new PacketByteBuf.DecoderException("Mechanic payload without a type id");
            }
            return payload;
        }
    }

    /** The block of one mechanic, or empty when this level does not declare it. */
    public Optional<String> mechanicBlock(Identifier type) {
        for (MechanicPayload payload : mechanics) {
            if (payload.type().equals(type)) {
                return Optional.of(payload.block());
            }
        }
        return Optional.empty();
    }

    public static final PacketStruct.Codec<LevelPayload> CODEC = PacketStruct.<LevelPayload>builder()
            .field(LevelPayload::width, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(LevelPayload::height, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .list(LevelPayload::seedPool, SeedOption::encode, SeedOption::decode)
            .field(LevelPayload::maxSeedSlots, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .stringList(LevelPayload::previewZombies)
            .list(LevelPayload::sceneCells, SceneSyncS2C.Cell::encode, SceneSyncS2C.Cell::decode)
            .stringList(LevelPayload::lockedSlots)
            .list(LevelPayload::mechanics, MechanicPayload::encode, MechanicPayload::decode)
            .build(values -> new LevelPayload((Integer) values.get(0), (Integer) values.get(1),
                    (List<SeedOption>) values.get(2), (Integer) values.get(3),
                    (List<String>) values.get(4), (List<SceneSyncS2C.Cell>) values.get(5),
                    (List<String>) values.get(6), (List<MechanicPayload>) values.get(7)));

    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static LevelPayload decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
