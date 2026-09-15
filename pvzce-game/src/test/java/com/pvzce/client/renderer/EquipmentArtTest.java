package com.pvzce.client.renderer;

import com.pvzce.api.content.ZombieDef;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.EntityLayers;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientEntity;
import com.pvzce.client.animation.AnimationManager;
import com.pvzce.client.animation.AnimationPlayback;
import com.pvzce.client.animation.BoneArt;
import com.pvzce.client.animation.BonePose;
import com.pvzce.client.animation.ControllerModel;
import com.pvzce.client.animation.ControllerPlayback;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.resource.PvzceResourceManager;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The last word on which bones a zombie draws.
 *
 * <p>{@code EquipmentArt} is the only place that answers "is this sprite part of this
 * zombie", and until now nothing tested it: a plan that resolved to nothing, or a bone
 * family whose names did not match the model, would have looked exactly like a working
 * one until somebody noticed an extra arm on the lawn.
 *
 * <p>The {@code hidden_bones} case is the one that needs pinning: the flag zombie holds its
 * flag with the rip's own hand <em>and</em> still carries the ordinary zombie's outer arm,
 * so the definition has to be able to say "these two sprites are the same limb".
 */
class EquipmentArtTest {
    private static PvzceResourceManager resources;
    private static AnimationManager manager;

    @BeforeAll
    static void setUp() throws Exception {
        resources = TestContent.loadBuiltInContentAndTags();
        manager = new AnimationManager(null, new com.pvzce.client.ClientLevel(), resources);
    }

    @AfterAll
    static void tearDown() throws java.io.IOException {
        resources.close();
    }

    @Test
    void theFlagZombieDrawsItsOwnArmAndNotTheOrdinaryOnes() {
        // The rip carries the flag-holding hand in the flag's own reanim and the ordinary
        // arm in the base zombie's, so a flag zombie that draws both holds the flag with one
        // hand while another hangs beside it. Every arm bone the equipment names must be
        // gone - and the flag's own hand and pole must still be there, which is what stops
        // "hide both families" from passing this.
        Set<String> visible = visibleBones("pvzce:flag_zombie", EntityAnimations.WALK);
        for (String bone : armBones("pvzce:flag_zombie")) {
            assertFalse(visible.contains(bone),
                    "the ordinary arm must not be drawn on a flag zombie, but " + bone + " was");
        }
        assertTrue(visible.contains("flaghand"),
                "the flag zombie has to keep the hand that holds the pole: " + visible);
        assertTrue(visible.contains("zombie_flag_1") || visible.contains("zombie_flag_3"),
                "and the flag itself: " + visible);
    }

    @Test
    void theOrdinaryArmComesBackWhenTheFlagIsGone() {
        // The other half of the rule, and the reason it is scoped to "the frames the flag is
        // drawn" rather than to the whole zombie: the death clip drops the flag, so the hand
        // that carried it is gone, and an armless zombie falling over is worse than the extra
        // arm this fixes.
        Set<String> visible = visibleBones("pvzce:flag_zombie", EntityAnimations.DEATH);
        assertFalse(visible.contains("flaghand"), "the flag's hand is not drawn in death: " + visible);
        assertTrue(visible.contains("innerarm_hand"),
                "so the ordinary arm has to be: " + visible);
    }

    @Test
    void anOrdinaryZombieStillDrawsItsOuterArm() {
        // The other direction, so the flag zombie's rule cannot leak into the shared model:
        // an ordinary zombie has exactly one arm and it is the outer one.
        Set<String> visible = visibleBones("pvzce:basic_zombie", EntityAnimations.WALK);
        assertTrue(visible.contains("outerarm_hand"),
                "an ordinary zombie must still draw its outer arm: " + visible);
    }

    @Test
    void aZombieWithNothingToOverrideHasNoBoneArt() {
        // Null matters: the renderer skips building a bone set for it, and building one per
        // frame for every zombie on the lawn is exactly the work this short-circuit exists
        // to avoid. A zombie with no equipment, no arm to lose and nothing hidden is the
        // case - an ordinary zombie is *not*, because it still loses an arm at half health.
        ZombieDef silent = BuiltInRegistries.ZOMBIES.get(Identifier.parse("pvzce:gargantuar"));
        assertTrue(silent.equipment().isEmpty(), "this test needs an unequipped zombie");
        assertFalse(silent.dropsArm(), "and one that keeps its arms");
        assertTrue(silent.hiddenBones().isEmpty(), "and one that hides nothing");
        assertNull(boneArt("pvzce:gargantuar"), "nothing to override means no override");
    }

    @Test
    void anArmlessZombieStillGetsItsOverride() {
        // The other half of the short-circuit, and the bug the condition had to be written
        // around: a zombie with nothing to hide still needs the override when it can lose an
        // arm, because "half health costs it the outer arm" is exactly what the override
        // implements. Null here would leave an armless zombie holding a detached arm.
        assertNotNull(boneArt("pvzce:basic_zombie"),
                "a zombie that drops an arm always needs the bone override");
    }

    /** Every arm bone the flag zombie's equipment claims as its own. */
    private static List<String> armBones(String zombieId) {
        ZombieDef def = BuiltInRegistries.ZOMBIES.get(Identifier.parse(zombieId));
        assertNotNull(def, zombieId + " must be a registered zombie");
        List<String> bones = new java.util.ArrayList<>();
        for (com.pvzce.api.content.EquipmentDef entry : def.equipment()) {
            bones.addAll(entry.armBones());
        }
        assertFalse(bones.isEmpty(), zombieId + " must declare arm_bones for this test");
        return bones;
    }

    /**
     * Every bone that would be drawn for one zombie in one clip.
     *
     * <p>The poses come from a real playback rather than an empty map, because the whole
     * point of this override is what it does <em>on top of</em> what the clip draws: an
     * empty pose set falls back to every bone's rest pose, in which nothing is ever hidden,
     * and the death clip's dropped flag would look like a flag that is still there.
     */
    private static Set<String> visibleBones(String zombieId, String animation) {
        ClientEntity entity = zombie(zombieId, animation);
        ControllerModel model = model(zombieId);
        BoneArt art = EquipmentArt.forEntity(entity, model);
        assertNotNull(art, zombieId + " must have a bone override to test");
        return art.visibleBones(model, poses(zombieId, animation));
    }

    /** The blended poses of one zombie's clip, exactly as the renderer would resolve them. */
    private static Map<String, BonePose> poses(String zombieId, String animation) {
        ClientEntity entity = zombie(zombieId, animation);
        // Through the manager and not through the entity: the entity's own playAnimation is a
        // convenience for spawned entities whose manager was injected by ClientLevel, and a
        // test entity has none.
        manager.play(entity, animation);
        AnimationPlayback playback = manager.playback(entity);
        assertTrue(playback instanceof ControllerPlayback,
                zombieId + "/" + animation + " must resolve to a controller playback");
        return ((ControllerPlayback) playback).currentPose(manager.now());
    }

    private static BoneArt boneArt(String zombieId) {
        return EquipmentArt.forEntity(zombie(zombieId, EntityAnimations.WALK), model(zombieId));
    }

    /** The zombie's controller model, resolved the way the client resolves any art. */
    private static ControllerModel model(String zombieId) {
        Identifier fileId = com.pvzce.common.core.EntityArt.animationFile(
                Identifier.parse(zombieId));
        assertNotNull(fileId, zombieId + " must declare an animation file");
        var file = manager.file(fileId);
        assertTrue(file.isPresent(), zombieId + " must load its animation file " + fileId);
        assertTrue(file.get() instanceof com.pvzce.client.animation.ControllerFile,
                zombieId + " must be a controller model, not a flipbook");
        return ((com.pvzce.client.animation.ControllerFile) file.get()).model();
    }

    private static ClientEntity zombie(String zombieId, String animation) {
        return new ClientEntity(1, "zombie", zombieId, 2.5F, 1.5F, 200,
                EntityLayers.GROUND, animation, 0F, "");
    }
}
