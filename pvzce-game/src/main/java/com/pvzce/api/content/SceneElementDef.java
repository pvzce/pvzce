package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;
import java.util.Optional;

/**
 * A 1x1 scene element (grass, ground, water, flat/sloped roof, grave, crater...).
 *
 * <p>What may be planted on an element is decided entirely by tags
 * ({@code #c:ground}, {@code #c:plantable}, {@code #c:water},
 * {@code #c:unplantable} in the {@code scene_element} registry - see
 * {@code PlantPlacement}), not by a field here. An earlier version carried an
 * {@code accepts} list of free strings ("plantable", "flower_pot") that the server
 * switched on; it was a second, hand-maintained copy of the same rules and went
 * stale the moment a pack added a tile. Terrain is now data-in, tags-rule: define
 * the element, then tag it in {@code data/<ns>/tags/scene_element/}.
 *
 * <p>{@code maxHeight} gives the slope apex used by projectile hit tests on
 * roofs.
 *
 * <p>{@code liquid}, when present, names the {@link LiquidDef} whose shader renders
 * this element instead of a flat texture. It is an id rather than an embedded
 * definition so the renderer's parameters stay out of the simulation's content:
 * two elements can share one liquid, and a resource pack can restyle a liquid
 * without redefining the element. The one exception is {@link SceneElementArt},
 * which is a sprite, its size, and its day/night variants - facts about this
 * element and no other.
 */
public record SceneElementDef(
        Identifier id,
        String surfaceClass,
        float maxHeight,
        Optional<Identifier> liquid,
        Optional<SceneElementArt> art,
        boolean overlay,
        Optional<SurfaceProfile> profile
) implements com.pvzce.api.entity.LevelAccess.SceneElementAccess {
    public static final Codec<SceneElementDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("id").forGetter(SceneElementDef::id),
            Codec.STRING.fieldOf("surface").forGetter(SceneElementDef::surfaceClass),
            Codec.FLOAT.optionalFieldOf("max_height", 0F).forGetter(SceneElementDef::maxHeight),
            Identifier.CODEC.optionalFieldOf("liquid").forGetter(SceneElementDef::liquid),
            SceneElementArt.CODEC.optionalFieldOf("art").forGetter(SceneElementDef::art),
            Codec.BOOL.optionalFieldOf("overlay", false).forGetter(SceneElementDef::overlay),
            SurfaceProfile.CODEC.optionalFieldOf("profile").forGetter(SceneElementDef::profile)
    ).apply(i, SceneElementDef::new));

    public SceneElementDef(Identifier id, String surfaceClass, float maxHeight,
                           Optional<Identifier> liquid, Optional<SceneElementArt> art) {
        this(id, surfaceClass, maxHeight, liquid, art, false, Optional.empty());
    }

    /** Resolves old element definitions at the import boundary; runtime reads SceneBoard. */
    public SurfaceProfile profileFor(int width) {
        if (profile.isPresent()) return profile.get();
        if ("ROOF".equals(surfaceClass)) return SurfaceProfile.flat(com.pvzce.common.PvzceConstants.ROOF_HEIGHT);
        if ("ROOF_SLOPE".equals(surfaceClass)) return new SurfaceProfile(0F,
                com.pvzce.common.PvzceConstants.ROOF_HEIGHT / com.pvzce.common.PvzceConstants.ROOF_SLOPE_COLUMNS,
                0F, 0F, com.pvzce.common.PvzceConstants.ROOF_HEIGHT);
        return new SurfaceProfile(0F, width <= 1 ? 0F : maxHeight / (width - 1), 0F,
                -Float.MAX_VALUE, Float.MAX_VALUE);
    }

    /** An element with no liquid surface and no art of its own; the common case for land tiles. */
    public SceneElementDef(Identifier id, String surfaceClass, float maxHeight) {
        this(id, surfaceClass, maxHeight, Optional.empty(), Optional.empty());
    }

    /** An element with a liquid surface but no art of its own. */
    public SceneElementDef(Identifier id, String surfaceClass, float maxHeight,
                           Optional<Identifier> liquid) {
        this(id, surfaceClass, maxHeight, liquid, Optional.empty());
    }

    /** True when this element is drawn by the liquid renderer. */
    public boolean isLiquid() {
        return liquid.isPresent();
    }

    /** How this element is drawn, or {@link SceneElementArt#NONE} when the convention decides. */
    public SceneElementArt artOrDefault() {
        return art.orElse(SceneElementArt.NONE);
    }

    /** Native roof profile; other raised surfaces interpolate their declared maxHeight. */
    @Override
    public float heightAt(float x, int width) {
        return profileFor(width).at(x, 0F);
    }

    /** The shared roof profile used by simulation and mouse picking. */
    public static float roofHeightAt(float x) {
        return com.pvzce.common.PvzceConstants.ROOF_HEIGHT
                * Math.max(0F, Math.min(1F, x / com.pvzce.common.PvzceConstants.ROOF_SLOPE_COLUMNS));
    }
}
