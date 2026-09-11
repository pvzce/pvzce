package com.pvzce.client.animation;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.api.Animatable;

import java.util.LinkedHashMap;
import java.util.Map;

/** Runtime playback for a 2D controller clip. */
public final class ControllerPlayback extends AnimationPlayback {
    private final ControllerFile controllerFile;
    private final ControllerClip controllerClip;
    private final ControllerModel model;
    private final Map<String, BonePose> previousPose;

    public ControllerPlayback(AnimationManager manager, Animatable target, ControllerFile file,
                              ControllerClip clip, String requestedState, String activeName,
                              AnimationPlayback previous, double now) {
        super(manager, target, file, clip, requestedState, activeName, previous, now);
        this.controllerFile = file;
        this.controllerClip = clip;
        this.model = file.model();
        this.previousPose = previous instanceof ControllerPlayback old ? old.currentPose(now) : null;
    }

    public Map<String, BonePose> currentPose(double now) {
        return controllerClip.samplePose(model, localTime(now));
    }

    private Map<String, BonePose> blendedPose(double now) {
        Map<String, BonePose> current = currentPose(now);
        if (previousPose == null) {
            return current;
        }
        float blend = transitionBlend(now);
        if (blend >= 1F) {
            return current;
        }
        Map<String, BonePose> result = new LinkedHashMap<>();
        for (ControllerModel.Bone bone : model.bones().values()) {
            BonePose from = previousPose.getOrDefault(bone.name(), bone.restPose());
            BonePose to = current.getOrDefault(bone.name(), bone.restPose());
            result.put(bone.name(), BonePose.lerp(from, to, blend));
        }
        return result;
    }

    private Map<String, Affine2> worldTransforms(Map<String, BonePose> poses) {
        Map<String, Affine2> world = new LinkedHashMap<>();
        for (ControllerModel.Bone bone : model.renderOrder()) {
            Affine2 parent = bone.parent() == null ? Affine2.IDENTITY : world.getOrDefault(bone.parent(), Affine2.IDENTITY);
            BonePose pose = poses.getOrDefault(bone.name(), bone.restPose());
            world.put(bone.name(), parent.multiply(pose.toAffine(bone.pivot())));
        }
        return world;
    }

    @Override
    public void render(PvzceClient client, float anchorX, float anchorY, float baseZ,
                       float xScale) {
        if (stopped) {
            return;
        }
        float scaleX = Math.max(0.0001F, xScale);
        Map<String, BonePose> poses = blendedPose(manager.now());
        Map<String, Affine2> world = worldTransforms(poses);
        for (ControllerModel.Bone bone : model.renderOrder()) {
            BonePose pose = poses.getOrDefault(bone.name(), bone.restPose());
            if (!pose.visible()) {
                continue;
            }
            Affine2 transform = world.getOrDefault(bone.name(), Affine2.IDENTITY);
            for (ControllerModel.Part part : bone.parts()) {
                float halfW = part.sizeX() * 0.5F;
                float halfH = part.sizeY() * 0.5F;
                float cx = part.offsetX();
                float cy = part.offsetY();
                float x0 = cx - halfW;
                float y0 = cy - halfH;
                float x1 = cx + halfW;
                float y1 = cy + halfH;

                float blx = anchorX + transform.transformX(x0, y0);
                float bly = anchorY + transform.transformY(x0, y0);
                float brx = anchorX + transform.transformX(x1, y0);
                float bry = anchorY + transform.transformY(x1, y0);
                float trx = anchorX + transform.transformX(x1, y1);
                float tryy = anchorY + transform.transformY(x1, y1);
                float tlx = anchorX + transform.transformX(x0, y1);
                float tly = anchorY + transform.transformY(x0, y1);

                // Post-scale horizontal geometry around the anchor. The world
                // projection uses 80px-per-cell horizontally and 100px
                // vertically, so this keeps reanim/sprite pixels square.
                blx = anchorX + (blx - anchorX) * scaleX;
                brx = anchorX + (brx - anchorX) * scaleX;
                trx = anchorX + (trx - anchorX) * scaleX;
                tlx = anchorX + (tlx - anchorX) * scaleX;

                float z = baseZ + part.z() * 0.001F;
                client.drawTextureQuad(part.texture(),
                        blx, bly, brx, bry, trx, tryy, tlx, tly,
                        part.u0(), part.v1(), part.u1(), part.v1(),
                        part.u1(), part.v0(), part.u0(), part.v0(),
                        z, 1F, 1F, 1F, 1F);
            }
        }
    }

    @Override
    public float[] eventPosition(String locator) {
        Map<String, Affine2> world = worldTransforms(currentPose(manager.now()));
        Affine2 transform = locator == null || locator.isBlank()
                ? world.getOrDefault("root", Affine2.IDENTITY)
                : world.getOrDefault(locator, world.getOrDefault("root", Affine2.IDENTITY));
        float[] anchor = manager.anchor(target);
        float offsetX = transform.transformX(0F, 0F) * manager.clientSpriteXScale();
        return new float[]{
                anchor[0] + offsetX,
                anchor[1] + transform.transformY(0F, 0F)
        };
    }
}
