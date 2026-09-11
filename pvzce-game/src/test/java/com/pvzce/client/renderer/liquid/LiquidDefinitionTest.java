package com.pvzce.client.renderer.liquid;

import com.mojang.serialization.JsonOps;
import com.pvzce.api.content.LiquidDef;
import com.pvzce.api.content.SceneElementDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.resource.PvzceDataLoader;
import com.pvzce.common.resource.PvzceResourceManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The liquid content layer: the shipped definition parses, the scene element points
 * at it, and a modded liquid can be authored without touching code.
 *
 * <p>The colour parsing is covered here rather than by eye because the definitions
 * are written as hex strings and a silent misparse would show up as water tinted
 * pure white - which looks like a shader bug, not a data bug.
 */
class LiquidDefinitionTest {
    @BeforeAll
    static void loadData() throws Exception {
        BuiltInRegistries.bootstrap();
        PvzceResourceManager resources =
                new PvzceResourceManager(Thread.currentThread().getContextClassLoader());
        resources.init(Path.of(System.getProperty("java.io.tmpdir"), "pvzce-liquid-test"));
        PvzceDataLoader.LoadResult result = new PvzceDataLoader().load(resources, BuiltInRegistries.ACCESS);
        assertTrue(result.errors().isEmpty(), result.errors().toString());
    }

    @Test
    void theShippedWaterDefinitionLoadsFromData() {
        LiquidDef water = BuiltInRegistries.LIQUIDS.get(Identifier.withDefaultNamespace("water"));
        assertNotNull(water, "data/pvzce/pvzce/liquids/water.json must define pvzce:water");
        assertEquals(LiquidDef.DEFAULT_BASE_TEXTURE, water.resolvedBaseTexture());
        assertEquals(0.68F, water.opacity(), 0.0001F);
        assertEquals(1.6F, water.depthScale(), 0.0001F);
        assertEquals(0.04F, water.foam().width(), 0.0001F);
        assertEquals(4, water.staticFrames());
        // The frame id is a full texture id, prefix included, because that is what
        // TextureManager resolves against assets/<ns>/<path>.png.
        assertEquals("pvzce:textures/scene/water_frames/water_2",
                water.staticFrameTexture(2).toString());
        assertEquals("pvzce:textures/scene/water_frames/water_2",
                water.staticFrameTexture(6).toString(),
                "the frame index must wrap, not run off the end of the set");
    }

    @Test
    void theBuiltInFallbackMatchesTheShippedData() {
        // The static entry is what an empty data pack falls back to, and the pack
        // entry replaces it by id. If the two drift, the same level renders different
        // water depending on whether a pack was found - which is exactly what
        // happened when the palette was retuned: the fallback kept the old colours
        // and a four-times-wider foam band.
        LiquidDef shipped = BuiltInRegistries.LIQUIDS.get(Identifier.withDefaultNamespace("water"));
        LiquidDef fallback = BuiltInRegistries.builtInWater();
        assertEquals(fallback.opacity(), shipped.opacity(), 1e-6F);
        assertEquals(fallback.depthScale(), shipped.depthScale(), 1e-6F);
        assertEquals(fallback.caustics(), shipped.caustics(), 1e-6F);
        assertEquals(fallback.specular(), shipped.specular(), 1e-6F);
        assertEquals(fallback.specularPower(), shipped.specularPower(), 1e-6F);
        assertEquals(fallback.foam().width(), shipped.foam().width(), 1e-6F);
        assertEquals(fallback.wave().speed(), shipped.wave().speed(), 1e-6F);
        assertArrayEquals(fallback.shallowColor(), shipped.shallowColor(), 1e-4F);
        assertArrayEquals(fallback.deepColor(), shipped.deepColor(), 1e-4F);
        assertArrayEquals(fallback.foam().color(), shipped.foam().color(), 1e-4F);
    }

    @Test
    void theBakedFallbackFramesAreShipped() throws Exception {
        // The fallback path asks for exactly static_frames files. A definition that
        // promises four frames while three are shipped does not fail loudly - it
        // silently draws the flat colour for every fourth frame, which looks like
        // flicker. So the count and the assets are checked against each other.
        LiquidDef water = BuiltInRegistries.LIQUIDS.get(Identifier.withDefaultNamespace("water"));
        PvzceResourceManager resources =
                new PvzceResourceManager(Thread.currentThread().getContextClassLoader());
        // init() is what installs the built-in classpath pack; without it the manager
        // is empty and every lookup "fails" for the wrong reason.
        resources.init(Path.of(System.getProperty("java.io.tmpdir"), "pvzce-liquid-frames"));
        for (int frame = 0; frame < water.staticFrames(); frame++) {
            Identifier id = water.staticFrameTexture(frame);
            boolean present = resources.getResource("assets/" + id.toPath() + ".png").isPresent();
            assertTrue(present, "missing fallback frame asset: " + id);
        }
        // And one past the end must NOT exist, so wrapping is observable rather than
        // an accident of the count being wrong.
        Identifier beyond = water.staticFrameTexture(water.staticFrames());
        assertEquals(water.staticFrameTexture(0), beyond, "the frame index must wrap");
    }

    @Test
    void theWaterSceneElementNamesTheLiquid() {
        SceneElementDef water = BuiltInRegistries.SCENE_ELEMENTS.get(Identifier.withDefaultNamespace("water"));
        assertTrue(water.isLiquid(), "the water scene element must opt into liquid rendering");
        assertEquals(Identifier.withDefaultNamespace("water"), water.liquid().orElseThrow());
        assertFalse(BuiltInRegistries.SCENE_ELEMENTS
                        .get(Identifier.withDefaultNamespace("grass")).isLiquid(),
                "grass is a plain texture and must not be routed to the liquid pass");
    }

    @Test
    void hexColoursParseInEveryAcceptedForm() {
        assertArrayEquals(new float[]{1F, 0F, 0F, 1F}, LiquidDef.parseColor("#FF0000"));
        assertArrayEquals(new float[]{1F, 0F, 0F, 1F}, LiquidDef.parseColor("#F00"));
        assertArrayEquals(new float[]{1F, 0F, 0F, 1F}, LiquidDef.parseColor("FF0000"));
        assertArrayEquals(new float[]{1F, 0F, 0F, 0.5019608F}, LiquidDef.parseColor("#FF000080"), 0.002F);
        assertArrayEquals(new float[]{1F, 1F, 1F, 1F}, LiquidDef.parseColor(null));
        // A typo must fall back to white rather than take the renderer down.
        assertArrayEquals(new float[]{1F, 1F, 1F, 1F}, LiquidDef.parseColor("#GGGGGG"));
        assertArrayEquals(new float[]{1F, 1F, 1F, 1F}, LiquidDef.parseColor("#12345"));
    }

    @Test
    void aMinimalDefinitionFillsInEveryDefault() {
        // This is the whole point of the defaults: a mod author writes three lines
        // and gets a working liquid.
        String json = "{\"id\":\"test:slime\",\"deep_color\":\"#2E6B1F\"}";
        LiquidDef slime = LiquidDef.CODEC
                .parse(JsonOps.INSTANCE, com.google.gson.JsonParser.parseString(json))
                .getOrThrow();
        assertEquals(Identifier.parse("test:slime"), slime.id());
        assertEquals(LiquidDef.DEFAULT_BASE_TEXTURE, slime.resolvedBaseTexture(),
                "an unspecified base texture falls back to the shared water floor");
        // The unsaid shallow colour defaults to #7FB6BE80: half-transparent, so its
        // alpha is 0x80/255 rather than 1.
        assertEquals(0.498F, slime.shallowColor()[0], 0.002F);
        assertEquals(128F / 255F, slime.shallowColor()[3], 0.002F);
        // The authored #2E6B1F.
        org.junit.jupiter.api.Assertions.assertEquals(107F / 255F, slime.deepColor()[1], 0.002F);
        assertEquals(LiquidDef.WaveShape.DEFAULT.speed(), slime.wave().speed(), 0.0001F);
        assertEquals(LiquidDef.FoamStyle.DEFAULT.width(), slime.foam().width(), 0.0001F);
    }

    @Test
    void everyFeatureBitIsReachableFromTheQualityTiers() {
        int low = LiquidRenderer.featuresFor(com.pvzce.client.config.PvzceClientConfig.WaterQuality.LOW);
        int medium = LiquidRenderer.featuresFor(com.pvzce.client.config.PvzceClientConfig.WaterQuality.MEDIUM);
        int high = LiquidRenderer.featuresFor(com.pvzce.client.config.PvzceClientConfig.WaterQuality.HIGH);
        assertTrue((low & LiquidShader.FEATURE_SHORE) != 0, "even the lowest tier needs a shoreline");
        assertTrue((medium & LiquidShader.FEATURE_WAVES) != 0, "and motion");
        assertTrue((medium & LiquidShader.FEATURE_CAUSTICS) == 0, "but not caustics");
        assertEquals(LiquidShader.FEATURE_WAVES | LiquidShader.FEATURE_CAUSTICS
                        | LiquidShader.FEATURE_FRESNEL | LiquidShader.FEATURE_SPECULAR
                        | LiquidShader.FEATURE_RIPPLES | LiquidShader.FEATURE_SHORE,
                high & (LiquidShader.FEATURE_WAVES | LiquidShader.FEATURE_CAUSTICS
                        | LiquidShader.FEATURE_FRESNEL | LiquidShader.FEATURE_SPECULAR
                        | LiquidShader.FEATURE_RIPPLES | LiquidShader.FEATURE_SHORE));
        assertTrue((low & high) == low, "the tiers must be nested subsets");
        assertTrue((medium & high) == medium);
    }

    private static void assertArrayEquals(float[] expected, float[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual, 1e-6F);
    }

    private static void assertArrayEquals(float[] expected, float[] actual, float tolerance) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual, tolerance);
    }

}
