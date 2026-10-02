package com.pvzce.client.mechanic;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientLevel;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.HoverTip;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.level.mechanic.VersusMechanic;
import com.pvzce.common.network.PacketByteBuf;

import java.util.List;

/**
 * 对战 on the client: the race's progress, the opponent's side of the board, and its last move.
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
    /** How far the card lines are inset from the panel's heading. */
    private static final float CARD_INDENT = 4F;

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
        renderOpponent(client, state);
        String progress = progressLine(state);
        String opponent = opponentLine(state);
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
     * The opponent's own side of the match: its sun, and the cards it is holding.
     *
     * <p>On the right edge, which nothing else in a versus level uses. It answers the question a
     * player asks while they watch: "what is coming, and can it afford it". The card the opponent
     * played last stays highlighted until it plays again, so a glance connects the panel to the
     * zombie that just walked in.
     *
     * <p>It is <b>the opponent's</b> information, not the level's: the sun number is that team's
     * wallet on the server, and the hand is the list the level gave that side. Nothing is derived
     * on the client, so a card the opponent cannot actually play cannot appear here.
     */
    private static void renderOpponent(PvzceClient client, VersusMechanic.State state) {
        List<String> hand = state.opponentHand();
        if (hand.isEmpty()) {
            return;
        }
        String title = String.format(GuiLang.raw("gui.pvzce.versus.opponent_sun",
                "对手阳光 %d"), state.opponentSun());
        float width = client.fonts().body().width(title, TEXT_SCALE) + PADDING * 2F;
        for (String card : hand) {
            width = Math.max(width, client.fonts().body().width(cardName(card), TEXT_SCALE)
                    + PADDING * 2F + CARD_INDENT);
        }
        float height = LINE_HEIGHT * (hand.size() + 1) + PADDING * 2F;
        float x = client.guiWidth() - width - 16F;
        // Above the race panel, which is anchored at the bottom of the same edge.
        float y = 14F + (LINE_HEIGHT * 2F + PADDING * 2F) + 8F;
        client.drawSolid(x, y, width, height, 8F, 0.16F, 0.06F, 0.06F, 0.85F);
        client.fonts().body().draw(title, x + PADDING, y + height - PADDING - LINE_HEIGHT * 0.9F,
                TEXT_SCALE, 1F, 0.85F, 0.55F, 1F);
        for (int i = 0; i < hand.size(); i++) {
            String card = hand.get(i);
            boolean last = card.equals(state.lastCardId());
            float lineY = y + height - PADDING - LINE_HEIGHT * (i + 1.9F);
            // The last one played is the bright line, the rest of the hand is dim: the panel has to
            // be readable at a glance during a wave, and a list of equal weights is not.
            client.fonts().body().draw(cardName(card), x + PADDING + CARD_INDENT, lineY, TEXT_SCALE,
                    last ? 1F : 0.62F, last ? 1F : 0.62F, last ? 0.7F : 0.62F, 1F);
        }
    }

    /** A card's display name, through the one lookup the card bar and the almanac also use. */
    private static String cardName(String cardId) {
        return HoverTip.cardName(cardId);
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
