package com.pvzce.client.mechanic;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientLevel;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.hud.cardbar.CardBarLayout;
import com.pvzce.client.gui.screens.InGameScreen;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.level.RandomPlantsState;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.packet.SlotInfo;
import java.util.List;
import java.util.Locale;

/** The selected seed packet explains its actual abilities and outputs for this run. */
public final class RandomPlantsClientMechanic implements ClientMechanic {
    @Override public Identifier id() { return PvzceIds.MECHANIC_RANDOM_PLANTS; }
    @Override public void applySync(ClientLevel level, PacketByteBuf payload) {
        level.setMechanicState(id(), RandomPlantsState.CODEC.decode(payload));
    }

    @Override public void renderHud(PvzceClient client) {
        if (!(client.currentScreen() instanceof InGameScreen screen)) return;
        SlotInfo selected = client.level().slots().stream().filter(slot -> slot.index() == screen.selectedCardIndex())
                .findFirst().orElse(null);
        if (selected == null) return;
        float x = CardBarLayout.BANK_WIDTH
                + CardBarLayout.MARGIN * 2F
                + CardBarLayout.GAP;
        float y = client.guiHeight() - CardBarLayout.BANK_HEIGHT
                - CardBarLayout.MARGIN;
        drawRecipe(client, selected, x, y, 0F);
    }

    public static void drawRecipe(PvzceClient client, SlotInfo slot, float cardX, float cardY, float cardHeight) {
        RandomPlantsState state = client.level().mechanicStateOrNull(
                PvzceIds.MECHANIC_RANDOM_PLANTS, RandomPlantsState.class);
        if (state == null) return;
        RandomPlantsState.Card card = state.cards().stream().filter(c -> c.plant().equals(slot.defId()))
                .findFirst().orElse(null);
        if (card == null) return;
        String abilities = String.join(" / ", card.abilities().stream()
                .map(id -> GuiLang.name("plant_capability", Identifier.tryParse(id))).toList());
        List<String> lines = List.of(
                GuiLang.name("plant", Identifier.tryParse(card.plant())) + " · "
                        + String.format(GuiLang.raw("gui.pvzce.random_plants.seed", "Seed %d"), state.seed()),
                abilities,
                String.format(Locale.ROOT, GuiLang.raw("gui.pvzce.random_plants.shot", "Shot: %s · %.1f cells/s"),
                        GuiLang.name(card.shotKind(), Identifier.tryParse(card.shot())), card.speed()),
                String.format(GuiLang.raw("gui.pvzce.random_plants.product", "Produces: %s"),
                        GuiLang.name(card.productKind(), Identifier.tryParse(card.product()))));
        float scale = 0.8F;
        float width = (float) lines.stream().mapToDouble(s -> client.fonts().body().width(s, scale)).max().orElse(0) + 16F;
        float height = 60F;
        float x = Math.max(4F, Math.min(cardX, client.guiWidth() - width - 4F));
        float preferredY = cardY > client.guiHeight() / 2F ? cardY - height - 6F : cardY + cardHeight + 6F;
        float y = Math.max(4F, Math.min(preferredY, client.guiHeight() - height - 4F));
        client.drawSolid(x, y, width, height, 8F, 0.05F, 0.12F, 0.08F, 0.93F);
        for (int i = 0; i < lines.size(); i++)
            client.fonts().body().draw(lines.get(i), x + 8F, y + height - 14F - i * 13F,
                    scale, 1F, 0.95F, 0.75F, 1F);
    }
}
