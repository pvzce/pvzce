package com.pvzce.client.gui.hud.cardbar;

/**
 * Where the HUD's two banks sit, and how much room the card row has to leave for them.
 *
 * <p>One source, because this is one rectangle: the screen draws the sun bank, the card row has
 * to start to the right of it, and a collected sun flies to its centre. Each of the three had its
 * own numbers, and they had already drifted apart - the row reserved 70px for a bank that is
 * drawn 78px wide, offset from a margin of 12 where the bank uses 8 - so the gap between the two
 * was 4px rather than the {@link #GAP} it was supposed to be, and changing the bank art would
 * have moved the number, the sprite and the card row by different amounts.
 *
 * <p>The bank is measured in GUI pixels at scale 1, exactly like {@code SeedCardBar}'s card sizes:
 * the sun bank is a fixed piece of HUD furniture, not something that grows with the window.
 */
public final class CardBarLayout {
    /** The sun bank's sprite: the original's {@code SunBank.png}, drawn at its own size. */
    public static final int BANK_WIDTH = 78;
    public static final int BANK_HEIGHT = 87;
    /**
     * The HUD banks' margin: the distance from the window's left edge, and from the corner each
     * bank is anchored to.
     *
     * <p>Not one distance: the sun bank is top-left furniture ({@link #bankY} puts it {@code MARGIN}
     * below the top edge, in a GUI space whose origin is the bottom-left corner) while the coin bank
     * is drawn at {@code y = MARGIN}, i.e. just above the bottom edge - see
     * {@code InGameScreen}'s coin bank, which takes the one corner the other HUD pieces leave free.
     */
    public static final int MARGIN = 8;
    /** Distance between the bank and the first card. */
    public static final int GAP = 8;

    private CardBarLayout() {
    }

    /** The sun bank's left edge. */
    public static int bankX() {
        return MARGIN;
    }

    /** The sun bank's bottom edge, for a window {@code guiHeight} tall. */
    public static int bankY(int guiHeight) {
        return guiHeight - BANK_HEIGHT - MARGIN;
    }

    /** The centre of the sun bank: where a collected sun flies to. */
    public static float[] bankCentre(int guiHeight) {
        return new float[]{MARGIN + BANK_WIDTH / 2F, guiHeight - BANK_HEIGHT / 2F - MARGIN};
    }

    /** Where the card row starts, leaving room for the bank when the level has one. */
    public static int cardsLeft(boolean hasSunBank) {
        return hasSunBank ? MARGIN + BANK_WIDTH + GAP : MARGIN;
    }
}
