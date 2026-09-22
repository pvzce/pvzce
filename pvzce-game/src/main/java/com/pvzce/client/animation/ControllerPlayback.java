package com.pvzce.client.animation;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.api.Animatable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runtime playback for a 2D controller clip. */
public final class ControllerPlayback extends AnimationPlayback {
    private final ControllerFile controllerFile;
    private final ControllerClip controllerClip;
    private final ControllerModel model;
    private final Map<String, BonePose> previousPose;
    /**
     * The model's bones in draw order, and the parts of each in draw order.
     *
     * <p>Built once per model rather than sorted per frame: a controller's part list is
     * fixed data, and a {@code part.z()} that only ever reorders the same list is a
     * constant. The old code emitted parts in the order the JSON happened to list them and
     * computed a z that nothing read, so a hand-written model whose list order disagreed
     * with its z values drew them in the wrong order with no way to fix it from the data.
     */
    private final List<DrawBone> drawOrder;

    public ControllerPlayback(AnimationManager manager, Animatable target, ControllerFile file,
                              ControllerClip clip, String requestedState, String activeName,
                              AnimationPlayback previous, double now) {
        super(manager, target, file, clip, requestedState, activeName, previous, now);
        this.controllerFile = file;
        this.controllerClip = clip;
        this.model = file.model();
        this.previousPose = previous instanceof ControllerPlayback old ? old.currentPose(now) : null;
        this.drawOrder = planDrawOrder(model);
    }

    /**
     * One bone with its parts already sorted for painting.
     *
     * <p>Public so the draw plan can be asserted without a GL context: "which parts are drawn,
     * in what order, in which blend pass" is exactly the part of rendering that has no other
     * way to be tested.
     */
    public record DrawBone(ControllerModel.Bone bone, List<ControllerModel.Part> parts) {
    }

    /**
     * Orders the whole model: bones by their shallowest part's z, parts within a bone by
     * their own z. A stable sort throughout, so equal z keeps the file's order and a model
     * that declares nothing keeps drawing exactly as it did.
     */
    public static List<DrawBone> planDrawOrder(ControllerModel model) {
        List<DrawBone> ordered = new ArrayList<>(model.renderOrder().size());
        for (ControllerModel.Bone bone : model.renderOrder()) {
            List<ControllerModel.Part> parts = new ArrayList<>(bone.parts());
            parts.sort(Comparator.comparingDouble(ControllerModel.Part::z));
            ordered.add(new DrawBone(bone, List.copyOf(parts)));
        }
        ordered.sort(Comparator.comparingDouble(entry -> entry.parts().isEmpty()
                ? Float.MAX_VALUE
                : entry.parts().get(0).z()));
        return List.copyOf(ordered);
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
                       float xScale, float yScale) {
        if (stopped) {
            return;
        }
        float scaleX = Math.max(0.0001F, xScale);
        float scaleY = Math.max(0.0001F, yScale);
        Map<String, BonePose> poses = blendedPose(manager.now());
        Map<String, Affine2> world = worldTransforms(poses);
        // A per-entity art override can hide bones and choose between a family's drawings
        // (a Conehead's three cones, a zombie whose arm was shot off). Null for everything
        // that has no such state, which is every entity but a few zombies - then the clip's
        // own visibility is the answer, exactly as before.
        BoneArt art = manager.activeBoneArt();
        java.util.Set<String> visibleBones = art == null ? null : art.visibleBones(model, poses);
        // Two passes over the same plan so the blend state changes at most twice per
        // entity instead of once per glowing part: paint first, then light. Within a pass
        // the order is the model's, so an additive part still lands where its z says.
        drawPass(client, poses, world, visibleBones, anchorX, anchorY, baseZ, scaleX, scaleY, BlendMode.NORMAL);
        drawPass(client, poses, world, visibleBones, anchorX, anchorY, baseZ, scaleX, scaleY, BlendMode.ADD);
    }

    private void drawPass(PvzceClient client, Map<String, BonePose> poses, Map<String, Affine2> world,
                          java.util.Set<String> visibleBones, float anchorX, float anchorY, float baseZ,
                          float scaleX, float scaleY, BlendMode pass) {
        boolean active = false;
        for (DrawBone entry : drawOrder) {
            ControllerModel.Bone bone = entry.bone();
            BonePose pose = poses.getOrDefault(bone.name(), bone.restPose());
            if (visibleBones == null ? !pose.visible() : !visibleBones.contains(bone.name())) {
                continue;
            }
            Affine2 transform = world.getOrDefault(bone.name(), Affine2.IDENTITY);
            float alpha = pose.alpha();
            if (alpha <= 0.001F) {
                continue;
            }
            for (ControllerModel.Part part : entry.parts()) {
                if (part.blend() != pass) {
                    continue;
                }
                if (!active) {
                    if (pass == BlendMode.ADD) {
                        com.pvzce.client.renderer.RenderSystem.blendAdditive();
                    }
                    active = true;
                }
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

                // Post-scale around the anchor. The world projection uses 80px-per-cell
                // horizontally and 100px vertically, so the horizontal factor keeps
                // reanim/sprite pixels square and the vertical one is the content's own
                // size knob; see AnimationManager#scalesFor for why there are two.
                blx = anchorX + (blx - anchorX) * scaleX;
                brx = anchorX + (brx - anchorX) * scaleX;
                trx = anchorX + (trx - anchorX) * scaleX;
                tlx = anchorX + (tlx - anchorX) * scaleX;
                bly = anchorY + (bly - anchorY) * scaleY;
                bry = anchorY + (bry - anchorY) * scaleY;
                tryy = anchorY + (tryy - anchorY) * scaleY;
                tly = anchorY + (tly - anchorY) * scaleY;

                // A mirrored entity (a charmed zombie) is drawn as the same quads reflected
                // about the anchor: the corner positions flip, each corner keeps its own texture
                // coordinate, and the winding reverses - which nothing minds, because face
                // culling is off (see RenderSystem's init).
                if (flipX) {
                    blx = anchorX - (blx - anchorX);
                    brx = anchorX - (brx - anchorX);
                    trx = anchorX - (trx - anchorX);
                    tlx = anchorX - (tlx - anchorX);
                }

                float z = baseZ + part.z() * 0.001F;
                client.drawTextureQuad(part.texture(),
                        blx, bly, brx, bry, trx, tryy, tlx, tly,
                        part.u0(), part.v1(), part.u1(), part.v1(),
                        part.u1(), part.v0(), part.u0(), part.v0(),
                        z, 1F, 1F, 1F, alpha);
            }
        }
        if (active && pass == BlendMode.ADD) {
            com.pvzce.client.renderer.RenderSystem.blendNormal();
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
