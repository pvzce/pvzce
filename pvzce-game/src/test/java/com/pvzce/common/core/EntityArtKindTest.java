package com.pvzce.common.core;

import com.pvzce.api.content.AnimationBindings;
import com.pvzce.api.entity.EntityKind;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One content id, two registries: which definition answers.
 *
 * <p>The snow pea is both a plant and the projectile that plant fires, and every art lookup
 * used to search the registries in a fixed order - plants first. The projectile therefore
 * inherited the plant's {@code animation_dir} and was drawn as a whole snow pea plant flying
 * down the lane. The fix is that the kind the caller already knows travels with the id; these
 * pin the answer for both sides of that collision, and that a caller with only an id still
 * gets the old answer.
 */
class EntityArtKindTest {
    private static final Identifier SNOW_PEA = Identifier.withDefaultNamespace("snow_pea");

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    @Test
    void theSameIdAnswersWithTheKindItWasAskedAbout() {
        assertNotNull(BuiltInRegistries.PLANTS.get(SNOW_PEA), "the plant exists");
        assertNotNull(BuiltInRegistries.PROJECTILES.get(SNOW_PEA), "and so does the projectile");

        AnimationBindings plant = EntityArt.bindings(SNOW_PEA, EntityKind.PLANT);
        AnimationBindings projectile = EntityArt.bindings(SNOW_PEA, EntityKind.PROJECTILE);
        assertNotNull(plant);
        assertNotNull(projectile);

        assertEquals(Optional.of("plant/attacker"), plant.animationDir(),
                "the plant's animation lives in the grouped art directory");
        assertTrue(projectile.animationDir().isEmpty(),
                "the projectile declares no directory: its file would be animations/snow_pea.json,"
                        + " which does not exist, so it falls back to its own sprite");
    }

    @Test
    void theProjectileIsDrawnFromItsOwnFrozenPeaSpiteAndNotThePlant() {
        Identifier asProjectile = EntityArt.sprite(SNOW_PEA, EntityKind.PROJECTILE);
        Identifier asPlant = EntityArt.sprite(SNOW_PEA, EntityKind.PLANT);

        assertEquals(Identifier.withDefaultNamespace("textures/entities/projectile/frozen_pea"),
                asProjectile, "the frozen pea the snow pea fires");
        assertEquals(Identifier.withDefaultNamespace("textures/entities/plant/attacker/snow_pea"),
                asPlant, "and the plant keeps its own art");
        assertFalse(asProjectile.equals(asPlant), "the two must not be the same picture");
    }

    @Test
    void anIdOnlyCallerStillGetsAnAnswer() {
        // Cards, editor palettes and anything else holding just an id: the old search order is
        // still a valid answer, it is simply not the one an entity should be drawn from.
        assertEquals(EntityArt.bindings(SNOW_PEA), EntityArt.bindings(SNOW_PEA, null));
        assertEquals(EntityArt.sprite(SNOW_PEA), EntityArt.sprite(SNOW_PEA, null));
    }
}
