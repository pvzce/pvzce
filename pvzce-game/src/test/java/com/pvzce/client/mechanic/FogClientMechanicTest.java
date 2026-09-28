package com.pvzce.client.mechanic;

import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.FogData;
import com.pvzce.client.ClientLevel;
import com.pvzce.client.renderer.LevelStage;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.LevelMechanics;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fog as the client sees it: the grid its cloud tiles are laid out on, and the line an entity
 * stops being drawn at.
 *
 * <p>The cloud itself is checked by screenshot - a test cannot tell whether fog looks like fog.
 * What it <em>can</em> pin is the arithmetic around it, and there are two kinds of that which have
 * already gone wrong once:
 *
 * <ul>
 *   <li>the tile grid, which is the original's own numbers (an 80x85 cell, a 210x190 tile) and is
 *       the only reason the band is continuous instead of eight blobs. Its size comes from the
 *       sprite sheet, so {@link #theSheetHasTheFramesTheGridDraws()} is what keeps the art and the
 *       code from drifting apart: reslice the PNG and the fog is drawn in the wrong place with
 *       nothing to say so;</li>
 *   <li>the hiding line, which has to agree with the alphas the renderer actually puts on screen.
 *       The board asks this class per entity, so a fold that disagreed with the draw is a zombie
 *       the player cannot see, or one they can see and cannot hit.</li>
 * </ul>
 *
 * <p>Read through the same entry points {@code InGameScreen} uses. No GL context is involved: the
 * hiding test and the grid arithmetic are pure.
 */
class FogClientMechanicTest {
    private static final FogClientMechanic MECHANIC = new FogClientMechanic();
    /** The pool board's own cell, which is what the tile grid was measured in. */
    private static final float REFERENCE_CELL_X = 80F;
    private static final float REFERENCE_CELL_Y = 85F;

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        ClientMechanics.bootstrap();
    }

    /** 4-1's board, as the client gets it: nine columns of six, fog from column six. */
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

    /**
     * The band is solid, and the near side is not.
     *
     * <p>4-1 hides the last three columns. What the player reads is a zombie appearing as it walks
     * out of the cloud, so the test walks a zombie across the boundary: clear at column 5, hidden
     * at column 6 and past it. The margin between the two is a column wide on purpose - the hiding
     * fraction is three quarters of the ceiling, and the cloud does not reach it until half a
     * column into the fog.
     */
    @Test
    void anEntityIsHiddenOnceItIsInsideTheCloud() {
        ClientLevel level = levelOf("4_1");
        assertEquals(6F, FogClientMechanic.fogOf(level).startColumn(), 0.001F,
                "4-1 has to be the level this test thinks it is");

        assertFalse(FogClientMechanic.hides(level, 0.5F, 0F), "the near lawn is clear");
        assertFalse(FogClientMechanic.hides(level, 5.5F, 2F), "and so is the last clear column");
        float line = FogClientMechanic.hidingColumn(FogClientMechanic.fogOf(level));
        assertTrue(line > 6F && line < 7F,
                "the line has to be inside the first fogged column, was " + line);
        assertFalse(FogClientMechanic.hides(level, line - 0.01F, 2F),
                "the near side of the line is drawn");
        assertTrue(FogClientMechanic.hides(level, line + 0.01F, 2F),
                "and the far side is not");
        assertTrue(FogClientMechanic.hides(level, 8.5F, 5F), "the far end hides everything");
        // Past the lawn, where a zombie spawns: the cloud still covers it, or a zombie would be
        // visible a tick before it steps onto the board.
        assertTrue(FogClientMechanic.hides(level, 9.6F, 2F), "including off the right edge");
    }

    /**
     * The lamp lights the hiding test and the drawing together.
     *
     * <p>Asked of the same fold the renderer draws with: a lamp standing in the fog takes its cell
     * back out of the hidden region. This is the case the capability exists for, and the one that
     * broke when the hiding test and the draw each had their own idea of the boundary.
     */
    @Test
    void aLampLiftsTheHidingTestToo() {
        ClientLevel level = levelOf("4_1");
        assertTrue(FogClientMechanic.hides(level, 7.5F, 2.5F), "foggy to begin with");
        assertTrue(FogClientMechanic.hides(level, 8.5F, 2.5F),
                "and so is the cell a lamp of that size does not reach");

        level.setMechanicState(PvzceIds.MECHANIC_FOG, new com.pvzce.common.level.mechanic
                .FogMechanic.Wire(6F, 9F, 0.94F, List.of(
                new com.pvzce.common.level.mechanic.FogMechanic.Reveal(7, 7.5F, 2.5F, 2.5F, 1F))));
        assertFalse(FogClientMechanic.hides(level, 7.5F, 2.5F),
                "a plantern's own cell is lit, so nothing standing there is hidden");
        assertTrue(FogClientMechanic.hides(level, 9.5F, 2.5F),
                "and the fog beyond the lamp's reach still hides");
        assertFalse(FogClientMechanic.hides(level, 5.5F, 2.5F),
                "while the clear side was never hidden in the first place");
    }

    /**
     * The tile grid is the original's, in cells, and it matches the sheet.
     *
     * <p>The original draws frame {@code n} of a 210x190 sheet at {@code x*80-15, y*85-30} in the
     * pool board's own pixels. Both halves of that are asserted here: the offsets say where a tile
     * goes relative to its cell, and the size says how much of the band one tile covers - which has
     * to be the sheet's frame, or the cloud arrives squashed.
     *
     * <p>Asserted at a scale of one, because the board's own scale is the camera's business: at
     * 1920x1080 a cell is 144 screen pixels and the tile is 1.8 times these numbers. That
     * multiplication is what the first cut of this missed, and it is why the fog was once drawn
     * small and in the wrong place.
     */
    @Test
    void theTilesAreLaidOutOnTheOriginalsGrid() {
        float[] origin = FogClientMechanic.tileOrigin(3F, 4F, 1F);
        assertEquals(-15F / REFERENCE_CELL_X, origin[0] - 3F, 0.0001F,
                "a tile hangs 15px left of its cell");
        assertEquals(-30F / REFERENCE_CELL_Y, origin[1] - 4F, 0.0001F,
                "and 30px below it");

        float[] size = FogClientMechanic.tileSize(1F);
        assertEquals(210F / REFERENCE_CELL_X, size[0], 0.0001F, "a tile is 210px wide");
        assertEquals(190F / REFERENCE_CELL_Y, size[1], 0.0001F, "and 190px tall");
        assertTrue(size[0] > 2F && size[1] > 2F,
                "so a tile covers more than its own cell, which is what fills the band:"
                        + " neighbouring tiles overlap by well over half");

        // The scale the camera reports at 1920x1080: the pool's 80px cell drawn 144px wide.
        float[] grown = FogClientMechanic.tileSize(144F / LevelStage.POOL.cellWidth());
        assertEquals(size[0] * 1.8F, grown[0], 0.0001F,
                "and the whole grid scales with the board, which is not always the art's own size");
    }

    /**
     * The hiding line sits a fifth of a column into the fog, on the cloud's own grid.
     *
     * <p>The number is not a preference: a tile hangs 0.1875 of a cell left of its cell, so that is
     * where the fog over a cell begins, and the original hides what is inside the first fogged cell.
     * The test pins the two facts separately - the offset is the art's, and the line follows it -
     * so that moving one without the other is a failure rather than a quiet change of behaviour.
     */
    @Test
    void theHidingLineFollowsTheCloudsOffset() {
        FogData fog = FogClientMechanic.fogOf(levelOf("4_1"));
        float[] size = FogClientMechanic.tileSize(1F);
        float[] origin = FogClientMechanic.tileOrigin(0F, 0F, 1F);
        float into = -origin[0];
        assertEquals(0.1875F, into, 0.0001F, "the cloud begins this far into its cell");
        assertTrue(into < size[0] / 2F, "and that is well inside one tile's span");
        assertEquals(fog.startColumn() + into,
                FogClientMechanic.hidingColumn(fog), 0.001F,
                "so the line is that far into the level's first fogged column");
    }

    /**
     * The sprite sheet really is the eight 210x190 frames the grid draws.
     *
     * <p>The failure this catches is silent: the mechanic slices the sheet by frame count and reads
     * the frame's height off the texture, so a sheet that was resliced or rescaled would draw fog
     * at the wrong scale rather than throw. Read from the classpath, because that is where the
     * renderer reads it from.
     */
    @Test
    void theSheetHasTheFramesTheGridDraws() throws Exception {
        try (var stream = FogClientMechanicTest.class.getResourceAsStream(
                "/assets/pvzce/textures/gui/screen/fog_cloud.png")) {
            assertNotNull(stream, "the fog cloud sheet has to be on the classpath;"
                    + " run tools/gen_fog_texture.py");
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(stream);
            assertNotNull(image, "and it has to decode as a PNG");
            float[] size = FogClientMechanic.tileSize(1F);
            assertEquals(210, image.getWidth() / 8, "eight frames across");
            assertEquals(190, image.getHeight(), "each one 190px tall");
            assertEquals(image.getWidth() / 8F, size[0] * REFERENCE_CELL_X, 0.001F,
                    "the frame's width is the tile's width in pixels");
            assertEquals(image.getHeight(), size[1] * REFERENCE_CELL_Y, 0.001F,
                    "and its height is the tile's");
        }
    }

    /** An ordinary level has no fog at all, so nothing about it is hidden or drawn. */
    @Test
    void aLawnWithNoFogHidesNothing() {
        ClientLevel level = levelOf("1_1");
        assertFalse(FogClientMechanic.hides(level, 8.5F, 2F),
                "no cloud comes down on a level that declares no fog");
        assertNull(MECHANIC.createWorldOverlay(level), "and none is drawn either");
    }

    /** A fog that draws nothing is not an overlay, and hides nothing: the mutation's own off switch. */
    @Test
    void aFogWithNoOpacityIsTheSameAsNone() {
        ClientLevel level = levelOf("4_1");
        assertNotNull(MECHANIC.createWorldOverlay(level), "as declared, this board is fogged");
        level.setMechanicState(PvzceIds.MECHANIC_FOG,
                new com.pvzce.common.level.mechanic.FogMechanic.Wire(6F, 9F, 0F));
        assertNull(MECHANIC.createWorldOverlay(level), "at zero opacity there is nothing to draw");
        assertFalse(FogClientMechanic.hides(level, 8.5F, 2F), "and nothing is hidden");
    }
}
