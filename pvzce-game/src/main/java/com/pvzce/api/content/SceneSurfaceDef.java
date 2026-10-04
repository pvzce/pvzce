package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;
import java.util.List;
import java.util.Map;

/** One independently occupied ground/bridge surface; its scene map uses ordinary x,y cells. */
public record SceneSurfaceDef(Identifier id, String name, SurfaceProfile profile, float thickness,
                              Map<Identifier, List<String>> scene) {
    public static final Codec<SceneSurfaceDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(SceneSurfaceDef::id),
            Codec.STRING.optionalFieldOf("name", "").forGetter(SceneSurfaceDef::name),
            SurfaceProfile.CODEC.optionalFieldOf("profile", SurfaceProfile.FLAT).forGetter(SceneSurfaceDef::profile),
            Codec.FLOAT.optionalFieldOf("thickness", 0F).forGetter(SceneSurfaceDef::thickness),
            Codec.unboundedMap(Identifier.CODEC, Codec.STRING.listOf()).fieldOf("scene")
                    .forGetter(SceneSurfaceDef::scene)
    ).apply(i, SceneSurfaceDef::new));

    public SceneSurfaceDef {
        if (!Float.isFinite(thickness) || thickness < 0F) throw new IllegalArgumentException("Invalid surface thickness");
        Map<Identifier, List<String>> copy = new java.util.LinkedHashMap<>();
        scene.forEach((idKey, cells) -> copy.put(idKey, List.copyOf(cells)));
        scene = java.util.Collections.unmodifiableMap(copy);
    }
}
