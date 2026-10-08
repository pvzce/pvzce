package com.pvzce.common.level;

import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;

import java.util.List;

/** An immutable client mirror. The mechanic declares its wire format here once. */
public record FusionState(List<Count> inventory, List<Count> tray, List<Drop> drops,
                          int tutorialStep, int price, String message, boolean success) {
    public FusionState {
        inventory = List.copyOf(inventory); tray = List.copyOf(tray); drops = List.copyOf(drops);
    }
    public record Count(String ability, int amount) {
        public static final PacketStruct.Codec<Count> CODEC = PacketStruct.<Count>builder()
                .field(Count::ability, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(Count::amount, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                .build(v -> new Count((String) v.get(0), (Integer) v.get(1)));
    }
    public record Drop(int id, String ability, float x, float y) {
        public static final PacketStruct.Codec<Drop> CODEC = PacketStruct.<Drop>builder()
                .field(Drop::id, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                .field(Drop::ability, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(Drop::x, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
                .field(Drop::y, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
                .build(v -> new Drop((Integer) v.get(0), (String) v.get(1), (Float) v.get(2), (Float) v.get(3)));
    }
    public static final PacketStruct.Codec<FusionState> CODEC = PacketStruct.<FusionState>builder()
            .field(FusionState::inventory, (b, v) -> b.writeList(v, Count.CODEC::encode), b -> b.readList(Count.CODEC::decode))
            .field(FusionState::tray, (b, v) -> b.writeList(v, Count.CODEC::encode), b -> b.readList(Count.CODEC::decode))
            .field(FusionState::drops, (b, v) -> b.writeList(v, Drop.CODEC::encode), b -> b.readList(Drop.CODEC::decode))
            .field(FusionState::tutorialStep, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(FusionState::price, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(FusionState::message, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(FusionState::success, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
            .build(v -> new FusionState(counts(v.get(0)), counts(v.get(1)), castDrops(v.get(2)),
                    (Integer) v.get(3), (Integer) v.get(4), (String) v.get(5), (Boolean) v.get(6)));
    @SuppressWarnings("unchecked") private static List<Count> counts(Object v) { return (List<Count>) v; }
    @SuppressWarnings("unchecked") private static List<Drop> castDrops(Object v) { return (List<Drop>) v; }
}
