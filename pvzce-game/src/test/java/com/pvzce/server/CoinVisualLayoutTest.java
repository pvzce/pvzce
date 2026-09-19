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
     * A dropped denomination's model has the same shape as the art it is drawn from.
     *
     * <p>That is the whole of "not squashed": the renderer fits a drop to
     * {@code 0.8 * render_scale} cells in <em>both</em> dimensions, so a model whose aspect
     * ratio differs from its own artwork's is stretched on one axis. The rule the renderer
     * used to follow was worse than wrong, it was self-contradicting - the width was fitted to
     * that target while the height came from the art - so the model had to be hand-authored at
     * whatever number the fit produced. That is why the coins sat at a hand-edited {@code 0.2}
     * and why re-running the converter silently put them back.
     *
     * <p>Checked against the part's own UV rectangle, which is the source PNG's pixel box: the
     * model's aspect and the artwork's aspect are the same statement, and comparing them needs
     * no knowledge of what the numbers are.
     */
    @Test
    void everyDenominationsModelHasTheSameShapeAsItsArt() throws Exception {
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

                float largest = 0F;
                for (var bone : model.getAsJsonArray("bones")) {
                    JsonObject entry = bone.getAsJsonObject();
                    if (!entry.has("parts")) {
                        continue;
                    }
                    for (var part : entry.getAsJsonArray("parts")) {
                        JsonObject shape = part.getAsJsonObject();
                        if (shape.has("blend") && "add".equals(shape.get("blend").getAsString())) {
                            // A flare is drawn over the sprite rather than being it, and its
                            // shape is its own business.
                            continue;
                        }
                        float modelWidth = shape.getAsJsonArray("size").get(0).getAsFloat();
                        float modelHeight = shape.getAsJsonArray("size").get(1).getAsFloat();
                        var uv = shape.getAsJsonArray("uv");
                        float artWidth = uv.get(2).getAsFloat() - uv.get(0).getAsFloat();
                        float artHeight = uv.get(3).getAsFloat() - uv.get(1).getAsFloat();
                        assertTrue(artWidth > 0F && artHeight > 0F, denomination + " part UV has no area");
                        assertEquals(artWidth / artHeight, modelWidth / modelHeight, 0.02F,
                                denomination + " part " + entry.get("name").getAsString()
                                        + ": the model must have the artwork's proportions, or the "
                                        + "drop is drawn stretched");
                        largest = Math.max(largest, Math.max(modelWidth, modelHeight));
                    }
                }

                // And the art is authored at the agreed size rather than at whatever a fit
                // happened to produce, so a drop's drawn size is a number in the data. Checked
                // as a band rather than an equality because the box fits the sprite *sheet* the
                // parts were cut from: a denomination whose tallest sprite is not its widest
                // one (the gold coin's dollar sign) lands a little under the box, and that is
                // the source art's proportions talking, not a sizing rule.
                float modelHeight = model.getAsJsonArray("size").get(1).getAsFloat();
                float authored = com.pvzce.client.renderer.EntityVisuals.AUTHORED_DROP_CELLS;
                assertTrue(modelHeight > authored * 0.6F && modelHeight < authored * 1.5F,
                        denomination + " is authored at " + modelHeight + " cells tall, nowhere near"
                                + " the " + authored + " the converter and the renderer agree on");
            }
        }
    }
}
