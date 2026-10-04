package com.pvzce.client.gui.screens;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.level.mechanic.StagePlan;
import com.pvzce.client.gui.components.Dialog;
import com.pvzce.common.network.packet.RoundClearS2C;

import java.util.Locale;

/**
 * "Round N is over" - the beat between two rounds of an endless run.
 *
 * <p>Not an end-of-level screen: nothing was won and nothing was lost, and the board behind this
 * dialog is still the board the next round will be played on. What it is for is the pause itself:
 * the server has stopped simulating, the player is about to choose their cards for the next
 * round, and a chooser that appeared out of nowhere would read as a bug rather than as a reward.
 *
 * <p>The three numbers are the same three the defeat screen shows, because on an endless run they
 * are the whole score: how many rounds survived, how many waves that took, and how long it ran.
 */
public final class RoundClearDialog extends Dialog {
    private final RoundClearS2C summary;

    private RoundClearDialog(PvzceClient client, int x, int y, int width, int height,
                             RoundClearS2C summary, Runnable onContinue) {
        super(x, y, width, height, client.level().mechanicData(PvzceIds.MECHANIC_STAGES, StagePlan.class) != null
                ? String.format(GuiLang.raw("gui.pvzce.stages.clear", "Stage %d complete"), summary.round())
                : "第 " + summary.round() + " 轮完成");
        this.summary = summary;
        titleScale(Math.min(1.35F, height / 260F));
        // The run is waiting on this answer: there is nowhere to escape to, and dismissing the
        // dialog would leave the player staring at a frozen board.
        closeOnEscape(false);

        int buttonWidth = Math.min(300, Math.max(150, width - 80));
        int buttonHeight = Math.min(56, Math.max(30, (height - 84) / 2));
        int buttonX = x + (width - buttonWidth) / 2;
        addButton(new Button(buttonX, y + 18, buttonWidth, buttonHeight,
                client.level().mechanicData(PvzceIds.MECHANIC_STAGES, StagePlan.class) != null
                        ? GuiLang.raw("gui.pvzce.stages.choose", "Choose cards and buffs") : "选择下一轮卡牌", () -> {
            close();
            onContinue.run();
        }).style(Button.Style.SEED_CHOOSER));
    }

    /** The round this dialog is about, for the caller that has to spot a stale one. */
    public int round() {
        return summary.round();
    }

    public static RoundClearDialog create(PvzceClient client, RoundClearS2C summary,
                                          Runnable onContinue) {
        int width = Math.min(560, client.guiWidth() - 24);
        int height = Math.min(320, client.guiHeight() - 24);
        return new RoundClearDialog(client, (client.guiWidth() - width) / 2,
                (client.guiHeight() - height) / 2, width, height, summary, onContinue);
    }

    @Override
    public void render(PvzceClient client) {
        super.render(client);
        if (!visible) {
            return;
        }
        // Inside the frame, below the title plate: the plate hangs over the top border, and text
        // drawn level with it collides with the title.
        float scale = 0.95F;
        int seconds = Math.max(0, summary.survivedSeconds());
        String clock = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
        String line = "存活 " + summary.round() + " 轮 · 累计 " + summary.cumulativeWaves()
                + " 波 · 击杀 " + summary.kills() + " · 用时 " + clock;
        if (client.level().mechanicData(PvzceIds.MECHANIC_STAGES, StagePlan.class) != null) {
            line = String.format(GuiLang.raw("gui.pvzce.stages.summary", "%d waves · %d kills · %s"),
                    summary.cumulativeWaves(), summary.kills(), clock);
        }
        float centerY = y + height * 0.58F;
        float lineWidth = client.fonts().body().width(line, scale);
        client.fonts().body().draw(line, x + (width - lineWidth) / 2F, centerY, scale,
                1F, 0.95F, 0.8F, 1F);
        boolean staged = client.level().mechanicData(PvzceIds.MECHANIC_STAGES, StagePlan.class) != null;
        String note = staged ? GuiLang.raw("gui.pvzce.stages.keep", "Lawn, sun and recharge retained; replace cards and buffs")
                : "草坪与阳光保留，只更换卡组";
        float noteScale = scale * 0.9F;
        float noteWidth = client.fonts().body().width(note, noteScale);
        client.fonts().body().draw(note, x + (width - noteWidth) / 2F, centerY - 26F, noteScale,
                0.85F, 0.85F, 0.85F, 1F);
    }
}
