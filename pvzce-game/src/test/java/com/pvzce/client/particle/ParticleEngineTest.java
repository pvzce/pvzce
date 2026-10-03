package com.pvzce.client.particle;

import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Where a particle is born.
 *
 * <p>An effect is emitted at one point, and most definitions put their particles exactly there.
 * A composition cannot: the doom-shroom's blast is a stem, seven pieces of cap and a word, and
 * the shape only exists if each piece is born at its own offset - the original says the same
 * thing with {@code SystemPosition}, and the converter dropped it. These are the two halves of
 * that contract, on the definitions the game actually ships.
 */
class ParticleEngineTest {
    @Test
    void staticFragmentVariantsKeepTheirChosenTextureThroughoutTheirLife() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        var def = com.pvzce.common.core.BuiltInRegistries.PARTICLES.get(
                com.pvzce.api.util.Identifier.parse("pvzce:melon_splat"));
        org.junit.jupiter.api.Assertions.assertFalse(def.look().animated());
        org.junit.jupiter.api.Assertions.assertTrue(def.look().scale() < .25F);
        for (int frame = 0; frame < def.look().frames().size(); frame++) {
            assertEquals(def.look().frames().get(frame), ParticleEngine.frameTexture(def.look(), frame, .1F));
            assertEquals(def.look().frames().get(frame), ParticleEngine.frameTexture(def.look(), frame, .9F));
        }
    }
    @Test
    void aDefinitionWithoutAnOffsetIsBornWhereItWasEmitted() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        ParticleEngine engine = new ParticleEngine();
        engine.spawn("pvzce:pea_splat", 4F, 2F);

        float[] first = engine.position(0);
        assertNotNull(first, "the burst has to have spawned something");
        assertEquals(4F, first[0], 0.001F);
        assertEquals(2F, first[1], 0.001F);
    }

    @Test
    void aDefinitionWithAnOffsetIsBornAroundTheEmitPoint() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        ParticleEngine engine = new ParticleEngine();
        engine.spawn("pvzce:doom_doom_mushroom_left", 5F, 2F);

        // The description is data, so the assertion reads it rather than a literal: what is
        // pinned is that the engine honours it, and that the mushroom's left cap really is to
        // the left and above the cell it was emitted from.
        var def = com.pvzce.common.core.BuiltInRegistries.PARTICLES.get(
                com.pvzce.api.util.Identifier.parse("pvzce:doom_doom_mushroom_left"));
        float offsetX = def.motion().offsetX();
        float offsetY = def.motion().offsetY();
        assertEquals(-1.125F, offsetX, 0.001F, "the left cap sits a cell to the left");
        assertEquals(1.5F, offsetY, 0.001F, "and a cell and a half up, over the blast");

        float[] first = engine.position(0);
        assertNotNull(first, "the cloud piece has to have spawned");
        assertEquals(5F + offsetX, first[0], 0.001F);
        assertEquals(2F + offsetY, first[1], 0.001F);
    }
}
