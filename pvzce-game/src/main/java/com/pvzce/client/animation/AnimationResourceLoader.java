package com.pvzce.client.animation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvzce.api.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parses the self-developed animation JSON schema into immutable runtime
 * objects. No GL or resource-manager access happens here, which keeps the
 * format testable with plain strings.
 */
public final class AnimationResourceLoader {
    private AnimationResourceLoader() {
    }

    public static AnimationFile parse(JsonObject root, Identifier source) {
        String declaredType = string(root, "type", "");
        Optional<AnimationFile.AnimationType> type = AnimationFile.AnimationType.parse(declaredType);
        if (type.isEmpty()) {
            type = inferType(root);
        }
        if (type.isEmpty()) {
            throw new IllegalArgumentException(source + ": cannot determine animation type; add \"type\":\"flipbook\" or \"controller\"");
        }
        JsonObject animations = object(root, "animations");
        if (animations == null || animations.size() == 0) {
            throw new IllegalArgumentException(source + ": missing non-empty \"animations\" object");
        }
        return switch (type.get()) {
            case FLIPBOOK -> parseFlipbook(root, animations, source);
            case CONTROLLER -> parseController(root, animations, source);
        };
    }

    private static Optional<AnimationFile.AnimationType> inferType(JsonObject root) {
        JsonObject animations = object(root, "animations");
        if (root.has("model") || (animations != null && firstHas(animations, "bones"))) {
            return Optional.of(AnimationFile.AnimationType.CONTROLLER);
        }
        if (animations != null && firstHas(animations, "frames")) {
            return Optional.of(AnimationFile.AnimationType.FLIPBOOK);
        }
        return Optional.empty();
    }

    private static boolean firstHas(JsonObject object, String key) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (entry.getValue().isJsonObject() && entry.getValue().getAsJsonObject().has(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walks a {@code animations} object, guaranteeing every entry is an object.
     * Both backends used to run their own copy of this loop and its error message.
     */
    private static void forEachClip(JsonObject animations, Identifier source, ClipVisitor visitor) {
        for (Map.Entry<String, JsonElement> entry : animations.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                throw new IllegalArgumentException(source + ": animation '" + entry.getKey()
                        + "' must be an object");
            }
            visitor.accept(entry.getKey(), entry.getValue().getAsJsonObject());
        }
    }

    @FunctionalInterface
    private interface ClipVisitor {
        void accept(String name, JsonObject json);
    }

    /** The clip fields both backends share: loop, on_end, next and transition. */
    private record ClipHeader(boolean loop, AnimationClip.OnEnd onEnd, String next, float transition) {
    }

    private static ClipHeader clipHeader(JsonObject json) {
        return new ClipHeader(
                bool(json, "loop", false),
                AnimationClip.OnEnd.parse(string(json, "on_end", "hold")),
                string(json, "next", ""),
                Math.max(0F, number(json, "transition", 0F)));
    }

    // ------------------------------------------------------------------
    // Flipbook
    // ------------------------------------------------------------------

    private static FlipbookFile parseFlipbook(JsonObject root, JsonObject animations, Identifier source) {
        Map<String, FlipbookClip> clips = new LinkedHashMap<>();
        forEachClip(animations, source,
                (name, json) -> clips.put(name, parseFlipbookClip(name, json, source)));
        float sizeX = number(root, "size", 0, 1F);
        float sizeY = number(root, "size", 1, 1F);
        float anchorX = number(root, "anchor", 0, 0.5F);
        float anchorY = number(root, "anchor", 1, 0F);
        return new FlipbookFile(clips, sizeX, sizeY, anchorX, anchorY);
    }

    private static FlipbookClip parseFlipbookClip(String name, JsonObject json, Identifier source) {
        JsonArray framesArray = array(json, "frames");
        if (framesArray == null || framesArray.isEmpty()) {
            throw new IllegalArgumentException(source + ": flipbook animation '" + name + "' has no frames");
        }
        List<Identifier> frames = new ArrayList<>(framesArray.size());
        for (JsonElement element : framesArray) {
            frames.add(textureId(element.getAsString(), source));
        }

        float[] delays;
        if (json.has("delays")) {
            JsonArray delaysArray = array(json, "delays");
            delays = new float[delaysArray.size()];
            for (int i = 0; i < delays.length; i++) {
                delays[i] = Math.max(0.0001F, delaysArray.get(i).getAsFloat());
            }
        } else if (json.has("delay")) {
            delays = new float[]{Math.max(0.0001F, json.get("delay").getAsFloat())};
        } else {
            delays = new float[]{0.1F};
        }

        ClipHeader header = clipHeader(json);
        return new FlipbookClip(frames, delays, header.loop(), header.onEnd(), header.next(), header.transition(),
                soundCues(json, source), particleCues(json, source));
    }

    // ------------------------------------------------------------------
    // Controller
    // ------------------------------------------------------------------

    private static ControllerFile parseController(JsonObject root, JsonObject animations, Identifier source) {
        JsonObject modelJson = object(root, "model");
        if (modelJson == null) {
            throw new IllegalArgumentException(source + ": controller animation has no \"model\" object");
        }
        ControllerModel model = parseModel(modelJson, source);
        Map<String, ControllerClip> clips = new LinkedHashMap<>();
        forEachClip(animations, source,
                (name, json) -> clips.put(name, parseControllerClip(name, json, source)));
        return new ControllerFile(model, clips);
    }

    private static ControllerModel parseModel(JsonObject modelJson, Identifier source) {
        JsonArray bonesArray = array(modelJson, "bones");
        if (bonesArray == null || bonesArray.isEmpty()) {
            throw new IllegalArgumentException(source + ": controller model has no bones");
        }
        List<ControllerModel.Bone> bones = new ArrayList<>();
        float[] modelSize = vec2(modelJson, "size", 0F, 0F);
        for (JsonElement element : bonesArray) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException(source + ": controller bone must be an object");
            }
            JsonObject boneJson = element.getAsJsonObject();
            String name = string(boneJson, "name", "");
            if (name.isBlank()) {
                throw new IllegalArgumentException(source + ": controller bone is missing \"name\"");
            }
            String parent = string(boneJson, "parent", null);
            float[] pivot = vec2(boneJson, "pivot", 0F, 0F);
            BonePose rest = parseRestPose(boneJson);
            List<ControllerModel.Part> parts = parseParts(boneJson, source);
            bones.add(new ControllerModel.Bone(name, parent, pivot, rest, parts));
        }
        return new ControllerModel(bones, modelSize[0], modelSize[1]);
    }

    private static BonePose parseRestPose(JsonObject boneJson) {
        JsonObject transform = object(boneJson, "transform");
        if (transform == null) {
            return BonePose.IDENTITY;
        }
        float[] translation = vec2(transform, "translation", 0F, 0F);
        float[] rotation = vec3(transform, "rotation", 0F, 0F, 0F);
        float[] scale = vec2(transform, "scale", 1F, 1F);
        return new BonePose(translation, rotation, scale, true);
    }

    private static List<ControllerModel.Part> parseParts(JsonObject boneJson, Identifier source) {
        JsonArray partsArray = array(boneJson, "parts");
        if (partsArray == null) {
            return List.of();
        }
        List<ControllerModel.Part> parts = new ArrayList<>(partsArray.size());
        for (JsonElement element : partsArray) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException(source + ": controller part must be an object");
            }
            JsonObject part = element.getAsJsonObject();
            Identifier texture = textureId(requiredString(part, "texture", source), source);
            float[] uv = vec4(part, "uv", 0F, 0F, 1F, 1F);
            float[] size = vec2(part, "size", 1F, 1F);
            float[] offset = vec2(part, "offset", 0F, 0F);
            float z = number(part, "z", 0F);
            parts.add(new ControllerModel.Part(texture, uv[0], uv[1], uv[2], uv[3],
                    size[0], size[1], offset[0], offset[1], z));
        }
        return parts;
    }

    private static ControllerClip parseControllerClip(String name, JsonObject json, Identifier source) {
        JsonObject bonesJson = object(json, "bones");
        Map<String, ControllerClip.BoneTracks> tracks = new LinkedHashMap<>();
        float maxTime = 0F;
        if (bonesJson != null) {
            for (Map.Entry<String, JsonElement> entry : bonesJson.entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    throw new IllegalArgumentException(source + ": clip '" + name + "' bone '" + entry.getKey() + "' must be an object");
                }
                JsonObject boneJson = entry.getValue().getAsJsonObject();
                VectorTrack translation = vectorTrack(boneJson, "translation");
                VectorTrack rotation = vectorTrack(boneJson, "rotation");
                VectorTrack scale = vectorTrack(boneJson, "scale");
                BooleanTrack visible = booleanTrack(boneJson, "visible");
                tracks.put(entry.getKey(), new ControllerClip.BoneTracks(translation, rotation, scale, visible));
                maxTime = Math.max(maxTime, maxTime(translation, rotation, scale, visible));
            }
        }
        float duration = number(json, "animation_length", maxTime);
        if (duration <= 0F && maxTime > 0F) {
            duration = maxTime;
        }
        ClipHeader header = clipHeader(json);
        return new ControllerClip(duration, header.loop(), header.onEnd(), header.next(), header.transition(),
                tracks, soundCues(json, source), particleCues(json, source));
    }

    private static float maxTime(VectorTrack translation, VectorTrack rotation, VectorTrack scale, BooleanTrack visible) {
        return Math.max(Math.max(translation.maxTime(), rotation.maxTime()),
                Math.max(scale.maxTime(), visible.maxTime()));
    }

    private static VectorTrack vectorTrack(JsonObject boneJson, String key) {
        JsonObject channel = object(boneJson, key);
        if (channel == null) {
            return new VectorTrack(List.of());
        }
        List<Keyframe<float[]>> keys = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : channel.entrySet()) {
            float time = parseTime(entry.getKey());
            JsonElement value = entry.getValue();
            float[] vector;
            Easing easing = Easing.LINEAR;
            if (value.isJsonArray()) {
                vector = floats(value.getAsJsonArray());
            } else if (value.isJsonObject()) {
                JsonObject object = value.getAsJsonObject();
                JsonArray array = array(object, "vector");
                vector = array == null ? new float[]{0F} : floats(array);
                easing = Easing.parse(string(object, "easing", "linear"));
            } else {
                continue;
            }
            keys.add(new Keyframe<>(time, vector, easing));
        }
        return new VectorTrack(keys);
    }

    private static BooleanTrack booleanTrack(JsonObject boneJson, String key) {
        JsonObject channel = object(boneJson, key);
        if (channel == null) {
            return new BooleanTrack(List.of());
        }
        List<Keyframe<Boolean>> keys = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : channel.entrySet()) {
            keys.add(new Keyframe<>(parseTime(entry.getKey()), entry.getValue().getAsBoolean(), Easing.STEP));
        }
        return new BooleanTrack(keys);
    }

    // ------------------------------------------------------------------
    // Events and JSON helpers
    // ------------------------------------------------------------------

    private static List<AnimationCue.Sound> soundCues(JsonObject json, Identifier source) {
        JsonObject effects = object(json, "sound_effects");
        if (effects == null) {
            return List.of();
        }
        List<AnimationCue.Sound> cues = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : effects.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject object = entry.getValue().getAsJsonObject();
            String effect = string(object, "effect", string(object, "sound", ""));
            if (effect.isBlank()) {
                continue;
            }
            cues.add(new AnimationCue.Sound(parseTime(entry.getKey()), textureId(effect, source),
                    number(object, "volume", 1F), number(object, "pitch", 1F)));
        }
        return cues;
    }

    private static List<AnimationCue.Particle> particleCues(JsonObject json, Identifier source) {
        JsonObject effects = object(json, "particle_effects");
        if (effects == null) {
            return List.of();
        }
        List<AnimationCue.Particle> cues = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : effects.entrySet()) {
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject object = entry.getValue().getAsJsonObject();
            String effect = string(object, "effect", string(object, "particle", ""));
            if (effect.isBlank()) {
                continue;
            }
            cues.add(new AnimationCue.Particle(parseTime(entry.getKey()), textureId(effect, source),
                    string(object, "locator", "")));
        }
        return cues;
    }

    private static float parseTime(String value) {
        try {
            return Math.max(0F, Float.parseFloat(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid keyframe time: " + value, e);
        }
    }

    private static Identifier textureId(String raw, Identifier source) {
        String value = raw;
        if (value.endsWith(".png")) {
            value = value.substring(0, value.length() - 4);
        }
        try {
            return Identifier.parse(value);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(source + ": invalid resource identifier '" + raw + "'", e);
        }
    }

    private static String requiredString(JsonObject object, String key, Identifier source) {
        String value = string(object, key, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(source + ": missing required string field \"" + key + "\"");
        }
        return value;
    }

    private static String string(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        return object.get(key).getAsString();
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return fallback;
        }
        return object.get(key).getAsBoolean();
    }

    private static float number(JsonObject object, String key, float fallback) {
        return number(object, key, 0, fallback);
    }

    private static float number(JsonObject object, String key, int index, float fallback) {
        if (object == null || !object.has(key)) {
            return fallback;
        }
        JsonElement element = object.get(key);
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            if (index >= array.size()) {
                return fallback;
            }
            return array.get(index).getAsFloat();
        }
        return element.getAsFloat();
    }

    private static JsonObject object(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonObject()) {
            return null;
        }
        return object.getAsJsonObject(key);
    }

    private static JsonArray array(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonArray()) {
            return null;
        }
        return object.getAsJsonArray(key);
    }

    private static float[] vec2(JsonObject object, String key, float x, float y) {
        JsonArray array = array(object, key);
        if (array == null) {
            return new float[]{x, y};
        }
        return new float[]{array.size() > 0 ? array.get(0).getAsFloat() : x,
                array.size() > 1 ? array.get(1).getAsFloat() : y};
    }

    private static float[] vec3(JsonObject object, String key, float x, float y, float z) {
        JsonArray array = array(object, key);
        if (array == null) {
            return new float[]{x, y, z};
        }
        return new float[]{array.size() > 0 ? array.get(0).getAsFloat() : x,
                array.size() > 1 ? array.get(1).getAsFloat() : y,
                array.size() > 2 ? array.get(2).getAsFloat() : z};
    }

    private static float[] vec4(JsonObject object, String key, float x, float y, float z, float w) {
        JsonArray array = array(object, key);
        if (array == null) {
            return new float[]{x, y, z, w};
        }
        return new float[]{array.size() > 0 ? array.get(0).getAsFloat() : x,
                array.size() > 1 ? array.get(1).getAsFloat() : y,
                array.size() > 2 ? array.get(2).getAsFloat() : z,
                array.size() > 3 ? array.get(3).getAsFloat() : w};
    }

    private static float[] floats(JsonArray array) {
        float[] values = new float[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).getAsFloat();
        }
        return values;
    }
}
