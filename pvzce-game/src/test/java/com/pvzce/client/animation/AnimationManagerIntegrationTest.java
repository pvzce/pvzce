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

    /**
     * A ducked scaredy-shroom ends up crying, and asking it to duck again does not undo that.
     *
     * <p>The reported bug. {@code ScaredyShroom.reanim} draws the duck as two clips - the
     * one-shot that puts the head down and the loop that holds it there - and the art file says so
     * with {@code on_end: hide_loop}. The server publishes the single state {@code hide} for as
     * long as a zombie stands next to the plant, and the client asks for that state on every
     * frame; the manager read the hand-over as "the state is no longer playing", restarted the
     * duck, and the cry never appeared. The plant ducked, re-ducked and never cried.
     *
     * <p>The other half of the rule is asserted here too, because it is the half that made the
     * first one delicate: a clip that hands over to {@code idle} (every {@code shoot}) *is* over,
     * so the next request for it has to replay it. Without that, a shooter would fire once per
     * level.
     */
    @Test
    void aDuckedScaredyShroomHandsOverToItsCryAndStaysThere() throws Exception {
        PvzceResourceManager resources = TestContent.loadBuiltInContentAndTags();
        ClientLevel level = new ClientLevel();
        AnimationManager manager = new AnimationManager(null, level, resources);
        level.setAnimationManager(manager);
        ClientEntity shroom = level.addEntity(new ClientEntity(
                1, "plant", "pvzce:scaredy_shroom", 1.5F, 2.5F, 300,
                com.pvzce.api.entity.EntityLayers.PLANT,
                com.pvzce.api.entity.EntityAnimations.HIDE, 0F, ""));

        manager.play(shroom, com.pvzce.api.entity.EntityAnimations.HIDE);
        assertEquals("hide", manager.playback(shroom).activeName(),
                "a scaredy-shroom ducks first");

        assertEquals("hide_loop", advanceUntilClip(manager, level, shroom, "hide_loop"),
                "the duck has to hand over to the crying loop it names in `on_end`");

        // The server keeps publishing `hide` while the zombie is there, so this is what the
        // render path does every frame. It must be idempotent - the duck is over.
        manager.play(shroom, com.pvzce.api.entity.EntityAnimations.HIDE);
        assertEquals("hide_loop", manager.playback(shroom).activeName(),
                "re-requesting `hide` must not restart the duck: that is what hid the cry");

        // And the state that ends on `idle` still replays, or a shooter fires once per level.
        manager.play(shroom, com.pvzce.api.entity.EntityAnimations.SHOOT);
        assertEquals("shoot", manager.playback(shroom).activeName());
        assertEquals("idle", advanceUntilClip(manager, level, shroom, "idle"),
                "a shoot clip hands over to idle when it is done");
        manager.play(shroom, com.pvzce.api.entity.EntityAnimations.SHOOT);
        assertEquals("idle", manager.playback(shroom).activeName(),
                "drawing an unchanged shoot state cannot invent another shot");
        shroom.apply(new com.pvzce.common.network.packet.EntityUpdateS2C(shroom.id(), 1.5F, 2.5F,
                300, "shoot", 0F, -1, false, false, false, false, 1, ""));
        assertEquals("shoot", manager.playback(shroom).activeName(),
                "a new authoritative action sequence replays the same named gesture");
        resources.close();
    }

    /**
     * Drives the client clock forward until the entity is playing {@code clip}, and reports the
     * clip it ended on.
     *
     * <p>The clock is the level's, which follows the F3 heartbeat: pushing a tick count in and
     * re-anchoring is how the real client learns the server tick, so this advances the animation
     * at game speed without sleeping for the clip's real duration. The step is deliberately
     * coarse - a playback that reaches its end late still reaches it - and the loop is bounded so
     * a clip that never arrives fails the assertion instead of hanging the suite.
     */
    private static String advanceUntilClip(AnimationManager manager, ClientLevel level,
                                           ClientEntity entity, String clip) throws InterruptedException {
        for (int tick = 0; tick < 2_000; tick += 4) {
            level.setDebugInfo(tick, tick, false, false);
            manager.tick();
            AnimationPlayback playback = manager.playback(entity);
            if (playback != null && clip.equals(playback.activeName())) {
                return clip;
            }
            Thread.sleep(1L);
        }
        AnimationPlayback playback = manager.playback(entity);
        return playback == null ? "none" : playback.activeName();
    }
}
