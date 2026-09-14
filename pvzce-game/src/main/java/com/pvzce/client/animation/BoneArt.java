package com.pvzce.client.animation;

import java.util.Map;
import java.util.Set;

/**
 * A last word on which bones one entity's controller model draws this frame.
 *
 * <p>Clips decide visibility from the source reanim, which is everything a walk cycle
 * needs and not enough for state the model cannot express: a Conehead wears one of three
 * cones depending on how much of its armour is left, and an ordinary zombie loses its
 * outer arm at half health. Both are *pieces of a model* rather than different animations,
 * so they cannot be a clip - and they must not be, since they have to survive a
 * walk/eat/hit switch.
 *
 * <p>The renderer asks once per frame with the whole model, not once per bone: "which of
 * the family does the clip draw at all" is only answerable with all of them in hand (a
 * newspaper zombie's gasp hides the paper on purpose, and that must keep working).
 *
 * @see com.pvzce.client.renderer.EquipmentArt
 */
public interface BoneArt {
    /**
     * The bone names to draw, given the poses the clip resolved.
     *
     * @param model the controller model being drawn, in render order
     * @param poses the blended poses; a bone the clip does not animate falls back to its
     *              rest pose, and the implementation is expected to do the same
     */
    Set<String> visibleBones(ControllerModel model, Map<String, BonePose> poses);
}
