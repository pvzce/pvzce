package com.pvzce.client;

import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.EntityLayers;
import com.pvzce.common.capability.plant.GraveBusterCapability;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tombstones come up out of the lawn, and go back down into it while they are eaten.
 *
 * <p>Both are the same drawing - the element is placed lower than its cell and cut off at the
 * lawn line - so what is pinned here is the two things that decide the number: a cell that gains
 * a tagged element during play rises over time, and a cell with a grave buster on it sinks by
 * however far that plant says it has got.
 */
class SceneShiftsTest {
    private static final float HALF = SceneShifts.RISE_SECONDS / 2F;
    private static final String GRAVE = "pvzce:grave";
    private static final String GRASS = "pvzce:grass";

    @BeforeAll
    static void loadContent() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    @Test
    void aTaggedElementComesUpOverTime() {
        SceneShifts shifts = new SceneShifts();
        shifts.cellChanged(3, 2, GRASS, GRAVE, 10D);

        shifts.sync(10D, List.of());
        SceneShifts.Shift start = shifts.at(3, 2);
        assertNotNull(start, "the stone is on its way up");
        assertEquals(GRASS, start.under(), "and the lawn it is pushing through stays drawn");
        assertEquals(1F, start.sink(), 0.0001F, "still buried at the moment it appears");

        shifts.sync(10D + HALF, List.of());
        assertEquals(0.5F, shifts.at(3, 2).sink(), 0.02F);

        shifts.sync(10D + SceneShifts.RISE_SECONDS, List.of());
        assertNull(shifts.at(3, 2), "and it is in place when the time is up");
    }

    @Test
    void anUntaggedElementAppearsAtOnce() {
        SceneShifts shifts = new SceneShifts();
        shifts.cellChanged(4, 1, GRASS, "pvzce:crater", 10D);

        shifts.sync(10D, List.of());
        assertNull(shifts.at(4, 1), "a crater is made by an explosion, not by growing");
    }

    /** A cell can change twice in one rise's lifetime; only the latest change is on screen. */
    @Test
    void aSecondChangeReplacesTheFirst() {
        SceneShifts shifts = new SceneShifts();
        shifts.cellChanged(1, 1, GRASS, GRAVE, 0D);
        shifts.cellChanged(1, 1, GRAVE, GRASS, 0.1D);

        shifts.sync(0.1D, List.of());
        assertNull(shifts.at(1, 1), "the stone is gone again, and grass does not rise");
    }

    @Test
    void aCellThatHeldNothingRisesOverTheLawn() {
        SceneShifts shifts = new SceneShifts();
        shifts.cellChanged(2, 2, null, "pvzce:grave_slab", 5D);

        shifts.sync(5D, List.of());
        assertEquals(GRASS, shifts.at(2, 2).under(),
                "an unpainted cell is lawn, and that is what a stone comes up through");
    }

    @Test
    void aGraveBusterSinksTheStoneItIsEating() {
        SceneShifts shifts = new SceneShifts();
        // Halfway through the meal: the plant's height is the progress bar.
        ClientEntity buster = plant("pvzce:grave_buster", 3.5F, 2.5F,
                -GraveBusterCapability.SINK_DEPTH_CELLS / 2F);

        shifts.sync(0D, List.of(buster));
        SceneShifts.Shift bite = shifts.at(3, 2);
        assertNotNull(bite, "the stone it stands on is being eaten");
        assertEquals(0.5F, bite.sink(), 0.02F, "and it is half gone from the top");
        assertEquals(GRASS, bite.under(), "the lawn behind it is what shows");

        // The plant finishes and is removed: the cell is a lawn again, not a half stone.
        shifts.sync(0D, List.of());
        assertNull(shifts.at(3, 2));
    }

    @Test
    void aPlantThatIsNotAGraveBusterDoesNotSinkAnything() {
        SceneShifts shifts = new SceneShifts();
        ClientEntity pea = plant("pvzce:pea_shooter", 3.5F, 2.5F, -0.2F);

        shifts.sync(0D, List.of(pea));
        assertNull(shifts.at(3, 2), "only the plant that eats stones sinks into one");
    }

    @Test
    void aStandingGraveBusterLeavesItsCellAlone() {
        SceneShifts shifts = new SceneShifts();
        ClientEntity buster = plant("pvzce:grave_buster", 3.5F, 2.5F, 0F);

        shifts.sync(0D, List.of(buster));
        assertNull(shifts.at(3, 2), "the stone is whole until the chewing starts");
    }

    @Test
    void clearingForgetsEverything() {
        SceneShifts shifts = new SceneShifts();
        shifts.cellChanged(0, 0, GRASS, GRAVE, 1D);
        shifts.sync(1D, List.of());
        shifts.clear();

        assertNull(shifts.at(0, 0), "a level instance's board is not the next one's");
    }

    private static ClientEntity plant(String defId, float x, float y, float height) {
        return new ClientEntity(1, "plant", defId, x, y, 100,
                EntityLayers.GROUND, EntityAnimations.IDLE, height, "");
    }
}
