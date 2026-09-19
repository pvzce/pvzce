package com.pvzce.client.gui.hud.cardbar;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.network.packet.SlotInfo;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A card's icon, whichever screen is drawing it.
 *
 * <p>This used to be two answers. The in-game bar built {@code pvzce:textures/entities/<path>}
 * by hand - stripping the namespace - while the seed chooser's preview kept it, so a modded
 * entity drew its sprite on one screen and requested a {@code pvzce:} texture on the other:
 * invisible wherever it lost, with nothing in the log to say which side. Both now ask
 * {@link com.pvzce.client.renderer.EntityTextures} through {@link SlotResolver}, and these are
 * the assertions that keep a third answer from growing.
 */
class CardPainterTest {
    @BeforeAll
    static void loadContent() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    /** A card as the server would send it; the kind only decides which art family is asked. */
    private static SlotInfo card(String defId, String kind) {
        return new SlotInfo(0, defId, kind, 100, 0, 300, SlotInfo.UNLIMITED_USES, true);
    }

    @Test
    void aModdedCardsIconKeepsItsOwnNamespace() {
        Identifier icon = CardPainter.icon(card("mymod:custom_plant", "plant"));
        assertNotNull(icon, "a card always has something to draw");
        assertEquals("mymod", icon.namespace(),
                "a mod's card must not be looked up under pvzce, where its art does not exist");
    }

    /** The id is only a fallback: a slot that declares an icon is drawn with that icon. */
    @Test
    void aDeclaredSlotIconWins() {
        Identifier expected = SlotResolver.resolve(Identifier.withDefaultNamespace("sun"))
                .flatMap(SlotResolver.ResolvedCard::icon)
                .orElseThrow(() -> new AssertionError("the built-in sun card must resolve"));
        assertEquals(expected, CardPainter.icon(card("pvzce:sun", "resource")));
        assertEquals("pvzce", expected.namespace());
    }

    /**
     * The fallback is the <em>content's</em> art, not the slot id's: a level may pin a card whose
     * slot id and content differ, and the plant is the thing that has a sprite.
     */
    @Test
    void theFallbackIsTheContentsArt() {
        Identifier icon = CardPainter.icon(card("pvzce:pea_shooter", "plant"));
        assertTrue(icon.toString().contains("pea_shooter"),
                "the peashooter's card draws the peashooter, got " + icon);
    }

    /** An unparseable id still has to answer with something drawable rather than null. */
    @Test
    void anUnparseableIdFallsBackToTheMissingTexture() {
        assertNotNull(CardPainter.icon(card("not an id", "plant")));
    }
}
