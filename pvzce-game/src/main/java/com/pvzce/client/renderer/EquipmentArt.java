package com.pvzce.client.renderer;

import com.pvzce.api.content.EquipmentDef;
import com.pvzce.api.content.ZombieDef;
import com.pvzce.api.entity.EntityKind;
import com.pvzce.client.ClientEntity;
import com.pvzce.client.animation.BoneArt;
import com.pvzce.client.animation.BonePose;
import com.pvzce.client.animation.ControllerModel;
import com.pvzce.common.network.packet.EntitySpawnS2C;
import com.pvzce.common.core.BuiltInRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A zombie's worn equipment and lost limbs, drawn from the state the server streams.
 *
 * <p>Two things about a zombie change without its animation changing, and both are pieces
 * of the model rather than different animations:
 *
 * <ul>
 *   <li>a cone, bucket, door or newspaper has three drawings and wears through them; the
 *       model carries one hidden bone per drawing and this picks the one to show;</li>
 *   <li>an ordinary zombie loses its outer arm at half health, which is those five bones
 *       simply not being drawn.</li>
 * </ul>
 *
 * <p>Which is why this exists at all: the clip cannot say either thing - a walk cycle has
 * one cone in it - and swapping the whole animation file per damage state would mean
 * duplicating an 800KB controller for every zombie that wears something.
 *
 * <p>The family is found in the model by name, so the data does not have to repeat the art
 * layout: {@code "art": "cone"} means the bones {@code cone_1}, {@code cone_2} ... and a
 * pack that ships only two drawings gets two states. A family the clip hides on purpose
 * (the newspaper zombie's gasp, which drops the paper to shout) stays hidden - the override
 * only chooses <em>among</em> the drawings a frame was going to draw.
 *
 * <p>The armour denominator is the definition's whole armour, not the piece's: the wire
 * carries one number, which is the piece's for every zombie that exists (one piece each).
 * A two-piece zombie would wear both drawings at the average rate; none does today.
 */
public final class EquipmentArt implements BoneArt {
    /** The bones a zombie loses when half health takes its outer arm. */
    private static final List<String> OUTER_ARM_BONES = List.of(
            "outerarm_hand", "outerarm_hand_2", "outerarm_upper", "outerarm_upper_2",
            "outerarm_lower");

    /** A bone name that ends in a damage-state number: {@code cone_2}. */
    private static final Pattern STATE_SUFFIX = Pattern.compile("^(.*)_([0-9]+)$");

    /**
     * Per-definition plans, keyed by content id.
     *
     * <p>Content is frozen once loaded and this is derived from a definition plus a model,
     * both of which are shared by every zombie of that type - rebuilding the bone families
     * for each of thirty zombies every frame would be work with one answer. Cleared by
     * {@code AnimationManager.invalidate()} so a reloaded pack is not drawn from a plan
     * built for the old one.
     */
    private static final Map<String, Plan> PLANS = new ConcurrentHashMap<>();

    private final Plan plan;
    private final ZombieDef def;
    private final ClientEntity entity;

    private EquipmentArt(Plan plan, ZombieDef def, ClientEntity entity) {
        this.plan = plan;
        this.def = def;
        this.entity = entity;
    }

    /**
     * The override for one entity, or {@code null} when nothing about it needs one.
     *
     * <p>Null is the common case and matters: an entity with no override is drawn by its
     * clip alone, and the renderer skips building a bone set for it.
     */
    public static BoneArt forEntity(ClientEntity entity, ControllerModel model) {
        if (entity == null || model == null || !EntityKind.ZOMBIE.equals(entity.kind())) {
            return null;
        }
        ZombieDef def = BuiltInRegistries.ZOMBIES.get(entity.defId());
        if (def == null) {
            return null;
        }
        boolean hasEquipment = !def.equipment().isEmpty();
        if (!hasEquipment && !def.dropsArm() && def.hiddenBones().isEmpty()) {
            return null;
        }
        Plan plan = PLANS.computeIfAbsent(entity.defIdString(), id -> Plan.of(def, model));
        if (plan.entries().isEmpty() && !def.dropsArm() && plan.hiddenBones().isEmpty()) {
            return null;
        }
        return new EquipmentArt(plan, def, entity);
    }

    /** Drops every cached plan; called when the packs are reloaded. */
    public static void clearCache() {
        PLANS.clear();
    }

    @Override
    public Set<String> visibleBones(ControllerModel model, Map<String, BonePose> poses) {
        // `bones` is what the clip asked for. Everything below answers "what does the player
        // actually see", and the two were conflated before: the arm loss used to edit `bones`
        // directly, which meant an equipment entry for the torn arm could never fire - the
        // entry asks "is the clip drawing this piece", and the answer had already been erased.
        // The rules, in order:
        //
        //   1. an equipment entry decides its own family. If one is drawn, `tookOver` remembers
        //      that some sprite speaks for this limb;
        //   2. only if nothing did, and this zombie has lost its arm, does the arm come off.
        //      That is what makes a definition *with* torn-arm art look torn, and one without
        //      it look armless - rather than the reverse;
        //   3. `hidden_bones` goes last so nothing above can resurrect a sprite the definition
        //      called out.
        Set<String> visible = new HashSet<>();
        for (ControllerModel.Bone bone : model.renderOrder()) {
            BonePose pose = poses.getOrDefault(bone.name(), bone.restPose());
            if (pose.visible()) {
                visible.add(bone.name());
            }
        }
        for (Entry entry : plan.entries()) {
            // Asked *before* the family is cleared: a frame that draws no member of it is a
            // frame the art deliberately leaves the equipment out of (the death clip drops
            // the cone with the head, the newspaper's gasp has no paper in it), and putting
            // one back would undo that. `present` is the drawings plus whatever the entry is a
            // damage state of, so a torn limb follows the limb it belongs to.
            boolean drawnByClip = entry.bones().stream().anyMatch(visible::contains);
            // How this zombie holds what it is holding, if the piece brought its own limb.
            // The flag zombie's hand is a bone of the flag's own reanim rather than one of
            // the cone family's damage states, so it is invisible to the loop above and is
            // read separately - see armBones() and the data's own note.
            String armHost = entry.armHost();
            boolean armDrawn = armHost != null && visible.contains(armHost);
            visible.removeAll(entry.bones());
            if (drawnByClip) {
                String chosen = entry.chooseFor(entity, def);
                if (chosen != null) {
                    visible.add(chosen);
                }
            }
            if (!entry.equipment().armBones().isEmpty()) {
                // The piece and the limb that carries it are one thing, so exactly one of the
                // two is ever drawn. The death clip drops the flag - and with it the hand
                // that held the pole, which is a bone of the flag's own reanim - so hiding
                // the ordinary arm unconditionally left an armless zombie falling over.
                // Handing the arm back on those frames is the whole point of scoping this to
                // "is the piece's own hand on screen" rather than to the zombie.
                if (armDrawn) {
                    visible.removeAll(entry.equipment().armBones());
                } else {
                    visible.addAll(entry.equipment().armBones());
                }
            }
        }
        if (plan.armLoss() && lostArm()) {
            // Half health costs an ordinary zombie its outer arm, and the art has nothing to
            // put in its place: the rip's torn drawings (`outerarm_hand_2`,
            // `outerarm_upper_2`) belong to the super-long death sequence, not to a walking
            // zombie. The feedback for the amputation is the arm that flies off - the server
            // emits `pvzce:zombie_arm` on the same condition - which is what the original does.
            visible.removeAll(OUTER_ARM_BONES);
        }
        // Last, so nothing above can put one back: this is the definition saying "this sprite
        // is the same limb as another one, and only one of them is this zombie's".
        visible.removeAll(plan.hiddenBones());
        return visible;
    }

    /**
     * True once this zombie has lost the arm: half its own health, and nothing left on its
     * head.
     *
     * <p>The armour half of that is what keeps a Conehead's arm attached while it is still
     * wearing the cone - the server emits the pop on the same condition (see
     * {@code ZombieEntity.damageBody}), and both read it from state the client already has.
     */
    private boolean lostArm() {
        return entity.health() * 2 <= Math.max(1, def.health()) && entity.armor() <= 0;
    }

    /** One equipment entry and the bones that draw it, by damage state. */
    private record Entry(EquipmentDef equipment, List<String> bones) {
        /**
         * The bone that means "this piece's own arm is on screen", or {@code null}.
         *
         * <p>Invisible to {@link #bones()} on purpose: the host is the bone the piece hangs
         * off rather than a drawing of the piece, so it is not part of the family the clip
         * turns on and off. It still has to be asked about, because it is the only thing that
         * says whether the limb came with the piece.
         */
        String armHost() {
            return equipment.armBones().isEmpty() || equipment.host().isEmpty()
                    ? null
                    : equipment.host().get();
        }

        /**
         * The bone to draw for the zombie's current state, or {@code null} for "it is gone".
         *
         * <p>{@code bones} is ordered by damage state, so "the worst drawing there is" is
         * the last element - which is what a health-driven piece uses, since it has one
         * threshold and not three.
         */
        String chooseFor(ClientEntity entity, ZombieDef def) {
            if (bones.isEmpty()) {
                return null;
            }
            if (equipment.armorDriven()) {
                if (entity.armor() == EntitySpawnS2C.NO_ARMOR) {
                    // Nothing has worn it: this is a preview entity the client built for
                    // the seed chooser or the editor, which no server has told about
                    // armour. A real zombie of this type always arrives with a value, so
                    // the intact drawing is the only sensible answer here.
                    return bones.get(0);
                }
                if (entity.armor() <= 0) {
                    return null;
                }
                float ratio = entity.armor() / (float) Math.max(1, armourTotal(def));
                return bones.get(stateIndexFor(ratio));
            }
            float ratio = entity.health() / (float) Math.max(1, def.health());
            if (ratio <= 0F) {
                return null;
            }
            return ratio <= equipment.healthBelow() ? bones.get(bones.size() - 1) : bones.get(0);
        }

        /**
         * Which drawing a wear ratio shows: the original's thirds, so a fresh cone turns
         * cracked at two thirds and crushed at one.
         */
        private int stateIndexFor(float ratio) {
            int state = ratio > 2F / 3F ? 1 : ratio > 1F / 3F ? 2 : 3;
            // A family with fewer drawings than states wears through the ones it has, and
            // one with more (a pack's own art) uses its earliest states.
            int index = Math.min(state, bones.size()) - 1;
            return Math.max(0, index);
        }

        private static int armourTotal(ZombieDef def) {
            return def.capability(com.pvzce.common.capability.zombie.ArmorCapability.class)
                    .map(armour -> {
                        int total = 0;
                        for (com.pvzce.api.content.ArmorDef piece : armour.armor()) {
                            total += Math.max(0, piece.durability());
                        }
                        return total;
                    })
                    .orElse(1);
        }
    }

    /** One zombie definition's equipment, resolved against its model once. */
    private record Plan(List<Entry> entries, boolean armLoss, Set<String> hiddenBones) {
        static Plan of(ZombieDef def, ControllerModel model) {
            List<Entry> resolved = new ArrayList<>();
            for (EquipmentDef equipment : def.equipment()) {
                if (!equipment.isUsable()) {
                    continue;
                }
                // What this entry may draw: the bones named after `art`, and nothing else. A
                // piece the rip names by part rather than by family - a limb, whose intact and
                // torn drawings are `outerarm_upper` and `outerarm_upper2` - cannot be described
                // this way at all, and none is: see `visibleBones` for what the arm does instead
                // when a zombie loses it.
                List<String> family = familyIn(model, equipment.art());
                if (!family.isEmpty()) {
                    resolved.add(new Entry(equipment, family));
                }
            }
            return new Plan(List.copyOf(resolved), def.dropsArm(),
                    Set.copyOf(def.hiddenBones()));
        }

        /** The model's bones for one art family, ordered by their damage-state number. */
        private static List<String> familyIn(ControllerModel model, String art) {
            String prefix = art + "_";
            List<int[]> found = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (String bone : model.bones().keySet()) {
                if (!bone.startsWith(prefix)) {
                    continue;
                }
                Matcher matcher = STATE_SUFFIX.matcher(bone);
                if (!matcher.matches() || !matcher.group(1).equals(art)) {
                    continue;
                }
                found.add(new int[]{Integer.parseInt(matcher.group(2)), names.size()});
                names.add(bone);
            }
            found.sort(Comparator.comparingInt(entry -> entry[0]));
            List<String> ordered = new ArrayList<>(found.size());
            for (int[] entry : found) {
                ordered.add(names.get(entry[1]));
            }
            return List.copyOf(ordered);
        }
    }
}
