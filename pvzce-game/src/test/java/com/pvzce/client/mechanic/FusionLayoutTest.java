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
                assertTrue(buy.y() >= card.y() + card.height());
                assertTrue(layout.abilityNext().x() + layout.abilityNext().width() < layout.topRight());
                double x = card.x() + card.width() / 2F, y = card.y() + card.height() / 2F;
                assertEquals(x, layout.x(layout.left() + x * layout.scale()), 0.001);
                assertEquals(y, layout.y(y * layout.scale()), 0.001);
                assertTrue(card.contains(x, y));
            }
            assertFalse(layout.inToolbar(400, layout.topY() / 2));
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

    @Test void overlayControlsKeepAllLawnCellsAndTheirArtClearAtDifferentAspectRatios() {
        for (int[] size : new int[][]{{960, 540}, {427, 240}, {960, 720}, {1440, 540}}) {
            var l = FusionLayout.of(size[0], size[1]);
            var camera = new com.pvzce.client.renderer.PvzceCamera(size[0], size[1], 9, 5,
                    com.pvzce.client.renderer.LevelStage.YARD, 0F, true);
            assertEquals(0.8F, (camera.screenX(1F) - camera.screenX(0F)) /
                    (camera.screenY(1F) - camera.screenY(0F)), 0.0001F, "native 80:100 cell aspect ratio");
            assertTrue(l.x(camera.screenX(9F)) < FusionLayout.WORKSHOP_LEFT);
            assertTrue(l.x(camera.screenX(9.4F)) < FusionLayout.WORKSHOP_LEFT,
                    "the right background must leave room to observe approaching zombies");
            assertTrue(l.y(camera.screenY(5F)) <= l.topY() + 0.001F);
            assertTrue(l.y(camera.screenY(0F)) >= FusionLayout.FOOTER_HEIGHT - 0.001F);
            for (int x = 0; x < 9; x++) for (int y = 0; y < 5; y++) {
                float px = camera.screenX(x + 0.5F), py = camera.cellScreenY(x, y);
                assertFalse(l.inToolbar(l.x(px), l.y(py)), "cell " + x + "," + y + " must receive the click");
                assertTrue(camera.inBoard(px, size[1] - py));
                assertEquals(x, camera.cellX(px, size[1] - py));
                assertEquals(y, camera.cellY(px, size[1] - py));
            }
        }
    }

    @Test void everyShiftedWorkshopControlRemainsInsideTheVisibleWindow() {
        for (int[] size : new int[][]{{960, 540}, {427, 240}, {960, 720}, {1440, 540}}) {
            var l = FusionLayout.of(size[0], size[1]);
            var controls = new java.util.ArrayList<FusionLayout.Box>();
            controls.addAll(java.util.List.of(l.fuse(), l.clear(), l.collect(), l.trayNext(), l.randomFill(),
                    l.seedPrevious(), l.seedNext(), l.held(), l.recycleHeld()));
            for (int i = 0; i < FusionLayout.TRAY_PER_PAGE; i++) controls.add(l.tray(i));
            for (int i = 0; i < FusionLayout.SEEDS_PER_PAGE; i++) {
                controls.add(l.seed(i)); controls.add(l.recycle(i));
            }
            for (var box : controls) {
                assertTrue(box.x() >= FusionLayout.WORKSHOP_LEFT);
                assertTrue(l.left() + (box.x() + box.width()) * l.scale() <= size[0]);
                assertTrue((box.y() + box.height()) * l.scale() <= size[1]);
                assertTrue(l.inToolbar(box.x() + box.width() / 2, box.y() + box.height() / 2));
            }
            assertTrue(l.randomFill().x() + l.randomFill().width() < l.trayNext().x());
            assertTrue(l.tray(0).y() + l.tray(0).height() < l.randomFill().y());
        }
    }
}
