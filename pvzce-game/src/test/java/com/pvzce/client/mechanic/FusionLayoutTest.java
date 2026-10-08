package com.pvzce.client.mechanic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FusionLayoutTest {
    @Test void upperPaletteAdaptsToTheSpaceLeftByNativePauseAndSpeedButtons() {
        var wide = FusionLayout.of(960, 540, 714);
        var small = FusionLayout.of(427, 240, 213);
        assertEquals(8, wide.abilitiesPerPage());
        assertTrue(small.abilitiesPerPage() < wide.abilitiesPerPage());
        for (var layout : new FusionLayout[]{wide, small}) {
            for (int i = 0; i < layout.abilitiesPerPage(); i++) {
                var card = layout.ability(i); var buy = layout.buy(i);
                assertTrue(card.x() + card.width() < layout.abilityPrevious().x());
                assertTrue(buy.y() + buy.height() <= card.y());
                assertTrue(layout.abilityNext().x() + layout.abilityNext().width() < layout.topRight());
                double x = card.x() + card.width() / 2F, y = card.y() + card.height() / 2F;
                assertEquals(x, layout.x(layout.left() + x * layout.scale()), 0.001);
                assertEquals(y, layout.y(y * layout.scale()), 0.001);
                assertTrue(card.contains(x, y));
            }
            assertTrue(layout.topY() > FusionLayout.BOTTOM_HEIGHT);
            assertFalse(layout.inToolbar(400, (layout.topY() + FusionLayout.BOTTOM_HEIGHT) / 2));
        }
    }
    @Test void eachPacketHasASeparateRecyclingTargetUnderItsPickingTarget() {
        var layout = FusionLayout.of(960, 540);
        for (int i = 0; i < FusionLayout.SEEDS_PER_PAGE; i++) {
            var card = layout.seed(i); var recycle = layout.recycle(i);
            assertTrue(recycle.y() + recycle.height() < card.y());
            assertFalse(card.contains(recycle.x() + recycle.width() / 2, recycle.y() + recycle.height() / 2));
        }
    }
}
