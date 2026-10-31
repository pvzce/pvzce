package com.pvzce.client.mechanic;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientLevel;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.EntityArt;
import com.pvzce.common.level.mechanic.ZombossMechanic.Status;
import com.pvzce.common.network.PacketByteBuf;

import static com.pvzce.common.PvzceConstants.*;

/** Reads the boss's authoritative snapshot; no client combat or random attack selection. */
public final class ZombossClientMechanic implements ClientMechanic {
    @Override public Identifier id() { return PvzceIds.MECHANIC_ZOMBOSS; }
    @Override public void applySync(ClientLevel level, PacketByteBuf payload) {
        level.setMechanicState(id(), Status.CODEC.decode(payload));
    }
    private static Status status(ClientLevel level) {
        return level.mechanicStateOrNull(PvzceIds.MECHANIC_ZOMBOSS, Status.class);
    }

    @Override public WorldOverlay createWorldOverlay(ClientLevel level) {
        return new WorldOverlay() {
            @Override public void render(PvzceClient client, com.pvzce.client.renderer.PvzceCamera camera) {
                Status s = status(level);
                if (s == null || s.ticksLeft() < ZOMBOSS_STOMP_TICKS / 2) return;
                if (s.phase().equals("rv") || s.phase().equals("stomp")) {
                    float width = s.phase().equals("rv") ? 3F : level.width() - 5F;
                    client.drawSolid(s.column(), s.row(), width, 2F, 0.08F, 1F, 0.2F, 0.1F, 0.25F);
                }
            }
            @Override public void renderOver(PvzceClient client, com.pvzce.client.renderer.PvzceCamera camera) {
                Status s = status(level);
                if (s == null) return;
                if (s.phase().equals("rv")) renderCamper(client, s);
                if (s.ballType() == 0) return;
                float size = ZOMBOSS_BALL_RENDER_SIZE;
                client.drawTexture(s.ballType() == 1 ? EntityArt.ZOMBOSS_FIREBALL : EntityArt.ZOMBOSS_ICEBALL,
                        s.ballX() - size / 2F, s.ballRow() + 0.15F, size, size / client.spriteXScale(),
                        4F, 1F, 1F, 1F, 1F);
            }
        };
    }

    /** The animation file owns the arm; the server's chosen cell owns where its camper lands. */
    private static void renderCamper(PvzceClient client, Status s) {
        float progress = 1F - s.ticksLeft() / (float)ZOMBOSS_RV_TICKS;
        float fall = Math.min(1F, progress / ZOMBOSS_RV_CONTACT_FRACTION);
        float x = s.column() + 1.5F - ZOMBOSS_RV_RENDER_WIDTH / 2F;
        float y = s.row() + 0.25F + (1F - fall * fall) * (client.level().height() + 2F);
        float alpha = progress <= ZOMBOSS_RV_CONTACT_FRACTION ? 1F
                : (1F - progress) / (1F - ZOMBOSS_RV_CONTACT_FRACTION);
        client.drawTexture(EntityArt.ZOMBOSS_RV, x, y, ZOMBOSS_RV_RENDER_WIDTH, ZOMBOSS_RV_RENDER_HEIGHT,
                4F, 1F, 1F, 1F, alpha);
        for (float offset : new float[]{0.35F, 2.15F}) {
            client.drawTexture(EntityArt.ZOMBOSS_RV_WHEEL, x + offset, y - 0.05F,
                    ZOMBOSS_RV_WHEEL_SIZE, ZOMBOSS_RV_WHEEL_SIZE / client.spriteXScale(),
                    4.01F, 1F, 1F, 1F, alpha);
        }
    }

    @Override public void renderHud(PvzceClient client) {
        Status s = status(client.level());
        if (s == null) return;
        float width = Math.min(340F, client.guiWidth() * 0.4F);
        float x = (client.guiWidth() - width) / 2F, y = 15F;
        client.drawSolid(x - 4F, y - 4F, width + 8F, 32F, 8F, 0.08F, 0.06F, 0.08F, 0.9F);
        client.drawSolid(x, y, width, 8F, 8.1F, 0.25F, 0.1F, 0.12F, 1F);
        client.drawSolid(x, y, width * Math.max(0, s.health()) / s.maxHealth(), 8F,
                8.2F, 0.75F, 0.12F, 0.15F, 1F);
        String phase = s.frozen() ? "frozen" : s.phase().startsWith("head_") && !s.phase().equals("head_leave") ? "exposed" : s.phase().startsWith("bungee") ? "bungee" : s.phase();
        String label = GuiLang.raw("gui.pvzce.zomboss.name", "Dr. Zomboss") + "  "
                + s.health() + " / " + s.maxHealth() + "  ·  " + GuiLang.raw("gui.pvzce.zomboss." + phase, phase);
        client.fonts().body().draw(label, x + 4F, y + 15F, 0.75F, 1F, 0.9F, 0.7F, 1F);
    }
}
