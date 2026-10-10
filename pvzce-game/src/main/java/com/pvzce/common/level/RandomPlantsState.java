package com.pvzce.common.level;

import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import java.util.List;

/** Read-only recipes sent by the server; the client never rolls abilities. */
public record RandomPlantsState(long seed, List<Card> cards) {
    public RandomPlantsState { cards = List.copyOf(cards); }
    public record Card(String plant, List<String> abilities, String shotKind, String shot,
                       String productKind, String product, float speed) {
        public Card { abilities = List.copyOf(abilities); }
        public static final PacketStruct.Codec<Card> CODEC = PacketStruct.<Card>builder()
                .field(Card::plant, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(Card::abilities, PacketByteBuf::writeStringList, PacketByteBuf::readStringList)
                .field(Card::shotKind, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(Card::shot, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(Card::productKind, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(Card::product, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(Card::speed, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
                .build(v -> new Card((String) v.get(0), strings(v.get(1)), (String) v.get(2),
                        (String) v.get(3), (String) v.get(4), (String) v.get(5), (Float) v.get(6)));
    }
    public static final PacketStruct.Codec<RandomPlantsState> CODEC = PacketStruct.<RandomPlantsState>builder()
            .field(RandomPlantsState::seed, PacketByteBuf::writeLong, PacketByteBuf::readLong)
            .field(RandomPlantsState::cards, (b, v) -> b.writeList(v, Card.CODEC::encode),
                    b -> b.readList(Card.CODEC::decode))
            .build(v -> new RandomPlantsState((Long) v.get(0), castCards(v.get(1))));
    @SuppressWarnings("unchecked") private static List<String> strings(Object value) { return (List<String>) value; }
    @SuppressWarnings("unchecked") private static List<Card> castCards(Object value) { return (List<Card>) value; }
}
