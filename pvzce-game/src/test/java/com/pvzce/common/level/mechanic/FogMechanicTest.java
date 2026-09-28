package com.pvzce.common.level.mechanic;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.pvzce.api.content.FogData;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.mechanic.TypedMechanic;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.buff.LevelBuffs;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.common.tag.TestContent;
import com.pvzce.server.level.LevelServer;
import com.pvzce.testutil.TestLevels;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fog: how far it reaches, how dark it gets, and what that means for what is drawn.
 *
 * <p>The feature is almost entirely visual, so most of it is checked by screenshot. What is worth
 * a unit test is the part a screenshot cannot tell you is <em>consistent</em>: the boundary the
 * renderer draws at and the boundary the hiding test uses have to be the same number, the baked
 * gradient sprite has to carry the same curve the data describes, and the buff that moves the fog
 * has to move it by the amount it says. A fog that is drawn one place and hides another is a bug
 * nobody would notice until a zombie was eaten by something invisible.
 */
class FogMechanicTest {
    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    /** A level with this block declared, and nothing else changed. */
    private static LevelDef withFog(FogData fog) {
        LevelDef base = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_1"));
        assertNotNull(base);
        List<TypedMechanic> mechanics = new ArrayList<>(base.mechanics());
        mechanics.add(TypedMechanic.of(PvzceIds.MECHANIC_FOG, fog));
        return TestLevels.copy(base).mechanics(mechanics).build();
    }

    @Test
    void aLevelThatDeclaresFogReportsIt() {
        FogData fog = new FogData(5F, 9F, 0.9F);
        LevelDef def = withFog(fog);
        assertEquals(fog, LevelMechanics.fogData(def), "the block has to come back as written");
        assertNotNull(LevelMechanics.get(PvzceIds.MECHANIC_FOG),
                "and the mechanic has to be registered, or the block fails to decode");

        LevelServer level = new LevelServer(def);
        assertEquals(fog, level.fogData(), "a level with no buffs plays the fog it declared");
    }

    @Test
    void aLevelWithNoFogReportsAFogThatDrawsNothing() {
        LevelDef plain = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_1"));
        assertNull(LevelMechanics.fogData(plain), "an ordinary level declares no fog");
        LevelServer level = new LevelServer(plain);
        assertEquals(0F, level.fogData().alphaAt(0F));
        assertEquals(0F, level.fogData().alphaAt(99F),
                "and the fallback hides nothing anywhere, so the renderer registers nothing");
    }

    /**
     * The retreat buff moves the boundary right by the distance it declares.
     *
     * <p>Read through {@code LevelServer.fogData()} rather than through the buff, because that is
     * the one place the level's block, a mutation's override and the buff are folded together - and
     * it is what the client is sent.
     */
    @Test
    void theRetreatBuffMovesTheBoundaryRightByItsOwnDistance() {
        FogData fog = new FogData(4F, 8F, 0.9F);
        LevelServer level = new LevelServer(withFog(fog));
        level.setActiveBuffs(List.of(com.pvzce.common.buff.BuiltInBuffs.FOG_RETREAT));
        FogData after = level.fogData();
        assertEquals(fog.startColumn() + LevelBuffs.fogRetreat(level.activeBuffs()),
                after.startColumn(), 0.0001F);
        assertEquals(fog.endColumn() + LevelBuffs.fogRetreat(level.activeBuffs()),
                after.endColumn(), 0.0001F);
        assertEquals(fog.maxAlpha(), after.maxAlpha(), 0.0001F,
                "the buff shortens the fog; it does not thin it");
        assertTrue(after.startColumn() > fog.startColumn(),
                "and 'retreat' has to mean the visible part gets bigger, was " + after.startColumn());
    }

    /** A mutation's override wins over the level's own block, and clearing it restores it. */
    @Test
    void aMutationCanRollFogInAndGiveItBack() {
        LevelDef plain = BuiltInRegistries.LEVELS.get(PvzceIds.id("yard/adventure/1_1"));
        LevelServer level = new LevelServer(plain);
        assertEquals(0F, level.fogData().alphaAt(99F), "no fog to begin with");

        level.setFogOverride(new FogData(3F, 7F, 0.95F));
        assertEquals(0.95F, level.fogData().alphaAt(99F), 0.0001F, "the mutation brings fog");

        level.setFogOverride(null);
        assertEquals(0F, level.fogData().alphaAt(99F), 0.0001F,
                "and evicting it gives the level's own answer back with nothing kept");
    }

    /**
     * The ramp: nothing before the span, everything after it, and never going backwards.
     *
     * <p>Monotonicity is the property that matters. "Darker toward the right" is what the player
     * was shown, and a curve with a dip in it would draw a bright band inside the fog.
     */
    @Test
    void theRampIsClearThenMonotoneThenOpaque() {
        FogData fog = new FogData(4F, 8F, 0.9F);
        assertEquals(0F, fog.alphaAt(0F), 0.0001F);
        assertEquals(0F, fog.alphaAt(4F), 0.0001F, "the start column is still clear");
        assertEquals(0.9F, fog.alphaAt(8F), 0.0001F, "the end column is as dark as it gets");
        assertEquals(0.9F, fog.alphaAt(30F), 0.0001F, "and it stays there");

        float previous = -1F;
        for (int step = 0; step <= 100; step++) {
            float column = 4F + 4F * step / 100F;
            float alpha = fog.alphaAt(column);
            assertTrue(alpha >= previous, "the fog lightened between samples at column " + column);
            previous = alpha;
        }
    }

    /**
     * What is hidden is behind what is dark, and the two are different questions.
     *
     * <p>{@code hidingColumn} is the point where the player can no longer make something out; it
     * has to sit strictly between the two ends of the span, or a level would either show a zombie
     * standing in pitch black or hide one standing in clear air.
     */
    @Test
    void theHidingColumnSitsInsideTheSpan() {
        FogData fog = new FogData(4F, 8F, 0.9F);
        assertTrue(fog.hidingColumn() > fog.startColumn(),
                "something has to be visible right at the boundary");
        assertTrue(fog.hidingColumn() < fog.endColumn(),
                "and something has to be hidden before the fog tops out");
        assertEquals(Float.MAX_VALUE, new FogData(0F, 0F, 0F).hidingColumn(),
                "a fog that draws nothing hides nothing, wherever it is asked about");
    }

    /** An empty span draws nothing, and a negative alpha cannot be written. */
    @Test
    void degenerateSpansFoldRatherThanDrawGarbage() {
        FogData backwards = new FogData(6F, 2F, 0.9F);
        assertEquals(6F, backwards.endColumn(), 0.0001F, "an end before the start folds to empty");
        assertEquals(1F, new FogData(0F, 4F, 9F).maxAlpha(), 0.0001F, "alpha is clamped to 1");
        assertEquals(0F, new FogData(-3F, 4F, 0.9F).startColumn(), 0.0001F,
                "and a negative column to 0");
    }

    /** The validator says so when a level's fog could never hide anything. */
    @Test
    void theValidatorReportsAnImpossibleFog() {
        assertFalse(new FogData(4F, 8F, 0.9F).validate(9).size() > 0,
                "an ordinary fog is valid");
        assertTrue(new FogData(12F, 14F, 0.9F).validate(9).size() > 0,
                "fog starting past the right edge hides nothing and must be reported");
        assertTrue(new FogData(2F, 8F, 0F).validate(9).size() > 0,
                "a span with zero opacity draws nothing and must be reported");
    }

    /** The span survives the wire, which is the only thing the client ever sees of it. */
    @Test
    void theSpanRoundTripsThroughTheSyncPacket() {
        FogData fog = new FogData(3.5F, 8.25F, 0.87F);
        PacketByteBuf buf = new PacketByteBuf(io.netty.buffer.Unpooled.buffer());
        FogMechanic.Wire.of(fog, java.util.List.of()).encode(buf);
        FogMechanic.Wire decoded = FogMechanic.Wire.decode(buf);
        assertEquals(fog.startColumn(), decoded.startColumn(), 0.0001F);
        assertEquals(fog.endColumn(), decoded.endColumn(), 0.0001F);
        assertEquals(fog.maxAlpha(), decoded.maxAlpha(), 0.0001F);
        assertEquals(fog, decoded.data());
    }

    /**
     * The level sends its fog when the boundary moves, and not when it does not.
     *
     * <p>The heartbeat is the cost of the feature: a packet every ten ticks for a number that
     * changes twice a level would be thirty packets a minute of nothing.
     */
    @Test
    void theBoundaryIsPublishedOnlyWhenItChanges() {
        LevelServer level = new LevelServer(withFog(new FogData(4F, 8F, 0.9F)));
        List<PvzcePacket> packets = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            level.tick(packets::add);
        }
        assertEquals(0, countFogSyncs(packets),
                "a still lawn publishes nothing: the fog it declared is already known");

        level.setActiveBuffs(List.of(com.pvzce.common.buff.BuiltInBuffs.FOG_RETREAT));
        packets.clear();
        for (int i = 0; i < 40; i++) {
            level.tick(packets::add);
        }
        assertEquals(1, countFogSyncs(packets),
                "and the buff moving it publishes exactly once, not once per check");
    }

    private static int countFogSyncs(List<PvzcePacket> packets) {
        int count = 0;
        for (PvzcePacket packet : packets) {
            if (packet instanceof MechanicSyncS2C sync
                    && sync.mechanic().equals(PvzceIds.MECHANIC_FOG)) {
                count++;
            }
        }
        return count;
    }

    /**
     * The cloud sheet is on the classpath, and it is a cloud sheet rather than a black rectangle.
     *
     * <p>The drawing half of the fog lives in {@code FogClientMechanic} (and its own test, which
     * checks the sheet's <em>geometry</em>); what belongs here is the one thing the data side can
     * say about the art: it has to be a light cloud with gaps in it. Both failure modes are quiet -
     * a missing sheet falls back to the missing-texture placeholder, and a sheet that baked black
     * would draw a black band, which is exactly the bug the tiles replaced.
     */
    @Test
    void theCloudSheetIsOnTheClasspath() throws Exception {
        try (var stream = FogMechanicTest.class.getResourceAsStream(
                "/assets/pvzce/textures/gui/screen/fog_cloud.png")) {
            assertNotNull(stream, "the fog cloud sheet has to be on the classpath;"
                    + " run tools/gen_fog_texture.py");
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(stream);
            assertNotNull(image, "and it has to decode as a PNG");
            int opaque = 0;
            int clear = 0;
            for (int y = 0; y < image.getHeight(); y += 3) {
                for (int x = 0; x < image.getWidth(); x += 3) {
                    int argb = image.getRGB(x, y);
                    int alpha = (argb >>> 24) & 0xFF;
                    if (alpha > 200) {
                        opaque++;
                    } else if (alpha < 40) {
                        clear++;
                    }
                }
            }
            int samples = (image.getWidth() / 3) * (image.getHeight() / 3);
            assertTrue(opaque > samples * 0.1, "a cloud has to be mostly opaque where it is: only "
                    + opaque + " of " + samples + " samples are");
            assertTrue(clear > samples * 0.3, "and mostly absent between its puffs: only "
                    + clear + " of " + samples + " samples are");
        }
    }
}
