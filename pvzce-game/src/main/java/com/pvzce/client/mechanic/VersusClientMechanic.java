package com.pvzce.client.mechanic;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientLevel;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.HoverTip;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.level.mechanic.VersusMechanic;
import com.pvzce.common.network.PacketByteBuf;

/**
 * 对战 on the client: the race's progress and what the opponent just did.
 *
 * <p>Its server half is {@code VersusMechanic}. This side decides nothing - the counter, the
 * opponent's side and its latest move all arrive as {@link VersusMechanic.State} - so what is left
 * is the two sentences a player needs to read the match: how much sun the plant side has collected
 * out of its goal, and who is on the other end.
 *
 * <p>The opponent line exists because "Jev is thinking" and "the game's own policy is playing" look
 * exactly the same on the lawn and are completely different matches. A player whose key expired
 * three levels ago should be able to see that from the HUD rather than from the log.
 */
final class VersusClientMechanic implements ClientMechanic {
    /** Panel geometry, in GUI pixels. */
    private static final float PADDING = 8F;
    private static final float TEXT_SCALE = 0.8F;
    private static final float LINE_HEIGHT = 12F;
    private static final float BAR_HEIGHT = 3F;

    @Override
    public Identifier id() {
        return PvzceIds.MECHANIC_VERSUS;
    }

    @Override
    public void applySync(ClientLevel level, PacketByteBuf payload) {
        level.setMechanicState(PvzceIds.MECHANIC_VERSUS, VersusMechanic.State.CODEC.decode(payload));
    }

    @Override
    public void renderHud(PvzceClient client) {
        VersusMechanic.State state = client.level()
                .mechanicStateOrNull(PvzceIds.MECHANIC_VERSUS, VersusMechanic.State.class);
        if (state == null) {
            return;
        }
        String progress = progressLine(state);
        String opponent = opponentLine(state);
        if (state.zombieStartCountdown() > 0) {
            // The build window, in seconds: the one moment of the match with nothing to react to
            // and everything to build, so the HUD says how long it lasts.
            opponent = String.format(GuiLang.raw("gui.pvzce.versus.build_window",
                            "对手还有 %d 秒出发"),
                    (state.zombieStartCountdown() + PvzceConstants.TICKS_PER_SECOND - 1)
                            / PvzceConstants.TICKS_PER_SECOND);
        }
        float width = Math.max(client.fonts().body().width(progress, TEXT_SCALE),
                client.fonts().body().width(opponent, TEXT_SCALE)) + PADDING * 2F;
        float height = LINE_HEIGHT * 2F + PADDING * 2F;
        // Bottom-right, which a versus level has to itself: the wave meter that normally lives
        // there is drawn from the level's wave table, and a versus level has no waves. The
        // bottom-centre - where this started - is the level's opening hint for the first half
        // minute, and the panel was hidden behind it exactly when a player reads it most.
        float x = client.guiWidth() - width - 16F;
        float y = 14F;
        client.drawSolid(x, y, width, height, 8F, 0.05F, 0.14F, 0.10F, 0.85F);
        // Bottom line first: the panel is drawn top-down in GUI space, so the progress the player
        // watches most sits against the bar underneath it.
        client.fonts().body().draw(progress, x + PADDING, y + PADDING + LINE_HEIGHT * 1.1F,
                TEXT_SCALE, 0.7F, 1F, 0.75F, 1F);
        client.fonts().body().draw(opponent, x + PADDING, y + PADDING,
                TEXT_SCALE, 0.9F, 0.9F, 0.95F, 1F);
        if (state.sunGoal() > 0) {
            float fraction = Math.min(1F, state.sunCollected() / (float) state.sunGoal());
            client.drawSolid(x, y + height, width * fraction, BAR_HEIGHT, 8.1F,
                    0.35F, 0.95F, 0.55F, 1F);
        }
    }

    /**
     * "How much sun, out of how much" - from the player's point of view.
     *
     * <p>Which side is racing is a fact about the level (the opponent's side), and the same number
     * means opposite things depending on it: a player on the plant side is watching their own
     * progress, and a player on the zombie side is watching a clock. The two are one key each rather
     * than a third sentence assembled from the number.
     */
    private static String progressLine(VersusMechanic.State state) {
        if (state.sunGoal() <= 0) {
            return GuiLang.raw("gui.pvzce.versus.no_goal", "本关没有阳光目标：守住草坪");
        }
        boolean playerIsPlant = !"plant".equals(state.opponentSide());
        String key = playerIsPlant ? "gui.pvzce.versus.goal_mine" : "gui.pvzce.versus.goal_theirs";
        String fallback = playerIsPlant ? "我方阳光 %d / %d" : "对方植物方阳光 %d / %d";
        return String.format(GuiLang.raw(key, fallback), state.sunCollected(), state.sunGoal());
    }

    /** Who is on the other end, and what they last spent their sun on. */
    private static String opponentLine(VersusMechanic.State state) {
        String who = switch (state.opponentStatus()) {
            case 1 -> GuiLang.raw("gui.pvzce.versus.opponent.jev", "对手：Jev");
            case 2 -> GuiLang.raw("gui.pvzce.versus.opponent.fallback",
                    "对手：内置策略（Jev 未响应）");
            default -> GuiLang.raw("gui.pvzce.versus.opponent.builtin", "对手：内置策略");
        };
        if (state.lastCardId() == null || state.lastCardId().isBlank() || state.lastRow() < 0) {
            return who;
        }
        // Rows count from one for a player reading the lawn, and from the top: the same convention
        // the resonance HUD uses.
        return who + " · " + String.format(GuiLang.raw("gui.pvzce.versus.last_move", "最近：%s → 第 %d 行"),
                HoverTip.cardName(state.lastCardId()), state.lastRow() + 1);
    }
}
