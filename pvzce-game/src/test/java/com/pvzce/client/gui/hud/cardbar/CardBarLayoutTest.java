package com.pvzce.client.gui.hud.cardbar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sun bank's rectangle and the room the card row leaves for it.
 *
 * <p>These were three separate sets of numbers - the sprite's size, the row's left edge and the
 * point a collected sun flies to - and two of them had already drifted: the row reserved 70px
 * for a bank drawn 78px wide, from a margin of 12 where the bank uses 8, so the gap came out at
 * 4px instead of the 8 it was named for. Nothing could fail over that until someone changed the
 * bank art; these are the assertions that say what the relationship is supposed to be.
 */
class CardBarLayoutTest {
    @Test
    void theCardRowClearsTheBankByExactlyTheGap() {
        int bankRight = CardBarLayout.bankX() + CardBarLayout.BANK_WIDTH;
        int rowLeft = CardBarLayout.cardsLeft(true);
        assertEquals(CardBarLayout.GAP, rowLeft - bankRight,
                "the row starts one GAP to the right of the bank, no more and no less");
        assertTrue(rowLeft > bankRight, "cards must never be drawn over the bank");
    }

    @Test
    void withoutABankTheRowStartsAtTheMargin() {
        assertEquals(CardBarLayout.MARGIN, CardBarLayout.cardsLeft(false));
    }

    @Test
    void theBankSitsInTheBottomLeftCorner() {
        int guiHeight = 720;
        assertEquals(CardBarLayout.MARGIN, CardBarLayout.bankX());
        assertEquals(guiHeight - CardBarLayout.BANK_HEIGHT - CardBarLayout.MARGIN,
                CardBarLayout.bankY(guiHeight));
        assertEquals(CardBarLayout.MARGIN,
                guiHeight - (CardBarLayout.bankY(guiHeight) + CardBarLayout.BANK_HEIGHT),
                "the bank keeps the same margin at the bottom as at the left");
    }

    /** A collected sun flies to the middle of the bank, not to its corner. */
    @Test
    void theFlyToTargetIsTheCentreOfTheBank() {
        int guiHeight = 720;
        float[] centre = CardBarLayout.bankCentre(guiHeight);
        assertEquals(CardBarLayout.bankX() + CardBarLayout.BANK_WIDTH / 2F, centre[0]);
        assertEquals(CardBarLayout.bankY(guiHeight) + CardBarLayout.BANK_HEIGHT / 2F, centre[1]);
    }
}
