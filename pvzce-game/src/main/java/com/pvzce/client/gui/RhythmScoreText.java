package com.pvzce.client.gui;

import com.pvzce.common.network.packet.GameStateS2C;

import java.util.ArrayList;
import java.util.List;

/**
 * How one rhythm run reads on the page after it.
 *
 * <p>Two lines, and they are one fact told twice: what the player hit, and how well they held it
 * together. The first line is the four verdicts - the same four words the HUD showed all run, in
 * the same order, so a player who watched "PERFECT 214 · MISS 3" on the lawn reads the receipt
 * without translating anything. The second is the streak the mode is played for: the best run of
 * notes without a mistake, the longest run of PERFECTs (which is the number the jalapenos are named
 * after, and the one that is zero by the end of any run with a single GOOD in it), and how many
 * times the lawn was set alight.
 *
 * <p>Here rather than in either screen because two of them draw it - the award page when the song
 * was survived, the defeat screen when it was not - and a report that reads differently depending
 * on whether you won is two reports.
 *
 * <p>Written as text rather than as numbers because that is what the HUD taught: this is the mode's
 * own vocabulary, and a receipt that suddenly spoke in percentages would be a different game's.
 */
public final class RhythmScoreText {
    private RhythmScoreText() {
    }

    /** The lines to draw, or an empty list on a level that has no chart to report on. */
    public static List<String> lines(GameStateS2C.RhythmScore score) {
        if (score == null || !score.played()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(2);
        lines.add("PERFECT " + score.perfect() + " · GOOD " + score.good()
                + " · FAIR " + score.fair() + " · MISS " + score.missed());
        lines.add(GuiLang.raw("pvzce.rhythm.score.streak", "最高连击 {0} · 最高连续 PERFECT {1} · 火爆辣椒 {2}")
                .replace("{0}", Integer.toString(score.bestCombo()))
                .replace("{1}", Integer.toString(score.bestPerfectStreak()))
                .replace("{2}", Integer.toString(score.jalapenos())));
        return List.copyOf(lines);
    }
}
