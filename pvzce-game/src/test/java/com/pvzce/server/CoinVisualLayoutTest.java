package com.pvzce.server;

import com.pvzce.api.content.ResourceDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.EntityArt;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every currency the world can hold must be something the player can see.
 *
 * <p>A dropped coin resolves to an animation when one exists and to a flat
 * {@code textures/entities/<path>.png} otherwise, and both halves fail silently: the
 * entity is spawned, the server counts it, and nothing is drawn. The four denominations
 * are the case that matters, because three come from converted reanims and the fourth
 * is a single sprite.
 */
class CoinVisualLayoutTest {
    /**
     * Every denomination ships an icon.
     *
     * <p>The icon is what the card, the HUD and the fly-to-bank animation use. Whether the drop
     * itself draws comes from the art resolvers, and that is covered by
     * {@code AnimationManagerIntegrationTest::everyDenominationPlaysAsADrop}.
     */
    @Test
    void everyDenominationHasAnIcon() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();

        for (Identifier denomination : PvzceIds.COIN_DENOMINATIONS) {
            ResourceDef def = BuiltInRegistries.RESOURCES.get(denomination);
            assertNotNull(def, denomination + " must be a registered resource");
            assertPresent(loader, "assets/" + def.icon().toPath() + ".png", denomination + " icon");
        }
    }

    private static void assertPresent(ClassLoader loader, String path, String what) throws Exception {
        try (InputStream in = loader.getResourceAsStream(path)) {
            assertNotNull(in, what + " is missing: " + path);
        }
    }

    /**
     * A coin's art is authored square, at exactly the size {@code render_scale} draws it.
     *
     * <p>For an animated drop the renderer fits the <em>width</em> to {@code 0.8 *
     * render_scale} cells and takes the height from the art itself. That makes the art's own
     * height the second half of the coin's size, and it is why "make the coins smaller" first
     * produced thin slivers: {@code render_scale} shrank one axis and the art kept the other.
     * A coin whose art is not square at that size is drawn squashed, so this pins both.
     */
    @Test
    void aCoinsArtIsAuthoredAtTheSizeRenderScaleDrawsIt() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();

        for (Identifier denomination : List.of(PvzceIds.COIN_SILVER, PvzceIds.COIN_GOLD, PvzceIds.DIAMOND)) {
            ResourceDef def = BuiltInRegistries.RESOURCES.get(denomination);
            Identifier file = EntityArt.animationFile(denomination);
            String path = "assets/" + file.namespace() + "/animations/" + file.path() + ".json";
            try (InputStream in = loader.getResourceAsStream(path)) {
                assertNotNull(in, denomination + " needs an animation at " + path);
                JsonObject model = JsonParser.parseReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject()
                        .getAsJsonObject("model");
                float expected = 0.8F * def.renderScale();
                assertEquals(expected, model.getAsJsonArray("size").get(0).getAsFloat(), 0.02F,
                        denomination + " model width");
                assertEquals(expected, model.getAsJsonArray("size").get(1).getAsFloat(), 0.02F,
                        denomination + " model height: a drop's height comes from its art, so a "
                                + "non-square model is drawn squashed");
            }
        }
    }
}
