package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

import java.util.List;

/**
 * The level select screen's tab table: which themes exist, and which categories each
 * theme offers, in order.
 *
 * <p>Sent by the server because the tabs are a property of the content, not of the
 * screen: themes and categories are data-driven registries, and a client that collected
 * its own tabs from the level list would (a) be unable to show a theme whose levels all
 * live in a mod's pack it parsed differently and (b) disagree with the server the moment
 * the two sides' packs differ. Deriving the table server-side also keeps "which page does
 * this level belong to" a single decision - the client only renders what it is told.
 *
 * <p>An empty table is not an error: the client keeps the levels' own theme/category ids
 * and falls back to a single unclassified page.
 */
public record LevelTabsS2C(List<Tab> tabs) implements PvzcePacket {
    /**
     * One page: a theme plus one category it offers.
     *
     * @param theme    the theme id, or {@link com.pvzce.api.util.LevelGrouping#UNCATEGORIZED}
     * @param category the category id, or the same sentinel
     */
    public record Tab(String theme, String category) {
        public static final PacketStruct.Codec<Tab> CODEC = PacketStruct.<Tab>builder()
                .field(Tab::theme, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(Tab::category, PacketByteBuf::writeString, PacketByteBuf::readString)
                .build(values -> new Tab((String) values.get(0), (String) values.get(1)));

        public void encode(PacketByteBuf buf) {
            CODEC.encode(this, buf);
        }

        public static Tab decode(PacketByteBuf buf) {
            return CODEC.decode(buf);
        }
    }

    public static final PacketStruct.Codec<LevelTabsS2C> CODEC = PacketStruct.<LevelTabsS2C>builder()
            .list(LevelTabsS2C::tabs, Tab::encode, Tab::decode)
            .build(values -> new LevelTabsS2C((List<Tab>) values.get(0)));

    public LevelTabsS2C {
        tabs = tabs == null ? List.of() : List.copyOf(tabs);
    }

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static LevelTabsS2C decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
