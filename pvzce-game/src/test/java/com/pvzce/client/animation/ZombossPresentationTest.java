package com.pvzce.client.animation;

import com.pvzce.client.ClientEntity;
import com.pvzce.client.ClientLevel;
import com.pvzce.client.renderer.ZombossArt;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.EntityArt;
import com.pvzce.common.level.mechanic.ZombossMechanic;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.tag.TestContent;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.pvzce.common.PvzceConstants.*;
import static org.junit.jupiter.api.Assertions.*;

class ZombossPresentationTest {
    @Test void iceSpitReplacesOnlyTheColoredGlowsAndTheFiveMouthPositionsMatchTheirRows() throws Exception {
        try (var resources = TestContent.loadBuiltInContentAndTags()) {
            ClientLevel level = new ClientLevel();
            AnimationManager manager = new AnimationManager(null, level, resources);
            level.setAnimationManager(manager);
            ClientEntity boss = level.addEntity(new ClientEntity(1, "zombie", "pvzce:zombie_boss",
                    ZOMBOSS_HEAD_X, ZOMBOSS_HEAD_Y, 60000, 2, "boss_head_attack_1", 0F, ""));
            boss.playAnimation("boss_head_attack_1");
            ControllerFile file = (ControllerFile)manager.playback(boss).file();
            var pose = file.clips().get("boss_head_attack_1").samplePose(file.model(), 1.6D);
            assertTrue(pose.get("mouthglow_red").visible());
            level.setMechanicState(PvzceIds.MECHANIC_ZOMBOSS, status(2));
            var blue = ZombossArt.forEntity(boss, level).visibleBones(file.model(), pose);
            assertFalse(blue.contains("mouthglow_red"));
            assertFalse(blue.contains("eyeglow_red"));
            assertTrue(blue.containsAll(List.of("mouthglow", "eyeglow", "mouthglow_blue", "eyeglow_blue")));
            assertEquals(pose.get("mouthglow_red").alpha(), pose.get("mouthglow_blue").alpha());
            level.setMechanicState(PvzceIds.MECHANIC_ZOMBOSS, status(1));
            var red = ZombossArt.forEntity(boss, level).visibleBones(file.model(), pose);
            assertTrue(red.contains("mouthglow_red"));
            assertFalse(red.contains("mouthglow_blue"));
            for (int row = 0; row < 5; row++) {
                var clip = file.clips().get("boss_head_attack_" + (5 - row));
                float mouth = clip.samplePose(file.model(), clip.duration() * 0.42D)
                        .get("mouthglow").translation()[1] + ZOMBOSS_ANCHOR_Y - 0.36F;
                assertEquals(row + 0.91F, mouth, 0.3F, "mouth and release must share a roof row");
            }
            var death = file.clips().get("death");
            assertTrue(death.duration() / death.rate() <= ZOMBOSS_DEATH_TICKS / (float)TICKS_PER_SECOND);
        }
    }

    @Test void elementalBallsHaveGroundedContactsAndSeekToTheirSyncedFormationAge() throws Exception {
        try (var resources = TestContent.loadBuiltInContentAndTags()) {
            var manager = new AnimationManager(null, new ClientLevel(), resources);
            for (var id : List.of(EntityArt.ZOMBOSS_FIREBALL_ANIMATION, EntityArt.ZOMBOSS_ICEBALL_ANIMATION)) {
                ArtTarget target = new ArtTarget(id);
                target.attach(manager);
                target.play("form");
                var playback = manager.playback(target);
                assertNotNull(playback);
                playback.seek(0.5F * playback.effectiveSpeed());
                assertEquals(0.5F, playback.progress(), 0.001F);
                var file = (ControllerFile)playback.file();
                var ground = file.clips().get("idle").samplePose(file.model(), 0D)
                        .get("zombie_boss_icefire_shadow").translation();
                assertEquals(0F, ground[0], 0.0001F);
                assertEquals(0F, ground[1], 0.0001F);
            }
        }
    }

    @Test void mechanicSnapshotKeepsSpitTypeBallAgeHandRopesAndPilotClocksTogether() {
        var state = status(2);
        var expected = new ZombossMechanic.Status(state.health(), state.maxHealth(), state.phase(), state.ticksLeft(),
                1, 0, 2, 7.5F, 0, false, 30, 2,
                List.of(new ZombossMechanic.BungeeRope(123, 2, 4, 3.13F, 5.07F)),
                new ZombossMechanic.Pilot(true, 1800, 3410, 0, 120, 1500, 600, true));
        var bytes = Unpooled.buffer();
        try {
            PacketByteBuf buf = new PacketByteBuf(bytes);
            ZombossMechanic.Status.CODEC.encode(expected, buf);
            assertEquals(expected, ZombossMechanic.Status.CODEC.decode(buf));
        } finally { bytes.release(); }
    }

    private static ZombossMechanic.Status status(int type) {
        return new ZombossMechanic.Status(60000, 60000, "head_spit", 100, 0, 4,
                0, 0F, 0, false, 0, type);
    }
}
