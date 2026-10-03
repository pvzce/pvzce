package com.pvzce.client.mechanic;

import com.pvzce.api.content.FogData;
import com.pvzce.api.content.LevelDef;
import com.pvzce.client.ClientLevel;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.mechanic.FogMechanic;
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
 * The fog the client is told about: which span a level has, what the cloud covers, and how the
 * picture travels when the span moves.
 *
 * <p>The cloud itself is checked by screenshot - a test cannot tell whether fog looks like fog -
 * and the grid it is laid out on is {@code FogCloudTest}'s. What is pinned here is the state: the
 * span the server sent, the block the seed chooser reads before a level exists, and the two speeds
 * the slide moves at.
 *
 * <p>Note what is deliberately <em>absent</em>: there is no "is this entity hidden" query any
 * more. A zombie in the fog is drawn like every other entity and the cloud is painted over it
 * ({@code ClientMechanic.WorldOverlay.renderOver}), so the only question left is whether the cloud
 * covers a point - which is what {@link FogClientMechanic#covers} answers for the HUD layers that
 * cannot be covered by it.
 */
class FogClientMechanicTest {
    private static final FogClientMechanic MECHANIC = new FogClientMechanic();

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

    /** The level's declared span, before any sync: this is what the chooser draws from too. */
    @Test
    void theDeclaredSpanIsWhatTheLevelWrote() {
        FogData fog = FogClientMechanic.fogOf(levelOf("4_1"));
        assertNotNull(fog, "4-1 declares fog");
        assertEquals(6F, fog.startColumn(), 0.001F);
        assertEquals(9F, fog.endColumn(), 0.001F);
        assertEquals(0.94F, fog.maxAlpha(), 0.0001F);
        assertNull(FogClientMechanic.fogOf(levelOf("1_1")), "an ordinary lawn declares none");
    }

    /** A synced span replaces the declared one; the level's block is only the opening picture. */
    @Test
    void aSyncReplacesTheDeclaredSpan() {
        ClientLevel level = levelOf("4_1");
        level.setMechanicState(PvzceIds.MECHANIC_FOG, new FogMechanic.Wire(4F, 9F, 0.5F));
        assertEquals(4F, FogClientMechanic.fogOf(level).startColumn(), 0.001F);
        assertEquals(0.5F, FogClientMechanic.fogOf(level).maxAlpha(), 0.0001F);
    }

    /**
     * The cloud covers the fogged columns and nothing else.
     *
     * <p>The near lawn is clear, the first fogged column is covered, the far end is covered all the
     * way off the board (a zombie spawns at {@code width + 0.6}, and a cloud that stopped at the
     * last column would show it one tick early) - and a lamp's own cell is not covered, which is the
     * whole of what a plantern does.
     */
    @Test
    void theCloudCoversTheFoggedColumns() {
        ClientLevel level = levelOf("4_1");
        assertFalse(FogClientMechanic.covers(level, 0.5F, 0F), "the near lawn is clear");
        assertFalse(FogClientMechanic.covers(level, 5.5F, 2F), "and so is the last clear column");
        assertTrue(FogClientMechanic.covers(level, 6.5F, 2.5F), "the first fogged column is covered");
        assertTrue(FogClientMechanic.covers(level, 8.5F, 5F), "the far end is covered");
        assertTrue(FogClientMechanic.covers(level, 9.6F, 2F), "including off the right edge");

        level.setMechanicState(PvzceIds.MECHANIC_FOG, new FogMechanic.Wire(6F, 9F, 0.94F, List.of(
                new FogMechanic.Reveal(7, 7.5F, 2.5F, 3F, 1F))));
        assertFalse(FogClientMechanic.covers(level, 7.5F, 2.5F),
                "a lamp's own cell is lit, so the cloud is not over it");
        assertTrue(FogClientMechanic.covers(level, 12.5F, 2.5F),
                "and the fog beyond the lamp's reach still covers");
    }

    /** A lawn with no fog covers nothing, and draws nothing either. */
    @Test
    void aLawnWithNoFogDrawsNothing() {
        ClientLevel level = levelOf("1_1");
        assertFalse(FogClientMechanic.covers(level, 8.5F, 2F),
                "no cloud comes down on a level that declares no fog");
        assertNull(MECHANIC.createWorldOverlay(level), "and none is drawn either");
    }

    /** A fog that draws nothing is not an overlay: the mutation's own off switch. */
    @Test
    void aFogWithNoOpacityIsTheSameAsNone() {
        ClientLevel level = levelOf("4_1");
        assertNotNull(MECHANIC.createWorldOverlay(level), "as declared, this board is fogged");
        level.setMechanicState(PvzceIds.MECHANIC_FOG, new FogMechanic.Wire(6F, 9F, 0F));
        assertNull(MECHANIC.createWorldOverlay(level), "at zero opacity there is nothing to draw");
        assertFalse(FogClientMechanic.covers(level, 8.5F, 2F), "and nothing is covered");
    }

    /**
     * A level payload's fog block is readable without a running level.
     *
     * <p>The seed chooser's path: world 4's first sight of the board is that screen, and it draws
     * the level's own block because there is nothing else to draw it from. The payload carries the
     * same blocks the running level gets, so this is not a second copy of the level file.
     */
    @Test
    void theBlockIsReadableOffALevelPayload() {
        LevelDef def = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/4_1"));
        FogData fog = FogClientMechanic.declaredIn(LevelMechanics.payloads(def));
        assertNotNull(fog, "4-1's payload carries its fog block");
        assertEquals(6F, fog.startColumn(), 0.001F);

        assertNull(FogClientMechanic.declaredIn(LevelMechanics.payloads(
                BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_1")))),
                "and a level with no fog carries none");
        assertNull(FogClientMechanic.declaredIn(null), "as does a payload that is not there");
    }

    /**
     * The slide: out in {@code BLOW_SECONDS}, back in {@code RETURN_SECONDS}.
     *
     * <p>Both are measured over the span's own width, and the asymmetry is the point - the gust is
     * a gesture, the fog's return is weather. A frame gap longer than the cap cannot skip the
     * movement, which is what a pause would otherwise do.
     */
    @Test
    void theSnapshotSlidesOutFastAndComesBackSlow() {
        float span = 3F;
        float blown = FogClientMechanic.slideStep(6F, 9F, span, 1D);
        assertTrue(blown > 6F && blown < 6.1F, "the first frame of a blow barely moves it");
        assertEquals(9F, slide(6F, 9F, span, FogClientMechanic.BLOW_SECONDS), 0.001F,
                "and the whole blow is over in BLOW_SECONDS");
        assertEquals(6F, slide(9F, 6F, span, FogClientMechanic.RETURN_SECONDS), 0.001F,
                "the return takes RETURN_SECONDS");
        assertTrue(slide(9F, 6F, span, 1F) > 8.5F,
                "so a second of it is a tenth of the way, not most of it");
        assertEquals(6F, FogClientMechanic.slideStep(6F, 6F, span, 1D),
                "and a band that is already where it belongs does not move");

        // A stalled frame - a pause, a level reload - is clamped rather than allowed to finish.
        float afterStall = FogClientMechanic.slideStep(6F, 9F, span, 100000D);
        assertTrue(afterStall < 9F, "a huge frame gap is not the whole sweep, was " + afterStall);
    }

    /** Runs the slide for {@code seconds} of frames, one tick at a time, as the board does. */
    private static float slide(float shown, float target, float span, float seconds) {
        int ticks = Math.round(seconds * com.pvzce.common.PvzceConstants.TICKS_PER_SECOND);
        for (int i = 0; i < ticks; i++) {
            shown = FogClientMechanic.slideStep(shown, target, span, 1D);
        }
        return shown;
    }
}
