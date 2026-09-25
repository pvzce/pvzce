package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * "Buy this shop item."
 *
 * <p>The client names the item and the world; it never names a price. The server re-reads the
 * price from {@code common.shop.ShopItems}, re-checks the wallet and the purchase ceiling from
 * its own copy of the profile, and charges. That is the same shape as buying a level
 * ({@link UnlockLevelC2S}), and it is what makes a modified client unable to buy anything cheaply.
 *
 * <p>{@code worldName} travels with the packet for the same reason it does there: the shop is
 * reached from the title screen, so the server has no current world to fall back on and would
 * otherwise charge whichever world it last defaulted to.
 */
public record BuyShopItemC2S(String itemId, String worldName) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeString(itemId);
        buf.writeString(worldName);
    }

    public static BuyShopItemC2S decode(PacketByteBuf buf) {
        return new BuyShopItemC2S(buf.readString(), buf.readString());
    }
}
