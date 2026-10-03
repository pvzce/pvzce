package com.pvzce.client.renderer;

import com.pvzce.api.content.LevelDef;
import com.pvzce.client.ClientEntity;
import com.pvzce.client.ClientLevel;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.PlantPlacement;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the client thinks a plant will go, against where the server puts it.
 *
 * <p>The one thing this has to get right is agreement: the ghost under the player's cursor is a
 * promise, and the plant that appears has to land inside it. The two offsets the server applies -
 * the cell's middle, plus a carrier's nudge and its top - are what a preview that only knew the
 * grid's middle and the terrain's height was missing, and the player's report was the lily-pad half
 * of it ("a plant planted on a lily pad is out of place").
 *
 * <p>The lily pad's number is asserted here rather than only in {@code PlantPlacementTest} because
 * this is where it is <em>visible</em>: a pad whose top is below its own disc is a plant standing
 * through the pad's art.
 */
class ClientPlacementTest {
    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static ClientLevel levelOf(String level) {
        LevelDef def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/" + level));
        assertNotNull(def, level + " has to exist");
        ClientLevel client = new ClientLevel();
        client.init(def.id().toString(), def.width(), def.height(), List.of(), List.of("small"),
                List.of(), 6, List.of(), List.of(), "pvzce:plant_team", "植物方",
                LevelMechanics.payloads(def), def.background().orElse(null),
                def.hiddenSceneElements(), def.disableShaders());
        return client;
    }

    /** A plant on a lily pad is anchored above the pad's disc, not through its bottom rim. */
    @Test
    void aPlantOnALilyPadStandsOnThePad() {
        ClientLevel level = levelOf("4_1");
        // Row 2 is water on 4-1's board; the lily pad is what makes it plantable.
        level.addEntity(new ClientEntity(1, "plant", "pvzce:lily_pad", 3.5F, 2.5F, 300,
                com.pvzce.api.entity.EntityLayers.PLANT,
                com.pvzce.api.entity.EntityAnimations.IDLE, 0F, "pvzce:plant_team"));

        float[] anchor = ClientPlacement.anchoredAt(level, 3, 2);
        assertEquals(3.5F + PlantPlacement.CARRIER_X_OFFSET, anchor[0], 0.0001F,
                "the pair is nudged the same way the server nudges it");
        assertEquals(2.5F, anchor[1], 0.0001F, "and stays in its own cell");
        assertEquals(PlantPlacement.LILY_PAD_TOP, anchor[2], 0.0001F,
                "standing on the pad's own top, whatever that number is");
        assertTrue(anchor[2] >= 0.25F,
                "which has to be up at the pad's disc rather than its bottom rim; see"
                        + " PlantPlacement.LILY_PAD_TOP for the measurement");
    }

    /**
     * An empty cell is still the cell's middle, at the terrain's own height.
     *
     * <p>Asked of 1-1, which is this build's one-row tutorial board - a one-row level is the case
     * that shows a preview which trusted its own coordinates over the board's bounds.
     */
    @Test
    void anEmptyCellIsJustTheCellsMiddle() {
        ClientLevel level = levelOf("1_1");
        float[] anchor = ClientPlacement.anchoredAt(level, 4, 0);
        assertEquals(4.5F, anchor[0], 0.0001F);
        assertEquals(0.5F, anchor[1], 0.0001F);
        assertEquals(0F, anchor[2], 0.0001F, "plain grass has no height of its own");
    }

    /** A pot nudges and lifts the same way, so the two carriers cannot drift apart. */
    @Test
    void aPlantInAPotStandsOnThePot() {
        ClientLevel level = levelOf("1_1");
        level.addEntity(new ClientEntity(2, "plant", "pvzce:flower_pot", 6.5F, 0.5F, 300,
                com.pvzce.api.entity.EntityLayers.PLANT,
                com.pvzce.api.entity.EntityAnimations.IDLE, 0F, "pvzce:plant_team"));

        float[] anchor = ClientPlacement.anchoredAt(level, 6, 0);
        assertEquals(6.5F + PlantPlacement.CARRIER_X_OFFSET, anchor[0], 0.0001F);
        assertEquals(PlantPlacement.FLOWER_POT_TOP, anchor[2], 0.0001F);
    }
}
