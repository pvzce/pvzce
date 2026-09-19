package com.pvzce.client.animation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A named controller clip: per-bone transform tracks plus event cues. */
public record ControllerClip(
        float duration,
        boolean loop,
        OnEnd onEnd,
        String next,
        float transition,
        float rate,
        float referenceSpeed,
        Map<String, BoneTracks> bones,
        List<AnimationCue.Sound> soundCues,
        List<AnimationCue.Particle> particleCues
) implements AnimationClip {
    public ControllerClip {
        duration = Math.max(0F, duration);
        next = next == null ? "" : next;
        rate = AnimationPlayback.sanitizeRate(rate);
        referenceSpeed = Math.max(0F, referenceSpeed);
        bones = Map.copyOf(bones);
        soundCues = List.copyOf(soundCues);
        particleCues = List.copyOf(particleCues);
    }

    public BoneTracks tracks(String boneName) {
        return bones.get(boneName);
    }

    /** Samples every model bone; bones absent from the clip use their rest pose. */
    public Map<String, BonePose> samplePose(ControllerModel model, double time) {
        double t = clipTime(time);
        Map<String, BonePose> poses = new LinkedHashMap<>();
        for (ControllerModel.Bone bone : model.bones().values()) {
            BoneTracks tracks = bones.get(bone.name());
            BonePose rest = bone.restPose();
            if (tracks == null) {
                poses.put(bone.name(), rest);
                continue;
            }
            float[] translation = tracks.translation().sample(t, rest.translation());
            float[] rotation = tracks.rotation().sample(t, rest.rotation());
            float[] scale = tracks.scale().sample(t, rest.scale());
            boolean visible = tracks.visible().sample(t, rest.visible());
            float alpha = tracks.alpha().sample(t, rest.alpha());
            poses.put(bone.name(), new BonePose(translation, rotation, scale, visible, alpha));
        }
        return poses;
    }

    private double clipTime(double time) {
        return Timeline.wrapForSampling(time, duration, loop);
    }

    /** Per-bone track set. Empty tracks mean "use the bone's rest pose". */
    public record BoneTracks(
            VectorTrack translation,
            VectorTrack rotation,
            VectorTrack scale,
            BooleanTrack visible,
            FloatTrack alpha
    ) {
        public BoneTracks {
            translation = translation == null ? new VectorTrack(List.of()) : translation;
            rotation = rotation == null ? new VectorTrack(List.of()) : rotation;
            scale = scale == null ? new VectorTrack(List.of()) : scale;
            visible = visible == null ? new BooleanTrack(List.of()) : visible;
            alpha = alpha == null ? new FloatTrack(List.of()) : alpha;
        }
    }
}
