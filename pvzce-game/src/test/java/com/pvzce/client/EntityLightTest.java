package com.pvzce.client;

import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.EntityKind;
import com.pvzce.api.entity.EntityLayers;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which drops light the board.
 *
 * <p>The sun is a light source - it lands in the middle of the lawn and warms the tiles
 * around it - and nothing else is. That distinction used to be made by <em>entity kind</em>,
 * which is the one thing a sun and a coin share: {@code EntityKind.RESOURCE} was the string
 * {@code "sun"}, so the filter matched every drop and each coin the bowling nut paid out lit
 * a pool of yellow light on the lawn. Content ids are the answer to "which resource is
 * this"; kinds never are.
 */
class EntityLightTest {
    private static ClientEntity drop(Identifier content, float x, float y) {
        return new ClientEntity(1, EntityKind.RESOURCE, content.toString(), x, y, 1,
                EntityLayers.AIR, EntityAnimations.IDLE, 0F, "");
    }

    @Test
    void onlyTheSunLightsTheBoard() {
        assertTrue(PvzceClient.lightsTheBoard(drop(PvzceIds.SUN, 4.5F, 2.5F)),
                "the sun is the one drop that lights the lawn");
        for (Identifier denomination : PvzceIds.COIN_DENOMINATIONS) {
            assertFalse(PvzceClient.lightsTheBoard(drop(denomination, 4.5F, 2.5F)),
                    denomination + " is currency: it must not glow like a sun");
        }
        assertFalse(PvzceClient.lightsTheBoard(null));
    }

    @Test
    void aResourceKindNeverNamesOneResource() {
        // The trap this test exists for: a kind is a category, so making it a content id
        // ("sun") silently turned every "is this a sun?" check into "is this a drop?".
        assertFalse(EntityKind.RESOURCE.contains(":"),
                "an entity kind is a category, not a content id: " + EntityKind.RESOURCE);
        assertFalse("sun".equals(EntityKind.RESOURCE));
        assertFalse(EntityKind.PLANT.equals(EntityKind.RESOURCE));
        assertFalse(EntityKind.ZOMBIE.equals(EntityKind.RESOURCE));
        assertFalse(EntityKind.PROJECTILE.equals(EntityKind.RESOURCE));
    }
}
