package com.pvzce.client.renderer;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.entity.EntityKind;
import com.pvzce.client.ClientEntity;
import com.pvzce.client.animation.BoneArt;
import com.pvzce.client.animation.BonePose;
import com.pvzce.client.animation.ControllerModel;
import com.pvzce.common.core.BuiltInRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A plant's cracked drawings, chosen from the health the server streams.
 *
 * <p>The nut plants are the case. The original swaps a wall-nut's art for a cracked one as it is
 * chewed and for a worse one near the end, and those two drawings are referenced by no track in
 * {@code Wallnut.reanim} - the game swapped them in code. The conversion therefore ships them as
 * extra bones the clips never draw (see {@code tools/reanim_to_pvzce_all.py}'s {@code damage_states})
 * and this picks the one the health calls for.
 *
 * <h2>Why a bone override and not another clip</h2>
 *
 * <p>Same reason {@link EquipmentArt} is one: the state has to survive a clip switch. A wall-nut
 * draws {@code idle} from the moment it is planted to the moment it dies, so "which nut is this"
 * cannot be a state name - and if it were, every clip in the model would have to be duplicated per
 * damage state.
 *
 * <h2>The family is found by name, and the name is agreed with the converter</h2>
 *
 * <p>{@code cracked_1}, {@code cracked_2}, ... in the model's own order, with the intact drawing
 * being whatever the clip draws. The prefix is written down once, in the converter's
 * {@code damage_states} entries, and a plant whose model declares no such bone gets no override at
 * all - which is every plant but the two nuts.
 */
public final class PlantDamageArt implements BoneArt {
    /**
     * The prefix a cracked drawing's bone is named with.
     *
     * <p>Public because the converter's {@code damage_states} tables name the bones and the two have
     * to agree; a pack that names its states this way gets the behaviour for free.
     */
    public static final String STATE_PREFIX = "cracked_";

    /** Per-definition families, keyed by content id; the model is shared by every plant of a type. */
    private static final Map<String, List<String>> FAMILIES = new ConcurrentHashMap<>();

    private final List<String> family;
    private final int fullHealth;
    private final int health;

    private PlantDamageArt(List<String> family, int fullHealth, int health) {
        this.family = family;
        this.fullHealth = fullHealth;
        this.health = health;
    }

    /**
     * The override for one plant, or {@code null} when its art has no damage states.
     *
     * <p>Null is the common case and matters: an entity with no override is drawn by its clip alone,
     * and the renderer skips building a bone set for it.
     */
    public static BoneArt forEntity(ClientEntity entity, ControllerModel model) {
        if (entity == null || model == null || !EntityKind.PLANT.equals(entity.kind())) {
            return null;
        }
        PlantDef def = BuiltInRegistries.PLANTS.get(entity.defId());
        if (def == null) {
            return null;
        }
        List<String> found = FAMILIES.computeIfAbsent(entity.defIdString(), id -> familyIn(model));
        if (found.isEmpty()) {
            return null;
        }
        return new PlantDamageArt(found, Math.max(1, def.health()), entity.health());
    }

    /** Drops every cached family; called when the packs are reloaded. */
    public static void clearCache() {
        FAMILIES.clear();
    }

    /** The model's cracked bones, worst last. Empty when the model declares none. */
    private static List<String> familyIn(ControllerModel model) {
        List<String> names = new ArrayList<>();
        for (String bone : model.bones().keySet()) {
            if (bone.startsWith(STATE_PREFIX) && bone.length() > STATE_PREFIX.length()) {
                names.add(bone);
            }
        }
        // By the suffix the converter's tables are written in, so a third state needs no code; a
        // name that is not numbered sorts last rather than throwing, which keeps a hand-written
        // model from breaking the frame.
        names.sort(Comparator.comparingInt(PlantDamageArt::stateNumber));
        return List.copyOf(names);
    }

    private static int stateNumber(String bone) {
        try {
            return Integer.parseInt(bone.substring(STATE_PREFIX.length()));
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Which drawing the plant's health calls for, or {@code null} for "the clip's own".
     *
     * <p>The original's thirds, the same thresholds {@link EquipmentArt} wears a cone through: full
     * art above two thirds, the first crack down to one third, the worst below it. A plant with one
     * cracked drawing wears it for everything under two thirds, because a family with fewer drawings
     * than states uses the ones it has.
     */
    private String chosen() {
        if (health <= 0) {
            // Dying: the death art is the clip's business, and putting a cracked nut back on screen
            // for the frame between "health 0" and "removed" would flicker.
            return null;
        }
        float ratio = health / (float) fullHealth;
        if (ratio > 2F / 3F) {
            return null;
        }
        int index = ratio > 1F / 3F ? 0 : 1;
        return family.get(Math.min(index, family.size() - 1));
    }

    @Override
    public Set<String> visibleBones(ControllerModel model, Map<String, BonePose> poses) {
        Set<String> visible = new HashSet<>();
        for (ControllerModel.Bone bone : model.renderOrder()) {
            BonePose pose = poses.getOrDefault(bone.name(), bone.restPose());
            if (pose.visible()) {
                visible.add(bone.name());
            }
        }
        // The cracked bones are hidden in every clip (`visible: false`), so the set above never
        // contains one; the chosen state is added by hand. "Which one" is the whole question - a
        // plant does not wear its cracks out at different rates, so there is no family to remove.
        String chosen = chosen();
        if (chosen != null) {
            visible.add(chosen);
        }
        return visible;
    }
}
