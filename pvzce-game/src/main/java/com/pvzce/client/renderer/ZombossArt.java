package com.pvzce.client.renderer;

import com.pvzce.client.ClientEntity;
import com.pvzce.client.ClientLevel;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.level.mechanic.ZombossMechanic.Status;
import com.pvzce.client.animation.BoneArt;
import com.pvzce.client.animation.BonePose;
import com.pvzce.client.animation.ControllerModel;
import com.pvzce.common.capability.zombie.ZombossCapability;
import com.pvzce.common.core.BuiltInRegistries;

import java.util.HashSet;
import java.util.List;

import static com.pvzce.common.PvzceConstants.*;

/** The original machine's 80% / 50% damage drawings, following the synced health. */
public final class ZombossArt {
    private ZombossArt() {}
    public static boolean applies(ClientEntity entity) {
        var def = BuiltInRegistries.ZOMBIES.get(entity.defId());
        return def != null && def.capability(ZombossCapability.class).isPresent();
    }
    public static BoneArt forEntity(ClientEntity entity, ClientLevel level) {
        if (!applies(entity)) return null;
        return (model, poses) -> {
            var visible = new HashSet<String>();
            for (ControllerModel.Bone bone : model.renderOrder()) {
                if (poses.getOrDefault(bone.name(), bone.restPose()).visible()) visible.add(bone.name());
            }
            float ratio = entity.health() / (float)entity.maxHealth();
            int damage = ratio <= ZOMBOSS_SECOND_DAMAGE ? 2 : ratio <= ZOMBOSS_FIRST_DAMAGE ? 1 : 0;
            for (String host : List.of("head", "jaw", "foot", "outerarm_hand")) {
                if (visible.remove(host)) visible.add(damage == 0 ? host : host + "_damage" + damage);
            }
            Status status = level == null ? null : level.mechanicStateOrNull(PvzceIds.MECHANIC_ZOMBOSS, Status.class);
            if (status != null && status.spitType() == 2) {
                for (String glow : List.of("eyeglow", "mouthglow")) {
                    if (visible.remove(glow + "_red")) visible.add(glow + "_blue");
                }
            }
            return visible;
        };
    }
}
