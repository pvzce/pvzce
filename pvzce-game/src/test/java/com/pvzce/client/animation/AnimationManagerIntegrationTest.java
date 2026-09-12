package com.pvzce.client.animation;

import com.pvzce.client.ClientEntity;
import com.pvzce.client.ClientLevel;
import com.pvzce.common.resource.PvzceResourceManager;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationManagerIntegrationTest {
    @Test
    void resolvesPathConventionAndKeepsRequestedStateIdempotent() throws Exception {
        // The data pack has to be loaded, not just bootstrapped: the animation directory
        // is declared by the content definitions, so without them the client would look
        // for the old flat layout that no longer exists.
        PvzceResourceManager resources = TestContent.loadBuiltInContentAndTags();
        ClientLevel level = new ClientLevel();
        AnimationManager manager = new AnimationManager(null, level, resources);
        level.setAnimationManager(manager);

        ClientEntity peaShooter = level.addEntity(new ClientEntity(
                1, "plant", "pvzce:pea_shooter", 1.5F, 2.5F, 300,
                com.pvzce.api.entity.EntityLayers.PLANT,
                com.pvzce.api.entity.EntityAnimations.IDLE, 0F, ""));
        ClientEntity basicZombie = level.addEntity(new ClientEntity(
                2, "zombie", "pvzce:basic_zombie", 5.5F, 2.5F, 200,
                com.pvzce.api.entity.EntityLayers.GROUND,
                com.pvzce.api.entity.EntityAnimations.IDLE, 0F, ""));
        ClientEntity unknown = level.addEntity(new ClientEntity(
                3, "plant", "pvzce:missing_animation", 1.5F, 2.5F, 300,
                com.pvzce.api.entity.EntityLayers.PLANT,
                com.pvzce.api.entity.EntityAnimations.IDLE, 0F, ""));

        AnimationHandle idle = peaShooter.playAnimation("idle");
        assertTrue(idle.isActive());
        assertEquals("idle", idle.animation());
        AnimationPlayback firstPlayback = manager.playback(peaShooter);

        AnimationHandle sameIdle = peaShooter.playAnimation("idle");
        assertTrue(sameIdle.isActive());
        assertEquals("idle", sameIdle.animation());
        assertSame(firstPlayback, manager.playback(peaShooter));

        AnimationHandle shoot = peaShooter.playAnimation("shoot");
        assertTrue(shoot.isActive());
        assertEquals("shoot", shoot.animation());
        assertNotSame(idle, shoot);

        AnimationHandle walk = basicZombie.playAnimation("walk");
        assertTrue(walk.isActive());
        assertEquals("walk", walk.animation());

        AnimationHandle missing = unknown.playAnimation("idle");
        assertEquals(AnimationHandle.NONE, missing);

        manager.tick();
        assertNotNull(manager.playback(peaShooter));
        resources.close();
    }

    @Test
    void everyDenominationPlaysAsADrop() throws Exception {
        // The coin and diamond reanims come from refer/im5 and were converted with a
        // different --input-dir than the rest; a broken one would fail here rather than
        // as an invisible drop in a live level.
        PvzceResourceManager resources = TestContent.loadBuiltInContentAndTags();
        ClientLevel level = new ClientLevel();
        AnimationManager manager = new AnimationManager(null, level, resources);
        level.setAnimationManager(manager);

        int id = 1;
        for (com.pvzce.api.util.Identifier denomination : com.pvzce.common.PvzceIds.COIN_DENOMINATIONS) {
            ClientEntity drop = level.addEntity(new ClientEntity(
                    id++, com.pvzce.api.entity.EntityKind.RESOURCE, denomination.toString(),
                    3.5F, 1.5F, 1, com.pvzce.api.entity.EntityLayers.GROUND,
                    com.pvzce.api.entity.EntityAnimations.IDLE, 0F, ""));
            AnimationHandle idle = drop.playAnimation("idle");
            // Three denominations come from converted reanims; the money bag is a single
            // sprite and is drawn through the flat-texture fallback instead, which is why
            // either answer is acceptable but neither being available is not. The sprite
            // is asked of the same resolver the renderer uses, so this keeps testing the
            // real path after the art was grouped by kind.
            com.pvzce.api.util.Identifier sprite =
                    com.pvzce.common.core.EntityArt.sprite(denomination);
            boolean flatSprite = resources
                    .getResource("assets/" + sprite.toPath() + ".png")
                    .isPresent();
            assertTrue(idle.isActive() || flatSprite,
                    denomination + " must have a drop animation or a flat sprite");
            if (idle.isActive()) {
                assertNotNull(manager.playback(drop), denomination + " must have a live playback");
            }
        }
        manager.tick();
        resources.close();
    }
}
