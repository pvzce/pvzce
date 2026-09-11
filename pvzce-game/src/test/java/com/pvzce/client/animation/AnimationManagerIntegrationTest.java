package com.pvzce.client.animation;

import com.pvzce.client.ClientEntity;
import com.pvzce.client.ClientLevel;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.resource.PvzceResourceManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationManagerIntegrationTest {
    @Test
    void resolvesPathConventionAndKeepsRequestedStateIdempotent() throws Exception {
        BuiltInRegistries.bootstrap();
        PvzceResourceManager resources = new PvzceResourceManager(Thread.currentThread().getContextClassLoader());
        resources.init(Files.createTempDirectory("pvzce-animation-test"));
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
}
