package com.pvzce.client.gui;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.gui.components.PaletteList;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.EntityArt;
import com.pvzce.common.core.SlotResolver;
import com.pvzce.common.resource.PvzceResourceManager;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every picture the editor draws is really there: the slot chain and the palette's icon chain.
 *
 * <p>Both of these shipped broken and neither had a test. The card list asked the <em>entity</em>
 * chain for its picture, so 44 of its 48 rows pointed at a path that is a directory of controller
 * parts; the plant and zombie palettes asked the same chain and got the same answer for all 77
 * content ids. The screenshots that would have shown it were never taken, and the failure mode - a
 * magenta and black checkerboard - is exactly the sort of thing a test can catch instead.
 *
 * <p>What is asserted is not "the editor is pretty" but the two facts that make it draw: a slot's
 * declared icon exists as a file, and skeleton content goes down the rig path rather than the
 * texture one.
 */
class EditorIconTest {
    private static PvzceResourceManager resources;

    @BeforeAll
    static void load() throws Exception {
        resources = TestContent.loadBuiltInContentAndTags();
    }

    @Test
    void everySlotIconIsATextureThatExists() {
        List<String> missing = new ArrayList<>();
        for (Identifier slotId : BuiltInRegistries.SLOT_TYPES.keySet()) {
            var card = SlotResolver.resolve(slotId).orElse(null);
            if (card == null) {
                missing.add(slotId + " does not resolve at all");
                continue;
            }
            Identifier icon = card.icon().orElse(null);
            if (icon == null) {
                // A card with no art at all is a supported state (the row draws a swatch); what is
                // not supported is naming a picture that is not there.
                continue;
            }
            if (!hasTexture(icon)) {
                missing.add(slotId + " names " + icon + ", which is not a texture in the pack");
            }
        }
        assertTrue(missing.isEmpty(), "these card faces would draw a missing-texture tile: " + missing);
    }

    @Test
    void everyPlantAndZombieReachesTheEditorAsARig() {
        // Skeleton content cannot be reduced to one texture: every built-in plant and zombie
        // declares a *directory* of parts, so the palette has to draw the rig. This is the property
        // that was false for a year without anybody noticing.
        for (Identifier id : BuiltInRegistries.PLANTS.keySet()) {
            assertRig(id, "plant");
        }
        for (Identifier id : BuiltInRegistries.ZOMBIES.keySet()) {
            assertRig(id, "zombie");
        }
    }

    @Test
    void aRigRowGoesLookingForItsOwnPartTextures() {
        // The other half of the same fact: the texture the *definition* declares is a directory,
        // which is why "prefer the declared texture" could never work.
        Identifier pea = Identifier.withDefaultNamespace("pea_shooter");
        assertNotNull(EntityArt.animationFile(pea), "a plant's art is an animation file");
        assertTrue(!hasTexture(EntityArt.sprite(pea)),
                "and the texture it declares is a directory of parts, not a picture");
    }

    private static void assertRig(Identifier id, String kind) {
        PaletteList.Item.Icon icon = PaletteList.iconFor(null, PaletteList.Kind.ENTITY, kind, id);
        assertNotNull(icon, id + " has no picture at all, so its row would be a bare swatch");
        assertEquals(PaletteList.Item.Icon.Rig.class, icon.getClass(),
                id + " does not reach the palette as a live rig");
        PaletteList.Item.Icon.Rig rig = (PaletteList.Item.Icon.Rig) icon;
        assertEquals(kind, rig.kind(), "a rig is built in its registry's kind");
        assertEquals(id, rig.contentId());
    }

    /** True when the pack really ships this texture as a file. */
    private static boolean hasTexture(Identifier texture) {
        try {
            return resources.getResource(
                    "assets/" + texture.namespace() + "/" + texture.path() + ".png").isPresent();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not read the pack for " + texture, e);
        }
    }
}
