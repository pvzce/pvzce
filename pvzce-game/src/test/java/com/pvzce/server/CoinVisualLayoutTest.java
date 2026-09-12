package com.pvzce.server;

import com.pvzce.api.content.ResourceDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.EntityArt;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

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
    @Test
    void everyDenominationHasSomethingToDraw() throws Exception {
        TestContent.loadBuiltInContentAndTags();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();

        for (Identifier denomination : PvzceIds.COIN_DENOMINATIONS) {
            ResourceDef def = BuiltInRegistries.RESOURCES.get(denomination);
            assertNotNull(def, denomination + " must be a registered resource");
            assertTrue(def.defaultValue() > 0, denomination + " must be worth something");
            assertTrue(def.collectibleWithoutCard(),
                    denomination + " is currency: picking it up must not need a card slot");

            // The icon is what the card, the HUD and the fly-to-bank animation use.
            assertPresent(loader, "assets/" + def.icon().toPath() + ".png", denomination + " icon");

            // The drop itself: an animation controller, or the flat sprite it falls back
            // to. Both paths come from the production resolvers rather than from a
            // hand-written layout, because the shipped art is grouped by kind and a copy
            // of that layout in a test would keep passing while the game drew nothing.
            Identifier animationFile = EntityArt.animationFile(denomination);
            String animation = "assets/" + animationFile.namespace() + "/animations/"
                    + animationFile.path() + ".json";
            Identifier sprite = EntityArt.sprite(denomination);
            String spritePath = "assets/" + sprite.toPath() + ".png";
            assertTrue(loader.getResource(animation) != null || loader.getResource(spritePath) != null,
                    denomination + " needs either " + animation + " or " + spritePath);
        }
    }

    private static void assertPresent(ClassLoader loader, String path, String what) throws Exception {
        try (InputStream in = loader.getResourceAsStream(path)) {
            assertNotNull(in, what + " is missing: " + path);
        }
    }
}
