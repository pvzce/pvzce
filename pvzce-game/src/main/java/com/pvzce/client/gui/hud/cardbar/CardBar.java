package com.pvzce.client.gui.hud.cardbar;

import com.pvzce.client.PvzceClient;
import com.pvzce.common.network.packet.SlotInfo;

import java.util.List;

/**
 * A card bar: the row (or belt) of cards the player spends during a level.
 *
 * <p>There are two, and the difference between them is a mechanic rather than a flag: the
 * ordinary {@link SeedCardBar} at the bottom of the screen, and {@link BeltCardBar}, the
 * conveyor a level deals its own cards with. Before this interface existed both were
 * methods inside {@code InGameScreen} chosen by {@code if (level.conveyor())} in six
 * places - layout, render, hit test, tick, the drag-target test and the scroll handler -
 * so a third way of dealing cards would have had to touch all six.
 *
 * <p>Which one a level gets is decided by its mechanics:
 * {@code ClientMechanics.cardBar} asks each of the level's mechanics in turn and falls back
 * to the seed bar.
 */
public interface CardBar {
    /** The cards in the bar's own order: belt order for a belt, seed order for a deck. */
    List<SlotInfo> slots();

    /** Advances anything that moves with time. Called once per screen tick. */
    void tick();

    /** Draws the bar. */
    void render();

    /** The card index under the cursor, or {@code -1}. */
    int slotAt(double guiX, double guiY);

    /** True when the point is inside the bar's own area, for "released over the bar". */
    boolean contains(double guiX, double guiY);

    /** Mouse wheel over the bar; true when the bar consumed the scroll. */
    boolean scroll(double guiX, double guiY, double amount);

    /** Card height in GUI pixels, for things drawn relative to the bar. */
    int cardHeight();

    /** What the bar needs from the screen that owns it. */
    interface Host {
        PvzceClient client();

        /** Right edge the bar may use; the pause and speed buttons own the rest of the row. */
        float rightBound();

        /** True when the sun bank is on screen, which pushes the seed bar to the right. */
        boolean hasSunBank();

        /** The card the player has selected, so the bar can highlight it. */
        int selectedCardIndex();

        /**
         * How far this card is drawn off its place, in GUI pixels.
         *
         * <p>Zero for every card except one that was just refused: the screen owns the
         * shake (it is the thing that heard the click and played the buzzer), the bar owns
         * where cards are, so the offset crosses here rather than the bar keeping a copy of
         * "which card is unhappy". Default is no shake, which is what a bar with no
         * refusable cards - the conveyor - needs.
         */
        default float cardShake(int slotIndex) {
            return 0F;
        }
    }
}
