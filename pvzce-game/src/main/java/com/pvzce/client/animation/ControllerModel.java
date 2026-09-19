package com.pvzce.client.animation;

import com.pvzce.api.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 2D controller model: a bone tree whose leaves are textured quads.
 *
 * <p>Units are world cells. The root bone origin is the entity's foot
 * centre; the local y axis points up. Each part is a quad centred on
 * {@code offset} relative to its bone origin and is textured by the
 * top-left pixel rectangle {@code uv}.</p>
 */
public final class ControllerModel {
    private final Map<String, Bone> bones;
    private final List<Bone> renderOrder;
    private final float sizeX;
    private final float sizeY;

    public ControllerModel(List<Bone> bones) {
        this(bones, 0F, 0F);
    }

    public ControllerModel(List<Bone> bones, float sizeX, float sizeY) {
        this.sizeX = Math.max(0F, sizeX);
        this.sizeY = Math.max(0F, sizeY);
        Map<String, Bone> byName = new LinkedHashMap<>();
        for (Bone bone : bones) {
            if (byName.putIfAbsent(bone.name(), bone) != null) {
                throw new IllegalArgumentException("Duplicate controller bone: " + bone.name());
            }
        }
        for (Bone bone : bones) {
            if (bone.parent() != null && !byName.containsKey(bone.parent())) {
                throw new IllegalArgumentException("Unknown controller bone parent: " + bone.parent());
            }
        }
        this.bones = Map.copyOf(byName);
        List<Bone> ordered = new ArrayList<>(bones);
        ordered.sort((a, b) -> Integer.compare(depth(a, byName), depth(b, byName)));
        this.renderOrder = List.copyOf(ordered);
    }

    private static int depth(Bone bone, Map<String, Bone> byName) {
        int depth = 0;
        Bone current = bone;
        while (current != null && depth < 256) {
            depth++;
            current = current.parent() == null ? null : byName.get(current.parent());
        }
        return depth;
    }

    public Map<String, Bone> bones() {
        return bones;
    }

    /** Reference width/height in world cells; 0 means "unknown". */
    public float sizeX() {
        return sizeX;
    }

    public float sizeY() {
        return sizeY;
    }

    public List<Bone> renderOrder() {
        return renderOrder;
    }

    public Bone bone(String name) {
        return bones.get(name);
    }

    public record Bone(String name, String parent, float[] pivot, BonePose restPose, List<Part> parts) {
        public Bone {
            name = name == null ? "" : name;
            parent = parent == null || parent.isBlank() ? null : parent;
            pivot = pivot == null ? new float[]{0F, 0F} : pivot.clone();
            restPose = restPose == null ? BonePose.IDENTITY : restPose;
            parts = parts == null ? List.of() : List.copyOf(parts);
        }
    }

    public record Part(
            Identifier texture,
            float u0,
            float v0,
            float u1,
            float v1,
            float sizeX,
            float sizeY,
            float offsetX,
            float offsetY,
            float z,
            BlendMode blend
    ) {
        public Part {
            blend = blend == null ? BlendMode.NORMAL : blend;
        }

        /** A part composited source-over, which is every part that declares no blend. */
        public Part(Identifier texture, float u0, float v0, float u1, float v1,
                    float sizeX, float sizeY, float offsetX, float offsetY, float z) {
            this(texture, u0, v0, u1, v1, sizeX, sizeY, offsetX, offsetY, z, BlendMode.NORMAL);
        }
    }
}
