package com.pvzce.client.mechanic;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientLevel;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.animation.ArtTarget;
import com.pvzce.client.animation.AnimationPlayback;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.EntityArt;
import com.pvzce.common.level.mechanic.ZombossMechanic.Pilot;
import com.pvzce.common.level.mechanic.ZombossMechanic.PilotArea;
import com.pvzce.common.level.mechanic.ZombossMechanic.PilotMove;
import com.pvzce.common.level.mechanic.ZombossMechanic.Status;
import com.pvzce.common.network.PacketByteBuf;

import java.util.List;

import static com.pvzce.common.PvzceConstants.*;

/**
 * Reads the boss's authoritative snapshot; no client combat or random attack selection.
 *
 * <p>When the zombie side flies the machine, its player also gets the pilot's buttons (bottom left)
 * and both sides read the head's clock on one line above them. A button is greyed by the same rule the
 * server applies ({@link Status#refusal}); the click is still the server's to answer.
 */
public final class ZombossClientMechanic implements ClientMechanic {
    /** The pilot's buttons, left to right; the head button raises the head while it is down. */
    private static final List<PilotMove> PANEL = List.of(PilotMove.LOWER, PilotMove.FIRE, PilotMove.ICE,
            PilotMove.STOMP, PilotMove.RV, PilotMove.BUNGEE);
    private static final float PANEL_LEFT_PX = 14F, PANEL_BOTTOM_PX = 12F;
    private static final float BUTTON_WIDTH_PX = 62F, BUTTON_HEIGHT_PX = 34F, BUTTON_GAP_PX = 6F;
    private static final float LABEL_SCALE = 0.8F, DETAIL_SCALE = 0.62F;

    @Override public Identifier id() { return PvzceIds.MECHANIC_ZOMBOSS; }
    @Override public void applySync(ClientLevel level, PacketByteBuf payload) {
        level.setMechanicState(id(), Status.CODEC.decode(payload));
    }
    private static Status status(ClientLevel level) {
        return level.mechanicStateOrNull(PvzceIds.MECHANIC_ZOMBOSS, Status.class);
    }

    /** True when this player flies the machine: a piloted boss and a seat on the zombie side. */
    private static boolean flying(PvzceClient client, Status s) {
        return s != null && s.pilot().active() && s.health() > 0
                && PvzceIds.ZOMBIE_TEAM.equals(client.level().controlledTeamId());
    }

    @Override
    public void renderHud(PvzceClient client) {
        Status s = status(client.level());
        if (s == null || !s.pilot().active() || s.health() <= 0) {
            return;
        }
        float lineY = PANEL_BOTTOM_PX + 4F;
        if (flying(client, s)) {
            for (int i = 0; i < PANEL.size(); i++) {
                renderButton(client, s, shown(PANEL.get(i), s), buttonLeft(i));
            }
            lineY = PANEL_BOTTOM_PX + BUTTON_HEIGHT_PX + 8F;
        }
        String line = headLine(s);
        float width = client.fonts().body().width(line, LABEL_SCALE);
        client.drawSolid(PANEL_LEFT_PX - 4F, lineY - 5F, width + 8F, 17F, 8F, 0.08F, 0.05F, 0.12F, 0.72F);
        client.fonts().body().draw(line, PANEL_LEFT_PX, lineY, LABEL_SCALE, 1F, 0.88F, 0.55F, 1F);
    }

    /**
     * The pilot's button under a GUI point, or {@code null}.
     *
     * <p>Answers the move the button shows, so a press on the head button raises a lowered head.
     */
    public static PilotMove clickedMove(PvzceClient client, double guiX, double guiY) {
        Status s = status(client.level());
        if (!flying(client, s) || guiY < PANEL_BOTTOM_PX || guiY > PANEL_BOTTOM_PX + BUTTON_HEIGHT_PX) {
            return null;
        }
        for (int i = 0; i < PANEL.size(); i++) {
            float left = buttonLeft(i);
            if (guiX >= left && guiX < left + BUTTON_WIDTH_PX) {
                return shown(PANEL.get(i), s);
            }
        }
        return null;
    }

    /** Why the machine would refuse this move now, as a language key, or {@code null} when it would not. */
    public static String refusal(PvzceClient client, PilotMove move) {
        Status s = status(client.level());
        return s == null ? null : s.refusal(move);
    }

    /** Shades the cells an aimed move would cover if the pilot clicked this cell. */
    public static void renderAim(PvzceClient client, PilotMove move, int cellX, int cellY) {
        PilotArea area = move.area(cellX, cellY, client.level().width(), client.level().height());
        if (area == null) {
            return;
        }
        boolean ice = move == PilotMove.ICE;
        client.drawSolid(area.column(), area.row(), area.columns(), area.rows(), 0.21F,
                ice ? 0.35F : 1F, ice ? 0.7F : 0.3F, ice ? 1F : 0.15F, 0.3F);
    }

    private static float buttonLeft(int index) {
        return PANEL_LEFT_PX + index * (BUTTON_WIDTH_PX + BUTTON_GAP_PX);
    }

    /** The head button shows what pressing it would do: raise a head that is down, else lower it. */
    private static PilotMove shown(PilotMove move, Status s) {
        boolean down = s.phase().startsWith("head_") && !s.phase().equals("head_leave");
        return move == PilotMove.LOWER && down ? PilotMove.RAISE : move;
    }

    private static void renderButton(PvzceClient client, Status s, PilotMove move, float left) {
        boolean ready = s.refusal(move) == null;
        float[] tint = switch (move) {
            case FIRE -> new float[] {0.72F, 0.22F, 0.1F};
            case ICE -> new float[] {0.18F, 0.42F, 0.78F};
            case LOWER, RAISE -> new float[] {0.42F, 0.3F, 0.62F};
            case STOMP, RV, BUNGEE -> new float[] {0.36F, 0.42F, 0.24F};
        };
        float shade = ready ? 1F : 0.38F;
        client.drawSolid(left, PANEL_BOTTOM_PX, BUTTON_WIDTH_PX, BUTTON_HEIGHT_PX, 8F,
                tint[0] * shade, tint[1] * shade, tint[2] * shade, 0.9F);
        int wait = s.pilot().cooldown(move);
        int total = recharge(move);
        if (wait > 0 && total > 0) {
            // The sweep drains from the top as the move recharges, like a seed packet's.
            float fill = Math.min(1F, wait / (float) total);
            client.drawSolid(left, PANEL_BOTTOM_PX + BUTTON_HEIGHT_PX * (1F - fill), BUTTON_WIDTH_PX,
                    BUTTON_HEIGHT_PX * fill, 8.05F, 0F, 0F, 0F, 0.4F);
        }
        float center = left + BUTTON_WIDTH_PX / 2F;
        String label = GuiLang.raw("gui.pvzce.zomboss.move." + move.id(), move.id());
        client.fonts().body().drawCentered(label, center, PANEL_BOTTOM_PX + BUTTON_HEIGHT_PX - 15F,
                LABEL_SCALE, 1F, 1F, 1F, ready ? 1F : 0.6F);
        if (wait > 0) {
            client.fonts().body().drawCentered(String.format(GuiLang.raw("gui.pvzce.zomboss.seconds", "%ds"),
                    seconds(wait)), center, PANEL_BOTTOM_PX + 5F, DETAIL_SCALE, 1F, 0.9F, 0.75F, 1F);
        }
    }

    /** The full recharge a button's sweep is drawn against. */
    private static int recharge(PilotMove move) {
        return switch (move) {
            case STOMP -> ZOMBOSS_PILOT_STOMP_COOLDOWN_TICKS;
            case RV -> ZOMBOSS_PILOT_RV_COOLDOWN_TICKS;
            case BUNGEE -> ZOMBOSS_PILOT_BUNGEE_COOLDOWN_TICKS;
            case LOWER -> ZOMBOSS_PILOT_HEAD_COOLDOWN_TICKS;
            case RAISE -> ZOMBOSS_PILOT_HEAD_HOLD_TICKS;
            case FIRE, ICE -> 0;
        };
    }

    /** The head's clock, said the same way to both sides. */
    private static String headLine(Status s) {
        Pilot pilot = s.pilot();
        if (s.phase().equals("head_leave")) {
            return GuiLang.raw("gui.pvzce.zomboss.rising", "Zomboss is raising its head");
        }
        if (s.phase().startsWith("head_")) {
            return pilot.hold() > 0
                    ? String.format(GuiLang.raw("gui.pvzce.zomboss.hold", "Head down: up in %d s at the earliest"),
                            seconds(pilot.hold()))
                    : GuiLang.raw("gui.pvzce.zomboss.down", "Head down");
        }
        return String.format(GuiLang.raw("gui.pvzce.zomboss.forced", "Head forced down in %d s"),
                seconds(pilot.forced()));
    }

    private static int seconds(int ticks) {
        return (ticks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
    }

    @Override public WorldOverlay createWorldOverlay(ClientLevel level) {
        return new WorldOverlay() {
            private ArtTarget ball;
            private int ballType;
            @Override public void render(PvzceClient client, com.pvzce.client.renderer.PvzceCamera camera) {
                Status s = status(level);
                if (s != null) renderTrail(client, s);
                if (s == null || s.ticksLeft() < ZOMBOSS_STOMP_TICKS / 2) return;
                if (s.phase().equals("rv") || s.phase().equals("stomp")) {
                    float width = s.phase().equals("rv") ? 3F : level.width() - ZOMBOSS_STOMP_COLUMN;
                    client.drawSolid(s.column(), s.row(), width, 2F, 0.08F, 1F, 0.2F, 0.1F, 0.25F);
                }
            }
            @Override public void renderOver(PvzceClient client, com.pvzce.client.renderer.PvzceCamera camera) {
                Status s = status(level);
                if (s == null) return;
                if (s.phase().equals("rv")) renderCamper(client, s);
                if (s.ballType() == 0) {
                    if (ball != null) ball.stopAnimation();
                    ball = null;
                    return;
                }
                if (ball == null || ballType != s.ballType()) {
                    if (ball != null) ball.stopAnimation();
                    ballType = s.ballType();
                    ball = new ArtTarget(ballType == 1 ? EntityArt.ZOMBOSS_FIREBALL_ANIMATION : EntityArt.ZOMBOSS_ICEBALL_ANIMATION);
                    ball.attach(client.animations());
                }
                boolean forming = s.ballAge() < ZOMBOSS_BALL_FORM_TICKS;
                ball.play(forming ? "form" : "idle");
                AnimationPlayback playback = client.animations().playback(ball);
                if (playback == null) return;
                float seconds = (forming ? s.ballAge() : s.ballAge() - ZOMBOSS_BALL_FORM_TICKS) / (float)TICKS_PER_SECOND;
                playback.seek(seconds * playback.effectiveSpeed());
                float ground = groundY(client, s.ballX(), s.ballRow());
                playback.render(client, s.ballX(), ground, 4F, client.spriteXScale(), 1F);
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

    /** The same roof profile as plants and cleaners, sampled at each moving ground contact. */
    static float groundY(PvzceClient client, float x, int row) {
        return row + 0.14F + client.level().sceneBoard().elevationAt(client.level().activeSurface(), x, row + 0.5F);
    }

    private static void renderTrail(PvzceClient client, Status s) {
        if (s.ballType() == 0 || s.ballAge() <= ZOMBOSS_BALL_FORM_TICKS) return;
        float travelled = (s.ballAge() - ZOMBOSS_BALL_FORM_TICKS) * ZOMBOSS_BALL_SPEED / TICKS_PER_SECOND;
        float step = ZOMBOSS_BALL_SPEED * ZOMBOSS_BALL_TRAIL_TICKS / TICKS_PER_SECOND;
        for (float distance = step; distance <= Math.min(travelled, ZOMBOSS_BALL_SPEED * 5.5F); distance += step) {
            float x = s.ballX() + distance;
            float age = distance / ZOMBOSS_BALL_SPEED;
            float scale = Math.min(1F, age / 0.3F);
            float width = 0.85F * scale, height = 0.4F * scale;
            float alpha = Math.min(1F, age / 0.1F) * (1F - age / 5.5F);
            Identifier texture = s.ballType() == 1 ? EntityArt.ZOMBOSS_FIRE_TRAIL : EntityArt.ZOMBOSS_ICE_TRAIL;
            float left = x - width / 2F, right = x + width / 2F;
            float ly = groundY(client, left, s.ballRow()), ry = groundY(client, right, s.ballRow());
            float tw = client.textureWidth(texture), th = client.textureHeight(texture);
            client.drawTextureQuad(texture, left, ly - height / 2F, right, ry - height / 2F,
                    right, ry + height / 2F, left, ly + height / 2F,
                    0, th, tw, th, tw, 0, 0, 0, 0.09F, 1F, 1F, 1F, alpha);
        }
    }
}
